package com.levango7.dataenginebdp.finops.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 查询计量记录（sql-gateway 上报，按租户+clientRequestId 唯一防重）。
 *
 * <p>支撑按查询计费：字节扫描量（或估算）经分层定价聚合为租户账单。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "query_metering", indexes = {
        @Index(name = "idx_metering_tenant_ts", columnList = "tenantId,createdAt"),
        @Index(name = "idx_metering_engine", columnList = "engine")
})
public class QueryMeteringRecord {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 租户 ID（来自 sql-gateway 请求上下文，非响应体）。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    /** 命名空间（可空）。 */
    @Column(length = 64)
    private String namespace;

    /** 执行引擎（trino / doris）。 */
    @Column(nullable = false, length = 16)
    private String engine;

    /** SQL 指纹（可选，hex 摘要）。 */
    @Column(length = 64)
    private String sqlHash;

    /** 扫描字节数（trino 真实或估算）。 */
    @Column(nullable = false)
    private long bytesScanned;

    /** 是否为估算值（true=耗时×系数，false=引擎真实值）。 */
    @Column(nullable = false)
    private boolean estimated;

    /** 查询耗时（毫秒）。 */
    @Column
    private Long durationMs;

    /** 客户端请求 ID（sql-gateway 生成 UUID，防重幂等键）。 */
    @Column(nullable = false, length = 64)
    private String clientRequestId;

    /** 记录创建时间。 */
    @Column(nullable = false)
    private Instant createdAt;

    /** 单测等场景设置 createdAt 用。 */
    public void touchCreatedAt() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}