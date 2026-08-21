package com.shuqing.bigdata.rule.engine.orchestrator.scheduler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 单个任务执行结果。
 *
 * <p>由 {@link TaskExecutor} 返回，承载执行状态、输出数据与耗时。
 * 调度器据此决定是否触发下游节点、是否重试、是否告警。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskResult {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    /** 节点 ID */
    private String nodeId;

    /** 执行状态 */
    private String status;

    /** 输出数据，供下游节点消费 */
    private Map<String, Object> output;

    /** 错误信息 */
    private String errorMessage;

    /** 执行耗时（毫秒） */
    private long durationMs;

    /** 完成时间 */
    private LocalDateTime finishedAt;

    /**
     * 快速构造成功结果。
     */
    public static TaskResult success(String nodeId, Map<String, Object> output, long durationMs) {
        return TaskResult.builder()
                .nodeId(nodeId)
                .status(STATUS_SUCCESS)
                .output(output)
                .durationMs(durationMs)
                .finishedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 快速构造失败结果。
     */
    public static TaskResult failure(String nodeId, String errorMessage, long durationMs) {
        return TaskResult.builder()
                .nodeId(nodeId)
                .status(STATUS_FAILED)
                .errorMessage(errorMessage)
                .durationMs(durationMs)
                .finishedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 是否成功。
     */
    public boolean isSuccess() {
        return STATUS_SUCCESS.equals(status);
    }
}