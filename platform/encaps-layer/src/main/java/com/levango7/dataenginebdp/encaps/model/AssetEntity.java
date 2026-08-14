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
 * 数据资产实体（ROADMAP 前后端接线：/assets）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "data_asset")
public class AssetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 资产名。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 类型：table/api/model/report/dataset。 */
    @Column(nullable = false, length = 32)
    private String type;

    /** 提供方租户 ID。 */
    @Column(nullable = false, length = 64)
    private String owner;

    /** 描述。 */
    @Column(length = 512)
    private String description;

    /** 状态：published/pending/offline。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 质量评分（0-100）。 */
    @Column(nullable = false)
    private Integer qualityScore;

    /** 安全等级。 */
    @Column(nullable = false, length = 16)
    private String securityLevel;

    /** 完整契约 JSON（schema/sample/pricing 等）。 */
    @Column(length = 32768)
    private String fullJson;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
