package com.levango7.dataenginebdp.rule.engine.orchestrator.persistence;

import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagGraph;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagNode;
import com.levango7.dataenginebdp.rule.engine.orchestrator.scheduler.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DAG 执行持久化与回放服务。
 *
 * <p>提供执行记录管理、检查点管理、回放事件记录与查询能力。
 * 是断点续跑与回放机制的核心载体。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>使用内存存储（ConcurrentHashMap），与 OrchestratorService 风格一致，
 *       MVP 阶段不引入 JPA，后续可替换为 Repository；</li>
 *   <li>每个执行 ID 维护独立的事件序列号生成器，避免全局竞争；</li>
 *   <li>检查点同时记录已完成节点列表与结果快照，恢复时直接注入调度器；</li>
 *   <li>自动检查点在每个节点成功后产生，可配置关闭以减少存储压力。</li>
 * </ul>
 * </p>
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    /** 执行记录仓库：execId -> ExecutionRecord */
    private final Map<String, ExecutionRecord> execStore = new ConcurrentHashMap<>();
    /** 检查点仓库：checkpointId -> Checkpoint */
    private final Map<String, Checkpoint> checkpointStore = new ConcurrentHashMap<>();
    /** 按 DAG 索引的执行 ID 列表：dagId -> List<execId> */
    private final Map<String, List<String>> execIndexByDag = new ConcurrentHashMap<>();
    /** 按 DAG 索引的检查点 ID 列表：dagId -> List<checkpointId> */
    private final Map<String, List<String>> checkpointIndexByDag = new ConcurrentHashMap<>();
    /** 回放事件仓库：execId -> List<ReplayEvent> */
    private final Map<String, List<ReplayEvent>> eventStore = new ConcurrentHashMap<>();
    /** 事件序列号生成器：execId -> AtomicLong */
    private final Map<String, AtomicLong> seqGenerators = new ConcurrentHashMap<>();

    /* ------------------------------ 执行记录 ------------------------------ */

    /**
     * 开始一次新执行，生成并保存执行记录。
     *
     * @param dagId      DAG ID
     * @param trigger    触发方式
     * @param totalNodes 总节点数
     * @return 新建的执行记录
     */
    public ExecutionRecord startExecution(String dagId, String trigger, int totalNodes) {
        String execId = UUID.randomUUID().toString();
        ExecutionRecord record = ExecutionRecord.start(execId, dagId, trigger, totalNodes);
        execStore.put(execId, record);
        execIndexByDag.computeIfAbsent(dagId, k -> Collections.synchronizedList(new ArrayList<>())).add(execId);
        eventStore.put(execId, Collections.synchronizedList(new ArrayList<>()));
        seqGenerators.put(execId, new AtomicLong(0));
        log.info("execution started execId={} dagId={} trigger={}", execId, dagId, trigger);
        return record;
    }

    /**
     * 更新执行进度。
     *
     * @param execId         执行 ID
     * @param completedCount 已完成节点数
     */
    public void updateProgress(String execId, int completedCount) {
        ExecutionRecord record = execStore.get(execId);
        if (record != null) {
            record.setCompletedCount(completedCount);
        }
    }

    /**
     * 结束执行。
     *
     * @param execId 执行 ID
     * @param status 最终状态
     */
    public void finishExecution(String execId, String status) {
        ExecutionRecord record = execStore.get(execId);
        if (record != null) {
            record.setStatus(status);
            record.setFinishedAt(LocalDateTime.now());
            log.info("execution finished execId={} status={} completed={}/{}",
                    execId, status, record.getCompletedCount(), record.getTotalNodes());
        }
    }

    /**
     * 查询指定 DAG 的所有执行记录。
     *
     * @param dagId DAG ID
     * @return 执行记录列表（按开始时间降序）
     */
    public List<ExecutionRecord> listExecutions(String dagId) {
        List<String> execIds = execIndexByDag.getOrDefault(dagId, List.of());
        List<ExecutionRecord> result = new ArrayList<>();
        for (String id : execIds) {
            ExecutionRecord r = execStore.get(id);
            if (r != null) {
                result.add(r);
            }
        }
        result.sort((a, b) -> {
            LocalDateTime ta = a.getStartedAt();
            LocalDateTime tb = b.getStartedAt();
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });
        return result;
    }

    /**
     * 查询单条执行记录。
     *
     * @param execId 执行 ID
     * @return 执行记录；不存在返回 null
     */
    public ExecutionRecord getExecution(String execId) {
        return execStore.get(execId);
    }

    /* ------------------------------ 回放事件 ------------------------------ */

    /**
     * 记录一个回放事件。
     *
     * @param execId  执行 ID
     * @param kind    事件类型
     * @param nodeId  关联节点 ID（可空）
     * @param payload 事件负载
     * @return 已记录的事件
     */
    public ReplayEvent recordEvent(String execId, String kind, String nodeId, Map<String, Object> payload) {
        List<ReplayEvent> events = eventStore.get(execId);
        if (events == null) {
            log.warn("event store not found for execId={}, drop event kind={}", execId, kind);
            return null;
        }
        AtomicLong seqGen = seqGenerators.computeIfAbsent(execId, k -> new AtomicLong(0));
        ReplayEvent event = ReplayEvent.builder()
                .seq(seqGen.incrementAndGet())
                .kind(kind)
                .nodeId(nodeId)
                .execId(execId)
                .timestamp(LocalDateTime.now())
                .payload(payload)
                .build();
        events.add(event);
        return event;
    }

    /**
     * 获取回放轨迹。
     *
     * @param execId 执行 ID
     * @return 回放轨迹；不存在返回 null
     */
    public ReplayTrace getTrace(String execId) {
        ExecutionRecord record = execStore.get(execId);
        if (record == null) {
            return null;
        }
        List<ReplayEvent> events = eventStore.getOrDefault(execId, List.of());
        return ReplayTrace.builder()
                .execId(execId)
                .dagId(record.getDagId())
                .events(new ArrayList<>(events))
                .startedAt(record.getStartedAt())
                .finishedAt(record.getFinishedAt())
                .build();
    }

    /* ------------------------------ 检查点 ------------------------------ */

    /**
     * 创建检查点。
     *
     * @param dagId          DAG ID
     * @param execId         执行 ID
     * @param kind           类型
     * @param completedNodes 已完成节点 ID 列表
     * @param results        已完成节点结果快照
     * @param note           备注
     * @return 新建的检查点
     */
    public Checkpoint createCheckpoint(String dagId, String execId, String kind,
                                       List<String> completedNodes,
                                       Map<String, TaskResult> results,
                                       String note) {
        String checkpointId = UUID.randomUUID().toString();
        Checkpoint checkpoint = Checkpoint.builder()
                .id(checkpointId)
                .dagId(dagId)
                .execId(execId)
                .kind(kind)
                .completedNodes(new ArrayList<>(completedNodes))
                .results(new LinkedHashMap<>(results))
                .createdAt(LocalDateTime.now())
                .note(note)
                .build();
        checkpointStore.put(checkpointId, checkpoint);
        checkpointIndexByDag.computeIfAbsent(dagId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(checkpointId);
        log.info("checkpoint created id={} dagId={} kind={} completed={}",
                checkpointId, dagId, kind, completedNodes.size());
        return checkpoint;
    }

    /**
     * 手动打检查点。
     *
     * @param dagId          DAG ID
     * @param completedNodes 已完成节点 ID 列表
     * @param results        已完成节点结果快照
     * @param note           备注
     * @return 新建的检查点
     */
    public Checkpoint createManualCheckpoint(String dagId, List<String> completedNodes,
                                             Map<String, TaskResult> results, String note) {
        return createCheckpoint(dagId, null, Checkpoint.KIND_MANUAL, completedNodes, results, note);
    }

    /**
     * 查询指定 DAG 的所有检查点。
     *
     * @param dagId DAG ID
     * @return 检查点列表（按创建时间降序）
     */
    public List<Checkpoint> listCheckpoints(String dagId) {
        List<String> ids = checkpointIndexByDag.getOrDefault(dagId, List.of());
        List<Checkpoint> result = new ArrayList<>();
        for (String id : ids) {
            Checkpoint c = checkpointStore.get(id);
            if (c != null) {
                result.add(c);
            }
        }
        result.sort((a, b) -> {
            LocalDateTime ta = a.getCreatedAt();
            LocalDateTime tb = b.getCreatedAt();
            return tb == null ? -1 : (ta == null ? 1 : tb.compareTo(ta));
        });
        return result;
    }

    /**
     * 查询单个检查点。
     *
     * @param checkpointId 检查点 ID
     * @return 检查点；不存在返回 null
     */
    public Checkpoint getCheckpoint(String checkpointId) {
        return checkpointStore.get(checkpointId);
    }

    /**
     * 从检查点恢复执行：将已完成节点标记为 SUCCESS，结果注入图，剩余节点重置为 PENDING。
     *
     * @param graph       DAG 图（将被修改）
     * @param checkpoint  检查点
     * @return 已恢复的节点结果（供调度器跳过这些节点）
     */
    public Map<String, TaskResult> restoreFromCheckpoint(DagGraph graph, Checkpoint checkpoint) {
        Map<String, TaskResult> restored = new LinkedHashMap<>();
        for (String nodeId : checkpoint.getCompletedNodes()) {
            DagNode node = graph.node(nodeId);
            if (node != null) {
                node.setStatus(DagNode.STATUS_SUCCESS);
                node.setStartedAt(checkpoint.getCreatedAt());
                node.setFinishedAt(checkpoint.getCreatedAt());
            }
            TaskResult result = checkpoint.getResults().get(nodeId);
            if (result != null) {
                restored.put(nodeId, result);
            }
        }
        // 剩余节点重置为 PENDING
        for (DagNode node : graph.allNodes()) {
            if (!checkpoint.getCompletedNodes().contains(node.getId())) {
                node.setStatus(DagNode.STATUS_PENDING);
                node.setStartedAt(null);
                node.setFinishedAt(null);
                node.setErrorMessage(null);
            }
        }
        log.info("restored from checkpoint id={} dagId={} restoredNodes={}",
                checkpoint.getId(), graph.getId(), restored.size());
        return restored;
    }

    /* ------------------------------ 清理 ------------------------------ */

    /**
     * 清除指定 DAG 的所有执行记录与检查点。
     *
     * @param dagId DAG ID
     */
    public void clearByDag(String dagId) {
        List<String> execIds = execIndexByDag.remove(dagId);
        if (execIds != null) {
            for (String id : execIds) {
                execStore.remove(id);
                eventStore.remove(id);
                seqGenerators.remove(id);
            }
        }
        List<String> cpIds = checkpointIndexByDag.remove(dagId);
        if (cpIds != null) {
            for (String id : cpIds) {
                checkpointStore.remove(id);
            }
        }
    }
}