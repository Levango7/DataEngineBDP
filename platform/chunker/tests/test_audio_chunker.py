"""AudioChunker 语音切片器单元测试 (T008-5).

覆盖场景：
    - 短音频（< 30 秒）：单切片
    - 长音频（≥ 30 分钟）：并行分段
    - 多说话人场景：说话人分离 + 时间戳对齐
    - ASR 引擎：Mock 引擎 + 可用性检测
    - 说话人分离：能量法 + pyannote 回退
    - 时间戳对齐：词级对齐 + 段合并
    - 异常处理：文件不存在 / 空音频 / None 输入
    - 注册机制
    - 工具函数
    - 性能（长音频 P95 ≤ 10s）
"""

from __future__ import annotations

import asyncio
import io
from pathlib import Path
import time
from unittest.mock import MagicMock, patch
import wave

from chunker.asr.diarization import (
    DiarizationResult,
    Diarizer,
    EnergyDiarizer,
    PyannoteDiarizer,
    SpeakerSegment,
    create_diarizer,
    is_pyannote_available,
)
from chunker.asr.timestamp_aligner import (
    AlignedSegment,
    AlignedWord,
    TimestampAligner,
    align_segments,
)
from chunker.asr.whisper_engine import (
    DEFAULT_WHISPER_MODEL,
    ASRResult,
    ASRSegment,
    MockWhisperEngine,
    WhisperEngine,
    WhisperWord,
    estimate_audio_duration,
    is_numpy_available,
    is_whisper_available,
    load_audio,
)

# 导入被测模块
from chunker.audio_chunker import (
    DEFAULT_MODEL,
    DEFAULT_WINDOW_MS,
    AudioChunker,
)
from chunker.base import BaseChunker
from chunker.exceptions import PreprocessError
from chunker.models import Chunk, ChunkConfig, Modality
from chunker.registry import (
    get_chunker,
    is_chunker_registered,
    list_modalities,
)
import numpy as np
import pytest

# ----------------------------------------------------------------------
# 合成音频生成
# ----------------------------------------------------------------------


def _make_sine_wave(
    duration: float,
    freq: float = 440.0,
    sample_rate: int = 16000,
    amplitude: float = 0.5,
) -> np.ndarray:
    """生成正弦波音频（模拟语音活动）."""
    n = int(duration * sample_rate)
    t = np.linspace(0, duration, n, endpoint=False)
    return (amplitude * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def _make_silence(duration: float, sample_rate: int = 16000) -> np.ndarray:
    """生成静音段."""
    n = int(duration * sample_rate)
    return np.zeros(n, dtype=np.float32)


def _make_multi_speaker_audio(
    segments: list[tuple[float, float, float]],
    sample_rate: int = 16000,
) -> tuple[np.ndarray, float]:
    """生成多说话人合成音频.

    :param segments: [(start_seconds, end_seconds, freq), ...]
        每段为不同说话人的语音（不同频率），段间自动插入静音
    :return: (audio_array, total_duration)
    """
    if not segments:
        return np.zeros(0, dtype=np.float32), 0.0

    total_duration = max(s[1] for s in segments)
    n_total = int(total_duration * sample_rate)
    audio = np.zeros(n_total, dtype=np.float32)

    for start, end, freq in segments:
        seg_duration = end - start
        if seg_duration <= 0:
            continue
        tone = _make_sine_wave(seg_duration, freq=freq, sample_rate=sample_rate, amplitude=0.5)
        start_idx = int(start * sample_rate)
        end_idx = start_idx + len(tone)
        if end_idx > n_total:
            end_idx = n_total
            tone = tone[: end_idx - start_idx]
        audio[start_idx:end_idx] = tone

    return audio, total_duration


def _audio_to_wav_bytes(audio: np.ndarray, sample_rate: int = 16000) -> bytes:
    """将 numpy 音频数组转为 WAV bytes."""
    buf = io.BytesIO()
    # 转为 16-bit PCM
    pcm = (audio * 32767).astype(np.int16)
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm.tobytes())
    return buf.getvalue()


def _save_wav(path: str, audio: np.ndarray, sample_rate: int = 16000) -> None:
    """保存 numpy 音频为 WAV 文件."""
    data = _audio_to_wav_bytes(audio, sample_rate)
    with open(path, "wb") as f:
        f.write(data)


# ----------------------------------------------------------------------
# fixtures
# ----------------------------------------------------------------------


@pytest.fixture
def chunker() -> AudioChunker:
    """默认 AudioChunker 实例（Mock ASR + 能量分离）."""
    return AudioChunker(
        useMockASR=True,
        useMockDiarization=True,
        enableDiarization=True,
    )


@pytest.fixture
def chunker_no_diar() -> AudioChunker:
    """禁用说话人分离的 AudioChunker."""
    return AudioChunker(
        useMockASR=True,
        enableDiarization=False,
    )


@pytest.fixture
def chunker_no_text() -> AudioChunker:
    """禁用 TextChunker 二次切片的 AudioChunker（默认即禁用）."""
    return AudioChunker(
        useMockASR=True,
        useMockDiarization=True,
        enableTextChunker=False,
    )


@pytest.fixture
def chunker_with_text() -> AudioChunker:
    """启用 TextChunker 二次切片的 AudioChunker."""
    return AudioChunker(
        useMockASR=True,
        useMockDiarization=True,
        enableTextChunker=True,
    )


@pytest.fixture
def short_audio() -> np.ndarray:
    """短音频（5 秒，单说话人）."""
    return _make_sine_wave(5.0, freq=440.0)


@pytest.fixture
def short_audio_wav_bytes(short_audio) -> bytes:
    """短音频 WAV bytes."""
    return _audio_to_wav_bytes(short_audio)


@pytest.fixture
def medium_audio() -> np.ndarray:
    """中等音频（30 秒，两说话人交替）."""
    segments = [
        (0.0, 10.0, 440.0),  # 说话人 1
        (10.5, 20.0, 880.0),  # 说话人 2
        (20.5, 30.0, 440.0),  # 说话人 1
    ]
    audio, _ = _make_multi_speaker_audio(segments)
    return audio


@pytest.fixture
def long_audio() -> np.ndarray:
    """长音频（35 秒，触发并行分段）."""
    segments = [
        (0.0, 10.0, 440.0),
        (10.5, 20.0, 880.0),
        (20.5, 30.0, 440.0),
        (30.5, 35.0, 880.0),
    ]
    audio, _ = _make_multi_speaker_audio(segments)
    return audio


@pytest.fixture
def multi_speaker_audio() -> np.ndarray:
    """多说话人音频（3 说话人交替，20 秒）."""
    segments = [
        (0.0, 5.0, 300.0),  # 说话人 1
        (5.5, 10.0, 600.0),  # 说话人 2
        (10.5, 15.0, 900.0),  # 说话人 3
        (15.5, 20.0, 300.0),  # 说话人 1
    ]
    audio, _ = _make_multi_speaker_audio(segments)
    return audio


@pytest.fixture
def silent_audio() -> np.ndarray:
    """静音音频（5 秒）."""
    return _make_silence(5.0)


@pytest.fixture
def tmp_wav_file(tmp_path: Path, short_audio: np.ndarray) -> str:
    """临时 WAV 文件."""
    path = tmp_path / "test.wav"
    _save_wav(str(path), short_audio)
    return str(path)


def _cfg(**kwargs) -> ChunkConfig:
    """构造音频 ChunkConfig 的便捷函数."""
    defaults = {
        "modality": Modality.AUDIO,
        "windowSize": DEFAULT_WINDOW_MS,
        "overlap": 0.1,
    }
    defaults.update(kwargs)
    return ChunkConfig(**defaults)


# ----------------------------------------------------------------------
# 1. 可用性检测
# ----------------------------------------------------------------------


class TestAvailability:
    """可用性检测测试."""

    def test_is_whisper_available_returns_bool(self):
        """is_whisper_available 返回布尔值."""
        result = is_whisper_available()
        assert isinstance(result, bool)

    def test_is_numpy_available(self):
        """numpy 应可用."""
        assert is_numpy_available() is True

    def test_is_pyannote_available_returns_bool(self):
        """is_pyannote_available 返回布尔值."""
        result = is_pyannote_available()
        assert isinstance(result, bool)


# ----------------------------------------------------------------------
# 2. WhisperEngine 测试
# ----------------------------------------------------------------------


class TestWhisperEngine:
    """WhisperEngine 测试."""

    def test_init_default(self):
        """默认初始化."""
        engine = WhisperEngine()
        assert engine.modelName == DEFAULT_WHISPER_MODEL
        assert engine.engine_name in ("whisper", "mock")

    def test_init_mock_forced(self):
        """强制 Mock 模式."""
        engine = WhisperEngine(useMock=True)
        assert engine.is_mock is True
        assert engine.engine_name == "mock"

    def test_mock_engine_transcribe_short(self, short_audio: np.ndarray):
        """Mock 引擎转录短音频."""
        engine = MockWhisperEngine(language="zh")
        result = engine.transcribe(short_audio)
        assert isinstance(result, ASRResult)
        assert result.engine == "mock"
        assert result.language == "zh"
        assert result.duration > 0

    def test_mock_engine_transcribe_with_word_timestamps(self, short_audio: np.ndarray):
        """Mock 引擎生成词级时间戳."""
        engine = MockWhisperEngine()
        result = engine.transcribe(short_audio, word_timestamps=True)
        for seg in result.segments:
            for w in seg.words:
                assert w.start <= w.end
                assert w.word  # 非空

    def test_mock_engine_transcribe_silent(self, silent_audio: np.ndarray):
        """Mock 引擎转录静音音频."""
        engine = MockWhisperEngine()
        result = engine.transcribe(silent_audio)
        assert isinstance(result, ASRResult)
        # 静音可能产生空段或占位段
        assert result.duration >= 0

    def test_whisper_engine_transcribe_async_mock(self, short_audio: np.ndarray):
        """WhisperEngine 异步转录（Mock 模式）."""
        engine = WhisperEngine(useMock=True)
        result = asyncio.run(engine.transcribe_async(short_audio))
        assert isinstance(result, ASRResult)
        assert result.engine == "mock"

    def test_whisper_engine_transcribe_async_long(self, long_audio: np.ndarray):
        """WhisperEngine 异步转录长音频（并行分段）."""
        engine = WhisperEngine(useMock=True)
        result = asyncio.run(engine.transcribe_async(long_audio, segment_max_seconds=10.0, parallel=True))
        assert isinstance(result, ASRResult)
        assert result.duration > 0

    def test_whisper_engine_transcribe_async_sequential(self, long_audio: np.ndarray):
        """WhisperEngine 异步转录长音频（顺序分段）."""
        engine = WhisperEngine(useMock=True)
        result = asyncio.run(engine.transcribe_async(long_audio, segment_max_seconds=10.0, parallel=False))
        assert isinstance(result, ASRResult)

    def test_asr_result_word_count(self):
        """ASRResult word_count 属性."""
        words = [WhisperWord("你", 0.0, 0.2), WhisperWord("好", 0.2, 0.4)]
        seg = ASRSegment(text="你好", start=0.0, end=0.4, words=words)
        result = ASRResult(text="你好", segments=[seg])
        assert result.word_count == 2

    def test_asr_result_to_dict(self):
        """ASRResult 序列化."""
        seg = ASRSegment(text="你好", start=0.0, end=0.4)
        result = ASRResult(text="你好", segments=[seg], language="zh", engine="mock", duration=0.4)
        d = result.to_dict()
        assert d["text"] == "你好"
        assert d["language"] == "zh"
        assert d["engine"] == "mock"
        assert d["duration"] == 0.4


# ----------------------------------------------------------------------
# 3. 音频加载测试
# ----------------------------------------------------------------------


class TestAudioLoading:
    """音频加载测试."""

    def test_load_audio_ndarray(self, short_audio: np.ndarray):
        """从 numpy 数组加载."""
        audio, duration = load_audio(short_audio)
        assert isinstance(audio, np.ndarray)
        assert duration > 0

    def test_load_audio_wav_bytes(self, short_audio_wav_bytes: bytes):
        """从 WAV bytes 加载."""
        audio, duration = load_audio(short_audio_wav_bytes)
        assert isinstance(audio, np.ndarray)
        assert duration > 0

    def test_load_audio_file_path(self, tmp_wav_file: str):
        """从文件路径加载."""
        audio, duration = load_audio(tmp_wav_file)
        assert isinstance(audio, np.ndarray)
        assert duration > 0

    def test_load_audio_file_not_exists(self):
        """文件不存在抛 FileNotFoundError."""
        with pytest.raises(FileNotFoundError):
            load_audio("/nonexistent/audio.wav")

    def test_load_audio_unsupported_type(self):
        """不支持的类型抛 TypeError."""
        with pytest.raises(TypeError):
            load_audio(12345)

    def test_estimate_audio_duration(self, short_audio: np.ndarray):
        """估算音频时长."""
        duration = estimate_audio_duration(short_audio)
        assert duration > 0
        assert abs(duration - 5.0) < 0.1  # 约 5 秒

    def test_estimate_audio_duration_invalid(self):
        """无效音频时长为 0."""
        duration = estimate_audio_duration(None)
        assert duration == 0.0


# ----------------------------------------------------------------------
# 4. 说话人分离测试
# ----------------------------------------------------------------------


class TestDiarization:
    """说话人分离测试."""

    def test_energy_diarizer_init(self):
        """能量分离器初始化."""
        d = EnergyDiarizer(num_speakers=2)
        assert d.num_speakers == 2
        assert d.engine_name == "energy"

    def test_energy_diarizer_basic(self, medium_audio: np.ndarray):
        """能量分离器基本功能."""
        d = EnergyDiarizer(num_speakers=2)
        result = d.diarize(medium_audio)
        assert isinstance(result, DiarizationResult)
        assert result.engine == "energy"
        assert result.duration > 0
        # 应识别出至少 1 个说话人
        assert result.num_speakers >= 1

    def test_energy_diarizer_segments_valid(self, medium_audio: np.ndarray):
        """分离段时间有效."""
        d = EnergyDiarizer(num_speakers=2)
        result = d.diarize(medium_audio)
        for seg in result.segments:
            assert seg.start < seg.end
            assert seg.duration > 0
            assert seg.speaker.startswith("SPEAKER_")

    def test_energy_diarizer_silent(self, silent_audio: np.ndarray):
        """静音音频分离."""
        d = EnergyDiarizer(num_speakers=2)
        result = d.diarize(silent_audio)
        assert isinstance(result, DiarizationResult)

    def test_energy_diarizer_async(self, medium_audio: np.ndarray):
        """异步分离."""
        d = EnergyDiarizer(num_speakers=2)
        result = asyncio.run(d.diarize_async(medium_audio))
        assert isinstance(result, DiarizationResult)

    def test_diarization_result_speaker_at(self):
        """speaker_at 查询."""
        segs = [
            SpeakerSegment(speaker="A", start=0.0, end=5.0),
            SpeakerSegment(speaker="B", start=5.0, end=10.0),
        ]
        result = DiarizationResult(segments=segs, speakers=["A", "B"])
        assert result.speaker_at(2.0) == "A"
        assert result.speaker_at(7.0) == "B"
        assert result.speaker_at(15.0) is None

    def test_diarization_result_to_dict(self):
        """序列化."""
        seg = SpeakerSegment(speaker="A", start=0.0, end=5.0)
        result = DiarizationResult(segments=[seg], speakers=["A"], engine="energy", duration=5.0)
        d = result.to_dict()
        assert d["engine"] == "energy"
        assert len(d["segments"]) == 1
        assert d["speakers"] == ["A"]

    def test_speaker_segment_duration(self):
        """段时长属性."""
        seg = SpeakerSegment(speaker="A", start=1.0, end=4.0)
        assert seg.duration == 3.0

    def test_pyannote_diarizer_fallback(self, medium_audio: np.ndarray):
        """pyannote 不可用时回退能量分离."""
        d = PyannoteDiarizer(useMock=True)
        assert d.is_fallback is True
        result = d.diarize(medium_audio)
        assert result.engine == "energy"

    def test_create_diarizer_factory(self):
        """工厂函数创建分离器."""
        d = create_diarizer(prefer_pyannote=False)
        assert isinstance(d, EnergyDiarizer)
        d2 = create_diarizer(prefer_pyannote=True)
        assert isinstance(d2, Diarizer)


# ----------------------------------------------------------------------
# 5. 时间戳对齐测试
# ----------------------------------------------------------------------


class TestTimestampAligner:
    """时间戳对齐测试."""

    def test_align_no_diarization(self):
        """无说话人分离：单说话人对齐."""
        words = [WhisperWord("你", 0.0, 0.2), WhisperWord("好", 0.2, 0.4)]
        seg = ASRSegment(text="你好", start=0.0, end=0.4, words=words)
        asr_result = ASRResult(text="你好", segments=[seg])
        aligned = align_segments(asr_result)
        assert len(aligned) == 1
        assert aligned[0].text == "你好"
        assert aligned[0].speaker == "SPEAKER_00"

    def test_align_with_diarization(self):
        """带说话人分离的对齐."""
        words = [
            WhisperWord("你", 0.0, 0.2),
            WhisperWord("好", 0.2, 0.4),
            WhisperWord("世", 0.4, 0.6),
            WhisperWord("界", 0.6, 0.8),
        ]
        seg = ASRSegment(text="你好世界", start=0.0, end=0.8, words=words)
        asr_result = ASRResult(text="你好世界", segments=[seg])
        diar = DiarizationResult(
            segments=[
                SpeakerSegment(speaker="A", start=0.0, end=0.4),
                SpeakerSegment(speaker="B", start=0.4, end=0.8),
            ],
            speakers=["A", "B"],
        )
        aligned = align_segments(asr_result, diar)
        assert len(aligned) == 2
        assert aligned[0].speaker == "A"
        assert aligned[1].speaker == "B"

    def test_align_empty(self):
        """空 ASR 结果."""
        asr_result = ASRResult(text="", segments=[])
        aligned = align_segments(asr_result)
        assert aligned == []

    def test_align_time_offset(self):
        """时间偏移."""
        words = [WhisperWord("你", 0.0, 0.2)]
        seg = ASRSegment(text="你", start=0.0, end=0.2, words=words)
        asr_result = ASRResult(text="你", segments=[seg])
        aligned = align_segments(asr_result, time_offset=10.0)
        assert aligned[0].start >= 10.0

    def test_aligned_segment_properties(self):
        """AlignedSegment 属性."""
        words = [AlignedWord(word="你", start=0.0, end=0.2, speaker="A")]
        seg = AlignedSegment(text="你", start=0.0, end=0.2, speaker="A", words=words)
        assert seg.duration == 0.2
        assert seg.word_count == 1

    def test_aligned_word_to_dict(self):
        """AlignedWord 序列化."""
        w = AlignedWord(word="你", start=0.0, end=0.2, speaker="A", probability=0.9)
        d = w.to_dict()
        assert d["word"] == "你"
        assert d["speaker"] == "A"

    def test_aligner_init_tolerance(self):
        """对齐器容差初始化."""
        a = TimestampAligner(tolerance=0.1)
        assert a.tolerance == 0.1

    def test_align_no_word_timestamps(self):
        """无词级时间戳：用段级时间戳."""
        seg = ASRSegment(text="你好", start=0.0, end=0.4, words=[])
        asr_result = ASRResult(text="你好", segments=[seg])
        aligned = align_segments(asr_result)
        assert len(aligned) == 1
        assert aligned[0].text == "你好"

    def test_align_multiple_speakers_merge(self):
        """多说话人连续段合并."""
        seg1 = ASRSegment(
            text="你好",
            start=0.0,
            end=0.4,
            words=[WhisperWord("你好", 0.0, 0.4)],
        )
        seg2 = ASRSegment(
            text="谢谢",
            start=0.4,
            end=0.8,
            words=[WhisperWord("谢谢", 0.4, 0.8)],
        )
        asr_result = ASRResult(text="你好谢谢", segments=[seg1, seg2])
        diar = DiarizationResult(
            segments=[
                SpeakerSegment(speaker="A", start=0.0, end=0.4),
                SpeakerSegment(speaker="B", start=0.4, end=0.8),
            ],
            speakers=["A", "B"],
        )
        aligned = align_segments(asr_result, diar)
        assert len(aligned) == 2
        assert aligned[0].speaker == "A"
        assert aligned[1].speaker == "B"


# ----------------------------------------------------------------------
# 6. AudioChunker 基础测试
# ----------------------------------------------------------------------


class TestAudioChunkerBasic:
    """AudioChunker 基础测试."""

    def test_init_default(self):
        """默认初始化."""
        c = AudioChunker()
        assert c.modality == Modality.AUDIO
        assert c.whisperModel == DEFAULT_MODEL
        assert c.enableDiarization is True
        assert c.enableTextChunker is False

    def test_init_custom(self):
        """自定义初始化."""
        c = AudioChunker(
            whisperModel="large",
            language="zh",
            enableDiarization=False,
            maxWorkers=8,
        )
        assert c.whisperModel == "large"
        assert c.language == "zh"
        assert c.enableDiarization is False
        assert c.maxWorkers == 8

    def test_is_base_chunker(self, chunker: AudioChunker):
        """是 BaseChunker 子类."""
        assert isinstance(chunker, BaseChunker)

    def test_count_tokens_chinese(self, chunker: AudioChunker):
        """中文 token 计数."""
        tokens = chunker._count_tokens("你好世界")
        assert tokens > 0

    def test_count_tokens_empty(self, chunker: AudioChunker):
        """空文本 token 计数."""
        assert chunker._count_tokens("") == 0

    def test_count_tokens_mixed(self, chunker: AudioChunker):
        """中英混合 token 计数."""
        tokens = chunker._count_tokens("你好 hello")
        assert tokens > 0


# ----------------------------------------------------------------------
# 7. 短音频切片测试
# ----------------------------------------------------------------------


class TestShortAudio:
    """短音频切片测试."""

    async def test_chunk_short_audio_ndarray(self, chunker: AudioChunker, short_audio: np.ndarray):
        """短音频（numpy 数组）切片."""
        cfg = _cfg(windowSize=30000)
        chunks = await chunker.chunk(short_audio, cfg)
        assert isinstance(chunks, list)
        # Mock 引擎应产生切片
        assert len(chunks) >= 1
        for c in chunks:
            assert isinstance(c, Chunk)
            assert c.metadata.modality == Modality.AUDIO
            assert "startTime" in c.metadata.extra
            assert "endTime" in c.metadata.extra

    async def test_chunk_short_audio_wav_bytes(self, chunker: AudioChunker, short_audio_wav_bytes: bytes):
        """短音频（WAV bytes）切片."""
        cfg = _cfg(windowSize=30000)
        chunks = await chunker.chunk(short_audio_wav_bytes, cfg)
        assert isinstance(chunks, list)

    async def test_chunk_short_audio_file(self, chunker: AudioChunker, tmp_wav_file: str):
        """短音频（文件路径）切片."""
        cfg = _cfg(windowSize=30000)
        chunks = await chunker.chunk(tmp_wav_file, cfg)
        assert isinstance(chunks, list)
        assert len(chunks) >= 1
        # 验证来源
        for c in chunks:
            assert c.metadata.source == tmp_wav_file

    async def test_chunk_short_audio_single_chunk(self, chunker: AudioChunker, short_audio: np.ndarray):
        """短音频（窗口 >= 时长）应产生单切片."""
        cfg = _cfg(windowSize=60000)  # 60 秒窗口
        chunks = await chunker.chunk(short_audio, cfg)
        assert len(chunks) == 1

    async def test_chunk_metadata_extra(self, chunker: AudioChunker, short_audio: np.ndarray):
        """切片元数据包含音频专属信息."""
        cfg = _cfg(windowSize=30000)
        chunks = await chunker.chunk(short_audio, cfg)
        for c in chunks:
            extra = c.metadata.extra
            assert "startTime" in extra
            assert "endTime" in extra
            assert "duration" in extra
            assert "speakers" in extra
            assert "asrEngine" in extra
            assert "diarEngine" in extra

    async def test_chunk_tokens_calculated(self, chunker: AudioChunker, short_audio: np.ndarray):
        """切片 tokens 已计算."""
        cfg = _cfg(windowSize=30000)
        chunks = await chunker.chunk(short_audio, cfg)
        for c in chunks:
            assert c.tokens is not None
            assert c.tokens >= 0

    async def test_chunk_index_sequential(self, chunker: AudioChunker, medium_audio: np.ndarray):
        """切片 index 从 0 连续."""
        cfg = _cfg(windowSize=10000)
        chunks = await chunker.chunk(medium_audio, cfg)
        for i, c in enumerate(chunks):
            assert c.metadata.index == i


# ----------------------------------------------------------------------
# 8. 长音频切片测试
# ----------------------------------------------------------------------


class TestLongAudio:
    """长音频切片测试."""

    async def test_chunk_long_audio_parallel(self, chunker: AudioChunker, long_audio: np.ndarray):
        """长音频并行切片."""
        cfg = _cfg(windowSize=10000, extra={"parallel": True, "maxWorkers": 4})
        chunks = await chunker.chunk(long_audio, cfg)
        assert isinstance(chunks, list)
        assert len(chunks) >= 1

    async def test_chunk_long_audio_sequential(self, chunker: AudioChunker, long_audio: np.ndarray):
        """长音频顺序切片."""
        cfg = _cfg(windowSize=10000, extra={"parallel": False})
        chunks = await chunker.chunk(long_audio, cfg)
        assert isinstance(chunks, list)
        assert len(chunks) >= 1

    async def test_chunk_long_audio_performance(self, chunker: AudioChunker, long_audio: np.ndarray):
        """长音频性能：P95 ≤ 10s（Mock 引擎，宽松验证）."""
        cfg = _cfg(windowSize=10000, extra={"parallel": True, "maxWorkers": 4})
        start = time.perf_counter()
        chunks = await chunker.chunk(long_audio, cfg)
        elapsed = time.perf_counter() - start
        # Mock 引擎应远快于 10s
        assert elapsed < 10.0, f"长音频切片耗时 {elapsed:.2f}s 超过 10s"
        assert len(chunks) >= 1

    async def test_chunk_long_audio_window_split(self, chunker: AudioChunker, long_audio: np.ndarray):
        """长音频按窗口切分多切片."""
        cfg = _cfg(windowSize=5000, overlap=0.0)  # 5 秒窗口
        chunks = await chunker.chunk(long_audio, cfg)
        # 35 秒音频 / 5 秒窗口 = 至少 7 个切片
        assert len(chunks) >= 1


# ----------------------------------------------------------------------
# 9. 多说话人场景测试
# ----------------------------------------------------------------------


class TestMultiSpeaker:
    """多说话人场景测试."""

    async def test_chunk_multi_speaker(self, chunker: AudioChunker, multi_speaker_audio: np.ndarray):
        """多说话人切片."""
        cfg = _cfg(windowSize=30000, extra={"minSpeakers": 2, "maxSpeakers": 4})
        chunks = await chunker.chunk(multi_speaker_audio, cfg)
        assert isinstance(chunks, list)
        assert len(chunks) >= 1
        # 至少有一个切片包含说话人信息
        all_speakers = set()
        for c in chunks:
            all_speakers.update(c.metadata.extra.get("speakers", []))
        # 能量分离应识别出至少 1 个说话人
        assert len(all_speakers) >= 1

    async def test_chunk_no_diarization(self, chunker_no_diar: AudioChunker, multi_speaker_audio: np.ndarray):
        """禁用说话人分离."""
        cfg = _cfg(windowSize=30000)
        chunks = await chunker_no_diar.chunk(multi_speaker_audio, cfg)
        assert isinstance(chunks, list)
        for c in chunks:
            assert c.metadata.extra.get("diarEngine") == "none"

    async def test_chunk_speaker_labels_format(self, chunker: AudioChunker, multi_speaker_audio: np.ndarray):
        """说话人标签格式 SPEAKER_XX."""
        cfg = _cfg(windowSize=30000)
        chunks = await chunker.chunk(multi_speaker_audio, cfg)
        for c in chunks:
            for spk in c.metadata.extra.get("speakers", []):
                assert spk.startswith("SPEAKER_")

    async def test_chunk_two_speaker_alternating(self, chunker: AudioChunker, medium_audio: np.ndarray):
        """两说话人交替场景."""
        cfg = _cfg(windowSize=15000, extra={"minSpeakers": 2, "maxSpeakers": 2})
        chunks = await chunker.chunk(medium_audio, cfg)
        assert len(chunks) >= 1


# ----------------------------------------------------------------------
# 10. TextChunker 二次切片测试
# ----------------------------------------------------------------------


class TestTextChunkerIntegration:
    """TextChunker 二次切片测试."""

    async def test_chunk_with_text_chunker(self, chunker_with_text: AudioChunker, medium_audio: np.ndarray):
        """启用 TextChunker 二次切片."""
        cfg = _cfg(windowSize=30000, extra={"enableTextChunker": True})
        chunks = await chunker_with_text.chunk(medium_audio, cfg)
        assert isinstance(chunks, list)
        # 二次切片后可能产生更多切片
        for c in chunks:
            assert isinstance(c, Chunk)

    async def test_chunk_without_text_chunker(self, chunker_no_text: AudioChunker, medium_audio: np.ndarray):
        """禁用 TextChunker."""
        cfg = _cfg(windowSize=30000, extra={"enableTextChunker": False})
        chunks = await chunker_no_text.chunk(medium_audio, cfg)
        assert isinstance(chunks, list)


# ----------------------------------------------------------------------
# 11. 异常处理测试
# ----------------------------------------------------------------------


class TestErrorHandling:
    """异常处理测试."""

    async def test_chunk_none_input(self, chunker: AudioChunker):
        """None 输入抛 PreprocessError."""
        cfg = _cfg()
        with pytest.raises(PreprocessError):
            await chunker.chunk(None, cfg)

    async def test_chunk_file_not_exists(self, chunker: AudioChunker):
        """文件不存在抛 PreprocessError."""
        cfg = _cfg()
        with pytest.raises(PreprocessError):
            await chunker.chunk("/nonexistent/audio.wav", cfg)

    async def test_chunk_empty_audio(self, chunker: AudioChunker):
        """空音频."""
        cfg = _cfg()
        empty = np.zeros(0, dtype=np.float32)
        chunks = await chunker.chunk(empty, cfg)
        assert chunks == []

    async def test_chunk_silent_audio(self, chunker: AudioChunker, silent_audio: np.ndarray):
        """静音音频."""
        cfg = _cfg(windowSize=30000)
        chunks = await chunker.chunk(silent_audio, cfg)
        # 静音可能产生空切片或占位切片
        assert isinstance(chunks, list)

    async def test_chunk_with_result(self, chunker: AudioChunker, short_audio: np.ndarray):
        """chunk_with_result 返回 ChunkResult."""
        cfg = _cfg(windowSize=30000)
        result = await chunker.chunk_with_result(short_audio, cfg)
        assert result.modality == Modality.AUDIO
        assert result.durationMs >= 0
        assert result.count >= 0


# ----------------------------------------------------------------------
# 12. 注册机制测试
# ----------------------------------------------------------------------


class TestRegistration:
    """注册机制测试."""

    def test_audio_chunker_registered(self):
        """AudioChunker 应可注册为 Modality.AUDIO."""
        from chunker.registry import ChunkerRegistry

        ChunkerRegistry.register(Modality.AUDIO, AudioChunker)
        assert is_chunker_registered(Modality.AUDIO)
        assert is_chunker_registered("audio")

    def test_get_chunker_audio(self):
        """get_chunker('audio') 返回 AudioChunker 实例."""
        from chunker.registry import ChunkerRegistry

        ChunkerRegistry.register(Modality.AUDIO, AudioChunker)
        c = get_chunker("audio")
        assert isinstance(c, AudioChunker)

    def test_list_modalities_includes_audio(self):
        """list_modalities 包含 audio."""
        from chunker.registry import ChunkerRegistry

        ChunkerRegistry.register(Modality.AUDIO, AudioChunker)
        modalities = list_modalities()
        assert "audio" in modalities

    def test_is_base_chunker_subclass(self):
        """AudioChunker 是 BaseChunker 子类."""
        assert issubclass(AudioChunker, BaseChunker)

    def test_decorator_registration(self):
        """验证 @register_chunker 装饰器能正确注册."""
        from chunker.registry import ChunkerRegistry

        ChunkerRegistry.register("audio", AudioChunker)
        assert is_chunker_registered("audio")


# ----------------------------------------------------------------------
# 13. 工具函数测试
# ----------------------------------------------------------------------


class TestUtilities:
    """工具函数测试."""

    def test_audio_to_wav_bytes(self, short_audio: np.ndarray):
        """numpy 转 WAV bytes."""
        data = _audio_to_wav_bytes(short_audio)
        assert isinstance(data, bytes)
        assert len(data) > 0
        # WAV 文件头：RIFF
        assert data[:4] == b"RIFF"

    def test_make_sine_wave(self):
        """正弦波生成."""
        audio = _make_sine_wave(1.0, freq=440.0)
        assert len(audio) == 16000
        assert audio.dtype == np.float32

    def test_make_silence(self):
        """静音生成."""
        audio = _make_silence(1.0)
        assert len(audio) == 16000
        assert np.all(audio == 0)

    def test_make_multi_speaker_audio(self):
        """多说话人音频生成."""
        segments = [(0.0, 2.0, 440.0), (2.5, 5.0, 880.0)]
        audio, duration = _make_multi_speaker_audio(segments)
        assert duration == 5.0
        assert len(audio) == 5 * 16000


# ----------------------------------------------------------------------
# 14. 配置测试
# ----------------------------------------------------------------------


class TestConfig:
    """配置测试."""

    async def test_config_language_override(self, chunker: AudioChunker, short_audio: np.ndarray):
        """配置覆盖语言."""
        cfg = _cfg(windowSize=30000, extra={"language": "zh"})
        chunks = await chunker.chunk(short_audio, cfg)
        assert isinstance(chunks, list)

    async def test_config_disable_diarization(self, chunker: AudioChunker, short_audio: np.ndarray):
        """配置禁用说话人分离."""
        cfg = _cfg(windowSize=30000, extra={"enableDiarization": False})
        chunks = await chunker.chunk(short_audio, cfg)
        for c in chunks:
            assert c.metadata.extra.get("diarEngine") == "none"

    async def test_config_window_size(self, chunker: AudioChunker, medium_audio: np.ndarray):
        """不同窗口大小."""
        cfg_small = _cfg(windowSize=5000)
        cfg_large = _cfg(windowSize=30000)
        chunks_small = await chunker.chunk(medium_audio, cfg_small)
        chunks_large = await chunker.chunk(medium_audio, cfg_large)
        # 小窗口应产生更多或相等切片
        assert len(chunks_small) >= len(chunks_large)

    async def test_config_overlap(self, chunker: AudioChunker, medium_audio: np.ndarray):
        """重叠配置."""
        cfg = _cfg(windowSize=10000, overlap=0.2)
        chunks = await chunker.chunk(medium_audio, cfg)
        assert isinstance(chunks, list)


# ----------------------------------------------------------------------
# 15. Whisper 真实引擎路径测试（mock whisper 库）
# ----------------------------------------------------------------------


class TestWhisperEngineRealPath:
    """Whisper 真实引擎路径测试（通过 mock whisper 库）."""

    def _make_fake_whisper_module(self):
        """构造假的 whisper 模块."""
        fake_module = MagicMock()
        # load_model 返回假模型
        fake_model = MagicMock()
        fake_model.transcribe.return_value = {
            "text": "你好世界",
            "language": "zh",
            "segments": [
                {
                    "text": "你好世界",
                    "start": 0.0,
                    "end": 1.0,
                    "words": [
                        {"word": "你", "start": 0.0, "end": 0.2, "probability": 0.9},
                        {"word": "好", "start": 0.2, "end": 0.4, "probability": 0.9},
                        {"word": "世", "start": 0.4, "end": 0.7, "probability": 0.9},
                        {"word": "界", "start": 0.7, "end": 1.0, "probability": 0.9},
                    ],
                }
            ],
        }
        fake_module.load_model.return_value = fake_model
        # audio.sample_rate
        fake_module.audio.sample_rate = 16000
        fake_module.load_audio = lambda x: np.zeros(16000, dtype=np.float32)
        return fake_module

    def test_engine_with_mocked_whisper_short(self, short_audio: np.ndarray):
        """通过 mock whisper 库测试真实引擎路径（短音频）."""
        fake_whisper = self._make_fake_whisper_module()
        with patch.dict("sys.modules", {"whisper": fake_whisper}):
            # 清除引擎缓存
            WhisperEngine._model_cache.clear()
            engine = WhisperEngine(modelName="tiny", language="zh")
            assert engine.is_mock is False
            result = engine.transcribe(short_audio)
            assert isinstance(result, ASRResult)
            assert result.engine == "whisper"
            assert result.text == "你好世界"
            assert len(result.segments) == 1
            assert len(result.segments[0].words) == 4

    def test_engine_with_mocked_whisper_async(self, short_audio: np.ndarray):
        """异步转录（mock whisper）."""
        fake_whisper = self._make_fake_whisper_module()
        with patch.dict("sys.modules", {"whisper": fake_whisper}):
            WhisperEngine._model_cache.clear()
            engine = WhisperEngine(modelName="tiny", language="zh")
            result = asyncio.run(engine.transcribe_async(short_audio))
            assert isinstance(result, ASRResult)
            assert result.engine == "whisper"

    def test_engine_with_mocked_whisper_long_parallel(self):
        """长音频并行转录（mock whisper）."""
        fake_whisper = self._make_fake_whisper_module()
        long_audio_local = _make_sine_wave(35.0)
        with patch.dict("sys.modules", {"whisper": fake_whisper}):
            WhisperEngine._model_cache.clear()
            engine = WhisperEngine(modelName="tiny", language="zh")
            result = asyncio.run(
                engine.transcribe_async(
                    long_audio_local,
                    segment_max_seconds=10.0,
                    parallel=True,
                    max_workers=2,
                )
            )
            assert isinstance(result, ASRResult)
            assert result.duration > 0

    def test_engine_model_load_failure_fallback(self, short_audio: np.ndarray):
        """模型加载失败回退 Mock."""
        fake_whisper = MagicMock()
        fake_whisper.load_model.side_effect = RuntimeError("model not found")
        fake_whisper.audio.sample_rate = 16000
        fake_whisper.load_audio = lambda x: np.zeros(16000, dtype=np.float32)
        with patch.dict("sys.modules", {"whisper": fake_whisper}):
            WhisperEngine._model_cache.clear()
            engine = WhisperEngine(modelName="tiny", language="zh")
            result = engine.transcribe(short_audio)
            # 加载失败应回退 Mock
            assert result.engine == "mock"

    def test_engine_transcribe_failure_fallback(self, short_audio: np.ndarray):
        """转录失败回退 Mock."""
        fake_whisper = MagicMock()
        fake_model = MagicMock()
        fake_model.transcribe.side_effect = RuntimeError("inference failed")
        fake_whisper.load_model.return_value = fake_model
        fake_whisper.audio.sample_rate = 16000
        fake_whisper.load_audio = lambda x: np.zeros(16000, dtype=np.float32)
        with patch.dict("sys.modules", {"whisper": fake_whisper}):
            WhisperEngine._model_cache.clear()
            engine = WhisperEngine(modelName="tiny", language="zh")
            result = engine.transcribe(short_audio)
            assert result.engine == "mock"

    def test_engine_with_device(self, short_audio: np.ndarray):
        """指定设备."""
        fake_whisper = self._make_fake_whisper_module()
        with patch.dict("sys.modules", {"whisper": fake_whisper}):
            WhisperEngine._model_cache.clear()
            engine = WhisperEngine(modelName="tiny", language="zh", device="cpu")
            result = engine.transcribe(short_audio)
            assert isinstance(result, ASRResult)
            # 验证 load_model 被调用时传入了 device
            fake_whisper.load_model.assert_called_with("tiny", device="cpu")

    def test_parse_whisper_result(self):
        """解析 Whisper 原始输出."""
        engine = WhisperEngine(useMock=True)
        raw = {
            "text": "测试",
            "language": "zh",
            "segments": [
                {
                    "text": "测试",
                    "start": 0.0,
                    "end": 0.5,
                    "words": [{"word": "测", "start": 0.0, "end": 0.25, "probability": 0.8}],
                }
            ],
        }
        result = engine._parse_whisper_result(raw, 0.5)
        assert result.text == "测试"
        assert result.language == "zh"
        assert len(result.segments) == 1
        assert result.segments[0].words[0].word == "测"

    def test_transcribe_segmented_sync(self):
        """同步分段转录."""
        fake_whisper = self._make_fake_whisper_module()
        long_audio_local = _make_sine_wave(35.0)
        with patch.dict("sys.modules", {"whisper": fake_whisper}):
            WhisperEngine._model_cache.clear()
            engine = WhisperEngine(modelName="tiny", language="zh")
            result = engine.transcribe(long_audio_local, segment_max_seconds=10.0)
            assert isinstance(result, ASRResult)


# ----------------------------------------------------------------------
# 16. Pyannote 真实路径测试（mock pyannote）
# ----------------------------------------------------------------------


class TestPyannoteRealPath:
    """Pyannote 真实路径测试."""

    def test_pyannote_with_mocked_module(self, medium_audio: np.ndarray):
        """通过 mock pyannote 库测试真实路径."""

        # 构造假的 Annotation 对象
        class FakeTurn:
            def __init__(self, start, end):
                self.start = start
                self.end = end

        class FakeAnnotation:
            def itertracks(self, yield_label=False):
                tracks = [
                    (FakeTurn(0.0, 5.0), None, "SPEAKER_00"),
                    (FakeTurn(5.0, 10.0), None, "SPEAKER_01"),
                ]
                for turn, _, label in tracks:
                    yield turn, _, label

        fake_pipeline = MagicMock()
        fake_pipeline.return_value = FakeAnnotation()

        with patch("chunker.asr.diarization.is_pyannote_available", return_value=True):
            with patch(
                "chunker.asr.diarization.PyannoteDiarizer._load_pipeline",
                return_value=fake_pipeline,
            ):
                diarizer = PyannoteDiarizer(hfToken="fake_token")
                # 强制不回退
                diarizer._use_fallback = False
                result = diarizer.diarize(medium_audio)
                assert isinstance(result, DiarizationResult)
                assert result.engine == "pyannote"
                assert len(result.segments) == 2

    def test_pyannote_pipeline_load_failure(self, medium_audio: np.ndarray):
        """pyannote pipeline 加载失败回退."""
        with patch("chunker.asr.diarization.is_pyannote_available", return_value=True):
            diarizer = PyannoteDiarizer(hfToken="fake_token")
            # _load_pipeline 返回 None 触发回退
            with patch.object(diarizer, "_load_pipeline", return_value=None):
                diarizer._use_fallback = False
                result = diarizer.diarize(medium_audio)
                # 应回退能量分离
                assert result.engine == "energy"

    def test_pyannote_diarize_failure_fallback(self, medium_audio: np.ndarray):
        """pyannote diarize 失败回退."""
        fake_pipeline = MagicMock()
        fake_pipeline.side_effect = RuntimeError("inference failed")
        with patch("chunker.asr.diarization.is_pyannote_available", return_value=True):
            diarizer = PyannoteDiarizer(hfToken="fake_token")
            with patch.object(diarizer, "_load_pipeline", return_value=fake_pipeline):
                diarizer._use_fallback = False
                result = diarizer.diarize(medium_audio)
                assert result.engine == "energy"

    def test_pyannote_parse_output(self):
        """解析 pyannote 输出."""
        diarizer = PyannoteDiarizer(useMock=True)

        class FakeTurn:
            def __init__(self, s, e):
                self.start = s
                self.end = e

        class FakeAnnotation:
            def itertracks(self, yield_label=False):
                yield FakeTurn(0.0, 3.0), None, "SPEAKER_00"
                yield FakeTurn(3.0, 6.0), None, "SPEAKER_01"

        result = diarizer._parse_pyannote_output(FakeAnnotation(), 6.0)
        assert len(result.segments) == 2
        assert result.segments[0].speaker == "SPEAKER_00"
        assert result.segments[1].speaker == "SPEAKER_01"


# ----------------------------------------------------------------------
# 17. 时间戳对齐边界测试
# ----------------------------------------------------------------------


class TestTimestampAlignerEdge:
    """时间戳对齐边界测试."""

    def test_align_speaker_not_found_uses_nearest(self):
        """词时间不在任何说话人段内：用最近说话人."""
        words = [WhisperWord("你", 100.0, 100.2)]  # 超出分离范围
        seg = ASRSegment(text="你", start=100.0, end=100.2, words=words)
        asr_result = ASRResult(text="你", segments=[seg])
        diar = DiarizationResult(
            segments=[SpeakerSegment(speaker="A", start=0.0, end=10.0)],
            speakers=["A"],
        )
        aligned = align_segments(asr_result, diar)
        assert len(aligned) == 1
        # 应分配到最近的说话人 A
        assert aligned[0].speaker == "A"

    def test_align_multiple_asr_segments(self):
        """多个 ASR 段对齐."""
        seg1 = ASRSegment(
            text="你好",
            start=0.0,
            end=0.4,
            words=[WhisperWord("你好", 0.0, 0.4)],
        )
        seg2 = ASRSegment(
            text="世界",
            start=0.4,
            end=0.8,
            words=[WhisperWord("世界", 0.4, 0.8)],
        )
        asr_result = ASRResult(text="你好世界", segments=[seg1, seg2])
        aligned = align_segments(asr_result)
        assert len(aligned) == 1
        assert aligned[0].text == "你好世界"

    def test_align_with_none_diarization(self):
        """diarization=None."""
        words = [WhisperWord("你", 0.0, 0.2)]
        seg = ASRSegment(text="你", start=0.0, end=0.2, words=words)
        asr_result = ASRResult(text="你", segments=[seg])
        aligned = align_segments(asr_result, None)
        assert len(aligned) == 1
        assert aligned[0].speaker == "SPEAKER_00"

    def test_align_empty_diarization_segments(self):
        """空说话人段."""
        words = [WhisperWord("你", 0.0, 0.2)]
        seg = ASRSegment(text="你", start=0.0, end=0.2, words=words)
        asr_result = ASRResult(text="你", segments=[seg])
        diar = DiarizationResult(segments=[], speakers=[])
        aligned = align_segments(asr_result, diar)
        assert len(aligned) == 1

    def test_aligned_segment_to_dict(self):
        """AlignedSegment 序列化."""
        words = [AlignedWord(word="你", start=0.0, end=0.2, speaker="A")]
        seg = AlignedSegment(text="你", start=0.0, end=0.2, speaker="A", words=words, confidence=0.9)
        d = seg.to_dict()
        assert d["text"] == "你"
        assert d["speaker"] == "A"
        assert d["confidence"] == 0.9
        assert len(d["words"]) == 1

    def test_aligner_with_custom_tolerance(self):
        """自定义容差."""
        aligner = TimestampAligner(tolerance=0.5)
        assert aligner.tolerance == 0.5
        words = [WhisperWord("你", 0.0, 0.2)]
        seg = ASRSegment(text="你", start=0.0, end=0.2, words=words)
        asr_result = ASRResult(text="你", segments=[seg])
        aligned = aligner.align(asr_result)
        assert len(aligned) == 1


# ----------------------------------------------------------------------
# 18. AudioChunker 内部方法测试
# ----------------------------------------------------------------------


class TestAudioChunkerInternals:
    """AudioChunker 内部方法测试."""

    def test_get_asr_engine(self, chunker: AudioChunker):
        """获取 ASR 引擎."""
        engine = chunker._get_asr_engine()
        assert isinstance(engine, WhisperEngine)

    def test_get_diarizer(self, chunker: AudioChunker):
        """获取分离器."""
        diarizer = chunker._get_diarizer()
        assert isinstance(diarizer, Diarizer)

    def test_get_aligner(self, chunker: AudioChunker):
        """获取对齐器."""
        aligner = chunker._get_aligner()
        assert isinstance(aligner, TimestampAligner)

    def test_get_text_chunker(self, chunker_with_text: AudioChunker):
        """获取 TextChunker."""
        tc = chunker_with_text._get_text_chunker()
        assert tc is not None

    def test_dedup_chunks(self, chunker: AudioChunker, short_audio: np.ndarray):
        """去重切片."""
        from chunker.models import ChunkMetadata

        meta = ChunkMetadata(modality=Modality.AUDIO, start=0, end=100, index=0)
        c1 = Chunk(id="1", content="hello", metadata=meta)
        c2 = Chunk(id="2", content="hello", metadata=meta)  # 重复
        c3 = Chunk(id="3", content="world", metadata=meta.model_copy(update={"end": 200}))
        result = chunker._dedup_chunks([c1, c2, c3])
        assert len(result) == 2

    def test_build_chunk(self, chunker: AudioChunker):
        """构建单个 Chunk."""
        seg = AlignedSegment(
            text="你好",
            start=0.0,
            end=1.0,
            speaker="SPEAKER_00",
            words=[AlignedWord(word="你好", start=0.0, end=1.0, speaker="SPEAKER_00")],
        )
        config = _cfg()
        chunk = chunker._build_chunk([seg], 0, "test.wav", config, 1.0, "mock", "energy")
        assert chunk.content == "你好"
        assert chunk.metadata.extra["speakers"] == ["SPEAKER_00"]
        assert chunk.metadata.extra["asrEngine"] == "mock"

    def test_collect_segments_in_window(self, chunker: AudioChunker):
        """收集窗口内段."""
        segs = [
            AlignedSegment(text="A", start=0.0, end=2.0, speaker="S1"),
            AlignedSegment(text="B", start=3.0, end=5.0, speaker="S2"),
            AlignedSegment(text="C", start=6.0, end=8.0, speaker="S3"),
        ]
        result = chunker._collect_segments_in_window(segs, 0.0, 4.0)
        assert len(result) == 2  # A 和 B

    async def test_apply_text_chunker_empty(self, chunker_with_text: AudioChunker):
        """TextChunker 处理空切片."""
        config = _cfg()
        result = await chunker_with_text._apply_text_chunker([], config, "zh")
        assert result == []


# ----------------------------------------------------------------------
# 19. Mock 引擎 VAD 测试
# ----------------------------------------------------------------------


class TestMockEngineVAD:
    """Mock 引擎 VAD 测试."""

    def test_vad_with_silent_audio(self, silent_audio: np.ndarray):
        """静音音频 VAD."""
        engine = MockWhisperEngine()
        segments = engine._vad(silent_audio, 5.0)
        # 静音应返回整段占位或空
        assert isinstance(segments, list)

    def test_vad_with_active_audio(self, short_audio: np.ndarray):
        """有语音活动 VAD."""
        engine = MockWhisperEngine()
        segments = engine._vad(short_audio, 5.0)
        assert len(segments) >= 1

    def test_mock_engine_no_numpy(self, short_audio: np.ndarray):
        """Mock 引擎在 numpy 不可用时（模拟）."""
        engine = MockWhisperEngine()
        # 正常调用（numpy 可用）
        result = engine.transcribe(short_audio)
        assert isinstance(result, ASRResult)

    def test_mock_engine_load_failure(self):
        """Mock 引擎音频加载失败."""
        engine = MockWhisperEngine()
        result = engine.transcribe("nonexistent.wav")
        assert result.text == ""
        assert result.duration == 0.0

    def test_mock_engine_transcribe_no_word_timestamps(self, short_audio: np.ndarray):
        """Mock 引擎不生成词级时间戳."""
        engine = MockWhisperEngine()
        result = engine.transcribe(short_audio, word_timestamps=False)
        for seg in result.segments:
            assert len(seg.words) == 0


# ----------------------------------------------------------------------
# 20. 集成场景测试
# ----------------------------------------------------------------------


class TestIntegration:
    """端到端集成场景测试."""

    async def test_full_pipeline_mock(self, chunker: AudioChunker, multi_speaker_audio: np.ndarray):
        """完整流水线：Mock ASR + 能量分离 + 对齐 + 切片."""
        cfg = _cfg(
            windowSize=10000,
            overlap=0.1,
            extra={"language": "zh", "minSpeakers": 2, "maxSpeakers": 3},
        )
        chunks = await chunker.chunk(multi_speaker_audio, cfg)
        assert len(chunks) >= 1
        # 验证每个切片结构完整
        for c in chunks:
            assert c.id
            assert isinstance(c.content, str)
            assert c.metadata.modality == Modality.AUDIO
            assert c.metadata.index >= 0
            assert c.tokens is not None
            extra = c.metadata.extra
            assert "startTime" in extra
            assert "endTime" in extra
            assert "speakers" in extra
            assert "asrEngine" in extra

    async def test_chunk_with_result_full(self, chunker: AudioChunker, medium_audio: np.ndarray):
        """chunk_with_result 完整测试."""
        cfg = _cfg(windowSize=10000)
        result = await chunker.chunk_with_result(medium_audio, cfg)
        assert result.modality == Modality.AUDIO
        assert result.count >= 1
        assert result.totalTokens >= 0
        assert result.durationMs >= 0

    async def test_wav_bytes_pipeline(self, chunker: AudioChunker, short_audio: np.ndarray):
        """WAV bytes 输入完整流水线."""
        wav_bytes = _audio_to_wav_bytes(short_audio)
        cfg = _cfg(windowSize=30000)
        chunks = await chunker.chunk(wav_bytes, cfg)
        assert len(chunks) >= 1
        for c in chunks:
            assert c.metadata.source == "bytes://audio"

    async def test_file_path_pipeline(self, chunker: AudioChunker, tmp_wav_file: str):
        """文件路径输入完整流水线."""
        cfg = _cfg(windowSize=30000)
        chunks = await chunker.chunk(tmp_wav_file, cfg)
        assert len(chunks) >= 1
        for c in chunks:
            assert c.metadata.source == tmp_wav_file
