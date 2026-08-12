package com.levango7.dataenginebdp.rule.engine.orchestrator.persistence;

import com.levango7.dataenginebdp.rule.engine.orchestrator.scheduler.TaskResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DAG 执行检查点。
 *
 * <p>检查点是执行过程中的快照，记录已完成节点及其结果，用于：
 * <ul>
 *   <li>断点续跑：从检查点恢复，跳过已完成节点，仅执行剩余节点；</li>
 *   <li>回放：以检查点为回放起点，重现后续事件流；</li>
 *   <li>容错：执行中断后不丢失已完成进度。</li>
 * </ul>
 * </p>
 *
 * <p>检查点类型：
 * <ul>
 *   <li>AUTO：调度器自动打点（每个节点成功后）；</li>
 *   <li>MANUAL：用户手动打点；</li>
 *   <li>INTERVENTION：人工介入暂停时自动打点。</li>
 * </ul>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Checkpoint {

    /** 检查点类型常量 */
    public static final String KIND_AUTO = "AUTO";
    public static final String KIND_MANUAL = "MANUAL";
    public static final String KIND_INTERVENTION = "INTERVENTION";

    /** 检查点 ID */
    private String id;

    /** DAG ID */
    private String dagId;

    /** 关联执行 ID */
    private String execId;

    /** 类型：AUTO / MANUAL / INTERVENTION */
    private String kind;

    /** 已完成节点 ID 列表（按完成顺序） */
    private List<String> completedNodes;

    /** 已完成节点结果快照：nodeId -> TaskResult */
    private Map<String, TaskResult> results;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 备注 */
    private String note;
}