package com.shuqing.bigdata.rule.engine.orchestrator.intervention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 人工介入服务。
 *
 * <p>管理介入请求的全生命周期：创建、查询、提交审批、阻塞等待审批结果。
 * 是人工介入节点与外部审批 API 之间的桥梁。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>使用内存仓库（ConcurrentHashMap），与 OrchestratorService 风格一致；</li>
 *   <li>每个待处理请求关联一个 Condition，调度线程在 await 时阻塞，
 *       审批线程在 signal 时唤醒，实现"暂停等待人工审批"语义；</li>
 *   <li>支持超时：超时后请求标记为 TIMEOUT，调度线程被唤醒并按超时策略处理；</li>
 *   <li>按 DAG 索引请求列表，便于前端查询某 DAG 的所有介入请求。</li>
 * </ul>
 * </p>
 */
@Service
public class InterventionService {

    private static final Logger log = LoggerFactory.getLogger(InterventionService.class);

    /** 介入请求仓库：requestId -> InterventionRequest */
    private final Map<String, InterventionRequest> requestStore = new ConcurrentHashMap<>();
    /** 按 DAG 索引：dagId -> List<requestId> */
    private final Map<String, List<String>> indexByDag = new ConcurrentHashMap<>();
    /** 等待句柄：requestId -> WaitHandle（包含锁与条件变量） */
    private final Map<String, WaitHandle> waitHandles = new ConcurrentHashMap<>();

    /* ------------------------------ 创建介入请求 ------------------------------ */

    /**
     * 创建一个待处理介入请求，并注册等待句柄。
     *
     * <p>调度器在执行 {@link HumanInterventionNode} 时调用此方法，
     * 随后调用 {@link #awaitResolution} 阻塞等待审批结果。</p>
     *
     * @param dagId    DAG ID
     * @param execId   执行 ID
     * @param nodeId   节点 ID
     * @param nodeName 节点名称
     * @param reason   介入原因
     * @param context  上下文数据
     * @return 新建的介入请求
     */
    public InterventionRequest createRequest(String dagId, String execId, String nodeId,
                                             String nodeName, String reason,
                                             Map<String, Object> context) {
        InterventionRequest request = InterventionRequest.pending(dagId, execId, nodeId, nodeName, reason, context);
        requestStore.put(request.getId(), request);
        indexByDag.computeIfAbsent(dagId, k -> new ArrayList<>()).add(request.getId());
        waitHandles.put(request.getId(), new WaitHandle());
        log.info("intervention request created id={} dagId={} nodeId={} reason={}",
                request.getId(), dagId, nodeId, reason);
        return request;
    }

    /* ------------------------------ 阻塞等待审批 ------------------------------ */

    /**
     * 阻塞等待介入请求被审批。
     *
     * <p>调度线程调用此方法阻塞，直到审批提交或超时。</p>
     *
     * @param requestId 介入请求 ID
     * @param timeoutMs 超时毫秒数；<=0 表示不超时
     * @return 已处理的介入请求；超时返回状态为 TIMEOUT 的请求
     */
    public InterventionRequest awaitResolution(String requestId, long timeoutMs) {
        WaitHandle handle = waitHandles.get(requestId);
        if (handle == null) {
            // 已被提前处理或不存在
            return requestStore.get(requestId);
        }
        Lock lock = handle.lock;
        Condition cond = handle.condition;
        try {
            lock.lock();
            InterventionRequest current = requestStore.get(requestId);
            if (current != null && current.isResolved()) {
                return current;
            }
            if (timeoutMs <= 0) {
                while (current == null || !current.isResolved()) {
                    cond.await();
                    current = requestStore.get(requestId);
                }
                return current;
            }
            long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (current == null || !current.isResolved()) {
                if (nanos <= 0) {
                    // 超时：标记为 TIMEOUT
                    markTimeout(requestId);
                    return requestStore.get(requestId);
                }
                nanos = cond.awaitNanos(nanos);
                current = requestStore.get(requestId);
            }
            return current;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 被中断：视为超时处理
            markTimeout(requestId);
            return requestStore.get(requestId);
        } finally {
            lock.unlock();
            // 清理等待句柄
            waitHandles.remove(requestId);
        }
    }

    /* ------------------------------ 提交审批 ------------------------------ */

    /**
     * 提交审批决定。
     *
     * @param requestId     介入请求 ID
     * @param decision      决定：APPROVED / REJECTED
     * @param approver      审批人
     * @param comment       审批意见
     * @param overrideParams 覆盖参数（批准时可调整下游参数）
     * @return 已处理的介入请求；不存在或已处理返回 null
     */
    public InterventionRequest resolve(String requestId, String decision,
                                       String approver, String comment,
                                       Map<String, Object> overrideParams) {
        InterventionRequest request = requestStore.get(requestId);
        if (request == null) {
            log.warn("intervention request not found id={}", requestId);
            return null;
        }
        WaitHandle handle = waitHandles.get(requestId);
        Lock lock = handle != null ? handle.lock : null;
        Condition cond = handle != null ? handle.condition : null;

        if (lock != null) {
            lock.lock();
        }
        try {
            // 二次检查状态
            if (request.isResolved()) {
                log.warn("intervention request already resolved id={} status={}", requestId, request.getStatus());
                return request;
            }
            String status = InterventionRequest.DECISION_APPROVED.equals(decision)
                    ? InterventionRequest.STATUS_APPROVED
                    : InterventionRequest.STATUS_REJECTED;
            request.setStatus(status);
            request.setResolvedAt(LocalDateTime.now());
            request.setApprover(approver);
            request.setComment(comment);
            request.setOverrideParams(overrideParams);
            log.info("intervention resolved id={} decision={} approver={}", requestId, status, approver);
            if (cond != null) {
                cond.signalAll();
            }
            return request;
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    /* ------------------------------ 查询 ------------------------------ */

    /**
     * 查询指定 DAG 的所有介入请求。
     *
     * @param dagId DAG ID
     * @return 介入请求列表（按创建时间降序）
     */
    public List<InterventionRequest> listByDag(String dagId) {
        List<String> ids = indexByDag.getOrDefault(dagId, List.of());
        List<InterventionRequest> result = new ArrayList<>();
        for (String id : ids) {
            InterventionRequest r = requestStore.get(id);
            if (r != null) {
                result.add(r);
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
     * 查询单个介入请求。
     *
     * @param requestId 介入请求 ID
     * @return 介入请求；不存在返回 null
     */
    public InterventionRequest get(String requestId) {
        return requestStore.get(requestId);
    }

    /**
     * 查询指定 DAG 的所有待处理介入请求。
     *
     * @param dagId DAG ID
     * @return 待处理介入请求列表
     */
    public List<InterventionRequest> listPendingByDag(String dagId) {
        List<InterventionRequest> all = listByDag(dagId);
        List<InterventionRequest> pending = new ArrayList<>();
        for (InterventionRequest r : all) {
            if (InterventionRequest.STATUS_PENDING.equals(r.getStatus())) {
                pending.add(r);
            }
        }
        return pending;
    }

    /* ------------------------------ 内部方法 ------------------------------ */

    /**
     * 标记请求为超时。
     */
    private void markTimeout(String requestId) {
        InterventionRequest request = requestStore.get(requestId);
        if (request != null && !request.isResolved()) {
            request.setStatus(InterventionRequest.STATUS_TIMEOUT);
            request.setResolvedAt(LocalDateTime.now());
            log.warn("intervention request timeout id={}", requestId);
        }
    }

    /**
     * 等待句柄：每个待处理请求持有一把锁与一个条件变量。
     */
    private static final class WaitHandle {
        final Lock lock = new ReentrantLock();
        final Condition condition = lock.newCondition();
    }
}