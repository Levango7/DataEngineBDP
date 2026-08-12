package com.levango7.dataenginebdp.sqlgateway.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 路由规则 JPA Entity。
 *
 * <p>定义一条 SQL 模式匹配规则，命中后将请求路由到指定引擎。
 * 通过 Spring Data JPA 持久化到关系型数据库（开发环境 H2，生产环境 PostgreSQL）。</p>
 *
 * @author shuqing-bigdata
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "route_rules")
public class RouteRule {

    /**
     * 规则 ID，由数据库自增生成。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * SQL 模式匹配表达式（前缀或正则，由路由服务决定语义）。
     */
    private String pattern;

    /**
     * 目标引擎：{@code trino} / {@code doris}。
     */
    private String engine;

    /**
     * 优先级，数值越小优先级越高。
     */
    private Integer priority;

    /**
     * 是否启用。
     */
    private Boolean enabled;

    /**
     * 全参构造器（不含 id，用于显式构造新规则）。
     */
    public RouteRule(String pattern, String engine, Integer priority, Boolean enabled) {
        this.pattern = pattern;
        this.engine = engine;
        this.priority = priority;
        this.enabled = enabled;
    }
}
