package com.levango7.dataenginebdp.rule.engine.orchestrator.persistence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 回放事件。
 *
 * <p>回放事件是单次执行过程中产生的离散事件，按时间顺序排列构成事件流。
 * 回放器通过逐事件重放，重现执行过程，用于调试、审计与可视化。</p>
 *
 * <p>事件类型：
 * <ul>
 *   <li>NODE_START：节点开始执行；</li>
 *   <li>NODE_SUCCESS：节点执行成功；</li>
 *   <li>NODE_FAILED：节点执行失败；</li>
 *   <li>NODE_SKIP：节点被跳过（前驱失败）；</li>
 *   <li>CHECKPOINT：检查点创建；</li>
 *   <li>INTERVENE：人工介入发生；</li>
 *   <li>TOOL_CALL：工具调用发生。</li>
 * </ul>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplayEvent {

    /** 事件类型常量 */
    public static final String KIND_NODE_START = "NODE_START";
    public static final String KIND_NODE_SUCCESS = "NODE_SUCCESS";
    public static final String KIND_NODE_FAILED = "NODE_FAILED";
    public static final String KIND_NODE_SKIP = "NODE_SKIP";
    public static final String KIND_CHECKPOINT = "CHECKPOINT";
    public static final String KIND_INTERVENE = "INTERVENE";
    public static final String KIND_TOOL_CALL = "TOOL_CALL";

    /** 事件序号（同一次执行内从 1 递增） */
    private long seq;

    /** 事件类型 */
    private String kind;

    /** 关联节点 ID（可空） */
    private String nodeId;

    /** 关联执行 ID */
    private String execId;

    /** 时间戳 */
    private LocalDateTime timestamp;

    /** 事件负载（结构化数据，按 kind 不同含义不同） */
    private Map<String, Object> payload;
}