package com.shuqing.bigdata.streambatch.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Singular;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DAG 执行结果（包含所有节点执行结果与 snapshot 隔离验证结论）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DagExecutionResult {

    /** DAG ID。 */
    private String dagId;

    /** 整体执行状态。 */
    private ExecutionStatus status;

    /** 各节点执行结果。 */
    @Singular("nodeResult")
    private List<TaskExecutionResult> nodeResults;

    /** snapshot 隔离验证是否通过。 */
    private boolean snapshotIsolationValid;

    /** snapshot 隔离验证详情。 */
    private String snapshotIsolationDetail;

    /** 开始时间。 */
    private Instant startTime;

    /** 结束时间。 */
    private Instant endTime;

    /** 总耗时毫秒。 */
    private long totalDurationMs;

    /**
     * 判断整体是否成功。
     *
     * @return {@code true} 表示所有节点成功且 snapshot 隔离验证通过
     */
    public boolean isSuccess() {
        if (status != ExecutionStatus.SUCCESS) {
            return false;
        }
        return nodeResults.stream().allMatch(TaskExecutionResult::isSuccess);
    }

    /**
     * 查找指定节点的执行结果。
     *
     * @param nodeId 节点 ID
     * @return 执行结果；未找到返回 {@code null}
     */
    public TaskExecutionResult findNodeResult(String nodeId) {
        return nodeResults.stream()
                .filter(r -> nodeId.equals(r.getNodeId()))
                .findFirst()
                .orElse(null);
    }
}