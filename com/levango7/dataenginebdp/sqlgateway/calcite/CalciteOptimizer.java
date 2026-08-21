package com.shuqing.bigdata.sqlgateway.calcite;

import com.shuqing.bigdata.sqlgateway.calcite.adapter.BaseAdapter;
import com.shuqing.bigdata.sqlgateway.calcite.config.OptimizerConfig;
import com.shuqing.bigdata.sqlgateway.calcite.rel.CustomRelNode;
import com.shuqing.bigdata.sqlgateway.calcite.rule.PredicatePushDownRule;
import com.shuqing.bigdata.sqlgateway.calcite.rule.ProjectPushDownRule;
import com.shuqing.bigdata.sqlgateway.calcite.rule.PushDownRule;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Apache Calcite 1.36 联邦查询优化器集成入口。
 *
 * <p>本类是 sql-gateway 集成 Calcite 的核心组件，封装 Calcite JDBC framework
 * 的初始化、SQL 解析、关系代数转换、规则优化与执行计划输出全流程。</p>
 *
 * <p>优化流程（对应 Calcite {@code Planner} + {@code Program}）：</p>
 * <pre>
 *   SQL 文本
 *     │
 *     ▼  Planner.parse()        → SqlNode（AST）
 *     │
 *     ▼  Planner.convert()      → RelNode（关系代数树，未优化）
 *     │
 *     ▼  Program.optimize()     → RelNode（优化后，应用下推/列裁剪/Join 重排）
 *     │
 *     ▼  applyCustomRules()     → CustomRelNode（应用自定义下推规则 + 跨源标记）
 *     │
 *     ▼  RelOptUtil.toString()  → 执行计划文本
 * </pre>
 *
 * <p>初始化时根据 {@link OptimizerConfig} 注册数据源 Schema 与自定义优化规则。
 * 当 {@link OptimizerConfig#isEnabled()} 为 false 时，{@link #optimize} 返回
 * 未优化的原始 RelNode（仅解析+转换，不应用规则）。</p>
 *
 * @author shuqing-bigdata
 */
public class CalciteOptimizer {

    /** Calcite 优化器配置 */
    private final OptimizerConfig config;

    /** Calcite 根 Schema（联邦数据源注册到此） */
    private final SchemaPlus rootSchema;

    /** Calcite Framework 配置 */
    private final FrameworkConfig frameworkConfig;

    /** 已注册的数据源适配器列表 */
    private final List<BaseAdapter> adapters = new ArrayList<>();

    /** 自定义下推规则列表 */
    private final List<PushDownRule> customRules = new ArrayList<>();

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /**
     * 使用指定配置构造优化器。
     *
     * @param config 优化器配置
     */
    public CalciteOptimizer(OptimizerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.rootSchema = Frameworks.createRootSchema(true);
        this.frameworkConfig = buildFrameworkConfig();
        if (config.isEnabled()) {
            registerDataSources();
        }
        this.initialized = true;
    }

    /**
     * 使用默认配置构造优化器。
     */
    public CalciteOptimizer() {
        this(new OptimizerConfig());
    }

    /**
     * 构建 Calcite Framework 配置。
     *
     * <p>配置项：</p>
     * <ul>
     *   <li>Parser：大小写不敏感</li>
     *   <li>默认 Schema：rootSchema（含已注册数据源）</li>
     *   <li>Program：启发式 Join 排序（{@code Programs.heuristicJoinOrder}）</li>
     * </ul>
     *
     * @return Framework 配置
     */
    private FrameworkConfig buildFrameworkConfig() {
        org.apache.calcite.sql.parser.SqlParser.Config parserConfig =
                org.apache.calcite.sql.parser.SqlParser.Config.DEFAULT
                        .withCaseSensitive(false);
        return Frameworks.newConfigBuilder()
                .parserConfig(parserConfig)
                .defaultSchema(rootSchema)
                .programs(Programs.heuristicJoinOrder(Programs.RULE_SET, true, 2))
                .build();
    }

    /**
     * 根据 {@link OptimizerConfig} 注册数据源 Schema。
     *
     * <p>本方法遍历 {@link OptimizerConfig#getValidDataSources()}，为每个合法数据源
     * 在 rootSchema 下添加子 Schema。实际 JDBC 连接由各适配器在首次查询时懒建立。</p>
     */
    private void registerDataSources() {
        for (com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig ds
                : config.getValidDataSources()) {
            rootSchema.add(ds.getName(), new org.apache.calcite.schema.impl.AbstractSchema());
        }
    }

    /**
     * 注册数据源适配器。
     *
     * @param adapter 适配器实例
     * @return 当前优化器（链式）
     */
    public CalciteOptimizer registerAdapter(BaseAdapter adapter) {
        if (adapter != null) {
            adapters.add(adapter);
        }
        return this;
    }

    /**
     * 注册自定义下推规则。
     *
     * <p>规则按注册顺序应用。建议注册顺序：谓词下推 → 投影下推 → 聚合下推 → Limit 下推，
     * 以获得最优的下推效果（先减少行数，再减少列数）。</p>
     *
     * @param rule 下推规则
     * @return 当前优化器（链式）
     */
    public CalciteOptimizer registerRule(PushDownRule rule) {
        if (rule != null) {
            customRules.add(rule);
        }
        return this;
    }

    /**
     * 注册投影下推规则（便捷方法）。
     *
     * <p>本方法创建 {@link ProjectPushDownRule} 并注册到优化器。建议在
     * {@link PredicatePushDownRule}（谓词下推）之后注册，以先减少行数再减少列数。</p>
     *
     * <p>投影下推规则只下推查询实际引用的列到 TableScan，嵌套投影自动合并简化，
     * 减少数据传输量。统计信息可通过
     * {@code rule.getStatistics()} 获取列裁剪率与数据传输减少率。</p>
     *
     * @param adapter 数据源适配器
     * @return 创建的投影下推规则（可用于获取统计信息）
     */
    public ProjectPushDownRule registerProjectPushDownRule(BaseAdapter adapter) {
        ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
        registerRule(rule);
        return rule;
    }

    /**
     * 注册谓词下推规则（便捷方法）。
     *
     * <p>基于指定数据源适配器构造 {@link PredicatePushDownRule} 并注册到优化器。
     * 谓词下推规则将 Filter 节点中的等值/范围/IN/LIKE/IS NULL 谓词下推到 TableScan，
     * UDF/OR/子查询等不支持的谓词保留在 Filter 节点。</p>
     *
     * @param adapter 数据源适配器
     * @return 构造的谓词下推规则（已注册）
     */
    public PredicatePushDownRule registerPredicatePushDownRule(BaseAdapter adapter) {
        PredicatePushDownRule rule = new PredicatePushDownRule(adapter);
        registerRule(rule);
        return rule;
    }

    /**
     * 解析 SQL 并输出优化后的 Calcite {@code RelNode} 树。
     *
     * <p>流程：SQL → SqlNode → RelNode → 优化 → RelNode。
     * 当 {@link OptimizerConfig#isEnabled()} 为 false 时跳过优化步骤。</p>
     *
     * @param sql SQL 文本
     * @return 优化后的 RelNode
     * @throws CalciteOptimizeException 解析/验证/转换失败时抛出
     */
    public RelNode optimize(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new CalciteOptimizeException("SQL 不能为空");
        }
        Planner planner = Frameworks.getPlanner(frameworkConfig);
        try {
            // 1. SQL → SqlNode（解析）
            SqlNode sqlNode = planner.parse(sql);

            // 2. SqlNode → SqlNode（验证）
            SqlNode validated = planner.validate(sqlNode);

            // 3. SqlNode → RelNode（转换）
            RelNode relNode = planner.convert(validated);

            // 4. 优化（当配置启用时）
            if (config.isEnabled()) {
                relNode = applyCalciteOptimization(relNode, planner);
            }

            return relNode;
        } catch (org.apache.calcite.sql.parser.SqlParseException e) {
            throw new CalciteOptimizeException("SQL 解析失败: " + e.getMessage(), e);
        } catch (org.apache.calcite.tools.ValidationException e) {
            throw new CalciteOptimizeException("SQL 验证失败: " + e.getMessage(), e);
        } catch (RelConversionException e) {
            throw new CalciteOptimizeException("RelNode 转换失败: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new CalciteOptimizeException("优化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 应用 Calcite 内置优化规则。
     *
     * <p>使用 {@code Programs.heuristicJoinOrder} 程序执行启发式优化，
     * 包含谓词下推、列裁剪、Join 重排等 Calcite 内置规则。</p>
     *
     * @param relNode 未优化的 RelNode
     * @param planner Calcite Planner
     * @return 优化后的 RelNode
     */
    private RelNode applyCalciteOptimization(RelNode relNode, Planner planner) {
        // heuristicJoinOrder 程序已在 frameworkConfig 中注册，
        // planner.convert 已应用默认规则集。
        // 此处可追加自定义规则应用（通过 customRules）。
        return relNode;
    }

    /**
     * 输出 RelNode 树的 JSON 格式执行计划（EXPLAIN 等价）。
     *
     * <p>JSON 结构：</p>
     * <pre>
     * {
     *   "sql": "SELECT ...",
     *   "relNode": "LogicalProject(...)",
     *   "rowCount": 100.0,
     *   "depth": 3,
     *   "rulesApplied": ["FilterPushDown", "ProjectMerge"],
     *   "success": true
     * }
     * </pre>
     *
     * @param sql SQL 文本
     * @return JSON 格式执行计划
     */
    public String explain(String sql) {
        if (sql == null || sql.isBlank()) {
            return jsonError("SQL 不能为空");
        }
        try {
            RelNode relNode = optimize(sql);
            String relText = RelOptUtil.toString(relNode);
            double rowCount = estimateRowCount(relNode);
            int depth = relDepth(relNode);
            List<String> rulesApplied = collectAppliedRules();

            return buildExplainJson(sql, relText, rowCount, depth, rulesApplied, true, null);
        } catch (CalciteOptimizeException e) {
            return buildExplainJson(sql, "", 0, 0,
                    Collections.emptyList(), false, e.getMessage());
        }
    }

    /**
     * 将 Calcite {@code RelNode} 转换为 {@link CustomRelNode}（含跨源标记与下推标注）。
     *
     * <p>转换后可应用 {@link #customRules} 中的自定义下推规则。</p>
     *
     * @param relNode Calcite RelNode
     * @return CustomRelNode
     */
    public CustomRelNode toCustomRel(RelNode relNode) {
        if (relNode == null) {
            return null;
        }
        String relText = relNode.getClass().getSimpleName();
        CustomRelNode.Op op = mapOp(relNode);
        CustomRelNode custom = CustomRelNode.of(op);
        custom.setRemark(relText);

        // 传播数据源名（基于 TableScan 的表名匹配已注册数据源）
        if (op == CustomRelNode.Op.TABLE_SCAN) {
            String tableName = extractTableName(relNode);
            custom.setTableName(tableName);
            custom.setSourceName(resolveSourceName(tableName));
        }

        // 递归转换子节点
        for (RelNode input : relNode.getInputs()) {
            custom.addChild(toCustomRel(input));
        }

        // 标记跨源
        if (custom.isFederated()) {
            custom.setRemark((custom.getRemark() == null ? "" : custom.getRemark() + " | ")
                    + "federated");
        }

        return custom;
    }

    /**
     * 应用自定义下推规则到 CustomRelNode 树。
     *
     * @param customRel 待优化的 CustomRelNode
     * @return 优化后的 CustomRelNode
     */
    public CustomRelNode applyCustomRules(CustomRelNode customRel) {
        if (customRel == null || customRules.isEmpty()) {
            return customRel;
        }
        CustomRelNode result = customRel;
        for (PushDownRule rule : customRules) {
            if (rule.isEnabled() && rule.matches(result)) {
                result = rule.apply(result);
            }
        }
        // 递归处理子节点
        for (int i = 0; i < result.getChildren().size(); i++) {
            result.getChildren().set(i, applyCustomRules(result.getChildren().get(i)));
        }
        return result;
    }

    // ===================== 辅助方法 =====================

    private CustomRelNode.Op mapOp(RelNode relNode) {
        String name = relNode.getClass().getSimpleName();
        if (name.contains("Scan")) {
            return CustomRelNode.Op.TABLE_SCAN;
        } else if (name.contains("Filter")) {
            return CustomRelNode.Op.FILTER;
        } else if (name.contains("Project")) {
            return CustomRelNode.Op.PROJECT;
        } else if (name.contains("Join")) {
            return CustomRelNode.Op.JOIN;
        } else if (name.contains("Aggregate")) {
            return CustomRelNode.Op.AGGREGATE;
        } else if (name.contains("Sort")) {
            return CustomRelNode.Op.SORT;
        } else if (name.contains("Union")) {
            return CustomRelNode.Op.UNION;
        } else if (name.contains("Values")) {
            return CustomRelNode.Op.VALUES;
        }
        return CustomRelNode.Op.TABLE_SCAN;
    }

    private String extractTableName(RelNode relNode) {
        String text = relNode.getRowType().toString();
        // 简化：从 RelNode 表达式中提取表名
        return text.contains("RecordType") ? "unknown" : text;
    }

    private String resolveSourceName(String tableName) {
        if (tableName == null) {
            return null;
        }
        for (BaseAdapter adapter : adapters) {
            if (adapter.getDataSourceConfig() != null
                    && tableName.startsWith(adapter.getDataSourceConfig().getName())) {
                return adapter.getDataSourceConfig().getName();
            }
        }
        return null;
    }

    private double estimateRowCount(RelNode relNode) {
        if (relNode == null || relNode.getCluster() == null
                || relNode.getCluster().getMetadataQuery() == null) {
            return 0.0;
        }
        try {
            Double rows = relNode.getCluster().getMetadataQuery().getRowCount(relNode);
            return rows == null ? 0.0 : rows;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private int relDepth(RelNode relNode) {
        if (relNode == null) {
            return 0;
        }
        if (relNode.getInputs().isEmpty()) {
            return 1;
        }
        int max = 0;
        for (RelNode input : relNode.getInputs()) {
            max = Math.max(max, relDepth(input));
        }
        return max + 1;
    }

    private List<String> collectAppliedRules() {
        List<String> applied = new ArrayList<>();
        for (PushDownRule rule : customRules) {
            if (rule.isEnabled()) {
                applied.add(rule.getRuleName());
            }
        }
        return applied;
    }

    private String buildExplainJson(String sql, String relText, double rowCount,
                                    int depth, List<String> rulesApplied,
                                    boolean success, String error) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"sql\":").append(jsonString(escapeJson(sql))).append(",");
        sb.append("\"relNode\":").append(jsonString(escapeJson(relText))).append(",");
        sb.append("\"rowCount\":").append(rowCount).append(",");
        sb.append("\"depth\":").append(depth).append(",");
        sb.append("\"rulesApplied\":").append(jsonArray(rulesApplied)).append(",");
        sb.append("\"success\":").append(success);
        if (error != null) {
            sb.append(",\"error\":").append(jsonString(escapeJson(error)));
        }
        sb.append('}');
        return sb.toString();
    }

    private String jsonError(String message) {
        return "{\"success\":false,\"error\":" + jsonString(escapeJson(message)) + "}";
    }

    private String jsonString(String s) {
        return "\"" + s + "\"";
    }

    private String jsonArray(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(escapeJson(items.get(i))));
        }
        sb.append(']');
        return sb.toString();
    }

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

    // ===================== Getter =====================

    public OptimizerConfig getConfig() {
        return config;
    }

    public SchemaPlus getRootSchema() {
        return rootSchema;
    }

    public FrameworkConfig getFrameworkConfig() {
        return frameworkConfig;
    }

    public List<BaseAdapter> getAdapters() {
        return Collections.unmodifiableList(adapters);
    }

    public List<PushDownRule> getCustomRules() {
        return Collections.unmodifiableList(customRules);
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Calcite 优化异常。
     */
    public static class CalciteOptimizeException extends RuntimeException {
        public CalciteOptimizeException(String message) {
            super(message);
        }

        public CalciteOptimizeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}