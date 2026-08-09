package com.levango7.dataenginebdp.sqlgateway.calcite.adapter;

import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trino 数据源适配器实现——对接 Trino（原 PrestoSQL）联邦查询引擎。
 *
 * <p>本类实现 {@link TrinoAdapter} 接口，基于 {@link AbstractBaseAdapter} 框架
 * 提供 Trino 特有的下推与 Cost 估算能力：</p>
 *
 * <p><b>方言转换（TRINO 方言）：</b>下推 SQL 生成 Trino 兼容 SQL，使用
 * {@code catalog.schema.table} 三段式命名，支持 CTE 内联与动态过滤。</p>
 *
 * <p><b>下推能力：</b></p>
 * <ul>
 *   <li>Connector 路由：将表路由到对应 Trino Connector（hive/iceberg/mysql）</li>
 *   <li>动态过滤：Trino 运行时生成的动态过滤谓词下推（基于 build 侧数据）</li>
 *   <li>Exchange 下推：将 Shuffle/Exchange 下推到 Trino Worker</li>
 *   <li>CTE 内联：将 WITH 子句内联以启用更多优化</li>
 * </ul>
 *
 * <p><b>Cost 模型：</b>Trino 为 MPP 联邦引擎，CPU Cost 中等（JIT 编译），
 * IO Cost 取决于底层 Connector（这里取中等），Network Cost 较高（跨 Worker Shuffle）。
 * Worker 数量影响并行度，Cost 随 Worker 数增加而降低（并行度提升）。</p>
 *
 * <pre>
 *   cpuCost   = rows × 0.2 / workerCount（并行执行）
 *   ioCost    = (rows × rowSize / 64KB) × 2.0 / workerCount（并行 IO）
 *   networkCost = rows × rowSize × 0.1（跨 Worker Shuffle）
 * </pre>
 *
 * @author shuqing-bigdata
 */
public class TrinoAdapterImpl extends AbstractBaseAdapter implements TrinoAdapter {

    /** 支持 Trino 动态过滤的 Connector 集合 */
    private static final Set<String> DYNAMIC_FILTER_CONNECTORS =
            new LinkedHashSet<>(Arrays.asList("hive", "iceberg", "delta"));

    /** CTE 匹配正则：WITH name AS (subquery) */
    private static final Pattern CTE_PATTERN =
            Pattern.compile("(?is)WITH\\s+(\\w+)\\s+AS\\s*\\(([^)]+)\\)\\s*");

    /** Connector 注册表：catalog 名 → Connector 名 */
    private final Map<String, String> connectorRegistry = new LinkedHashMap<>();
    /** Trino 集群 Worker 数量 */
    private int workerCount;

    /**
     * 构造 Trino 适配器。
     *
     * @param config 数据源配置（type 必须为 TRINO）
     */
    public TrinoAdapterImpl(DataSourceConfig config) {
        super(config);
        if (config.getType() != DataSourceConfig.Type.TRINO) {
            throw new IllegalArgumentException("数据源类型必须为 TRINO, 实际: " + config.getType());
        }
        // 从配置读取 Worker 数量
        String workerStr = config.getProperties().get("workerCount");
        this.workerCount = parseInt(workerStr, 10);
        // 默认注册常见 Connector
        registerConnector("hive", "hive");
        registerConnector("iceberg", "iceberg");
        registerConnector("mysql", "mysql");
    }

    // ===================== 方言与下推 SQL 生成 =====================

    @Override
    public SqlDialect getDialect() {
        SqlDialect dialect = getDataSourceConfig().getDialect();
        return dialect == null ? SqlDialect.TRINO : dialect;
    }

    @Override
    protected String buildPushedSql(CustomRelNode relNode, PushDownContext context) {
        String tableName = extractTableName(relNode);
        if (tableName == null) {
            return null;
        }

        // 1. 列裁剪（投影下推）
        List<String> projects = extractProjects(relNode);
        String selectClause = buildSelectClause(projects);

        // 2. 谓词下推（含动态过滤）
        String condition = extractCondition(relNode);
        String whereClause = condition != null ? " WHERE " + condition : "";

        // 3. Trino 三段式命名保持（catalog.schema.table）
        return "SELECT " + selectClause + " FROM " + tableName + whereClause;
    }

    // ===================== Trino 特有方法 =====================

    @Override
    public String getConnectorName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return "default";
        }
        // 从三段式命名提取 catalog：catalog.schema.table
        String[] parts = tableName.split("\\.");
        if (parts.length >= 1) {
            String catalog = parts[0];
            String connector = connectorRegistry.get(catalog);
            if (connector != null) {
                return connector;
            }
            // catalog 名即 connector 名（Trino 默认行为）
            return catalog;
        }
        return "default";
    }

    /**
     * 注册 Trino Connector（catalog → connector 映射）。
     *
     * @param catalog     catalog 名
     * @param connector   connector 名
     */
    public void registerConnector(String catalog, String connector) {
        connectorRegistry.put(catalog, connector);
    }

    @Override
    public boolean supportsDynamicFiltering(String connectorName) {
        if (connectorName == null) {
            return false;
        }
        return DYNAMIC_FILTER_CONNECTORS.contains(connectorName.toLowerCase());
    }

    /**
     * 添加支持动态过滤的 Connector。
     *
     * @param connectorName Connector 名
     */
    public void addDynamicFilterConnector(String connectorName) {
        if (connectorName != null) {
            DYNAMIC_FILTER_CONNECTORS.add(connectorName.toLowerCase());
        }
    }

    @Override
    public String inlineCte(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        // 简化 CTE 内联：移除 WITH 子句，保留主查询
        // 完整实现需解析 SQL 并替换 CTE 引用，这里提供基础内联
        Matcher matcher = CTE_PATTERN.matcher(sql);
        if (matcher.find()) {
            // 提取 CTE 名与子查询
            String cteName = matcher.group(1);
            String cteBody = matcher.group(2);
            // 移除 WITH 子句
            String remaining = sql.substring(matcher.end());
            // 简化：将主查询中的 CTE 名替换为子查询（括号包裹）
            // 注意：这是简化实现，完整实现需 SQL 解析
            return remaining.replaceAll("(?i)\\b" + cteName + "\\b", "(" + cteBody + ")");
        }
        return sql;
    }

    @Override
    public int getWorkerCount() {
        return workerCount;
    }

    /**
     * 设置 Trino 集群 Worker 数量。
     *
     * @param workerCount Worker 数
     */
    public void setWorkerCount(int workerCount) {
        this.workerCount = workerCount > 0 ? workerCount : 1;
    }

    // ===================== 统计信息加载 =====================

    @Override
    protected TableStatistics loadStatistics(String tableName) {
        Map<String, String> props = getDataSourceConfig().getProperties();
        long rowCount = parseLong(props.get("stats." + tableName + ".rowCount"),
                TableStatistics.DEFAULT_ROW_COUNT);
        int rowSize = parseInt(props.get("stats." + tableName + ".rowSizeBytes"), 100);
        int partitionCount = parseInt(props.get("stats." + tableName + ".partitionCount"), 1);
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

    // ===================== Cost 因子（考虑 Worker 并行度） =====================

    @Override
    protected double cpuCostFactor() {
        // Trino JIT 编译 + 并行执行，CPU Cost 随 Worker 数降低
        return 0.2 / Math.max(1, workerCount);
    }

    @Override
    protected double ioCostFactor() {
        // 并行 IO，Cost 随 Worker 数降低
        return 2.0 / Math.max(1, workerCount);
    }

    @Override
    protected double networkCostFactor() {
        // 跨 Worker Shuffle，Network Cost 较高
        return 0.1;
    }
}