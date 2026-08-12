"""ASR (自动语音识别) 子模块 (T008-5).

提供语音转文本能力，包含三个子组件：

1. **whisper_engine**：Whisper ASR 引擎封装
   - 优先使用 ``openai-whisper``（本地 GPU/CPU 推理）
   - 不可用时自动回退为 Mock 引擎（基于能量启发式生成占位文本）
   - 支持词级时间戳（``word_timestamps=True``）
2. **diarization**：说话人分离
   - 优先使用 ``pyannote.audio``（深度学习模型）
   - 不可用时回退为基于短时能量的简单分离
   - 输出 ``(speaker, start, end)`` 时间段列表
3. **timestamp_aligner**：时间戳对齐
   - 将 ASR 词级时间戳与说话人时间段对齐
   - 合并为 ``AlignedSegment``（含文本、起止时间、说话人、词列表）

对齐设计文档 T008-5：语音切片(ASR转文本)。
"""

from __future__ import annotations

from chunker.asr.diarization import (
    DiarizationResult,
    Diarizer,
    EnergyDiarizer,
    PyannoteDiarizer,
    SpeakerSegment,
)
from chunker.asr.timestamp_aligner import (
    AlignedSegment,
    AlignedWord,
    TimestampAligner,
    align_segments,
)
from chunker.asr.whisper_engine import (
    ASRResult,
    MockWhisperEngine,
    WhisperEngine,
    WhisperWord,
    is_whisper_available,
)

__all__ = [
    # Whisper 引擎
    "ASRResult",
    "WhisperEngine",
    "MockWhisperEngine",
    "WhisperWord",
    "is_whisper_available",
    # 说话人分离
    "Diarizer",
    "PyannoteDiarizer",
    "EnergyDiarizer",
    "SpeakerSegment",
    "DiarizationResult",
    # 时间戳对齐
    "AlignedSegment",
    "AlignedWord",
    "TimestampAligner",
    "align_segments",
]
