package com.levango7.dataenginebdp.tagengine.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 标签规则。
 *
 * <p>规则标签的计算逻辑定义，由若干条件 + 优先级组成。
 * 引擎按规则优先级从高到低匹配，命中即赋值。</p>
 *
 * <p>对应详细设计 §3 规则标签、§4 标签计算。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TagRule {

    /** 规则唯一标识 */
    private String ruleId;

    /** 所属标签 ID */
    @NotBlank(message = "tagId must not be blank")
    private String tagId;

    /** 租户 ID */
    private String tenantId;

    /**
     * 规则条件表达式，DSL 形式。
     * <p>例如 {@code "total_amount >= 5000 AND last_order_ts >= now() - 30d"}。</p>
     * <p>由 DorisSqlGenerator 翻译为 SQL WHERE 子句。</p>
     */
    private String condition;

    /**
     * 命中后赋给标签的值。
     * <p>枚举型标签为值域中的某一项；数值型标签为数字字符串。</p>
     */
    private String value;

    /** 优先级，数字越大优先级越高；同标签内多规则按优先级降序匹配 */
    private Integer priority;

    /**
     * 规则扩展属性，例如 SQL 模板参数、UDF 名等。
     * <p>用于支持复杂规则而避免 SQL 注入。</p>
     */
    private Map<String, Object> properties;

    /** 规则状态：ACTIVE / DISABLED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}