"""M3E Embedding 适配器 (T008-6).

基于 sentence-transformers 加载 moka-ai/m3e-base 模型。

特性：
1. **中文优化**：M3E 在中文语义相似度任务上表现优秀
2. **无需查询指令**：M3E 不需要查询/文档区分，对称编码
3. **L2 归一化**：M3E 输出建议归一化
4. **维度 768**：m3e-base 默认维度 768

对齐设计文档 T008-6。
"""

from __future__ import annotations

from typing import Optional

from chunker.embedding.registry import register_adapter
from chunker.embedding.st_adapter import SentenceTransformerAdapter

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: M3E-base 模型完整标识
M3E_BASE_MODEL = "moka-ai/m3e-base"

#: M3E-small 模型完整标识
M3E_SMALL_MODEL = "moka-ai/m3e-small"

#: 各 M3E 模型默认维度
M3E_DIMENSIONS = {
    M3E_BASE_MODEL: 768,
    M3E_SMALL_MODEL: 384,
}


@register_adapter(
    "m3e-base",
    defaults={
        "dimension": 768,
        "normalize": True,
        "query_instruction": None,
    },
)
class M3EAdapter(SentenceTransformerAdapter):
    """M3E Embedding 适配器.

    支持 m3e-base / m3e-small。
    M3E 采用对称编码，查询与文档使用相同编码方式（无指令前缀）。

    用法::

        from chunker.embedding import get_adapter

        adapter = get_adapter("m3e-base")
        vecs = await adapter.embed(["你好世界"])
        query_vec = await adapter.embed_query("检索查询")
    """

    def __init__(
        self,
        model: str = M3E_BASE_MODEL,
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
        """初始化 M3E 适配器.

        :param model: 模型完整标识，默认 m3e-base
        :param dimension: 向量维度，0 表示按模型名自动推断
        :param normalize: 是否 L2 归一化
        :param device: 推理设备
        :param batch_size: 批量推理大小
        :param async_chunk: 异步分块大小
        :param cache_dir: 模型缓存目录
        :param offline: 离线模式
        :param query_instruction: 查询指令前缀（M3E 默认 None）
        """
        # 自动推断维度
        if dimension <= 0:
            dimension = M3E_DIMENSIONS.get(model, 0)
        # M3E 对称编码，默认无查询指令
        super().__init__(
            model,
            dimension=dimension,
            normalize=normalize,
            device=device,
            batch_size=batch_size,
            async_chunk=async_chunk,
            cache_dir=cache_dir,
            offline=offline,
            query_instruction=query_instruction,
        )


# 注册 m3e-small 变体
register_adapter(
    "m3e-small",
    M3EAdapter,
    defaults={
        "model": M3E_SMALL_MODEL,
        "dimension": 384,
        "normalize": True,
        "query_instruction": None,
    },
)
