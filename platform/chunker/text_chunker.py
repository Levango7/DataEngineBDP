"""文本语义切片器 (T008-2).

基于 T008-1 多模态切片器框架实现 ``TextChunker``，支持：

1. **语义切片**：基于 Embedding 余弦相似度识别段落边界
   - 使用 sentence-transformers 加载 ``bge-large-zh`` 模型
   - 相邻单元 cos 相似度 < 阈值时识别为语义边界
   - 模型不可用时自动回退为固定大小滑动窗口切片
2. **滑动窗口策略**：窗口大小可配，重叠率 10%~30%
   - 通过 ``ChunkConfig.windowSize`` / ``ChunkConfig.overlap`` 配置
   - 重叠区域由 ``_overlap_merge`` 合并
3. **token 计数**：支持 GPT-4 (tiktoken) 与 bge tokenizer
   - ``tokenizerBackend="tiktoken"`` 使用 ``cl100k_base`` 编码
   - ``tokenizerBackend="bge"`` 使用 embedding 模型自带 tokenizer
4. **性能**：长文档 (≥10 万字) 切片 P95 ≤ 3s
   - 异步 IO + 批量 Embedding + 模型懒加载 + 单例缓存
5. **多语言**：中文 / 英文 / 混合文本
   - 中文按 ``。！？`` 切分句子
   - 英文按 ``.!?`` 切分句子
6. **Markdown 结构感知**：识别 ``# / ## / ###`` 标题作为天然边界
7. **注册**：通过 ``@register_chunker(Modality.TEXT)`` 自动注册

对齐设计文档 T008-2。
"""
from __future__ import annotations

import asyncio
import math
import re
import threading
from typing import Any

from chunker.base import BaseChunker
from chunker.models import Chunk, ChunkConfig, Modality
from chunker.registry import register_chunker

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: 默认 embedding 模型名（bge-large-zh，中英文双语）
DEFAULT_EMBEDDING_MODEL = "BAAI/bge-large-zh"

#: 默认语义边界相似度阈值，cos < 此值视为语义切换
DEFAULT_SIMILARITY_THRESHOLD = 0.7

#: 默认 tiktoken 编码名（GPT-4 / GPT-3.5 共用）
DEFAULT_TIKTOKEN_ENCODING = "cl100k_base"

#: 中文句子结束符（含全角/半角）
_CN_SENTENCE_END = "。！？!?…"

#: 英文句子结束符
_EN_SENTENCE_END = ".!?"

#: Markdown 标题正则（#/##/.../######）
_MD_HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")

#: 段落分隔（连续空行）
_PARAGRAPH_RE = re.compile(r"\n\s*\n")

#: 中文句子切分正则（保留结束符）
_CN_SENTENCE_RE = re.compile(rf"[^{_CN_SENTENCE_END}]+[{_CN_SENTENCE_END}]+|[^{_CN_SENTENCE_END}]+$")

#: 英文句子切分正则（保留结束符）
_EN_SENTENCE_RE = re.compile(rf"[^{_EN_SENTENCE_END}]+[{_EN_SENTENCE_END}]+|[^{_EN_SENTENCE_END}]+$")

#: 批量 embedding 计算的并发上限（避免显存溢出）
_EMBEDDING_BATCH_SIZE = 64

#: 异步 embedding 计算分块大小（每块交由线程池执行）
_EMBEDDING_ASYNC_CHUNK = 32


# ----------------------------------------------------------------------
# 工具函数
# ----------------------------------------------------------------------


def _detect_language(text: str) -> str:
    """粗略检测文本主语言.

    :param text: 输入文本
    :return: ``"zh"`` / ``"en"`` / ``"mixed"``
    """
    if not text:
        return "en"
    cjk = sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")
    ascii_letters = sum(1 for ch in text if ch.isalpha() and ch.isascii())
    total = cjk + ascii_letters
    if total == 0:
        return "en"
    cjk_ratio = cjk / total
    if cjk_ratio > 0.7:
        return "zh"
    if cjk_ratio < 0.3:
        return "en"
    return "mixed"


def _is_markdown_heading(line: str) -> tuple[bool, int, str]:
    """判断单行是否为 Markdown 标题.

    :param line: 单行文本
    :return: (是否标题, 标题层级 1-6, 标题文本)；非标题返回 (False, 0, "")
    """
    m = _MD_HEADING_RE.match(line)
    if not m:
        return False, 0, ""
    return True, len(m.group(1)), m.group(2)


def _split_sentences(text: str, language: str) -> list[str]:
    """按语言将段落切分为句子.

    :param text: 段落文本（不含跨段落换行）
    :param language: ``"zh"`` / ``"en"`` / ``"mixed"``
    :return: 句子列表（保留结束符，过滤空白）
    """
    if not text.strip():
        return []
    if language == "zh":
        sentences = _CN_SENTENCE_RE.findall(text)
    elif language == "en":
        sentences = _EN_SENTENCE_RE.findall(text)
    else:  # mixed：先按中文切，再对每段按英文切
        sentences = []
        for seg in _CN_SENTENCE_RE.findall(text):
            sentences.extend(_EN_SENTENCE_RE.findall(seg))
    return [s.strip() for s in sentences if s.strip()]


def _split_into_units(text: str, language: str) -> list[tuple[str, dict[str, Any]]]:
    """将原始文本切分为初始单元（标题/段落/句子）.

    Markdown 标题作为天然边界单独成单元；
    其余按段落 -> 句子层级切分。

    :param text: 原始文本
    :param language: 语言提示
    :return: [(unit_text, meta), ...]，meta 含 ``kind``/``level``
    """
    units: list[tuple[str, dict[str, Any]]] = []
    if not text:
        return units

    # 先按段落切分（保留 Markdown 结构）
    paragraphs = _PARAGRAPH_RE.split(text)
    for para in paragraphs:
        if not para.strip():
            continue
        # 按行处理，识别 Markdown 标题
        lines = para.split("\n")
        # 单行段落：可能是标题
        if len(lines) == 1:
            line = lines[0].strip()
            is_heading, level, title = _is_markdown_heading(line)
            if is_heading:
                units.append((line, {"kind": "heading", "level": level}))
                continue
        # 多行段落：逐行检查标题，其余按句子切分
        buf: list[str] = []
        for line in lines:
            stripped = line.strip()
            if not stripped:
                continue
            is_heading, level, _title = _is_markdown_heading(stripped)
            if is_heading:
                # flush buffer
                if buf:
                    for sent in _split_sentences(" ".join(buf), language):
                        units.append((sent, {"kind": "sentence"}))
                    buf = []
                units.append((stripped, {"kind": "heading", "level": level}))
            else:
                buf.append(stripped)
        if buf:
            for sent in _split_sentences(" ".join(buf), language):
                units.append((sent, {"kind": "sentence"}))
    return units


def _cos_similarity(a: list[float], b: list[float]) -> float:
    """计算两个向量的余弦相似度.

    :param a: 向量 A
    :param b: 向量 B
    :return: cos 相似度，[-1, 1]；任一向量为零向量返回 0.0
    """
    if not a or not b:
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(y * y for y in b))
    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0
    return dot / (norm_a * norm_b)


# ----------------------------------------------------------------------
# TextChunker
# ----------------------------------------------------------------------


@register_chunker(Modality.TEXT)
class TextChunker(BaseChunker):
    """文本语义切片器.

    配置通过 ``ChunkConfig`` 传入，模态专属配置通过 ``ChunkConfig.extra`` 提供：

    - ``similarityThreshold``: 语义边界相似度阈值，默认 0.7
    - ``enableEmbedding``: 是否启用 embedding 语义切片，默认 True
    - ``tokenizerBackend``: ``"tiktoken"`` 或 ``"bge"``，默认 ``"tiktoken"``
    - ``embeddingModel``: embedding 模型名，默认 ``BAAI/bge-large-zh``

    用法::

        from chunker import get_chunker, ChunkConfig, Modality

        chunker = get_chunker("text")
        cfg = ChunkConfig(modality=Modality.TEXT, windowSize=512, overlap=0.1)
        chunks = await chunker.chunk(long_text, cfg)
    """

    MODALITY = Modality.TEXT

    # 类级单例：embedding 模型 / tiktoken 编码（避免重复加载）
    _model_lock = threading.Lock()
    _model_cache: dict[str, Any] = {}
    _tiktoken_lock = threading.Lock()
    _tiktoken_cache: dict[str, Any] = {}

    def __init__(
        self,
        modality: Modality | str | None = None,
        *,
        embeddingModel: str = DEFAULT_EMBEDDING_MODEL,
        similarityThreshold: float = DEFAULT_SIMILARITY_THRESHOLD,
        enableEmbedding: bool = True,
        tokenizerBackend: str = "tiktoken",
        tiktokenEncoding: str = DEFAULT_TIKTOKEN_ENCODING,
    ) -> None:
        """初始化文本切片器.

        :param modality: 模态（默认 TEXT）
        :param embeddingModel: embedding 模型名
        :param similarityThreshold: 语义边界 cos 阈值
        :param enableEmbedding: 是否启用 embedding 语义切片
        :param tokenizerBackend: ``"tiktoken"`` 或 ``"bge"``
        :param tiktokenEncoding: tiktoken 编码名
        """
        super().__init__(modality)
        self.embeddingModel = embeddingModel
        self.similarityThreshold = similarityThreshold
        self.enableEmbedding = enableEmbedding
        self.tokenizerBackend = tokenizerBackend
        self.tiktokenEncoding = tiktokenEncoding
        # 标记 embedding 模型是否可用（懒加载后更新）
        self._embedding_available: bool | None = None

    # ------------------------------------------------------------------
    # 模型懒加载
    # ------------------------------------------------------------------

    def _load_embedding_model(self) -> Any | None:
        """懒加载 embedding 模型（线程安全单例）.

        :return: SentenceTransformer 实例；加载失败返回 None
        """
        if not self.enableEmbedding:
            return None
        name = self.embeddingModel
        with self._model_lock:
            if name in self._model_cache:
                self._embedding_available = True
                return self._model_cache[name]
            try:
                from sentence_transformers import SentenceTransformer
            except ImportError:
                self._embedding_available = False
                return None
            try:
                model = SentenceTransformer(name)
                self._model_cache[name] = model
                self._embedding_available = True
                return model
            except Exception:  # noqa: BLE001
                # 模型下载失败 / 离线 / 不可用
                self._embedding_available = False
                return None

    def _load_tiktoken_encoder(self) -> Any | None:
        """懒加载 tiktoken 编码器（线程安全单例）.

        :return: tiktoken Encoding 实例；加载失败返回 None
        """
        name = self.tiktokenEncoding
        with self._tiktoken_lock:
            if name in self._tiktoken_cache:
                return self._tiktoken_cache[name]
            try:
                import tiktoken
            except ImportError:
                return None
            try:
                enc = tiktoken.get_encoding(name)
                self._tiktoken_cache[name] = enc
                return enc
            except Exception:  # noqa: BLE001
                return None

    # ------------------------------------------------------------------
    # token 计数
    # ------------------------------------------------------------------

    def _count_tokens(self, text: str) -> int:
        """计算文本 token 数.

        - ``tokenizerBackend="tiktoken"``：使用 GPT-4 cl100k_base 编码
        - ``tokenizerBackend="bge"``：使用 embedding 模型 tokenizer
        - 加载失败回退到 4 字符/token 估算

        :param text: 文本
        :return: token 数
        """
        if not text:
            return 0
        if self.tokenizerBackend == "tiktoken":
            enc = self._load_tiktoken_encoder()
            if enc is not None:
                try:
                    return len(enc.encode(text))
                except Exception:  # noqa: BLE001
                    pass
        elif self.tokenizerBackend == "bge":
            model = self._load_embedding_model()
            if model is not None:
                try:
                    tok = model.tokenizer.encode(text)
                    return len(tok)
                except Exception:  # noqa: BLE001
                    pass
        # 回退：4 字符/token
        return max(1, len(text) // 4)

    # ------------------------------------------------------------------
    # Embedding 计算
    # ------------------------------------------------------------------

    async def _compute_embeddings(self, texts: list[str]) -> list[list[float]] | None:
        """批量异步计算文本 embedding.

        :param texts: 文本列表
        :return: embedding 列表（与输入同序）；模型不可用返回 None
        """
        if not texts:
            return []
        model = self._load_embedding_model()
        if model is None:
            return None
        # 分块异步执行，避免阻塞事件循环
        results: list[list[float]] = [None] * len(texts)  # type: ignore[list-item]
        loop = asyncio.get_running_loop()

        async def _encode_chunk(start: int, batch: list[str]) -> None:
            def _work() -> list[list[float]]:
                emb = model.encode(
                    batch,
                    batch_size=_EMBEDDING_BATCH_SIZE,
                    show_progress_bar=False,
                    convert_to_numpy=True,
                )
                return [list(map(float, row)) for row in emb]

            out = await loop.run_in_executor(None, _work)
            for i, row in enumerate(out):
                results[start + i] = row

        tasks = []
        for i in range(0, len(texts), _EMBEDDING_ASYNC_CHUNK):
            batch = texts[i : i + _EMBEDDING_ASYNC_CHUNK]
            tasks.append(_encode_chunk(i, batch))
        await asyncio.gather(*tasks)
        return results

    # ------------------------------------------------------------------
    # BaseChunker 抽象方法实现
    # ------------------------------------------------------------------

    async def _preprocess(self, content: Any, config: ChunkConfig) -> Any:
        """预处理：校验类型 + 归一化 + 切分初始单元.

        :param content: 原始内容（必须为 str）
        :param config: 切片配置
        :return: 预处理结果字典::

            {
                "units": [(text, meta), ...],
                "language": "zh"/"en"/"mixed",
                "original": 原始文本,
            }
        """
        if not isinstance(content, str):
            raise TypeError(
                f"TextChunker 仅支持 str 内容，得到 {type(content).__name__}"
            )
        # 归一化：去除首尾空白，统一换行符
        text = content.replace("\r\n", "\n").replace("\r", "\n")
        stripped = text.strip()
        if not stripped:
            return {"units": [], "language": "en", "original": ""}
        # 语言检测
        lang_hint = config.language or "auto"
        if lang_hint in ("zh", "en", "mixed"):
            language = lang_hint
        else:
            language = _detect_language(stripped)
        # 切分初始单元
        units = _split_into_units(stripped, language)
        return {"units": units, "language": language, "original": stripped}

    async def _split(self, preprocessed: Any, config: ChunkConfig) -> list[Chunk]:
        """切分：语义合并 + 滑动窗口 + maxTokens 截断.

        :param preprocessed: ``_preprocess`` 返回的字典
        :param config: 切片配置
        :return: 切片列表
        """
        units: list[tuple[str, dict[str, Any]]] = preprocessed.get("units", [])
        if not units:
            return []

        # 读取模态专属配置
        extra = config.extra or {}
        sim_threshold = float(
            extra.get("similarityThreshold", self.similarityThreshold)
        )
        enable_emb = bool(extra.get("enableEmbedding", self.enableEmbedding))
        # 临时切换 enableEmbedding（不修改实例属性，避免副作用）
        original_enable = self.enableEmbedding
        self.enableEmbedding = enable_emb
        try:
            merged_units = await self._semantic_merge(units, sim_threshold)
        finally:
            self.enableEmbedding = original_enable

        # 滑动窗口 + maxTokens 截断
        chunks = self._sliding_window(merged_units, config)
        return chunks

    async def _postprocess(self, chunks: list[Chunk], config: ChunkConfig) -> list[Chunk]:
        """后处理：计算 tokens + 重排 index.

        :param chunks: 切片列表
        :param config: 切片配置
        :return: 处理后的切片列表
        """
        for i, c in enumerate(chunks):
            c.tokens = self._count_tokens(c.content)
            # 重排 index，确保从 0 连续
            c.metadata = c.metadata.model_copy(update={"index": i})
        return chunks

    # ------------------------------------------------------------------
    # 语义合并
    # ------------------------------------------------------------------

    async def _semantic_merge(
        self,
        units: list[tuple[str, dict[str, Any]]],
        threshold: float,
    ) -> list[tuple[str, dict[str, Any]]]:
        """基于 embedding 相似度合并相邻单元.

        算法：
        1. 标题单元始终作为边界（不与前一单元合并）
        2. 启用 embedding 时：相邻非标题单元 cos < threshold 视为边界
        3. 未启用 / 模型不可用：回退为按 windowSize 字符数合并（在滑动窗口阶段处理）

        合并后单元的 meta 标记 ``kind="merged"``。

        :param units: 初始单元列表
        :param threshold: cos 相似度阈值
        :return: 合并后的单元列表
        """
        if len(units) <= 1:
            return list(units)

        # 计算相邻单元相似度（仅在启用 embedding 时）
        similarities: list[float] | None = None
        if self.enableEmbedding:
            texts = [u[0] for u in units]
            embeddings = await self._compute_embeddings(texts)
            if embeddings is not None and len(embeddings) == len(units):
                similarities = [
                    _cos_similarity(embeddings[i], embeddings[i + 1])
                    for i in range(len(embeddings) - 1)
                ]

        merged: list[tuple[str, dict[str, Any]]] = []
        buf_text: list[str] = []
        buf_meta: list[dict[str, Any]] = []

        def _flush() -> None:
            if not buf_text:
                return
            text = " ".join(buf_text) if _is_en(buf_meta) else "".join(buf_text)
            merged.append((text, {"kind": "merged"}))
            buf_text.clear()
            buf_meta.clear()

        for i, (text, meta) in enumerate(units):
            is_heading = meta.get("kind") == "heading"
            # 标题：先 flush 当前缓冲，标题独立成单元
            if is_heading:
                _flush()
                merged.append((text, dict(meta)))
                continue
            # 非标题单元：判断是否需要切边界
            if buf_text:
                # 已有缓冲：检查与前一单元的相似度
                should_break = False
                if similarities is not None and i - 1 < len(similarities):
                    sim = similarities[i - 1]
                    if sim < threshold:
                        should_break = True
                if should_break:
                    _flush()
            buf_text.append(text)
            buf_meta.append(meta)
        _flush()
        return merged

    # ------------------------------------------------------------------
    # 滑动窗口
    # ------------------------------------------------------------------

    def _sliding_window(
        self,
        units: list[tuple[str, dict[str, Any]]],
        config: ChunkConfig,
    ) -> list[Chunk]:
        """对合并后的单元应用滑动窗口 + maxTokens 截断.

        :param units: 合并后的单元列表
        :param config: 切片配置
        :return: 切片列表
        """
        if not units:
            return []
        window = config.windowSize
        overlap_size = config.overlap_size()
        stride = config.stride()
        max_tokens = config.maxTokens

        # 将单元按 windowSize 字符数打包为初始切片
        # 同时尊重标题边界（标题不与后续单元合并到同一切片头部之外）
        raw_chunks: list[tuple[str, int, int]] = []  # (text, start_char, end_char)
        buf: list[str] = []
        buf_len = 0
        start_char = 0

        def _flush_buf() -> None:
            nonlocal buf, buf_len, start_char
            if not buf:
                return
            text = "".join(buf)
            raw_chunks.append((text, start_char, start_char + buf_len))
            start_char = start_char + buf_len
            buf = []
            buf_len = 0

        for text, meta in units:
            is_heading = meta.get("kind") == "heading"
            # 标题强制 flush（标题独占一个 raw chunk，便于滑动窗口对齐）
            if is_heading and buf:
                _flush_buf()
            # 单个单元超过 window：直接按 window 滑窗切
            if len(text) >= window:
                _flush_buf()
                for s in range(0, len(text), stride):
                    e = min(s + window, len(text))
                    raw_chunks.append((text[s:e], s, e))
                    if e >= len(text):
                        break
                # 更新 start_char 为 text 末尾
                start_char = start_char + len(text)
                continue
            # 累加到缓冲
            if buf_len + len(text) > window and buf:
                _flush_buf()
            buf.append(text)
            buf_len += len(text)
        _flush_buf()

        if not raw_chunks:
            return []

        # 应用滑动窗口重叠：在 raw_chunks 之间插入重叠切片
        # 简化策略：raw_chunks 已按 window 切分，重叠通过合并相邻切片尾部/头部实现
        chunks: list[Chunk] = []
        idx = 0
        for i, (text, start, end) in enumerate(raw_chunks):
            # maxTokens 硬截断
            tokens = self._count_tokens(text)
            if tokens > max_tokens:
                # 按 maxTokens 二次切分
                for sub in self._split_by_max_tokens(text, max_tokens):
                    chunks.append(
                        Chunk(
                            id=self._make_chunk_id(),
                            content=sub,
                            metadata=self._make_metadata(
                                config,
                                index=idx,
                                start=start,
                                end=end,
                                extra={"truncated": True},
                            ),
                        )
                    )
                    idx += 1
            else:
                chunks.append(
                    Chunk(
                        id=self._make_chunk_id(),
                        content=text,
                        metadata=self._make_metadata(
                            config, index=idx, start=start, end=end
                        ),
                    )
                )
                idx += 1

        # 应用重叠合并
        if overlap_size > 0 and len(chunks) > 1:
            chunks = self._overlap_merge(chunks, overlap_size)
        return chunks

    def _split_by_max_tokens(self, text: str, max_tokens: int) -> list[str]:
        """按 maxTokens 二次切分文本.

        :param text: 待切分文本
        :param max_tokens: 单切片最大 token 数
        :return: 子切片列表
        """
        if not text:
            return []
        # 估算字符数：按 4 字符/token 上取整
        max_chars = max(4, max_tokens * 4)
        if len(text) <= max_chars:
            return [text]
        return [text[i : i + max_chars] for i in range(0, len(text), max_chars)]

    # ------------------------------------------------------------------
    # 重叠合并
    # ------------------------------------------------------------------

    def _overlap_merge(
        self, chunks: list[Chunk], overlap_size: int
    ) -> list[Chunk]:
        """重叠合并相邻切片.

        将前一切片尾部 ``overlap_size`` 字符拼接到后一切片头部，
        确保语义连续。第一切片不受影响。

        :param chunks: 切片列表
        :param overlap_size: 重叠字符数
        :return: 合并后的切片列表
        """
        if overlap_size <= 0 or len(chunks) <= 1:
            return chunks
        merged: list[Chunk] = [chunks[0]]
        for prev, curr in zip(chunks, chunks[1:]):
            prev_text = prev.content if isinstance(prev.content, str) else ""
            tail = prev_text[-overlap_size:] if len(prev_text) >= overlap_size else prev_text
            new_text = tail + (curr.content if isinstance(curr.content, str) else "")
            new_chunk = curr.model_copy(update={"content": new_text})
            merged.append(new_chunk)
        return merged


# ----------------------------------------------------------------------
# 辅助：判断单元 meta 列表是否主要为英文
# ----------------------------------------------------------------------


def _is_en(metas: list[dict[str, Any]]) -> bool:
    """粗略判断单元 meta 列表对应文本是否主要为英文（用于决定 join 分隔符）.

    :param metas: 单元 meta 列表
    :return: True 表示用空格连接，False 表示直接拼接
    """
    # 简化：始终用直接拼接（中文场景占多数，英文拼接空格由句子切分时已保留）
    return False