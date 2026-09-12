package com.levango7.dataenginebdp.finops.billing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 账单数据模型（JPA Entity）。
 *
 * <p>表示某租户在某账期内的成本账单，含账单头信息与明细项。
 * 账单由 {@code BillingGenerator} 从 Prometheus 指标采集并计算生成，
 * 持久化到关系型数据库（H2 开发 / PostgreSQL 生产）。</p>
 *
 * <p>字段说明（对齐任务要求：租户ID/资源类型/用量/单价/金额/账期）：</p>
 * <ul>
 *   <li>{@code tenantId} 租户 ID</li>
 *   <li>{@code billingPeriod} 账期（如 2026-08）</li>
 *   <li>{@code items} 账单明细项（每项含资源类型/用量/单价/金额）</li>
 *   <li>{@code totalAmount} 账单总金额</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "billing",
    indexes = {
        @Index(name = "idx_billing_tenant", columnList = "tenant_id"),
        @Index(name = "idx_billing_period", columnList = "billing_period"),
        @Index(name = "idx_billing_tenant_period", columnList = "tenant_id,billing_period"),
        @Index(name = "idx_billing_status", columnList = "status")
    }
)
public class BillingModel {

    /** 账单 ID（UUID，主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private String id;

    /** 租户 ID（来自 TenantContext，不信任请求参数） */
    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    /** 账期（格式 yyyy-MM，如 2026-08） */
    @Column(name = "billing_period", nullable = false, length = 16)
    private String billingPeriod;

    /** 账单明细项（资源类型/用量/单价/金额），以 JSON 字符串大对象存储 */
    @Lob
    @Column(name = "items_json", nullable = false)
    private String itemsJson;

    /** 账单总金额（人民币元，精度 0.0001） */
    @Column(name = "total_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalAmount;

    /** 账单状态：GENERATED / SETTLED / FAILED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 账单生成时间（UTC） */
    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /** 账期起始时间（UTC，含） */
    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    /** 账期结束时间（UTC，不含） */
    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    /** 备注/说明 */
    @Column(name = "note", length = 1024)
    private String note;
}