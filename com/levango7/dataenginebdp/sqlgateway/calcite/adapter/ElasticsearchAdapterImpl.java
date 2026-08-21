package com.shuqing.bigdata.sqlgateway.calcite.adapter;

import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;
import com.shuqing.bigdata.sqlgateway.calcite.rel.CustomRelNode;
import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Elasticsearch 数据源适配器实现——对接 Elasticsearch 检索引擎。
 *
 * <p>本类实现 {@link ElasticsearchAdapter} 接口，基于 {@link AbstractBaseAdapter} 框架
 * 提供 ES 特有的下推与 Cost 估算能力：</p>
 *
 * <p><b>方言转换（ES Query DSL）：</b>ES 不使用 SQL，下推"SQL"实际为 ES Query DSL JSON。
 * 本类将 SQL 谓词翻译为 ES Query DSL，将 GROUP BY 翻译为 ES Aggregation，
 * 将 ORDER BY 翻译为 ES sort，将 LIMIT/OFFSET 翻译为 ES from/size。</p>
 *
 * <p><b>DSL 转换示例：</b></p>
 * <pre>
 *   SQL:  age > 18 AND name LIKE '%张%'
 *   DSL:  {"bool":{"must":[{"range":{"age":{"gt":18}}},{"wildcard":{"name":"*张*"}}]}}
 *
 *   SQL:  GROUP BY category, sum(amount)
 *   DSL:  {"aggs":{"by_category":{"terms":{"field":"category"},"aggs":{"sum_amount":{"sum":{"field":"amount"}}}}}}
 * </pre>
 *
 * <p><b>下推能力：</b></p>
 * <ul>
 *   <li>查询 DSL 下推：将 filter 谓词转为 ES Query DSL</li>
 *   <li>聚合下推：将 GROUP BY + 聚合转为 ES Aggregation</li>
 *   <li>排序下推：将 ORDER BY 转为 ES sort 参数</li>
 *   <li>分页下推：将 LIMIT/OFFSET 转为 ES from/size</li>
 *   <li>全文检索下推：将 MATCH/QUERY 谓词转为 ES match query</li>
 * </ul>
 *
 * <p><b>Cost 模型：</b>ES 为倒排索引检索引擎，CPU Cost 极低（倒排索引查询），
 * IO Cost 低（索引文件 mmap），Network Cost 中等（分布式分片查询）。</p>
 *
 * <pre>
 *   cpuCost   = rows × 0.05（倒排索引查询 CPU 极低）
 *   ioCost    = (rows × rowSize / 64KB) × 0.5（索引 mmap IO 低）
 *   networkCost = rows × rowSize × 0.05（分布式分片查询）
 * </pre>
 *
 * @author shuqing-bigdata
 */
public class ElasticsearchAdapterImpl extends AbstractBaseAdapter implements ElasticsearchAdapter {

    /** 等值谓词：col = value */
    private static final Pattern EQUALITY_PATTERN =
            Pattern.compile("(\\w+)\\s*=\\s*([^=<>!\\s]+)");

    /** 范围谓词：col > value, col < value, col >= value, col <= value */
    private static final Pattern RANGE_PATTERN =
            Pattern.compile("(\\w+)\\s*(>=|<=|>|<)\\s*([^=<>!\\s]+)");

    /** LIKE 谓词：col LIKE '%pattern%' */
    private static final Pattern LIKE_PATTERN =
            Pattern.compile("(\\w+)\\s+LIKE\\s+['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);

    /** IN 谓词：col IN (v1, v2, ...) */
    private static final Pattern IN_PATTERN =
            Pattern.compile("(\\w+)\\s+IN\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);

    /** IS NULL / IS NOT NULL */
    private static final Pattern IS_NULL_PATTERN =
            Pattern.compile("(\\w+)\\s+IS\\s+NULL", Pattern.CASE_INSENSITIVE);
    private static final Pattern IS_NOT_NULL_PATTERN =
            Pattern.compile("(\\w+)\\s+IS\\s+NOT\\s+NULL", Pattern.CASE_INSENSITIVE);

    /** MATCH 谓词：MATCH(col, 'text') */
    private static final Pattern MATCH_PATTERN =
            Pattern.compile("MATCH\\s*\\(\\s*(\\w+)\\s*,\\s*['\"]([^'\"]*)['\"]\\s*\\)",
                    Pattern.CASE_INSENSITIVE);

    /** AND 连接符 */
    private static final Pattern AND_PATTERN =
            Pattern.compile("\\sAND\\s", Pattern.CASE_INSENSITIVE);

    /** OR 连接符 */
    private static final Pattern OR_PATTERN =
            Pattern.compile("\\sOR\\s", Pattern.CASE_INSENSITIVE);

    /** 可用索引集合 */
    private final Set<String> availableIndices = new LinkedHashSet<>();

    /**
     * 构造 Elasticsearch 适配器。
     *
     * @param config 数据源配置（type 必须为 ELASTICSEARCH）
     */
    public ElasticsearchAdapterImpl(DataSourceConfig config) {
        super(config);
        if (config.getType() != DataSourceConfig.Type.ELASTICSEARCH) {
            throw new IllegalArgumentException("数据源类型必须为 ELASTICSEARCH, 实际: " + config.getType());
        }
        // 从配置加载可用索引
        String indicesStr = config.getProperties().get("indices");
        if (indicesStr != null && !indicesStr.isBlank()) {
            for (String idx : indicesStr.split(",")) {
                availableIndices.add(idx.trim());
            }
        }
    }

    // ===================== 方言与下推 SQL（DSL）生成 =====================

    @Override
    public SqlDialect getDialect() {
        // ES 不使用 SQL，返回 ANSI 作为占位
        SqlDialect dialect = getDataSourceConfig().getDialect();
        return dialect == null ? SqlDialect.ANSI : dialect;
    }

    @Override
    protected String buildPushedSql(CustomRelNode relNode, PushDownContext context) {
        String tableName = extractTableName(relNode);
        if (tableName == null) {
            return null;
        }

        // 1. 查询 DSL（谓词下推）
        String condition = extractCondition(relNode);
        String queryDsl = condition != null ? toQueryDsl(condition) : "{\"match_all\":{}}";

        // 2. 聚合 DSL
        List<String> groupBy = extractGroupBy(relNode);
        List<String> aggFuncs = extractAggFuncs(relNode);
        String aggDsl = (!groupBy.isEmpty() || !aggFuncs.isEmpty())
                ? toAggregationDsl(groupBy, aggFuncs) : null;

        // 3. 排序 DSL
        List<String> sortKeys = extractSortKeys(relNode);
        String sortDsl = !sortKeys.isEmpty() ? toSortDsl(sortKeys) : null;

        // 4. 分页 DSL
        long[] limitOffset = extractLimitOffset(relNode);
        String pageDsl = limitOffset != null ? toPaginationDsl(limitOffset[0], limitOffset[1]) : null;

        // 5. 组装完整 ES 请求体
        return buildEsRequestBody(tableName, queryDsl, aggDsl, sortDsl, pageDsl);
    }

    /**
     * 组装 ES 请求体 JSON。
     *
     * @param index    索引名
     * @param queryDsl 查询 DSL
     * @param aggDsl   聚合 DSL（null 表示无聚合）
     * @param sortDsl  排序 DSL（null 表示无排序）
     * @param pageDsl  分页 DSL（null 表示无分页）
     * @return ES 请求体 JSON
     */
    private String buildEsRequestBody(String index, String queryDsl,
                                      String aggDsl, String sortDsl, String pageDsl) {
        StringBuilder sb = new StringBuilder();
        sb.append("GET /").append(index).append("/_search\n");
        sb.append("{");
        sb.append("\"query\":").append(queryDsl);
        if (aggDsl != null) {
            sb.append(",\"aggs\":").append(aggDsl);
        }
        if (sortDsl != null) {
            sb.append(",\"sort\":").append(sortDsl);
        }
        if (pageDsl != null) {
            sb.append(",").append(pageDsl);
        }
        sb.append("}");
        return sb.toString();
    }

    // ===================== ES DSL 转换 =====================

    @Override
    public String toQueryDsl(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return "{\"match_all\":{}}";
        }

        String trimmed = predicate.trim();

        // 检查 OR 连接 → bool.should
        if (OR_PATTERN.matcher(trimmed).find()) {
            return buildBoolShouldDsl(trimmed);
        }

        // 按 AND 拆分 → bool.must
        if (AND_PATTERN.matcher(trimmed).find()) {
            return buildBoolMustDsl(trimmed);
        }

        // 单个谓词
        return singlePredicateToDsl(trimmed);
    }

    /**
     * 将 AND 连接的谓词转为 bool.must DSL。
     *
     * @param predicate AND 连接的谓词
     * @return bool.must DSL
     */
    private String buildBoolMustDsl(String predicate) {
        String[] parts = predicate.split("(?i)\\sAND\\s");
        List<String> clauses = new ArrayList<>();
        for (String part : parts) {
            String dsl = singlePredicateToDsl(part.trim());
            if (dsl != null) {
                clauses.add(dsl);
            }
        }
        return "{\"bool\":{\"must\":[" + String.join(",", clauses) + "]}}";
    }

    /**
     * 将 OR 连接的谓词转为 bool.should DSL。
     *
     * @param predicate OR 连接的谓词
     * @return bool.should DSL
     */
    private String buildBoolShouldDsl(String predicate) {
        String[] parts = predicate.split("(?i)\\sOR\\s");
        List<String> clauses = new ArrayList<>();
        for (String part : parts) {
            String dsl = toQueryDsl(part.trim());
            if (dsl != null) {
                clauses.add(dsl);
            }
        }
        return "{\"bool\":{\"should\":[" + String.join(",", clauses) + "],\"minimum_should_match\":1}}";
    }

    /**
     * 将单个原子谓词转为 ES DSL。
     *
     * @param predicate 原子谓词
     * @return ES DSL
     */
    private String singlePredicateToDsl(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return "{\"match_all\":{}}";
        }

        // 1. MATCH 谓词 → match query
        Matcher matchMatcher = MATCH_PATTERN.matcher(predicate);
        if (matchMatcher.find()) {
            String field = matchMatcher.group(1);
            String text = matchMatcher.group(2);
            return "{\"match\":{\"" + field + "\":\"" + escapeJson(text) + "\"}}";
        }

        // 2. IS NOT NULL → exists query
        Matcher notNullMatcher = IS_NOT_NULL_PATTERN.matcher(predicate);
        if (notNullMatcher.find()) {
            return "{\"exists\":{\"field\":\"" + notNullMatcher.group(1) + "\"}}";
        }

        // 3. IS NULL → must_not exists
        Matcher isNullMatcher = IS_NULL_PATTERN.matcher(predicate);
        if (isNullMatcher.find()) {
            return "{\"bool\":{\"must_not\":[{\"exists\":{\"field\":\""
                    + isNullMatcher.group(1) + "\"}}]}}";
        }

        // 4. LIKE 谓词 → wildcard query
        Matcher likeMatcher = LIKE_PATTERN.matcher(predicate);
        if (likeMatcher.find()) {
            String field = likeMatcher.group(1);
            String pattern = likeMatcher.group(2).replace('%', '*').replace('_', '?');
            return "{\"wildcard\":{\"" + field + "\":\"" + escapeJson(pattern) + "\"}}";
        }

        // 5. IN 谓词 → terms query
        Matcher inMatcher = IN_PATTERN.matcher(predicate);
        if (inMatcher.find()) {
            String field = inMatcher.group(1);
            String[] values = inMatcher.group(2).split(",");
            List<String> valueList = new ArrayList<>();
            for (String v : values) {
                valueList.add("\"" + escapeJson(v.trim()) + "\"");
            }
            return "{\"terms\":{\"" + field + "\":[" + String.join(",", valueList) + "]}}";
        }

        // 6. 范围谓词 → range query
        Matcher rangeMatcher = RANGE_PATTERN.matcher(predicate);
        if (rangeMatcher.find()) {
            String field = rangeMatcher.group(1);
            String op = rangeMatcher.group(2);
            String value = rangeMatcher.group(3);
            String esOp = op.equals(">=") ? "gte" : op.equals("<=") ? "lte"
                    : op.equals(">") ? "gt" : "lt";
            return "{\"range\":{\"" + field + "\":{\"" + esOp + "\":"
                    + jsonValue(value) + "}}}";
        }

        // 7. 等值谓词 → term query
        Matcher eqMatcher = EQUALITY_PATTERN.matcher(predicate);
        if (eqMatcher.find()) {
            String field = eqMatcher.group(1);
            String value = eqMatcher.group(2);
            return "{\"term\":{\"" + field + "\":" + jsonValue(value) + "}}";
        }

        // 8. 无法识别 → match_all
        return "{\"match_all\":{}}";
    }

    /**
     * 将值转为 JSON 格式（字符串加引号，数字不加）。
     *
     * @param value 原始值
     * @return JSON 格式值
     */
    private String jsonValue(String value) {
        if (value == null) {
            return "null";
        }
        String trimmed = value.trim();
        // 移除引号
        if ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
            return "\"" + escapeJson(trimmed) + "\"";
        }
        // 数字
        try {
            Double.parseDouble(trimmed);
            return trimmed;
        } catch (NumberFormatException e) {
            return "\"" + escapeJson(trimmed) + "\"";
        }
    }

    /**
     * JSON 字符串转义。
     *
     * @param s 原始字符串
     * @return 转义后的字符串
     */
    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public String toAggregationDsl(List<String> groupBy, List<String> aggFuncs) {
        if ((groupBy == null || groupBy.isEmpty()) && (aggFuncs == null || aggFuncs.isEmpty())) {
            return "{}";
        }

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;

        // GROUP BY → terms aggregation
        if (groupBy != null && !groupBy.isEmpty()) {
            String field = groupBy.get(0);
            sb.append("\"by_").append(field).append("\":{\"terms\":{\"field\":\"").append(field).append("\"}");
            // 嵌套聚合函数
            if (aggFuncs != null && !aggFuncs.isEmpty()) {
                sb.append(",\"aggs\":{");
                sb.append(buildAggFuncsDsl(aggFuncs));
                sb.append("}");
            }
            sb.append("}");
            first = false;
        } else if (aggFuncs != null && !aggFuncs.isEmpty()) {
            // 无 GROUP BY，仅聚合函数
            sb.append(buildAggFuncsDsl(aggFuncs));
            first = false;
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * 构造聚合函数 DSL。
     *
     * @param aggFuncs 聚合函数列表（如 ["sum(amount)", "count(*)"]）
     * @return 聚合函数 DSL
     */
    private String buildAggFuncsDsl(List<String> aggFuncs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < aggFuncs.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            String aggFunc = aggFuncs.get(i).trim();
            sb.append(parseAggFuncDsl(aggFunc));
        }
        return sb.toString();
    }

    /**
     * 解析单个聚合函数为 ES DSL。
     *
     * @param aggFunc 聚合函数（如 "sum(amount)"）
     * @return ES 聚合 DSL（如 "sum_amount":{"sum":{"field":"amount"}}）
     */
    private String parseAggFuncDsl(String aggFunc) {
        Pattern p = Pattern.compile("(?i)(count|sum|avg|min|max)\\s*\\(\\s*([^)]*)\\s*\\)");
        Matcher m = p.matcher(aggFunc);
        if (m.find()) {
            String func = m.group(1).toLowerCase();
            String arg = m.group(2).trim();
            String name = func + "_" + (arg.equals("*") ? "all" : arg);
            if (func.equals("count")) {
                return "\"" + name + "\":{\"value_count\":{\"script\":{\"source\":\"1\"}}}";
            }
            if (arg.equals("*")) {
                return "\"" + name + "\":{\"value_count\":{\"script\":{\"source\":\"1\"}}}";
            }
            return "\"" + name + "\":{\"" + func + "\":{\"field\":\"" + arg + "\"}}";
        }
        // 无法解析
        return "\"unknown\":{\"value_count\":{\"script\":{\"source\":\"1\"}}}";
    }

    @Override
    public String toSortDsl(List<String> sortKeys) {
        if (sortKeys == null || sortKeys.isEmpty()) {
            return "[]";
        }
        List<String> clauses = new ArrayList<>();
        for (String key : sortKeys) {
            String trimmed = key.trim();
            String field;
            String order = "asc";
            // 解析 "field DESC" / "field ASC"
            String[] parts = trimmed.split("\\s+");
            field = parts[0];
            if (parts.length > 1) {
                order = parts[1].toLowerCase();
            }
            clauses.add("{\"" + field + "\":{\"order\":\"" + order + "\"}}");
        }
        return "[" + String.join(",", clauses) + "]";
    }

    @Override
    public String toPaginationDsl(long limit, long offset) {
        return "{\"from\":" + Math.max(0, offset) + ",\"size\":" + Math.max(0, limit) + "}";
    }

    @Override
    public boolean isIndexAvailable(String indexName) {
        if (indexName == null) {
            return false;
        }
        return availableIndices.contains(indexName);
    }

    /**
     * 声明可用索引（用于注册与测试）。
     *
     * @param indexNames 索引名集合
     */
    public void declareAvailableIndices(String... indexNames) {
        for (String idx : indexNames) {
            if (idx != null) {
                availableIndices.add(idx);
            }
        }
    }

    // ===================== 辅助方法：从 RelNode 提取 ES 特有信息 =====================

    /**
     * 从 RelNode 子树提取 GROUP BY 列。
     */
    private List<String> extractGroupBy(CustomRelNode relNode) {
        if (relNode == null) {
            return Collections.emptyList();
        }
        if (relNode.getOp() == CustomRelNode.Op.AGGREGATE) {
            return relNode.getProjects();
        }
        for (CustomRelNode child : relNode.getChildren()) {
            List<String> groupBy = extractGroupBy(child);
            if (!groupBy.isEmpty()) {
                return groupBy;
            }
        }
        return Collections.emptyList();
    }

    /**
     * 从 RelNode 子树提取聚合函数列表。
     */
    private List<String> extractAggFuncs(CustomRelNode relNode) {
        if (relNode == null) {
            return Collections.emptyList();
        }
        List<String> aggFuncs = new ArrayList<>();
        if (relNode.getOp() == CustomRelNode.Op.AGGREGATE
                && relNode.getRemark() != null) {
            for (String part : relNode.getRemark().split(",")) {
                if (part.trim().matches("(?i).*\\(.*\\).*")) {
                    aggFuncs.add(part.trim());
                }
            }
        }
        for (CustomRelNode child : relNode.getChildren()) {
            aggFuncs.addAll(extractAggFuncs(child));
        }
        return aggFuncs;
    }

    /**
     * 从 RelNode 子树提取排序键。
     */
    private List<String> extractSortKeys(CustomRelNode relNode) {
        if (relNode == null) {
            return Collections.emptyList();
        }
        if (relNode.getOp() == CustomRelNode.Op.SORT) {
            return relNode.getProjects();
        }
        for (CustomRelNode child : relNode.getChildren()) {
            List<String> sortKeys = extractSortKeys(child);
            if (!sortKeys.isEmpty()) {
                return sortKeys;
            }
        }
        return Collections.emptyList();
    }

    /**
     * 从 RelNode 子树提取 LIMIT/OFFSET。
     *
     * @return [limit, offset]，null 表示无 LIMIT
     */
    private long[] extractLimitOffset(CustomRelNode relNode) {
        if (relNode == null) {
            return null;
        }
        if (relNode.getOp() == CustomRelNode.Op.LIMIT) {
            // LIMIT 节点用 estimatedRows 存储 limit，estimatedCost 存储 offset
            return new long[]{(long) relNode.getEstimatedRows(), (long) relNode.getEstimatedCost()};
        }
        for (CustomRelNode child : relNode.getChildren()) {
            long[] lo = extractLimitOffset(child);
            if (lo != null) {
                return lo;
            }
        }
        return null;
    }

    // ===================== 统计信息加载 =====================

    @Override
    protected TableStatistics loadStatistics(String tableName) {
        Map<String, String> props = getDataSourceConfig().getProperties();
        long rowCount = parseLong(props.get("stats." + tableName + ".rowCount"),
                TableStatistics.DEFAULT_ROW_COUNT);
        int rowSize = parseInt(props.get("stats." + tableName + ".rowSizeBytes"), 200);
        int partitionCount = parseInt(props.get("stats." + tableName + ".shardCount"), 1);
        return new TableStatistics(rowCount, null, rowSize, partitionCount);
    }

    private long parseLong(String s, long defaultValue) {
        if (s == null || s.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int parseInt(String s, int defaultValue) {
        if (s == null || s.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ===================== Cost 因子 =====================

    @Override
    protected double cpuCostFactor() {
        // 倒排索引查询，CPU Cost 极低
        return 0.05;
    }

    @Override
    protected double ioCostFactor() {
        // 索引 mmap，IO Cost 低
        return 0.5;
    }

    @Override
    protected double networkCostFactor() {
        // 分布式分片查询，Network Cost 中等
        return 0.05;
    }
}