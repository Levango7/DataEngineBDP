package com.levango7.dataenginebdp.sqlgateway.calcite.rule;

import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 谓词下推规则——将 Filter 节点中的可下推谓词下推到 TableScan 之上。
 *
 * <p>本规则继承 {@link PushDownRule}，匹配 {@code FILTER} 节点，将谓词条件按类型分类，
 * 通过数据源适配器判断每类谓词是否可下推：</p>
 * <ul>
 *   <li><b>等值谓词</b>（=）：所有数据源均支持</li>
 *   <li><b>范围谓词</b>（&lt;, &gt;, &lt;=, &gt;=, BETWEEN）：所有数据源均支持</li>
 *   <li><b>IN 谓词</b>（IN/NOT IN）：所有数据源均支持</li>
 *   <li><b>LIKE 谓词</b>（LIKE/NOT LIKE）：Iceberg/Doris/Trino/ES 支持，IoTDB 不支持</li>
 *   <li><b>NULL 检查</b>（IS NULL/IS NOT NULL）：所有数据源均支持</li>
 *   <li><b>不支持的谓词</b>（UDF/OR/子查询/复杂表达式）：自动保留在 Filter 节点</li>
 * </ul>
 *
 * <p>下推流程：</p>
 * <pre>
 *   Filter(condition: "id = 100 AND age > 18 AND UDF(name) = 'x'")
 *     │
 *     ▼  1. 提取并分类谓词
 *   [{id = 100, EQUALITY}, {age > 18, RANGE}, {UDF(name) = 'x', UNSUPPORTED}]
 *     │
 *     ▼  2. 检查适配器支持 + 跨源判定
 *   pushable = [id = 100, age > 18]
 *   remaining = [UDF(name) = 'x']
 *     │
 *     ▼  3. 下推 + 保留
 *   Filter(condition: "UDF(name) = 'x'")
 *     └─ TableScan(table: t, pushedFilter: "id = 100 AND age > 18")
 * </pre>
 *
 * <p>语义等价性保证：下推后的查询结果与原查询等价。可下推谓词在数据源执行，
 * 不可下推谓词在联邦层执行，两者 AND 连接的结果与原 Filter 条件等价。</p>
 *
 * <p>下推率统计：每次 {@link #onMatch} 执行后，统计信息累积到 {@link #statistics}，
 * 可通过 {@link #getStatistics()} 获取总体与分类下推率。</p>
 *
 * @author shuqing-bigdata
 */
public class PredicatePushDownRule extends PushDownRule {

    /** 规则短名 */
    public static final String RULE_NAME = "PredicatePushDown";

    /** 谓词下推统计器 */
    private final PushDownStatistics statistics;

    // ===================== 谓词识别正则 =====================

    /** AND 连接符（不区分大小写，词边界） */
    private static final Pattern AND_PATTERN =
            Pattern.compile("\\sAND\\s", Pattern.CASE_INSENSITIVE);

    /** OR 连接符（出现 OR 即标记为 UNSUPPORTED） */
    private static final Pattern OR_PATTERN =
            Pattern.compile("\\sOR\\s", Pattern.CASE_INSENSITIVE);

    /** 等值谓词：col = value（排除 &lt;=、&gt;=、!=） */
    private static final Pattern EQUALITY_PATTERN =
            Pattern.compile("(?i)\\w+\\s*=\\s*[^=<>!]+");

    /** 范围谓词：col < value, col > value, col <= value, col >= value */
    private static final Pattern RANGE_PATTERN =
            Pattern.compile("(?i)\\w+\\s*(?:<=|>=|<|>)\\s*[^=<>!]+");

    /** BETWEEN 谓词 */
    private static final Pattern BETWEEN_PATTERN =
            Pattern.compile("(?i)\\w+\\s+BETWEEN\\s+.+\\s+AND\\s+.+");

    /** IN 谓词 */
    private static final Pattern IN_PATTERN =
            Pattern.compile("(?i)\\w+\\s+(?:NOT\\s+)?IN\\s*\\([^)]+\\)");

    /** LIKE 谓词 */
    private static final Pattern LIKE_PATTERN =
            Pattern.compile("(?i)\\w+\\s+(?:NOT\\s+)?LIKE\\s+['\"][^'\"]*['\"]");

    /** IS NULL / IS NOT NULL */
    private static final Pattern IS_NULL_PATTERN =
            Pattern.compile("(?i)\\w+\\s+IS\\s+(?:NOT\\s+)?NULL");

    /** UDF 调用（函数名(参数) 形式，排除 IN/NOT IN/BETWEEN/LIKE/IS 等关键字） */
    private static final Pattern UDF_PATTERN =
            Pattern.compile("(?i)\\b(?!IN\\b|NOT\\b|BETWEEN\\b|LIKE\\b|IS\\b|AND\\b|OR\\b)"
                    + "[a-zA-Z_]\\w*\\s*\\([^)]*\\)");

    /** 子查询（EXISTS/IN (SELECT...)） */
    private static final Pattern SUBQUERY_PATTERN =
            Pattern.compile("(?i)\\b(?:EXISTS|IN)\\s*\\(\\s*SELECT\\b");

    /** 不等谓词 != 或 <> */
    private static final Pattern NOT_EQUAL_PATTERN =
            Pattern.compile("(?i)\\w+\\s*(?:!=|<>)\\s*[^=<>!]+");

    /**
     * 构造谓词下推规则。
     *
     * @param adapter 关联的数据源适配器
     */
    public PredicatePushDownRule(BaseAdapter adapter) {
        this(adapter, new PushDownStatistics());
    }

    /**
     * 构造谓词下推规则（指定统计器）。
     *
     * @param adapter    关联的数据源适配器
     * @param statistics 下推率统计器
     */
    public PredicatePushDownRule(BaseAdapter adapter, PushDownStatistics statistics) {
        super(RULE_NAME,
                "谓词下推规则：将等值/范围/IN/LIKE/IS NULL 谓词下推到数据源，"
                        + "UDF/OR/子查询等不支持的谓词保留在 Filter",
                Objects.requireNonNull(adapter, "adapter"),
                CustomRelNode.Op.FILTER);
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    /**
     * 获取下推率统计器。
     *
     * @return 统计器实例
     */
    public PushDownStatistics getStatistics() {
        return statistics;
    }

    // ===================== 谓词分类 =====================

    /**
     * 将 Filter 条件字符串拆分为独立谓词列表（按 AND 拆分）。
     *
     * <p>拆分规则：按顶层 AND 连接符拆分（不进入括号内的 AND，也不拆分 BETWEEN 内的 AND）。
     * 若条件中含顶层 OR（不在括号内），则整个条件视为一个不可拆分的谓词（分类为 UNSUPPORTED）。</p>
     *
     * @param condition Filter 条件字符串
     * @return 谓词列表
     */
    public List<String> extractPredicates(String condition) {
        if (condition == null || condition.isBlank()) {
            return Collections.emptyList();
        }
        String trimmed = condition.trim();
        // 如果含顶层 OR（不在括号内），整体作为一个谓词（分类为 UNSUPPORTED）
        if (hasTopLevelOr(trimmed)) {
            return Collections.singletonList(trimmed);
        }
        // 按顶层 AND 拆分（不进入括号，不拆分 BETWEEN 内的 AND）
        List<String> predicates = new ArrayList<>();
        splitByTopLevelAnd(trimmed, predicates);
        return predicates;
    }

    /**
     * 检测条件中是否含顶层 OR（不在括号内的 OR）。
     *
     * @param condition 条件字符串
     * @return 含顶层 OR 返回 true
     */
    private boolean hasTopLevelOr(String condition) {
        int depth = 0;
        for (int i = 0; i < condition.length(); i++) {
            char c = condition.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && i > 0 && Character.isWhitespace(c)
                    && isOrAt(condition, i + 1)) {
                return true;
            }
        }
        return false;
    }

    /** 判断 condition 从 pos 开始是否为 "OR"（大小写无关） */
    private boolean isOrAt(String condition, int pos) {
        return pos + 2 <= condition.length()
                && condition.substring(pos, pos + 2).equalsIgnoreCase("OR")
                && (pos + 2 == condition.length()
                        || Character.isWhitespace(condition.charAt(pos + 2)));
    }

    /**
     * 按顶层 AND 拆分条件（不进入括号，不拆分 BETWEEN 内的 AND）。
     *
     * @param condition  条件字符串
     * @param result     拆分结果列表
     */
    private void splitByTopLevelAnd(String condition, List<String> result) {
        int depth = 0;
        int last = 0;
        boolean inBetween = false;
        for (int i = 0; i < condition.length(); i++) {
            char c = condition.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && Character.isWhitespace(c)
                    && isBetweenAt(condition, i + 1)) {
                // 进入 BETWEEN 模式，下一个 AND 不拆分
                inBetween = true;
            } else if (depth == 0 && Character.isWhitespace(c)
                    && isAndAt(condition, i + 1)) {
                if (inBetween) {
                    // BETWEEN 内的 AND，跳过，重置 inBetween
                    inBetween = false;
                    i += 3; // 跳过 AND
                    continue;
                }
                String part = condition.substring(last, i).trim();
                if (!part.isEmpty()) {
                    result.add(part);
                }
                last = i + 4; // 跳过 " AND"
                i += 3; // 跳过 AND 三个字符（循环 i++ 跳过空格）
            }
        }
        String tail = condition.substring(last).trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
    }

    /** 判断 condition 从 pos 开始是否为 "BETWEEN"（大小写无关） */
    private boolean isBetweenAt(String condition, int pos) {
        return pos + 7 <= condition.length()
                && condition.substring(pos, pos + 7).equalsIgnoreCase("BETWEEN")
                && (pos + 7 == condition.length()
                        || Character.isWhitespace(condition.charAt(pos + 7)));
    }

    /** 判断 condition 从 pos 开始是否为 "AND"（大小写无关） */
    private boolean isAndAt(String condition, int pos) {
        return pos + 3 <= condition.length()
                && condition.substring(pos, pos + 3).equalsIgnoreCase("AND")
                && (pos + 3 == condition.length()
                        || Character.isWhitespace(condition.charAt(pos + 3)));
    }

    /**
     * 对单个谓词进行类型分类。
     *
     * @param predicate 谓词字符串
     * @return 谓词类型
     */
    public PredicateType classifyPredicate(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return PredicateType.UNSUPPORTED;
        }
        String trimmed = predicate.trim();

        // 1. 子查询 → UNSUPPORTED
        if (SUBQUERY_PATTERN.matcher(trimmed).find()) {
            return PredicateType.UNSUPPORTED;
        }
        // 2. OR 连接 → UNSUPPORTED
        if (OR_PATTERN.matcher(trimmed).find()) {
            return PredicateType.UNSUPPORTED;
        }
        // 3. UDF 调用 → UNSUPPORTED（如 UDF(name) = 'x'）
        if (UDF_PATTERN.matcher(trimmed).find()) {
            return PredicateType.UNSUPPORTED;
        }
        // 4. 不等谓词 != / <> → UNSUPPORTED（部分数据源不支持，保守保留）
        if (NOT_EQUAL_PATTERN.matcher(trimmed).find()) {
            return PredicateType.UNSUPPORTED;
        }
        // 5. IS NULL / IS NOT NULL
        if (IS_NULL_PATTERN.matcher(trimmed).find()) {
            return PredicateType.IS_NULL;
        }
        // 6. IN / NOT IN
        if (IN_PATTERN.matcher(trimmed).find()) {
            return PredicateType.IN;
        }
        // 7. LIKE / NOT LIKE
        if (LIKE_PATTERN.matcher(trimmed).find()) {
            return PredicateType.LIKE;
        }
        // 8. BETWEEN → RANGE
        if (BETWEEN_PATTERN.matcher(trimmed).find()) {
            return PredicateType.RANGE;
        }
        // 9. 范围谓词 <, >, <=, >=
        if (RANGE_PATTERN.matcher(trimmed).find()) {
            return PredicateType.RANGE;
        }
        // 10. 等值谓词 =
        if (EQUALITY_PATTERN.matcher(trimmed).find()) {
            return PredicateType.EQUALITY;
        }
        // 11. 其他无法识别 → UNSUPPORTED
        return PredicateType.UNSUPPORTED;
    }

    /**
     * 将谓词列表按类型分类。
     *
     * @param predicates 谓词列表
     * @return 分类映射：谓词类型 → 谓词列表
     */
    public Map<PredicateType, List<String>> classifyPredicates(List<String> predicates) {
        Map<PredicateType, List<String>> classified = new LinkedHashMap<>();
        for (PredicateType type : PredicateType.values()) {
            classified.put(type, new ArrayList<>());
        }
        if (predicates == null) {
            return classified;
        }
        for (String predicate : predicates) {
            PredicateType type = classifyPredicate(predicate);
            classified.get(type).add(predicate);
        }
        return classified;
    }

    // ===================== 适配器支持判定 =====================

    /**
     * 判断指定谓词类型在指定适配器上是否可下推。
     *
     * <p>下推支持矩阵：</p>
     * <table>
     *   <caption>表：谓词下推支持矩阵</caption>
     *   <tr><th>谓词类型</th><th>ICEBERG</th><th>DORIS</th><th>TRINO</th><th>IOTDB</th><th>ES</th></tr>
     *   <tr><td>EQUALITY</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td></tr>
     *   <tr><td>RANGE</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td></tr>
     *   <tr><td>IN</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td></tr>
     *   <tr><td>LIKE</td><td>✓</td><td>✓</td><td>✓</td><td>✗</td><td>✓</td></tr>
     *   <tr><td>IS_NULL</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td></tr>
     *   <tr><td>UNSUPPORTED</td><td>✗</td><td>✗</td><td>✗</td><td>✗</td><td>✗</td></tr>
     * </table>
     *
     * @param predicateType 谓词类型
     * @param adapter       数据源适配器
     * @return {@code true} 表示该类型谓词在该适配器上可下推
     */
    public boolean isPushable(PredicateType predicateType, BaseAdapter adapter) {
        if (predicateType == null || adapter == null) {
            return false;
        }
        if (!predicateType.isPushable()) {
            return false;
        }
        DataSourceConfig.Type sourceType = adapter.getAdapterType();
        // IoTDB 不支持 LIKE 谓词下推（时序数据库无通配符匹配语义）
        if (sourceType == DataSourceConfig.Type.IOTDB && predicateType == PredicateType.LIKE) {
            return false;
        }
        // 检查数据源配置是否启用下推
        DataSourceConfig dsConfig = adapter.getDataSourceConfig();
        if (dsConfig != null && !dsConfig.isPushDownEnabled()) {
            return false;
        }
        return true;
    }

    // ===================== 下推执行 =====================

    /**
     * 当规则匹配成功时执行下推改写。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>提取 Filter 条件并拆分为谓词列表</li>
     *   <li>按类型分类谓词</li>
     *   <li>通过适配器判定每类谓词是否可下推</li>
     *   <li>可下推谓词下推到 TableScan（标记 pushedFilter）</li>
     *   <li>不可下推谓词保留在 Filter 节点</li>
     *   <li>统计下推率</li>
     * </ol>
     *
     * @param call 规则调用上下文
     */
    @Override
    public void onMatch(RuleCall call) {
        CustomRelNode filter = call.getRoot();
        if (filter == null || filter.getOp() != CustomRelNode.Op.FILTER) {
            return;
        }

        String condition = filter.getCondition();
        if (condition == null || condition.isBlank()) {
            return;
        }

        // 1. 提取谓词
        List<String> predicates = extractPredicates(condition);
        if (predicates.isEmpty()) {
            return;
        }

        // 2. 分类谓词
        Map<PredicateType, List<String>> classified = classifyPredicates(predicates);

        // 3. 区分可下推与保留谓词
        List<String> pushablePredicates = new ArrayList<>();
        List<String> remainingPredicates = new ArrayList<>();
        DataSourceConfig.Type sourceType = getAdapter().getAdapterType();

        for (Map.Entry<PredicateType, List<String>> entry : classified.entrySet()) {
            PredicateType type = entry.getKey();
            List<String> preds = entry.getValue();
            boolean pushable = isPushable(type, getAdapter());
            for (String pred : preds) {
                if (pushable) {
                    pushablePredicates.add(pred);
                    statistics.recordPredicate(sourceType, type, true, null, pred);
                } else {
                    String reason = buildRemainingReason(type, pred);
                    remainingPredicates.add(pred);
                    statistics.recordPredicate(sourceType, type, false, reason, pred);
                }
            }
        }

        // 4. 执行下推改写
        if (pushablePredicates.isEmpty()) {
            // 无可下推谓词，保持原 Filter
            filter.setPushDownStatus(CustomRelNode.PushDownStatus.NOT_PUSHED);
            filter.setPushDownReason("所有谓词均不可下推");
            return;
        }

        // 构造下推后的 TableScan（在子节点上标记 pushedFilter）
        String pushedFilter = String.join(" AND ", pushablePredicates);
        CustomRelNode pushedScan = buildPushedScan(filter, pushedFilter);

        if (remainingPredicates.isEmpty()) {
            // 全部谓词下推，移除 Filter 节点
            pushedScan.setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
            pushedScan.addPushedOperation("filter: " + pushedFilter);
            call.transformTo(pushedScan);
        } else {
            // 部分下推：保留剩余谓词的 Filter
            String remainingCondition = String.join(" AND ", remainingPredicates);
            CustomRelNode newFilter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition(remainingCondition)
                    .setPushDownStatus(CustomRelNode.PushDownStatus.PARTIALLY_PUSHED)
                    .setPushDownReason("部分谓词保留：" + remainingReasons(remainingPredicates));
            newFilter.addPushedOperation("pushed: " + pushedFilter);
            newFilter.addChild(pushedScan);
            call.transformTo(newFilter);
        }
    }

    /**
     * 构造下推后的 TableScan 节点。
     *
     * @param filter       原 Filter 节点
     * @param pushedFilter 已下推的谓词条件
     * @return 标记了下推谓词的 TableScan
     */
    private CustomRelNode buildPushedScan(CustomRelNode filter, String pushedFilter) {
        // Filter 的子节点应为 TableScan
        if (filter.getChildren().isEmpty()) {
            // 无子节点，构造一个虚拟 TableScan
            CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setCondition(pushedFilter)
                    .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
            scan.addPushedOperation("filter: " + pushedFilter);
            return scan;
        }
        CustomRelNode child = filter.getChildren().get(0);
        // 复制子节点并附加下推谓词
        CustomRelNode pushedScan = CustomRelNode.of(child.getOp())
                .setTableName(child.getTableName())
                .setSourceName(child.getSourceName())
                .setCondition(mergeConditions(child.getCondition(), pushedFilter))
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
        for (CustomRelNode grandChild : child.getChildren()) {
            pushedScan.addChild(greatCopy(greatCopy(grandChild)));
        }
        pushedScan.addPushedOperation("filter: " + pushedFilter);
        return pushedScan;
    }

    /** 简化的节点复制（浅拷贝子节点引用） */
    private CustomRelNode greatCopy(CustomRelNode node) {
        if (node == null) {
            return null;
        }
        return node;
    }

    /** 合并两个条件（已存在条件 AND 新条件） */
    private String mergeConditions(String existing, String pushed) {
        if (existing == null || existing.isBlank()) {
            return pushed;
        }
        if (pushed == null || pushed.isBlank()) {
            return existing;
        }
        return existing + " AND " + pushed;
    }

    /** 构造保留原因 */
    private String buildRemainingReason(PredicateType type, String predicate) {
        return switch (type) {
            case UNSUPPORTED -> "不支持的谓词类型: " + predicate;
            case LIKE -> "LIKE 谓词在当前数据源不支持: " + predicate;
            default -> "谓词不可下推: " + predicate;
        };
    }

    /** 拼接保留原因摘要 */
    private String remainingReasons(List<String> remaining) {
        if (remaining.size() <= 3) {
            return String.join("; ", remaining);
        }
        return remaining.get(0) + "; " + remaining.get(1) + "; ...共" + remaining.size() + "个";
    }

    // ===================== 批量下推便捷方法 =====================

    /**
     * 对一组谓词执行下推分析，返回下推结果（不修改 RelNode 树）。
     *
     * <p>本方法供测试与统计使用，不触发 RelNode 改写。</p>
     *
     * @param condition Filter 条件字符串
     * @param adapter   数据源适配器
     * @return 下推分析结果
     */
    public PushDownAnalysis analyze(String condition, BaseAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        List<String> predicates = extractPredicates(condition);
        Map<PredicateType, List<String>> classified = classifyPredicates(predicates);

        List<String> pushable = new ArrayList<>();
        List<String> remaining = new ArrayList<>();
        Map<PredicateType, List<String>> pushableByType = new LinkedHashMap<>();
        Map<PredicateType, List<String>> remainingByType = new LinkedHashMap<>();

        for (Map.Entry<PredicateType, List<String>> entry : classified.entrySet()) {
            PredicateType type = entry.getKey();
            List<String> preds = entry.getValue();
            if (preds.isEmpty()) {
                continue;
            }
            if (isPushable(type, adapter)) {
                pushable.addAll(preds);
                pushableByType.put(type, preds);
            } else {
                remaining.addAll(preds);
                remainingByType.put(type, preds);
            }
        }

        double pushDownRate = predicates.isEmpty() ? 0.0
                : (double) pushable.size() / predicates.size();
        return new PushDownAnalysis(predicates, classified, pushable, remaining,
                pushableByType, remainingByType, pushDownRate);
    }

    /**
     * 下推分析结果——封装谓词下推的分析输出。
     */
    public static class PushDownAnalysis {
        /** 全部谓词列表 */
        private final List<String> allPredicates;
        /** 按类型分类的全部谓词 */
        private final Map<PredicateType, List<String>> classified;
        /** 可下推谓词列表 */
        private final List<String> pushable;
        /** 保留谓词列表 */
        private final List<String> remaining;
        /** 按类型分类的可下推谓词 */
        private final Map<PredicateType, List<String>> pushableByType;
        /** 按类型分类的保留谓词 */
        private final Map<PredicateType, List<String>> remainingByType;
        /** 下推率 */
        private final double pushDownRate;

        public PushDownAnalysis(List<String> allPredicates,
                                Map<PredicateType, List<String>> classified,
                                List<String> pushable, List<String> remaining,
                                Map<PredicateType, List<String>> pushableByType,
                                Map<PredicateType, List<String>> remainingByType,
                                double pushDownRate) {
            this.allPredicates = Collections.unmodifiableList(allPredicates);
            this.classified = Collections.unmodifiableMap(classified);
            this.pushable = Collections.unmodifiableList(pushable);
            this.remaining = Collections.unmodifiableList(remaining);
            this.pushableByType = Collections.unmodifiableMap(pushableByType);
            this.remainingByType = Collections.unmodifiableMap(remainingByType);
            this.pushDownRate = pushDownRate;
        }

        public List<String> getAllPredicates() { return allPredicates; }
        public Map<PredicateType, List<String>> getClassified() { return classified; }
        public List<String> getPushable() { return pushable; }
        public List<String> getRemaining() { return remaining; }
        public Map<PredicateType, List<String>> getPushableByType() { return pushableByType; }
        public Map<PredicateType, List<String>> getRemainingByType() { return remainingByType; }
        public double getPushDownRate() { return pushDownRate; }

        /** 是否全部谓词均可下推 */
        public boolean isFullyPushed() { return remaining.isEmpty(); }
        /** 是否全部谓词均保留 */
        public boolean isFullyRemaining() { return pushable.isEmpty(); }
        /** 下推谓词数 */
        public int getPushedCount() { return pushable.size(); }
        /** 保留谓词数 */
        public int getRemainingCount() { return remaining.size(); }
        /** 总谓词数 */
        public int getTotalCount() { return allPredicates.size(); }

        @Override
        public String toString() {
            return "PushDownAnalysis{total=" + getTotalCount()
                    + ", pushed=" + getPushedCount()
                    + ", remaining=" + getRemainingCount()
                    + ", rate=" + String.format("%.2f%%", pushDownRate * 100) + '}';
        }
    }
}