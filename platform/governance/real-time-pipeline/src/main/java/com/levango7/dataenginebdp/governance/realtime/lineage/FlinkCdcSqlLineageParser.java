package com.levango7.dataenginebdp.governance.realtime.lineage;

import com.levango7.dataenginebdp.governance.realtime.model.FieldLineage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flink CDC SQL 血缘解析器。
 *
 * <p>解析 Flink CDC SQL 语句，提取源表/目标表的字段级血缘关系。
 * 支持 Flink CDC 3.0 常见 SQL 语法：
 *
 * <pre>
 * -- 语法 1：INSERT INTO ... SELECT ...（最常见）
 * INSERT INTO target_table (field1, field2)
 * SELECT src.field_a, src.field_b + 1
 * FROM source_table src;
 *
 * -- 语法 2：CREATE TABLE AS SELECT
 * CREATE TABLE target_table AS
 * SELECT field_a, field_b FROM source_table;
 *
 * -- 语法 3：Flink CDC 多源 JOIN
 * INSERT INTO target_table
 * SELECT a.field1, b.field2
 * FROM source_table_a a JOIN source_table_b b ON a.id = b.id;
 * </pre>
 *
 * <p>血缘提取策略：
 * <ol>
 *   <li>识别 INSERT INTO / CREATE TABLE AS SELECT 语句</li>
 *   <li>提取目标表名与目标字段列表（INSERT 显式列表或 SELECT 投影）</li>
 *   <li>提取 FROM 子句中的源表（含别名）</li>
 *   <li>解析 SELECT 投影列，匹配源表字段，识别转换类型</li>
 *   <li>构造 {@link FieldLineage} 字段级血缘</li>
 * </ol>
 *
 * <p>对于复杂 SQL（子查询、UDF、窗口函数），降级为表级血缘（不提取字段级映射）。
 */
@Component
public class FlinkCdcSqlLineageParser {

    private static final Logger log = LoggerFactory.getLogger(FlinkCdcSqlLineageParser.class);

    // INSERT INTO target [(field1, field2, ...)] SELECT ... FROM ...
    private static final Pattern INSERT_PATTERN = Pattern.compile(
            "INSERT\\s+INTO\\s+(\\w+)\\s*(?:\\(([^)]+)\\))?\\s*SELECT\\s+(.+?)\\s+FROM\\s+(.+?)(?:;|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // CREATE TABLE target AS SELECT ... FROM ...
    private static final Pattern CTAS_PATTERN = Pattern.compile(
            "CREATE\\s+TABLE\\s+(\\w+)\\s+AS\\s*SELECT\\s+(.+?)\\s+FROM\\s+(.+?)(?:;|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // 源表与别名：source_table src 或 source_table AS src 或 source_table
    private static final Pattern SOURCE_TABLE_PATTERN = Pattern.compile(
            "(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?",
            Pattern.CASE_INSENSITIVE);

    // 投影列：field 或 alias.field 或 expression AS field 或 expression field
    private static final Pattern PROJECTION_COLUMN_PATTERN = Pattern.compile(
            "(?:([\\w.]+)\\s*(?:AS\\s+)?(\\w+)?|([^,]+))",
            Pattern.CASE_INSENSITIVE);

    /**
     * 解析 Flink CDC SQL，提取字段级血缘。
     *
     * @param sqlText Flink CDC SQL 文本
     * @param jobId Flink 作业 ID（用于追溯）
     * @return 字段级血缘；解析失败时返回表级血缘（fieldMappings 为空）
     */
    public FieldLineage parse(String sqlText, String jobId) {
        long start = System.currentTimeMillis();
        log.debug("Parsing lineage from SQL: {}", sqlText);

        if (sqlText == null || sqlText.trim().isEmpty()) {
            return buildEmptyLineage(jobId, sqlText, start);
        }

        String normalized = normalizeSql(sqlText);

        try {
            // 尝试 INSERT INTO ... SELECT ... FROM ...
            Matcher insertMatcher = INSERT_PATTERN.matcher(normalized);
            if (insertMatcher.find()) {
                return parseInsert(insertMatcher, jobId, sqlText, start);
            }

            // 尝试 CREATE TABLE AS SELECT
            Matcher ctasMatcher = CTAS_PATTERN.matcher(normalized);
            if (ctasMatcher.find()) {
                return parseCtas(ctasMatcher, jobId, sqlText, start);
            }

            log.warn("Unrecognized SQL pattern, returning empty lineage: {}", normalized);
            return buildEmptyLineage(jobId, sqlText, start);
        } catch (Exception e) {
            log.error("Lineage parsing failed: {}", e.getMessage(), e);
            return buildEmptyLineage(jobId, sqlText, start);
        }
    }

    // -----------------------------------------------------------------------
    // 私有解析方法
    // -----------------------------------------------------------------------

    private FieldLineage parseInsert(Matcher matcher, String jobId, String sqlText, long start) {
        String targetTable = matcher.group(1);
        String targetColumnsStr = matcher.group(2); // 可能为 null
        String projectionStr = matcher.group(3);
        String fromClause = matcher.group(4);

        List<SourceTableInfo> sources = parseFromClause(fromClause);
        List<FieldLineage.FieldMapping> mappings = parseProjection(projectionStr, targetColumnsStr, sources);

        String primarySource = sources.isEmpty() ? "unknown" : sources.get(0).tableName;

        return FieldLineage.builder()
                .lineageId(java.util.UUID.randomUUID().toString())
                .sourceTable(primarySource)
                .targetTable(targetTable)
                .fieldMappings(mappings)
                .jobId(jobId)
                .sqlText(sqlText)
                .extractedAt(java.time.Instant.now())
                .extractDurationMs(System.currentTimeMillis() - start)
                .build();
    }

    private FieldLineage parseCtas(Matcher matcher, String jobId, String sqlText, long start) {
        String targetTable = matcher.group(1);
        String projectionStr = matcher.group(2);
        String fromClause = matcher.group(3);

        List<SourceTableInfo> sources = parseFromClause(fromClause);
        List<FieldLineage.FieldMapping> mappings = parseProjection(projectionStr, null, sources);

        String primarySource = sources.isEmpty() ? "unknown" : sources.get(0).tableName;

        return FieldLineage.builder()
                .lineageId(java.util.UUID.randomUUID().toString())
                .sourceTable(primarySource)
                .targetTable(targetTable)
                .fieldMappings(mappings)
                .jobId(jobId)
                .sqlText(sqlText)
                .extractedAt(java.time.Instant.now())
                .extractDurationMs(System.currentTimeMillis() - start)
                .build();
    }

    /**
     * 解析 FROM 子句，提取源表与别名。
     *
     * <p>支持：
     * <ul>
     *   <li>单表：{@code source_table}</li>
     *   <li>单表带别名：{@code source_table src} 或 {@code source_table AS src}</li>
     *   <li>多表 JOIN：{@code a JOIN b ON a.id = b.id}（简化处理，提取所有表）</li>
     * </ul>
     */
    private List<SourceTableInfo> parseFromClause(String fromClause) {
        List<SourceTableInfo> sources = new ArrayList<>();
        if (fromClause == null) {
            return sources;
        }

        // 按 JOIN/WHERE/GROUP/ORDER 切分，只取第一个片段（JOIN 前的表）
        String[] segments = fromClause.split("(?i)\\s+(?:JOIN|INNER\\s+JOIN|LEFT\\s+JOIN|RIGHT\\s+JOIN|FULL\\s+JOIN|WHERE|GROUP|ORDER|HAVING|LIMIT)\\s+");
        for (String segment : segments) {
            segment = segment.trim();
            if (segment.isEmpty()) {
                continue;
            }
            Matcher m = SOURCE_TABLE_PATTERN.matcher(segment);
            if (m.find()) {
                String tableName = m.group(1);
                String alias = m.group(2);
                // 跳过 JOIN 关键字本身
                if (isJoinKeyword(tableName)) {
                    continue;
                }
                sources.add(new SourceTableInfo(tableName, alias));
            }
        }
        return sources;
    }

    /**
     * 解析 SELECT 投影列，构造字段映射。
     *
     * @param projectionStr SELECT 投影部分（逗号分隔）
     * @param targetColumnsStr INSERT 显式目标列（可能为 null，表示按 SELECT 顺序映射）
     * @param sources 源表列表（用于匹配别名）
     */
    private List<FieldLineage.FieldMapping> parseProjection(
            String projectionStr, String targetColumnsStr, List<SourceTableInfo> sources) {
        List<FieldLineage.FieldMapping> mappings = new ArrayList<>();
        if (projectionStr == null) {
            return mappings;
        }

        // 解析目标列
        List<String> targetColumns = new ArrayList<>();
        if (targetColumnsStr != null) {
            for (String col : targetColumnsStr.split(",")) {
                targetColumns.add(col.trim());
            }
        }

        // 解析投影列
        String[] projections = splitProjection(projectionStr);
        for (int i = 0; i < projections.length; i++) {
            String projection = projections[i].trim();
            String targetField = i < targetColumns.size()
                    ? targetColumns.get(i)
                    : inferTargetFieldName(projection);

            FieldLineage.FieldMapping mapping = parseSingleProjection(projection, targetField, sources);
            mappings.add(mapping);
        }
        return mappings;
    }

    /**
     * 解析单个投影列，识别转换类型。
     */
    private FieldLineage.FieldMapping parseSingleProjection(
            String projection, String targetField, List<SourceTableInfo> sources) {
        projection = projection.trim();

        // 简单字段引用：alias.field 或 table.field 或 field
        if (projection.matches("[\\w.]+")) {
            String sourceField = extractFieldName(projection);
            return FieldLineage.FieldMapping.builder()
                    .sourceField(sourceField)
                    .targetField(targetField)
                    .transformType("DIRECT")
                    .expression(null)
                    .build();
        }

        // 常量：'literal' 或 123 或 true
        if (projection.matches("(?i)('[^']*'|\\d+|true|false|null)")) {
            return FieldLineage.FieldMapping.builder()
                    .sourceField(null)
                    .targetField(targetField)
                    .transformType("CONSTANT")
                    .expression(projection)
                    .build();
        }

        // 聚合函数：SUM(field), COUNT(field), AVG(field), MAX(field), MIN(field)
        if (projection.matches("(?i)(SUM|COUNT|AVG|MAX|MIN)\\s*\\(.*\\)")) {
            String innerField = extractInnerField(projection);
            return FieldLineage.FieldMapping.builder()
                    .sourceField(innerField)
                    .targetField(targetField)
                    .transformType("AGGREGATE")
                    .expression(projection)
                    .build();
        }

        // 其他转换表达式：field + 1, CONCAT(a, b), CASE WHEN ... 等
        String firstField = extractFirstFieldReference(projection);
        return FieldLineage.FieldMapping.builder()
                .sourceField(firstField)
                .targetField(targetField)
                .transformType("TRANSFORM")
                .expression(projection)
                .build();
    }

    // -----------------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------------

    private String normalizeSql(String sql) {
        // 去除注释、多余空白、统一换行
        return sql.replaceAll("--[^\\n]*", " ")
                .replaceAll("/\\*.*?\\*/", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isJoinKeyword(String word) {
        return word.matches("(?i)(JOIN|INNER|LEFT|RIGHT|FULL|OUTER|CROSS|ON|WHERE)");
    }

    private String extractFieldName(String reference) {
        // alias.field → field；table.field → field；field → field
        int dot = reference.lastIndexOf('.');
        return dot >= 0 ? reference.substring(dot + 1) : reference;
    }

    private String inferTargetFieldName(String projection) {
        // expression AS field → field
        java.util.regex.Pattern asPattern = java.util.regex.Pattern.compile("(?i)AS\\s+(\\w+)$");
        Matcher m = asPattern.matcher(projection);
        if (m.find()) {
            return m.group(1);
        }
        // 简单字段引用 → 字段名
        if (projection.matches("[\\w.]+")) {
            return extractFieldName(projection);
        }
        // 其他：使用 _col0, _col1 等
        return "_col" + projection.hashCode();
    }

    private String extractInnerField(String expression) {
        Matcher m = Pattern.compile("\\(([^)]+)\\)").matcher(expression);
        if (m.find()) {
            return extractFieldName(m.group(1).trim());
        }
        return null;
    }

    private String extractFirstFieldReference(String expression) {
        Matcher m = Pattern.compile("([\\w]+\\.[\\w]+|[\\w]+)").matcher(expression);
        if (m.find()) {
            return extractFieldName(m.group(1));
        }
        return null;
    }

    private String[] splitProjection(String projectionStr) {
        // 简单按逗号切分（不处理嵌套括号内的逗号，复杂场景降级）
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : projectionStr.toCharArray()) {
            if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts.toArray(new String[0]);
    }

    private FieldLineage buildEmptyLineage(String jobId, String sqlText, long start) {
        return FieldLineage.builder()
                .lineageId(java.util.UUID.randomUUID().toString())
                .sourceTable("unknown")
                .targetTable("unknown")
                .fieldMappings(new ArrayList<>())
                .jobId(jobId)
                .sqlText(sqlText)
                .extractedAt(java.time.Instant.now())
                .extractDurationMs(System.currentTimeMillis() - start)
                .build();
    }

    /** 源表信息内部类 */
    private record SourceTableInfo(String tableName, String alias) {}
}