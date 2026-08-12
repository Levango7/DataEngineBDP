package com.levango7.dataenginebdp.ruleengine.model;

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
 * 规则定义 JPA Entity。
 *
 * <p>支持三种规则类型：DQ（数据质量检查）、MASK（数据脱敏）、ALERT（告警）。
 * 通过 Spring Data JPA 持久化到关系型数据库（开发环境 H2，生产环境 PostgreSQL）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rules")
public class Rule {

    /** 规则唯一标识，由数据库自增生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则名称 */
    private String name;

    /** 规则描述 */
    private String description;

    /** 规则类型：DQ / MASK / ALERT */
    private String type;

    /** 规则表达式（MVP 阶段仅做存储，不实际执行） */
    private String expression;

    /** 严重级别：INFO / WARN / ERROR */
    private String severity;

    /** 是否启用 */
    private Boolean enabled;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
