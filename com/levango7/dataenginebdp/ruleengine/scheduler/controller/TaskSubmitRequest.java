package com.shuqing.bigdata.ruleengine.scheduler.controller;

import com.shuqing.bigdata.ruleengine.scheduler.priority.TaskPriority;
import lombok.Data;

import java.util.Map;

/**
 * 任务提交请求 DTO。
 */
@Data
public class TaskSubmitRequest {

    /** 关联规则 ID */
    private Long ruleId;

    /** 优先级；null 时默认 MEDIUM */
    private TaskPriority priority;

    /** 所需 CPU 核数（默认 1.0） */
    private Double requiredCpu;

    /** 所需内存 MB（默认 512） */
    private Long requiredMemory;

    /** 执行上下文 */
    private Map<String, Object> context;

    /** 租户 ID；null 时由服务从 security.TenantContext 取 */
    private String tenantId;

    /** 提交用户 ID */
    private String userId;
}