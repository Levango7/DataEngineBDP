"""SentenceTransformer 通用适配器 (T008-6).

提供基于 ``sentence-transformers`` 库的通用适配器实现，
BGE / M3E 等本地模型均可复用此基类。

特性：
1. **懒加载**：首次调用 embed 时才加载模型，避免启动开销
2. **线程安全单例**：同一模型名全局共享一个 SentenceTransformer 实例
3. **依赖缺失回退**：sentence-transformers 未安装时抛 ModelUnavailableError
4. **离线模式**：``offline=True`` 时不下载模型，加载失败即不可用
5. **批量推理**：通过 ``batch_size`` 控制单次推理批量，避免显存溢出
6. **设备选择**：支持 cpu / cuda / mps / auto

对齐设计文档 T008-6。
"""
from __future__ import annotations

import threading
from typing import Any, Optional

from chunker.embedding.base import EmbeddingAdapter
from chunker.embedding.exceptions import (
    EmbeddingComputeError,
    ModelLoadError,
    ModelUnavailableError,
)

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: 类级单例缓存：模型名 -> SentenceTransformer 实例（避免重复加载）
_model_cache: dict[str, Any] = {}
_model_cache_lock = threading.Lock()


def is_sentence_transformers_available() -> bool:
    """检查 sentence-transformers 是否已安装."""
    try:
        import sentence_transformers  # noqa: F401

        return True
    except ImportError:
        return False


def clear_model_cache() -> None:
    """清空模型缓存（测试用）."""
    with _model_cache_lock:
        _model_cache.clear()


class SentenceTransformerAdapter(EmbeddingAdapter):
    """基于 sentence-transformers 的通用适配器.

    BGE / M3E 等本地 HuggingFace 模型均可使用此适配器。
    子类只需指定默认模型名与维度即可。

    用法::

        adapter = SentenceTransformerAdapter("BAAI/bge-large-zh", dimension=1024)
        vecs = await adapter.embed(["hello", "world"])
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
        query_instruction: Optional[str] = None,
    ) -> None:
        """初始化 SentenceTransformer 适配器.

        :param model: 模型完整标识（如 ``"BAAI/bge-large-zh"``）
        :param dimension: 向量维度
        :param normalize: 是否 L2 归一化
        :param device: 推理设备
        :param batch_size: 批量推理大小
        :param async_chunk: 异步分块大小
        :param cache_dir: 模型缓存目录
        :param offline: 离线模式
        :param query_instruction: 查询指令前缀（如 BGE 的 "为这个句子生成表示以用于检索相关文章："）
        """
        super().__init__(
            model,
            dimension=dimension,
            normalize=normalize,
            device=device,
            batch_size=batch_size,
            async_chunk=async_chunk,
            cache_dir=cache_dir,
            offline=offline,
        )
        self.queryInstruction = query_instruction

    # ------------------------------------------------------------------
    # 后端加载
    # ------------------------------------------------------------------

    def _load_backend(self) -> Any:
        """懒加载 SentenceTransformer 模型（线程安全单例）.

        :return: SentenceTransformer 实例
        :raises ModelUnavailableError: sentence-transformers 未安装
        :raises ModelLoadError: 模型加载失败
        """
        if not is_sentence_transformers_available():
            self._mark_unavailable("sentence-transformers 未安装")
            raise ModelUnavailableError(
                self.model,
                "sentence-transformers 未安装，请 pip install sentence-transformers",
            )

        name = self.model
        with _model_cache_lock:
            if name in _model_cache:
                return _model_cache[name]

        # 解析设备
        device = self._resolve_device()

        # 构造加载参数
        load_kwargs: dict[str, Any] = {"device": device}
        if self.cacheDir is not None:
            load_kwargs["cache_folder"] = self.cacheDir
        if self.offline:
            load_kwargs["local_files_only"] = True

        try:
            from sentence_transformers import SentenceTransformer
        except ImportError as ex:
            self._mark_unavailable("sentence-transformers 导入失败")
            raise ModelUnavailableError(
                self.model, f"sentence-transformers 导入失败: {ex}"
            ) from ex

        try:
            st_model = SentenceTransformer(name, **load_kwargs)
        except Exception as ex:  # noqa: BLE001
            self._mark_unavailable(f"模型加载失败: {ex}")
            raise ModelLoadError(
                f"加载模型 {name} 失败: {ex}", cause=ex
            ) from ex

        with _model_cache_lock:
            _model_cache[name] = st_model
        return st_model

    def _resolve_device(self) -> str:
        """解析实际设备.

        :return: 设备字符串（如 ``"cpu"`` / ``"cuda"`` / ``"mps"``）
        """
        if self.device == "auto":
            try:
                import torch

                if torch.cuda.is_available():
                    return "cuda"
                if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
                    return "mps"
            except ImportError:
                pass
            return "cpu"
        return self.device

    # ------------------------------------------------------------------
    # 推理
    # ------------------------------------------------------------------

    def _encode(self, texts: list[str], backend: Any) -> list[list[float]]:
        """同步批量嵌入.

        :param texts: 文本列表
        :param backend: SentenceTransformer 实例
        :return: 向量列表
        :raises EmbeddingComputeError: 计算失败
        """
        if not texts:
            return []
        try:
            emb = backend.encode(
                texts,
                batch_size=self.batchSize,
                show_progress_bar=False,
                convert_to_numpy=True,
                normalize_embeddings=False,  # 由基类统一归一化
            )
            return [list(map(float, row)) for row in emb]
        except Exception as ex:  # noqa: BLE001
            raise EmbeddingComputeError(
                f"SentenceTransformer 编码失败: {ex}", cause=ex
            ) from ex

    async def embed_query(self, text: str) -> list[float]:
        """单条查询嵌入.

        若设置了 ``queryInstruction``，会在查询文本前添加指令前缀
        （BGE 模型推荐做法）。

        :param text: 查询文本
        :return: 向量
        """
        if not text:
            return []
        query = text
        if self.queryInstruction:
            query = self.queryInstruction + text
        vecs = await self.embed([query])
        return vecs[0] if vecs else []