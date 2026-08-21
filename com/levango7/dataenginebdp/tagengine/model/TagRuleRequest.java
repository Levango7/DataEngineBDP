package com.shuqing.bigdata.tagengine.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 创建标签规则请求 DTO。
 *
 * <p>对应接口 {@code POST /api/v1/tags/{id}/rules}。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TagRuleRequest {

    /** 租户 ID（可由 Token 注入，DTO 中保留以方便测试） */
    private String tenantId;

    /** 规则条件表达式 */
    @NotBlank(message = "condition must not be blank")
    private String condition;

    /** 命中后赋的值 */
    @NotBlank(message = "value must not be blank")
    private String value;

    /** 优先级 */
    private Integer priority;

    /** 扩展属性 */
    private Map<String, Object> properties;
}