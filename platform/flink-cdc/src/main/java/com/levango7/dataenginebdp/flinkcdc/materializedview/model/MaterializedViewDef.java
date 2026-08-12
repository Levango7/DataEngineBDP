package com.levango7.dataenginebdp.flinkcdc.materializedview.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 物化视图定义，描述一个 Doris 物化视图的完整元数据。
 *
 * <p>包含以下核心要素：</p>
 * <ul>
 *   <li>源表：物化视图基于哪些源表构建（支持多表 JOIN）</li>
 *   <li>聚合维度：GROUP BY 列，定义聚合粒度</li>
 *   <li>指标列：每个指标由 (列名, 聚合类型) 二元组描述</li>
 *   <li>刷新策略：定义何时触发刷新（定时/事件/手动）</li>
 *   <li>目标表：物化视图在 Doris 中的存储表名</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * MaterializedViewDef def = MaterializedViewDef.builder()
 *     .name("mv_order_daily_summary")
 *     .database("report")
 *     .addSourceTable("shop.orders")
 *     .addDimension("order_date")
 *     .addDimension("region")
 *     .addMetric("total_amount", AggregationType.SUM)
 *     .addMetric("order_count", AggregationType.COUNT)
 *     .refreshPolicy(RefreshPolicy.scheduled(Duration.ofMinutes(5)))
 *     .build();
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public class MaterializedViewDef implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 物化视图名称（唯一标识）。 */
    private String name;

    /** 目标 Doris 数据库名。 */
    private String database;

    /** 目标 Doris 表名（物化视图存储表）。 */
    private String targetTable;

    /** 源表列表（格式 db.table，支持多表）。 */
    private List<String> sourceTables = new ArrayList<>();

    /** 聚合维度列（GROUP BY 列）。 */
    private List<String> dimensions = new ArrayList<>();

    /** 指标列：有序映射（列名 → 聚合类型），保持声明顺序。 */
    private Map<String, AggregationType> metrics = new LinkedHashMap<>();

    /** 刷新策略。 */
    private RefreshPolicy refreshPolicy = RefreshPolicy.manual();

    /** 可选的 WHERE 过滤条件。 */
    private String filterCondition;

    /** 是否启用。 */
    private boolean enabled = true;

    /** 默认构造器，供反序列化使用。 */
    public MaterializedViewDef() {
    }

    /**
     * 全参构造器。
     *
     * @param name            物化视图名称
     * @param database        目标数据库
     * @param targetTable     目标表名
     * @param sourceTables    源表列表
     * @param dimensions      维度列
     * @param metrics         指标列映射
     * @param refreshPolicy   刷新策略
     * @param filterCondition 过滤条件
     * @param enabled         是否启用
     */
    public MaterializedViewDef(String name, String database, String targetTable,
                               List<String> sourceTables, List<String> dimensions,
                               Map<String, AggregationType> metrics,
                               RefreshPolicy refreshPolicy, String filterCondition,
                               boolean enabled) {
        this.name = name;
        this.database = database;
        this.targetTable = targetTable;
        this.sourceTables = sourceTables == null ? new ArrayList<>() : new ArrayList<>(sourceTables);
        this.dimensions = dimensions == null ? new ArrayList<>() : new ArrayList<>(dimensions);
        this.metrics = metrics == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metrics);
        this.refreshPolicy = refreshPolicy == null ? RefreshPolicy.manual() : refreshPolicy;
        this.filterCondition = filterCondition;
        this.enabled = enabled;
    }

    /**
     * 生成该物化视图的 SELECT SQL（用于全量刷新）。
     *
     * <p>生成形如：</p>
     * <pre>{@code
     * SELECT order_date, region, SUM(amount) AS total_amount, COUNT(*) AS order_count
     * FROM shop.orders
     * WHERE status = 'paid'
     * GROUP BY order_date, region
     * }</pre>
     *
     * @return SELECT SQL
     * @throws IllegalStateException 若定义不完整
     */
    public String toSelectSql() {
        validate();
        StringBuilder sql = new StringBuilder("SELECT ");
        // 维度列
        sql.append(String.join(", ", dimensions));
        // 指标列
        for (Map.Entry<String, AggregationType> entry : metrics.entrySet()) {
            if (!sql.toString().endsWith("SELECT ")) {
                sql.append(", ");
            }
            String metricCol = entry.getKey();
            AggregationType aggType = entry.getValue();
            // COUNT 类型的指标列名可能是虚拟的，使用 COUNT(*)
            String aggExpr = aggType == AggregationType.COUNT
                    ? "COUNT(*)"
                    : aggType.sqlFunction() + "(" + metricCol + ")";
            sql.append(aggExpr).append(" AS ").append(metricCol);
        }
        sql.append(" FROM ").append(String.join(", ", sourceTables));
        if (filterCondition != null && !filterCondition.isBlank()) {
            sql.append(" WHERE ").append(filterCondition);
        }
        if (!dimensions.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", dimensions));
        }
        return sql.toString();
    }

    /**
     * 生成 CREATE MATERIALIZED VIEW DDL（Doris 语法）。
     *
     * @return DDL 语句
     */
    public String toCreateDdl() {
        validate();
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE MATERIALIZED VIEW `").append(database).append("`.`").append(targetTable).append("` ");
        ddl.append("AS ").append(toSelectSql());
        return ddl.toString();
    }

    /**
     * 校验定义的完整性。
     *
     * @throws IllegalStateException 若缺少必要字段
     */
    public void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("物化视图名称不能为空");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalStateException("目标数据库不能为空");
        }
        if (targetTable == null || targetTable.isBlank()) {
            throw new IllegalStateException("目标表名不能为空");
        }
        if (sourceTables.isEmpty()) {
            throw new IllegalStateException("源表列表不能为空");
        }
        if (dimensions.isEmpty() && metrics.isEmpty()) {
            throw new IllegalStateException("维度列和指标列不能同时为空");
        }
        if (refreshPolicy != null) {
            refreshPolicy.validate();
        }
    }

    /**
     * 判断该物化视图是否为纯聚合视图（含 GROUP BY）。
     *
     * @return 若有维度列返回 true
     */
    public boolean isAggregationView() {
        return !dimensions.isEmpty();
    }

    /**
     * 获取所有需要监听 CDC 的源表（启用状态下）。
     *
     * @return 源表列表；若未启用返回空列表
     */
    public List<String> cdcListenTables() {
        return enabled ? Collections.unmodifiableList(sourceTables) : Collections.emptyList();
    }

    // ===== getter / setter =====

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public List<String> getSourceTables() {
        return Collections.unmodifiableList(sourceTables);
    }

    public void setSourceTables(List<String> sourceTables) {
        this.sourceTables = sourceTables == null ? new ArrayList<>() : new ArrayList<>(sourceTables);
    }

    public List<String> getDimensions() {
        return Collections.unmodifiableList(dimensions);
    }

    public void setDimensions(List<String> dimensions) {
        this.dimensions = dimensions == null ? new ArrayList<>() : new ArrayList<>(dimensions);
    }

    public Map<String, AggregationType> getMetrics() {
        return Collections.unmodifiableMap(metrics);
    }

    public void setMetrics(Map<String, AggregationType> metrics) {
        this.metrics = metrics == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metrics);
    }

    public RefreshPolicy getRefreshPolicy() {
        return refreshPolicy;
    }

    public void setRefreshPolicy(RefreshPolicy refreshPolicy) {
        this.refreshPolicy = refreshPolicy;
    }

    public String getFilterCondition() {
        return filterCondition;
    }

    public void setFilterCondition(String filterCondition) {
        this.filterCondition = filterCondition;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MaterializedViewDef that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(name, that.name)
                && Objects.equals(database, that.database)
                && Objects.equals(targetTable, that.targetTable)
                && Objects.equals(sourceTables, that.sourceTables)
                && Objects.equals(dimensions, that.dimensions)
                && Objects.equals(metrics, that.metrics)
                && Objects.equals(refreshPolicy, that.refreshPolicy)
                && Objects.equals(filterCondition, that.filterCondition);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, database, targetTable, sourceTables, dimensions,
                metrics, refreshPolicy, filterCondition, enabled);
    }

    @Override
    public String toString() {
        return "MaterializedViewDef{name='" + name + "', database='" + database + '\''
                + ", targetTable='" + targetTable + '\''
                + ", sourceTables=" + sourceTables
                + ", dimensions=" + dimensions
                + ", metrics=" + metrics
                + ", refreshPolicy=" + refreshPolicy
                + ", enabled=" + enabled + '}';
    }

    /**
     * 创建 Builder 实例。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 链式 Builder。
     */
    public static final class Builder {
        private final MaterializedViewDef def = new MaterializedViewDef();

        public Builder name(String name) {
            def.name = name;
            return this;
        }

        public Builder database(String database) {
            def.database = database;
            return this;
        }

        public Builder targetTable(String targetTable) {
            def.targetTable = targetTable;
            return this;
        }

        public Builder addSourceTable(String table) {
            def.sourceTables.add(table);
            return this;
        }

        public Builder addDimension(String dimension) {
            def.dimensions.add(dimension);
            return this;
        }

        public Builder addMetric(String column, AggregationType type) {
            def.metrics.put(column, type);
            return this;
        }

        public Builder refreshPolicy(RefreshPolicy policy) {
            def.refreshPolicy = policy;
            return this;
        }

        public Builder filterCondition(String condition) {
            def.filterCondition = condition;
            return this;
        }

        public Builder enabled(boolean enabled) {
            def.enabled = enabled;
            return this;
        }

        public MaterializedViewDef build() {
            return def;
        }
    }
}