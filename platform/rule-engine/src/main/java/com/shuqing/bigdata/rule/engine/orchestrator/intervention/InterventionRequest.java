package com.shuqing.bigdata.rule.engine.orchestrator.intervention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 人工介入请求。
 *
 * <p>当 DAG 执行到 {@link HumanInterventionNode} 时，调度器创建一个介入请求并阻塞等待，
 * 直到人工审批（批准 / 驳回）或超时。</p>
 *
 * <p>状态流转：
 * <pre>
 *   PENDING --approve--> APPROVED  (调度器继续执行下游)
 *   PENDING --reject----> REJECTED (调度器跳过下游，标记介入节点 FAILED)
 *   PENDING --timeout---> TIMEOUT  (按超时策略处理)
 * </pre>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterventionRequest {

    /** 状态常量 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    /** 决定常量（与 STATUS_APPROVED/REJECTED 对齐，用于提交审批入参） */
    public static final String DECISION_APPROVED = "APPROVED";
    public static final String DECISION_REJECTED = "REJECTED";

    /** 介入 ID */
    private String id;

    /** DAG ID */
    private String dagId;

    /** 关联执行 ID */
    private String execId;

    /** 节点 ID */
    private String nodeId;

    /** 节点名称 */
    private String nodeName;

    /** 介入原因（暂停时由节点写入） */
    private String reason;

    /** 上下文数据（供审批人参考，如上游输出摘要） */
    private Map<String, Object> context;

    /** 状态 */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 处理时间 */
    private LocalDateTime resolvedAt;

    /** 审批人 */
    private String approver;

    /** 审批意见 */
    private String comment;

    /** 覆盖参数（批准时可调整下游参数） */
    private Map<String, Object> overrideParams;

    /**
     * 工厂方法：构建一个待处理介入请求。
     *
     * @param dagId    DAG ID
     * @param execId   执行 ID
     * @param nodeId   节点 ID
     * @param nodeName 节点名称
     * @param reason   介入原因
     * @param context  上下文数据
     * @return 状态为 PENDING 的 InterventionRequest
     */
    public static InterventionRequest pending(String dagId, String execId, String nodeId,
                                              String nodeName, String reason,
                                              Map<String, Object> context) {
        return InterventionRequest.builder()
                .id(java.util.UUID.randomUUID().toString())
                .dagId(dagId)
                .execId(execId)
                .nodeId(nodeId)
                .nodeName(nodeName)
                .reason(reason)
                .context(context)
                .status(STATUS_PENDING)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * 是否已处理（非 PENDING）。
     */
    public boolean isResolved() {
        return !STATUS_PENDING.equals(status);
    }

    /**
     * 是否批准。
     */
    public boolean isApproved() {
        return STATUS_APPROVED.equals(status);
    }
}