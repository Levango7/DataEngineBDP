package com.levango7.dataenginebdp.sqlgateway.crosssource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 跨源归并列类型统一转换器。
 *
 * <p>不同数据源（Trino/Doris/Hive/Iceberg/IoTDB/Elasticsearch）返回的 Java 类型往往不一致，
 * 例如同样是 BIGINT 列，Trino 可能返回 {@code Long}，Doris 返回 {@code BigInteger}，
 * Elasticsearch 返回 {@code Integer}。在 UNION/JOIN 归并时，若不统一类型，
 * 会导致：</p>
 * <ul>
 *   <li>去重指纹（fingerprint）误判：{@code 1 (Integer)} 与 {@code 1L (Long)} 被视为不同行；</li>
 *   <li>JOIN 键匹配失败：{@code 1} 与 {@code 1L} 在 {@code equals} 比较下不相等；</li>
 *   <li>下游消费方类型断言失败：调用方期望 {@code Long} 却收到 {@code Integer}。</li>
 * </ul>
 *
 * <p>本类提供统一的类型归一化策略：</p>
 * <ul>
 *   <li>数值类型（{@code Byte/Short/Integer/Long/Float/Double/BigInteger/BigDecimal}）
 *       统一转换为 {@link BigDecimal}，保留精度且跨类型可比较；</li>
 *   <li>布尔类型保持 {@code Boolean}；</li>
 *   <li>字符串类型去首尾空格；</li>
 *   <li>时间类型（{@code java.sql.Date/java.sql.Timestamp/java.time.LocalDate/java.time.LocalDateTime}）
 *       统一转换为 {@code String}（ISO-8601 格式），避免跨源时区差异；</li>
 *   <li>{@code null} 保持 {@code null}；</li>
 *   <li>其他类型调用 {@code toString()} 转为字符串。</li>
 * </ul>
 *
 * <p>本类为无状态工具类，所有方法线程安全。</p>
 *
 * @author shuqing-bigdata
 */
public final class ColumnTypeConverter {

    private ColumnTypeConverter() {
        // 工具类，禁止实例化
    }

    /**
     * 对单值执行类型归一化。
     *
     * @param value 原始值（可能为 null）
     * @return 归一化后的值；输入 null 返回 null
     */
    public static Object convertValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (value instanceof Long l) {
            return BigDecimal.valueOf(l);
        }
        if (value instanceof Integer i) {
            return BigDecimal.valueOf(i);
        }
        if (value instanceof Short s) {
            return BigDecimal.valueOf(s);
        }
        if (value instanceof Byte b) {
            return BigDecimal.valueOf(b);
        }
        if (value instanceof Double d) {
            return BigDecimal.valueOf(d);
        }
        if (value instanceof Float f) {
            return BigDecimal.valueOf(f);
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String s) {
            return s.trim();
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (value instanceof Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.toString();
        }
        if (value instanceof LocalDate ld) {
            return ld.toString();
        }
        // 兜底：转字符串
        return value.toString();
    }

    /**
     * 对整行数据执行类型归一化。
     *
     * @param row 原始行（可能为 null）
     * @return 归一化后的新行；输入 null 返回空列表
     */
    public static List<Object> convertRow(List<Object> row) {
        if (row == null) {
            return new ArrayList<>();
        }
        List<Object> out = new ArrayList<>(row.size());
        for (Object cell : row) {
            out.add(convertValue(cell));
        }
        return out;
    }

    /**
     * 对多行数据执行类型归一化。
     *
     * @param rows 原始行列表（可能为 null）
     * @return 归一化后的新行列表；输入 null 返回空列表
     */
    public static List<List<Object>> convertRows(List<List<Object>> rows) {
        if (rows == null) {
            return new ArrayList<>();
        }
        List<List<Object>> out = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            out.add(convertRow(row));
        }
        return out;
    }

    /**
     * 归一化列名：去首尾空格并转小写。
     *
     * <p>不同数据源对列名大小写处理不一致（Doris 默认小写、Trino 保留原始大小写、
     * Elasticsearch 字段名区分大小写）。归并前统一为小写，确保按列名对齐时能正确匹配。</p>
     *
     * @param columnName 原始列名
     * @return 归一化后的列名；输入 null 返回 null
     */
    public static String normalizeColumnName(String columnName) {
        if (columnName == null) {
            return null;
        }
        return columnName.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 批量归一化列名列表。
     *
     * @param columns 原始列名列表
     * @return 归一化后的新列名列表；输入 null 返回空列表
     */
    public static List<String> normalizeColumns(List<String> columns) {
        if (columns == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(columns.size());
        for (String col : columns) {
            out.add(normalizeColumnName(col));
        }
        return out;
    }

    /**
     * 判断两个归一化后的值是否语义相等。
     *
     * <p>用于 JOIN 键匹配和 UNION 去重。两个 null 视为相等（与 SQL UNION 语义一致，
     * 但与 SQL JOIN 不同——JOIN 中 NULL = NULL 为 UNKNOWN，调用方需自行处理）。</p>
     *
     * @param a 第一个值（应已通过 {@link #convertValue} 归一化）
     * @param b 第二个值（应已通过 {@link #convertValue} 归一化）
     * @return true 表示语义相等
     */
    public static boolean semanticEquals(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof BigDecimal ad && b instanceof BigDecimal bd) {
            return ad.compareTo(bd) == 0;
        }
        return a.equals(b);
    }

    /**
     * 计算归一化后值的语义哈希码。
     *
     * <p>与 {@link #semanticEquals} 配套：{@code semanticEquals(a,b)} 为 true 时，
     * {@code semanticHashCode(a) == semanticHashCode(b)} 必须成立。</p>
     *
     * @param value 已归一化的值
     * @return 哈希码；null 返回 0
     */
    public static int semanticHashCode(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof BigDecimal bd) {
            // BigDecimal.equals 考虑精度（1.0 != 1.00），但 compareTo 不考虑
            // 用 stripTrailingZeros 的 hashCode 保证语义一致
            return bd.stripTrailingZeros().hashCode();
        }
        return value.hashCode();
    }
}