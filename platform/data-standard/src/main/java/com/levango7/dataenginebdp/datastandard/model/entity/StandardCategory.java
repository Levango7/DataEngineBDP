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
 * 数据标准分类 JPA 实体。
 *
 * <p>按业务域 / 主题域对数据标准进行分类组织，支持层级分类结构。
 * 支持多租户隔离（tenantId）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "standard_categories")
public class StandardCategory {

    /** 分类唯一标识，由数据库自增生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 分类名称 */
    @Column(nullable = false)
    private String name;

    /** 分类编码（唯一标识，如 user-domain / order-domain） */
    @Column(nullable = false)
    private String code;

    /** 父分类 ID（null 表示顶级分类） */
    private Long parentId;

    /** 分类描述 */
    @Column(length = 2000)
    private String description;

    /**
     * 租户 ID（多租户隔离）。
     *
     * <p>创建时写入，查询时按此字段过滤，避免跨租户数据泄漏。</p>
     */
    private String tenantId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}