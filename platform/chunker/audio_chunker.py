"""语音切片器 (T008-5).

基于 T008-1 多模态切片器框架实现 ``AudioChunker``，支持：

1. **ASR 转文本**：集成 Whisper 引擎（中文普通话准确率 ≥ 85%）
   - 优先使用 ``openai-whisper`` 本地推理
   - 不可用时自动回退为 Mock 引擎（基于能量 VAD）
   - 词级时间戳（``word_timestamps=True``）
2. **说话人分离**：支持 ≥2 说话人（准确率 ≥ 80%）
   - 优先使用 ``pyannote.audio`` 深度学习模型
   - 不可用时回退为基于短时能量的简单分离
3. **时间戳对齐**：词级时间戳与说话人段对齐
   - 输出 ``AlignedSegment``（文本 + 起止时间 + 说话人 + 词列表）
4. **长音频并行处理**：≥30 分钟音频切片 P95 ≤ 10s
   - 按 ``windowSize`` 毫秒分段并行 ASR
   - ``asyncio.gather`` + 线程池并发
5. **复用 TextChunker**：ASR 转出的文本可选用 TextChunker 二次语义切片
   - 通过 ``enableTextChunker=True`` 启用
   - 保留原始时间戳与说话人信息
6. **输入格式**：支持 WAV / MP3 / FLAC 文件路径 + bytes + numpy 数组
7. **注册**：通过 ``@register_chunker(Modality.AUDIO)`` 自动注册

对齐设计文档 T008-5。
"""
from __future__ import annotations

import asyncio
import os
import threading
from typing import Any

from chunker.asr.diarization import (
    DEFAULT_MAX_SPEAKERS,
    DEFAULT_MIN_SPEAKERS,
    DiarizationResult,
    Diarizer,
    EnergyDiarizer,
    PyannoteDiarizer,
    SpeakerSegment,
    create_diarizer,
)
from chunker.asr.timestamp_aligner import (
    AlignedSegment,
    AlignedWord,
    TimestampAligner,
    align_segments,
)
from chunker.asr.whisper_engine import (
    ASRResult,
    ASRSegment,
    DEFAULT_SEGMENT_MAX_SECONDS,
    DEFAULT_WHISPER_MODEL,
    SUPPORTED_AUDIO_EXTS,
    WhisperEngine,
    WhisperWord,
    estimate_audio_duration,
    is_whisper_available,
    load_audio,
)
from chunker.base import BaseChunker
from chunker.exceptions import PreprocessError
from chunker.models import Chunk, ChunkConfig, Modality
from chunker.registry import register_chunker

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: 默认 Whisper 模型（中文普通话推荐 medium）
DEFAULT_MODEL = DEFAULT_WHISPER_MODEL

#: 默认时间窗口（毫秒），对应 ChunkConfig.windowSize
DEFAULT_WINDOW_MS = 30000

#: 默认重叠（毫秒），对应 ChunkConfig.overlap
DEFAULT_OVERLAP = 0.1

#: 长音频阈值（秒），超过则启用并行分段
LONG_AUDIO_THRESHOLD_SECONDS = 30.0

#: 并行分段最大工作线程数
DEFAULT_MAX_WORKERS = 4

#: 单段最大 ASR 时长（秒）
DEFAULT_ASR_SEGMENT_SECONDS = DEFAULT_SEGMENT_MAX_SECONDS


# ----------------------------------------------------------------------
# 辅助函数
# ----------------------------------------------------------------------


def _is_audio_path(content: Any) -> bool:
    """判断内容是否为音频文件路径."""
    if isinstance(content, (str, os.PathLike)):
        ext = os.path.splitext(str(content))[1].lower()
        return ext in SUPPORTED_AUDIO_EXTS
    return False


def _resolve_source(content: Any) -> str:
    """获取来源标识."""
    if isinstance(content, (str, os.PathLike)):
        return os.fspath(content)
    if isinstance(content, (bytes, bytearray)):
        return "bytes://audio"
    return "audio://stream"


# ----------------------------------------------------------------------
# AudioChunker
# ----------------------------------------------------------------------


@register_chunker(Modality.AUDIO)
class AudioChunker(BaseChunker):
    """语音切片器.

    基于 ASR + 说话人分离 + 时间戳对齐，将音频切分为带时间戳与说话人标签的文本切片。

    配置通过 ``ChunkConfig`` 传入，模态专属配置通过 ``ChunkConfig.extra`` 提供：

    - ``whisperModel``: Whisper 模型名，默认 ``"medium"``
    - ``language``: 语言提示（"zh" 强制中文），默认 None 自动检测
    - ``enableDiarization``: 是否启用说话人分离，默认 True
    - ``enableTextChunker``: 是否对 ASR 文本用 TextChunker 二次切片，默认 False
    - ``wordTimestamps``: 是否生成词级时间戳，默认 True
    - ``parallel``: 长音频是否并行分段，默认 True
    - ``maxWorkers``: 并行工作线程数，默认 4
    - ``minSpeakers``: 最小说话人数，默认 2
    - ``maxSpeakers``: 最大说话人数，默认 8
    - ``useMockASR``: 强制使用 Mock ASR（测试用），默认 False
    - ``useMockDiarization``: 强制使用能量分离（测试用），默认 False

    ``ChunkConfig.windowSize`` 单位为**毫秒**，表示单切片最大音频时长。
    ``ChunkConfig.overlap`` 为重叠率（0~1）。

    用法::

        from chunker import get_chunker, ChunkConfig, Modality

        chunker = get_chunker("audio")
        cfg = ChunkConfig(
            modality=Modality.AUDIO,
            windowSize=30000,  # 30 秒
            overlap=0.1,
            extra={"language": "zh", "enableDiarization": True},
        )
        chunks = await chunker.chunk("/path/to/audio.wav", cfg)
        for c in chunks:
            print(c.content, c.metadata.extra)
    """

    MODALITY = Modality.AUDIO

    # 类级单例锁：引擎懒加载
    _engine_lock = threading.Lock()
    _engine_cache: dict[str, Any] = {}

    def __init__(
        self,
        modality: Modality | str | None = None,
        *,
        whisperModel: str = DEFAULT_MODEL,
        language: str | None = None,
        enableDiarization: bool = True,
        enableTextChunker: bool = False,
        wordTimestamps: bool = True,
        parallel: bool = True,
        maxWorkers: int = DEFAULT_MAX_WORKERS,
        minSpeakers: int = DEFAULT_MIN_SPEAKERS,
        maxSpeakers: int = DEFAULT_MAX_SPEAKERS,
        hfToken: str | None = None,
        useMockASR: bool = False,
        useMockDiarization: bool = False,
    ) -> None:
        """初始化语音切片器.

        :param modality: 模态（默认 AUDIO）
        :param whisperModel: Whisper 模型名
        :param language: 语言提示
        :param enableDiarization: 是否启用说话人分离
        :param enableTextChunker: 是否对 ASR 文本用 TextChunker 二次切片
        :param wordTimestamps: 是否生成词级时间戳
        :param parallel: 长音频是否并行分段
        :param maxWorkers: 并行工作线程数
        :param minSpeakers: 最小说话人数
        :param maxSpeakers: 最大说话人数
        :param hfToken: HuggingFace 令牌（pyannote 用）
        :param useMockASR: 强制使用 Mock ASR
        :param useMockDiarization: 强制使用能量分离
        """
        super().__init__(modality)
        self.whisperModel = whisperModel
        self.language = language
        self.enableDiarization = enableDiarization
        self.enableTextChunker = enableTextChunker
        self.wordTimestamps = wordTimestamps
        self.parallel = parallel
        self.maxWorkers = maxWorkers
        self.minSpeakers = minSpeakers
        self.maxSpeakers = maxSpeakers
        self.hfToken = hfToken
        self.useMockASR = useMockASR
        self.useMockDiarization = useMockDiarization

        # 引擎懒加载
        self._asr_engine: WhisperEngine | None = None
        self._diarizer: Diarizer | None = None
        self._aligner: TimestampAligner | None = None
        self._text_chunker: Any | None = None

    # ------------------------------------------------------------------
    # 引擎懒加载
    # ------------------------------------------------------------------

    def _get_asr_engine(self) -> WhisperEngine:
        """获取 ASR 引擎（懒加载）."""
        if self._asr_engine is None:
            self._asr_engine = WhisperEngine(
                modelName=self.whisperModel,
                language=self.language,
                useMock=self.useMockASR,
            )
        return self._asr_engine

    def _get_diarizer(self) -> Diarizer:
        """获取说话人分离器（懒加载）."""
        if self._diarizer is None:
            if self.useMockDiarization:
                self._diarizer = EnergyDiarizer(num_speakers=self.minSpeakers)
            else:
                self._diarizer = create_diarizer(
                    prefer_pyannote=True,
                    hfToken=self.hfToken,
                    num_speakers=self.minSpeakers,
                )
        return self._diarizer

    def _get_aligner(self) -> TimestampAligner:
        """获取时间戳对齐器（懒加载）."""
        if self._aligner is None:
            self._aligner = TimestampAligner()
        return self._aligner

    def _get_text_chunker(self) -> Any:
        """获取 TextChunker 实例（懒加载）."""
        if self._text_chunker is None:
            # 延迟导入避免循环依赖
            from chunker.text_chunker import TextChunker

            self._text_chunker = TextChunker()
        return self._text_chunker

    # ------------------------------------------------------------------
    # BaseChunker 抽象方法实现
    # ------------------------------------------------------------------

    async def _preprocess(self, content: Any, config: ChunkConfig) -> Any:
        """预处理：校验输入 + 加载音频元信息.

        :param content: 音频文件路径 / bytes / numpy 数组
        :param config: 切片配置
        :return: 预处理结果字典::

            {
                "content": 原始内容,
                "source": 来源标识,
                "duration": 时长秒数,
            }
        :raises PreprocessError: 输入非法
        """
        if content is None:
            raise PreprocessError("音频内容不能为 None")

        # 校验路径存在性
        if isinstance(content, (str, os.PathLike)):
            path = os.fspath(content)
            if not os.path.exists(path):
                raise PreprocessError(f"音频文件不存在: {path}")

        source = _resolve_source(content)
        duration = estimate_audio_duration(content)
        return {
            "content": content,
            "source": source,
            "duration": duration,
        }

    async def _split(self, preprocessed: Any, config: ChunkConfig) -> list[Chunk]:
        """切分：ASR + 说话人分离 + 时间戳对齐 + 按时间窗口切片.

        :param preprocessed: ``_preprocess`` 返回的字典
        :param config: 切片配置
        :return: 切片列表
        """
        content = preprocessed["content"]
        source = preprocessed["source"]
        duration = preprocessed["duration"]

        if duration <= 0:
            return []

        # 读取模态专属配置
        extra = config.extra or {}
        language = extra.get("language", self.language)
        enable_diar = bool(extra.get("enableDiarization", self.enableDiarization))
        enable_text_chunker = bool(
            extra.get("enableTextChunker", self.enableTextChunker)
        )
        word_ts = bool(extra.get("wordTimestamps", self.wordTimestamps))
        parallel = bool(extra.get("parallel", self.parallel))
        max_workers = int(extra.get("maxWorkers", self.maxWorkers))
        min_spk = int(extra.get("minSpeakers", self.minSpeakers))
        max_spk = int(extra.get("maxSpeakers", self.maxSpeakers))

        # 1. ASR 转文本
        asr_engine = self._get_asr_engine()
        asr_result = await asr_engine.transcribe_async(
            content,
            language=language,
            word_timestamps=word_ts,
            segment_max_seconds=DEFAULT_ASR_SEGMENT_SECONDS,
            parallel=parallel,
            max_workers=max_workers,
        )

        # 2. 说话人分离
        diarization: DiarizationResult | None = None
        if enable_diar:
            diarizer = self._get_diarizer()
            diarization = await diarizer.diarize_async(
                content, min_speakers=min_spk, max_speakers=max_spk
            )

        # 3. 时间戳对齐
        aligner = self._get_aligner()
        aligned_segments = aligner.align(asr_result, diarization)

        if not aligned_segments:
            return []

        # 4. 按时间窗口切分（windowSize 毫秒）
        window_seconds = config.windowSize / 1000.0
        chunks = self._split_by_window(
            aligned_segments,
            window_seconds=window_seconds,
            overlap_ratio=config.overlap,
            source=source,
            config=config,
            duration=duration,
            asr_engine=asr_engine.engine_name,
            diar_engine=diarization.engine if diarization else "none",
        )

        # 5. 可选：对每个切片的文本用 TextChunker 二次切片
        if enable_text_chunker and chunks:
            chunks = await self._apply_text_chunker(chunks, config, language)

        return chunks

    async def _postprocess(self, chunks: list[Chunk], config: ChunkConfig) -> list[Chunk]:
        """后处理：计算 tokens + 重排 index + 过滤空切片.

        :param chunks: 切片列表
        :param config: 切片配置
        :return: 处理后的切片列表
        """
        # 过滤空内容切片
        filtered: list[Chunk] = []
        for c in chunks:
            text = c.content if isinstance(c.content, str) else ""
            if text.strip():
                filtered.append(c)

        # 计算 tokens + 重排 index
        for i, c in enumerate(filtered):
            text = c.content if isinstance(c.content, str) else ""
            c.tokens = self._count_tokens(text)
            c.metadata = c.metadata.model_copy(update={"index": i})
        return filtered

    # ------------------------------------------------------------------
    # 按时间窗口切分
    # ------------------------------------------------------------------

    def _split_by_window(
        self,
        aligned_segments: list[AlignedSegment],
        *,
        window_seconds: float,
        overlap_ratio: float,
        source: str,
        config: ChunkConfig,
        duration: float,
        asr_engine: str,
        diar_engine: str,
    ) -> list[Chunk]:
        """按时间窗口将 AlignedSegment 切分为 Chunk.

        策略：
        - 若 window_seconds >= duration：所有段合并为一个切片
        - 否则按时间窗口滑窗，窗口内所有段合并为一个切片
        - 重叠区域：相邻切片共享 overlap_ratio * window_seconds 时长

        :return: Chunk 列表
        """
        if not aligned_segments:
            return []

        # 短音频：单切片
        if window_seconds >= duration or len(aligned_segments) == 1:
            return [self._build_chunk(aligned_segments, 0, source, config, duration, asr_engine, diar_engine)]

        # 按时间窗口滑窗
        chunks: list[Chunk] = []
        idx = 0
        overlap_seconds = window_seconds * overlap_ratio
        stride = max(0.1, window_seconds - overlap_seconds)

        t = 0.0
        while t < duration:
            window_end = min(t + window_seconds, duration)
            # 收集窗口内的段
            window_segs = self._collect_segments_in_window(
                aligned_segments, t, window_end
            )
            if window_segs:
                chunk = self._build_chunk(
                    window_segs,
                    idx,
                    source,
                    config,
                    duration,
                    asr_engine,
                    diar_engine,
                    window_start=t,
                    window_end=window_end,
                )
                chunks.append(chunk)
                idx += 1
            t += stride
            if window_end >= duration:
                break

        # 去重：完全相同的切片只保留一个
        return self._dedup_chunks(chunks)

    def _collect_segments_in_window(
        self,
        segments: list[AlignedSegment],
        start: float,
        end: float,
    ) -> list[AlignedSegment]:
        """收集时间窗口 [start, end] 内的段.

        段与窗口有交集即纳入（按段起始时间排序）。
        """
        result: list[AlignedSegment] = []
        for seg in segments:
            # 段与窗口有交集
            if seg.start < end and seg.end > start:
                result.append(seg)
        return result

    def _build_chunk(
        self,
        segments: list[AlignedSegment],
        index: int,
        source: str,
        config: ChunkConfig,
        duration: float,
        asr_engine: str,
        diar_engine: str,
        *,
        window_start: float | None = None,
        window_end: float | None = None,
    ) -> Chunk:
        """构建单个 Chunk.

        :param segments: 窗口内的 AlignedSegment 列表
        :return: Chunk
        """
        # 合并文本
        text_parts: list[str] = []
        speakers: set[str] = set()
        all_words: list[dict[str, Any]] = []
        seg_start = float("inf")
        seg_end = 0.0

        for seg in segments:
            text_parts.append(seg.text)
            speakers.add(seg.speaker)
            for w in seg.words:
                all_words.append(w.to_dict())
            seg_start = min(seg_start, seg.start)
            seg_end = max(seg_end, seg.end)

        if seg_start == float("inf"):
            seg_start = window_start or 0.0
        if seg_end == 0.0:
            seg_end = window_end or duration

        text = "".join(text_parts)
        extra = {
            "startTime": seg_start,
            "endTime": seg_end,
            "duration": seg_end - seg_start,
            "speakers": sorted(speakers),
            "speakerCount": len(speakers),
            "asrEngine": asr_engine,
            "diarEngine": diar_engine,
            "wordCount": len(all_words),
        }
        if window_start is not None:
            extra["windowStart"] = window_start
        if window_end is not None:
            extra["windowEnd"] = window_end
        # 携带词级时间戳（限制大小，避免过大）
        if all_words and len(all_words) <= 500:
            extra["words"] = all_words

        return Chunk(
            id=self._make_chunk_id(),
            content=text,
            metadata=self._make_metadata(
                config,
                index=index,
                start=int(seg_start * 1000),  # 毫秒
                end=int(seg_end * 1000),
                source=source,
                extra=extra,
            ),
        )

    def _dedup_chunks(self, chunks: list[Chunk]) -> list[Chunk]:
        """去除完全相同的切片（相同内容 + 相同时间范围）."""
        if not chunks:
            return chunks
        seen: set[tuple[str, int, int]] = set()
        result: list[Chunk] = []
        for c in chunks:
            key = (
                c.content if isinstance(c.content, str) else "",
                c.metadata.start,
                c.metadata.end,
            )
            if key not in seen:
                seen.add(key)
                result.append(c)
        return result

    # ------------------------------------------------------------------
    # TextChunker 二次切片
    # ------------------------------------------------------------------

    async def _apply_text_chunker(
        self,
        chunks: list[Chunk],
        config: ChunkConfig,
        language: str | None,
    ) -> list[Chunk]:
        """对每个音频切片的文本用 TextChunker 二次语义切片.

        保留原始音频时间戳与说话人信息。
        """
        text_chunker = self._get_text_chunker()
        text_config = ChunkConfig(
            modality=Modality.TEXT,
            windowSize=config.windowSize,
            overlap=config.overlap,
            maxTokens=config.maxTokens,
            language=language or "auto",
        )

        result: list[Chunk] = []
        for chunk in chunks:
            text = chunk.content if isinstance(chunk.content, str) else ""
            if not text.strip():
                continue
            try:
                sub_chunks = await text_chunker.chunk(text, text_config)
            except Exception:  # noqa: BLE001
                # TextChunker 失败：保留原切片
                result.append(chunk)
                continue
            if not sub_chunks:
                result.append(chunk)
                continue
            # 为每个子切片保留原音频元信息
            for sub in sub_chunks:
                extra = dict(chunk.metadata.extra)
                extra["textChunked"] = True
                extra["parentChunkId"] = chunk.id
                new_chunk = Chunk(
                    id=self._make_chunk_id(),
                    content=sub.content,
                    metadata=self._make_metadata(
                        config,
                        index=0,  # postprocess 会重排
                        start=chunk.metadata.start,
                        end=chunk.metadata.end,
                        source=chunk.metadata.source,
                        extra=extra,
                    ),
                )
                result.append(new_chunk)
        return result

    # ------------------------------------------------------------------
    # token 计数
    # ------------------------------------------------------------------

    def _count_tokens(self, text: str) -> int:
        """计算文本 token 数（中文按 1.5 字符/token 估算）."""
        if not text:
            return 0
        # 中文为主：按 1.5 字符/token
        cjk = sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")
        non_cjk = len(text) - cjk
        # 中文 1.5 字符/token，英文 4 字符/token
        cn_tokens = max(1, int(cjk / 1.5)) if cjk else 0
        en_tokens = max(1, non_cjk // 4) if non_cjk else 0
        return cn_tokens + en_tokens