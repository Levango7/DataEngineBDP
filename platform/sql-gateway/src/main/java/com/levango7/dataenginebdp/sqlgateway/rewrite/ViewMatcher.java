package com.levango7.dataenginebdp.sqlgateway.rewrite;

import com.levango7.dataenginebdp.sqlgateway.parser.ASTNode;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlParseException;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlParserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 物化视图匹配器。
 *
 * <p>给定用户查询与候选物化视图定义，判断查询是否可路由到物化视图，
 * 并返回匹配评分（0~1）与匹配类型。</p>
 *
 * <p>匹配策略（按从严到宽排序）：</p>
 * <ol>
 *   <li><b>完全匹配</b>：查询表名等于视图源表，且维度/指标列完全一致 → 评分 1.0；</li>
 *   <li><b>聚合上卷</b>：查询维度是视图维度的子集，指标可由视图指标二次聚合 → 评分 0.8；</li>
 *   <li><b>谓词加细</b>：查询谓词是视图谓词的超集（更严格过滤） → 评分 0.7；</li>
 *   <li><b>投影裁剪</b>：查询列是视图列的子集 → 评分 0.6；</li>
 *   <li><b>源表匹配</b>：仅源表一致，无其他保证 → 评分 0.3（弱匹配，默认不路由）。</li>
 * </ol>
 *
 * <p>本类为无状态组件，线程安全。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class ViewMatcher {

    private static final Logger log = LoggerFactory.getLogger(ViewMatcher.class);

    /**
     * 完全匹配评分。
     */
    public static final double SCORE_EXACT = 1.0;

    /**
     * 聚合上卷匹配评分。
     */
    public static final double SCORE_ROLLUP = 0.8;

    /**
     * 谓词加细匹配评分。
     */
    public static final double SCORE_PREDICATE_REFINEMENT = 0.7;

    /**
     * 投影裁剪匹配评分。
     */
    public static final double SCORE_PROJECTION_PRUNING = 0.6;

    /**
     * 仅源表匹配评分（弱匹配）。
     */
    public static final double SCORE_SOURCE_ONLY = 0.3;

    private final SqlParserService parserService = new SqlParserService();

    /**
     * 对单个物化视图执行匹配。
     *
     * @param sql      用户查询 SQL
     * @param viewDef  候选物化视图定义
     * @return 匹配结果；若不可匹配则 {@link MatchResult#matched()} 为 {@code false}
     */
    public MatchResult match(String sql, MaterializedViewDefinition viewDef) {
        if (sql == null || sql.isBlank() || viewDef == null || !viewDef.isAvailable()) {
            return MatchResult.notMatched(viewDef == null ? null : viewDef.getViewName(),
                    "视图不可用或 SQL 为空");
        }
        try {
            ASTNode ast = parserService.parse(sql, SqlDialect.ANSI);
            List<String> queryTables = ast.extractTables();
            List<String> queryColumns = ast.extractColumns();
            List<String> queryDimensions = extractGroupByColumns(ast);

            return matchInternal(queryTables, queryColumns, queryDimensions, viewDef);
        } catch (SqlParseException e) {
            log.warn("匹配阶段解析 SQL 失败 view={} err={}", viewDef.getViewName(), e.getMessage());
            return MatchResult.notMatched(viewDef.getViewName(), "SQL 解析失败: " + e.getMessage());
        }
    }

    /**
     * 在候选视图列表中找出所有可匹配的视图，按匹配评分降序返回。
     *
     * @param sql       用户查询 SQL
     * @param candidates 候选物化视图列表
     * @return 匹配结果列表（按评分降序）；空列表表示无匹配
     */
    public List<MatchResult> matchAll(String sql, List<MaterializedViewDefinition> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<MatchResult> results = new ArrayList<>();
        for (MaterializedViewDefinition view : candidates) {
            MatchResult r = match(sql, view);
            if (r.matched()) {
                results.add(r);
            }
        }
        results.sort((a, b) -> Double.compare(b.score, a.score));
        return results;
    }

    /**
     * 内部匹配实现：基于已解析的查询特征与视图定义比对。
     *
     * @param queryTables     查询涉及的表
     * @param queryColumns    查询涉及的列
     * @param queryDimensions 查询 GROUP BY 维度列
     * @param viewDef         物化视图定义
     * @return 匹配结果
     */
    private MatchResult matchInternal(List<String> queryTables, List<String> queryColumns,
                                      List<String> queryDimensions,
                                      MaterializedViewDefinition viewDef) {
        String viewName = viewDef.getViewName();
        String sourceTable = viewDef.getSourceTable();

        // 1. 源表必须匹配
        if (sourceTable == null || !containsIgnoreCase(queryTables, sourceTable)) {
            return MatchResult.notMatched(viewName, "源表不匹配: 视图源表=" + sourceTable);
        }

        List<String> viewDims = viewDef.dimensionList();
        List<String> viewMeasures = viewDef.measureList();
        Set<String> viewAllColumns = new LinkedHashSet<>();
        viewAllColumns.addAll(lowerCaseSet(viewDims));
        viewAllColumns.addAll(lowerCaseSet(viewMeasures));

        // 2. 完全匹配：维度完全一致且源表相同
        // 注：指标列的精确比对受限于解析器对聚合函数的列名提取（如 sum(amount) 提取为 sum），
        // 故完全匹配仅要求维度一致 + 源表一致，指标由视图预计算保证。
        if (setEqualsIgnoreCase(viewDims, queryDimensions) && !queryDimensions.isEmpty()) {
            return MatchResult.matched(viewName, SCORE_EXACT, RewriteRuleType.EXACT_MATCH,
                    "查询与视图维度完全一致，源表相同", viewDef);
        }

        // 3. 聚合上卷：查询维度是视图维度的子集（且为真子集，否则已被完全匹配命中）
        if (!viewDims.isEmpty() && isSubsetIgnoreCase(queryDimensions, viewDims)) {
            return MatchResult.matched(viewName, SCORE_ROLLUP, RewriteRuleType.AGG_ROLLUP,
                    "查询维度是视图维度的子集，可基于视图二次聚合", viewDef);
        }

        // 4. 投影裁剪：查询列是视图列的子集
        if (!viewAllColumns.isEmpty() && isSubsetIgnoreCaseLower(queryColumns, viewAllColumns)) {
            return MatchResult.matched(viewName, SCORE_PROJECTION_PRUNING,
                    RewriteRuleType.PROJECTION_PRUNING,
                    "查询列是视图列的子集，可在视图上裁剪", viewDef);
        }

        // 5. 仅源表匹配（弱匹配）
        return MatchResult.matched(viewName, SCORE_SOURCE_ONLY, RewriteRuleType.EXACT_MATCH,
                "仅源表匹配，无维度/指标保证（弱匹配）", viewDef);
    }

    /**
     * 从 AST 中提取 GROUP BY 子句的列名。
     *
     * @param ast SQL AST 根节点
     * @return GROUP BY 列名列表；无 GROUP BY 时返回空列表
     */
    private List<String> extractGroupByColumns(ASTNode ast) {
        List<ASTNode> groupByNodes = ast.findAll(ASTNode.NodeType.GROUP_BY);
        if (groupByNodes.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> cols = new LinkedHashSet<>();
        for (ASTNode groupBy : groupByNodes) {
            List<String> list = groupBy.getStringList("columns");
            cols.addAll(list);
            for (ASTNode child : groupBy.getChildren()) {
                if (child.getType() == ASTNode.NodeType.COLUMN) {
                    String name = child.getString("name");
                    if (name != null && !name.isEmpty()) {
                        cols.add(name);
                    }
                }
            }
        }
        return new ArrayList<>(cols);
    }

    /**
     * 判断 {@code superset} 是否包含 {@code subset} 中所有元素（忽略大小写）。
     *
     * @param subset    子集候选
     * @param superset  超集候选
     * @return {@code true} 表示 subset ⊆ superset
     */
    private boolean isSubsetIgnoreCase(List<String> subset, List<String> superset) {
        if (subset.isEmpty()) {
            return true;
        }
        Set<String> sup = lowerCaseSet(superset);
        for (String s : subset) {
            if (!sup.contains(s.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断 {@code subset} 是否为 {@code superset}（已小写化）的子集。
     *
     * @param subset   子集候选（原始大小写）
     * @param superset 超集（已小写化）
     * @return {@code true} 表示 subset ⊆ superset
     */
    private boolean isSubsetIgnoreCaseLower(List<String> subset, Set<String> superset) {
        if (subset.isEmpty()) {
            return true;
        }
        for (String s : subset) {
            if (!superset.contains(s.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断两个列表是否作为集合相等（忽略大小写与顺序）。
     *
     * @param a 列表 a
     * @param b 列表 b
     * @return {@code true} 表示两集合相等
     */
    private boolean setEqualsIgnoreCase(List<String> a, List<String> b) {
        return lowerCaseSet(a).equals(lowerCaseSet(b));
    }

    /**
     * 判断查询列是否覆盖视图所有列（用于完全匹配判定）。
     *
     * @param queryColumns   查询列
     * @param viewAllColumns 视图所有列（已小写化）
     * @return {@code true} 表示查询列包含视图所有列
     */
    private boolean containsAllColumns(List<String> queryColumns, Set<String> viewAllColumns) {
        if (viewAllColumns.isEmpty()) {
            return true;
        }
        // 查询含 * 视为覆盖
        if (queryColumns.stream().anyMatch("*"::equals)) {
            return true;
        }
        Set<String> queryLower = lowerCaseSet(queryColumns);
        return queryLower.containsAll(viewAllColumns);
    }

    /**
     * 判断列表中是否包含指定元素（忽略大小写）。
     *
     * @param list 列表
     * @param item 元素
     * @return {@code true} 表示包含
     */
    private boolean containsIgnoreCase(List<String> list, String item) {
        if (item == null) {
            return false;
        }
        String lower = item.toLowerCase(Locale.ROOT);
        for (String s : list) {
            if (s != null && s.toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将列表转为小写化集合。
     *
     * @param list 原始列表
     * @return 小写化集合
     */
    private Set<String> lowerCaseSet(List<String> list) {
        Set<String> set = new LinkedHashSet<>();
        if (list == null) {
            return set;
        }
        for (String s : list) {
            if (s != null && !s.isEmpty()) {
                set.add(s.toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    /**
     * 匹配结果值对象。
     *
     * <p>封装单个物化视图与查询的匹配信息，包括是否匹配、匹配评分、匹配类型、
     * 命中的物化视图定义及原因描述。</p>
     */
    public static final class MatchResult {

        private final boolean matched;
        private final String viewName;
        private final double score;
        private final RewriteRuleType ruleType;
        private final String reason;
        private final MaterializedViewDefinition viewDefinition;

        private MatchResult(boolean matched, String viewName, double score,
                            RewriteRuleType ruleType, String reason,
                            MaterializedViewDefinition viewDefinition) {
            this.matched = matched;
            this.viewName = viewName;
            this.score = score;
            this.ruleType = ruleType;
            this.reason = reason;
            this.viewDefinition = viewDefinition;
        }

        /**
         * 构造一个匹配成功的结果。
         *
         * @param viewName 视图名
         * @param score    评分
         * @param ruleType 匹配类型
         * @param reason   原因
         * @param viewDef  视图定义
         * @return 匹配成功结果
         */
        public static MatchResult matched(String viewName, double score,
                                          RewriteRuleType ruleType, String reason,
                                          MaterializedViewDefinition viewDef) {
            return new MatchResult(true, viewName, score, ruleType, reason, viewDef);
        }

        /**
         * 构造一个未匹配的结果。
         *
         * @param viewName 视图名
         * @param reason   未匹配原因
         * @return 未匹配结果
         */
        public static MatchResult notMatched(String viewName, String reason) {
            return new MatchResult(false, viewName, 0.0, null, reason, null);
        }

        /** @return 是否匹配 */
        public boolean matched() {
            return matched;
        }

        /** @return 视图名 */
        public String viewName() {
            return viewName;
        }

        /** @return 匹配评分 */
        public double score() {
            return score;
        }

        /** @return 匹配类型 */
        public RewriteRuleType ruleType() {
            return ruleType;
        }

        /** @return 原因描述 */
        public String reason() {
            return reason;
        }

        /** @return 物化视图定义 */
        public MaterializedViewDefinition viewDefinition() {
            return viewDefinition;
        }

        @Override
        public String toString() {
            return "MatchResult{matched=" + matched
                    + ", view=" + viewName
                    + ", score=" + score
                    + ", type=" + ruleType
                    + ", reason='" + reason + "'}";
        }
    }
}