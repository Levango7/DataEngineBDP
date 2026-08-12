package com.levango7.dataenginebdp.streambatch.service;

import com.levango7.dataenginebdp.streambatch.dag.StreamBatchDagOrchestrator;
import com.levango7.dataenginebdp.streambatch.model.DagExecutionResult;
import com.levango7.dataenginebdp.streambatch.model.StreamBatchDag;
import com.levango7.dataenginebdp.streambatch.run.DagRunService;
import com.levango7.dataenginebdp.streambatch.run.DagRunType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流批统一编排服务。
 *
 * <p>封装 DAG 编排器的业务逻辑，提供 DAG 提交、查询、取消等操作，
 * 维护 DAG 执行历史（in-memory，生产环境可持久化）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamBatchOrchestrationService {

    private final StreamBatchDagOrchestrator orchestrator;
    private final DagRunService dagRunService;

    /** DAG 执行历史（dagId → 执行结果，in-memory）。 */
    private final Map<String, DagExecutionResult> executionHistory = new ConcurrentHashMap<>();

    /**
     * 提交并执行流批 DAG（手动触发）。
     *
     * @param dag 流批 DAG
     * @return 执行结果
     */
    public DagExecutionResult submitDag(StreamBatchDag dag) {
        log.info("提交流批 DAG: dagId={}, name={}", dag.getDagId(), dag.getName());
        DagExecutionResult result = orchestrator.orchestrate(dag);
        executionHistory.put(dag.getDagId(), result);
        // 任务运维中心：执行完成落库（手动触发类型）
        dagRunService.recordRun(dag, result, DagRunType.MANUAL, "api", null, null);
        return result;
    }

    /**
     * 查询 DAG 执行结果。
     *
     * @param dagId DAG ID
     * @return 执行结果；不存在返回 {@code null}
     */
    public DagExecutionResult getDagResult(String dagId) {
        return executionHistory.get(dagId);
    }

    /**
     * 获取所有 DAG 执行历史。
     *
     * @return DAG 执行历史 Map
     */
    public Map<String, DagExecutionResult> getAllHistory() {
        return new ConcurrentHashMap<>(executionHistory);
    }
}