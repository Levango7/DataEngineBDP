"""Embedding 适配器抽象基类 (T008-6).

定义统一接口：
    embed(texts)          批量嵌入
    embed_query(text)     单条查询嵌入
    dim()                 向量维度
    name()                模型名
    is_available()        是否可用

子类只需实现 ``_load_backend`` / ``_encode`` / ``_encode_query`` 即可获得完整能力。
通用工具方法：
    _normalize(vec)            L2 归一化
    _normalize_batch(vecs)     批量 L2 归一化
    _run_in_executor(func)     在线程池中运行同步函数

对齐设计文档 T008-6。
"""
from __future__ import annotations

import asyncio
import math
import threading
from abc import ABC, abstractmethod
from typing import Any, Optional

from chunker.embedding.exceptions import (
    EmbeddingComputeError,
    EmbeddingDimensionError,
    ModelLoadError,
    ModelUnavailableError,
)


class EmbeddingAdapter(ABC):
    """Embedding 适配器抽象基类.

    子类必须实现：
        _load_backend() -> Any          懒加载后端模型/客户端
        _encode(texts) -> list[list[float]]   批量同步嵌入

    可选覆盖：
        _encode_query(text) -> list[float]    单条查询嵌入（默认走 _encode）
        dim() -> int                          向量维度（默认 0=运行时探测）

    用法::

        from chunker.embedding import get_adapter

        adapter = get_adapter("bge-large-zh")
        vecs = await adapter.embed(["hello", "world"])
        query_vec = await adapter.embed_query("hello")
    """

    def __init__(
        self,
        model: str,
        *,
        dimension: int = 0,
        normalize: bool = True,
        device: str = "cpu",
        batch_size: int = 32,
        async_chunk: int = 16,
        cache_dir: Optional[str] = None,
        offline: bool = False,
    ) -> None:
        """初始化适配器.

        :param model: 模型完整标识（如 ``"BAAI/bge-large-zh"``）
        :param dimension: 向量维度，0 表示运行时探测
        :param normalize: 是否对输出向量做 L2 归一化
        :param device: 推理设备（cpu/cuda/mps）
        :param batch_size: 批量计算分块大小
        :param async_chunk: 异步分块大小
        :param cache_dir: 模型缓存目录
        :param offline: 是否离线模式
        """
        self.model = model
        self._declared_dim = dimension
        self.normalize = normalize
        self.device = device
        self.batchSize = batch_size
        self.asyncChunk = async_chunk
        self.cacheDir = cache_dir
        self.offline = offline

        # 后端懒加载
        self._backend: Any = None
        self._backend_loaded: bool = False
        self._backend_available: Optional[bool] = None
        self._lock = threading.Lock()
        # 运行时探测的维度缓存
        self._probed_dim: Optional[int] = None

    # ------------------------------------------------------------------
    # 抽象方法
    # ------------------------------------------------------------------

    @abstractmethod
    def _load_backend(self) -> Any:
        """懒加载后端模型/客户端.

        :return: 后端对象（如 SentenceTransformer / openai.Client）
        :raises ModelLoadError: 加载失败
        :raises ModelUnavailableError: 依赖缺失
        """

    @abstractmethod
    def _encode(self, texts: list[str], backend: Any) -> list[list[float]]:
        """同步批量嵌入（由子类实现具体推理逻辑）.

        :param texts: 文本列表
        :param backend: 后端对象
        :return: 向量列表（与输入同序）
        :raises EmbeddingComputeError: 计算失败
        """

    # ------------------------------------------------------------------
    # 公共接口
    # ------------------------------------------------------------------

    def name(self) -> str:
        """返回模型名."""
        return self.model

    def dim(self) -> int:
        """返回向量维度.

        优先返回声明维度，其次返回运行时探测维度，最后返回 0。
        """
        if self._declared_dim > 0:
            return self._declared_dim
        if self._probed_dim is not None:
            return self._probed_dim
        return 0

    def is_available(self) -> bool:
        """检查后端是否可用（不抛异常）."""
        if self._backend_available is not None:
            return self._backend_available
        try:
            self._ensure_backend()
            return True
        except (ModelLoadError, ModelUnavailableError):
            return False
        except Exception:  # noqa: BLE001
            return False

    async def embed(self, texts: list[str]) -> list[list[float]]:
        """批量异步嵌入.

        :param texts: 文本列表
        :return: 向量列表（与输入同序）
        :raises EmbeddingComputeError: 计算失败
        :raises ModelUnavailableError: 模型不可用
        """
        if not texts:
            return []
        backend = self._ensure_backend()
        # 分块异步执行
        results: list[list[float]] = [None] * len(texts)  # type: ignore[list-item]
        loop = asyncio.get_running_loop()

        async def _encode_chunk(start: int, batch: list[str]) -> None:
            def _work() -> list[list[float]]:
                return self._encode(batch, backend)

            try:
                out = await loop.run_in_executor(None, _work)
            except (ModelLoadError, ModelUnavailableError, EmbeddingComputeError):
                raise
            except Exception as ex:  # noqa: BLE001
                raise EmbeddingComputeError(
                    f"embedding 计算失败: {ex}", cause=ex
                ) from ex
            for i, row in enumerate(out):
                results[start + i] = row

        tasks = []
        chunk = max(1, self.asyncChunk)
        for i in range(0, len(texts), chunk):
            batch = texts[i : i + chunk]
            tasks.append(_encode_chunk(i, batch))
        await asyncio.gather(*tasks)

        # 归一化
        if self.normalize:
            results = [self._normalize(r) if r else r for r in results]

        # 维度探测与校验
        if results:
            actual_dim = len(results[0])
            if self._probed_dim is None:
                self._probed_dim = actual_dim
            declared = self.dim()
            if declared > 0 and actual_dim != declared:
                raise EmbeddingDimensionError(declared, actual_dim)

        return results

    async def embed_query(self, text: str) -> list[float]:
        """单条查询嵌入.

        默认走 ``embed``，子类可覆盖以走更高效的查询编码路径
        （如 BGE 的 query instruction 前缀）。

        :param text: 查询文本
        :return: 向量
        """
        if not text:
            return []
        vecs = await self.embed([text])
        return vecs[0] if vecs else []

    # ------------------------------------------------------------------
    # 通用工具方法
    # ------------------------------------------------------------------

    def _ensure_backend(self) -> Any:
        """确保后端已加载（线程安全懒加载）.

        :return: 后端对象
        :raises ModelLoadError: 加载失败
        :raises ModelUnavailableError: 依赖缺失
        """
        if self._backend_loaded and self._backend is not None:
            return self._backend
        with self._lock:
            if self._backend_loaded and self._backend is not None:
                return self._backend
        # 在锁外加载后端（_load_backend 可能调用 _mark_unavailable 获取锁）
        backend = self._load_backend()
        with self._lock:
            self._backend = backend
            self._backend_loaded = True
            self._backend_available = True
        return backend

    @staticmethod
    def _normalize(vec: list[float]) -> list[float]:
        """L2 归一化向量.

        :param vec: 输入向量
        :return: 归一化后的向量；零向量返回原向量
        """
        if not vec:
            return vec
        norm = math.sqrt(sum(x * x for x in vec))
        if norm == 0.0:
            return vec
        return [x / norm for x in vec]

    def _normalize_batch(self, vecs: list[list[float]]) -> list[list[float]]:
        """批量 L2 归一化.

        :param vecs: 向量列表
        :return: 归一化后的向量列表
        """
        return [self._normalize(v) for v in vecs]

    def _mark_unavailable(self, reason: str = "") -> None:
        """标记后端不可用（子类加载失败时调用）.

        :param reason: 不可用原因
        """
        with self._lock:
            self._backend_available = False
            self._backend_loaded = True
        self._unavailable_reason = reason

    @property
    def unavailable_reason(self) -> str:
        """后端不可用原因（若已标记）."""
        return getattr(self, "_unavailable_reason", "")