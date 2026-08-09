package com.levango7.dataenginebdp.ruleengine.model;

import lombok.Data;

import java.util.Map;

/**
 * 规则执行请求 POJO。
 */
@Data
public class RuleExecutionRequest {

    /** 待执行规则 ID */
    private Long ruleId;

    /** 执行上下文（变量绑定） */
    private Map<String, Object> context;

    /** 租户 ID（多租户隔离） */
    private String tenantId;
}