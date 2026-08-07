"""多模态切片器抽象基类.

定义统一切片流程：
    chunk(content, config)
      └── _preprocess(content, config) -> Any
      └── _split(preprocessed, config) -> list[Chunk]
      └── _postprocess(chunks, config) -> list[Chunk]

子类只需实现三个抽象方法即可获得完整切片能力。
通用工具方法：
    _count_tokens(text)            估算 token 数（默认按 4 字符/token）
    _overlap_merge(chunks, overlap) 重叠合并相邻切片
    _make_chunk_id()               生成切片 ID（uuid4）

对齐设计文档 T008-1。
"""
from __future__ import annotations

import time
import uuid
from abc import ABC, abstractmethod
from typing import Any

from chunker.exceptions import InvalidOverlapError
from chunker.models import Chunk, ChunkConfig, ChunkMetadata, ChunkResult, Modality


class BaseChunker(ABC):
    """多模态切片器抽象基类.

    子类必须实现：
        _preprocess(content, config) -> Any
        _split(preprocessed, config) -> list[Chunk]
        _postprocess(chunks, config) -> list[Chunk]

    可选覆盖：
        _count_tokens(text) -> int
        _overlap_merge(chunks, overlap) -> list[Chunk]
    """

    def __init__(self, modality: Modality | str | None = None) -> None:
        """初始化切片器.

        :param modality: 模态类型，子类可省略并在类属性 MODALITY 中声明
        """
        if modality is not None:
            self.modality = Modality(modality) if isinstance(modality, str) else modality
        else:
            self.modality = getattr(self, "MODALITY", Modality.TEXT)

    # ------------------------------------------------------------------
    # 抽象方法
    # ------------------------------------------------------------------

    @abstractmethod
    async def _preprocess(self, content: Any, config: ChunkConfig) -> Any:
        """预处理：清洗/归一化/解码等.

        :param content: 原始内容
        :param config: 切片配置
        :return: 预处理后的内容
        """

    @abstractmethod
    async def _split(self, preprocessed: Any, config: ChunkConfig) -> list[Chunk]:
        """切分：按模态特定策略将预处理内容切分为多个切片.

        :param preprocessed: 预处理后的内容
        :param config: 切片配置
        :return: 切片列表
        """

    @abstractmethod
    async def _postprocess(self, chunks: list[Chunk], config: ChunkConfig) -> list[Chunk]:
        """后处理：去重/合并/补全 metadata/计算 tokens 等.

        :param chunks: 切片列表
        :param config: 切片配置
        :return: 处理后的切片列表
        """

    # ------------------------------------------------------------------
    # 公共接口
    # ------------------------------------------------------------------

    async def chunk(self, content: Any, config: ChunkConfig) -> list[Chunk]:
        """统一切片入口.

        执行流程：preprocess -> split -> postprocess。
        子类不应直接覆盖此方法，而应实现三个抽象方法。

        :param content: 原始内容
        :param config: 切片配置
        :return: 切片列表
        """
        self._validate_config(config)
        preprocessed = await self._preprocess(content, config)
        chunks = await self._split(preprocessed, config)
        chunks = await self._postprocess(chunks, config)
        return chunks

    async def chunk_with_result(self, content: Any, config: ChunkConfig) -> ChunkResult:
        """切片并返回聚合结果（含耗时统计）.

        :param content: 原始内容
        :param config: 切片配置
        :return: ChunkResult 聚合结果
        """
        start_ts = time.perf_counter()
        chunks = await self.chunk(content, config)
        duration_ms = (time.perf_counter() - start_ts) * 1000.0
        total_tokens = sum(c.tokens or 0 for c in chunks)
        return ChunkResult(
            chunks=chunks,
            totalTokens=total_tokens,
            durationMs=duration_ms,
            modality=config.modality,
            source="",
        )

    # ------------------------------------------------------------------
    # 通用工具方法
    # ------------------------------------------------------------------

    def _validate_config(self, config: ChunkConfig) -> None:
        """校验配置语义合法性.

        :raises InvalidOverlapError: overlap * windowSize >= windowSize 时抛出
        """
        overlap_size = config.overlap_size()
        if overlap_size >= config.windowSize:
            raise InvalidOverlapError(overlap=config.overlap, windowSize=config.windowSize)

    def _count_tokens(self, text: str) -> int:
        """估算 token 数.

        默认按 4 字符/token 估算（适用于英文），
        子类可覆盖以接入 tiktoken / sentencepiece 等精确 tokenizer。

        :param text: 文本
        :return: token 数
        """
        if not text:
            return 0
        return max(1, len(text) // 4)

    def _overlap_merge(
        self, chunks: list[Chunk], overlap_size: int
    ) -> list[Chunk]:
        """重叠合并相邻切片.

        当切片策略产生的相邻切片存在重叠区域时，
        将重叠部分合并到后一切片的头部，确保语义连续。

        本基类提供的是占位实现（直接返回原列表），
        文本/代码等需要重叠的模态应覆盖此方法。

        :param chunks: 切片列表
        :param overlap_size: 重叠大小
        :return: 合并后的切片列表
        """
        return chunks

    def _make_chunk_id(self) -> str:
        """生成切片全局唯一 ID."""
        return uuid.uuid4().hex

    def _make_metadata(
        self,
        config: ChunkConfig,
        index: int,
        start: int = 0,
        end: int = 0,
        source: str = "",
        extra: dict[str, Any] | None = None,
    ) -> ChunkMetadata:
        """构造切片元数据的便捷方法."""
        return ChunkMetadata(
            modality=config.modality,
            source=source,
            start=start,
            end=end,
            index=index,
            extra=extra or {},
        )