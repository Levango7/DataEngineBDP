package com.shuqing.bigdata.ruleengine.scheduler.controller;

import com.shuqing.bigdata.ruleengine.scheduler.service.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 任务提交响应 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskSubmitResponse {

    private String taskId;
    private TaskStatus status;
    private String tenantId;
    private LocalDateTime createdAt;
    private String errorMessage;
}