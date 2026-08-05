package com.shuqing.bigdata.tagengine.entity;

import com.shuqing.bigdata.tagengine.model.TagType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 标签定义 JPA Entity。
 *
 * <p>持久化到关系型数据库（开发环境 H2，生产环境 PostgreSQL）。
 * 标签定义是元数据，标签计算结果写入 Doris 宽表。</p>
 *
 * <p>对应详细设计 §3 标签模型。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "tag_definitions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenantId", "name"})
})
public class TagDefinitionEntity {

    /** 标签唯一标识（UUID 风格字符串，由引擎生成） */
    @Id
    @Column(length = 64)
    private String tagId;

    /** 租户 ID */
    @Column(nullable = false, length = 64)
    private String tenantId;

    /** 标签名称（同租户内逻辑唯一） */
    @Column(nullable = false, length = 128)
    private String name;

    /** 标签展示名 */
    @Column(length = 256)
    private String displayName;

    /** 标签类型 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TagType type;

    /** 标签值域（JSON 数组字符串） */
    @Lob
    @Column
    private String valueDomainJson;

    /** 标签描述 */
    @Column(length = 1024)
    private String description;

    /** Doris 宽表列名 */
    @Column(length = 128)
    private String columnName;

    /** 标签状态：DRAFT / ACTIVE / ARCHIVED */
    @Column(length = 16)
    private String status;

    /** 创建时间 */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}