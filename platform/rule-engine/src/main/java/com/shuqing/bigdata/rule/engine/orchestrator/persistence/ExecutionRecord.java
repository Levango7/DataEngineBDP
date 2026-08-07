package com.shuqing.bigdata.rule.engine.orchestrator.persistence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DAG 执行记录。
 *
 * <p>记录一次 DAG 执行的元信息，是断点续跑与回放的索引载体。
 * 同一个 DAG 可对应多条 ExecutionRecord（多次执行 / 重跑 / 断点恢复）。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>execId 为单次执行唯一标识，与 dagId 解耦；</li>
 *   <li>trigger 区分首次执行、断点恢复与回放重放；</li>
 *   <li>fromCheckpointId 在 trigger=RESUME 时指向恢复起点；</li>
 *   <li>completedCount/totalNodes 用于进度展示与断点判断。</li>
 * </ul>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionRecord {

    /** 触发方式常量 */
    public static final String TRIGGER_RUN = "RUN";
    public static final String TRIGGER_RESUME = "RESUME";
    public static final String TRIGGER_REPLAY = "REPLAY";

    /** 执行 ID（单次执行唯一） */
    private String execId;

    /** DAG ID */
    private String dagId;

    /** 触发方式：RUN / RESUME / REPLAY */
    private String trigger;

    /** 起始检查点 ID（断点续跑时非空） */
    private String fromCheckpointId;

    /** 执行状态：DRAFT / RUNNING / SUCCESS / FAILED / STOPPED / PAUSED */
    private String status;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 已完成节点数 */
    private int completedCount;

    /** 总节点数 */
    private int totalNodes;

    /**
     * 工厂方法：构建一次新执行记录。
     *
     * @param execId     执行 ID
     * @param dagId      DAG ID
     * @param trigger    触发方式
     * @param totalNodes 总节点数
     * @return 初始状态为 RUNNING 的 ExecutionRecord
     */
    public static ExecutionRecord start(String execId, String dagId, String trigger, int totalNodes) {
        return ExecutionRecord.builder()
                .execId(execId)
                .dagId(dagId)
                .trigger(trigger)
                .status("RUNNING")
                .startedAt(LocalDateTime.now())
                .totalNodes(totalNodes)
                .completedCount(0)
                .build();
    }
}