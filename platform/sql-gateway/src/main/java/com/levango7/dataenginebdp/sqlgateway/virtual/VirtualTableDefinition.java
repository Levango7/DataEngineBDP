package com.levango7.dataenginebdp.sqlgateway.virtual;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 虚拟表定义（JPA Entity）。
 *
 * <p>一个虚拟表将外部数据源（MySQL/Oracle/JDBC/Kafka/REST）中的数据
 * 映射为一张可被 SQL 网关查询的"表"。用户在 SQL 中直接引用虚拟表名，
 * 网关通过适配器透明地将查询下推到外部源。</p>
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code tableName}：虚拟表名（租户内唯一），SQL 中使用该名称引用；</li>
 *   <li>{@code tenantId}：所属租户 ID，实现租户级虚拟表隔离；</li>
 *   <li>{@code dataSourceType}：外部数据源类型；</li>
 *   <li>{@code connectionConfig}：连接信息 JSON（数据源类型不同，结构不同）；</li>
 *   <li>{@code sourceObject}：外部源对象（如 MySQL 表名、Kafka topic、REST URL）；</li>
 *   <li>{@code columns}：列定义列表；</li>
 *   <li>{@code materializationStrategy}：物化策略（NONE/FULL/INCREMENTAL/MANUAL）；</li>
 *   <li>{@code refreshIntervalSeconds}：定时刷新间隔（仅物化表生效）；</li>
 *   <li>{@code enabled}：是否启用。</li>
 * </ul>
 *
 * <p>通过 Spring Data JPA 持久化到关系型数据库，支持运行期通过 REST API 增删改查。</p>
 *
 * @author shuqing-bigdata
 */
@Data
@NoArgsConstructor
@Entity
@Table(
        name = "virtual_table_definitions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_virtual_table_tenant_name",
                columnNames = {"tenant_id", "table_name"}
        ),
        indexes = {
                @Index(name = "idx_virtual_table_tenant", columnList = "tenant_id"),
                @Index(name = "idx_virtual_table_source", columnList = "data_source_type")
        }
)
public class VirtualTableDefinition {

    /**
     * 自增主键。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 虚拟表名（租户内唯一）。SQL 中通过该名称引用虚拟表。
     */
    @Column(nullable = false)
    private String tableName;

    /**
     * 所属租户 ID，实现租户级虚拟表隔离。
     */
    @Column(nullable = false)
    private String tenantId;

    /**
     * 外部数据源类型。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataSourceType dataSourceType;

    /**
     * 连接信息 JSON 字符串。
     *
     * <p>不同数据源类型结构不同：</p>
     * <ul>
     *   <li>MYSQL/ORACLE/JDBC：{@code {"url":"...","username":"...","password":"...","driver":"..."}}；</li>
     *   <li>KAFKA：{@code {"bootstrapServers":"...","groupId":"...","topic":"..."}}；</li>
     *   <li>REST：{@code {"baseUrl":"...","method":"GET","headers":{...},"authToken":"..."}}。</li>
     * </ul>
     */
    @Lob
    @Column(nullable = false)
    private String connectionConfig;

    /**
     * 外部源对象名。
     *
     * <p>不同数据源类型含义不同：</p>
     * <ul>
     *   <li>MYSQL/ORACLE/JDBC：外部表名（如 {@code user_db.orders}）；</li>
     *   <li>KAFKA：topic 名；</li>
     *   <li>REST：API 路径（如 {@code /api/v1/orders}）。</li>
     * </ul>
     */
    @Column(nullable = false)
    private String sourceObject;

    /**
     * 虚拟表列定义列表。
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "virtual_table_columns",
            joinColumns = @JoinColumn(name = "table_id")
    )
    private List<ColumnDefinition> columns = new ArrayList<>();

    /**
     * 物化策略：NONE（不物化）/ FULL（全量物化）/ INCREMENTAL（增量物化）/ MANUAL（手动刷新）。
     */
    @Column(nullable = false)
    private String materializationStrategy = "NONE";

    /**
     * 物化表名（物化策略非 NONE 时生效，物化数据存储于该表）。
     */
    private String materializedTableName;

    /**
     * 定时刷新间隔（秒），仅物化表生效。{@code null} 或 {@code <=0} 表示不自动刷新。
     */
    private Integer refreshIntervalSeconds;

    /**
     * 最近一次物化刷新时间（UTC）。
     */
    private Instant lastRefreshTime;

    /**
     * 是否启用。
     */
    @Column(nullable = false)
    private Boolean enabled = true;

    /**
     * 虚拟表描述（可选）。
     */
    private String description;

    /**
     * 创建时间（UTC）。
     */
    private Instant createdAt;

    /**
     * 更新时间（UTC）。
     */
    private Instant updatedAt;

    /**
     * 全参构造器（不含 id 与时间戳，用于显式构造新虚拟表）。
     *
     * @param tableName                虚拟表名
     * @param tenantId                 租户 ID
     * @param dataSourceType           数据源类型
     * @param connectionConfig         连接信息 JSON
     * @param sourceObject             外部源对象
     * @param columns                  列定义列表
     * @param materializationStrategy  物化策略
     * @param enabled                  是否启用
     * @param description              描述
     */
    public VirtualTableDefinition(String tableName, String tenantId,
                                  DataSourceType dataSourceType,
                                  String connectionConfig, String sourceObject,
                                  List<ColumnDefinition> columns,
                                  String materializationStrategy,
                                  Boolean enabled, String description) {
        this.tableName = tableName;
        this.tenantId = tenantId;
        this.dataSourceType = dataSourceType;
        this.connectionConfig = connectionConfig;
        this.sourceObject = sourceObject;
        this.columns = columns != null ? columns : new ArrayList<>();
        this.materializationStrategy = materializationStrategy != null ? materializationStrategy : "NONE";
        this.enabled = enabled != null ? enabled : true;
        this.description = description;
    }

    /**
     * 判断虚拟表是否处于可用状态（启用且列定义非空）。
     *
     * @return {@code true} 表示可参与查询
     */
    public boolean isAvailable() {
        return Boolean.TRUE.equals(enabled) && columns != null && !columns.isEmpty();
    }

    /**
     * 判断是否需要物化（物化策略非 NONE）。
     *
     * @return {@code true} 表示该虚拟表需要物化
     */
    public boolean needsMaterialization() {
        return materializationStrategy != null && !"NONE".equalsIgnoreCase(materializationStrategy);
    }
}