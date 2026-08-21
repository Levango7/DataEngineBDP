package com.levango7.dataenginebdp.federated.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事务恢复服务。
 *
 * <p>定期扫描未完成事务，根据状态执行恢复操作：
 * <ul>
 *   <li>PREPARED 但未 COMMIT → 继续 commit（协调器可能在阶段 2 之前崩溃）</li>
 *   <li>PREPARING 但超时 → 回滚（阶段 1 超时，事务安全可回滚）</li>
 *   <li>COMMITTING 但失败 → 重试 commit（阶段 2 必须最终成功，否则数据不一致）</li>
 * </ul>
 *
 * <p>恢复策略基于 2PC 协议的"crash recovery"理论：
 * <ul>
 *   <li>协调器崩溃前已写入 PREPARED 日志 → 重启后必须 commit（参与者已锁资源）</li>
 *   <li>协调器崩溃时还在 PREPARING → 参与者未 prepared，可安全 rollback</li>
 *   <li>协调器崩溃时在 COMMITTING → 部分参与者已 commit，必须重试直到全部 commit</li>
 * </ul>
 *
 * <p>扫描间隔默认 60 秒，可通过 {@code federated.transaction.recovery-interval-ms} 配置。
 */
@Slf4j
@Component
public class TransactionRecoveryService {

    private final TransactionCoordinator coordinator;
    private final TwoPhaseCommitProtocol protocol;

    /** PREPARING 状态超时阈值（毫秒），超时后回滚。 */
    private final long preparingTimeoutMs;

    /** COMMITTING 状态最大重试次数。 */
    private final int maxCommitRetries;

    /** 恢复扫描次数（用于监控/测试）。 */
    private final AtomicLong recoveryScanCount = new AtomicLong(0);

    /** 上次恢复扫描时间。 */
    private volatile Instant lastScanAt;

    /** 上次恢复的事务数。 */
    private final AtomicLong lastRecoveredCount = new AtomicLong(0);

    public TransactionRecoveryService(
            TransactionCoordinator coordinator,
            TwoPhaseCommitProtocol protocol,
            @Value("${federated.transaction.preparing-timeout-ms:30000}") long preparingTimeoutMs,
            @Value("${federated.transaction.recovery-max-commit-retries:5}") int maxCommitRetries) {
        this.coordinator = coordinator;
        this.protocol = protocol;
        this.preparingTimeoutMs = preparingTimeoutMs;
        this.maxCommitRetries = maxCommitRetries;
    }

    /**
     * 定期扫描未完成事务并恢复。
     *
     * <p>每 60 秒执行一次（可配置）。
     */
    @Scheduled(fixedDelayString = "${federated.transaction.recovery-interval-ms:60000}")
    public void recoverPendingTransactions() {
        recoveryScanCount.incrementAndGet();
        lastScanAt = Instant.now();
        long recovered = 0;

        List<TransactionLog> recoverable = coordinator.getRecoverableTransactions();
        if (recoverable.isEmpty()) {
            log.debug("Recovery scan: no pending transactions");
            lastRecoveredCount.set(0);
            return;
        }

        log.info("Recovery scan start: pendingTransactions={}", recoverable.size());

        for (TransactionLog tx : recoverable) {
            try {
                boolean did = recover(tx);
                if (did) {
                    recovered++;
                }
            } catch (Exception e) {
                log.error("Recovery failed: txId={} status={}", tx.getTxId(), tx.getStatus(), e);
            }
        }

        lastRecoveredCount.set(recovered);
        log.info("Recovery scan done: scanned={} recovered={}", recoverable.size(), recovered);
    }

    /**
     * 恢复单个事务。
     *
     * @param tx 待恢复事务
     * @return true 已执行恢复操作
     */
    public boolean recover(TransactionLog tx) {
        switch (tx.getStatus()) {
            case PREPARED:
                return recoverPrepared(tx);
            case PREPARING:
                return recoverPreparing(tx);
            case COMMITTING:
                return recoverCommitting(tx);
            default:
                log.debug("Recovery skip: txId={} status={} (not recoverable)", tx.getTxId(), tx.getStatus());
                return false;
        }
    }

    /**
     * 恢复 PREPARED 状态事务：继续 commit。
     *
     * <p>协调器在阶段 1 完成后、阶段 2 开始前崩溃，重启后事务处于 PREPARED。
     * 由于参与者已 prepared（持有锁），必须 commit 释放资源。
     */
    private boolean recoverPrepared(TransactionLog tx) {
        log.info("Recovering PREPARED transaction: txId={} preparedClusters={}",
                tx.getTxId(), tx.getPreparedClusters());
        boolean ok = coordinator.commit(tx.getTxId());
        log.info("Recovery PREPARED result: txId={} committed={}", tx.getTxId(), ok);
        return true;
    }

    /**
     * 恢复 PREPARING 状态事务：超时则回滚。
     *
     * <p>协调器在阶段 1 进行中崩溃，重启后事务处于 PREPARING。
     * 若超时（参与者可能未 prepared），可安全 rollback；
     * 若未超时，等待下次扫描（避免与正在进行的 prepare 冲突）。
     */
    private boolean recoverPreparing(TransactionLog tx) {
        Instant preparingAt = tx.getPreparingAt();
        if (preparingAt == null) {
            log.warn("Recovery PREPARING: txId={} preparingAt is null, force rollback", tx.getTxId());
            coordinator.rollback(tx.getTxId());
            return true;
        }
        long elapsedMs = Duration.between(preparingAt, Instant.now()).toMillis();
        if (elapsedMs < preparingTimeoutMs) {
            log.debug("Recovery PREPARING: txId={} elapsedMs={} < {}ms, wait next scan",
                    tx.getTxId(), elapsedMs, preparingTimeoutMs);
            return false;
        }
        log.info("Recovering PREPARING transaction (timed out): txId={} elapsedMs={}",
                tx.getTxId(), elapsedMs);
        boolean ok = coordinator.rollback(tx.getTxId());
        log.info("Recovery PREPARING result: txId={} rolledBack={}", tx.getTxId(), ok);
        return true;
    }

    /**
     * 恢复 COMMITTING 状态事务：重试 commit。
     *
     * <p>协调器在阶段 2 进行中崩溃，重启后事务处于 COMMITTING。
     * 部分参与者可能已 commit，必须重试直到全部 commit（commit 幂等）。
     * 若重试次数超过阈值，标记为 FAILED 等待人工介入。
     */
    private boolean recoverCommitting(TransactionLog tx) {
        if (tx.getRetryCount() >= maxCommitRetries) {
            log.error("Recovery COMMITTING: txId={} retryCount={} >= max={}, mark FAILED (require manual)",
                    tx.getTxId(), tx.getRetryCount(), maxCommitRetries);
            tx.setStatus(TransactionLog.Status.FAILED);
            tx.setFailureReason("commit retry exhausted: " + tx.getRetryCount());
            tx.setUpdatedAt(Instant.now());
            return true;
        }

        log.info("Recovering COMMITTING transaction: txId={} retryCount={}",
                tx.getTxId(), tx.getRetryCount());

        TwoPhaseCommitProtocol.CommitResult result = protocol.executeCommit(
                tx.getPreparedClusters(), tx.getParticipants(), tx.getTxId());
        tx.setRetryCount(tx.getRetryCount() + 1);

        if (result.isAllCommitted()) {
            tx.setStatus(TransactionLog.Status.COMMITTED);
            tx.setCommittedAt(Instant.now());
            tx.setUpdatedAt(tx.getCommittedAt());
            log.info("Recovery COMMITTING success: txId={}", tx.getTxId());
        } else {
            log.warn("Recovery COMMITTING partial fail: txId={} failedClusters={} will retry next scan",
                    tx.getTxId(), result.getFailedClusters());
        }
        return true;
    }

    /**
     * 获取恢复扫描次数。
     */
    public long getRecoveryScanCount() {
        return recoveryScanCount.get();
    }

    /**
     * 获取上次扫描时间。
     */
    public Instant getLastScanAt() {
        return lastScanAt;
    }

    /**
     * 获取上次恢复的事务数。
     */
    public long getLastRecoveredCount() {
        return lastRecoveredCount.get();
    }
}