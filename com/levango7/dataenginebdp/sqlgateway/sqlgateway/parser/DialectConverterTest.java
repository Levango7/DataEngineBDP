package com.shuqing.bigdata.sqlgateway.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DialectConverter} 单元测试。
 *
 * <p>覆盖 Hive、Doris、Trino、ANSI 之间常见方言差异的转换。</p>
 *
 * @author shuqing-bigdata
 */
@DisplayName("DialectConverter 方言转换测试")
class DialectConverterTest {

    private final DialectConverter converter = new DialectConverter();

    // ===================== Hive 源 =====================

    @Nested
    @DisplayName("Hive → 其他方言")
    class HiveSourceTest {

        @Test
        @DisplayName("Hive→Trino: INSERT OVERWRITE 改写")
        void hiveToTrinoInsertOverwrite() {
            String result = converter.convert(
                    "INSERT OVERWRITE TABLE t1 SELECT a FROM t2",
                    SqlDialect.HIVE, SqlDialect.TRINO);
            assertTrue(result.contains("INSERT INTO"));
            assertFalse(result.toUpperCase().contains("OVERWRITE"));
        }

        @Test
        @DisplayName("Hive→Trino: 删除 STORED AS")
        void hiveToTrinoRemoveStoredAs() {
            String result = converter.convert(
                    "CREATE TABLE t1 (a INT) STORED AS ORC",
                    SqlDialect.HIVE, SqlDialect.TRINO);
            assertFalse(result.toUpperCase().contains("STORED AS"));
        }

        @Test
        @DisplayName("Hive→Trino: 删除 PARTITIONED BY")
        void hiveToTrinoRemovePartitionedBy() {
            String result = converter.convert(
                    "CREATE TABLE t1 (a INT) PARTITIONED BY (dt STRING)",
                    SqlDialect.HIVE, SqlDialect.TRINO);
            assertFalse(result.toUpperCase().contains("PARTITIONED BY"));
        }

        @Test
        @DisplayName("Hive→Trino: 反引号转双引号")
        void hiveToTrinoBacktickToDoubleQuote() {
            String result = converter.convert(
                    "SELECT `a` FROM `t1`", SqlDialect.HIVE, SqlDialect.TRINO);
            assertTrue(result.contains("\"a\""));
            assertTrue(result.contains("\"t1\""));
        }

        @Test
        @DisplayName("Hive→Trino: date_format → format_datetime")
        void hiveToTrinoDateFormat() {
            String result = converter.convert(
                    "SELECT date_format(t, 'yyyy-MM') FROM t1",
                    SqlDialect.HIVE, SqlDialect.TRINO);
            assertTrue(result.contains("format_datetime"));
        }

        @Test
        @DisplayName("Hive→Doris: 删除 STORED AS")
        void hiveToDorisRemoveStoredAs() {
            String result = converter.convert(
                    "CREATE TABLE t1 (a INT) STORED AS ORC",
                    SqlDialect.HIVE, SqlDialect.DORIS);
            assertFalse(result.toUpperCase().contains("STORED AS"));
        }

        @Test
        @DisplayName("Hive→Doris: INSERT OVERWRITE 改写")
        void hiveToDorisInsertOverwrite() {
            String result = converter.convert(
                    "INSERT OVERWRITE TABLE t1 SELECT a FROM t2",
                    SqlDialect.HIVE, SqlDialect.DORIS);
            assertTrue(result.contains("INSERT INTO"));
        }

        @Test
        @DisplayName("Hive→ANSI: 反引号去除")
        void hiveToAnsiRemoveBacktick() {
            String result = converter.convert(
                    "SELECT `a` FROM `t1`", SqlDialect.HIVE, SqlDialect.ANSI);
            assertFalse(result.contains("`"));
        }

        @Test
        @DisplayName("Hive→ANSI: 删除 LOCATION")
        void hiveToAnsiRemoveLocation() {
            String result = converter.convert(
                    "CREATE TABLE t1 (a INT) LOCATION '/path/to/data'",
                    SqlDialect.HIVE, SqlDialect.ANSI);
            assertFalse(result.toUpperCase().contains("LOCATION"));
        }

        @Test
        @DisplayName("Hive→Hive: 不变")
        void hiveToHiveNoChange() {
            String sql = "SELECT a FROM t1";
            assertEquals(sql, converter.convert(sql, SqlDialect.HIVE, SqlDialect.HIVE));
        }
    }

    // ===================== Doris 源 =====================

    @Nested
    @DisplayName("Doris → 其他方言")
    class DorisSourceTest {

        @Test
        @DisplayName("Doris→Trino: 删除 DISTRIBUTED BY")
        void dorisToTrinoRemoveDistributed() {
            String result = converter.convert(
                    "CREATE TABLE t1 (a INT) DISTRIBUTED BY HASH(a) BUCKETS 10",
                    SqlDialect.DORIS, SqlDialect.TRINO);
            assertFalse(result.toUpperCase().contains("DISTRIBUTED"));
        }

        @Test
        @DisplayName("Doris→Trino: 删除 PROPERTIES")
        void dorisToTrinoRemoveProperties() {
            String result = converter.convert(
                    "CREATE TABLE t1 (a INT) PROPERTIES('k'='v')",
                    SqlDialect.DORIS, SqlDialect.TRINO);
            assertFalse(result.toUpperCase().contains("PROPERTIES"));
        }

        @Test
        @DisplayName("Doris→Trino: 反引号转双引号")
        void dorisToTrinoBacktickToDoubleQuote() {
            String result = converter.convert(
                    "SELECT `a` FROM `t1`", SqlDialect.DORIS, SqlDialect.TRINO);
            assertTrue(result.contains("\"a\""));
        }

        @Test
        @DisplayName("Doris→Hive: PROPERTIES → TBLPROPERTIES")
        void dorisToHivePropertiesToTblproperties() {
            String result = converter.convert(
                    "CREATE TABLE t1 (a INT) PROPERTIES('k'='v')",
                    SqlDialect.DORIS, SqlDialect.HIVE);
            assertTrue(result.contains("TBLPROPERTIES"));
        }

        @Test
        @DisplayName("Doris→Hive: 删除 DISTRIBUTED BY")
        void dorisToHiveRemoveDistributed() {
            String result = converter.convert(
                    "CREATE TABLE t1 (a INT) DISTRIBUTED BY HASH(a) BUCKETS 10",
                    SqlDialect.DORIS, SqlDialect.HIVE);
            assertFalse(result.toUpperCase().contains("DISTRIBUTED"));
        }

        @Test
        @DisplayName("Doris→ANSI: 删除 PROPERTIES")
        void dorisToAnsiRemoveProperties() {
            String result = converter.convert(
                    "CREATE TABLE t1 (a INT) PROPERTIES('k'='v')",
                    SqlDialect.DORIS, SqlDialect.ANSI);
            assertFalse(result.toUpperCase().contains("PROPERTIES"));
        }

        @Test
        @DisplayName("Doris→ANSI: 反引号去除")
        void dorisToAnsiRemoveBacktick() {
            String result = converter.convert(
                    "SELECT `a` FROM `t1`", SqlDialect.DORIS, SqlDialect.ANSI);
            assertFalse(result.contains("`"));
        }

        @Test
        @DisplayName("Doris→Doris: 不变")
        void dorisToDorisNoChange() {
            String sql = "SELECT a FROM t1";
            assertEquals(sql, converter.convert(sql, SqlDialect.DORIS, SqlDialect.DORIS));
        }
    }

    // ===================== Trino 源 =====================

    @Nested
    @DisplayName("Trino → 其他方言")
    class TrinoSourceTest {

        @Test
        @DisplayName("Trino→Hive: 双引号转反引号")
        void trinoToHiveDoubleQuoteToBacktick() {
            String result = converter.convert(
                    "SELECT \"a\" FROM \"t1\"", SqlDialect.TRINO, SqlDialect.HIVE);
            assertTrue(result.contains("`a`"));
            assertTrue(result.contains("`t1`"));
        }

        @Test
        @DisplayName("Trino→Hive: format_datetime → date_format")
        void trinoToHiveFormatDatetime() {
            String result = converter.convert(
                    "SELECT format_datetime(t, 'yyyy-MM') FROM t1",
                    SqlDialect.TRINO, SqlDialect.HIVE);
            assertTrue(result.contains("date_format"));
        }

        @Test
        @DisplayName("Trino→Doris: 双引号转反引号")
        void trinoToDorisDoubleQuoteToBacktick() {
            String result = converter.convert(
                    "SELECT \"a\" FROM \"t1\"", SqlDialect.TRINO, SqlDialect.DORIS);
            assertTrue(result.contains("`a`"));
        }

        @Test
        @DisplayName("Trino→ANSI: 双引号去除")
        void trinoToAnsiRemoveDoubleQuote() {
            String result = converter.convert(
                    "SELECT \"a\" FROM \"t1\"", SqlDialect.TRINO, SqlDialect.ANSI);
            assertFalse(result.contains("\""));
        }

        @Test
        @DisplayName("Trino→ANSI: 删除 OFFSET")
        void trinoToAnsiRemoveOffset() {
            String result = converter.convert(
                    "SELECT a FROM t1 OFFSET 5 LIMIT 10", SqlDialect.TRINO, SqlDialect.ANSI);
            assertFalse(result.toUpperCase().contains("OFFSET"));
        }

        @Test
        @DisplayName("Trino→Trino: 不变")
        void trinoToTrinoNoChange() {
            String sql = "SELECT a FROM t1";
            assertEquals(sql, converter.convert(sql, SqlDialect.TRINO, SqlDialect.TRINO));
        }
    }

    // ===================== ANSI 源 =====================

    @Nested
    @DisplayName("ANSI → 其他方言")
    class AnsiSourceTest {

        @Test
        @DisplayName("ANSI→Hive: 不变")
        void ansiToHive() {
            String sql = "SELECT a FROM t1";
            assertEquals(sql, converter.convert(sql, SqlDialect.ANSI, SqlDialect.HIVE));
        }

        @Test
        @DisplayName("ANSI→Doris: 不变")
        void ansiToDoris() {
            String sql = "SELECT a FROM t1";
            assertEquals(sql, converter.convert(sql, SqlDialect.ANSI, SqlDialect.DORIS));
        }

        @Test
        @DisplayName("ANSI→Trino: 不变")
        void ansiToTrino() {
            String sql = "SELECT a FROM t1";
            assertEquals(sql, converter.convert(sql, SqlDialect.ANSI, SqlDialect.TRINO));
        }

        @Test
        @DisplayName("ANSI→ANSI: 不变")
        void ansiToAnsi() {
            String sql = "SELECT a FROM t1";
            assertEquals(sql, converter.convert(sql, SqlDialect.ANSI, SqlDialect.ANSI));
        }
    }

    // ===================== 自动检测 + 边界 =====================

    @Nested
    @DisplayName("自动检测与边界")
    class AutoAndEdgeTest {

        @Test
        @DisplayName("convertAuto 自动检测 Hive")
        void convertAutoHive() {
            String result = converter.convertAuto(
                    "INSERT OVERWRITE TABLE t1 SELECT a FROM t2", SqlDialect.TRINO);
            assertTrue(result.contains("INSERT INTO"));
        }

        @Test
        @DisplayName("convertAuto 自动检测 Doris")
        void convertAutoDoris() {
            String result = converter.convertAuto(
                    "CREATE TABLE t1 (a INT) DISTRIBUTED BY HASH(a) BUCKETS 10",
                    SqlDialect.TRINO);
            assertFalse(result.toUpperCase().contains("DISTRIBUTED"));
        }

        @Test
        @DisplayName("convertAuto 自动检测 Trino")
        void convertAutoTrino() {
            String result = converter.convertAuto(
                    "WITH cte AS (SELECT \"a\" FROM t1) SELECT * FROM cte", SqlDialect.HIVE);
            assertTrue(result.contains("`a`"));
        }

        @Test
        @DisplayName("空 SQL 返回原样")
        void emptySql() {
            assertEquals("", converter.convert("", SqlDialect.HIVE, SqlDialect.TRINO));
        }

        @Test
        @DisplayName("null SQL 返回 null")
        void nullSql() {
            assertNull(converter.convert(null, SqlDialect.HIVE, SqlDialect.TRINO));
        }

        @Test
        @DisplayName("保留结尾分号")
        void preserveTrailingSemicolon() {
            String result = converter.convert(
                    "SELECT a FROM t1;", SqlDialect.ANSI, SqlDialect.HIVE);
            assertTrue(result.endsWith(";"));
        }

        @Test
        @DisplayName("相同方言返回原样")
        void sameDialectReturnsOriginal() {
            String sql = "SELECT a FROM t1 WHERE b > 0";
            assertEquals(sql, converter.convert(sql, SqlDialect.HIVE, SqlDialect.HIVE));
        }

        @Test
        @DisplayName("Hive→Trino: LIMIT offset, count → OFFSET ... LIMIT ...")
        void hiveToTrinoLimitOffset() {
            String result = converter.convert(
                    "SELECT a FROM t1 LIMIT 5, 10", SqlDialect.HIVE, SqlDialect.TRINO);
            assertTrue(result.contains("OFFSET"));
            assertTrue(result.contains("LIMIT"));
        }

        @Test
        @DisplayName("Trino→Hive: OFFSET ... LIMIT ... → LIMIT offset, count")
        void trinoToHiveOffsetLimit() {
            String result = converter.convert(
                    "SELECT a FROM t1 OFFSET 5 LIMIT 10", SqlDialect.TRINO, SqlDialect.HIVE);
            assertTrue(result.contains("LIMIT"));
        }

        @Test
        @DisplayName("Hive→Trino: datediff 转换")
        void hiveToTrinoDatediff() {
            String result = converter.convert(
                    "SELECT datediff(a, b) FROM t1", SqlDialect.HIVE, SqlDialect.TRINO);
            assertTrue(result.contains("date_diff"));
        }

        @Test
        @DisplayName("Hive→Trino: 删除 TBLPROPERTIES")
        void hiveToTrinoRemoveTblproperties() {
            String result = converter.convert(
                    "CREATE TABLE t1 (a INT) TBLPROPERTIES('k'='v')",
                    SqlDialect.HIVE, SqlDialect.TRINO);
            assertFalse(result.toUpperCase().contains("TBLPROPERTIES"));
        }
    }
}