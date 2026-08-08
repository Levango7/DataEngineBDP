"""Whisper ASR 引擎封装 (T008-5).

提供语音转文本能力，支持：

1. **Whisper 本地推理**：优先使用 ``openai-whisper`` 库
   - 支持 ``tiny`` / ``base`` / ``small`` / ``medium`` / ``large`` 模型
   - 中文普通话推荐 ``medium`` 或 ``large``（准确率 ≥ 85%）
   - 词级时间戳（``word_timestamps=True``）
2. **Mock 回退**：Whisper 不可用时自动启用 Mock 引擎
   - 基于音频能量启发式生成占位文本
   - 保证流水线可运行（测试 / CI 环境）
3. **音频加载**：支持 WAV / MP3 / FLAC 文件路径 + numpy 数组
   - 16kHz 单声道归一化（Whisper 要求）
4. **异步接口**：``transcribe_async`` 不阻塞事件循环
   - 通过 ``loop.run_in_executor`` 在线程池中执行推理
5. **分段转录**：对长音频按时间窗口分段转录，合并结果
   - 每段独立调用 Whisper，避免长音频 OOM
   - 段间时间戳偏移自动修正

对齐设计文档 T008-5。
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import io
import os
import threading
from typing import Any

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: 默认 Whisper 模型名（中文普通话推荐 medium）
DEFAULT_WHISPER_MODEL = "medium"

#: Whisper 采样率（固定 16kHz）
WHISPER_SAMPLE_RATE = 16000

#: 默认语言提示（None 表示自动检测）
DEFAULT_LANGUAGE: str | None = None

#: 单段最大时长（秒），超过则分段转录
DEFAULT_SEGMENT_MAX_SECONDS = 30.0

#: 支持的音频格式扩展名
SUPPORTED_AUDIO_EXTS = frozenset({".wav", ".mp3", ".flac", ".m4a", ".ogg"})

#: Mock 引擎生成的占位文本（每段）
_MOCK_PLACEHOLDER_TEXT = "（语音内容）"


# ----------------------------------------------------------------------
# 可用性检测
# ----------------------------------------------------------------------


def is_whisper_available() -> bool:
    """检测 ``openai-whisper`` 是否可用.

    :return: True 表示 whisper 库可导入
    """
    try:
        import whisper  # type: ignore[import-untyped]  # noqa: F401

        return True
    except ImportError:
        return False


def is_numpy_available() -> bool:
    """检测 numpy 是否可用."""
    try:
        import numpy  # type: ignore[import-untyped]  # noqa: F401

        return True
    except ImportError:
        return False


# ----------------------------------------------------------------------
# 数据结构
# ----------------------------------------------------------------------


@dataclass
class WhisperWord:
    """词级时间戳项.

    Whisper ``word_timestamps=True`` 输出的单个词。
    """

    word: str
    start: float  # 秒
    end: float  # 秒
    probability: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        return {
            "word": self.word,
            "start": self.start,
            "end": self.end,
            "probability": self.probability,
        }


@dataclass
class ASRSegment:
    """ASR 转录段.

    对应 Whisper 输出的一个 segment（通常为一句话）。
    """

    text: str
    start: float  # 秒
    end: float  # 秒
    words: list[WhisperWord] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "text": self.text,
            "start": self.start,
            "end": self.end,
            "words": [w.to_dict() for w in self.words],
        }


@dataclass
class ASRResult:
    """ASR 转录结果.

    携带完整文本、分段列表、所用引擎、语言。
    """

    text: str
    segments: list[ASRSegment]
    language: str = "zh"
    engine: str = "whisper"
    duration: float = 0.0  # 秒

    @property
    def word_count(self) -> int:
        """总词数."""
        return sum(len(s.words) for s in self.segments)

    def to_dict(self) -> dict[str, Any]:
        return {
            "text": self.text,
            "segments": [s.to_dict() for s in self.segments],
            "language": self.language,
            "engine": self.engine,
            "duration": self.duration,
        }


# ----------------------------------------------------------------------
# 音频加载辅助
# ----------------------------------------------------------------------


def _is_audio_path(content: Any) -> bool:
    """判断内容是否为音频文件路径."""
    if isinstance(content, (str, os.PathLike)):
        ext = os.path.splitext(str(content))[1].lower()
        return ext in SUPPORTED_AUDIO_EXTS
    return False


def load_audio(content: Any, sample_rate: int = WHISPER_SAMPLE_RATE) -> tuple[Any, float]:
    """加载音频为 numpy 数组（16kHz 单声道浮点）.

    :param content: 文件路径 / bytes / numpy 数组
    :param sample_rate: 目标采样率（默认 16000）
    :return: (audio_array, duration_seconds)
    :raises ImportError: numpy / whisper 不可用
    :raises FileNotFoundError: 文件不存在
    """
    import numpy as np  # type: ignore[import-untyped]

    if isinstance(content, (str, os.PathLike)):
        path = os.fspath(content)
        if not os.path.exists(path):
            raise FileNotFoundError(f"音频文件不存在: {path}")
        # 优先用 whisper.load_audio（自动重采样到 16kHz）
        if is_whisper_available():
            import whisper  # type: ignore[import-untyped]

            audio = whisper.load_audio(path)
            duration = len(audio) / whisper.audio.sample_rate
            return audio, duration
        # whisper 不可用：用 numpy + wave 读取 WAV
        return _load_wav_with_numpy(path, sample_rate)

    if isinstance(content, (bytes, bytearray)):
        # bytes 输入：写入临时内存文件用 whisper 解码
        if is_whisper_available():
            # whisper.load_audio 接受文件路径，需写临时文件
            import tempfile

            import whisper  # type: ignore[import-untyped]

            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
                tmp.write(bytes(content))
                tmp_path = tmp.name
            try:
                audio = whisper.load_audio(tmp_path)
                duration = len(audio) / whisper.audio.sample_rate
                return audio, duration
            finally:
                try:
                    os.unlink(tmp_path)
                except OSError:
                    pass
        # 无 whisper：尝试从 bytes 读 WAV
        return _load_wav_bytes_with_numpy(bytes(content), sample_rate)

    if isinstance(content, np.ndarray):
        audio = content.astype(np.float32)
        duration = len(audio) / sample_rate
        return audio, duration

    raise TypeError(f"不支持的音频输入类型: {type(content).__name__}，" f"期望 str/Path/bytes/numpy.ndarray")


def _load_wav_with_numpy(path: str, sample_rate: int) -> tuple[Any, float]:
    """用 numpy + wave 读取 WAV 文件（无 whisper 依赖时）."""
    import wave

    import numpy as np  # type: ignore[import-untyped]

    with wave.open(path, "rb") as wf:
        n_channels = wf.getnchannels()
        sampwidth = wf.getsampwidth()
        n_frames = wf.getnframes()
        raw = wf.readframes(n_frames)

    # 转为 numpy float32
    if sampwidth == 2:
        audio = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    elif sampwidth == 4:
        audio = np.frombuffer(raw, dtype=np.int32).astype(np.float32) / 2147483648.0
    else:
        # 8-bit / 其他：简单归一化
        audio = np.frombuffer(raw, dtype=np.uint8).astype(np.float32) / 128.0 - 1.0

    # 多声道 -> 单声道（取均值）
    if n_channels > 1:
        audio = audio.reshape(-1, n_channels).mean(axis=1)

    duration = len(audio) / sample_rate
    return audio, duration


def _load_wav_bytes_with_numpy(data: bytes, sample_rate: int) -> tuple[Any, float]:
    """从 bytes 读取 WAV（无 whisper 依赖时）."""
    import wave

    import numpy as np  # type: ignore[import-untyped]

    with wave.open(io.BytesIO(data), "rb") as wf:
        n_channels = wf.getnchannels()
        sampwidth = wf.getsampwidth()
        n_frames = wf.getnframes()
        raw = wf.readframes(n_frames)

    if sampwidth == 2:
        audio = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    elif sampwidth == 4:
        audio = np.frombuffer(raw, dtype=np.int32).astype(np.float32) / 2147483648.0
    else:
        audio = np.frombuffer(raw, dtype=np.uint8).astype(np.float32) / 128.0 - 1.0

    if n_channels > 1:
        audio = audio.reshape(-1, n_channels).mean(axis=1)

    duration = len(audio) / sample_rate
    return audio, duration


def estimate_audio_duration(content: Any, sample_rate: int = WHISPER_SAMPLE_RATE) -> float:
    """估算音频时长（秒），不加载完整音频.

    :param content: 文件路径 / bytes / numpy 数组
    :param sample_rate: 采样率
    :return: 时长秒数
    """
    try:
        _, duration = load_audio(content, sample_rate)
        return duration
    except Exception:  # noqa: BLE001
        return 0.0


# ----------------------------------------------------------------------
# WhisperEngine
# ----------------------------------------------------------------------


class WhisperEngine:
    """Whisper ASR 引擎封装.

    优先使用 ``openai-whisper`` 本地推理；不可用时自动回退为 Mock 引擎。

    用法::

        engine = WhisperEngine(modelName="medium")
        result = await engine.transcribe_async("/path/to/audio.wav")
        print(result.text)
        for seg in result.segments:
            for w in seg.words:
                print(f"{w.start:.2f}-{w.end:.2f}: {w.word}")
    """

    _model_lock = threading.Lock()
    _model_cache: dict[str, Any] = {}

    def __init__(
        self,
        *,
        modelName: str = DEFAULT_WHISPER_MODEL,
        language: str | None = DEFAULT_LANGUAGE,
        useMock: bool = False,
        device: str | None = None,
    ) -> None:
        """初始化 Whisper 引擎.

        :param modelName: Whisper 模型名（tiny/base/small/medium/large）
        :param language: 语言提示（None 自动检测；"zh" 强制中文）
        :param useMock: 强制使用 Mock 引擎（测试用）
        :param device: 推理设备（"cpu"/"cuda"），None 自动选择
        """
        self.modelName = modelName
        self.language = language
        self.device = device
        self._use_mock = useMock or not is_whisper_available()
        self._model: Any = None
        self._mock_engine: MockWhisperEngine | None = None

    @property
    def is_mock(self) -> bool:
        """是否使用 Mock 引擎."""
        return self._use_mock

    @property
    def engine_name(self) -> str:
        """引擎名称（"whisper" / "mock"）."""
        return "mock" if self._use_mock else "whisper"

    def _load_model(self) -> Any:
        """懒加载 Whisper 模型（线程安全单例）."""
        if self._use_mock:
            return None
        name = self.modelName
        with self._model_lock:
            if name in self._model_cache:
                return self._model_cache[name]
            try:
                import whisper  # type: ignore[import-untyped]

                model = whisper.load_model(name, device=self.device) if self.device else whisper.load_model(name)
                self._model_cache[name] = model
                return model
            except Exception:  # noqa: BLE001
                # 模型加载失败：回退 Mock
                self._use_mock = True
                return None

    def _get_mock(self) -> MockWhisperEngine:
        """获取 Mock 引擎实例."""
        if self._mock_engine is None:
            self._mock_engine = MockWhisperEngine(language=self.language)
        return self._mock_engine

    # ------------------------------------------------------------------
    # 同步转录
    # ------------------------------------------------------------------

    def transcribe(
        self,
        content: Any,
        *,
        language: str | None = None,
        word_timestamps: bool = True,
        segment_max_seconds: float = DEFAULT_SEGMENT_MAX_SECONDS,
    ) -> ASRResult:
        """同步转录音频.

        :param content: 音频文件路径 / bytes / numpy 数组
        :param language: 语言提示（覆盖实例配置）
        :param word_timestamps: 是否生成词级时间戳
        :param segment_max_seconds: 单段最大时长（秒），超过则分段
        :return: ASRResult 转录结果
        """
        if self._use_mock:
            return self._get_mock().transcribe(
                content,
                language=language,
                word_timestamps=word_timestamps,
                segment_max_seconds=segment_max_seconds,
            )

        audio, duration = load_audio(content)
        lang = language or self.language

        # 短音频：直接转录
        if duration <= segment_max_seconds:
            return self._transcribe_once(audio, lang, word_timestamps, duration)

        # 长音频：分段转录
        return self._transcribe_segmented(audio, lang, word_timestamps, duration, segment_max_seconds)

    def _transcribe_once(
        self,
        audio: Any,
        language: str | None,
        word_timestamps: bool,
        duration: float,
    ) -> ASRResult:
        """单次转录（不分段）."""
        model = self._load_model()
        if model is None:
            # 加载失败回退 Mock
            return self._get_mock().transcribe(audio, language=language, word_timestamps=word_timestamps)

        try:
            options: dict[str, Any] = {"word_timestamps": word_timestamps}
            if language is not None:
                options["language"] = language
            result = model.transcribe(audio, **options)
            return self._parse_whisper_result(result, duration)
        except Exception:  # noqa: BLE001
            # 推理失败回退 Mock
            self._use_mock = True
            return self._get_mock().transcribe(audio, language=language, word_timestamps=word_timestamps)

    def _transcribe_segmented(
        self,
        audio: Any,
        language: str | None,
        word_timestamps: bool,
        duration: float,
        segment_max_seconds: float,
    ) -> ASRResult:
        """分段转录长音频（同步顺序版，异步版见 transcribe_async）."""

        sr = WHISPER_SAMPLE_RATE
        seg_samples = int(segment_max_seconds * sr)
        all_segments: list[ASRSegment] = []
        full_text_parts: list[str] = []

        for start_sample in range(0, len(audio), seg_samples):
            end_sample = min(start_sample + seg_samples, len(audio))
            chunk = audio[start_sample:end_sample]
            if len(chunk) < sr * 0.1:  # < 0.1s 跳过
                continue
            time_offset = start_sample / sr
            try:
                sub = self._transcribe_once(chunk, language, word_timestamps, len(chunk) / sr)
            except Exception:  # noqa: BLE001
                continue
            # 修正时间戳偏移
            for seg in sub.segments:
                seg.start += time_offset
                seg.end += time_offset
                for w in seg.words:
                    w.start += time_offset
                    w.end += time_offset
                all_segments.append(seg)
            if sub.text:
                full_text_parts.append(sub.text)

        return ASRResult(
            text="".join(full_text_parts),
            segments=all_segments,
            language=language or self.language or "zh",
            engine=self.engine_name,
            duration=duration,
        )

    def _parse_whisper_result(self, result: dict[str, Any], duration: float) -> ASRResult:
        """解析 Whisper 原始输出为 ASRResult."""
        segments: list[ASRSegment] = []
        for seg in result.get("segments", []):
            words: list[WhisperWord] = []
            for w in seg.get("words", []):
                words.append(
                    WhisperWord(
                        word=str(w.get("word", "")).strip(),
                        start=float(w.get("start", 0.0)),
                        end=float(w.get("end", 0.0)),
                        probability=float(w.get("probability", 0.0)),
                    )
                )
            segments.append(
                ASRSegment(
                    text=str(seg.get("text", "")).strip(),
                    start=float(seg.get("start", 0.0)),
                    end=float(seg.get("end", 0.0)),
                    words=words,
                )
            )
        return ASRResult(
            text=str(result.get("text", "")).strip(),
            segments=segments,
            language=str(result.get("language", self.language or "zh")),
            engine=self.engine_name,
            duration=duration,
        )

    # ------------------------------------------------------------------
    # 异步转录
    # ------------------------------------------------------------------

    async def transcribe_async(
        self,
        content: Any,
        *,
        language: str | None = None,
        word_timestamps: bool = True,
        segment_max_seconds: float = DEFAULT_SEGMENT_MAX_SECONDS,
        parallel: bool = True,
        max_workers: int = 4,
    ) -> ASRResult:
        """异步转录音频（不阻塞事件循环）.

        :param content: 音频文件路径 / bytes / numpy 数组
        :param language: 语言提示
        :param word_timestamps: 是否生成词级时间戳
        :param segment_max_seconds: 单段最大时长
        :param parallel: 长音频是否并行分段转录
        :param max_workers: 并行工作线程数
        :return: ASRResult
        """
        if self._use_mock:
            # Mock 引擎：直接同步执行（很快）
            return self._get_mock().transcribe(
                content,
                language=language,
                word_timestamps=word_timestamps,
                segment_max_seconds=segment_max_seconds,
            )

        loop = asyncio.get_running_loop()
        audio, duration = await loop.run_in_executor(None, load_audio, content)
        lang = language or self.language

        # 短音频：单次转录
        if duration <= segment_max_seconds:
            return await loop.run_in_executor(None, self._transcribe_once, audio, lang, word_timestamps, duration)

        # 长音频：并行分段转录
        if parallel:
            return await self._transcribe_parallel(
                audio, lang, word_timestamps, duration, segment_max_seconds, max_workers
            )
        return await loop.run_in_executor(
            None,
            self._transcribe_segmented,
            audio,
            lang,
            word_timestamps,
            duration,
            segment_max_seconds,
        )

    async def _transcribe_parallel(
        self,
        audio: Any,
        language: str | None,
        word_timestamps: bool,
        duration: float,
        segment_max_seconds: float,
        max_workers: int,
    ) -> ASRResult:
        """并行分段转录长音频."""
        sr = WHISPER_SAMPLE_RATE
        seg_samples = int(segment_max_seconds * sr)
        loop = asyncio.get_running_loop()

        # 切分音频段
        chunks: list[tuple[int, Any]] = []
        for start_sample in range(0, len(audio), seg_samples):
            end_sample = min(start_sample + seg_samples, len(audio))
            chunk = audio[start_sample:end_sample]
            if len(chunk) < sr * 0.1:
                continue
            chunks.append((start_sample, chunk))

        # 限制并发数
        semaphore = asyncio.Semaphore(max(1, max_workers))
        results: list[tuple[int, ASRResult]] = [None] * len(chunks)  # type: ignore[list-item]

        async def _worker(idx: int, start_sample: int, chunk: Any) -> None:
            async with semaphore:
                time_offset = start_sample / sr
                try:
                    sub = await loop.run_in_executor(
                        None,
                        self._transcribe_once,
                        chunk,
                        language,
                        word_timestamps,
                        len(chunk) / sr,
                    )
                    results[idx] = (time_offset, sub)
                except Exception:  # noqa: BLE001
                    results[idx] = (0.0, ASRResult(text="", segments=[], engine=self.engine_name))

        await asyncio.gather(*[_worker(i, s, c) for i, (s, c) in enumerate(chunks)])

        # 合并结果（按时间偏移修正）
        all_segments: list[ASRSegment] = []
        full_text_parts: list[str] = []
        for time_offset, sub in results:
            if sub is None:
                continue
            for seg in sub.segments:
                seg.start += time_offset
                seg.end += time_offset
                for w in seg.words:
                    w.start += time_offset
                    w.end += time_offset
                all_segments.append(seg)
            if sub.text:
                full_text_parts.append(sub.text)

        # 按起始时间排序
        all_segments.sort(key=lambda s: s.start)

        return ASRResult(
            text="".join(full_text_parts),
            segments=all_segments,
            language=language or self.language or "zh",
            engine=self.engine_name,
            duration=duration,
        )


# ----------------------------------------------------------------------
# MockWhisperEngine
# ----------------------------------------------------------------------


class MockWhisperEngine:
    """Mock Whisper 引擎（无 Whisper 依赖时的回退实现）.

    基于音频能量启发式生成占位转录结果：
    - 检测音频中的语音活动段（能量超过阈值）
    - 每段生成占位文本 ``（语音内容）``
    - 生成词级时间戳（按固定间隔切分占位文本）

    主要用于：
    - CI / 无 GPU 环境
    - 单元测试
    - Whisper 安装失败的回退
    """

    #: 语音活动检测的能量阈值（RMS）
    VAD_ENERGY_THRESHOLD = 0.01

    #: VAD 帧长（秒）
    VAD_FRAME_SECONDS = 0.5

    def __init__(self, *, language: str | None = None) -> None:
        """初始化 Mock 引擎.

        :param language: 语言提示
        """
        self.language = language or "zh"

    def transcribe(
        self,
        content: Any,
        *,
        language: str | None = None,
        word_timestamps: bool = True,
        segment_max_seconds: float = DEFAULT_SEGMENT_MAX_SECONDS,
    ) -> ASRResult:
        """Mock 转录：基于能量 VAD 生成占位结果.

        :param content: 音频
        :param language: 语言
        :param word_timestamps: 是否生成词级时间戳
        :param segment_max_seconds: 单段最大时长
        :return: ASRResult
        """
        lang = language or self.language

        # 加载音频
        try:
            audio, duration = load_audio(content)
        except Exception:  # noqa: BLE001
            # 加载失败：返回空结果
            return ASRResult(text="", segments=[], language=lang, engine="mock", duration=0.0)

        # 能量 VAD 检测语音段
        vad_segments = self._vad(audio, duration)

        # 生成 ASR 段
        asr_segments: list[ASRSegment] = []
        for start, end in vad_segments:
            seg_len = end - start
            # 占位文本：每 2 秒一个 "（语音内容）"
            n_phrases = max(1, int(seg_len / 2.0))
            phrase = _MOCK_PLACEHOLDER_TEXT
            text = phrase * n_phrases
            words: list[WhisperWord] = []
            if word_timestamps:
                # 按字符切分占位文本，均匀分配时间
                chars = list(text)
                if chars:
                    per_char = seg_len / len(chars)
                    for i, ch in enumerate(chars):
                        w_start = start + i * per_char
                        w_end = start + (i + 1) * per_char
                        words.append(WhisperWord(word=ch, start=w_start, end=w_end, probability=0.5))
            asr_segments.append(ASRSegment(text=text, start=start, end=end, words=words))

        full_text = "".join(s.text for s in asr_segments)
        return ASRResult(
            text=full_text,
            segments=asr_segments,
            language=lang,
            engine="mock",
            duration=duration,
        )

    def _vad(self, audio: Any, duration: float) -> list[tuple[float, float]]:
        """基于能量的语音活动检测.

        :param audio: numpy 数组
        :param duration: 总时长
        :return: [(start, end), ...] 语音段列表（秒）
        """
        try:
            import numpy as np  # type: ignore[import-untyped]
        except ImportError:
            # numpy 不可用：整段作为语音
            return [(0.0, duration)] if duration > 0 else []

        sr = WHISPER_SAMPLE_RATE
        frame_size = int(self.VAD_FRAME_SECONDS * sr)
        if frame_size <= 0 or len(audio) == 0:
            return [(0.0, duration)] if duration > 0 else []

        # 计算每帧 RMS 能量
        n_frames = len(audio) // frame_size
        if n_frames == 0:
            return [(0.0, duration)] if duration > 0 else []

        energies = np.array(
            [float(np.sqrt(np.mean(audio[i * frame_size : (i + 1) * frame_size] ** 2))) for i in range(n_frames)]
        )

        # 阈值自适应：取能量的中位数 * 2 与固定阈值取较大
        adaptive_thresh = max(
            self.VAD_ENERGY_THRESHOLD,
            float(np.median(energies)) * 0.5,
        )

        # 标记语音帧
        is_voice = energies > adaptive_thresh

        # 合并连续语音帧为段
        segments: list[tuple[float, float]] = []
        in_seg = False
        seg_start = 0.0
        for i, v in enumerate(is_voice):
            t = i * self.VAD_FRAME_SECONDS
            if v and not in_seg:
                in_seg = True
                seg_start = t
            elif not v and in_seg:
                in_seg = False
                seg_end = t
                if seg_end - seg_start >= 0.3:  # 最短 0.3s
                    segments.append((seg_start, seg_end))
        if in_seg:
            seg_end = duration
            if seg_end - seg_start >= 0.3:
                segments.append((seg_start, seg_end))

        # 无语音段：返回整段作为占位
        if not segments and duration > 0:
            segments.append((0.0, duration))

        return segments
