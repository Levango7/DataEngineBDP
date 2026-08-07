"""OpenAI Embedding 适配器 (T008-6).

基于 OpenAI API（text-embedding-3-small / text-embedding-3-large）。

特性：
1. **API 调用**：通过 HTTP 调用 OpenAI / 兼容端点（如 Azure OpenAI、本地 vLLM）
2. **依赖缺失回退**：openai 库未安装时抛 ModelUnavailableError
3. **API Key 缺失回退**：未配置 API Key 时抛 ModelUnavailableError
4. **批量分页**：OpenAI 单次最多 2048 输入，自动分页
5. **维度可配**：text-embedding-3-small 默认 1536，支持 dimensions 参数降维
6. **已归一化**：OpenAI 输出已归一化，无需再次归一化
7. **Mock 模式**：``api_key="mock"`` 时使用确定性哈希模拟向量，便于测试

对齐设计文档 T008-6。
"""
from __future__ import annotations

import asyncio
import hashlib
import threading
from typing import Any, Optional

from chunker.embedding.base import EmbeddingAdapter
from chunker.embedding.exceptions import (
    EmbeddingComputeError,
    EmbeddingDimensionError,
    ModelLoadError,
    ModelUnavailableError,
)
from chunker.embedding.registry import register_adapter

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: OpenAI text-embedding-3-small 模型
OPENAI_SMALL_MODEL = "text-embedding-3-small"

#: OpenAI text-embedding-3-large 模型
OPENAI_LARGE_MODEL = "text-embedding-3-large"

#: OpenAI 默认 API 基址
DEFAULT_BASE_URL = "https://api.openai.com/v1"

#: OpenAI 单次请求最大输入数
OPENAI_MAX_INPUTS = 2048

#: OpenAI 单次请求最大 token 数
OPENAI_MAX_TOKENS = 8191

#: 各 OpenAI 模型默认维度
OPENAI_DIMENSIONS = {
    OPENAI_SMALL_MODEL: 1536,
    OPENAI_LARGE_MODEL: 3072,
}

#: Mock API Key 标志
MOCK_API_KEY = "mock"

#: 类级单例缓存：客户端 key -> client 实例
_client_cache: dict[str, Any] = {}
_client_cache_lock = threading.Lock()


def is_openai_available() -> bool:
    """检查 openai 库是否已安装."""
    try:
        import openai  # noqa: F401

        return True
    except ImportError:
        return False


def clear_client_cache() -> None:
    """清空客户端缓存（测试用）."""
    with _client_cache_lock:
        _client_cache.clear()


@register_adapter(
    "openai",
    defaults={
        "dimension": 1536,
        "normalize": False,
    },
)
class OpenAIAdapter(EmbeddingAdapter):
    """OpenAI Embedding 适配器.

    支持 text-embedding-3-small / text-embedding-3-large。
    通过 HTTP 调用 OpenAI API 或兼容端点。

    特殊模式：
        - ``api_key="mock"``：使用确定性哈希模拟向量，无需网络与 API Key，便于测试

    用法::

        from chunker.embedding import get_adapter

        # 生产用法
        adapter = get_adapter("openai", api_key="sk-xxx")
        vecs = await adapter.embed(["hello"])

        # 测试用法（mock 模式）
        adapter = get_adapter("openai", api_key="mock")
        vecs = await adapter.embed(["hello"])
    """

    def __init__(
        self,
        model: str = OPENAI_SMALL_MODEL,
        *,
        dimension: int = 0,
        normalize: bool = False,
        device: str = "cpu",
        batch_size: int = 32,
        async_chunk: int = 16,
        cache_dir: Optional[str] = None,
        offline: bool = False,
        api_key: Optional[str] = None,
        base_url: str = DEFAULT_BASE_URL,
        timeout: float = 30.0,
        request_dimensions: Optional[int] = None,
    ) -> None:
        """初始化 OpenAI 适配器.

        :param model: 模型名（text-embedding-3-small / text-embedding-3-large）
        :param dimension: 向量维度，0 表示按模型名自动推断
        :param normalize: 是否 L2 归一化（OpenAI 已归一化，默认 False）
        :param device: 推理设备（OpenAI 不使用本地设备，仅用于接口兼容）
        :param batch_size: 批量请求大小
        :param async_chunk: 异步分块大小
        :param cache_dir: 缓存目录（OpenAI 不使用，仅用于接口兼容）
        :param offline: 离线模式（OpenAI 不适用，仅用于接口兼容）
        :param api_key: OpenAI API Key；``"mock"`` 表示 mock 模式
        :param base_url: API 基址（可指向 Azure OpenAI / 本地兼容端点）
        :param timeout: 请求超时秒
        :param request_dimensions: 请求时指定的输出维度（降维），None 表示使用模型默认
        """
        # 自动推断维度
        if dimension <= 0:
            dimension = OPENAI_DIMENSIONS.get(model, 0)
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
        self.apiKey = api_key
        self.baseUrl = base_url
        self.timeout = timeout
        self.requestDimensions = request_dimensions

    # ------------------------------------------------------------------
    # 后端加载
    # ------------------------------------------------------------------

    def _load_backend(self) -> Any:
        """加载 OpenAI 客户端或 Mock 后端.

        :return: 后端对象（openai.Client 或 "mock" 字符串）
        :raises ModelUnavailableError: 依赖缺失或 API Key 缺失
        """
        # Mock 模式
        if self.apiKey == MOCK_API_KEY:
            return "mock"

        # 真实模式：需要 API Key
        if not self.apiKey:
            self._mark_unavailable("未配置 API Key")
            raise ModelUnavailableError(
                self.model,
                "未配置 OpenAI API Key，请设置 api_key 参数或 "
                "CHUNKER_EMBEDDING_OPENAI_API_KEY 环境变量",
            )

        if not is_openai_available():
            self._mark_unavailable("openai 库未安装")
            raise ModelUnavailableError(
                self.model,
                "openai 库未安装，请 pip install openai",
            )

        cache_key = f"{self.apiKey}@{self.baseUrl}"
        with _client_cache_lock:
            if cache_key in _client_cache:
                return _client_cache[cache_key]

        try:
            import openai
        except ImportError as ex:
            self._mark_unavailable("openai 库导入失败")
            raise ModelUnavailableError(
                self.model, f"openai 库导入失败: {ex}"
            ) from ex

        try:
            client = openai.AsyncOpenAI(
                api_key=self.apiKey,
                base_url=self.baseUrl,
                timeout=self.timeout,
            )
        except Exception as ex:  # noqa: BLE001
            self._mark_unavailable(f"客户端创建失败: {ex}")
            raise ModelLoadError(
                f"创建 OpenAI 客户端失败: {ex}", cause=ex
            ) from ex

        with _client_cache_lock:
            _client_cache[cache_key] = client
        return client

    # ------------------------------------------------------------------
    # 推理
    # ------------------------------------------------------------------

    def _encode(self, texts: list[str], backend: Any) -> list[list[float]]:
        """同步批量嵌入（实际由 async embed 调用，此处仅 mock 同步路径）.

        对于真实 OpenAI 后端，``embed`` 会直接走异步路径，不会调用此方法。
        对于 mock 后端，此方法生成确定性哈希向量。

        :param texts: 文本列表
        :param backend: 后端对象（"mock" 或 openai.AsyncOpenAI）
        :return: 向量列表
        """
        if backend == "mock":
            return [self._mock_embed(t) for t in texts]
        # 真实后端不应走到这里（embed 会走异步路径）
        # 但为兼容基类接口，提供同步回退
        raise EmbeddingComputeError(
            "OpenAI 真实后端需通过 async embed 调用，不支持同步 _encode"
        )

    async def embed(self, texts: list[str]) -> list[list[float]]:
        """批量异步嵌入.

        Mock 模式走同步哈希；真实模式走 OpenAI Async API。

        :param texts: 文本列表
        :return: 向量列表
        :raises EmbeddingComputeError: 计算失败
        :raises ModelUnavailableError: 模型不可用
        """
        if not texts:
            return []
        backend = self._ensure_backend()

        # Mock 模式
        if backend == "mock":
            results = [self._mock_embed(t) for t in texts]
            if self.normalize:
                results = [self._normalize(r) for r in results]
            return results

        # 真实 OpenAI 异步路径
        results: list[list[float]] = [None] * len(texts)  # type: ignore[list-item]

        async def _encode_chunk(start: int, batch: list[str]) -> None:
            try:
                kwargs: dict[str, Any] = {
                    "model": self.model,
                    "input": batch,
                }
                if self.requestDimensions is not None:
                    kwargs["dimensions"] = self.requestDimensions
                resp = await backend.embeddings.create(**kwargs)
                for i, item in enumerate(resp.data):
                    results[start + i] = list(map(float, item.embedding))
            except Exception as ex:  # noqa: BLE001
                raise EmbeddingComputeError(
                    f"OpenAI embedding 请求失败: {ex}", cause=ex
                ) from ex

        tasks = []
        chunk = min(self.asyncChunk, OPENAI_MAX_INPUTS)
        for i in range(0, len(texts), chunk):
            batch = texts[i : i + chunk]
            tasks.append(_encode_chunk(i, batch))
        await asyncio.gather(*tasks)

        # 归一化（OpenAI 已归一化，默认 normalize=False）
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

    # ------------------------------------------------------------------
    # Mock 向量生成
    # ------------------------------------------------------------------

    def _mock_embed(self, text: str) -> list[float]:
        """生成确定性 mock 向量（基于哈希）.

        同一文本始终生成同一向量，便于测试断言。
        向量维度由 ``dim()`` 决定，默认 1536。

        :param text: 输入文本
        :return: mock 向量
        """
        d = self.dim() or 1536
        # 使用 SHA256 哈希生成确定性种子
        h = hashlib.sha256(text.encode("utf-8")).digest()
        # 扩展哈希到所需长度
        seed_bytes = bytearray()
        counter = 0
        while len(seed_bytes) < d * 4:
            seed_bytes.extend(
                hashlib.sha256(h + counter.to_bytes(4, "big")).digest()
            )
            counter += 1
        # 转为 float 向量（[-1, 1] 范围）
        import struct

        vec: list[float] = []
        for i in range(d):
            chunk = seed_bytes[i * 4 : i * 4 + 4]
            val = struct.unpack("f", chunk)[0]
            # 归一化到 [-1, 1]
            if val != val:  # NaN 检查
                val = 0.0
            val = max(-1.0, min(1.0, val))
            vec.append(val)
        return vec


# 注册 openai-small / openai-large 变体
register_adapter(
    "openai-small",
    OpenAIAdapter,
    defaults={
        "model": OPENAI_SMALL_MODEL,
        "dimension": 1536,
        "normalize": False,
    },
)
register_adapter(
    "openai-large",
    OpenAIAdapter,
    defaults={
        "model": OPENAI_LARGE_MODEL,
        "dimension": 3072,
        "normalize": False,
    },
)