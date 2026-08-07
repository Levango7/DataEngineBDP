package com.shuqing.bigdata.sqlgateway.rewrite;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 物化视图定义（JPA Entity）。
 *
 * <p>描述一个物化视图的元数据，供查询改写器与匹配器使用。核心字段：</p>
 * <ul>
 *   <li>{@code viewName}：物化视图名（如 {@code mv_sales_daily}）；</li>
 *   <li>{@code sourceTable}：底层源表名（如 {@code sales}）；</li>
 *   <li>{@code definitionSql}：物化视图定义 SQL（即 {@code CREATE MATERIALIZED VIEW ... AS SELECT ...}）；</li>
 *   <li>{@code querySql}：物化视图查询体（即 {@code SELECT} 部分，便于匹配器解析）；</li>
 *   <li>{@code dimensionColumns}：维度列（逗号分隔，如 {@code region,product}）；</li>
 *   <li>{@code measureColumns}：指标列（逗号分隔，如 {@code sum(amount),count(*)}）；</li>
 *   <li>{@code refreshStrategy}：刷新策略（{@code FULL}/{@code INCREMENTAL}/{@code ON_DEMAND}）；</li>
 *   <li>{@code lastRefreshTime}：最近一次刷新时间；</li>
 *   <li>{@code enabled}：是否启用自动路由；</li>
 *   <li>{@code priority}：优先级，数值越小优先级越高。</li>
 * </ul>
 *
 * <p>通过 Spring Data JPA 持久化到关系型数据库，支持运行期通过 REST API 增删改查，
 * 实现"视图定义控制台管理"需求。</p>
 *
 * @author shuqing-bigdata
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "materialized_view_definitions")
public class MaterializedViewDefinition {

    /**
     * 自增主键。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 物化视图名（唯一标识）。
     */
    private String viewName;

    /**
     * 底层源表名。
     */
    private String sourceTable;

    /**
     * 物化视图定义 SQL（CREATE MATERIALIZED VIEW ... AS SELECT ...）。
     */
    @Lob
    private String definitionSql;

    /**
     * 物化视图查询体（SELECT 部分），便于匹配器解析。
     */
    @Lob
    private String querySql;

    /**
     * 维度列（逗号分隔）。
     */
    private String dimensionColumns;

    /**
     * 指标列（逗号分隔）。
     */
    private String measureColumns;

    /**
     * 刷新策略：FULL / INCREMENTAL / ON_DEMAND。
     */
    private String refreshStrategy;

    /**
     * 最近一次刷新时间（UTC）。
     */
    private Instant lastRefreshTime;

    /**
     * 是否启用自动路由。
     */
    private Boolean enabled;

    /**
     * 优先级，数值越小优先级越高。
     */
    private Integer priority;

    /**
     * 视图描述（可选）。
     */
    private String description;

    /**
     * 全参构造器（不含 id 与 lastRefreshTime，用于显式构造新视图定义）。
     *
     * @param viewName          视图名
     * @param sourceTable       源表名
     * @param definitionSql     定义 SQL
     * @param querySql          查询体 SQL
     * @param dimensionColumns  维度列
     * @param measureColumns    指标列
     * @param refreshStrategy   刷新策略
     * @param enabled           是否启用
     * @param priority          优先级
     * @param description       描述
     */
    public MaterializedViewDefinition(String viewName, String sourceTable,
                                      String definitionSql, String querySql,
                                      String dimensionColumns, String measureColumns,
                                      String refreshStrategy, Boolean enabled,
                                      Integer priority, String description) {
        this.viewName = viewName;
        this.sourceTable = sourceTable;
        this.definitionSql = definitionSql;
        this.querySql = querySql;
        this.dimensionColumns = dimensionColumns;
        this.measureColumns = measureColumns;
        this.refreshStrategy = refreshStrategy;
        this.enabled = enabled;
        this.priority = priority;
        this.description = description;
    }

    /**
     * 将逗号分隔的维度列拆分为列表。
     *
     * @return 维度列列表；空时返回空列表（不返回 null）
     */
    public java.util.List<String> dimensionList() {
        return splitCsv(dimensionColumns);
    }

    /**
     * 将逗号分隔的指标列拆分为列表。
     *
     * @return 指标列列表；空时返回空列表
     */
    public java.util.List<String> measureList() {
        return splitCsv(measureColumns);
    }

    /**
     * 判断视图是否处于可用状态（启用且已刷新过）。
     *
     * @return {@code true} 表示可参与匹配
     */
    public boolean isAvailable() {
        return Boolean.TRUE.equals(enabled) && lastRefreshTime != null;
    }

    /**
     * 拆分逗号分隔字符串，去除空白与空项。
     *
     * @param csv 逗号分隔字符串
     * @return 拆分后的列表
     */
    private static java.util.List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return java.util.Collections.emptyList();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}