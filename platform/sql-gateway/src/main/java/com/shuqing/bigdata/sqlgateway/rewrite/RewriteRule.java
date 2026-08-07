package com.shuqing.bigdata.sqlgateway.rewrite;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 改写规则定义（JPA Entity）。
 *
 * <p>定义一条查询改写规则，描述在何种条件下将查询改写为基于物化视图的等价查询。
 * 通过 Spring Data JPA 持久化到关系型数据库，支持运行期通过 REST API 增删改查。</p>
 *
 * <p>规则核心字段：</p>
 * <ul>
 *   <li>{@code ruleName}：规则唯一名称，便于引用与日志追踪；</li>
 *   <li>{@code ruleType}：规则类型，见 {@link RewriteRuleType}；</li>
 *   <li>{@code targetView}：命中后路由到的物化视图名；</li>
 *   <li>{@code sourceTablePattern}：源表名匹配模式（支持前缀/正则）；</li>
 *   <li>{@code priority}：优先级，数值越小优先级越高；</li>
 *   <li>{@code enabled}：是否启用。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "rewrite_rules")
public class RewriteRule {

    /**
     * 规则 ID，由数据库自增生成。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 规则名称（唯一标识，便于引用）。
     */
    private String ruleName;

    /**
     * 规则类型，对应 {@link RewriteRuleType} 枚举名称。
     */
    private String ruleType;

    /**
     * 命中后路由到的物化视图名。
     */
    private String targetView;

    /**
     * 源表名匹配模式（前缀或正则，由匹配器决定语义）。
     */
    private String sourceTablePattern;

    /**
     * 优先级，数值越小优先级越高。
     */
    private Integer priority;

    /**
     * 是否启用。
     */
    private Boolean enabled;

    /**
     * 规则描述（可选，便于运维理解）。
     */
    private String description;

    /**
     * 全参构造器（不含 id，用于显式构造新规则）。
     *
     * @param ruleName            规则名称
     * @param ruleType            规则类型
     * @param targetView          目标物化视图名
     * @param sourceTablePattern  源表匹配模式
     * @param priority            优先级
     * @param enabled             是否启用
     * @param description         规则描述
     */
    public RewriteRule(String ruleName, String ruleType, String targetView,
                       String sourceTablePattern, Integer priority,
                       Boolean enabled, String description) {
        this.ruleName = ruleName;
        this.ruleType = ruleType;
        this.targetView = targetView;
        this.sourceTablePattern = sourceTablePattern;
        this.priority = priority;
        this.enabled = enabled;
        this.description = description;
    }

    /**
     * 解析规则类型为枚举，无效时返回 {@code null}。
     *
     * @return 规则类型枚举；无效返回 {@code null}
     */
    public RewriteRuleType typeEnum() {
        if (ruleType == null) {
            return null;
        }
        try {
            return RewriteRuleType.valueOf(ruleType.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}