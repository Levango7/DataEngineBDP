package com.levango7.dataenginebdp.sqlgateway.crosssource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ColumnTypeConverter} 单元测试。
 *
 * <p>覆盖跨源归并中的类型统一转换逻辑，包括：</p>
 * <ul>
 *   <li>数值类型统一为 BigDecimal（Integer/Long/Short/Byte/Float/Double/BigInteger/BigDecimal）；</li>
 *   <li>字符串去首尾空格；</li>
 *   <li>布尔保持；</li>
 *   <li>null 保持；</li>
 *   <li>时间类型转 ISO-8601 字符串；</li>
 *   <li>列名归一化（小写 + trim）；</li>
 *   <li>语义相等与哈希一致性。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class ColumnTypeConverterTest {

    // ===================== convertValue =====================

    @Test
    @DisplayName("convertValue — null 保持 null")
    void convertValue_nullPreserved() {
        assertThat(ColumnTypeConverter.convertValue(null)).isNull();
    }

    @Test
    @DisplayName("convertValue — Integer 转 BigDecimal")
    void convertValue_integerToBigDecimal() {
        Object result = ColumnTypeConverter.convertValue(42);
        assertThat(result).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) result).isEqualByComparingTo("42");
    }

    @Test
    @DisplayName("convertValue — Long 转 BigDecimal")
    void convertValue_longToBigDecimal() {
        Object result = ColumnTypeConverter.convertValue(123L);
        assertThat(result).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) result).isEqualByComparingTo("123");
    }

    @Test
    @DisplayName("convertValue — Short 转 BigDecimal")
    void convertValue_shortToBigDecimal() {
        Object result = ColumnTypeConverter.convertValue((short) 7);
        assertThat(result).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) result).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("convertValue — Byte 转 BigDecimal")
    void convertValue_byteToBigDecimal() {
        Object result = ColumnTypeConverter.convertValue((byte) 3);
        assertThat(result).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) result).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("convertValue — Float 转 BigDecimal")
    void convertValue_floatToBigDecimal() {
        Object result = ColumnTypeConverter.convertValue(3.14f);
        assertThat(result).isInstanceOf(BigDecimal.class);
    }

    @Test
    @DisplayName("convertValue — Double 转 BigDecimal")
    void convertValue_doubleToBigDecimal() {
        Object result = ColumnTypeConverter.convertValue(2.718);
        assertThat(result).isInstanceOf(BigDecimal.class);
    }

    @Test
    @DisplayName("convertValue — BigInteger 转 BigDecimal")
    void convertValue_bigIntegerToBigDecimal() {
        Object result = ColumnTypeConverter.convertValue(new BigInteger("999999999999999999999"));
        assertThat(result).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) result).isEqualByComparingTo("999999999999999999999");
    }

    @Test
    @DisplayName("convertValue — BigDecimal 保持")
    void convertValue_bigDecimalPreserved() {
        BigDecimal bd = new BigDecimal("123.456");
        Object result = ColumnTypeConverter.convertValue(bd);
        assertThat(result).isSameAs(bd);
    }

    @Test
    @DisplayName("convertValue — Boolean 保持")
    void convertValue_booleanPreserved() {
        assertThat(ColumnTypeConverter.convertValue(Boolean.TRUE)).isEqualTo(true);
        assertThat(ColumnTypeConverter.convertValue(Boolean.FALSE)).isEqualTo(false);
    }

    @Test
    @DisplayName("convertValue — String 去首尾空格")
    void convertValue_stringTrimmed() {
        assertThat(ColumnTypeConverter.convertValue("  hello  ")).isEqualTo("hello");
        assertThat(ColumnTypeConverter.convertValue("hello")).isEqualTo("hello");
    }

    @Test
    @DisplayName("convertValue — java.sql.Timestamp 转 ISO-8601 字符串")
    void convertValue_timestampToString() {
        java.sql.Timestamp ts = java.sql.Timestamp.valueOf("2026-08-21 10:30:45");
        Object result = ColumnTypeConverter.convertValue(ts);
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("2026-08-21");
        assertThat((String) result).contains("10:30:45");
    }

    @Test
    @DisplayName("convertValue — java.sql.Date 转 ISO-8601 字符串")
    void convertValue_dateToString() {
        java.sql.Date d = java.sql.Date.valueOf("2026-08-21");
        Object result = ColumnTypeConverter.convertValue(d);
        assertThat(result).isEqualTo("2026-08-21");
    }

    @Test
    @DisplayName("convertValue — LocalDateTime 转 ISO-8601 字符串")
    void convertValue_localDateTimeToString() {
        java.time.LocalDateTime ldt = java.time.LocalDateTime.of(2026, 8, 21, 10, 30, 45);
        Object result = ColumnTypeConverter.convertValue(ldt);
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("2026-08-21");
    }

    @Test
    @DisplayName("convertValue — LocalDate 转 ISO-8601 字符串")
    void convertValue_localDateToString() {
        java.time.LocalDate ld = java.time.LocalDate.of(2026, 8, 21);
        Object result = ColumnTypeConverter.convertValue(ld);
        assertThat(result).isEqualTo("2026-08-21");
    }

    @Test
    @DisplayName("convertValue — 其他类型转 toString")
    void convertValue_otherTypeToString() {
        Object result = ColumnTypeConverter.convertValue(List.of(1, 2, 3));
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("1").contains("2").contains("3");
    }

    // ===================== convertRow / convertRows =====================

    @Test
    @DisplayName("convertRow — 整行类型归一化")
    void convertRow_mixedTypes() {
        List<Object> row = Arrays.asList(1, 2L, 3.0, "  x  ", true, null);
        List<Object> result = ColumnTypeConverter.convertRow(row);

        assertThat(result).hasSize(6);
        assertThat(result.get(0)).isInstanceOf(BigDecimal.class);
        assertThat(result.get(1)).isInstanceOf(BigDecimal.class);
        assertThat(result.get(2)).isInstanceOf(BigDecimal.class);
        assertThat(result.get(3)).isEqualTo("x");
        assertThat(result.get(4)).isEqualTo(true);
        assertThat(result.get(5)).isNull();
    }

    @Test
    @DisplayName("convertRow — null 输入返回空列表")
    void convertRow_nullInput() {
        assertThat(ColumnTypeConverter.convertRow(null)).isEmpty();
    }

    @Test
    @DisplayName("convertRows — 多行类型归一化")
    void convertRows_multipleRows() {
        List<List<Object>> rows = List.of(
                List.of(1, "a"),
                List.of(2L, "b"));
        List<List<Object>> result = ColumnTypeConverter.convertRows(rows);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get(0)).isInstanceOf(BigDecimal.class);
        assertThat(result.get(1).get(0)).isInstanceOf(BigDecimal.class);
    }

    @Test
    @DisplayName("convertRows — null 输入返回空列表")
    void convertRows_nullInput() {
        assertThat(ColumnTypeConverter.convertRows(null)).isEmpty();
    }

    // ===================== normalizeColumnName / normalizeColumns =====================

    @Test
    @DisplayName("normalizeColumnName — 去空格并转小写")
    void normalizeColumnName_trimAndLower() {
        assertThat(ColumnTypeConverter.normalizeColumnName("  ID  ")).isEqualTo("id");
        assertThat(ColumnTypeConverter.normalizeColumnName("Name")).isEqualTo("name");
        assertThat(ColumnTypeConverter.normalizeColumnName(null)).isNull();
    }

    @Test
    @DisplayName("normalizeColumns — 批量归一化")
    void normalizeColumns_batch() {
        List<String> result = ColumnTypeConverter.normalizeColumns(List.of("  ID  ", "Name", "AGE"));
        assertThat(result).containsExactly("id", "name", "age");
    }

    @Test
    @DisplayName("normalizeColumns — null 输入返回空列表")
    void normalizeColumns_nullInput() {
        assertThat(ColumnTypeConverter.normalizeColumns(null)).isEmpty();
    }

    // ===================== semanticEquals / semanticHashCode =====================

    @Test
    @DisplayName("semanticEquals — 两个 null 视为相等")
    void semanticEquals_bothNull() {
        assertThat(ColumnTypeConverter.semanticEquals(null, null)).isTrue();
    }

    @Test
    @DisplayName("semanticEquals — 一侧 null 视为不等")
    void semanticEquals_oneNull() {
        assertThat(ColumnTypeConverter.semanticEquals(null, "x")).isFalse();
        assertThat(ColumnTypeConverter.semanticEquals("x", null)).isFalse();
    }

    @Test
    @DisplayName("semanticEquals — BigDecimal 按 compareTo 语义比较（1.0 == 1.00）")
    void semanticEquals_bigDecimalCompareToSemantics() {
        BigDecimal a = new BigDecimal("1.0");
        BigDecimal b = new BigDecimal("1.00");
        assertThat(ColumnTypeConverter.semanticEquals(a, b)).isTrue();
    }

    @Test
    @DisplayName("semanticEquals — 字符串相等")
    void semanticEquals_stringEqual() {
        assertThat(ColumnTypeConverter.semanticEquals("abc", "abc")).isTrue();
        assertThat(ColumnTypeConverter.semanticEquals("abc", "abd")).isFalse();
    }

    @Test
    @DisplayName("semanticHashCode — 相等值哈希一致（BigDecimal 1.0 vs 1.00）")
    void semanticHashCode_consistentWithEquals() {
        BigDecimal a = new BigDecimal("1.0");
        BigDecimal b = new BigDecimal("1.00");
        assertThat(ColumnTypeConverter.semanticHashCode(a))
                .isEqualTo(ColumnTypeConverter.semanticHashCode(b));
    }

    @Test
    @DisplayName("semanticHashCode — null 返回 0")
    void semanticHashCode_nullReturnsZero() {
        assertThat(ColumnTypeConverter.semanticHashCode(null)).isZero();
    }
}