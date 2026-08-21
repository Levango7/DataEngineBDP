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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Doris 数据源适配器实现——对接 Apache Doris MPP OLAP 引擎。
 *
 * <p>本类实现 {@link DorisAdapter} 接口，基于 {@link AbstractBaseAdapter} 框架
 * 提供 Doris 特有的下推与 Cost 估算能力：</p>
 *
 * <p><b>方言转换（DORIS 方言）：</b>下推 SQL 生成 Doris 兼容的 MySQL 协议 SQL，
 * 支持物化视图路由（自动改写表名为物化视图名）。</p>
 *
 * <p><b>下推能力：</b></p>
 * <ul>
 *   <li>聚合下推：将 GROUP BY + 聚合函数下推到 Doris 物化视图</li>
 *   <li>Join 下推：将同源 Join 下推到 Doris 分布式执行（Colocate Join）</li>
 *   <li>分区裁剪：基于 Doris 动态分区裁剪 Tablet 扫描范围</li>
 *   <li>物化视图路由：自动匹配最优物化视图加速聚合查询</li>
 * </ul>
 *
 * <p><b>Cost 模型：</b>Doris 为 MPP 列存 OLAP 引擎，IO Cost 较低（列存 + 向量化），
 * CPU Cost 较低（向量化执行），Network Cost 中等（分布式 Shuffle）。</p>
 *
 * <pre>
 *   cpuCost   = rows × 0.1（向量化执行 CPU 低）
 *   ioCost    = (rows × rowSize / 64KB) × 1.0（列存 IO 低）
 *   networkCost = rows × rowSize × 0.05（分布式 Shuffle）
 * </pre>
 *
 * @author shuqing-bigdata
 */
public class DorisAdapterImpl extends AbstractBaseAdapter implements DorisAdapter {

    /** 物化视图命名后缀 */
    private static final String MV_SUFFIX = "_mv";

    /** 聚合函数识别正则：sum(col)、count(*)、avg(col) 等 */
    private static final Pattern AGG_FUNC_PATTERN =
            Pattern.compile("(?i)\\b(count|sum|avg|min|max|stddev|variance)\\s*\\([^)]*\\)");

    /** 表 Tablet 数量缓存：表名 → Tablet 数 */
    private final Map<String, Integer> tabletCounts = new LinkedHashMap<>();
    /** 表估算行数缓存：表名 → 行数 */
    private final Map<String, Long> rowCounts = new LinkedHashMap<>();
    /** 物化视图注册表：原表名 → (groupBy 签名 → 物化视图名) */
    private final Map<String, Map<String, String>> materializedViews = new LinkedHashMap<>();
    /** Colocate Group 注册表：表名 → colocate group 名 */
    private final Map<String, String> colocateGroups = new LinkedHashMap<>();

    /**
     * 构造 Doris 适配器。
     *
     * @param config 数据源配置（type 必须为 DORIS）
     */
    public DorisAdapterImpl(DataSourceConfig config) {
        super(config);
        if (config.getType() != DataSourceConfig.Type.DORIS) {
            throw new IllegalArgumentException("数据源类型必须为 DORIS, 实际: " + config.getType());
        }
    }

    // ===================== 方言与下推 SQL 生成 =====================

    @Override
    public SqlDialect getDialect() {
        SqlDialect dialect = getDataSourceConfig().getDialect();
        return dialect == null ? SqlDialect.DORIS : dialect;
    }

    @Override
    protected String buildPushedSql(CustomRelNode relNode, PushDownContext context) {
        String tableName = extractTableName(relNode);
        if (tableName == null) {
            return null;
        }

        // 1. 物化视图路由（聚合查询自动改写为物化视图）
        List<String> groupBy = extractGroupBy(relNode);
        List<String> aggFuncs = extractAggFuncs(relNode);
        String targetTable = tableName;
        if (!groupBy.isEmpty() && !aggFuncs.isEmpty()) {
            targetTable = routeMaterializedView(tableName, groupBy, aggFuncs);
        }

        // 2. 列裁剪（投影下推）
        List<String> projects = extractProjects(relNode);
        String selectClause = buildSelectClause(projects);
        if (!aggFuncs.isEmpty()) {
            // 聚合查询：SELECT 列 = groupBy + aggFuncs
            List<String> selectItems = new ArrayList<>(groupBy);
            selectItems.addAll(aggFuncs);
            selectClause = String.join(", ", selectItems);
        }

        // 3. 谓词下推
        String condition = extractCondition(relNode);
        String whereClause = condition != null ? " WHERE " + condition : "";

        // 4. GROUP BY 下推
        String groupByClause = groupBy.isEmpty() ? "" : " GROUP BY " + String.join(", ", groupBy);

        return "SELECT " + selectClause + " FROM " + targetTable
                + whereClause + groupByClause;
    }

    /**
     * 从 RelNode 子树提取 GROUP BY 列。
     *
     * @param relNode RelNode 子树
     * @return GROUP BY 列列表
     */
    private List<String> extractGroupBy(CustomRelNode relNode) {
        if (relNode == null) {
            return Collections.emptyList();
        }
        if (relNode.getOp() == CustomRelNode.Op.AGGREGATE) {
            // Aggregate 节点的 projects 字段存储 groupBy 列
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
     *
     * @param relNode RelNode 子树
     * @return 聚合函数列表
     */
    private List<String> extractAggFuncs(CustomRelNode relNode) {
        if (relNode == null) {
            return Collections.emptyList();
        }
        List<String> aggFuncs = new ArrayList<>();
        if (relNode.getOp() == CustomRelNode.Op.AGGREGATE
                && relNode.getRemark() != null) {
            // Aggregate 节点的 remark 字段存储聚合函数（逗号分隔）
            for (String part : relNode.getRemark().split(",")) {
                if (AGG_FUNC_PATTERN.matcher(part.trim()).find()) {
                    aggFuncs.add(part.trim());
                }
            }
        }
        for (CustomRelNode child : relNode.getChildren()) {
            aggFuncs.addAll(extractAggFuncs(child));
        }
        return aggFuncs;
    }

    // ===================== Doris 特有方法 =====================

    @Override
    public String routeMaterializedView(String tableName, List<String> groupBy,
                                        List<String> aggFuncs) {
        Objects.requireNonNull(tableName, "tableName");
        if (groupBy == null || groupBy.isEmpty() || aggFuncs == null || aggFuncs.isEmpty()) {
            return tableName;
        }

        // 查找已注册的物化视图
        Map<String, String> mvs = materializedViews.get(tableName);
        if (mvs != null) {
            String signature = buildMvSignature(groupBy, aggFuncs);
            String mv = mvs.get(signature);
            if (mv != null) {
                return mv;
            }
        }

        // 默认：返回 tableName + MV_SUFFIX（假设存在同名物化视图）
        return tableName + MV_SUFFIX;
    }

    /**
     * 构造物化视图匹配签名（groupBy + aggFuncs 的规范化字符串）。
     *
     * @param groupBy  GROUP BY 列
     * @param aggFuncs 聚合函数
     * @return 签名字符串
     */
    private String buildMvSignature(List<String> groupBy, List<String> aggFuncs) {
        return "gb=" + String.join("|", groupBy) + ";agg=" + String.join("|", aggFuncs);
    }

    /**
     * 注册物化视图（用于 YAML 声明式配置）。
     *
     * @param baseTable  原表名
     * @param groupBy    GROUP BY 列
     * @param aggFuncs   聚合函数
     * @param mvName     物化视图名
     */
    public void registerMaterializedView(String baseTable, List<String> groupBy,
                                         List<String> aggFuncs, String mvName) {
        materializedViews.computeIfAbsent(baseTable, k -> new LinkedHashMap<>())
                .put(buildMvSignature(groupBy, aggFuncs), mvName);
    }

    @Override
    public boolean canColocateJoin(String leftTable, String rightTable) {
        if (leftTable == null || rightTable == null) {
            return false;
        }
        // 同数据源前缀检查
        String sourceName = getDataSourceConfig().getName();
        if (!leftTable.startsWith(sourceName) || !rightTable.startsWith(sourceName)) {
            return false;
        }
        // Colocate Group 检查
        String leftGroup = colocateGroups.get(leftTable);
        String rightGroup = colocateGroups.get(rightTable);
        if (leftGroup != null && rightGroup != null) {
            return leftGroup.equals(rightGroup);
        }
        // 默认：同源表视为可 Colocate Join
        return true;
    }

    /**
     * 声明表的 Colocate Group（用于 Colocate Join 判定）。
     *
     * @param tableName 表名
     * @param group     Colocate Group 名
     */
    public void declareColocateGroup(String tableName, String group) {
        colocateGroups.put(tableName, group);
    }

    @Override
    public int getTabletCount(String tableName) {
        if (tableName == null) {
            return 0;
        }
        Integer cached = tabletCounts.get(tableName);
        if (cached != null) {
            return cached;
        }
        // 从配置 properties 读取
        String key = "stats." + tableName + ".tabletCount";
        String value = getDataSourceConfig().getProperties().get(key);
        if (value != null) {
            try {
                int count = Integer.parseInt(value.trim());
                tabletCounts.put(tableName, count);
                return count;
            } catch (NumberFormatException ignored) {
                // 忽略格式错误
            }
        }
        // 默认 64 个 Tablet
        return 64;
    }

    /**
     * 设置表的 Tablet 数量（用于测试与手动配置）。
     *
     * @param tableName  表名
     * @param tabletCount Tablet 数
     */
    public void setTabletCount(String tableName, int tabletCount) {
        tabletCounts.put(tableName, tabletCount);
    }

    @Override
    public long getEstimatedRowCount(String tableName) {
        if (tableName == null) {
            return 0;
        }
        Long cached = rowCounts.get(tableName);
        if (cached != null) {
            return cached;
        }
        // 从统计信息加载
        TableStatistics stats = getStatistics(tableName);
        return stats.getRowCount();
    }

    /**
     * 设置表的估算行数（用于测试与手动配置）。
     *
     * @param tableName 表名
     * @param rowCount  行数
     */
    public void setEstimatedRowCount(String tableName, long rowCount) {
        rowCounts.put(tableName, rowCount);
    }

    // ===================== 统计信息加载 =====================

    @Override
    protected TableStatistics loadStatistics(String tableName) {
        Map<String, String> props = getDataSourceConfig().getProperties();
        long rowCount = parseLong(props.get("stats." + tableName + ".rowCount"),
                TableStatistics.DEFAULT_ROW_COUNT);
        int rowSize = parseInt(props.get("stats." + tableName + ".rowSizeBytes"), 50);
        int partitionCount = parseInt(props.get("stats." + tableName + ".partitionCount"), 1);

        // 缓存行数
        rowCounts.put(tableName, rowCount);

        // 解析列基数（stats.table.column.cardinality=col1:100,col2:1000）
        Map<String, Long> columnCards = new LinkedHashMap<>();
        String cardsStr = props.get("stats." + tableName + ".columnCardinalities");
        if (cardsStr != null && !cardsStr.isBlank()) {
            for (String pair : cardsStr.split(",")) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    try {
                        columnCards.put(kv[0].trim(), Long.parseLong(kv[1].trim()));
                    } catch (NumberFormatException ignored) {
                        // 忽略格式错误
                    }
                }
            }
        }

        return new TableStatistics(rowCount, columnCards, rowSize, partitionCount);
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
        // Doris 向量化执行，CPU Cost 低
        return 0.1;
    }

    @Override
    protected double ioCostFactor() {
        // 列存 + 向量化读取，IO Cost 低
        return 1.0;
    }

    @Override
    protected double networkCostFactor() {
        // 分布式 Shuffle，Network Cost 中等
        return 0.05;
    }
}