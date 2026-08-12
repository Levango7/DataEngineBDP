package com.levango7.dataenginebdp.rule.engine.orchestrator.dag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * DAG 节点模型。
 *
 * <p>表示编排图中的一个任务节点，包含任务执行所需的核心元数据：
 * 任务标识、执行命令、超时、重试策略以及运行时状态。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>id 为节点唯一标识，用于在图中引用；</li>
 *   <li>taskType 决定调度器如何分派执行（如 SHELL/HTTP/SPARK/RULE）；</li>
 *   <li>status 反映运行时状态，由调度器写入，初始为 PENDING；</li>
 *   <li>params 为任务参数透传字段，供具体执行器解释。</li>
 * </ul>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DagNode {

    /** 节点状态枚举字符串常量，避免魔法字符串 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    /** 节点唯一标识（图中不可重复） */
    private String id;

    /** 节点展示名称 */
    private String name;

    /** 任务类型：SHELL / HTTP / SPARK / RULE 等，由执行器解释 */
    private String taskType;

    /** 任务执行命令或 URL，具体含义由 taskType 决定 */
    private String command;

    /** 任务超时时间（秒），<=0 表示不限制 */
    private long timeoutSeconds;

    /** 最大重试次数（不含首次执行） */
    private int maxRetries;

    /** 退避策略名称：FIXED / EXPONENTIAL */
    private String backoffStrategy;

    /** 退避基准间隔（毫秒） */
    private long backoffIntervalMs;

    /** 任务参数，透传给执行器 */
    private Map<String, Object> params;

    /** 运行时状态：PENDING / RUNNING / SUCCESS / FAILED / SKIPPED */
    private String status;

    /** 节点入度（运行时由调度器维护，初始 0） */
    private int inDegree;

    /** 实际开始执行时间 */
    private LocalDateTime startedAt;

    /** 实际结束执行时间 */
    private LocalDateTime finishedAt;

    /** 最近一次执行错误信息 */
    private String errorMessage;

    /**
     * 工厂方法：构建一个默认配置的待执行节点。
     *
     * <p>默认 timeoutSeconds=0（不限）、maxRetries=0、backoffStrategy=FIXED、
     * backoffIntervalMs=1000、status=PENDING。便于调用方快速构造。</p>
     *
     * @param id       节点 ID
     * @param name     节点名称
     * @param taskType 任务类型
     * @param command  执行命令
     * @return 具备默认值的 DagNode
     */
    public static DagNode of(String id, String name, String taskType, String command) {
        return DagNode.builder()
                .id(id)
                .name(name)
                .taskType(taskType)
                .command(command)
                .timeoutSeconds(0L)
                .maxRetries(0)
                .backoffStrategy("FIXED")
                .backoffIntervalMs(1000L)
                .status(STATUS_PENDING)
                .inDegree(0)
                .build();
    }
}