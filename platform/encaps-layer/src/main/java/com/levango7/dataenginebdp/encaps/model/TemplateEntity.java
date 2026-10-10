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
 * 行业应用模板实体（ROADMAP 前后端接线：/templates）。
 *
 * <p>meta 字段独立存储，parameters/dataFlow/computeLogic/visualization/readme/schema
 * 以 JSON 文本整体保存（对齐前端 Template 契约）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "industry_template")
public class TemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模板名。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 行业：finance/retail/manufacturing/government/iot。 */
    @Column(nullable = false, length = 32)
    private String industry;

    /** 版本。 */
    @Column(nullable = false, length = 32)
    private String version;

    /** 描述。 */
    @Column(length = 512)
    private String description;

    /** 作者。 */
    @Column(length = 128)
    private String author;

    /** 状态：dev/review/catalog/deprecated。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 安装次数（统计）。 */
    @Column(nullable = false)
    private Integer installCount;

    /** 完整模板 JSON（parameters/dataFlow/computeLogic 等）。 */
    @Column(length = 32768)
    private String fullJson;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
