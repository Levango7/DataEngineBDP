package com.levango7.dataenginebdp.streambatch.service;

import com.levango7.dataenginebdp.streambatch.dag.StreamBatchDagOrchestrator;
import com.levango7.dataenginebdp.streambatch.model.DagExecutionResult;
import com.levango7.dataenginebdp.streambatch.model.StreamBatchDag;
import com.levango7.dataenginebdp.streambatch.run.DagRunService;
import com.levango7.dataenginebdp.streambatch.run.DagRunType;
import com.levango7.dataenginebdp.common.security.TenantContext;
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

    /** DAG 执行历史（复合 key：tenantId|dagId → 执行结果，in-memory，租户隔离）。 */
    private final Map<String, DagExecutionResult> executionHistory = new ConcurrentHashMap<>();

    /**
     * 构造租户隔离的执行历史复合 key。
     *
     * @param tenantId 租户 ID
     * @param dagId    DAG ID
     * @return 复合 key
     */
    private static String historyKey(String tenantId, String dagId) {
        return (tenantId == null ? "" : tenantId) + "|" + dagId;
    }

    /**
     * 提交并执行流批 DAG（手动触发，自动绑定当前租户）。
     *
     * @param dag 流批 DAG
     * @return 执行结果
     */
    public DagExecutionResult submitDag(StreamBatchDag dag) {
        String tenantId = TenantContext.getTenantId();
        log.info("提交流批 DAG: dagId={}, name={}, tenant={}", dag.getDagId(), dag.getName(), tenantId);
        DagExecutionResult result = orchestrator.orchestrate(dag);
        executionHistory.put(historyKey(tenantId, dag.getDagId()), result);
        // 任务运维中心：执行完成落库（手动触发类型）
        dagRunService.recordRun(dag, result, DagRunType.MANUAL, "api", null, null);
        return result;
    }

    /**
     * 查询 DAG 执行结果（租户隔离）。
     *
     * @param dagId DAG ID
     * @return 执行结果；不存在或跨租户返回 {@code null}
     */
    public DagExecutionResult getDagResult(String dagId) {
        String tenantId = TenantContext.getTenantId();
        return executionHistory.get(historyKey(tenantId, dagId));
    }

    /**
     * 获取当前租户的所有 DAG 执行历史（租户隔离）。
     *
     * @return DAG 执行历史 Map（key 为 dagId）
     */
    public Map<String, DagExecutionResult> getAllHistory() {
        String tenantId = TenantContext.getTenantId();
        String prefix = (tenantId == null ? "" : tenantId) + "|";
        Map<String, DagExecutionResult> scoped = new ConcurrentHashMap<>();
        executionHistory.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                scoped.put(k.substring(prefix.length()), v);
            }
        });
        return scoped;
    }
}