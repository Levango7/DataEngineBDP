package com.shuqing.bigdata.ruleengine.scheduler.service;

import com.shuqing.bigdata.ruleengine.scheduler.priority.TaskPriority;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 调度任务领域模型。
 *
 * <p>承载一次规则执行请求在调度引擎内的全部元数据：身份（tenantId/taskId）、
 * 调度属性（priority/requiredCpu/requiredMemory）、执行上下文（ruleId/context）、
 * 生命周期（status/createdAt/startedAt/finishedAt/errorMessage）。</p>
 *
 * <p>设计说明：</p>
 * <ul>
 *   <li>{@code taskId} 使用 UUID，保证多 worker 并发出队时全局唯一，便于跨服务追踪</li>
 *   <li>资源需求字段（{@code requiredCpu}/{@code requiredMemory}）内联，便于
 *       {@code ResourceAllocator} 在入队前做准入校验，避免入队后因资源不足反复出队回退</li>
 *   <li>所有时间字段使用 {@link LocalDateTime}，与现有 {@code RuleExecutionResult} 风格一致</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchedulerTask {

    /** 任务唯一标识，提交时若为空则由调度器生成 UUID */
    private String taskId;

    /** 租户 ID，来自 {@code TenantContext}，用于多租户隔离调度 */
    private String tenantId;

    /** 提交用户 ID，用于审计 */
    private String userId;

    /** 关联的规则 ID（对应 {@code Rule.id}） */
    private Long ruleId;

    /** 任务优先级，默认 {@link TaskPriority#MEDIUM} */
    @Builder.Default
    private TaskPriority priority = TaskPriority.MEDIUM;

    /** 所需 CPU 核数（可小数，如 0.5 表示半核）；&gt; 0 */
    private double requiredCpu;

    /** 所需内存（MB）；&gt; 0 */
    private long requiredMemory;

    /** 规则执行上下文，透传给 {@code RuleExecutor} */
    private Map<String, Object> context;

    /** 任务状态 */
    @Builder.Default
    private TaskStatus status = TaskStatus.QUEUED;

    /** 入队时间 */
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 开始执行时间 */
    private LocalDateTime startedAt;

    /** 完成时间（成功/失败/取消均填充） */
    private LocalDateTime finishedAt;

    /** 执行耗时（毫秒），完成时填充 */
    private long durationMs;

    /** 失败/拒绝/取消原因 */
    private String errorMessage;

    /**
     * 生成默认 taskId（UUID 去横线）。
     *
     * @return 32 位十六进制字符串
     */
    public static String generateTaskId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}