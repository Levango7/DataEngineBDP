package com.levango7.dataenginebdp.rule.engine.orchestrator.persistence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回放轨迹。
 *
 * <p>单次执行的完整事件流，由 {@link ReplayService} 装配。
 * 前端按事件序列逐帧重放，重现执行过程。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplayTrace {

    /** 执行 ID */
    private String execId;

    /** DAG ID */
    private String dagId;

    /** 事件序列（按 seq 升序） */
    private List<ReplayEvent> events;

    /** 起始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;
}