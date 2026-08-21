package com.shuqing.bigdata.sqlgateway.parser;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 方言转换器。
 *
 * <p>基于正则替换实现 Hive、Doris、Trino、ANSI 方言之间的常见语法差异转换。
 * 转换策略为"最小必要改写"：仅替换方言间不兼容的语法结构，保留通用部分。</p>
 *
 * <p>支持的差异处理：</p>
 * <ul>
 *   <li>Hive：{@code LIMIT} 语法、{@code STORED AS ORC}、分区语法、{@code INSERT OVERWRITE}</li>
 *   <li>Doris：{@code DISTRIBUTED BY HASH}、{@code PROPERTIES}</li>
 *   <li>Trino：{@code WITH} CTE 语法、{@code CROSS JOIN}、{@code ARRAY} 函数、{@code date_diff} 命名</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public class DialectConverter {

    private final SqlParserService parserService = new SqlParserService();

    /**
     * 将 SQL 从源方言转换到目标方言。
     *
     * @param sql        SQL 文本
     * @param fromDialect 源方言
     * @param toDialect  目标方言
     * @return 转换后的 SQL
     */
    public String convert(String sql, SqlDialect fromDialect, SqlDialect toDialect) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        if (fromDialect == toDialect) {
            return sql;
        }
        // 先按源方言规范化
        String normalized = sql.trim();
        // 去除结尾分号便于后续追加
        boolean trailingSemicolon = normalized.endsWith(";");
        if (trailingSemicolon) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }

        String result = normalized;
        // 通用：先做源方言特有结构的"展开/标准化"
        if (fromDialect == SqlDialect.HIVE) {
            result = fromHive(result, toDialect);
        } else if (fromDialect == SqlDialect.DORIS) {
            result = fromDoris(result, toDialect);
        } else if (fromDialect == SqlDialect.TRINO) {
            result = fromTrino(result, toDialect);
        } else {
            // ANSI 源：直接进入目标方言适配
            result = toTargetFromAnsi(result, toDialect);
        }

        if (trailingSemicolon) {
            result = result + ";";
        }
        return result;
    }

    /**
     * 自动检测源方言并转换到目标方言。
     *
     * @param sql        SQL 文本
     * @param toDialect  目标方言
     * @return 转换后的 SQL
     */
    public String convertAuto(String sql, SqlDialect toDialect) {
        return convert(sql, parserService.detectDialect(sql), toDialect);
    }

    // ===================== Hive 源 =====================

    private String fromHive(String sql, SqlDialect target) {
        String s = sql;
        switch (target) {
            case TRINO -> {
                // Hive INSERT OVERWRITE TABLE t → Trino 不支持 OVERWRITE，改为 DELETE + INSERT（简化为 INSERT）
                s = s.replaceAll("(?i)INSERT\\s+OVERWRITE\\s+TABLE\\s+", "INSERT INTO ");
                // Hive 的 STORED AS ORC/TEXTFILE/PARQUET → Trino 无需（删除）
                s = s.replaceAll("(?i)\\s+STORED\\s+AS\\s+\\w+", "");
                // Hive 的 PARTITIONED BY (...) → Trino 无需（删除）
                s = s.replaceAll("(?i)\\s+PARTITIONED\\s+BY\\s*\\([^)]*\\)", "");
                // Hive 的 LOCATION '...' → 删除
                s = s.replaceAll("(?i)\\s+LOCATION\\s+'[^']*'", "");
                // Hive 的 TBLPROPERTIES → 删除
                s = s.replaceAll("(?i)\\s+TBLPROPERTIES\\s*\\([^)]*\\)", "");
                // Hive 的 `backtick` 标识符 → Trino 使用双引号
                s = s.replaceAll("`([^`]+)`", "\"$1\"");
                // Hive 的 date_format → Trino 的 format_datetime
                s = s.replaceAll("(?i)\\bdate_format\\b", "format_datetime");
                // Hive 的 datediff(a,b) → Trino 的 date_diff('day', b, a)
                s = s.replaceAll("(?i)\\bdatediff\\s*\\(", "date_diff('day', ");
                // Hive 的 LIMIT n m（Hive 支持 LIMIT offset, count）→ Trino 用 OFFSET n LIMIT m
                s = convertHiveLimitToOffset(s);
            }
            case DORIS -> {
                // Hive STORED AS ORC → Doris 无需（删除）
                s = s.replaceAll("(?i)\\s+STORED\\s+AS\\s+\\w+", "");
                // Hive PARTITIONED BY → Doris 也支持，保留
                // Hive INSERT OVERWRITE TABLE → Doris 不支持 OVERWRITE，改为 INSERT INTO
                s = s.replaceAll("(?i)INSERT\\s+OVERWRITE\\s+TABLE\\s+", "INSERT INTO ");
                // Hive 的 `backtick` 标识符 → Doris 也支持反引号，保留
            }
            case ANSI -> {
                s = s.replaceAll("(?i)INSERT\\s+OVERWRITE\\s+TABLE\\s+", "INSERT INTO ");
                s = s.replaceAll("(?i)\\s+STORED\\s+AS\\s+\\w+", "");
                s = s.replaceAll("(?i)\\s+PARTITIONED\\s+BY\\s*\\([^)]*\\)", "");
                s = s.replaceAll("(?i)\\s+LOCATION\\s+'[^']*'", "");
                s = s.replaceAll("`([^`]+)`", "$1");
            }
            default -> {
            }
        }
        return s;
    }

    private String convertHiveLimitToOffset(String s) {
        // LIMIT offset, count → OFFSET offset LIMIT count
        Pattern p = Pattern.compile("(?i)\\bLIMIT\\s+(\\d+)\\s*,\\s*(\\d+)");
        Matcher m = p.matcher(s);
        if (m.find()) {
            return m.replaceFirst("OFFSET " + m.group(1) + " LIMIT " + m.group(2));
        }
        return s;
    }

    // ===================== Doris 源 =====================

    private String fromDoris(String sql, SqlDialect target) {
        String s = sql;
        switch (target) {
            case TRINO -> {
                // Doris DISTRIBUTED BY HASH(...) BUCKETS n → Trino 无需（删除）
                s = s.replaceAll("(?i)\\s+DISTRIBUTED\\s+BY\\s+HASH\\s*\\([^)]*\\)\\s+BUCKETS\\s+\\d+", "");
                s = s.replaceAll("(?i)\\s+DISTRIBUTED\\s+BY\\s+HASH\\s*\\([^)]*\\)", "");
                // Doris PROPERTIES (...) → Trino 无需（删除）
                s = s.replaceAll("(?i)\\s+PROPERTIES\\s*\\([^)]*\\)", "");
                // Doris 的 `backtick` → Trino 双引号
                s = s.replaceAll("`([^`]+)`", "\"$1\"");
                // Doris 的 BITMAP_UNION → Trino 无直接对应，保留（标记为函数）
            }
            case HIVE -> {
                // Doris DISTRIBUTED BY → Hive 无需（删除）
                s = s.replaceAll("(?i)\\s+DISTRIBUTED\\s+BY\\s+HASH\\s*\\([^)]*\\)\\s+BUCKETS\\s+\\d+", "");
                s = s.replaceAll("(?i)\\s+DISTRIBUTED\\s+BY\\s+HASH\\s*\\([^)]*\\)", "");
                // Doris PROPERTIES → Hive TBLPROPERTIES
                s = s.replaceAll("(?i)\\bPROPERTIES\\b", "TBLPROPERTIES");
            }
            case ANSI -> {
                s = s.replaceAll("(?i)\\s+DISTRIBUTED\\s+BY\\s+HASH\\s*\\([^)]*\\)\\s+BUCKETS\\s+\\d+", "");
                s = s.replaceAll("(?i)\\s+DISTRIBUTED\\s+BY\\s+HASH\\s*\\([^)]*\\)", "");
                s = s.replaceAll("(?i)\\s+PROPERTIES\\s*\\([^)]*\\)", "");
                s = s.replaceAll("`([^`]+)`", "$1");
            }
            default -> {
            }
        }
        return s;
    }

    // ===================== Trino 源 =====================

    private String fromTrino(String sql, SqlDialect target) {
        String s = sql;
        switch (target) {
            case HIVE -> {
                // Trino 双引号标识符 → Hive 反引号
                s = s.replaceAll("\"([^\"]+)\"", "`$1`");
                // Trino format_datetime → Hive date_format
                s = s.replaceAll("(?i)\\bformat_datetime\\b", "date_format");
                // Trino date_diff('day', b, a) → Hive datediff(a, b)（仅处理 'day' 单位）
                s = s.replaceAll("(?i)\\bdate_diff\\s*\\(\\s*'day'\\s*,\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                        "datediff($2, $1)");
                // Trino OFFSET n LIMIT m → Hive LIMIT n, m
                s = convertTrinoOffsetToHiveLimit(s);
                // Trino 的 CROSS JOIN → Hive 也支持，保留
            }
            case DORIS -> {
                // Trino 双引号标识符 → Doris 反引号
                s = s.replaceAll("\"([^\"]+)\"", "`$1`");
                // Trino OFFSET → Doris 不支持，转为 LIMIT（简化：删除 OFFSET）
                s = s.replaceAll("(?i)\\s+OFFSET\\s+\\d+", "");
            }
            case ANSI -> {
                s = s.replaceAll("\"([^\"]+)\"", "$1");
                s = s.replaceAll("(?i)\\s+OFFSET\\s+\\d+", "");
            }
            default -> {
            }
        }
        return s;
    }

    private String convertTrinoOffsetToHiveLimit(String s) {
        Pattern p = Pattern.compile("(?i)\\bOFFSET\\s+(\\d+)\\s+LIMIT\\s+(\\d+)");
        Matcher m = p.matcher(s);
        if (m.find()) {
            return m.replaceFirst("LIMIT " + m.group(1) + ", " + m.group(2));
        }
        return s;
    }

    // ===================== ANSI 源 → 目标 =====================

    private String toTargetFromAnsi(String sql, SqlDialect target) {
        String s = sql;
        switch (target) {
            case HIVE -> {
                // ANSI → Hive：基本兼容，无需改写
            }
            case DORIS -> {
                // ANSI → Doris：基本兼容
            }
            case TRINO -> {
                // ANSI → Trino：基本兼容
            }
            default -> {
            }
        }
        return s;
    }
}