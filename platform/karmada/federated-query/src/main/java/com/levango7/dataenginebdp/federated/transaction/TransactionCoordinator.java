package com.levango7.dataenginebdp.federated.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨集群 ACID 事务协调器。
 *
 * <p>基于 Iceberg Snapshot Isolation + 2PC 协议实现跨集群事务，
 * 保证 Atomicity + Consistency + Isolation + Durability。
 *
 * <p>事务状态机：
 * <pre>
 *   ACTIVE → PREPARING → PREPARED → COMMITTING → COMMITTED
 *                                      → ROLLING_BACK → ROLLED_BACK
 *                                      → FAILED
 * </pre>
 *
 * <p>核心 API：
 * <ul>
 *   <li>{@link #begin(Map, List)} - 开启事务，分配 txId 和 Iceberg snapshot</li>
 *   <li>{@link #prepare(String)} - 阶段 1：向所有集群发送 prepare</li>
 *   <li>{@link #commit(String)} - 阶段 2：向已 prepared 集群发送 commit</li>
 *   <li>{@link #rollback(String)} - 回滚事务</li>
 *   <li>{@link #getTransactionStatus(String)} - 查询事务状态</li>
 * </ul>
 *
 * <p>事务日志使用内存 ConcurrentHashMap 存储，生产环境可替换为持久化存储
 * （由 {@link TransactionRecoveryService} 负责恢复）。
 */
@Slf4j
@Component
public class TransactionCoordinator {

    private final TwoPhaseCommitProtocol protocol;
    private final IcebergSnapshotIsolation snapshotIsolation;

    /** 事务日志存储：txId → TransactionLog（内存存储）。 */
    private final ConcurrentHashMap<String, TransactionLog> transactionLogs = new ConcurrentHashMap<>();

    public TransactionCoordinator(TwoPhaseCommitProtocol protocol,
                                  IcebergSnapshotIsolation snapshotIsolation) {
        this.protocol = protocol;
        this.snapshotIsolation = snapshotIsolation;
    }

    /**
     * 开启跨集群事务。
     *
     * <p>生成全局事务 ID，为每个参与表创建独立 Iceberg snapshot，
     * 记录事务日志，状态置为 ACTIVE。
     *
     * @param participants 参与集群（集群名 → endpoint URL）
     * @param tableIds     参与的 Iceberg 表标识列表
     * @return 全局事务 ID
     */
    public String begin(Map<String, String> participants, List<String> tableIds) {
        String txId = generateTxId();
        Instant now = Instant.now();

        TransactionLog logEntry = TransactionLog.builder()
                .txId(txId)
                .participants(new java.util.HashMap<>(participants))
                .status(TransactionLog.Status.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        if (tableIds != null) {
            for (String tableId : tableIds) {
                long snapshotId = snapshotIsolation.createSnapshot(txId, tableId);
                logEntry.getSnapshots().put(tableId, snapshotId);
            }
        }

        transactionLogs.put(txId, logEntry);
        log.info("Transaction begun: txId={} participants={} tables={} snapshots={}",
                txId, participants.keySet(),
                tableIds != null ? tableIds : List.of(),
                logEntry.getSnapshots());
        return txId;
    }

    /**
     * 阶段 1：准备阶段，向所有参与集群发送 prepare 请求。
     *
     * <p>所有集群 prepared → 状态置为 PREPARED，返回 true。
     * 任一集群失败或超时 → 自动触发 rollback，状态置为 ROLLED_BACK 或 FAILED，返回 false。
     *
     * @param txId 全局事务 ID
     * @return true 所有集群已 prepared，false 失败（已自动回滚）
     */
    public boolean prepare(String txId) {
        TransactionLog tx = transactionLogs.get(txId);
        if (tx == null) {
            log.warn("Prepare failed: txId={} not found", txId);
            return false;
        }
        if (tx.getStatus() != TransactionLog.Status.ACTIVE) {
            log.warn("Prepare failed: txId={} invalid status={}", txId, tx.getStatus());
            return false;
        }

        Instant now = Instant.now();
        tx.setStatus(TransactionLog.Status.PREPARING);
        tx.setPreparingAt(now);
        tx.setUpdatedAt(now);

        TwoPhaseCommitProtocol.PrepareResult result =
                protocol.executePrepare(tx.getParticipants(), txId);

        tx.setPrepareResults(new java.util.HashMap<>(result.getResults()));

        if (result.isAllPrepared()) {
            tx.setPreparedClusters(new ArrayList<>(result.getPreparedClusters()));
            tx.setStatus(TransactionLog.Status.PREPARED);
            tx.setPreparedAt(Instant.now());
            tx.setUpdatedAt(tx.getPreparedAt());
            log.info("Transaction prepared: txId={} preparedClusters={}", txId, tx.getPreparedClusters());
            return true;
        }

        log.warn("Transaction prepare failed: txId={} failed={} timedOut={}, rolling back",
                txId, result.getFailedClusters(), result.getTimedOutClusters());
        rollbackInternal(tx, "prepare failed or timed out");
        return false;
    }

    /**
     * 阶段 2：提交阶段，向所有已 prepared 集群发送 commit。
     *
     * <p>所有集群 committed → 提交 Iceberg snapshot，状态置为 COMMITTED，返回 true。
     * 任一集群 commit 失败（重试耗尽）→ 状态置为 FAILED，返回 false（需要人工介入或恢复服务重试）。
     *
     * @param txId 全局事务 ID
     * @return true 所有集群已 committed，false 部分失败
     */
    public boolean commit(String txId) {
        TransactionLog tx = transactionLogs.get(txId);
        if (tx == null) {
            log.warn("Commit failed: txId={} not found", txId);
            return false;
        }
        if (tx.getStatus() != TransactionLog.Status.PREPARED) {
            log.warn("Commit failed: txId={} invalid status={}", txId, tx.getStatus());
            return false;
        }

        Instant now = Instant.now();
        tx.setStatus(TransactionLog.Status.COMMITTING);
        tx.setCommittingAt(now);
        tx.setUpdatedAt(now);

        TwoPhaseCommitProtocol.CommitResult result =
                protocol.executeCommit(tx.getPreparedClusters(), tx.getParticipants(), txId);

        tx.setCommitResults(new java.util.HashMap<>(result.getResults()));
        tx.setCommittedClusters(new ArrayList<>(result.getCommittedClusters()));
        tx.setRetryCount(result.getRetryCounts().values().stream().mapToInt(Integer::intValue).sum());

        if (result.isAllCommitted()) {
            for (Map.Entry<String, Long> entry : tx.getSnapshots().entrySet()) {
                snapshotIsolation.commitSnapshot(txId, entry.getKey(), entry.getValue());
            }
            tx.setStatus(TransactionLog.Status.COMMITTED);
            tx.setCommittedAt(Instant.now());
            tx.setUpdatedAt(tx.getCommittedAt());
            snapshotIsolation.cleanup(txId);
            log.info("Transaction committed: txId={} committedClusters={}",
                    txId, tx.getCommittedClusters());
            return true;
        }

        tx.setStatus(TransactionLog.Status.FAILED);
        tx.setFailureReason("commit failed for clusters: " + result.getFailedClusters());
        tx.setUpdatedAt(Instant.now());
        log.error("Transaction commit failed: txId={} failedClusters={} (requires recovery)",
                txId, result.getFailedClusters());
        return false;
    }

    /**
     * 回滚事务。
     *
     * <p>向所有参与过事务的集群发送 rollback，回滚 Iceberg snapshot，
     * 状态置为 ROLLED_BACK。
     *
     * @param txId 全局事务 ID
     * @return true 回滚成功，false 事务不存在或已终态
     */
    public boolean rollback(String txId) {
        TransactionLog tx = transactionLogs.get(txId);
        if (tx == null) {
            log.warn("Rollback failed: txId={} not found", txId);
            return false;
        }
        if (tx.isTerminal()) {
            log.warn("Rollback failed: txId={} already terminal status={}", txId, tx.getStatus());
            return false;
        }
        return rollbackInternal(tx, "explicit rollback");
    }

    /**
     * 查询事务状态。
     *
     * @param txId 全局事务 ID
     * @return 事务日志，不存在返回 null
     */
    public TransactionLog getTransactionStatus(String txId) {
        return transactionLogs.get(txId);
    }

    /**
     * 列出所有事务。
     *
     * @return 事务日志集合
     */
    public Collection<TransactionLog> listTransactions() {
        return transactionLogs.values();
    }

    /**
     * 获取所有需要恢复的事务（处于中间态）。
     *
     * @return 待恢复事务列表
     */
    public List<TransactionLog> getRecoverableTransactions() {
        List<TransactionLog> recoverable = new ArrayList<>();
        for (TransactionLog tx : transactionLogs.values()) {
            if (tx.isRecoverable()) {
                recoverable.add(tx);
            }
        }
        return recoverable;
    }

    /**
     * 获取事务日志存储（供恢复服务使用）。
     */
    ConcurrentHashMap<String, TransactionLog> getTransactionLogs() {
        return transactionLogs;
    }

    /**
     * 内部回滚实现。
     *
     * @param tx     事务日志
     * @param reason 回滚原因
     * @return true 回滚成功
     */
    private boolean rollbackInternal(TransactionLog tx, String reason) {
        String txId = tx.getTxId();
        Instant now = Instant.now();
        tx.setStatus(TransactionLog.Status.ROLLING_BACK);
        tx.setRollingBackAt(now);
        tx.setUpdatedAt(now);

        List<String> participated = new ArrayList<>();
        participated.addAll(tx.getPreparedClusters());
        for (Map.Entry<String, Boolean> entry : tx.getPrepareResults().entrySet()) {
            if (entry.getValue() && !participated.contains(entry.getKey())) {
                participated.add(entry.getKey());
            }
        }
        for (String cluster : tx.getParticipants().keySet()) {
            if (tx.getPrepareResults().containsKey(cluster) && !participated.contains(cluster)) {
                participated.add(cluster);
            }
        }

        TwoPhaseCommitProtocol.RollbackResult result =
                protocol.executeRollback(participated, tx.getParticipants(), txId);

        tx.setRollbackResults(new java.util.HashMap<>(result.getResults()));
        tx.setRolledBackClusters(new ArrayList<>(result.getRolledBackClusters()));

        for (Map.Entry<String, Long> entry : tx.getSnapshots().entrySet()) {
            snapshotIsolation.rollbackSnapshot(txId, entry.getKey());
        }
        snapshotIsolation.releaseAll(txId);
        snapshotIsolation.cleanup(txId);

        if (result.isAllRolledBack()) {
            tx.setStatus(TransactionLog.Status.ROLLED_BACK);
            tx.setRolledBackAt(Instant.now());
            tx.setUpdatedAt(tx.getRolledBackAt());
            log.info("Transaction rolled back: txId={} reason={} rolledBackClusters={}",
                    txId, reason, tx.getRolledBackClusters());
            return true;
        }

        tx.setStatus(TransactionLog.Status.FAILED);
        tx.setFailureReason("rollback failed: " + reason + ", failedClusters=" + result.getFailedClusters());
        tx.setUpdatedAt(Instant.now());
        log.error("Transaction rollback failed: txId={} reason={} failedClusters={}",
                txId, reason, result.getFailedClusters());
        return false;
    }

    /**
     * 生成全局事务 ID（UUID）。
     */
    private String generateTxId() {
        return "tx-" + UUID.randomUUID().toString();
    }
}