"""TextChunker 文本语义切片器单元测试 (T008-2).

覆盖场景：
    - 短文本 / 空文本 / 纯空白
    - 长文档切片
    - 多语言（中文 / 英文 / 混合）
    - Markdown 结构感知
    - 滑动窗口 / 重叠
    - 语义边界识别（embedding 可用 / 不可用回退）
    - token 计数（tiktoken / bge / 回退）
    - maxTokens 硬截断
    - 性能（长文档耗时）
    - 注册机制
    - 异常处理
"""

from __future__ import annotations

import time
from unittest.mock import MagicMock, patch

from chunker.base import BaseChunker
from chunker.models import Chunk, ChunkConfig, Modality
from chunker.registry import (
    get_chunker,
    is_chunker_registered,
)
from chunker.text_chunker import (
    DEFAULT_EMBEDDING_MODEL,
    DEFAULT_SIMILARITY_THRESHOLD,
    TextChunker,
    _cos_similarity,
    _detect_language,
    _is_markdown_heading,
    _split_into_units,
    _split_sentences,
)
import pytest

# ----------------------------------------------------------------------
# fixtures
# ----------------------------------------------------------------------


@pytest.fixture
def chunker() -> TextChunker:
    """默认 TextChunker 实例（禁用 embedding 以加速测试）."""
    return TextChunker(enableEmbedding=False)


@pytest.fixture
def chunker_with_emb() -> TextChunker:
    """启用 embedding 的 TextChunker 实例."""
    return TextChunker(enableEmbedding=True)


def _cfg(**kwargs) -> ChunkConfig:
    """构造文本 ChunkConfig 的便捷函数."""
    defaults = {"modality": Modality.TEXT, "windowSize": 64, "overlap": 0.1}
    defaults.update(kwargs)
    return ChunkConfig(**defaults)


# ----------------------------------------------------------------------
# 工具函数测试
# ----------------------------------------------------------------------


class TestDetectLanguage:
    def test_chinese(self):
        assert _detect_language("这是一段中文文本，用于测试语言检测。") == "zh"

    def test_english(self):
        assert _detect_language("This is an English text for language detection.") == "en"

    def test_mixed(self):
        # 中英文比例均衡（约 1:1）应识别为 mixed
        assert _detect_language("你好世界 Hello 这是混合文本 World") == "mixed"

    def test_empty(self):
        assert _detect_language("") == "en"

    def test_punctuation_only(self):
        # 纯标点无字母 -> total=0 -> "en"
        assert _detect_language("。。。") == "en"


class TestIsMarkdownHeading:
    def test_h1(self):
        ok, level, title = _is_markdown_heading("# 标题")
        assert ok and level == 1 and title == "标题"

    def test_h3(self):
        ok, level, title = _is_markdown_heading("### Section Name")
        assert ok and level == 3 and title == "Section Name"

    def test_h6_with_closing(self):
        ok, level, title = _is_markdown_heading("###### Deep ###")
        assert ok and level == 6 and title == "Deep"

    def test_not_heading(self):
        ok, level, title = _is_markdown_heading("普通文本")
        assert not ok and level == 0 and title == ""

    def test_too_many_hashes(self):
        # 7 个 # 不匹配（正则 1-6）
        ok, _, _ = _is_markdown_heading("####### too deep")
        assert not ok

    def test_no_space(self):
        # # 后无空格不匹配
        ok, _, _ = _is_markdown_heading("#nospace")
        assert not ok


class TestSplitSentences:
    def test_chinese(self):
        sents = _split_sentences("你好。世界！测试？", "zh")
        assert sents == ["你好。", "世界！", "测试？"]

    def test_chinese_no_end(self):
        sents = _split_sentences("没有结束符的句子", "zh")
        assert sents == ["没有结束符的句子"]

    def test_english(self):
        sents = _split_sentences("Hello. World! Test?", "en")
        assert sents == ["Hello.", "World!", "Test?"]

    def test_mixed(self):
        sents = _split_sentences("你好.World！测试.", "mixed")
        # mixed 先按中文切再按英文切
        assert len(sents) >= 2

    def test_empty(self):
        assert _split_sentences("", "zh") == []
        assert _split_sentences("   ", "en") == []


class TestSplitIntoUnits:
    def test_plain_text(self):
        units = _split_into_units("你好。世界！", "zh")
        assert len(units) == 2
        assert all(m["kind"] == "sentence" for _, m in units)

    def test_markdown_headings(self):
        text = "# 标题一\n\n段落一。\n\n## 标题二\n\n段落二。"
        units = _split_into_units(text, "zh")
        kinds = [m["kind"] for _, m in units]
        assert "heading" in kinds
        # 标题应独立成单元
        headings = [(t, m) for t, m in units if m["kind"] == "heading"]
        assert len(headings) == 2
        assert headings[0][1]["level"] == 1
        assert headings[1][1]["level"] == 2

    def test_empty(self):
        assert _split_into_units("", "zh") == []
        assert _split_into_units("   \n\n  ", "en") == []

    def test_multiple_paragraphs(self):
        text = "第一段第一句。第一段第二句。\n\n第二段第一句。"
        units = _split_into_units(text, "zh")
        assert len(units) >= 3


class TestCosSimilarity:
    def test_identical(self):
        assert _cos_similarity([1.0, 0.0], [1.0, 0.0]) == pytest.approx(1.0)

    def test_orthogonal(self):
        assert _cos_similarity([1.0, 0.0], [0.0, 1.0]) == pytest.approx(0.0)

    def test_opposite(self):
        assert _cos_similarity([1.0, 0.0], [-1.0, 0.0]) == pytest.approx(-1.0)

    def test_empty(self):
        assert _cos_similarity([], [1.0]) == 0.0
        assert _cos_similarity([1.0], []) == 0.0

    def test_zero_vector(self):
        assert _cos_similarity([0.0, 0.0], [1.0, 1.0]) == 0.0


# ----------------------------------------------------------------------
# TextChunker 初始化与注册
# ----------------------------------------------------------------------


class TestInitAndRegistration:
    def test_modality_is_text(self, chunker):
        assert chunker.modality is Modality.TEXT

    def test_default_attributes(self):
        c = TextChunker()
        assert c.embeddingModel == DEFAULT_EMBEDDING_MODEL
        assert c.similarityThreshold == DEFAULT_SIMILARITY_THRESHOLD
        assert c.enableEmbedding is True
        assert c.tokenizerBackend == "tiktoken"

    def test_custom_attributes(self):
        c = TextChunker(
            embeddingModel="custom-model",
            similarityThreshold=0.85,
            enableEmbedding=False,
            tokenizerBackend="bge",
        )
        assert c.embeddingModel == "custom-model"
        assert c.similarityThreshold == 0.85
        assert c.enableEmbedding is False
        assert c.tokenizerBackend == "bge"

    def test_registered_as_text(self):
        # conftest 每个测试清空注册表，这里手动注册验证
        from chunker.registry import ChunkerRegistry

        ChunkerRegistry.register("text", TextChunker)
        assert is_chunker_registered("text")

    def test_get_chunker_returns_text_chunker(self):
        from chunker.registry import ChunkerRegistry

        ChunkerRegistry.register("text", TextChunker)
        c = get_chunker("text")
        assert isinstance(c, TextChunker)

    def test_is_base_chunker_subclass(self):
        assert issubclass(TextChunker, BaseChunker)


# ----------------------------------------------------------------------
# 基本切片流程
# ----------------------------------------------------------------------


class TestBasicChunking:
    async def test_short_text_single_chunk(self, chunker):
        cfg = _cfg(windowSize=100, overlap=0.0)
        chunks = await chunker.chunk("你好世界", cfg)
        assert len(chunks) == 1
        assert chunks[0].content == "你好世界"

    async def test_empty_string(self, chunker):
        cfg = _cfg()
        chunks = await chunker.chunk("", cfg)
        assert chunks == []

    async def test_whitespace_only(self, chunker):
        cfg = _cfg()
        chunks = await chunker.chunk("   \n\n  \t  ", cfg)
        assert chunks == []

    async def test_non_string_raises(self, chunker):
        cfg = _cfg()
        with pytest.raises(TypeError):
            await chunker.chunk(12345, cfg)  # type: ignore[arg-type]

    async def test_non_string_bytes_raises(self, chunker):
        cfg = _cfg()
        with pytest.raises(TypeError):
            await chunker.chunk(b"hello", cfg)  # type: ignore[arg-type]

    async def test_tokens_set_in_postprocess(self, chunker):
        cfg = _cfg(windowSize=100)
        chunks = await chunker.chunk("hello world this is a test", cfg)
        assert all(c.tokens is not None for c in chunks)
        assert all(c.tokens >= 1 for c in chunks)

    async def test_metadata_modality_text(self, chunker):
        cfg = _cfg()
        chunks = await chunker.chunk("一些文本内容", cfg)
        assert all(c.metadata.modality is Modality.TEXT for c in chunks)

    async def test_metadata_index_sequential(self, chunker):
        cfg = _cfg(windowSize=10, overlap=0.0)
        chunks = await chunker.chunk("abcdefghijklmnopqrstuvwxyz", cfg)
        for i, c in enumerate(chunks):
            assert c.metadata.index == i

    async def test_chunk_ids_unique(self, chunker):
        cfg = _cfg(windowSize=5, overlap=0.0)
        chunks = await chunker.chunk("abcdefghijklmnopqrstuvwxyz", cfg)
        ids = [c.id for c in chunks]
        assert len(ids) == len(set(ids))


# ----------------------------------------------------------------------
# 长文档切片
# ----------------------------------------------------------------------


class TestLongDocument:
    @pytest.fixture
    def long_text(self) -> str:
        """构造约 5000 字的中文长文档."""
        paragraph = "这是一段用于测试的中文文本。包含多个句子。用于验证长文档切片性能。" * 10
        return "\n\n".join([paragraph] * 50)

    async def test_long_doc_chunks_not_empty(self, chunker, long_text):
        cfg = _cfg(windowSize=200, overlap=0.1)
        chunks = await chunker.chunk(long_text, cfg)
        assert len(chunks) > 1
        assert all(len(c.content) > 0 for c in chunks)

    async def test_long_doc_content_coverage(self, chunker, long_text):
        """所有切片内容拼接应覆盖原文（去除空白后）."""
        cfg = _cfg(windowSize=200, overlap=0.0)
        chunks = await chunker.chunk(long_text, cfg)
        joined = "".join(c.content for c in chunks)
        # 去除空白后应包含原文所有非空白字符
        original_compact = "".join(long_text.split())
        joined_compact = "".join(joined.split())
        assert joined_compact == original_compact

    async def test_long_doc_performance(self, chunker):
        """长文档 (≥1 万字) 切片耗时 < 3s (P95)."""
        # 构造 1 万字文档
        paragraph = "性能测试文本。快速切片。验证耗时。" * 20
        text = "\n\n".join([paragraph] * 20)  # ~1.2 万字
        cfg = _cfg(windowSize=500, overlap=0.1)
        start = time.perf_counter()
        chunks = await chunker.chunk(text, cfg)
        duration = time.perf_counter() - start
        assert duration < 3.0, f"切片耗时 {duration:.3f}s 超过 3s 限制"
        assert len(chunks) > 1

    async def test_100k_chars_performance(self, chunker):
        """10 万字长文档切片 P95 ≤ 3s."""
        paragraph = "这是一段用于性能测试的中文文本。包含多个句子。用于验证长文档切片性能。" * 10
        text = "\n\n".join([paragraph] * 300)  # ~10.5 万字
        assert len(text) >= 100000, f"文档仅 {len(text)} 字符，未达 10 万"
        cfg = _cfg(windowSize=500, overlap=0.1)
        # 跑 5 次取最大值近似 P95
        durations = []
        for _ in range(5):
            start = time.perf_counter()
            chunks = await chunker.chunk(text, cfg)
            durations.append(time.perf_counter() - start)
        p95 = max(durations)
        assert p95 < 3.0, f"P95 耗时 {p95:.3f}s 超过 3s 限制"
        assert len(chunks) > 1


# ----------------------------------------------------------------------
# 多语言
# ----------------------------------------------------------------------


class TestMultilingual:
    async def test_chinese(self, chunker):
        cfg = _cfg(windowSize=20, overlap=0.0)
        text = "你好世界。这是中文测试。包含多个句子。用于验证切片。"
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 1
        assert all(isinstance(c.content, str) for c in chunks)

    async def test_english(self, chunker):
        cfg = _cfg(windowSize=30, overlap=0.0)
        text = "Hello world. This is English test. With multiple sentences. For chunking."
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 1
        assert all(isinstance(c.content, str) for c in chunks)

    async def test_mixed(self, chunker):
        cfg = _cfg(windowSize=30, overlap=0.0)
        text = "你好 Hello 世界 World。Mixed 混合 text 文本。"
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 1

    async def test_language_hint_zh(self, chunker):
        cfg = _cfg(windowSize=20, language="zh")
        text = "你好世界。这是中文测试。"
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 1

    async def test_language_hint_en(self, chunker):
        cfg = _cfg(windowSize=20, language="en")
        text = "Hello world. English test."
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 1


# ----------------------------------------------------------------------
# Markdown 结构感知
# ----------------------------------------------------------------------


class TestMarkdownStructure:
    async def test_headings_as_boundaries(self, chunker):
        """Markdown 标题应作为天然边界，独立成单元."""
        text = "# 第一章\n\n第一章内容。详细描述。\n\n# 第二章\n\n第二章内容。不同主题。"
        cfg = _cfg(windowSize=100, overlap=0.0)
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 1
        # 至少有一个切片以 "# " 开头
        contents = [c.content for c in chunks]
        assert any("# 第一章" in c for c in contents)
        assert any("# 第二章" in c for c in contents)

    async def test_heading_levels(self, chunker):
        text = "# H1\n\n内容一。\n\n## H2\n\n内容二。\n\n### H3\n\n内容三。"
        cfg = _cfg(windowSize=100, overlap=0.0)
        chunks = await chunker.chunk(text, cfg)
        contents = "".join(c.content for c in chunks)
        assert "# H1" in contents
        assert "## H2" in contents
        assert "### H3" in contents

    async def test_no_heading_treated_as_plain(self, chunker):
        text = "普通段落。没有标题。只是文本。"
        cfg = _cfg(windowSize=100, overlap=0.0)
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 1
        assert "# " not in chunks[0].content


# ----------------------------------------------------------------------
# 滑动窗口与重叠
# ----------------------------------------------------------------------


class TestSlidingWindow:
    async def test_window_size_respected(self, chunker):
        """无重叠时，每一切片长度 <= windowSize（可能因单元边界略小）."""
        cfg = _cfg(windowSize=10, overlap=0.0)
        text = "abcdefghijklmnopqrstuvwxyz"
        chunks = await chunker.chunk(text, cfg)
        assert all(len(c.content) <= 10 for c in chunks)

    async def test_overlap_produces_more_chunks(self, chunker):
        text = "abcdefghijklmnopqrstuvwxyz"
        cfg_no_overlap = _cfg(windowSize=5, overlap=0.0)
        cfg_overlap = _cfg(windowSize=5, overlap=0.2)
        chunks_no = await chunker.chunk(text, cfg_no_overlap)
        chunks_yes = await chunker.chunk(text, cfg_overlap)
        # 有重叠时切片数应 >= 无重叠
        assert len(chunks_yes) >= len(chunks_no)

    async def test_overlap_merge_content(self, chunker):
        """重叠合并后，后一切片应包含前一切片尾部字符."""
        text = "abcdefghijklmnopqrstuvwxyz"
        cfg = _cfg(windowSize=8, overlap=0.25)  # overlap_size=2
        chunks = await chunker.chunk(text, cfg)
        if len(chunks) >= 2:
            # 第二切片应包含第一切片的最后 2 个字符
            tail = chunks[0].content[-2:]
            assert chunks[1].content.startswith(tail)

    async def test_zero_overlap(self, chunker):
        cfg = _cfg(windowSize=5, overlap=0.0)
        text = "abcdefghij"
        chunks = await chunker.chunk(text, cfg)
        # 无重叠时切片内容应不重复
        assert len(chunks) >= 1

    async def test_large_overlap(self, chunker):
        cfg = _cfg(windowSize=10, overlap=0.3)
        text = "abcdefghijklmnopqrstuvwxyz"
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 2


# ----------------------------------------------------------------------
# maxTokens 硬截断
# ----------------------------------------------------------------------


class TestMaxTokens:
    async def test_max_tokens_truncation(self, chunker):
        """单切片 tokens 超过 maxTokens 时应被截断."""
        cfg = _cfg(windowSize=1000, overlap=0.0, maxTokens=5)
        text = "a" * 200  # 200 字符 -> tiktoken ~50 tokens > 5
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 1
        # 截断后的切片应标记 truncated
        assert any(c.metadata.extra.get("truncated") for c in chunks)

    async def test_max_tokens_large_enough(self, chunker):
        cfg = _cfg(windowSize=100, overlap=0.0, maxTokens=10000)
        text = "hello world"
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) == 1
        assert not chunks[0].metadata.extra.get("truncated")


# ----------------------------------------------------------------------
# token 计数
# ----------------------------------------------------------------------


class TestTokenCounting:
    def test_count_tokens_empty(self, chunker):
        assert chunker._count_tokens("") == 0

    def test_count_tokens_tiktoken(self):
        c = TextChunker(tokenizerBackend="tiktoken", enableEmbedding=False)
        tokens = c._count_tokens("hello world")
        assert tokens > 0

    def test_count_tokens_fallback_when_tiktoken_unavailable(self):
        c = TextChunker(tokenizerBackend="nonexistent", enableEmbedding=False)
        # 未知 backend -> 回退到 4 字符/token
        tokens = c._count_tokens("abcdefgh")
        assert tokens == 2

    def test_count_tokens_bge_backend_no_model(self):
        """bge backend 但 embedding 禁用 -> 回退."""
        c = TextChunker(tokenizerBackend="bge", enableEmbedding=False)
        tokens = c._count_tokens("hello world")
        # 回退到 4 字符/token
        assert tokens == max(1, len("hello world") // 4)

    def test_count_tokens_at_least_one(self, chunker):
        assert chunker._count_tokens("a") >= 1


# ----------------------------------------------------------------------
# Embedding 回退策略
# ----------------------------------------------------------------------


class TestEmbeddingFallback:
    async def test_disabled_embedding_uses_sliding_window(self):
        """禁用 embedding 时应使用滑动窗口策略正常切片."""
        c = TextChunker(enableEmbedding=False)
        cfg = _cfg(windowSize=10, overlap=0.0)
        text = "abcdefghijklmnopqrstuvwxyz"
        chunks = await c.chunk(text, cfg)
        assert len(chunks) >= 2
        assert all(isinstance(ch.content, str) for ch in chunks)

    async def test_model_unavailable_fallback(self):
        """embedding 模型不可用时应回退为滑动窗口，不抛异常."""
        c = TextChunker(
            enableEmbedding=True,
            embeddingModel="nonexistent-model-xxx",
        )
        # mock 模型加载返回 None，避免实际下载
        with patch.object(c, "_load_embedding_model", return_value=None):
            cfg = _cfg(windowSize=10, overlap=0.0)
            text = "abcdefghijklmnopqrstuvwxyz"
            # 不应抛异常
            chunks = await c.chunk(text, cfg)
            assert len(chunks) >= 1

    async def test_extra_config_enable_embedding_override(self):
        """通过 config.extra 覆盖 enableEmbedding."""
        c = TextChunker(enableEmbedding=True)
        cfg = _cfg(
            windowSize=10,
            overlap=0.0,
            extra={"enableEmbedding": False},
        )
        text = "abcdefghijklmnopqrstuvwxyz"
        chunks = await c.chunk(text, cfg)
        assert len(chunks) >= 1

    async def test_extra_config_similarity_threshold(self):
        """通过 config.extra 覆盖 similarityThreshold."""
        c = TextChunker(enableEmbedding=False)
        cfg = _cfg(
            windowSize=10,
            overlap=0.0,
            extra={"similarityThreshold": 0.9},
        )
        text = "abcdefghijklmnopqrstuvwxyz"
        chunks = await c.chunk(text, cfg)
        assert len(chunks) >= 1


# ----------------------------------------------------------------------
# 语义边界识别（mock embedding）
# ----------------------------------------------------------------------


class TestSemanticBoundary:
    async def test_semantic_merge_with_mock_embeddings(self):
        """mock embedding 计算结果，验证语义边界识别逻辑."""
        c = TextChunker(enableEmbedding=True)

        # mock _compute_embeddings 返回固定向量
        # 让前两个单元相似度高（>0.7），后两个相似度低（<0.7）
        async def mock_embeddings(texts):
            # 单元 0,1 相似（向量接近），单元 2 与 1 不相似
            return [
                [1.0, 0.0, 0.0],  # unit 0
                [0.95, 0.05, 0.0],  # unit 1 (与 0 相似 ~0.997)
                [0.0, 0.0, 1.0],  # unit 2 (与 1 不相似 ~0)
                [0.0, 0.1, 0.99],  # unit 3 (与 2 相似)
            ][: len(texts)]

        with patch.object(c, "_compute_embeddings", side_effect=mock_embeddings):
            units = [
                ("第一单元内容", {"kind": "sentence"}),
                ("第二单元内容", {"kind": "sentence"}),
                ("第三单元内容", {"kind": "sentence"}),
                ("第四单元内容", {"kind": "sentence"}),
            ]
            merged = await c._semantic_merge(units, threshold=0.7)
            # unit 0,1 相似 -> 合并；unit 1,2 不相似 -> 分开；unit 2,3 相似 -> 合并
            assert len(merged) == 2

    async def test_heading_always_boundary(self):
        """标题单元始终作为边界，不与前一单元合并."""
        c = TextChunker(enableEmbedding=True)

        async def mock_embeddings(texts):
            # 全部返回相同向量（相似度=1.0），但标题仍应独立
            return [[1.0, 0.0]] * len(texts)

        with patch.object(c, "_compute_embeddings", side_effect=mock_embeddings):
            units = [
                ("段落内容一", {"kind": "sentence"}),
                ("# 标题", {"kind": "heading", "level": 1}),
                ("段落内容二", {"kind": "sentence"}),
            ]
            merged = await c._semantic_merge(units, threshold=0.7)
            # 标题独立，前后段落即使相似也因标题分隔
            assert len(merged) == 3
            assert merged[1][1]["kind"] == "heading"

    async def test_single_unit_no_merge(self):
        c = TextChunker(enableEmbedding=True)
        units = [("唯一单元", {"kind": "sentence"})]
        merged = await c._semantic_merge(units, threshold=0.7)
        assert len(merged) == 1

    async def test_no_embedding_returns_units(self):
        """embedding 不可用时，回退为不基于语义合并（按 windowSize 在滑动窗口处理）."""
        c = TextChunker(enableEmbedding=False)
        units = [(f"单元{i}", {"kind": "sentence"}) for i in range(5)]
        merged = await c._semantic_merge(units, threshold=0.7)
        # 无 embedding 时所有单元合并为一个（无边界）
        assert len(merged) == 1


# ----------------------------------------------------------------------
# _compute_embeddings 测试
# ----------------------------------------------------------------------


class TestComputeEmbeddings:
    async def test_empty_list(self, chunker_with_emb):
        result = await chunker_with_emb._compute_embeddings([])
        assert result == []

    async def test_model_unavailable_returns_none(self):
        c = TextChunker(
            enableEmbedding=True,
            embeddingModel="nonexistent-xxx-yyy",
        )
        # mock 模型加载返回 None，避免实际下载
        with patch.object(c, "_load_embedding_model", return_value=None):
            result = await c._compute_embeddings(["hello", "world"])
            assert result is None

    async def test_disabled_embedding_returns_none(self):
        c = TextChunker(enableEmbedding=False)
        result = await c._compute_embeddings(["hello"])
        assert result is None


# ----------------------------------------------------------------------
# _overlap_merge 测试
# ----------------------------------------------------------------------


class TestOverlapMerge:
    def test_no_overlap(self, chunker):
        from chunker.models import ChunkMetadata

        c1 = Chunk(id="1", content="abc", metadata=ChunkMetadata(modality=Modality.TEXT))
        c2 = Chunk(id="2", content="def", metadata=ChunkMetadata(modality=Modality.TEXT))
        result = chunker._overlap_merge([c1, c2], overlap_size=0)
        assert len(result) == 2
        assert result[0].content == "abc"
        assert result[1].content == "def"

    def test_with_overlap(self, chunker):
        from chunker.models import ChunkMetadata

        c1 = Chunk(id="1", content="abcdef", metadata=ChunkMetadata(modality=Modality.TEXT))
        c2 = Chunk(id="2", content="ghijkl", metadata=ChunkMetadata(modality=Modality.TEXT))
        result = chunker._overlap_merge([c1, c2], overlap_size=2)
        # c2 头部应加上 c1 尾部 2 字符 "ef"
        assert result[1].content == "efghijkl"

    def test_single_chunk(self, chunker):
        from chunker.models import ChunkMetadata

        c1 = Chunk(id="1", content="abc", metadata=ChunkMetadata(modality=Modality.TEXT))
        result = chunker._overlap_merge([c1], overlap_size=2)
        assert len(result) == 1

    def test_short_prev_content(self, chunker):
        """前一切片短于 overlap_size 时，使用全部内容."""
        from chunker.models import ChunkMetadata

        c1 = Chunk(id="1", content="ab", metadata=ChunkMetadata(modality=Modality.TEXT))
        c2 = Chunk(id="2", content="cdef", metadata=ChunkMetadata(modality=Modality.TEXT))
        result = chunker._overlap_merge([c1, c2], overlap_size=5)
        assert result[1].content == "abcdef"


# ----------------------------------------------------------------------
# _split_by_max_tokens 测试
# ----------------------------------------------------------------------


class TestSplitByMaxTokens:
    def test_empty(self, chunker):
        assert chunker._split_by_max_tokens("", 100) == []

    def test_within_limit(self, chunker):
        assert chunker._split_by_max_tokens("hello", 100) == ["hello"]

    def test_exceeds_limit(self, chunker):
        text = "a" * 100
        result = chunker._split_by_max_tokens(text, max_tokens=5)
        # max_chars = 5*4 = 20
        assert len(result) == 5  # 100 / 20 = 5
        assert all(len(s) == 20 for s in result)


# ----------------------------------------------------------------------
# chunk_with_result
# ----------------------------------------------------------------------


class TestChunkWithResult:
    async def test_result_aggregation(self, chunker):
        cfg = _cfg(windowSize=10, overlap=0.0)
        result = await chunker.chunk_with_result("abcdefghijklmnopqrstuvwxyz", cfg)
        assert result.count >= 2
        assert result.modality is Modality.TEXT
        assert result.durationMs >= 0.0
        assert result.totalTokens > 0

    async def test_result_empty(self, chunker):
        cfg = _cfg()
        result = await chunker.chunk_with_result("", cfg)
        assert result.count == 0
        assert result.totalTokens == 0


# ----------------------------------------------------------------------
# 预处理
# ----------------------------------------------------------------------


class TestPreprocess:
    async def test_normalizes_line_endings(self, chunker):
        """\\r\\n 应被归一化为 \\n."""
        cfg = _cfg()
        result = await chunker._preprocess("hello\r\nworld", cfg)
        assert "\r" not in result["original"]

    async def test_strips_whitespace(self, chunker):
        cfg = _cfg()
        result = await chunker._preprocess("  hello  ", cfg)
        assert result["original"] == "hello"

    async def test_returns_units(self, chunker):
        cfg = _cfg()
        result = await chunker._preprocess("你好。世界！", cfg)
        assert "units" in result
        assert len(result["units"]) == 2

    async def test_language_detection(self, chunker):
        cfg = _cfg(language="auto")
        result = await chunker._preprocess("你好世界。", cfg)
        assert result["language"] == "zh"

    async def test_language_hint_override(self, chunker):
        cfg = _cfg(language="en")
        result = await chunker._preprocess("你好世界。", cfg)
        assert result["language"] == "en"


# ----------------------------------------------------------------------
# 异常与边界
# ----------------------------------------------------------------------


class TestEdgeCases:
    async def test_single_character(self, chunker):
        cfg = _cfg(windowSize=10)
        chunks = await chunker.chunk("a", cfg)
        assert len(chunks) == 1
        assert chunks[0].content == "a"

    async def test_very_small_window(self, chunker):
        cfg = _cfg(windowSize=2, overlap=0.0)
        text = "abcdefgh"
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 4

    async def test_text_exactly_window_size(self, chunker):
        cfg = _cfg(windowSize=10, overlap=0.0)
        text = "abcdefghij"  # 恰好 10 字符
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 1

    async def test_only_headings(self, chunker):
        text = "# H1\n\n## H2\n\n### H3"
        cfg = _cfg(windowSize=100, overlap=0.0)
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 1
        contents = "".join(c.content for c in chunks)
        assert "# H1" in contents
        assert "## H2" in contents
        assert "### H3" in contents

    async def test_config_extra_ignored_when_invalid(self, chunker):
        """config.extra 中无效字段应被忽略，不影响切片."""
        cfg = _cfg(windowSize=20, overlap=0.0, extra={"unknownField": "value"})
        text = "你好世界。这是测试。"
        chunks = await chunker.chunk(text, cfg)
        assert len(chunks) >= 1


# ----------------------------------------------------------------------
# Embedding 实际执行路径（mock 模型）
# ----------------------------------------------------------------------


class TestEmbeddingExecution:
    """覆盖 _compute_embeddings 的实际模型调用路径."""

    async def test_compute_embeddings_with_mock_model(self):
        """mock 模型返回 embedding，验证批量计算逻辑."""
        import numpy as np

        c = TextChunker(enableEmbedding=True)

        mock_model = MagicMock()
        mock_model.encode = MagicMock(return_value=np.array([[1.0, 0.0], [0.0, 1.0], [0.5, 0.5]]))
        with patch.object(c, "_load_embedding_model", return_value=mock_model):
            result = await c._compute_embeddings(["a", "b", "c"])
            assert result is not None
            assert len(result) == 3
            assert result[0] == [1.0, 0.0]
            assert result[1] == [0.0, 1.0]
            assert result[2] == [0.5, 0.5]

    async def test_compute_embeddings_large_batch(self):
        """超过 _EMBEDDING_ASYNC_CHUNK 的批量应分块执行."""
        import numpy as np

        c = TextChunker(enableEmbedding=True)
        texts = [f"text_{i}" for i in range(40)]

        def encode_fn(batch, **kwargs):
            return np.array([[1.0, float(i)] for i in range(len(batch))])

        mock_model = MagicMock()
        mock_model.encode = MagicMock(side_effect=encode_fn)
        with patch.object(c, "_load_embedding_model", return_value=mock_model):
            result = await c._compute_embeddings(texts)
            assert result is not None
            assert len(result) == 40

    async def test_semantic_chunking_with_mock_model(self):
        """端到端：mock 模型实现语义切片，验证完整流程."""
        import numpy as np

        c = TextChunker(enableEmbedding=True, similarityThreshold=0.7)

        # mock 模型：前两句相似（cos≈1），后两句与前两句不相似
        def encode_fn(batch, **kwargs):
            # 根据输入文本返回不同向量
            vecs = []
            for text in batch:
                if "机器学习" in text or "深度学习" in text:
                    vecs.append([1.0, 0.0, 0.0])
                else:
                    vecs.append([0.0, 0.0, 1.0])
            return np.array(vecs)

        mock_model = MagicMock()
        mock_model.encode = MagicMock(side_effect=encode_fn)
        mock_model.tokenizer = MagicMock()
        mock_model.tokenizer.encode = MagicMock(return_value=[1, 2, 3])

        with patch.object(c, "_load_embedding_model", return_value=mock_model):
            text = "机器学习是人工智能的分支。深度学习是机器学习的子领域。今天天气真好。我们去公园散步。"
            cfg = _cfg(windowSize=100, overlap=0.0)
            chunks = await c.chunk(text, cfg)
            assert len(chunks) >= 1
            assert all(isinstance(ch.content, str) for ch in chunks)

    async def test_bge_tokenizer_with_mock_model(self):
        """bge backend + mock 模型，验证 tokenizer 调用路径."""
        c = TextChunker(tokenizerBackend="bge", enableEmbedding=True)

        mock_model = MagicMock()
        mock_model.tokenizer.encode = MagicMock(return_value=[1, 2, 3, 4])
        with patch.object(c, "_load_embedding_model", return_value=mock_model):
            tokens = c._count_tokens("测试文本")
            assert tokens == 4

    async def test_load_embedding_model_disabled_returns_none(self):
        """enableEmbedding=False 时 _load_embedding_model 直接返回 None."""
        c = TextChunker(enableEmbedding=False)
        assert c._load_embedding_model() is None

    async def test_load_embedding_model_cache_hit(self):
        """模型缓存命中路径."""
        c = TextChunker(enableEmbedding=True, embeddingModel="test-cache-model")
        mock_model = MagicMock()
        # 预填充缓存
        TextChunker._model_cache["test-cache-model"] = mock_model
        try:
            result = c._load_embedding_model()
            assert result is mock_model
        finally:
            TextChunker._model_cache.pop("test-cache-model", None)

    async def test_tiktoken_encoder_cache_hit(self):
        """tiktoken 编码器缓存命中路径."""
        c = TextChunker(tokenizerBackend="tiktoken")
        # 先加载一次填充缓存
        c._load_tiktoken_encoder()
        # 第二次应命中缓存
        result = c._load_tiktoken_encoder()
        assert result is not None


# ----------------------------------------------------------------------
# _split 内部路径
# ----------------------------------------------------------------------


class TestSplitInternal:
    async def test_split_empty_units(self, chunker):
        """_split 收到空 units 应返回空列表."""
        result = await chunker._split({"units": [], "language": "zh", "original": ""}, _cfg())
        assert result == []

    async def test_split_single_unit(self, chunker):
        """单个单元应产生单个切片."""
        result = await chunker._split(
            {"units": [("唯一内容", {"kind": "sentence"})], "language": "zh", "original": "唯一内容"},
            _cfg(windowSize=100),
        )
        assert len(result) == 1
        assert result[0].content == "唯一内容"

    async def test_split_with_heading_and_sentences(self, chunker):
        """标题 + 句子混合单元."""
        units = [
            ("# 标题", {"kind": "heading", "level": 1}),
            ("句子一。", {"kind": "sentence"}),
            ("句子二。", {"kind": "sentence"}),
        ]
        result = await chunker._split(
            {"units": units, "language": "zh", "original": ""},
            _cfg(windowSize=100),
        )
        assert len(result) >= 1

    async def test_postprocess_reindexes(self, chunker):
        """_postprocess 应重排 metadata.index."""
        from chunker.models import ChunkMetadata

        chunks = [
            Chunk(id="1", content="a", metadata=ChunkMetadata(modality=Modality.TEXT, index=5)),
            Chunk(id="2", content="b", metadata=ChunkMetadata(modality=Modality.TEXT, index=10)),
        ]
        result = await chunker._postprocess(chunks, _cfg())
        assert result[0].metadata.index == 0
        assert result[1].metadata.index == 1
        assert result[0].tokens is not None
        assert result[1].tokens is not None
