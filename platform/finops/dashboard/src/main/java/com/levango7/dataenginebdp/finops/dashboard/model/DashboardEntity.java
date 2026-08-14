package com.levango7.dataenginebdp.finops.dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 看板实体（ROADMAP 前后端接线：/dashboards）。
 *
 * <p>panels 以 JSON 文本存储（前端 Panel 数组），
 * 创建/更新时原样透传。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "bi_dashboard")
public class DashboardEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 看板名。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 描述。 */
    @Column(length = 512)
    private String description;

    /** 组件列表（JSON 文本）。 */
    @Column(length = 16384)
    private String panelsJson;

    /** 租户 ID。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
