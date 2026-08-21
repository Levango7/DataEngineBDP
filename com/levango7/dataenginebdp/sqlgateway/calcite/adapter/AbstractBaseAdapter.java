package com.shuqing.bigdata.sqlgateway.calcite.adapter;

import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;
import com.shuqing.bigdata.sqlgateway.calcite.rel.CustomRelNode;
import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 数据源适配器抽象基类——为 5 种具体适配器（Iceberg/Doris/Trino/IoTDB/ES）提供
 * {@link BaseAdapter} 的通用实现框架。
 *
 * <p>本类封装所有适配器共有的逻辑：</p>
 * <ul>
 *   <li><b>toRel</b>：构造 {@code TABLE_SCAN} CustomRelNode，携带表名、数据源名、投影列</li>
 *   <li><b>pushDown 通用流程</b>：跨源判定 → 收集下推 SQL → 标记下推状态 → 返回结果</li>
 *   <li><b>costEstimate 通用模型</b>：基于 {@link TableStatistics} 估算 CPU/IO/Network Cost</li>
 *   <li><b>表统计信息缓存</b>：按表名缓存 {@link TableStatistics}，避免重复查询元数据</li>
 * </ul>
 *
 * <p>子类需实现：</p>
 * <ul>
 *   <li>{@link #getDialect()}：返回数据源 SQL 方言</li>
 *   <li>{@link #buildPushedSql}：将 RelNode 转为数据源特定下推 SQL/DSL</li>
 *   <li>{@link #loadStatistics}：从数据源加载表统计信息（懒加载，可缓存）</li>
 *   <li>{@link #cpuCostFactor}/{@link #ioCostFactor}/{@link #networkCostFactor}：Cost 因子</li>
 * </ul>
 *
 * <p>Cost 估算公式（基于表统计）：</p>
 * <pre>
 *   rows      = stats.rowCount × selectivity（谓词选择率）
 *   cpuCost   = rows × cpuCostFactor
 *   ioCost    = (rows × stats.averageRowSizeBytes) / IO_BLOCK_SIZE × ioCostFactor
 *   networkCost = rows × stats.averageRowSizeBytes × networkCostFactor
 * </pre>
 *
 * @author shuqing-bigdata
 */
public abstract class AbstractBaseAdapter implements BaseAdapter {

    /** IO 块大小（字节，默认 64KB，用于 IO Cost 估算） */
    protected static final int IO_BLOCK_SIZE_BYTES = 64 * 1024;

    /** 数据源配置 */
    private final DataSourceConfig config;

    /** 表统计信息缓存：表名 → 统计信息（懒加载） */
    private final Map<String, TableStatistics> statsCache = new LinkedHashMap<>();

    /**
     * 构造适配器。
     *
     * @param config 数据源配置（非空）
     */
    protected AbstractBaseAdapter(DataSourceConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        if (!config.isValid()) {
            throw new IllegalArgumentException("数据源配置不合法: " + config);
        }
    }

    @Override
    public DataSourceConfig getDataSourceConfig() {
        return config;
    }

    // ===================== toRel 通用实现 =====================

    @Override
    public CustomRelNode toRel(String tableName, List<String> columns) {
        Objects.requireNonNull(tableName, "tableName");
        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName(tableName)
                .setSourceName(config.getName());
        if (columns != null && !columns.isEmpty()) {
            scan.setProjects(new ArrayList<>(columns));
        }
        return scan;
    }

    // ===================== pushDown 通用流程 =====================

    /**
     * 通用下推流程：跨源判定 → 构造下推 SQL → 标记下推状态 → 返回结果。
     *
     * <p>子类通过覆写 {@link #buildPushedSql} 提供数据源特定的 SQL/DSL 生成逻辑。
     * 当 {@link DataSourceConfig#isPushDownEnabled()} 为 false 时直接返回失败。</p>
     */
    @Override
    public PushDownResult pushDown(CustomRelNode relNode, PushDownContext context) {
        Objects.requireNonNull(relNode, "relNode");
        Objects.requireNonNull(context, "context");

        if (!config.isPushDownEnabled()) {
            return PushDownResult.failure("数据源 " + config.getName() + " 已禁用下推");
        }
        if (!canPushDown(relNode)) {
            return PushDownResult.failure("跨源节点不可下推到 " + config.getName());
        }

        String pushedSql = buildPushedSql(relNode, context);
        if (pushedSql == null || pushedSql.isBlank()) {
            return PushDownResult.failure("无法生成下推 SQL");
        }

        List<String> pushedOps = new ArrayList<>(context.getPushedOperations());
        pushedOps.add("pushDown[" + getAdapterType() + "]:" + relNode.getOp());

        CustomRelNode remaining = relNode;
        relNode.setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
        relNode.addPushedOperation(relNode.getOp().name());

        return new PushDownResult(pushedSql, remaining, pushedOps, true, null);
    }

    /**
     * 将 RelNode 转为数据源特定的下推 SQL/DSL。
     *
     * <p>子类实现应基于 {@link CustomRelNode.Op} 与节点字段（condition/projects/tableName）
     * 生成对应方言的 SQL 或 DSL（如 ES 的 Query DSL JSON）。</p>
     *
     * @param relNode 待下推的 RelNode
     * @param context 下推上下文
     * @return 下推 SQL/DSL（null 或空表示无法下推）
     */
    protected abstract String buildPushedSql(CustomRelNode relNode, PushDownContext context);

    // ===================== costEstimate 通用模型 =====================

    @Override
    public Cost costEstimate(CustomRelNode relNode) {
        if (relNode == null) {
            return Cost.zero();
        }
        if (!config.isCostEstimationEnabled()) {
            return Cost.zero();
        }

        TableStatistics stats = getStatistics(relNode);
        double selectivity = estimateSelectivity(relNode, stats);
        double rows = stats.getRowCount() * selectivity;

        double cpuCost = rows * cpuCostFactor();
        double ioCost = (rows * stats.getAverageRowSizeBytes())
                / IO_BLOCK_SIZE_BYTES * ioCostFactor();
        double networkCost = rows * stats.getAverageRowSizeBytes() * networkCostFactor();

        return new Cost(cpuCost, ioCost, networkCost, rows);
    }

    /**
     * 获取 RelNode 子树对应的表统计信息（带缓存）。
     *
     * <p>取子树中第一个 TABLE_SCAN 的表名，加载其统计信息。
     * 跨源 Join 时取行数最大的表（保守估算）。</p>
     *
     * @param relNode RelNode 子树
     * @return 表统计信息
     */
    protected TableStatistics getStatistics(CustomRelNode relNode) {
        List<String> tables = relNode.collectTableNames();
        if (tables.isEmpty()) {
            return TableStatistics.defaultStats();
        }
        // 取第一个表的统计信息（多表 Join 时取最大行数的表，保守估算）
        TableStatistics maxStats = null;
        for (String table : tables) {
            TableStatistics stats = getStatistics(table);
            if (maxStats == null || stats.getRowCount() > maxStats.getRowCount()) {
                maxStats = stats;
            }
        }
        return maxStats;
    }

    /**
     * 获取指定表的统计信息（带缓存，懒加载）。
     *
     * @param tableName 表名
     * @return 表统计信息
     */
    public TableStatistics getStatistics(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return TableStatistics.defaultStats();
        }
        return statsCache.computeIfAbsent(tableName, this::loadStatistics);
    }

    /**
     * 从数据源加载表统计信息（子类实现，可查询元数据表或 REST API）。
     *
     * <p>默认实现返回 {@link TableStatistics#defaultStats()}，子类可覆写以提供
     * 真实统计信息。本方法被 {@link #getStatistics(String)} 缓存调用。</p>
     *
     * @param tableName 表名
     * @return 表统计信息
     */
    protected TableStatistics loadStatistics(String tableName) {
        return TableStatistics.defaultStats();
    }

    /**
     * 估算 RelNode 子树的选择率（基于谓词与列基数）。
     *
     * <p>简化模型：</p>
     * <ul>
     *   <li>无 Filter：选择率 = 1.0</li>
     *   <li>等值谓词：选择率 = 1 / 列基数</li>
     *   <li>范围谓词：选择率 = 0.1</li>
     *   <li>多谓词 AND：选择率相乘</li>
     * </ul>
     *
     * @param relNode RelNode 子树
     * @param stats   表统计信息
     * @return 选择率（0, 1]
     */
    protected double estimateSelectivity(CustomRelNode relNode, TableStatistics stats) {
        if (relNode == null) {
            return 1.0;
        }
        double selectivity = 1.0;
        if (relNode.getOp() == CustomRelNode.Op.FILTER
                && relNode.getCondition() != null) {
            selectivity *= estimatePredicateSelectivity(relNode.getCondition(), stats);
        }
        for (CustomRelNode child : relNode.getChildren()) {
            selectivity *= estimateSelectivity(child, stats);
        }
        return Math.max(selectivity, 0.0001);
    }

    /**
     * 估算单个谓词条件的选择率。
     *
     * @param condition 谓词条件字符串
     * @param stats     表统计信息
     * @return 选择率
     */
    protected double estimatePredicateSelectivity(String condition, TableStatistics stats) {
        if (condition == null || condition.isBlank()) {
            return 1.0;
        }
        // 按 AND 拆分谓词，各谓词选择率相乘
        String[] parts = condition.split("(?i)\\sAND\\s");
        double sel = 1.0;
        for (String part : parts) {
            sel *= singlePredicateSelectivity(part.trim(), stats);
        }
        return sel;
    }

    /**
     * 估算单个原子谓词的选择率。
     *
     * @param predicate 原子谓词（如 "age > 18"、"id = 100"）
     * @param stats     表统计信息
     * @return 选择率
     */
    protected double singlePredicateSelectivity(String predicate, TableStatistics stats) {
        if (predicate == null || predicate.isBlank()) {
            return 1.0;
        }
        // 等值谓词 col = value
        if (predicate.matches("(?i)\\w+\\s*=\\s*[^=<>!]+")) {
            String col = predicate.split("\\s*=\\s*")[0].trim();
            return stats.equalitySelectivity(col);
        }
        // 范围谓词 <, >, <=, >=, BETWEEN
        if (predicate.matches("(?i).*[<>]=?.*") || predicate.toUpperCase().contains("BETWEEN")) {
            return stats.rangeSelectivity();
        }
        // IN 谓词：选择率 = IN 列表大小 / 列基数（简化为 0.1）
        if (predicate.toUpperCase().contains(" IN ")) {
            return 0.1;
        }
        // LIKE / IS NULL / 其他：保守估算 0.1
        return 0.1;
    }

    /**
     * CPU Cost 因子（子类可覆写以反映数据源特性）。
     *
     * @return CPU Cost 因子
     */
    protected abstract double cpuCostFactor();

    /**
     * IO Cost 因子（子类可覆写以反映数据源特性，如列存 IO 更低）。
     *
     * @return IO Cost 因子
     */
    protected abstract double ioCostFactor();

    /**
     * 网络 Cost 因子（子类可覆写以反映数据源特性，如本地源网络更低）。
     *
     * @return 网络 Cost 因子
     */
    protected abstract double networkCostFactor();

    // ===================== 辅助方法 =====================

    /**
     * 提取 RelNode 子树中的投影列（用于下推 SQL 生成）。
     *
     * @param relNode RelNode 子树
     * @return 投影列列表（空表示全表扫描）
     */
    protected List<String> extractProjects(CustomRelNode relNode) {
        if (relNode == null) {
            return Collections.emptyList();
        }
        if (relNode.getOp() == CustomRelNode.Op.PROJECT) {
            return relNode.getProjects();
        }
        for (CustomRelNode child : relNode.getChildren()) {
            List<String> projects = extractProjects(child);
            if (!projects.isEmpty()) {
                return projects;
            }
        }
        return Collections.emptyList();
    }

    /**
     * 提取 RelNode 子树中的谓词条件（用于下推 SQL 生成）。
     *
     * @param relNode RelNode 子树
     * @return 谓词条件字符串（null 表示无 Filter）
     */
    protected String extractCondition(CustomRelNode relNode) {
        if (relNode == null) {
            return null;
        }
        if (relNode.getOp() == CustomRelNode.Op.FILTER
                && relNode.getCondition() != null) {
            return relNode.getCondition();
        }
        for (CustomRelNode child : relNode.getChildren()) {
            String cond = extractCondition(child);
            if (cond != null) {
                return cond;
            }
        }
        return null;
    }

    /**
     * 提取 RelNode 子树中的主表名（用于下推 SQL 生成）。
     *
     * @param relNode RelNode 子树
     * @return 表名（null 表示无 TableScan）
     */
    protected String extractTableName(CustomRelNode relNode) {
        if (relNode == null) {
            return null;
        }
        List<String> tables = relNode.collectTableNames();
        return tables.isEmpty() ? null : tables.get(0);
    }

    /**
     * 构造 SELECT 列子句（投影列下推）。
     *
     * @param projects 投影列列表
     * @return SELECT 子句（如 "id, name" 或 "*"）
     */
    protected String buildSelectClause(List<String> projects) {
        if (projects == null || projects.isEmpty()) {
            return "*";
        }
        return String.join(", ", projects);
    }

    @Override
    public String toString() {
        return getAdapterType() + "Adapter{name=" + config.getName()
                + ", dialect=" + getDialect() + '}';
    }
}