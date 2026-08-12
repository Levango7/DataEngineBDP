package com.levango7.dataenginebdp.ruleengine.scheduler.controller;

import com.levango7.dataenginebdp.ruleengine.scheduler.priority.TaskPriority;
import com.levango7.dataenginebdp.ruleengine.scheduler.service.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 任务状态查询响应 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskStatusResponse {

    private String taskId;
    private String tenantId;
    private String userId;
    private Long ruleId;
    private TaskPriority priority;
    private TaskStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private long durationMs;
    private String errorMessage;
}