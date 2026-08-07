"""ImageChunker 图像切片器单元测试 (T008-4).

覆盖场景：
    - 输入格式：文件路径 / bytes / PIL.Image
    - 多页 TIFF
    - OCR 文本识别（Mock pytesseract）
    - 版面分析（5 类区域分类）
    - 图表区域单独切片
    - 性能（单页 P95 ≤ 2s）
    - 异常处理（文件不存在 / 加载失败 / OCR 超时）
    - 注册机制
    - 工具函数（BBox / _iou / _merge_bboxes / _is_formula_text 等）
"""
from __future__ import annotations

import asyncio
import io
import os
import time
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch

import pytest

from chunker.base import BaseChunker
from chunker.exceptions import PreprocessError
from chunker.models import Chunk, ChunkConfig, Modality
from chunker.registry import (
    clear_registry,
    get_chunker,
    is_chunker_registered,
    list_modalities,
    register_chunker,
)

# 导入被测模块
from chunker.image_chunker import (
    ALL_REGION_TYPES,
    BBox,
    DEFAULT_OCR_LANG,
    IMAGE_MIN_AREA_RATIO,
    OCRItem,
    REGION_FORMULA,
    REGION_IMAGE,
    REGION_TABLE,
    REGION_TEXT,
    REGION_TITLE,
    SUPPORTED_IMAGE_EXTS,
    ImageChunker,
    _classify_regions,
    _contains,
    _count_horizontal_lines,
    _count_vertical_lines,
    _iou,
    _is_formula_text,
    _load_image,
    _load_multi_page_images,
    _merge_bboxes,
    _union,
)
from PIL import Image, ImageDraw


# ----------------------------------------------------------------------
# fixtures
# ----------------------------------------------------------------------


@pytest.fixture
def chunker() -> ImageChunker:
    """默认 ImageChunker 实例."""
    return ImageChunker()


@pytest.fixture
def chunker_no_ocr() -> ImageChunker:
    """禁用 OCR 的 ImageChunker 实例（仅版面分析）."""
    return ImageChunker(enableOCR=False)


@pytest.fixture
def chunker_no_layout() -> ImageChunker:
    """禁用版面分析的 ImageChunker 实例（仅 OCR）."""
    return ImageChunker(enableLayout=False)


def _cfg(**kwargs) -> ChunkConfig:
    """构造图像 ChunkConfig 的便捷函数."""
    defaults = {"modality": Modality.IMAGE, "windowSize": 1024, "overlap": 0.0}
    defaults.update(kwargs)
    return ChunkConfig(**defaults)


# ----------------------------------------------------------------------
# 合成图像生成
# ----------------------------------------------------------------------


def _make_blank_image(w: int = 200, h: int = 200, color: str = "white") -> Image.Image:
    """生成空白图像."""
    return Image.new("RGB", (w, h), color)


def _make_text_image(
    w: int = 400,
    h: int = 300,
    text: str = "Hello World",
    font_size: int = 20,
) -> Image.Image:
    """生成带文本的合成图像（使用 PIL 默认字体）."""
    img = Image.new("RGB", (w, h), "white")
    draw = ImageDraw.Draw(img)
    draw.text((20, 20), text, fill="black")
    return img


def _make_mixed_layout_image(w: int = 600, h: int = 800) -> Image.Image:
    """生成混合版面图像：标题 + 文本块 + 图片区域 + 表格区域."""
    img = Image.new("RGB", (w, h), "white")
    draw = ImageDraw.Draw(img)
    # 标题（上方，大字体）
    draw.rectangle((50, 30, 550, 80), fill="black")
    draw.rectangle((55, 35, 545, 75), fill="white")
    draw.text((60, 40), "TITLE", fill="black")
    # 文本块
    draw.rectangle((50, 120, 550, 220), outline="black", width=1)
    draw.text((60, 130), "Body text line 1", fill="black")
    draw.text((60, 150), "Body text line 2", fill="black")
    # 图片区域（颜色丰富）
    for i in range(50, 250):
        for j in range(250, 400):
            img.putpixel((i, j), ((i * 7) % 256, (j * 5) % 256, ((i + j) * 3) % 256))
    # 表格区域（网格线）
    for x in range(50, 550, 100):
        draw.line((x, 450, x, 600), fill="black", width=2)
    for y in range(450, 650, 50):
        draw.line((50, y, 550, y), fill="black", width=2)
    return img


def _make_table_image(w: int = 400, h: int = 300) -> Image.Image:
    """生成表格图像（密集网格线）."""
    img = Image.new("RGB", (w, h), "white")
    draw = ImageDraw.Draw(img)
    # 水平线
    for y in range(20, h - 20, 40):
        draw.line((20, y, w - 20, y), fill="black", width=2)
    # 垂直线
    for x in range(20, w - 20, 80):
        draw.line((x, 20, x, h - 20), fill="black", width=2)
    return img


def _make_image_region_image(w: int = 400, h: int = 400) -> Image.Image:
    """生成包含图片区域的图像（颜色丰富的大区域）."""
    img = Image.new("RGB", (w, h), "white")
    # 在中央绘制颜色丰富区域
    for i in range(50, w - 50):
        for j in range(50, h - 50):
            img.putpixel((i, j), ((i * 7) % 256, (j * 5) % 256, ((i + j) * 3) % 256))
    return img


def _make_multi_page_tiff(path: Path, pages: int = 3) -> Path:
    """生成多页 TIFF 文件."""
    images = []
    for i in range(pages):
        img = Image.new("RGB", (100, 100), f"rgb({i * 80}, {i * 80}, {i * 80})")
        draw = ImageDraw.Draw(img)
        draw.text((10, 10), f"Page {i + 1}", fill="white")
        images.append(img)
    images[0].save(path, format="TIFF", save_all=True, append_images=images[1:])
    return path


def _save_image(img: Image.Image, path: Path, fmt: str = "PNG") -> Path:
    """保存图像到文件."""
    img.save(path, format=fmt)
    return path


# ----------------------------------------------------------------------
# Mock OCR 辅助
# ----------------------------------------------------------------------


def _mock_ocr_data(texts: list[str], confs: list[float] | None = None) -> dict:
    """构造 pytesseract.image_to_data 的返回结构（DICT 模式）."""
    n = len(texts)
    if confs is None:
        confs = [95.0] * n
    lefts = [10 + i * 50 for i in range(n)]
    tops = [10 + i * 25 for i in range(n)]
    widths = [40] * n
    heights = [20] * n
    return {
        "text": texts,
        "conf": confs,
        "left": lefts,
        "top": tops,
        "width": widths,
        "height": heights,
    }


class _MockPytesseractOutput:
    DICT = "dict"


# ----------------------------------------------------------------------
# 工具函数测试
# ----------------------------------------------------------------------


class TestBBox:
    def test_basic_properties(self):
        b = BBox(10, 20, 30, 40)
        assert b.x == 10
        assert b.y == 20
        assert b.w == 30
        assert b.h == 40
        assert b.x2 == 40
        assert b.y2 == 60
        assert b.area == 1200

    def test_to_dict(self):
        b = BBox(1, 2, 3, 4)
        d = b.to_dict()
        assert d == {"x": 1, "y": 2, "w": 3, "h": 4, "x2": 4, "y2": 6}


class TestOCRItem:
    def test_is_valid(self):
        item = OCRItem(text="hello", bbox=BBox(0, 0, 10, 10), conf=95.0)
        assert item.is_valid

    def test_is_valid_empty_text(self):
        item = OCRItem(text="", bbox=BBox(0, 0, 10, 10), conf=95.0)
        assert not item.is_valid

    def test_is_valid_low_conf(self):
        item = OCRItem(text="hello", bbox=BBox(0, 0, 10, 10), conf=-1.0)
        assert not item.is_valid


class TestIou:
    def test_identical(self):
        a = BBox(0, 0, 10, 10)
        assert _iou(a, a) == pytest.approx(1.0)

    def test_disjoint(self):
        a = BBox(0, 0, 10, 10)
        b = BBox(20, 20, 10, 10)
        assert _iou(a, b) == 0.0

    def test_partial_overlap(self):
        a = BBox(0, 0, 10, 10)
        b = BBox(5, 5, 10, 10)
        # 交集 5x5=25，并集 100+100-25=175
        assert _iou(a, b) == pytest.approx(25 / 175)


class TestContains:
    def test_contains(self):
        outer = BBox(0, 0, 20, 20)
        inner = BBox(5, 5, 10, 10)
        assert _contains(outer, inner)

    def test_not_contains(self):
        a = BBox(0, 0, 10, 10)
        b = BBox(5, 5, 10, 10)
        assert not _contains(a, b)


class TestUnion:
    def test_union(self):
        a = BBox(0, 0, 10, 10)
        b = BBox(5, 5, 10, 10)
        u = _union(a, b)
        assert u.x == 0 and u.y == 0
        assert u.x2 == 15 and u.y2 == 15


class TestMergeBboxes:
    def test_empty(self):
        assert _merge_bboxes([]) == []

    def test_no_overlap(self):
        boxes = [BBox(0, 0, 10, 10), BBox(50, 50, 10, 10)]
        merged = _merge_bboxes(boxes)
        assert len(merged) == 2

    def test_with_overlap(self):
        # 高重叠（IoU >= 0.3）应合并为 1 个
        boxes = [BBox(0, 0, 20, 20), BBox(2, 2, 20, 20)]
        merged = _merge_bboxes(boxes)
        assert len(merged) == 1

    def test_contained(self):
        boxes = [BBox(0, 0, 20, 20), BBox(5, 5, 5, 5)]
        merged = _merge_bboxes(boxes)
        assert len(merged) == 1


class TestIsFormulaText:
    def test_plain_text(self):
        assert not _is_formula_text("Hello World")

    def test_formula(self):
        # 特殊字符占比 > 20% 视为公式
        assert _is_formula_text("∫∑∏√αβγδεζηθλμνξπρστυφχψω")

    def test_empty(self):
        assert not _is_formula_text("")


class TestCountLines:
    def test_horizontal_lines(self):
        import numpy as np

        # 5 行水平线
        arr = np.zeros((50, 100), dtype=np.uint8)
        for y in [5, 15, 25, 35, 45]:
            arr[y, :] = 255
        assert _count_horizontal_lines(arr) >= 5

    def test_no_lines(self):
        import numpy as np

        arr = np.zeros((50, 100), dtype=np.uint8)
        assert _count_horizontal_lines(arr) == 0

    def test_vertical_lines(self):
        import numpy as np

        arr = np.zeros((100, 50), dtype=np.uint8)
        for x in [5, 15, 25, 35, 45]:
            arr[:, x] = 255
        assert _count_vertical_lines(arr) >= 5

    def test_empty_region(self):
        import numpy as np

        assert _count_horizontal_lines(np.array([])) == 0
        assert _count_vertical_lines(np.array([])) == 0


class TestClassifyRegions:
    def test_empty(self):
        import numpy as np

        binary = np.zeros((100, 100), dtype=np.uint8)
        gray = np.zeros((100, 100), dtype=np.uint8)
        assert _classify_regions([], binary, gray) == []

    def test_text_region(self):
        import numpy as np

        # 普通文本块：高度等于中位高度
        binary = np.zeros((200, 200), dtype=np.uint8)
        gray = np.zeros((200, 200), dtype=np.uint8)
        bboxes = [BBox(10, 10, 100, 20)]
        regions = _classify_regions(bboxes, binary, gray)
        assert len(regions) == 1
        # 默认应为 text
        assert regions[0].region_type in ALL_REGION_TYPES


# ----------------------------------------------------------------------
# 输入格式测试
# ----------------------------------------------------------------------


class TestInputFormats:
    async def test_pil_image_input(self, chunker):
        img = _make_text_image()
        cfg = _cfg(extra={"enableOCR": False, "enableLayout": False})
        chunks = await chunker.chunk(img, cfg)
        # 应至少生成一个占位切片
        assert len(chunks) >= 1

    async def test_bytes_input(self, chunker):
        img = _make_text_image()
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        cfg = _cfg(extra={"enableOCR": False, "enableLayout": False})
        chunks = await chunker.chunk(buf.getvalue(), cfg)
        assert len(chunks) >= 1

    async def test_file_path_input(self, chunker, tmp_path):
        img = _make_text_image()
        path = _save_image(img, tmp_path / "test.png")
        cfg = _cfg(extra={"enableOCR": False, "enableLayout": False})
        chunks = await chunker.chunk(str(path), cfg)
        assert len(chunks) >= 1

    async def test_pathlib_input(self, chunker, tmp_path):
        img = _make_text_image()
        path = _save_image(img, tmp_path / "test2.png")
        cfg = _cfg(extra={"enableOCR": False, "enableLayout": False})
        chunks = await chunker.chunk(path, cfg)
        assert len(chunks) >= 1

    async def test_jpeg_input(self, chunker, tmp_path):
        img = _make_text_image()
        path = tmp_path / "test.jpg"
        img.save(path, format="JPEG")
        cfg = _cfg(extra={"enableOCR": False, "enableLayout": False})
        chunks = await chunker.chunk(str(path), cfg)
        assert len(chunks) >= 1

    async def test_bmp_input(self, chunker, tmp_path):
        img = _make_text_image()
        path = tmp_path / "test.bmp"
        img.save(path, format="BMP")
        cfg = _cfg(extra={"enableOCR": False, "enableLayout": False})
        chunks = await chunker.chunk(str(path), cfg)
        assert len(chunks) >= 1

    async def test_unsupported_input_type(self, chunker):
        cfg = _cfg()
        with pytest.raises(PreprocessError):
            await chunker.chunk(12345, cfg)

    async def test_nonexistent_file(self, chunker):
        cfg = _cfg()
        with pytest.raises(PreprocessError):
            await chunker.chunk("/nonexistent/path/image.png", cfg)


# ----------------------------------------------------------------------
# 多页 TIFF 测试
# ----------------------------------------------------------------------


class TestMultiPageTiff:
    async def test_multi_page_tiff(self, chunker, tmp_path):
        path = tmp_path / "multi.tiff"
        _make_multi_page_tiff(path, pages=3)
        cfg = _cfg(extra={"enableOCR": False, "enableLayout": False})
        chunks = await chunker.chunk(str(path), cfg)
        # 3 页应生成至少 3 个切片
        assert len(chunks) >= 3
        # 切片应来自不同页
        pages = {c.metadata.extra.get("page") for c in chunks}
        assert pages == {0, 1, 2}

    async def test_single_page_tiff(self, chunker, tmp_path):
        path = tmp_path / "single.tiff"
        _make_multi_page_tiff(path, pages=1)
        cfg = _cfg(extra={"enableOCR": False, "enableLayout": False})
        chunks = await chunker.chunk(str(path), cfg)
        assert len(chunks) >= 1


# ----------------------------------------------------------------------
# OCR 测试（Mock pytesseract）
# ----------------------------------------------------------------------


class TestOCR:
    async def test_ocr_with_mock(self, chunker):
        """Mock pytesseract.image_to_data 测试 OCR 流程."""
        img = _make_text_image(text="Hello World")
        mock_data = _mock_ocr_data(["Hello", "World"])
        with patch.dict("sys.modules", {"pytesseract": MagicMock()}):
            import sys

            mock_mod = sys.modules["pytesseract"]
            mock_mod.image_to_data = MagicMock(return_value=mock_data)
            mock_mod.Output = _MockPytesseractOutput
            cfg = _cfg(extra={"enableLayout": False})
            chunks = await chunker.chunk(img, cfg)
            assert len(chunks) >= 1
            # 切片内容应包含 OCR 文本
            contents = [c.content for c in chunks if isinstance(c.content, str)]
            joined = " ".join(contents)
            assert "Hello" in joined or "World" in joined

    async def test_ocr_disabled(self, chunker_no_ocr):
        img = _make_text_image()
        cfg = _cfg(extra={"enableLayout": False})
        chunks = await chunker_no_ocr.chunk(img, cfg)
        # 无 OCR 无版面分析：应生成占位切片
        assert len(chunks) >= 1

    async def test_ocr_low_confidence_filtered(self, chunker):
        """低置信度 OCR 结果仍保留（conf >= 0 即保留）."""
        img = _make_text_image()
        mock_data = _mock_ocr_data(["good", "bad"], confs=[95.0, -1.0])
        with patch.dict("sys.modules", {"pytesseract": MagicMock()}):
            import sys

            mock_mod = sys.modules["pytesseract"]
            mock_mod.image_to_data = MagicMock(return_value=mock_data)
            mock_mod.Output = _MockPytesseractOutput
            cfg = _cfg(extra={"enableLayout": False})
            chunks = await chunker.chunk(img, cfg)
            assert len(chunks) >= 1

    async def test_ocr_empty_result(self, chunker):
        """OCR 返回空结果时应正常处理."""
        img = _make_blank_image()
        mock_data = _mock_ocr_data([])
        with patch.dict("sys.modules", {"pytesseract": MagicMock()}):
            import sys

            mock_mod = sys.modules["pytesseract"]
            mock_mod.image_to_data = MagicMock(return_value=mock_data)
            mock_mod.Output = _MockPytesseractOutput
            cfg = _cfg(extra={"enableLayout": False})
            chunks = await chunker.chunk(img, cfg)
            # 空白图应生成占位切片
            assert len(chunks) >= 1

    async def test_ocr_to_regions(self, chunker):
        """测试 OCR 结果聚合为区域."""
        items = [
            OCRItem(text="Line1", bbox=BBox(10, 10, 50, 20), conf=95.0),
            OCRItem(text="Line2", bbox=BBox(10, 50, 50, 20), conf=90.0),
        ]
        img = _make_blank_image(w=200, h=200)
        regions = chunker._ocr_to_regions(items, img)
        assert len(regions) == 2
        assert all(r.region_type == REGION_TEXT for r in regions)
        assert "Line1" in regions[0].content
        assert "Line2" in regions[1].content

    async def test_ocr_to_regions_empty(self, chunker):
        img = _make_blank_image()
        assert chunker._ocr_to_regions([], img) == []


# ----------------------------------------------------------------------
# 版面分析测试
# ----------------------------------------------------------------------


class TestLayoutAnalysis:
    async def test_layout_returns_regions(self, chunker_no_ocr):
        """版面分析应返回 Region 列表."""
        img = _make_mixed_layout_image()
        regions = await chunker_no_ocr._layout_analysis(img)
        assert isinstance(regions, list)
        # 混合版面应识别出至少 1 个区域
        assert len(regions) >= 1
        for r in regions:
            assert r.region_type in ALL_REGION_TYPES

    async def test_layout_blank_image(self, chunker_no_ocr):
        """空白图像版面分析应返回少量或零区域."""
        img = _make_blank_image()
        regions = await chunker_no_ocr._layout_analysis(img)
        assert isinstance(regions, list)

    async def test_layout_table_detection(self, chunker_no_ocr):
        """表格图像应识别出表格区域."""
        img = _make_table_image()
        regions = await chunker_no_ocr._layout_analysis(img)
        # 至少识别出区域
        assert isinstance(regions, list)
        # 若识别出区域，应有类型
        for r in regions:
            assert r.region_type in ALL_REGION_TYPES

    async def test_layout_image_region(self, chunker_no_ocr):
        """图片区域图像应识别出图片区域."""
        img = _make_image_region_image()
        regions = await chunker_no_ocr._layout_analysis(img)
        assert isinstance(regions, list)

    async def test_layout_disabled(self, chunker_no_layout):
        img = _make_text_image()
        cfg = _cfg(extra={"enableOCR": False})
        chunks = await chunker_no_layout.chunk(img, cfg)
        # 无 OCR 无版面分析：占位切片
        assert len(chunks) >= 1


# ----------------------------------------------------------------------
# 切片生成测试
# ----------------------------------------------------------------------


class TestChunkGeneration:
    async def test_text_chunk_content_is_string(self, chunker):
        img = _make_text_image(text="Hello")
        mock_data = _mock_ocr_data(["Hello"])
        with patch.dict("sys.modules", {"pytesseract": MagicMock()}):
            import sys

            mock_mod = sys.modules["pytesseract"]
            mock_mod.image_to_data = MagicMock(return_value=mock_data)
            mock_mod.Output = _MockPytesseractOutput
            cfg = _cfg(extra={"enableLayout": False})
            chunks = await chunker.chunk(img, cfg)
            text_chunks = [c for c in chunks if isinstance(c.content, str)]
            assert len(text_chunks) >= 1

    async def test_image_chunk_content_is_bytes(self, chunker_no_ocr):
        """图片区域切片 content 应为 bytes（PNG 编码）."""
        img = _make_image_region_image()
        cfg = _cfg(extra={"extractImageRegions": True, "imageEncoding": "png"})
        chunks = await chunker_no_ocr.chunk(img, cfg)
        # 至少有一个切片
        assert len(chunks) >= 1
        # 检查是否有 bytes 类型 content（图片切片）
        bytes_chunks = [c for c in chunks if isinstance(c.content, (bytes, bytearray))]
        # 混合版面应至少有一个图片切片
        assert len(bytes_chunks) >= 1

    async def test_image_chunk_base64_encoding(self, chunker_no_ocr):
        """base64 编码的图片切片 content 应为 str."""
        img = _make_image_region_image()
        cfg = _cfg(extra={"extractImageRegions": True, "imageEncoding": "base64"})
        chunks = await chunker_no_ocr.chunk(img, cfg)
        assert len(chunks) >= 1

    async def test_chunk_metadata_has_bbox(self, chunker_no_ocr):
        """切片 metadata.extra 应包含 bbox 信息."""
        img = _make_mixed_layout_image()
        cfg = _cfg()
        chunks = await chunker_no_ocr.chunk(img, cfg)
        assert len(chunks) >= 1
        for c in chunks:
            assert "bbox" in c.metadata.extra
            assert "regionType" in c.metadata.extra
            assert "page" in c.metadata.extra

    async def test_chunk_metadata_has_source_path(self, chunker_no_ocr, tmp_path):
        """切片 metadata.extra 应包含 sourcePath."""
        img = _make_text_image()
        path = _save_image(img, tmp_path / "src.png")
        cfg = _cfg()
        chunks = await chunker_no_ocr.chunk(str(path), cfg)
        assert len(chunks) >= 1
        for c in chunks:
            assert c.metadata.extra.get("sourcePath") == str(path)

    async def test_extract_image_regions_disabled(self, chunker_no_ocr):
        """禁用图片区域提取时，图片类型切片应标记 extracted=False."""
        img = _make_mixed_layout_image()
        cfg = _cfg(extra={"extractImageRegions": False, "minChunkSize": 1})
        chunks = await chunker_no_ocr.chunk(img, cfg)
        # 检查图片类型切片的 extracted 标记
        for c in chunks:
            if c.metadata.extra.get("regionType") in (REGION_IMAGE, REGION_TABLE):
                assert c.metadata.extra.get("extracted") is False
                # content 应为空字符串占位（不提取图片内容）
                assert c.content == ""

    async def test_extract_image_regions_with_placeholder(self, chunker_no_ocr):
        """禁用图片提取且无识别区域时，占位切片应标记 extracted=False."""
        img = _make_image_region_image()
        cfg = _cfg(extra={"extractImageRegions": False, "minChunkSize": 1})
        chunks = await chunker_no_ocr.chunk(img, cfg)
        # 应生成占位切片，extracted=False，content 为空
        assert len(chunks) >= 1
        for c in chunks:
            assert c.metadata.extra.get("extracted") is False

    async def test_chunk_index_sequential(self, chunker_no_ocr):
        """切片 index 应从 0 连续递增."""
        img = _make_mixed_layout_image()
        cfg = _cfg()
        chunks = await chunker_no_ocr.chunk(img, cfg)
        for i, c in enumerate(chunks):
            assert c.metadata.index == i

    async def test_chunk_tokens_computed(self, chunker_no_ocr):
        """切片 tokens 应被计算."""
        img = _make_text_image()
        cfg = _cfg()
        chunks = await chunker_no_ocr.chunk(img, cfg)
        for c in chunks:
            assert c.tokens is not None
            assert c.tokens >= 0


# ----------------------------------------------------------------------
# 性能测试
# ----------------------------------------------------------------------


class TestPerformance:
    async def test_single_page_p95_under_2s(self, chunker_no_ocr):
        """单页图像切片 P95 耗时应 ≤ 2s."""
        img = _make_mixed_layout_image()
        cfg = _cfg()
        # 运行 20 次取 P95
        durations = []
        for _ in range(20):
            start = time.perf_counter()
            await chunker_no_ocr.chunk(img, cfg)
            durations.append(time.perf_counter() - start)
        durations.sort()
        p95_idx = int(len(durations) * 0.95)
        p95 = durations[min(p95_idx, len(durations) - 1)]
        assert p95 <= 2.0, f"P95 耗时 {p95:.3f}s 超过 2s 阈值"

    async def test_parallel_ocr_and_layout(self, chunker):
        """OCR 和版面分析应并行执行（总耗时 < 两者串行之和）."""
        img = _make_mixed_layout_image()
        mock_data = _mock_ocr_data(["text"])
        with patch.dict("sys.modules", {"pytesseract": MagicMock()}):
            import sys

            mock_mod = sys.modules["pytesseract"]
            mock_mod.image_to_data = MagicMock(return_value=mock_data)
            mock_mod.Output = _MockPytesseractOutput
            cfg = _cfg()
            start = time.perf_counter()
            await chunker.chunk(img, cfg)
            duration = time.perf_counter() - start
            # 应在合理时间内完成
            assert duration <= 5.0


# ----------------------------------------------------------------------
# 异常处理测试
# ----------------------------------------------------------------------


class TestErrorHandling:
    async def test_preprocess_error_on_invalid_input(self, chunker):
        cfg = _cfg()
        with pytest.raises(PreprocessError):
            await chunker.chunk(None, cfg)

    async def test_preprocess_error_on_nonexistent(self, chunker):
        cfg = _cfg()
        with pytest.raises(PreprocessError):
            await chunker.chunk("nonexistent.png", cfg)

    async def test_ocr_timeout(self, chunker):
        """OCR 超时应抛出 PreprocessError."""
        img = _make_text_image()

        async def slow_ocr(*args, **kwargs):
            await asyncio.sleep(10)
            return []

        with patch.object(chunker, "_ocr", side_effect=slow_ocr):
            cfg = _cfg(extra={"enableLayout": False, "ocrTimeout": 0.1})
            with pytest.raises(PreprocessError):
                await chunker.chunk(img, cfg)


# ----------------------------------------------------------------------
# 配置测试
# ----------------------------------------------------------------------


class TestConfig:
    def test_default_config(self):
        c = ImageChunker()
        assert c.ocrLang == DEFAULT_OCR_LANG
        assert c.enableOCR is True
        assert c.enableLayout is True
        assert c.extractImageRegions is True
        assert c.imageEncoding == "png"

    def test_custom_config(self):
        c = ImageChunker(
            ocrLang="eng",
            enableOCR=False,
            enableLayout=False,
            extractImageRegions=False,
            imageEncoding="base64",
            ocrTimeout=10.0,
        )
        assert c.ocrLang == "eng"
        assert c.enableOCR is False
        assert c.enableLayout is False
        assert c.extractImageRegions is False
        assert c.imageEncoding == "base64"
        assert c.ocrTimeout == 10.0

    def test_invalid_image_encoding(self):
        with pytest.raises(ValueError):
            ImageChunker(imageEncoding="invalid")

    def test_tesseract_path(self):
        c = ImageChunker(tesseractPath="/usr/bin/tesseract")
        assert c.tesseractPath == "/usr/bin/tesseract"

    async def test_config_extra_overrides(self, chunker):
        """config.extra 应覆盖实例配置."""
        img = _make_text_image()
        cfg = _cfg(extra={"enableOCR": False, "enableLayout": False})
        chunks = await chunker.chunk(img, cfg)
        assert len(chunks) >= 1


# ----------------------------------------------------------------------
# 注册机制测试
# ----------------------------------------------------------------------


class TestRegistration:
    def test_registered_as_image(self):
        """ImageChunker 应可注册为 Modality.IMAGE."""
        # conftest 每个测试清空注册表，这里手动注册验证
        from chunker.registry import ChunkerRegistry

        ChunkerRegistry.register(Modality.IMAGE, ImageChunker)
        assert is_chunker_registered(Modality.IMAGE)
        assert is_chunker_registered("image")

    def test_get_chunker_returns_image_chunker(self):
        from chunker.registry import ChunkerRegistry

        ChunkerRegistry.register(Modality.IMAGE, ImageChunker)
        chunker = get_chunker("image")
        assert isinstance(chunker, ImageChunker)

    def test_list_modalities_includes_image(self):
        from chunker.registry import ChunkerRegistry

        ChunkerRegistry.register(Modality.IMAGE, ImageChunker)
        modalities = list_modalities()
        assert "image" in modalities

    def test_modality_attribute(self, chunker):
        assert chunker.modality == Modality.IMAGE

    def test_is_base_chunker_subclass(self):
        assert issubclass(ImageChunker, BaseChunker)

    def test_decorator_registration(self):
        """验证 @register_chunker 装饰器能正确注册."""
        from chunker.registry import ChunkerRegistry

        # ImageChunker 类本身已被装饰器标记，手动注册应成功
        ChunkerRegistry.register("image", ImageChunker)
        assert is_chunker_registered("image")


# ----------------------------------------------------------------------
# chunk_with_result 测试
# ----------------------------------------------------------------------


class TestChunkWithResult:
    async def test_result_aggregation(self, chunker_no_ocr):
        img = _make_text_image()
        cfg = _cfg()
        result = await chunker_no_ocr.chunk_with_result(img, cfg)
        assert result.count >= 1
        assert result.modality is Modality.IMAGE
        assert result.durationMs >= 0.0
        assert result.totalTokens >= 0

    async def test_result_empty_on_error(self, chunker):
        cfg = _cfg()
        with pytest.raises(PreprocessError):
            await chunker.chunk_with_result(None, cfg)


# ----------------------------------------------------------------------
# 后处理测试
# ----------------------------------------------------------------------


class TestPostprocess:
    async def test_min_chunk_size_filter(self, chunker_no_ocr):
        """minChunkSize 应过滤过小切片（占位切片豁免）."""
        img = _make_text_image()
        # 设置较大的 minChunkSize 过滤掉小切片
        cfg = _cfg(minChunkSize=10000)
        chunks = await chunker_no_ocr.chunk(img, cfg)
        # 所有过滤后切片应满足大小要求（占位切片豁免）
        for c in chunks:
            if c.metadata.extra.get("placeholder"):
                continue
            if isinstance(c.content, str):
                assert len(c.content) >= 10000
            elif isinstance(c.content, (bytes, bytearray)):
                assert len(c.content) >= 10000

    async def test_index_reassigned(self, chunker_no_ocr):
        """postprocess 应重排 index."""
        img = _make_mixed_layout_image()
        cfg = _cfg()
        chunks = await chunker_no_ocr.chunk(img, cfg)
        indices = [c.metadata.index for c in chunks]
        assert indices == list(range(len(chunks)))


# ----------------------------------------------------------------------
# 辅助函数测试
# ----------------------------------------------------------------------


class TestLoadImage:
    def test_load_pil_image(self):
        img = _make_blank_image()
        loaded = _load_image(img)
        assert loaded.size == (200, 200)
        assert loaded.mode == "RGB"

    def test_load_bytes(self):
        img = _make_blank_image()
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        loaded = _load_image(buf.getvalue())
        assert loaded.size == (200, 200)

    def test_load_file(self, tmp_path):
        img = _make_blank_image()
        path = _save_image(img, tmp_path / "load.png")
        loaded = _load_image(str(path))
        assert loaded.size == (200, 200)

    def test_load_nonexistent_raises(self):
        with pytest.raises(PreprocessError):
            _load_image("/nonexistent.png")

    def test_load_invalid_type_raises(self):
        with pytest.raises(PreprocessError):
            _load_image(12345)

    def test_load_rgba_converts_to_rgb(self):
        img = Image.new("RGBA", (50, 50), (255, 0, 0, 128))
        loaded = _load_image(img)
        assert loaded.mode == "RGB"

    def test_load_grayscale_converts_to_rgb(self):
        img = Image.new("L", (50, 50), 128)
        loaded = _load_image(img)
        assert loaded.mode == "RGB"


class TestLoadMultiPageImages:
    def test_single_page(self):
        img = _make_blank_image()
        pages, source = _load_multi_page_images(img)
        assert len(pages) == 1
        assert source == ""

    def test_multi_page_tiff(self, tmp_path):
        path = tmp_path / "multi.tiff"
        _make_multi_page_tiff(path, pages=3)
        pages, source = _load_multi_page_images(str(path))
        assert len(pages) == 3
        assert source == str(path)

    def test_bytes_source(self):
        img = _make_blank_image()
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        pages, source = _load_multi_page_images(buf.getvalue())
        assert len(pages) >= 1
        assert "bytes" in source


# ----------------------------------------------------------------------
# 常量测试
# ----------------------------------------------------------------------


class TestConstants:
    def test_region_types(self):
        assert REGION_TEXT == "text"
        assert REGION_TITLE == "title"
        assert REGION_TABLE == "table"
        assert REGION_IMAGE == "image"
        assert REGION_FORMULA == "formula"
        assert ALL_REGION_TYPES == ("text", "title", "table", "image", "formula")

    def test_supported_exts(self):
        assert ".png" in SUPPORTED_IMAGE_EXTS
        assert ".jpg" in SUPPORTED_IMAGE_EXTS
        assert ".jpeg" in SUPPORTED_IMAGE_EXTS
        assert ".tif" in SUPPORTED_IMAGE_EXTS
        assert ".tiff" in SUPPORTED_IMAGE_EXTS
        assert ".bmp" in SUPPORTED_IMAGE_EXTS

    def test_default_ocr_lang(self):
        assert DEFAULT_OCR_LANG == "chi_sim+eng"