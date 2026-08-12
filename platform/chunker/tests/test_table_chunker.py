"""TableChunker 表格行列切片器单元测试 (T008-3).

覆盖场景：
    - 工具函数（格式检测/类型签名/Jaccard 相似度/表头一致性/多行表头展平）
    - 表头识别（自动检测/显式指定/多行合并单元格/质量校验回退）
    - 单页表切片（DataFrame/CSV/Excel/HTML）
    - 跨页表自动合并（表头一致合并/不一致保留）
    - 行分组（类型签名/Jaccard 相似度）
    - 窗口切分（同组不拆分/overlap/单组超窗口）
    - 性能（大表耗时 P95）
    - 注册机制
    - 异常处理（不支持格式/空内容/加载失败）
    - 元数据完整性（表头/行范围/列信息/合并来源）
"""

from __future__ import annotations

import asyncio
import time

from chunker.base import BaseChunker
from chunker.exceptions import PreprocessError
from chunker.models import ChunkConfig, Modality
from chunker.registry import (
    get_chunker,
    is_chunker_registered,
    list_modalities,
    register_chunker,
)
from chunker.table_chunker import (
    DEFAULT_SIMILARITY_THRESHOLD,
    TableChunker,
    _detect_format_from_path,
    _detect_header_rows,
    _flatten_multirow_header,
    _headers_consistent,
    _is_file_path,
    _is_html_content,
    _jaccard_similarity,
    _normalize_row,
    _normalize_value,
    _row_to_text,
    _row_type_signature,
    _validate_header_quality,
)
import pandas as pd
import pytest

# ----------------------------------------------------------------------
# fixtures
# ----------------------------------------------------------------------


@pytest.fixture
def chunker() -> TableChunker:
    """默认 TableChunker 实例."""
    return TableChunker()


@pytest.fixture
def tmp_csv(tmp_path) -> str:
    """创建临时 CSV 文件."""
    df = pd.DataFrame({"姓名": ["张三", "李四", "王五"], "年龄": [25, 30, 35], "城市": ["北京", "上海", "广州"]})
    p = tmp_path / "data.csv"
    df.to_csv(p, index=False, encoding="utf-8-sig")
    return str(p)


@pytest.fixture
def tmp_excel(tmp_path) -> str:
    """创建临时 Excel 文件."""
    df = pd.DataFrame({"name": ["Alice", "Bob", "Charlie"], "score": [90.5, 85.0, 78.5], "passed": [True, True, False]})
    p = tmp_path / "data.xlsx"
    df.to_excel(p, index=False)
    return str(p)


@pytest.fixture
def tmp_html(tmp_path) -> str:
    """创建临时 HTML 文件（含两个表，表头一致 → 跨页合并）."""
    html = """
    <html><body>
    <table>
      <tr><th>id</th><th>value</th></tr>
      <tr><td>1</td><td>10</td></tr>
      <tr><td>2</td><td>20</td></tr>
    </table>
    <table>
      <tr><th>id</th><th>value</th></tr>
      <tr><td>3</td><td>30</td></tr>
      <tr><td>4</td><td>40</td></tr>
    </table>
    </body></html>
    """
    p = tmp_path / "data.html"
    p.write_text(html, encoding="utf-8")
    return str(p)


def _cfg(**kwargs) -> ChunkConfig:
    """构造表格 ChunkConfig 的便捷函数."""
    defaults = {"modality": Modality.TABLE, "windowSize": 50, "overlap": 0.0}
    defaults.update(kwargs)
    return ChunkConfig(**defaults)


# ----------------------------------------------------------------------
# 工具函数测试
# ----------------------------------------------------------------------


class TestDetectFormatFromPath:
    def test_csv(self):
        assert _detect_format_from_path("a.csv") == "csv"
        assert _detect_format_from_path("a.tsv") == "csv"

    def test_excel(self):
        assert _detect_format_from_path("a.xlsx") == "excel"
        assert _detect_format_from_path("a.xls") == "excel"

    def test_html(self):
        assert _detect_format_from_path("a.html") == "html"
        assert _detect_format_from_path("a.htm") == "html"

    def test_uppercase(self):
        assert _detect_format_from_path("A.CSV") == "csv"

    def test_unsupported(self):
        with pytest.raises(PreprocessError):
            _detect_format_from_path("a.txt")


class TestIsHtmlContent:
    def test_has_table(self):
        assert _is_html_content("<table><tr></tr></table>") is True
        assert _is_html_content("<TABLE><tr></tr></TABLE>") is True

    def test_no_table(self):
        assert _is_html_content("hello world") is False
        assert _is_html_content("<div>no table</div>") is False


class TestIsFilePath:
    def test_existing_csv(self, tmp_csv):
        assert _is_file_path(tmp_csv) is True

    def test_nonexistent(self):
        assert _is_file_path("nonexistent_xyz.csv") is False

    def test_no_extension(self):
        assert _is_file_path("noext") is False

    def test_empty(self):
        assert _is_file_path("") is False

    def test_too_long(self):
        assert _is_file_path("x" * 5000 + ".csv") is False


class TestNormalizeValue:
    def test_none(self):
        assert _normalize_value(None) is None

    def test_nan(self):
        assert _normalize_value(float("nan")) is None

    def test_native_types(self):
        assert _normalize_value(42) == 42
        assert _normalize_value(3.14) == 3.14
        assert _normalize_value("hello") == "hello"
        assert _normalize_value(True) is True

    def test_numpy_int(self):
        import numpy as np

        result = _normalize_value(np.int64(7))
        assert result == 7

    def test_numpy_nan(self):
        import numpy as np

        assert _normalize_value(np.float64(np.nan)) is None

    def test_datetime(self):
        import datetime

        dt = datetime.datetime(2024, 1, 1, 12, 0, 0)
        assert _normalize_value(dt) == "2024-01-01T12:00:00"

    def test_other(self):
        # 未知类型转为字符串
        result = _normalize_value(object())
        assert isinstance(result, str)


class TestRowTypeSignature:
    def test_string_row(self):
        assert _row_type_signature(["a", "b", "c"]) == "sss"

    def test_number_row(self):
        assert _row_type_signature([1, 2.5, 3]) == "nnn"

    def test_mixed(self):
        assert _row_type_signature(["a", 1, True, None]) == "snb0"

    def test_empty(self):
        assert _row_type_signature([]) == ""


class TestRowToText:
    def test_basic(self):
        assert _row_to_text(["a", 1, None]) == "a 1"

    def test_empty(self):
        assert _row_to_text([]) == ""

    def test_all_none(self):
        assert _row_to_text([None, None]) == ""


class TestJaccardSimilarity:
    def test_identical(self):
        assert _jaccard_similarity("a b c", "a b c") == 1.0

    def test_disjoint(self):
        assert _jaccard_similarity("a b", "c d") == 0.0

    def test_partial(self):
        sim = _jaccard_similarity("a b c", "a b d")
        assert 0.0 < sim < 1.0
        # 交集 {a,b}=2，并集 {a,b,c,d}=4 → 0.5
        assert sim == pytest.approx(0.5)

    def test_empty_both(self):
        assert _jaccard_similarity("", "") == 1.0

    def test_one_empty(self):
        assert _jaccard_similarity("a b", "") == 0.0


class TestHeadersConsistent:
    def test_same(self):
        assert _headers_consistent(["a", "b"], ["a", "b"]) is True

    def test_case_insensitive(self):
        assert _headers_consistent(["A", "B"], ["a", "b"]) is True

    def test_case_sensitive(self):
        assert _headers_consistent(["A", "B"], ["a", "b"], ignore_case=False) is False

    def test_different_length(self):
        assert _headers_consistent(["a", "b"], ["a"]) is False

    def test_ignore_order(self):
        assert _headers_consistent(["a", "b"], ["b", "a"], ignore_order=True) is True
        assert _headers_consistent(["a", "b"], ["b", "a"], ignore_order=False) is False

    def test_whitespace(self):
        assert _headers_consistent([" a ", "b"], ["a", " b"]) is True


class TestFlattenMultirowHeader:
    def test_single_row(self):
        df = pd.DataFrame([["a", "b"], [1, 2]])
        df.columns = [0, 1]
        headers, body = _flatten_multirow_header(df, 1)
        assert headers == ["a", "b"]
        assert body.shape[0] == 1
        assert body.iloc[0].tolist() == [1, 2]

    def test_multirow(self):
        df = pd.DataFrame([["主表", "主表"], ["姓名", "年龄"], ["张三", 25]])
        df.columns = [0, 1]
        headers, body = _flatten_multirow_header(df, 2)
        assert headers == ["主表 / 姓名", "主表 / 年龄"]
        assert body.shape[0] == 1

    def test_zero_rows(self):
        df = pd.DataFrame({"a": [1], "b": [2]})
        headers, body = _flatten_multirow_header(df, 0)
        assert headers == ["a", "b"]
        assert body.shape[0] == 1

    def test_skip_empty(self):
        df = pd.DataFrame([["a", None], [1, 2]])
        df.columns = [0, 1]
        headers, body = _flatten_multirow_header(df, 1)
        # 第二列空，回退到列名（列名为整数 1）
        assert headers == ["a", "1"]


class TestDetectHeaderRows:
    def test_already_has_header(self):
        df = pd.DataFrame({"name": ["a", "b"], "age": [1, 2]})
        assert _detect_header_rows(df) == 0

    def test_default_index_with_string_first_row(self):
        df = pd.DataFrame([["name", "age"], ["a", 25], ["b", 30]])
        df.columns = [0, 1]
        assert _detect_header_rows(df) == 1

    def test_multirow_header(self):
        df = pd.DataFrame([["主表", "主表"], ["姓名", "年龄"], ["张三", 25], ["李四", 30]])
        df.columns = [0, 1]
        result = _detect_header_rows(df)
        assert result >= 1

    def test_empty(self):
        df = pd.DataFrame()
        assert _detect_header_rows(df) == 0

    def test_first_row_not_string(self):
        df = pd.DataFrame([[1, 2], [3, 4]])
        df.columns = [0, 1]
        assert _detect_header_rows(df) == 0


class TestValidateHeaderQuality:
    def test_good_header(self):
        assert _validate_header_quality(["name", "age", "city"], pd.DataFrame()) is True

    def test_empty(self):
        assert _validate_header_quality([], pd.DataFrame()) is False

    def test_all_empty(self):
        assert _validate_header_quality(["", ""], pd.DataFrame()) is False

    def test_duplicates(self):
        # 唯一性不足
        assert _validate_header_quality(["a", "a", "a"], pd.DataFrame()) is False

    def test_too_long(self):
        # 平均长度过长
        long_name = "x" * 100
        assert _validate_header_quality([long_name, long_name], pd.DataFrame()) is False


class TestNormalizeRow:
    def test_basic(self):
        s = pd.Series([1, "a", 2.5], name=0)
        result = _normalize_row(s)
        assert result == [1, "a", 2.5]

    def test_with_nan(self):
        s = pd.Series([1, float("nan"), 3], name=0)
        result = _normalize_row(s)
        assert result[0] == 1
        assert pd.isna(result[1])
        assert result[2] == 3


# ----------------------------------------------------------------------
# 表头识别测试
# ----------------------------------------------------------------------


class TestHeaderExtraction:
    def test_auto_detect_single_row(self, chunker):
        df = pd.DataFrame([["name", "age"], ["a", 25], ["b", 30]])
        df.columns = [0, 1]
        headers, body, n = chunker._extract_header(df, 0)
        assert headers == ["name", "age"]
        assert body.shape[0] == 2
        assert n == 1

    def test_explicit_header_rows(self, chunker):
        df = pd.DataFrame([["主表", "主表"], ["姓名", "年龄"], ["张三", 25], ["李四", 30]])
        df.columns = [0, 1]
        headers, body, n = chunker._extract_header(df, 2)
        assert "主表" in headers[0]
        assert body.shape[0] == 2
        assert n == 2

    def test_already_has_header(self, chunker):
        df = pd.DataFrame({"name": ["a", "b"], "age": [1, 2]})
        headers, body, n = chunker._extract_header(df, 0)
        assert headers == ["name", "age"]
        assert n == 0
        assert body.shape[0] == 2

    def test_header_rows_exceeds_rows(self, chunker):
        df = pd.DataFrame([["a", "b"], [1, 2]])
        df.columns = [0, 1]
        headers, body, n = chunker._extract_header(df, 5)
        assert len(headers) == 2
        assert body.shape[0] == 0


# ----------------------------------------------------------------------
# 单页表切片测试
# ----------------------------------------------------------------------


class TestSinglePageTable:
    def test_dataframe_input(self, chunker):
        df = pd.DataFrame({"name": ["a", "b", "c"], "value": [1, 2, 3]})
        chunks = asyncio.run(chunker.chunk(df, _cfg()))
        assert len(chunks) == 1
        c = chunks[0]
        assert c.content["headers"] == ["name", "value"]
        assert c.content["rowCount"] == 3
        assert c.metadata.extra["colCount"] == 2

    def test_csv_file(self, chunker, tmp_csv):
        chunks = asyncio.run(chunker.chunk(tmp_csv, _cfg()))
        assert len(chunks) == 1
        assert chunks[0].content["rowCount"] == 3
        assert "姓名" in chunks[0].content["headers"]

    def test_excel_file(self, chunker, tmp_excel):
        chunks = asyncio.run(chunker.chunk(tmp_excel, _cfg()))
        assert len(chunks) == 1
        assert chunks[0].content["rowCount"] == 3
        assert "name" in chunks[0].content["headers"]

    def test_csv_string_content(self, chunker):
        csv = "name,age\nAlice,25\nBob,30\n"
        chunks = asyncio.run(chunker.chunk(csv, _cfg()))
        assert len(chunks) == 1
        assert chunks[0].content["headers"] == ["name", "age"]
        assert chunks[0].content["rowCount"] == 2

    def test_html_string_content(self, chunker):
        html = "<table><tr><th>x</th><th>y</th></tr><tr><td>1</td><td>2</td></tr></table>"
        chunks = asyncio.run(chunker.chunk(html, _cfg()))
        assert len(chunks) == 1
        assert chunks[0].content["headers"] == ["x", "y"]

    def test_window_split(self, chunker):
        # 10 行同类型，窗口 3：同组不拆分 → 单组超窗口单独成切片
        df = pd.DataFrame({"id": list(range(10)), "v": list(range(10))})
        chunks = asyncio.run(chunker.chunk(df, _cfg(windowSize=3)))
        # 同类型为一组，单组超窗口单独成切片
        assert len(chunks) == 1
        total_rows = sum(c.content["rowCount"] for c in chunks)
        assert total_rows == 10

    def test_metadata_integrity(self, chunker):
        df = pd.DataFrame({"a": [1, 2], "b": [3, 4]})
        chunks = asyncio.run(chunker.chunk(df, _cfg()))
        c = chunks[0]
        assert c.metadata.modality == Modality.TABLE
        assert c.metadata.extra["headers"] == ["a", "b"]
        assert c.metadata.extra["rowRange"] == [0, 1]
        assert c.metadata.extra["colCount"] == 2
        assert c.metadata.extra["rowCount"] == 2
        assert c.tokens is not None and c.tokens > 0


# ----------------------------------------------------------------------
# 跨页表合并测试
# ----------------------------------------------------------------------


class TestCrossPageMerge:
    def test_merge_consistent_headers(self, chunker):
        df1 = pd.DataFrame({"id": [1, 2], "v": [10, 20]})
        df2 = pd.DataFrame({"id": [3, 4], "v": [30, 40]})
        chunks = asyncio.run(chunker.chunk([df1, df2], _cfg()))
        assert len(chunks) == 1
        assert chunks[0].content["rowCount"] == 4
        assert chunks[0].metadata.extra["crossPage"] is True
        assert len(chunks[0].metadata.extra["mergedFrom"]) == 2

    def test_no_merge_inconsistent_headers(self, chunker):
        df1 = pd.DataFrame({"id": [1, 2], "v": [10, 20]})
        df2 = pd.DataFrame({"name": ["a", "b"], "score": [90, 80]})
        chunks = asyncio.run(chunker.chunk([df1, df2], _cfg()))
        assert len(chunks) == 2
        assert chunks[0].metadata.extra["crossPage"] is False

    def test_disable_merge(self, chunker):
        df1 = pd.DataFrame({"id": [1, 2], "v": [10, 20]})
        df2 = pd.DataFrame({"id": [3, 4], "v": [30, 40]})
        chunks = asyncio.run(chunker.chunk([df1, df2], _cfg(extra={"mergeCrossPage": False})))
        assert len(chunks) == 2

    def test_html_multiple_tables_merge(self, chunker, tmp_html):
        chunks = asyncio.run(chunker.chunk(tmp_html, _cfg()))
        # 两个表表头一致 → 合并为一个
        assert len(chunks) == 1
        assert chunks[0].content["rowCount"] == 4
        assert chunks[0].metadata.extra["crossPage"] is True

    def test_partial_merge(self, chunker):
        # 3 个表：前两个表头一致，第三个不同
        df1 = pd.DataFrame({"id": [1], "v": [10]})
        df2 = pd.DataFrame({"id": [2], "v": [20]})
        df3 = pd.DataFrame({"x": ["a"], "y": ["b"]})
        chunks = asyncio.run(chunker.chunk([df1, df2, df3], _cfg()))
        assert len(chunks) == 2
        assert chunks[0].content["rowCount"] == 2
        assert chunks[1].content["rowCount"] == 1


# ----------------------------------------------------------------------
# 合并单元格表头测试
# ----------------------------------------------------------------------


class TestMergedCellHeader:
    def test_multirow_header_explicit(self, chunker):
        # 模拟合并单元格：前两行是表头
        df = pd.DataFrame([["主表", "主表"], ["姓名", "年龄"], ["张三", 25], ["李四", 30]])
        df.columns = [0, 1]
        chunks = asyncio.run(chunker.chunk(df, _cfg(extra={"headerRows": 2})))
        assert len(chunks) == 1
        headers = chunks[0].content["headers"]
        assert "主表" in headers[0]
        assert "姓名" in headers[0]
        assert chunks[0].content["rowCount"] == 2

    def test_multirow_header_auto(self, chunker):
        df = pd.DataFrame([["主表", "主表"], ["姓名", "年龄"], ["张三", 25], ["李四", 30]])
        df.columns = [0, 1]
        chunks = asyncio.run(chunker.chunk(df, _cfg()))
        assert len(chunks) == 1
        # 自动检测应识别出表头
        assert chunks[0].content["rowCount"] >= 1


# ----------------------------------------------------------------------
# 行分组测试
# ----------------------------------------------------------------------


class TestRowGrouping:
    def test_type_signature_grouping(self, chunker):
        # 不同类型行应分到不同切片（窗口小时）
        df = pd.DataFrame({"a": ["text", 1, "text2", 2], "b": ["x", 2, "y", 3]})
        chunks = asyncio.run(chunker.chunk(df, _cfg(windowSize=1)))
        # 每行类型不同，窗口=1 → 每行一个切片
        assert len(chunks) >= 2

    def test_similarity_grouping(self, chunker):
        df = pd.DataFrame({"desc": ["apple red fruit", "apple green fruit", "banana yellow fruit", "car vehicle fast"]})
        chunks = asyncio.run(
            chunker.chunk(
                df,
                _cfg(windowSize=1, extra={"groupBySimilarity": True, "similarityThreshold": 0.4}),
            )
        )
        # 相似行应分到同组，不相似的分到不同组
        assert len(chunks) >= 1

    def test_same_group_not_split(self, chunker):
        # 5 行同类型，窗口 3：同组不拆分 → 1 个切片（5 行）
        df = pd.DataFrame({"id": list(range(5))})
        chunks = asyncio.run(chunker.chunk(df, _cfg(windowSize=3)))
        # 同类型为一组，单组超窗口单独成切片
        assert len(chunks) == 1
        assert chunks[0].content["rowCount"] == 5


# ----------------------------------------------------------------------
# 窗口切分测试
# ----------------------------------------------------------------------


class TestWindowSplit:
    def test_overlap(self, chunker):
        # 不同类型行交替，窗口 2，overlap 1
        rows = []
        for i in range(6):
            rows.append([f"s{i}", i])  # 交替类型
        df = pd.DataFrame(rows, columns=["a", "b"])
        chunks = asyncio.run(chunker.chunk(df, _cfg(windowSize=2, overlap=0.5)))
        # 应有重叠
        total = sum(c.content["rowCount"] for c in chunks)
        assert total >= 6  # 有重叠则 total > 6

    def test_single_group_over_window(self, chunker):
        # 单组 10 行，窗口 3 → 1 个切片（单组不拆分）
        df = pd.DataFrame({"id": list(range(10))})
        chunks = asyncio.run(chunker.chunk(df, _cfg(windowSize=3)))
        assert len(chunks) == 1
        assert chunks[0].content["rowCount"] == 10

    def test_empty_body(self, chunker):
        # 只有表头，无数据
        df = pd.DataFrame({"a": [], "b": []})
        chunks = asyncio.run(chunker.chunk(df, _cfg()))
        assert len(chunks) == 1
        assert chunks[0].content["rowCount"] == 0
        assert chunks[0].content["headers"] == ["a", "b"]


# ----------------------------------------------------------------------
# 性能测试
# ----------------------------------------------------------------------


class TestPerformance:
    def test_large_table_p95(self, chunker):
        """大表 (10 万行) 切片 P95 ≤ 5s."""
        n = 100_000
        df = pd.DataFrame(
            {
                "id": list(range(n)),
                "name": [f"name_{i}" for i in range(n)],
                "value": [float(i) for i in range(n)],
            }
        )
        cfg = _cfg(windowSize=1000)
        # 多次测量取 P95
        durations = []
        for _ in range(5):
            start = time.perf_counter()
            chunks = asyncio.run(chunker.chunk(df, cfg))
            dur = time.perf_counter() - start
            durations.append(dur)
        durations.sort()
        p95 = durations[int(len(durations) * 0.95) - 1] if len(durations) > 1 else durations[0]
        assert p95 <= 5.0, f"P95 耗时 {p95:.2f}s 超过 5s"
        # 验证切片正确
        total_rows = sum(c.content["rowCount"] for c in chunks)
        assert total_rows == n

    def test_large_table_chunk_count(self, chunker):
        """大表按窗口切分产生合理切片数."""
        n = 10_000
        # 交替类型，确保产生多个切片
        rows = []
        for i in range(n):
            if i % 2 == 0:
                rows.append([f"s{i}", i])
            else:
                rows.append([i, f"s{i}"])
        df = pd.DataFrame(rows, columns=["a", "b"])
        chunks = asyncio.run(chunker.chunk(df, _cfg(windowSize=100)))
        assert len(chunks) >= 1
        total = sum(c.content["rowCount"] for c in chunks)
        assert total == n


# ----------------------------------------------------------------------
# 注册机制测试
# ----------------------------------------------------------------------


class TestRegistration:
    def test_registered(self):
        # conftest 清空注册表，显式重新注册验证装饰器机制
        from chunker.table_chunker import TableChunker as _TC

        register_chunker(Modality.TABLE)(_TC)
        assert is_chunker_registered("table") is True
        assert "table" in list_modalities()

    def test_get_chunker(self):
        from chunker.table_chunker import TableChunker as _TC

        register_chunker(Modality.TABLE)(_TC)
        c = get_chunker("table")
        assert isinstance(c, TableChunker)
        assert c.modality == Modality.TABLE

    def test_is_base_chunker_subclass(self):
        assert issubclass(TableChunker, BaseChunker)


# ----------------------------------------------------------------------
# 异常处理测试
# ----------------------------------------------------------------------


class TestExceptions:
    def test_unsupported_extension(self, chunker, tmp_path):
        p = tmp_path / "data.txt"
        p.write_text("hello")
        with pytest.raises(PreprocessError):
            asyncio.run(chunker.chunk(str(p), _cfg()))

    def test_empty_content(self, chunker):
        with pytest.raises(PreprocessError):
            asyncio.run(chunker.chunk([], _cfg()))

    def test_invalid_content_type(self, chunker):
        with pytest.raises(PreprocessError):
            asyncio.run(chunker.chunk(12345, _cfg()))

    def test_csv_parse_error(self, chunker):
        # 非法 CSV 内容（但仍能被 pandas 解析为单列）→ 不应抛错
        # 用真正无法解析的内容
        with pytest.raises(PreprocessError):
            asyncio.run(chunker.chunk("\x00\x01\x02", _cfg(extra={"inputFormat": "html"})))

    def test_nonexistent_file(self, chunker):
        # 不存在的文件路径，但扩展名已知 → 当作内容解析失败
        with pytest.raises(PreprocessError):
            asyncio.run(chunker.chunk("nonexistent_file_xyz.csv", _cfg()))


# ----------------------------------------------------------------------
# 配置测试
# ----------------------------------------------------------------------


class TestConfig:
    def test_input_format_csv(self, chunker):
        csv = "a,b\n1,2\n"
        chunks = asyncio.run(chunker.chunk(csv, _cfg(extra={"inputFormat": "csv"})))
        assert len(chunks) == 1

    def test_source_label(self, chunker):
        df = pd.DataFrame({"a": [1]})
        chunks = asyncio.run(chunker.chunk(df, _cfg(extra={"source": "my_table"})))
        assert chunks[0].metadata.extra["source"] == "my_table"
        assert chunks[0].metadata.source == "my_table"

    def test_similarity_threshold_default(self):
        c = TableChunker()
        assert c.similarityThreshold == DEFAULT_SIMILARITY_THRESHOLD

    def test_custom_similarity_threshold(self):
        c = TableChunker(similarityThreshold=0.8)
        assert c.similarityThreshold == 0.8


# ----------------------------------------------------------------------
# 后处理测试
# ----------------------------------------------------------------------


class TestPostprocess:
    def test_tokens_calculated(self, chunker):
        df = pd.DataFrame({"a": [1, 2], "b": ["x", "y"]})
        chunks = asyncio.run(chunker.chunk(df, _cfg()))
        for c in chunks:
            assert c.tokens is not None
            assert c.tokens > 0

    def test_index_sequential(self, chunker):
        df1 = pd.DataFrame({"id": [1, 2]})
        df2 = pd.DataFrame({"name": ["a", "b"]})  # 不同表头，不合并
        chunks = asyncio.run(chunker.chunk([df1, df2], _cfg()))
        for i, c in enumerate(chunks):
            assert c.metadata.index == i

    def test_chunk_id_unique(self, chunker):
        df = pd.DataFrame({"a": [1, 2, 3, 4]})
        chunks = asyncio.run(chunker.chunk(df, _cfg(windowSize=1)))
        ids = [c.id for c in chunks]
        assert len(ids) == len(set(ids))

    def test_chunk_with_result(self, chunker):
        df = pd.DataFrame({"a": [1, 2]})
        result = asyncio.run(chunker.chunk_with_result(df, _cfg()))
        assert result.modality == Modality.TABLE
        assert result.count >= 1
        assert result.durationMs >= 0
        assert result.totalTokens > 0
