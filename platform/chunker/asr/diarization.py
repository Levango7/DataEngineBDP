"""说话人分离 (Diarization) (T008-5).

识别音频中"谁在什么时间说话"，支持：

1. **pyannote.audio**：深度学习说话人分离（首选）
   - 基于 ``pyannote/speaker-diarization`` 模型
   - 支持 ≥2 说话人，准确率 ≥ 80%
   - 需要 HuggingFace token + 模型下载
2. **能量启发式分离**：无 pyannote 时的回退方案
   - 基于短时能量 + 静音段切换检测说话人变化
   - 准确率较低（约 60%），但无外部依赖
   - 通过能量谷值点切换说话人标签
3. **统一接口**：``Diarizer`` 抽象基类
   - ``diarize(audio) -> DiarizationResult``
   - 输出 ``SpeakerSegment(speaker, start, end)`` 列表
4. **异步接口**：``diarize_async`` 不阻塞事件循环

对齐设计文档 T008-5。
"""
from __future__ import annotations

import asyncio
import os
import threading
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any

from chunker.asr.whisper_engine import (
    WHISPER_SAMPLE_RATE,
    load_audio,
)

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: 默认最大说话人数
DEFAULT_MAX_SPEAKERS = 8

#: 默认最小说话人数
DEFAULT_MIN_SPEAKERS = 2

#: 能量分离：静音阈值倍数（相对中位数能量）
ENERGY_SILENCE_RATIO = 0.3

#: 能量分离：最小静音时长（秒），短于此不切换说话人
MIN_SILENCE_SECONDS = 0.5

#: 能量分离：帧长（秒）
ENERGY_FRAME_SECONDS = 0.3

#: 能量分离：最小说话段时长（秒）
MIN_SPEAKER_SEGMENT_SECONDS = 0.5


# ----------------------------------------------------------------------
# 可用性检测
# ----------------------------------------------------------------------


def is_pyannote_available() -> bool:
    """检测 ``pyannote.audio`` 是否可用."""
    try:
        import pyannote.audio  # type: ignore[import-untyped]  # noqa: F401

        return True
    except ImportError:
        return False


# ----------------------------------------------------------------------
# 数据结构
# ----------------------------------------------------------------------


@dataclass
class SpeakerSegment:
    """说话人时间段.

    表示某个说话人在 ``[start, end]`` 时间段内说话。
    """

    speaker: str  # 说话人标签，如 "SPEAKER_00"
    start: float  # 秒
    end: float  # 秒
    confidence: float = 1.0

    @property
    def duration(self) -> float:
        """段时长（秒）."""
        return self.end - self.start

    def to_dict(self) -> dict[str, Any]:
        return {
            "speaker": self.speaker,
            "start": self.start,
            "end": self.end,
            "confidence": self.confidence,
        }


@dataclass
class DiarizationResult:
    """说话人分离结果.

    携带说话人段列表、说话人集合、所用引擎。
    """

    segments: list[SpeakerSegment] = field(default_factory=list)
    speakers: list[str] = field(default_factory=list)
    engine: str = "energy"
    duration: float = 0.0

    @property
    def num_speakers(self) -> int:
        """说话人数量."""
        return len(self.speakers)

    def to_dict(self) -> dict[str, Any]:
        return {
            "segments": [s.to_dict() for s in self.segments],
            "speakers": list(self.speakers),
            "engine": self.engine,
            "duration": self.duration,
        }

    def speaker_at(self, time: float) -> str | None:
        """查询某时间点的说话人.

        :param time: 时间点（秒）
        :return: 说话人标签；无匹配返回 None
        """
        for seg in self.segments:
            if seg.start <= time < seg.end:
                return seg.speaker
        return None


# ----------------------------------------------------------------------
# Diarizer 抽象基类
# ----------------------------------------------------------------------


class Diarizer(ABC):
    """说话人分离器抽象基类."""

    @abstractmethod
    def diarize(
        self,
        content: Any,
        *,
        min_speakers: int = DEFAULT_MIN_SPEAKERS,
        max_speakers: int = DEFAULT_MAX_SPEAKERS,
    ) -> DiarizationResult:
        """同步说话人分离.

        :param content: 音频
        :param min_speakers: 最小说话人数
        :param max_speakers: 最大说话人数
        :return: DiarizationResult
        """

    async def diarize_async(
        self,
        content: Any,
        *,
        min_speakers: int = DEFAULT_MIN_SPEAKERS,
        max_speakers: int = DEFAULT_MAX_SPEAKERS,
    ) -> DiarizationResult:
        """异步说话人分离（默认在线程池执行同步版）."""
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            None,
            lambda: self.diarize(
                content, min_speakers=min_speakers, max_speakers=max_speakers
            ),
        )


# ----------------------------------------------------------------------
# PyannoteDiarizer
# ----------------------------------------------------------------------


class PyannoteDiarizer(Diarizer):
    """基于 ``pyannote.audio`` 的说话人分离器.

    需要安装 ``pyannote.audio`` 并配置 HuggingFace token。

    用法::

        diarizer = PyannoteDiarizer(hfToken="hf_xxx")
        result = await diarizer.diarize_async("/path/to/audio.wav")
        for seg in result.segments:
            print(f"{seg.start:.1f}-{seg.end:.1f}: {seg.speaker}")
    """

    _pipeline_lock = threading.Lock()
    _pipeline_cache: dict[str, Any] = {}

    def __init__(
        self,
        *,
        hfToken: str | None = None,
        modelName: str = "pyannote/speaker-diarization-3.1",
        useMock: bool = False,
    ) -> None:
        """初始化 pyannote 分离器.

        :param hfToken: HuggingFace 访问令牌
        :param modelName: pyannote 模型名
        :param useMock: 强制回退为能量分离（测试用）
        """
        self.hfToken = hfToken
        self.modelName = modelName
        self._use_fallback = useMock or not is_pyannote_available()
        self._pipeline: Any = None
        self._fallback: EnergyDiarizer | None = None

    @property
    def is_fallback(self) -> bool:
        """是否回退为能量分离."""
        return self._use_fallback

    @property
    def engine_name(self) -> str:
        """引擎名称."""
        return "energy" if self._use_fallback else "pyannote"

    def _load_pipeline(self) -> Any:
        """懒加载 pyannote pipeline."""
        if self._use_fallback:
            return None
        with self._pipeline_lock:
            if self.modelName in self._pipeline_cache:
                return self._pipeline_cache[self.modelName]
            try:
                from pyannote.audio import Pipeline  # type: ignore[import-untyped]

                pipeline = Pipeline.from_pretrained(
                    self.modelName, use_auth_token=self.hfToken
                )
                self._pipeline_cache[self.modelName] = pipeline
                return pipeline
            except Exception:  # noqa: BLE001
                self._use_fallback = True
                return None

    def _get_fallback(self) -> EnergyDiarizer:
        """获取能量分离器实例."""
        if self._fallback is None:
            self._fallback = EnergyDiarizer()
        return self._fallback

    def diarize(
        self,
        content: Any,
        *,
        min_speakers: int = DEFAULT_MIN_SPEAKERS,
        max_speakers: int = DEFAULT_MAX_SPEAKERS,
    ) -> DiarizationResult:
        """同步说话人分离."""
        if self._use_fallback:
            return self._get_fallback().diarize(
                content, min_speakers=min_speakers, max_speakers=max_speakers
            )

        pipeline = self._load_pipeline()
        if pipeline is None:
            return self._get_fallback().diarize(
                content, min_speakers=min_speakers, max_speakers=max_speakers
            )

        try:
            # 加载音频
            audio, duration = load_audio(content)
            # pyannote 需要 AudioFile 对象（文件路径或 dict）
            audio_input = content if isinstance(content, (str, os.PathLike)) else {"waveform": _to_tensor(audio), "sample_rate": WHISPER_SAMPLE_RATE}
            output = pipeline(
                audio_input,
                min_speakers=min_speakers,
                max_speakers=max_speakers,
            )
            return self._parse_pyannote_output(output, duration)
        except Exception:  # noqa: BLE001
            # 失败回退
            self._use_fallback = True
            return self._get_fallback().diarize(
                content, min_speakers=min_speakers, max_speakers=max_speakers
            )

    def _parse_pyannote_output(self, output: Any, duration: float) -> DiarizationResult:
        """解析 pyannote Annotation 输出."""
        segments: list[SpeakerSegment] = []
        speakers: set[str] = set()

        try:
            # pyannote Annotation: itertracks() yields (turn, _) -> (start, end), label
            for turn, _, label in output.itertracks(yield_label=True):
                seg = SpeakerSegment(
                    speaker=str(label),
                    start=float(turn.start),
                    end=float(turn.end),
                )
                segments.append(seg)
                speakers.add(seg.speaker)
        except Exception:  # noqa: BLE001
            pass

        segments.sort(key=lambda s: s.start)
        return DiarizationResult(
            segments=segments,
            speakers=sorted(speakers),
            engine=self.engine_name,
            duration=duration,
        )


def _to_tensor(audio: Any) -> Any:
    """将 numpy 数组转为 torch tensor（pyannote 要求）."""
    try:
        import torch  # type: ignore[import-untyped]

        # pyannote 期望 shape: (1, n_samples)
        return torch.from_numpy(audio).float().unsqueeze(0)
    except Exception:  # noqa: BLE001
        return audio


# ----------------------------------------------------------------------
# EnergyDiarizer
# ----------------------------------------------------------------------


class EnergyDiarizer(Diarizer):
    """基于短时能量的说话人分离器（无外部依赖回退方案）.

    算法：
    1. 计算短时能量序列
    2. 检测能量谷值点（静音段）作为说话人切换候选
    3. 在谷值点交替切换说话人标签（SPEAKER_00 / SPEAKER_01 / ...）
    4. 合并过短段，限制说话人数量

    准确率约 60%（仅适用于明显交替说话的场景），
    主要用于无 pyannote 环境的回退。
    """

    def __init__(self, *, num_speakers: int = 2) -> None:
        """初始化能量分离器.

        :param num_speakers: 说话人数量（能量法固定交替切换）
        """
        self.num_speakers = max(2, num_speakers)

    @property
    def engine_name(self) -> str:
        """引擎名称."""
        return "energy"

    def diarize(
        self,
        content: Any,
        *,
        min_speakers: int = DEFAULT_MIN_SPEAKERS,
        max_speakers: int = DEFAULT_MAX_SPEAKERS,
    ) -> DiarizationResult:
        """同步能量说话人分离."""
        try:
            audio, duration = load_audio(content)
        except Exception:  # noqa: BLE001
            return DiarizationResult(segments=[], speakers=[], engine="energy", duration=0.0)

        if duration <= 0 or len(audio) == 0:
            return DiarizationResult(segments=[], speakers=[], engine="energy", duration=0.0)

        # 计算短时能量
        energy_curve, frame_times = self._compute_energy_curve(audio, duration)

        if not energy_curve:
            # 整段归一个说话人
            spk = "SPEAKER_00"
            return DiarizationResult(
                segments=[SpeakerSegment(speaker=spk, start=0.0, end=duration)],
                speakers=[spk],
                engine="energy",
                duration=duration,
            )

        # 检测静音谷值点
        silence_points = self._detect_silence_points(energy_curve, frame_times, duration)

        # 在谷值点交替切换说话人
        segments = self._build_segments(silence_points, duration, min_speakers, max_speakers)

        # 合并过短段
        segments = self._merge_short_segments(segments)

        speakers = sorted({s.speaker for s in segments})
        return DiarizationResult(
            segments=segments,
            speakers=speakers,
            engine="energy",
            duration=duration,
        )

    def _compute_energy_curve(
        self, audio: Any, duration: float
    ) -> tuple[list[float], list[float]]:
        """计算短时能量曲线.

        :return: (energies, frame_times)
        """
        try:
            import numpy as np  # type: ignore[import-untyped]
        except ImportError:
            return [], []

        sr = WHISPER_SAMPLE_RATE
        frame_size = int(ENERGY_FRAME_SECONDS * sr)
        if frame_size <= 0:
            return [], []

        n_frames = len(audio) // frame_size
        if n_frames == 0:
            return [], []

        energies: list[float] = []
        times: list[float] = []
        for i in range(n_frames):
            frame = audio[i * frame_size : (i + 1) * frame_size]
            rms = float(np.sqrt(np.mean(frame ** 2)))
            energies.append(rms)
            times.append(i * ENERGY_FRAME_SECONDS)
        return energies, times

    def _detect_silence_points(
        self,
        energies: list[float],
        frame_times: list[float],
        duration: float,
    ) -> list[float]:
        """检测静音谷值点（说话人切换候选）.

        :return: 静音点时间列表（秒）
        """
        if not energies:
            return []

        try:
            import numpy as np  # type: ignore[import-untyped]
        except ImportError:
            return []

        median_energy = float(np.median(energies))
        threshold = median_energy * ENERGY_SILENCE_RATIO

        # 标记低能量帧
        is_silence = [e < threshold for e in energies]

        # 合并连续静音帧为静音段
        silence_segments: list[tuple[float, float]] = []
        in_sil = False
        sil_start = 0.0
        for i, v in enumerate(is_silence):
            t = frame_times[i]
            if v and not in_sil:
                in_sil = True
                sil_start = t
            elif not v and in_sil:
                in_sil = False
                sil_end = t
                if sil_end - sil_start >= MIN_SILENCE_SECONDS:
                    silence_segments.append((sil_start, sil_end))
        if in_sil:
            sil_end = duration
            if sil_end - sil_start >= MIN_SILENCE_SECONDS:
                silence_segments.append((sil_start, sil_end))

        # 取每个静音段的中点作为切换点
        return [(s + e) / 2.0 for s, e in silence_segments]

    def _build_segments(
        self,
        silence_points: list[float],
        duration: float,
        min_speakers: int,
        max_speakers: int,
    ) -> list[SpeakerSegment]:
        """在静音点交替切换说话人，构建段列表."""
        n_spk = min(max(min_speakers, self.num_speakers), max_speakers)
        boundaries = [0.0] + silence_points + [duration]
        segments: list[SpeakerSegment] = []
        spk_idx = 0
        for i in range(len(boundaries) - 1):
            start = boundaries[i]
            end = boundaries[i + 1]
            if end - start < MIN_SPEAKER_SEGMENT_SECONDS:
                continue
            speaker = f"SPEAKER_{spk_idx:02d}"
            segments.append(SpeakerSegment(speaker=speaker, start=start, end=end))
            spk_idx = (spk_idx + 1) % n_spk
        return segments

    def _merge_short_segments(self, segments: list[SpeakerSegment]) -> list[SpeakerSegment]:
        """合并过短段到前一段."""
        if not segments:
            return segments
        merged: list[SpeakerSegment] = [segments[0]]
        for seg in segments[1:]:
            if seg.duration < MIN_SPEAKER_SEGMENT_SECONDS and merged:
                # 合并到前一段
                prev = merged[-1]
                merged[-1] = SpeakerSegment(
                    speaker=prev.speaker,
                    start=prev.start,
                    end=seg.end,
                    confidence=min(prev.confidence, seg.confidence),
                )
            else:
                merged.append(seg)
        return merged


# ----------------------------------------------------------------------
# 便捷工厂
# ----------------------------------------------------------------------


def create_diarizer(
    *,
    prefer_pyannote: bool = True,
    hfToken: str | None = None,
    num_speakers: int = 2,
) -> Diarizer:
    """创建说话人分离器（自动选择最优可用引擎）.

    :param prefer_pyannote: 是否优先尝试 pyannote
    :param hfToken: HuggingFace 令牌（pyannote 用）
    :param num_speakers: 说话人数量（能量法用）
    :return: Diarizer 实例
    """
    if prefer_pyannote and is_pyannote_available():
        return PyannoteDiarizer(hfToken=hfToken)
    return EnergyDiarizer(num_speakers=num_speakers)