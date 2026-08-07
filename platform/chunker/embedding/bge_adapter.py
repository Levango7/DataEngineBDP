"""BGE Embedding 适配器 (T008-6).

基于 sentence-transformers 加载 BAAI/bge-large-zh 模型。

特性：
1. **中英文双语**：bge-large-zh 在中文与中英混合文本上表现优秀
2. **查询指令前缀**：检索时为查询添加 "为这个句子生成表示以用于检索相关文章：" 前缀
3. **L2 归一化**：BGE 输出需归一化后与 IP 度量配合使用
4. **维度 1024**：bge-large-zh 默认维度 1024

对齐设计文档 T008-6。
"""
from __future__ import annotations

from typing import Any, Optional

from chunker.embedding.registry import register_adapter
from chunker.embedding.st_adapter import SentenceTransformerAdapter

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: BGE-large-zh 模型完整标识
BGE_LARGE_ZH_MODEL = "BAAI/bge-large-zh"

#: BGE-large-en 模型完整标识
BGE_LARGE_EN_MODEL = "BAAI/bge-large-en"

#: BGE-small-zh 模型完整标识
BGE_SMALL_ZH_MODEL = "BAAI/bge-small-zh"

#: BGE 中文查询指令前缀（提升检索效果）
BGE_ZH_QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："

#: BGE 英文查询指令前缀
BGE_EN_QUERY_INSTRUCTION = "Represent this sentence for searching relevant passages: "

#: 各 BGE 模型默认维度
BGE_DIMENSIONS = {
    BGE_LARGE_ZH_MODEL: 1024,
    BGE_LARGE_EN_MODEL: 1024,
    BGE_SMALL_ZH_MODEL: 512,
}


@register_adapter(
    "bge-large-zh",
    defaults={
        "dimension": 1024,
        "normalize": True,
        "query_instruction": BGE_ZH_QUERY_INSTRUCTION,
    },
)
class BGEAdapter(SentenceTransformerAdapter):
    """BGE Embedding 适配器.

    支持 bge-large-zh / bge-large-en / bge-small-zh。
    通过模型名自动选择维度与查询指令。

    用法::

        from chunker.embedding import get_adapter

        adapter = get_adapter("bge-large-zh")
        vecs = await adapter.embed(["你好世界"])
        query_vec = await adapter.embed_query("检索查询")
    """

    def __init__(
        self,
        model: str = BGE_LARGE_ZH_MODEL,
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
        """初始化 BGE 适配器.

        :param model: 模型完整标识，默认 bge-large-zh
        :param dimension: 向量维度，0 表示按模型名自动推断
        :param normalize: 是否 L2 归一化
        :param device: 推理设备
        :param batch_size: 批量推理大小
        :param async_chunk: 异步分块大小
        :param cache_dir: 模型缓存目录
        :param offline: 离线模式
        :param query_instruction: 查询指令前缀，None 表示按模型自动选择
        """
        # 自动推断维度
        if dimension <= 0:
            dimension = BGE_DIMENSIONS.get(model, 0)
        # 自动推断查询指令
        if query_instruction is None:
            if "zh" in model:
                query_instruction = BGE_ZH_QUERY_INSTRUCTION
            else:
                query_instruction = BGE_EN_QUERY_INSTRUCTION
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


# 注册其他 BGE 变体
register_adapter(
    "bge-large-en",
    BGEAdapter,
    defaults={
        "model": BGE_LARGE_EN_MODEL,
        "dimension": 1024,
        "normalize": True,
        "query_instruction": BGE_EN_QUERY_INSTRUCTION,
    },
)
register_adapter(
    "bge-small-zh",
    BGEAdapter,
    defaults={
        "model": BGE_SMALL_ZH_MODEL,
        "dimension": 512,
        "normalize": True,
        "query_instruction": BGE_ZH_QUERY_INSTRUCTION,
    },
)