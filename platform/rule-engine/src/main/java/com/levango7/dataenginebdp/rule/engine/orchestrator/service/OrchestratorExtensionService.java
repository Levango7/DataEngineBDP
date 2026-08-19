package com.levango7.dataenginebdp.rule.engine.orchestrator.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 编排引擎扩展业务服务。
 *
 * <p>对齐前端 {@code orchestrator-viz.ts}，提供 Agent 思考链、工具调用记录、
 * 人工介入、检查点、执行历史与回放轨迹的内存存储能力。
 * 与 {@link OrchestratorService} 风格一致，MVP 阶段使用内存仓库
 * （{@link ConcurrentHashMap} + {@link CopyOnWriteArrayList}），
 * 后续可替换为 JPA Repository。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>每个仓库按 dagId 索引，列表使用 CopyOnWriteArrayList 保证读多写少场景下的线程安全；</li>
 *   <li>检查点 ID、执行 ID 使用 UUID 保证全局唯一；</li>
 *   <li>intervene 通过更新对应介入请求的 status 字段实现审批流转；</li>
 *   <li>resume 不真正重启调度器，仅返回恢复语义的节点结果映射，供前端展示。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class OrchestratorExtensionService {

    /** Agent 思考链仓库：dagId -> 思考步骤列表 */
    private final Map<String, List<Map<String, Object>>> thoughtsStore = new ConcurrentHashMap<>();
    /** 工具调用记录仓库：dagId -> 工具调用列表 */
    private final Map<String, List<Map<String, Object>>> toolCallsStore = new ConcurrentHashMap<>();
    /** 人工介入请求仓库：dagId -> 介入请求列表 */
    private final Map<String, List<Map<String, Object>>> interventionStore = new ConcurrentHashMap<>();
    /** 检查点仓库：dagId -> 检查点列表 */
    private final Map<String, List<Map<String, Object>>> checkpointStore = new ConcurrentHashMap<>();
    /** 执行历史仓库：dagId -> 执行历史列表 */
    private final Map<String, List<Map<String, Object>>> executionStore = new ConcurrentHashMap<>();
    /** 回放轨迹仓库：dagId -> execId -> 回放轨迹 */
    private final Map<String, Map<String, Map<String, Object>>> replayStore = new ConcurrentHashMap<>();

    /* ------------------------------ Agent 思考链 ------------------------------ */

    /**
     * 拉取 Agent 思考链。
     *
     * @param dagId DAG ID
     * @return 思考步骤列表；不存在返回空列表
     */
    public List<Map<String, Object>> getThoughts(String dagId) {
        return new ArrayList<>(thoughtsStore.getOrDefault(dagId, List.of()));
    }

    /**
     * 记录一条 Agent 思考步骤。
     *
     * @param dagId   DAG ID
     * @param thought 思考步骤负载
     * @return 已记录的思考步骤
     */
    public Map<String, Object> recordThought(String dagId, Map<String, Object> thought) {
        Map<String, Object> entry = new LinkedHashMap<>(thought);
        entry.putIfAbsent("id", UUID.randomUUID().toString());
        entry.putIfAbsent("dagId", dagId);
        entry.putIfAbsent("timestamp", LocalDateTime.now().toString());
        thoughtsStore.computeIfAbsent(dagId, k -> new CopyOnWriteArrayList<>()).add(entry);
        log.info("thought recorded dagId={} id={}", dagId, entry.get("id"));
        return entry;
    }

    /* ------------------------------ 工具调用记录 ------------------------------ */

    /**
     * 拉取工具调用记录。
     *
     * @param dagId DAG ID
     * @return 工具调用记录列表；不存在返回空列表
     */
    public List<Map<String, Object>> getToolCalls(String dagId) {
        return new ArrayList<>(toolCallsStore.getOrDefault(dagId, List.of()));
    }

    /**
     * 记录一次工具调用。
     *
     * @param dagId DAG ID
     * @param call  工具调用负载
     * @return 已记录的工具调用
     */
    public Map<String, Object> recordToolCall(String dagId, Map<String, Object> call) {
        Map<String, Object> entry = new LinkedHashMap<>(call);
        entry.putIfAbsent("id", UUID.randomUUID().toString());
        entry.putIfAbsent("dagId", dagId);
        entry.putIfAbsent("timestamp", LocalDateTime.now().toString());
        toolCallsStore.computeIfAbsent(dagId, k -> new CopyOnWriteArrayList<>()).add(entry);
        log.info("tool call recorded dagId={} id={}", dagId, entry.get("id"));
        return entry;
    }

    /* ------------------------------ 人工介入 ------------------------------ */

    /**
     * 查询待处理人工介入请求。
     *
     * @param dagId DAG ID
     * @return 介入请求列表；不存在返回空列表
     */
    public List<Map<String, Object>> getInterventions(String dagId) {
        return new ArrayList<>(interventionStore.getOrDefault(dagId, List.of()));
    }

    /**
     * 提交人工审批。
     *
     * <p>若 payload 中携带 {@code requestId}，则更新对应介入请求的 status；
     * 否则视为新建一条审批记录。</p>
     *
     * @param dagId   DAG ID
     * @param payload 审批载荷（含 decision、requestId 等字段）
     * @return 更新后的介入请求
     */
    public Map<String, Object> submitIntervention(String dagId, Map<String, Object> payload) {
        Object requestId = payload.get("requestId");
        List<Map<String, Object>> interventions = interventionStore.get(dagId);
        if (requestId != null && interventions != null) {
            for (Map<String, Object> req : interventions) {
                if (requestId.equals(req.get("id"))) {
                    req.put("status", payload.getOrDefault("decision", "APPROVED"));
                    req.put("reviewedAt", LocalDateTime.now().toString());
                    req.putAll(payload);
                    log.info("intervention updated dagId={} requestId={} status={}",
                            dagId, requestId, req.get("status"));
                    return new LinkedHashMap<>(req);
                }
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>(payload);
        entry.putIfAbsent("id", requestId != null ? requestId : UUID.randomUUID().toString());
        entry.putIfAbsent("dagId", dagId);
        entry.putIfAbsent("status", payload.getOrDefault("decision", "PENDING"));
        entry.putIfAbsent("createdAt", LocalDateTime.now().toString());
        interventionStore.computeIfAbsent(dagId, k -> new CopyOnWriteArrayList<>()).add(entry);
        log.info("intervention submitted dagId={} id={} status={}",
                dagId, entry.get("id"), entry.get("status"));
        return entry;
    }

    /* ------------------------------ 检查点 ------------------------------ */

    /**
     * 拉取检查点列表。
     *
     * @param dagId DAG ID
     * @return 检查点列表；不存在返回空列表
     */
    public List<Map<String, Object>> getCheckpoints(String dagId) {
        return new ArrayList<>(checkpointStore.getOrDefault(dagId, List.of()));
    }

    /**
     * 手动打检查点。
     *
     * @param dagId DAG ID
     * @param body  含 note 字段
     * @return 新建的检查点
     */
    public Map<String, Object> createCheckpoint(String dagId, Map<String, Object> body) {
        String checkpointId = "cp-" + UUID.randomUUID();
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("id", checkpointId);
        cp.put("dagId", dagId);
        cp.put("kind", "MANUAL");
        cp.put("note", body == null ? null : body.get("note"));
        cp.put("createdAt", LocalDateTime.now().toString());
        if (body != null) {
            cp.putAll(body);
            cp.put("id", checkpointId);
            cp.put("dagId", dagId);
            cp.put("kind", "MANUAL");
        }
        checkpointStore.computeIfAbsent(dagId, k -> new CopyOnWriteArrayList<>()).add(cp);
        log.info("checkpoint created dagId={} id={}", dagId, checkpointId);
        return cp;
    }

    /**
     * 从检查点恢复执行。
     *
     * <p>不真正重启调度器，仅返回恢复语义的节点结果映射，供前端展示。
     * 若 checkpointId 对应的检查点存在，则附带其快照信息。</p>
     *
     * @param dagId DAG ID
     * @param body  含 checkpointId 字段
     * @return 恢复结果映射
     */
    public Map<String, Object> resumeFromCheckpoint(String dagId, Map<String, Object> body) {
        Object checkpointId = body == null ? "" : body.getOrDefault("checkpointId", "");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dagId", dagId);
        result.put("resumed", true);
        result.put("checkpointId", checkpointId);
        result.put("resumedAt", LocalDateTime.now().toString());
        List<Map<String, Object>> checkpoints = checkpointStore.get(dagId);
        if (checkpointId != null && !"".equals(checkpointId) && checkpoints != null) {
            for (Map<String, Object> cp : checkpoints) {
                if (checkpointId.equals(cp.get("id"))) {
                    result.put("checkpoint", new LinkedHashMap<>(cp));
                    break;
                }
            }
        }
        log.info("resume from checkpoint dagId={} checkpointId={}", dagId, checkpointId);
        return result;
    }

    /* ------------------------------ 执行历史 ------------------------------ */

    /**
     * 拉取执行历史。
     *
     * @param dagId DAG ID
     * @return 执行历史列表；不存在返回空列表
     */
    public List<Map<String, Object>> getExecutions(String dagId) {
        return new ArrayList<>(executionStore.getOrDefault(dagId, List.of()));
    }

    /**
     * 记录一次执行。
     *
     * @param dagId DAG ID
     * @param exec  执行负载
     * @return 已记录的执行
     */
    public Map<String, Object> recordExecution(String dagId, Map<String, Object> exec) {
        Map<String, Object> entry = new LinkedHashMap<>(exec);
        entry.putIfAbsent("execId", UUID.randomUUID().toString());
        entry.putIfAbsent("dagId", dagId);
        entry.putIfAbsent("startedAt", LocalDateTime.now().toString());
        executionStore.computeIfAbsent(dagId, k -> new CopyOnWriteArrayList<>()).add(entry);
        log.info("execution recorded dagId={} execId={}", dagId, entry.get("execId"));
        return entry;
    }

    /* ------------------------------ 回放轨迹 ------------------------------ */

    /**
     * 拉取单次回放轨迹。
     *
     * @param dagId  DAG ID
     * @param execId 执行 ID
     * @return 回放轨迹；不存在则返回仅含 dagId/execId 与空 events 的占位轨迹
     */
    public Map<String, Object> getReplayTrace(String dagId, String execId) {
        Map<String, Map<String, Object>> byDag = replayStore.get(dagId);
        if (byDag != null) {
            Map<String, Object> trace = byDag.get(execId);
            if (trace != null) {
                return new LinkedHashMap<>(trace);
            }
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("execId", execId);
        trace.put("dagId", dagId);
        trace.put("events", List.of());
        return trace;
    }

    /**
     * 记录回放轨迹（供内部事件流写入使用）。
     *
     * @param dagId  DAG ID
     * @param execId 执行 ID
     * @param trace  回放轨迹
     * @return 已记录的回放轨迹
     */
    public Map<String, Object> recordReplayTrace(String dagId, String execId, Map<String, Object> trace) {
        Map<String, Object> entry = new LinkedHashMap<>(trace);
        entry.putIfAbsent("execId", execId);
        entry.putIfAbsent("dagId", dagId);
        entry.putIfAbsent("events", List.of());
        replayStore.computeIfAbsent(dagId, k -> new ConcurrentHashMap<>()).put(execId, entry);
        log.info("replay trace recorded dagId={} execId={}", dagId, execId);
        return entry;
    }

    /* ------------------------------ 清理 ------------------------------ */

    /**
     * 清除指定 DAG 的所有扩展记录。
     *
     * @param dagId DAG ID
     */
    public void clearByDag(String dagId) {
        thoughtsStore.remove(dagId);
        toolCallsStore.remove(dagId);
        interventionStore.remove(dagId);
        checkpointStore.remove(dagId);
        executionStore.remove(dagId);
        replayStore.remove(dagId);
    }
}