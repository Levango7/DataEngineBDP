package com.levango7.dataenginebdp.datastandard.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 数据标准 JPA 实体。
 *
 * <p>描述企业级数据标准定义，包括字段命名、数据类型、值域、格式等约束。
 * 通过 JSON Schema 描述结构化约束，供下游校验引擎消费。
 * 支持多租户隔离（tenantId）与版本管理。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "data_standards")
public class Standard {

    /** 标准唯一标识，由数据库自增生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 标准名称 */
    @Column(nullable = false)
    private String name;

    /** 标准分类（业务域 / 主题域） */
    private String category;

    /** 标准描述 */
    @Column(length = 2000)
    private String description;

    /** 标准规则表达式（如 type=integer; min=1; not_null=true） */
    @Column(length = 4000)
    private String rule;

    /** JSON Schema 约束定义 */
    @Column(length = 8000)
    private String jsonSchema;

    /** 标准版本号（如 1.0.0） */
    private String version;

    /** 标准状态：DRAFT / PUBLISHED / DEPRECATED */
    private String status;

    /**
     * 租户 ID（多租户隔离）。
     *
     * <p>创建时写入，查询 / 更新 / 删除时按此字段过滤，避免跨租户数据泄漏。</p>
     */
    private String tenantId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}