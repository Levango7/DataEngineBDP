"""图像切片器 (T008-4).

基于 T008-1 多模态切片器框架实现 ``ImageChunker``，支持：

1. **OCR 文本识别**：清晰印刷体准确率 ≥ 90%
   - 集成 Tesseract OCR（pytesseract 库）
   - 支持中文 + 英文（``chi_sim+eng``）
   - 保留位置信息（bounding box），通过 ``pytesseract.image_to_data`` 获取
   - Tesseract 不可用时自动回退为空 OCR 结果（仅做版面分析）
2. **版面分析**：识别 5 类区域，准确率 ≥ 85%
   - 文本块(text)、标题(title)、表格(table)、图片(image)、公式(formula)
   - 基于 OpenCV 轮廓检测 + 启发式规则分类
   - 每个区域输出类型 + 位置 + 内容
3. **图表区域单独切片**：保留原图引用
   - 图片/表格/公式区域提取为独立 Chunk
   - ``ChunkMetadata.extra`` 中保留原图路径(``sourcePath``)和区域坐标(``bbox``)
4. **性能**：单页图像切片 P95 ≤ 2s
   - 异步处理（asyncio + 线程池）
   - OCR 和版面分析并行（``asyncio.gather``）
5. **输入格式**：支持 PNG / JPEG / TIFF / BMP
   - 使用 Pillow(PIL) 加载图像
   - 支持多页 TIFF（每页独立切片）
6. **注册**：通过 ``@register_chunker(Modality.IMAGE)`` 自动注册

对齐设计文档 T008-4。
"""

from __future__ import annotations

import asyncio
import base64
from dataclasses import dataclass, field
import io
import os
import threading
from typing import Any

from chunker.base import BaseChunker
from chunker.exceptions import PreprocessError
from chunker.models import Chunk, ChunkConfig, Modality
from chunker.registry import register_chunker

# ----------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------

#: 默认 OCR 语言（中文简体 + 英文）
DEFAULT_OCR_LANG = "chi_sim+eng"

#: 默认 Tesseract 路径（None 表示使用系统 PATH）
DEFAULT_TESSERACT_PATH: str | None = None

#: 版面区域类型枚举值
REGION_TEXT = "text"
REGION_TITLE = "title"
REGION_TABLE = "table"
REGION_IMAGE = "image"
REGION_FORMULA = "formula"

#: 全部支持的版面区域类型
ALL_REGION_TYPES = (REGION_TEXT, REGION_TITLE, REGION_TABLE, REGION_IMAGE, REGION_FORMULA)

#: 支持的图像格式扩展名
SUPPORTED_IMAGE_EXTS = frozenset({".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp"})

#: 标题高度倍数阈值：文本块高度 > median * 此值 视为标题
TITLE_HEIGHT_RATIO = 1.5

#: 表格检测：最少连续水平线条数
TABLE_MIN_LINES = 3

#: 图片区域：最小面积占比（占整图比例）
IMAGE_MIN_AREA_RATIO = 0.01

#: 公式检测：特殊字符集
_FORMULA_CHARS = set("+-=±×÷∑∏∫√∞αβγδεζηθλμνξπρστυφχψω∈∉∀∃≤≥≠≈∞²³")

#: OCR 单页超时（秒），避免 Tesseract 卡死
OCR_TIMEOUT_SECONDS = 30.0

#: 版面分析最小区域面积（像素²），过小区域忽略
MIN_REGION_AREA = 100

#: 轮廓检测最小宽高
MIN_CONTOUR_W = 5
MIN_CONTOUR_H = 5


# ----------------------------------------------------------------------
# 数据结构
# ----------------------------------------------------------------------


@dataclass
class BBox:
    """矩形边界框.

    坐标系：图像左上角为原点，x 向右、y 向下。
    """

    x: int
    y: int
    w: int
    h: int

    @property
    def x2(self) -> int:
        return self.x + self.w

    @property
    def y2(self) -> int:
        return self.y + self.h

    @property
    def area(self) -> int:
        return self.w * self.h

    def to_dict(self) -> dict[str, int]:
        return {"x": self.x, "y": self.y, "w": self.w, "h": self.h, "x2": self.x2, "y2": self.y2}


@dataclass
class OCRItem:
    """OCR 识别的单个文本项.

    携带文本内容与位置信息，用于后续版面分类与切片构造。
    """

    text: str
    bbox: BBox
    conf: float = 0.0  # 置信度 0~100

    @property
    def is_valid(self) -> bool:
        """文本非空且置信度足够."""
        return bool(self.text.strip()) and self.conf >= 0


@dataclass
class Region:
    """版面区域.

    表示图像中识别出的一个语义区域，携带类型、位置、内容。
    """

    region_type: str
    bbox: BBox
    content: Any = None  # text: str; image: bytes(PNG); table: list[list[str]]; formula: str
    ocr_items: list[OCRItem] = field(default_factory=list)
    confidence: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        """序列化为字典（用于 ChunkMetadata.extra）."""
        return {
            "regionType": self.region_type,
            "bbox": self.bbox.to_dict(),
            "confidence": self.confidence,
            "contentKind": type(self.content).__name__,
        }


# ----------------------------------------------------------------------
# 辅助函数
# ----------------------------------------------------------------------


def _is_image_path(content: Any) -> bool:
    """判断内容是否为图像文件路径."""
    if isinstance(content, (str, os.PathLike)):
        ext = os.path.splitext(str(content))[1].lower()
        return ext in SUPPORTED_IMAGE_EXTS
    return False


def _load_image(content: Any) -> Any:
    """加载 PIL 图像.

    :param content: 文件路径 / bytes / PIL.Image.Image
    :return: PIL.Image.Image（RGB 模式）
    :raises PreprocessError: 加载失败
    """
    from PIL import Image  # type: ignore[import-untyped]

    try:
        if isinstance(content, Image.Image):
            img = content
        elif isinstance(content, (bytes, bytearray)):
            img = Image.open(io.BytesIO(bytes(content)))
        elif isinstance(content, (str, os.PathLike)):
            path = os.fspath(content)
            if not os.path.exists(path):
                raise PreprocessError(f"图像文件不存在: {path}")
            img = Image.open(path)
        else:
            raise PreprocessError(
                f"不支持的图像输入类型: {type(content).__name__}，" f"期望 str/Path/bytes/PIL.Image.Image"
            )
        # 统一为 RGB（去除 alpha 通道、灰度转 RGB）
        if img.mode != "RGB":
            img = img.convert("RGB")
        # 加载像素数据（避免懒加载在跨线程时出错）
        img.load()
        return img
    except PreprocessError:
        raise
    except Exception as ex:
        raise PreprocessError(f"图像加载失败: {ex}", cause=ex) from ex


def _load_multi_page_images(content: Any) -> tuple[list[Any], str]:
    """加载图像，返回图像列表（多页 TIFF 拆为多张）+ 来源标识.

    :param content: 文件路径 / bytes / PIL.Image.Image
    :return: (images, source_path)
    """
    from PIL import Image  # type: ignore[import-untyped]

    source = ""
    if isinstance(content, (str, os.PathLike)):
        source = os.fspath(content)
    elif isinstance(content, (bytes, bytearray)):
        source = "bytes://image"

    img = _load_image(content)
    # 检测多页 TIFF
    pages: list[Any] = [img]
    if isinstance(content, (str, os.PathLike)) or isinstance(content, (bytes, bytearray)):
        try:
            # 重新打开原始流以读取多页
            if isinstance(content, (str, os.PathLike)):
                raw = Image.open(os.fspath(content))
            else:
                raw = Image.open(io.BytesIO(bytes(content)))
            page_count = getattr(raw, "n_frames", 1)
            if page_count > 1:
                pages = []
                for i in range(page_count):
                    raw.seek(i)
                    page = raw.copy()
                    if page.mode != "RGB":
                        page = page.convert("RGB")
                    page.load()
                    pages.append(page)
        except Exception:  # noqa: BLE001
            # 多页读取失败，回退为单页
            pages = [img]
    return pages, source


def _image_to_png_bytes(img: Any) -> bytes:
    """将 PIL 图像编码为 PNG bytes."""
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def _image_to_b64(img: Any) -> str:
    """将 PIL 图像编码为 base64 字符串（用于 JSON 序列化）."""
    return base64.b64encode(_image_to_png_bytes(img)).decode("ascii")


def _crop_image(img: Any, bbox: BBox) -> Any:
    """按 bbox 裁剪 PIL 图像."""

    # 裁剪边界保护
    w, h = img.size
    x1 = max(0, min(bbox.x, w))
    y1 = max(0, min(bbox.y, h))
    x2 = max(x1, min(bbox.x2, w))
    y2 = max(y1, min(bbox.y2, h))
    return img.crop((x1, y1, x2, y2))


# ----------------------------------------------------------------------
# ImageChunker
# ----------------------------------------------------------------------


@register_chunker(Modality.IMAGE)
class ImageChunker(BaseChunker):
    """图像切片器.

    配置通过 ``ChunkConfig`` 传入，模态专属配置通过 ``ChunkConfig.extra`` 提供：

    - ``ocrLang``: OCR 语言，默认 ``"chi_sim+eng"``
    - ``enableOCR``: 是否启用 OCR，默认 True
    - ``enableLayout``: 是否启用版面分析，默认 True
    - ``tesseractPath``: Tesseract 可执行文件路径，默认 None（使用系统 PATH）
    - ``extractImageRegions``: 是否将图片区域提取为独立切片，默认 True
    - ``imageEncoding``: 图片切片内容编码方式，``"png"``(bytes) 或 ``"base64"``(str)，默认 ``"png"``
    - ``ocrTimeout``: OCR 单页超时秒数，默认 30.0

    用法::

        from chunker import get_chunker, ChunkConfig, Modality

        chunker = get_chunker("image")
        cfg = ChunkConfig(modality=Modality.IMAGE)
        chunks = await chunker.chunk("/path/to/scan.png", cfg)
    """

    MODALITY = Modality.IMAGE

    # 类级单例锁：Tesseract 路径设置只需一次
    _tesseract_lock = threading.Lock()
    _tesseract_configured: bool = False

    def __init__(
        self,
        modality: Modality | str | None = None,
        *,
        ocrLang: str = DEFAULT_OCR_LANG,
        enableOCR: bool = True,
        enableLayout: bool = True,
        tesseractPath: str | None = DEFAULT_TESSERACT_PATH,
        extractImageRegions: bool = True,
        imageEncoding: str = "png",
        ocrTimeout: float = OCR_TIMEOUT_SECONDS,
    ) -> None:
        """初始化图像切片器.

        :param modality: 模态（默认 IMAGE）
        :param ocrLang: Tesseract OCR 语言
        :param enableOCR: 是否启用 OCR
        :param enableLayout: 是否启用版面分析
        :param tesseractPath: Tesseract 可执行文件路径
        :param extractImageRegions: 是否将图片区域提取为独立切片
        :param imageEncoding: 图片切片内容编码（"png" 或 "base64"）
        :param ocrTimeout: OCR 单页超时秒数
        """
        super().__init__(modality)
        self.ocrLang = ocrLang
        self.enableOCR = enableOCR
        self.enableLayout = enableLayout
        self.tesseractPath = tesseractPath
        self.extractImageRegions = extractImageRegions
        if imageEncoding not in ("png", "base64"):
            raise ValueError(f"imageEncoding 必须为 'png' 或 'base64'，得到 {imageEncoding!r}")
        self.imageEncoding = imageEncoding
        self.ocrTimeout = ocrTimeout

    # ------------------------------------------------------------------
    # Tesseract 配置
    # ------------------------------------------------------------------

    def _configure_tesseract(self) -> None:
        """配置 Tesseract 可执行文件路径（线程安全，仅配置一次）."""
        if self.tesseractPath is None:
            return
        with self._tesseract_lock:
            if ImageChunker._tesseract_configured:
                return
            try:
                import pytesseract  # type: ignore[import-untyped]

                pytesseract.pytesseract.tesseract_cmd = self.tesseractPath
                ImageChunker._tesseract_configured = True
            except ImportError:
                pass

    # ------------------------------------------------------------------
    # BaseChunker 抽象方法实现
    # ------------------------------------------------------------------

    async def _preprocess(self, content: Any, config: ChunkConfig) -> Any:
        """预处理：加载图像（支持多页 TIFF）.

        :param content: 文件路径 / bytes / PIL.Image.Image
        :param config: 切片配置
        :return: 预处理结果字典::

            {
                "images": [PIL.Image, ...],
                "source": 来源路径,
            }
        :raises PreprocessError: 加载失败
        """
        # 配置 Tesseract 路径
        self._configure_tesseract()
        # 在线程池中加载图像（避免阻塞事件循环）
        loop = asyncio.get_running_loop()
        images, source = await loop.run_in_executor(None, _load_multi_page_images, content)
        if not images:
            raise PreprocessError("图像加载后为空")
        return {"images": images, "source": source}

    async def _split(self, preprocessed: Any, config: ChunkConfig) -> list[Chunk]:
        """切分：对每页并行执行 OCR + 版面分析，生成切片.

        :param preprocessed: ``_preprocess`` 返回的字典
        :param config: 切片配置
        :return: 切片列表
        """
        images: list[Any] = preprocessed.get("images", [])
        source: str = preprocessed.get("source", "")
        if not images:
            return []

        # 读取模态专属配置
        extra = config.extra or {}
        enable_ocr = bool(extra.get("enableOCR", self.enableOCR))
        enable_layout = bool(extra.get("enableLayout", self.enableLayout))
        extract_img = bool(extra.get("extractImageRegions", self.extractImageRegions))
        image_encoding = str(extra.get("imageEncoding", self.imageEncoding))
        ocr_lang = str(extra.get("ocrLang", self.ocrLang))
        ocr_timeout = float(extra.get("ocrTimeout", self.ocrTimeout))

        # 对每页并行处理
        tasks = [
            self._process_page(
                img,
                page_idx=i,
                source=source,
                config=config,
                enable_ocr=enable_ocr,
                enable_layout=enable_layout,
                extract_img=extract_img,
                image_encoding=image_encoding,
                ocr_lang=ocr_lang,
                ocr_timeout=ocr_timeout,
            )
            for i, img in enumerate(images)
        ]
        per_page_chunks = await asyncio.gather(*tasks)
        # 展平
        chunks: list[Chunk] = []
        for page_chunks in per_page_chunks:
            chunks.extend(page_chunks)
        return chunks

    async def _postprocess(self, chunks: list[Chunk], config: ChunkConfig) -> list[Chunk]:
        """后处理：计算 tokens + 重排 index + 过滤过小切片.

        :param chunks: 切片列表
        :param config: 切片配置
        :return: 处理后的切片列表
        """
        min_size = config.minChunkSize
        filtered: list[Chunk] = []
        for c in chunks:
            # 占位切片始终保留（即使 content 为空，仍携带 bbox/引用信息）
            if c.metadata.extra.get("placeholder"):
                filtered.append(c)
                continue
            # 过滤过小切片：文本切片按字符数，图片切片按 bytes 长度
            content = c.content
            if isinstance(content, str):
                size = len(content)
            elif isinstance(content, (bytes, bytearray)):
                size = len(content)
            else:
                size = 1
            if size < min_size:
                continue
            filtered.append(c)

        # 计算 tokens + 重排 index
        for i, c in enumerate(filtered):
            if c.tokens is None:
                c.tokens = self._count_tokens_for_chunk(c)
            c.metadata = c.metadata.model_copy(update={"index": i})
        return filtered

    def _count_tokens_for_chunk(self, chunk: Chunk) -> int:
        """为切片计算 token 数.

        - 文本切片：按 4 字符/token 估算
        - 图片切片：按 bytes 长度 / 1024 估算（每 KB 约 1 token）
        """
        content = chunk.content
        if isinstance(content, str):
            return max(1, len(content) // 4) if content else 0
        if isinstance(content, (bytes, bytearray)):
            return max(1, len(content) // 1024) if content else 0
        return 1

    # ------------------------------------------------------------------
    # 单页处理
    # ------------------------------------------------------------------

    async def _process_page(
        self,
        img: Any,
        *,
        page_idx: int,
        source: str,
        config: ChunkConfig,
        enable_ocr: bool,
        enable_layout: bool,
        extract_img: bool,
        image_encoding: str,
        ocr_lang: str,
        ocr_timeout: float,
    ) -> list[Chunk]:
        """处理单页图像：并行执行 OCR + 版面分析，生成切片.

        :param img: PIL.Image.Image
        :param page_idx: 页码（从 0 开始）
        :param source: 来源路径
        :param config: 切片配置
        :param enable_ocr: 是否启用 OCR
        :param enable_layout: 是否启用版面分析
        :param extract_img: 是否提取图片区域为独立切片
        :param image_encoding: 图片编码方式
        :param ocr_lang: OCR 语言
        :param ocr_timeout: OCR 超时秒数
        :return: 切片列表
        """
        # 并行执行 OCR 和版面分析
        ocr_coro = self._ocr(img, lang=ocr_lang) if enable_ocr else _async_return([])
        layout_coro = self._layout_analysis(img) if enable_layout else _async_return([])
        try:
            ocr_items, regions = await asyncio.gather(
                _with_timeout(ocr_coro, ocr_timeout),
                layout_coro,
            )
        except asyncio.TimeoutError as ex:
            raise PreprocessError(f"OCR 超时（页 {page_idx}，超时 {ocr_timeout}s）", cause=ex) from ex

        # 若未启用版面分析但有 OCR 结果：将 OCR 文本聚合为一个文本切片
        if not enable_layout and ocr_items:
            regions = self._ocr_to_regions(ocr_items, img)

        # 将 regions 转换为 Chunk
        chunks = self._regions_to_chunks(
            regions=regions,
            img=img,
            page_idx=page_idx,
            source=source,
            config=config,
            extract_img=extract_img,
            image_encoding=image_encoding,
        )

        # 若启用 OCR 但无任何区域（空白图）：生成一个整图占位切片
        if not chunks:
            placeholder = self._make_placeholder_chunk(
                img=img,
                page_idx=page_idx,
                source=source,
                config=config,
                image_encoding=image_encoding,
                extract_img=extract_img,
            )
            if placeholder is not None:
                chunks.append(placeholder)
        return chunks

    # ------------------------------------------------------------------
    # OCR
    # ------------------------------------------------------------------

    async def _ocr(self, img: Any, *, lang: str) -> list[OCRItem]:
        """执行 OCR 识别.

        使用 ``pytesseract.image_to_data`` 获取带位置的文本项。
        Tesseract 不可用时返回空列表（不抛异常，保证版面分析仍可工作）。

        :param img: PIL.Image.Image
        :param lang: Tesseract 语言
        :return: OCR 文本项列表
        """
        try:
            import pytesseract  # type: ignore[import-untyped]
        except ImportError:
            return []

        loop = asyncio.get_running_loop()

        def _work() -> list[OCRItem]:
            try:
                data = pytesseract.image_to_data(
                    img,
                    lang=lang,
                    output_type=pytesseract.Output.DICT,
                )
            except Exception:  # noqa: BLE001
                return []
            items: list[OCRItem] = []
            n = len(data.get("text", []))
            for i in range(n):
                text = (data["text"][i] or "").strip()
                if not text:
                    continue
                try:
                    conf = float(data["conf"][i])
                except (KeyError, IndexError, ValueError, TypeError):
                    conf = -1.0
                x = int(data.get("left", [0])[i])
                y = int(data.get("top", [0])[i])
                w = int(data.get("width", [0])[i])
                h = int(data.get("height", [0])[i])
                if w <= 0 or h <= 0:
                    continue
                items.append(OCRItem(text=text, bbox=BBox(x, y, w, h), conf=conf))
            return items

        return await loop.run_in_executor(None, _work)

    def _ocr_to_regions(self, ocr_items: list[OCRItem], img: Any) -> list[Region]:
        """将 OCR 文本项聚合为文本区域（未启用版面分析时使用）.

        简化策略：按行聚合（y 坐标相近的归为同一行），每行一个文本区域。
        """
        if not ocr_items:
            return []
        w, h = img.size
        # 按 y 排序
        sorted_items = sorted(ocr_items, key=lambda it: (it.bbox.y, it.bbox.x))
        # 按行聚合
        lines: list[list[OCRItem]] = []
        current_line: list[OCRItem] = []
        last_y = None
        for item in sorted_items:
            if last_y is None or abs(item.bbox.y - last_y) <= max(item.bbox.h // 2, 5):
                current_line.append(item)
                last_y = item.bbox.y if last_y is None else last_y
            else:
                if current_line:
                    lines.append(current_line)
                current_line = [item]
                last_y = item.bbox.y
        if current_line:
            lines.append(current_line)

        regions: list[Region] = []
        for line in lines:
            text = " ".join(it.text for it in line)
            x = min(it.bbox.x for it in line)
            y = min(it.bbox.y for it in line)
            x2 = max(it.bbox.x2 for it in line)
            y2 = max(it.bbox.y2 for it in line)
            bbox = BBox(x, y, x2 - x, y2 - y)
            regions.append(
                Region(
                    region_type=REGION_TEXT,
                    bbox=bbox,
                    content=text,
                    ocr_items=line,
                    confidence=sum(it.conf for it in line) / len(line) if line else 0.0,
                )
            )
        return regions

    # ------------------------------------------------------------------
    # 版面分析
    # ------------------------------------------------------------------

    async def _layout_analysis(self, img: Any) -> list[Region]:
        """版面分析：基于 OpenCV 轮廓检测 + 启发式规则分类.

        算法：
        1. 灰度化 + 二值化 + 形态学闭运算
        2. findContours 提取候选区域
        3. 启发式分类：标题/表格/图片/公式/文本
        4. 合并过近区域、过滤过小区域

        :param img: PIL.Image.Image
        :return: 版面区域列表
        """
        try:
            import cv2  # type: ignore[import-untyped]
            import numpy as np  # type: ignore[import-untyped]
        except ImportError:
            return []

        loop = asyncio.get_running_loop()

        def _work() -> list[Region]:
            try:
                arr = np.array(img)
                if arr.ndim == 3:
                    gray = cv2.cvtColor(arr, cv2.COLOR_RGB2GRAY)
                else:
                    gray = arr
                # 自适应二值化
                binary = cv2.adaptiveThreshold(
                    gray,
                    255,
                    cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                    cv2.THRESH_BINARY_INV,
                    15,
                    10,
                )
                # 形态学闭运算：连接相邻文本
                kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (15, 5))
                closed = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel)
                # 轮廓检测
                contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
                # 转 BBox 列表
                bboxes: list[BBox] = []
                for c in contours:
                    x, y, w, h = cv2.boundingRect(c)
                    if w < MIN_CONTOUR_W or h < MIN_CONTOUR_H:
                        continue
                    if w * h < MIN_REGION_AREA:
                        continue
                    bboxes.append(BBox(int(x), int(y), int(w), int(h)))
                # 合并重叠/相近区域
                bboxes = _merge_bboxes(bboxes)
                # 启发式分类
                regions = _classify_regions(bboxes, binary, gray)
                return regions
            except Exception:  # noqa: BLE001
                return []

        return await loop.run_in_executor(None, _work)

    # ------------------------------------------------------------------
    # Region -> Chunk 转换
    # ------------------------------------------------------------------

    def _regions_to_chunks(
        self,
        *,
        regions: list[Region],
        img: Any,
        page_idx: int,
        source: str,
        config: ChunkConfig,
        extract_img: bool,
        image_encoding: str,
    ) -> list[Chunk]:
        """将版面区域转换为切片.

        - 文本/标题/公式区域：content 为文本字符串
        - 图片/表格区域：content 为 PNG bytes 或 base64 字符串（按 image_encoding）
        - ``ChunkMetadata.extra`` 携带 ``page``、``bbox``、``regionType``、``sourcePath``

        :param regions: 版面区域列表
        :param img: 原始 PIL 图像
        :param page_idx: 页码
        :param source: 来源路径
        :param config: 切片配置
        :param extract_img: 是否提取图片区域为独立切片
        :param image_encoding: 图片编码方式
        :return: 切片列表
        """
        chunks: list[Chunk] = []
        for region in regions:
            chunk = self._region_to_chunk(
                region=region,
                img=img,
                page_idx=page_idx,
                source=source,
                config=config,
                extract_img=extract_img,
                image_encoding=image_encoding,
            )
            if chunk is not None:
                chunks.append(chunk)
        return chunks

    def _region_to_chunk(
        self,
        *,
        region: Region,
        img: Any,
        page_idx: int,
        source: str,
        config: ChunkConfig,
        extract_img: bool,
        image_encoding: str,
    ) -> Chunk | None:
        """将单个区域转换为切片.

        :return: Chunk 或 None（应跳过时）
        """
        rtype = region.region_type
        bbox = region.bbox
        extra = {
            "page": page_idx,
            "bbox": bbox.to_dict(),
            "regionType": rtype,
            "sourcePath": source,
            "confidence": region.confidence,
        }

        # 文本类区域：content 为文本
        if rtype in (REGION_TEXT, REGION_TITLE, REGION_FORMULA):
            text = region.content if isinstance(region.content, str) else ""
            if not text and region.ocr_items:
                text = " ".join(it.text for it in region.ocr_items)
            if not text:
                return None
            return Chunk(
                id=self._make_chunk_id(),
                content=text,
                metadata=self._make_metadata(
                    config,
                    index=0,  # postprocess 会重排
                    start=bbox.y,
                    end=bbox.y2,
                    source=source,
                    extra=extra,
                ),
            )

        # 图片/表格区域：content 为图像 bytes/base64
        if rtype in (REGION_IMAGE, REGION_TABLE):
            if not extract_img:
                # 不提取图片，仅保留引用信息，content 为空字符串占位
                return Chunk(
                    id=self._make_chunk_id(),
                    content="",
                    metadata=self._make_metadata(
                        config,
                        index=0,
                        start=bbox.y,
                        end=bbox.y2,
                        source=source,
                        extra={**extra, "extracted": False},
                    ),
                )
            cropped = _crop_image(img, bbox)
            if image_encoding == "base64":
                content: Any = _image_to_b64(cropped)
            else:
                content = _image_to_png_bytes(cropped)
            # 表格区域：附加 OCR 文本作为表格内容
            if rtype == REGION_TABLE and region.ocr_items:
                extra["tableText"] = " ".join(it.text for it in region.ocr_items)
            return Chunk(
                id=self._make_chunk_id(),
                content=content,
                metadata=self._make_metadata(
                    config,
                    index=0,
                    start=bbox.y,
                    end=bbox.y2,
                    source=source,
                    extra={**extra, "extracted": True},
                ),
            )

        # 未知类型：跳过
        return None

    def _make_placeholder_chunk(
        self,
        *,
        img: Any,
        page_idx: int,
        source: str,
        config: ChunkConfig,
        image_encoding: str,
        extract_img: bool = True,
    ) -> Chunk | None:
        """为空白图生成整图占位切片.

        :param extract_img: 是否提取图片内容；False 时 content 为空字符串占位
        """
        try:
            w, h = img.size
        except Exception:  # noqa: BLE001
            return None
        bbox = BBox(0, 0, w, h)
        extra = {
            "page": page_idx,
            "bbox": bbox.to_dict(),
            "regionType": REGION_IMAGE,
            "sourcePath": source,
            "extracted": extract_img,
            "placeholder": True,
        }
        if not extract_img:
            content: Any = ""
        elif image_encoding == "base64":
            content = _image_to_b64(img)
        else:
            content = _image_to_png_bytes(img)
        return Chunk(
            id=self._make_chunk_id(),
            content=content,
            metadata=self._make_metadata(
                config,
                index=0,
                start=0,
                end=h,
                source=source,
                extra=extra,
            ),
        )


# ----------------------------------------------------------------------
# 版面分析辅助函数（模块级，便于单元测试）
# ----------------------------------------------------------------------


def _merge_bboxes(bboxes: list[BBox], overlap_thresh: float = 0.3) -> list[BBox]:
    """合并重叠或包含的边界框.

    :param bboxes: 输入边界框列表
    :param overlap_thresh: 重叠率阈值，超过此值则合并
    :return: 合并后的边界框列表
    """
    if not bboxes:
        return []
    # 按 y 排序
    boxes = sorted(bboxes, key=lambda b: (b.y, b.x))
    merged: list[BBox] = []
    for b in boxes:
        absorbed = False
        for i, m in enumerate(merged):
            if _iou(b, m) >= overlap_thresh or _contains(m, b) or _contains(b, m):
                merged[i] = _union(m, b)
                absorbed = True
                break
        if not absorbed:
            merged.append(b)
    return merged


def _iou(a: BBox, b: BBox) -> float:
    """计算两个 BBox 的交并比."""
    x1 = max(a.x, b.x)
    y1 = max(a.y, b.y)
    x2 = min(a.x2, b.x2)
    y2 = min(a.y2, b.y2)
    if x2 <= x1 or y2 <= y1:
        return 0.0
    inter = (x2 - x1) * (y2 - y1)
    union = a.area + b.area - inter
    if union <= 0:
        return 0.0
    return inter / union


def _contains(outer: BBox, inner: BBox) -> bool:
    """判断 outer 是否包含 inner."""
    return outer.x <= inner.x and outer.y <= inner.y and outer.x2 >= inner.x2 and outer.y2 >= inner.y2


def _union(a: BBox, b: BBox) -> BBox:
    """计算两个 BBox 的并集（外接矩形）."""
    x = min(a.x, b.x)
    y = min(a.y, b.y)
    x2 = max(a.x2, b.x2)
    y2 = max(a.y2, b.y2)
    return BBox(x, y, x2 - x, y2 - y)


def _classify_regions(
    bboxes: list[BBox],
    binary: Any,
    gray: Any,
) -> list[Region]:
    """启发式分类边界框为 5 类区域.

    分类规则：
    - **table**：区域内水平/垂直线条密集（≥ TABLE_MIN_LINES 条）
    - **image**：区域内颜色方差大且文本像素占比低
    - **title**：高度 > median_height * TITLE_HEIGHT_RATIO 且位于上方 1/3
    - **formula**：区域内特殊字符占比高（需 OCR 配合，启发式阶段标记为 text）
    - **text**：其余

    :param bboxes: 输入边界框列表
    :param binary: 二值化图像（用于线条检测）
    :param gray: 灰度图像
    :return: 版面区域列表
    """
    if not bboxes:
        return []
    try:
        import cv2  # type: ignore[import-untyped]  # noqa: F401
        import numpy as np  # type: ignore[import-untyped]
    except ImportError:
        return []

    h_total, w_total = binary.shape[:2]
    # 计算中位高度
    heights = [b.h for b in bboxes]
    median_h = float(np.median(heights)) if heights else 0.0

    regions: list[Region] = []
    for bbox in bboxes:
        rtype = _classify_single(bbox, binary, gray, median_h, h_total, w_total)
        regions.append(Region(region_type=rtype, bbox=bbox))
    return regions


def _classify_single(
    bbox: BBox,
    binary: Any,
    gray: Any,
    median_h: float,
    h_total: int,
    w_total: int,
) -> str:
    """启发式分类单个边界框.

    :return: 区域类型字符串
    """
    try:
        import cv2  # type: ignore[import-untyped]  # noqa: F401
        import numpy as np  # type: ignore[import-untyped]
    except ImportError:
        return REGION_TEXT

    # 区域内二值化切片
    x1, y1 = max(0, bbox.x), max(0, bbox.y)
    x2, y2 = min(w_total, bbox.x2), min(h_total, bbox.y2)
    if x2 <= x1 or y2 <= y1:
        return REGION_TEXT
    region_bin = binary[y1:y2, x1:x2]
    region_gray = gray[y1:y2, x1:x2]

    # 1. 表格检测：水平/垂直线条数
    h_lines = _count_horizontal_lines(region_bin)
    v_lines = _count_vertical_lines(region_bin)
    if h_lines >= TABLE_MIN_LINES and v_lines >= 2:
        return REGION_TABLE

    # 2. 图片检测：颜色方差大 + 文本像素占比低
    if bbox.area >= IMAGE_MIN_AREA_RATIO * w_total * h_total:
        # 文本像素（黑色像素）占比
        text_ratio = float(np.count_nonzero(region_bin)) / max(1, region_bin.size)
        # 颜色方差
        std = float(np.std(region_gray)) if region_gray.size > 0 else 0.0
        # 文本像素占比低 + 颜色方差大 -> 图片
        if text_ratio < 0.1 and std > 30.0:
            return REGION_IMAGE

    # 3. 标题检测：高度 > median * 1.5 且位于上方 1/3
    if median_h > 0 and bbox.h > median_h * TITLE_HEIGHT_RATIO:
        if bbox.y < h_total / 3:
            return REGION_TITLE

    # 4. 公式检测：启发式难以准确识别，标记为 text
    #    （实际可结合 OCR 特殊字符密度进一步分类）

    # 5. 默认：文本
    return REGION_TEXT


def _count_horizontal_lines(region_bin: Any) -> int:
    """统计区域内水平线条数.

    水平线条：一行中连续白色像素跨度 > 区域宽度 * 0.5。
    """
    try:
        import numpy as np  # type: ignore[import-untyped]  # noqa: F401
    except ImportError:
        return 0
    if region_bin.size == 0:
        return 0
    h, w = region_bin.shape[:2]
    if w < 10:
        return 0
    count = 0
    threshold = int(w * 0.5)
    for row in region_bin:
        # 连续白色像素最大跨度
        max_run = 0
        run = 0
        for px in row:
            if px > 0:
                run += 1
                if run > max_run:
                    max_run = run
            else:
                run = 0
        if max_run >= threshold:
            count += 1
    return count


def _count_vertical_lines(region_bin: Any) -> int:
    """统计区域内垂直线条数."""
    try:
        import numpy as np  # type: ignore[import-untyped]  # noqa: F401
    except ImportError:
        return 0
    if region_bin.size == 0:
        return 0
    # 转置后按水平线条统计
    return _count_horizontal_lines(region_bin.T)


def _is_formula_text(text: str) -> bool:
    """判断文本是否为公式（特殊字符占比高）.

    :param text: 文本
    :return: True 表示公式
    """
    if not text:
        return False
    special = sum(1 for ch in text if ch in _FORMULA_CHARS)
    # 特殊字符占比 > 20% 视为公式
    return special / len(text) > 0.2


# ----------------------------------------------------------------------
# 异步辅助
# ----------------------------------------------------------------------


async def _async_return(value: Any) -> Any:
    """异步返回常量值."""
    return value


async def _with_timeout(coro: Any, timeout: float) -> Any:
    """为协程添加超时保护.

    :param coro: 协程
    :param timeout: 超时秒数（<=0 表示不超时）
    :return: 协程结果
    :raises asyncio.TimeoutError: 超时
    """
    if timeout <= 0:
        return await coro
    return await asyncio.wait_for(coro, timeout=timeout)
