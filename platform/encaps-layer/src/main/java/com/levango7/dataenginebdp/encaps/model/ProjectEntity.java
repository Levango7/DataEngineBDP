package com.levango7.dataenginebdp.encaps.model;

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
 * 项目实体（ROADMAP 前后端接线：/projects）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "project")
public class ProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目名。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 业务域。 */
    @Column(nullable = false, length = 64)
    private String domain;

    /** 描述。 */
    @Column(length = 512)
    private String description;

    /** 状态。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 数据集数量（统计值，可后续从 catalog 汇总）。 */
    @Column(nullable = false)
    private Integer datasets;

    /** 作业数量（统计值，可后续从 scheduler 汇总）。 */
    @Column(nullable = false)
    private Integer jobs;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
