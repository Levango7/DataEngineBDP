"""时间戳对齐 (T008-5).

将 ASR 词级时间戳与说话人分离结果对齐，生成统一的 ``AlignedSegment``：

1. **词级对齐**：每个词携带说话人标签 + 起止时间
2. **段级合并**：按说话人切换边界合并词为段
3. **跨段修正**：处理 ASR 段与说话人段边界不一致的情况
   - ASR 段跨越多个说话人 → 按说话人切换点拆分
   - 说话人段无对应 ASR 词 → 跳过（静音段）
4. **时间偏移**：支持全局时间偏移（分段转录时用）

对齐设计文档 T008-5。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from chunker.asr.diarization import DiarizationResult, SpeakerSegment
from chunker.asr.whisper_engine import ASRResult

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: 时间对齐容差（秒），小于此差异视为同一时间点
ALIGN_TOLERANCE_SECONDS = 0.05

#: 默认说话人标签（无分离结果时）
DEFAULT_SPEAKER = "SPEAKER_00"


# ----------------------------------------------------------------------
# 数据结构
# ----------------------------------------------------------------------


@dataclass
class AlignedWord:
    """对齐后的词（携带说话人标签）."""

    word: str
    start: float
    end: float
    speaker: str = DEFAULT_SPEAKER
    probability: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        return {
            "word": self.word,
            "start": self.start,
            "end": self.end,
            "speaker": self.speaker,
            "probability": self.probability,
        }


@dataclass
class AlignedSegment:
    """对齐后的段（同一说话人的连续词）.

    一个 AlignedSegment 对应一个说话人的一段连续话语。
    """

    text: str
    start: float
    end: float
    speaker: str = DEFAULT_SPEAKER
    words: list[AlignedWord] = field(default_factory=list)
    confidence: float = 0.0

    @property
    def duration(self) -> float:
        """段时长."""
        return self.end - self.start

    @property
    def word_count(self) -> int:
        """词数."""
        return len(self.words)

    def to_dict(self) -> dict[str, Any]:
        return {
            "text": self.text,
            "start": self.start,
            "end": self.end,
            "speaker": self.speaker,
            "words": [w.to_dict() for w in self.words],
            "confidence": self.confidence,
        }


# ----------------------------------------------------------------------
# TimestampAligner
# ----------------------------------------------------------------------


class TimestampAligner:
    """时间戳对齐器.

    将 ASR 结果与说话人分离结果对齐，生成 ``AlignedSegment`` 列表。

    用法::

        aligner = TimestampAligner()
        segments = aligner.align(asr_result, diarization_result)
        for seg in segments:
            print(f"[{seg.start:.1f}-{seg.end:.1f}] {seg.speaker}: {seg.text}")
    """

    def __init__(self, *, tolerance: float = ALIGN_TOLERANCE_SECONDS) -> None:
        """初始化对齐器.

        :param tolerance: 时间对齐容差（秒）
        """
        self.tolerance = tolerance

    def align(
        self,
        asr_result: ASRResult,
        diarization: DiarizationResult | None = None,
        *,
        time_offset: float = 0.0,
    ) -> list[AlignedSegment]:
        """对齐 ASR 结果与说话人分离结果.

        :param asr_result: ASR 转录结果
        :param diarization: 说话人分离结果（None 表示单说话人）
        :param time_offset: 全局时间偏移（秒）
        :return: AlignedSegment 列表（按时间排序）
        """
        # 收集所有词
        words = self._collect_words(asr_result, time_offset)
        if not words:
            return []

        # 为每个词分配说话人
        if diarization is not None and diarization.segments:
            self._assign_speakers(words, diarization)
        # 否则保持默认 DEFAULT_SPEAKER

        # 按说话人切换边界合并词为段
        segments = self._merge_to_segments(words)
        return segments

    # ------------------------------------------------------------------
    # 词收集
    # ------------------------------------------------------------------

    def _collect_words(self, asr_result: ASRResult, time_offset: float) -> list[AlignedWord]:
        """从 ASR 结果收集所有词，应用时间偏移."""
        words: list[AlignedWord] = []
        for seg in asr_result.segments:
            if seg.words:
                for w in seg.words:
                    if not w.word:
                        continue
                    words.append(
                        AlignedWord(
                            word=w.word,
                            start=w.start + time_offset,
                            end=w.end + time_offset,
                            probability=w.probability,
                        )
                    )
            elif seg.text:
                # 无词级时间戳：用段级时间戳生成单"词"
                words.append(
                    AlignedWord(
                        word=seg.text,
                        start=seg.start + time_offset,
                        end=seg.end + time_offset,
                    )
                )
        return words

    # ------------------------------------------------------------------
    # 说话人分配
    # ------------------------------------------------------------------

    def _assign_speakers(self, words: list[AlignedWord], diarization: DiarizationResult) -> None:
        """为每个词分配说话人标签（基于词中点时间）."""
        diar_segments = diarization.segments
        for w in words:
            mid = (w.start + w.end) / 2.0
            speaker = self._find_speaker(mid, diar_segments)
            w.speaker = speaker

    def _find_speaker(self, time: float, segments: list[SpeakerSegment]) -> str:
        """查找时间点对应的说话人.

        :param time: 时间点
        :param segments: 说话人段列表
        :return: 说话人标签
        """
        for seg in segments:
            if seg.start - self.tolerance <= time < seg.end + self.tolerance:
                return seg.speaker
        # 无精确匹配：找最近的段
        nearest: SpeakerSegment | None = None
        min_dist = float("inf")
        for seg in segments:
            if time < seg.start:
                dist = seg.start - time
            elif time > seg.end:
                dist = time - seg.end
            else:
                dist = 0.0
            if dist < min_dist:
                min_dist = dist
                nearest = seg
        return nearest.speaker if nearest is not None else DEFAULT_SPEAKER

    # ------------------------------------------------------------------
    # 段合并
    # ------------------------------------------------------------------

    def _merge_to_segments(self, words: list[AlignedWord]) -> list[AlignedSegment]:
        """按说话人切换边界合并词为段."""
        if not words:
            return []

        segments: list[AlignedSegment] = []
        buf: list[AlignedWord] = []

        def _flush() -> None:
            if not buf:
                return
            text = "".join(w.word for w in buf)
            start = buf[0].start
            end = buf[-1].end
            speaker = buf[0].speaker
            conf = sum(w.probability for w in buf) / len(buf) if buf else 0.0
            segments.append(
                AlignedSegment(
                    text=text,
                    start=start,
                    end=end,
                    speaker=speaker,
                    words=list(buf),
                    confidence=conf,
                )
            )
            buf.clear()

        current_speaker: str | None = None
        for w in words:
            if current_speaker is None:
                current_speaker = w.speaker
                buf.append(w)
            elif w.speaker == current_speaker:
                buf.append(w)
            else:
                # 说话人切换
                _flush()
                current_speaker = w.speaker
                buf.append(w)
        _flush()
        return segments


# ----------------------------------------------------------------------
# 便捷函数
# ----------------------------------------------------------------------


def align_segments(
    asr_result: ASRResult,
    diarization: DiarizationResult | None = None,
    *,
    tolerance: float = ALIGN_TOLERANCE_SECONDS,
    time_offset: float = 0.0,
) -> list[AlignedSegment]:
    """对齐 ASR 结果与说话人分离结果（便捷函数）.

    :param asr_result: ASR 转录结果
    :param diarization: 说话人分离结果
    :param tolerance: 时间对齐容差
    :param time_offset: 全局时间偏移
    :return: AlignedSegment 列表
    """
    aligner = TimestampAligner(tolerance=tolerance)
    return aligner.align(asr_result, diarization, time_offset=time_offset)
