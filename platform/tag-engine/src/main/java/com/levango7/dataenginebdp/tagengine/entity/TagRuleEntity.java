package com.levango7.dataenginebdp.tagengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 标签规则 JPA Entity。
 *
 * <p>规则标签的计算逻辑定义。对应详细设计 §3 规则标签、§4 标签计算。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "tag_rules")
public class TagRuleEntity {

    /** 规则唯一标识 */
    @Id
    @Column(length = 64)
    private String ruleId;

    /** 所属标签 ID */
    @Column(nullable = false, length = 64)
    private String tagId;

    /** 租户 ID */
    @Column(nullable = false, length = 64)
    private String tenantId;

    /** 规则条件表达式 */
    @Lob
    @Column(nullable = false)
    private String condition;

    /** 命中后赋值（列名 rule_value，避开 H2/SQL 保留字 value） */
    @Column(name = "rule_value", nullable = false, length = 256)
    private String value;

    /** 优先级 */
    @Column
    private Integer priority;

    /** 扩展属性（JSON 字符串） */
    @Lob
    @Column
    private String propertiesJson;

    /** 规则状态：ACTIVE / DISABLED */
    @Column(length = 16)
    private String status;

    /** 创建时间 */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}