package com.levango7.dataenginebdp.federated.transaction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 两阶段提交（2PC）协议实现。
 *
 * <p>协议保证：所有参与集群要么全部 commit，要么全部 rollback。
 *
 * <p>阶段 1（Prepare）：
 * <ul>
 *   <li>协调器向所有参与集群发送 prepare 请求</li>
 *   <li>所有集群 prepared → 进入阶段 2</li>
 *   <li>任一集群拒绝或超时 → 自动 rollback 所有已 prepared 集群</li>
 * </ul>
 *
 * <p>阶段 2（Commit）：
 * <ul>
 *   <li>协调器向所有已 prepared 集群发送 commit 请求</li>
 *   <li>commit 失败 → 重试（直到成功或达到最大重试次数）</li>
 *   <li>commit 超时 → 重试（commit 命令幂等，集群最终会处理）</li>
 * </ul>
 *
 * <p>超时处理：
 * <ul>
 *   <li>prepare 超时 → 自动回滚（事务安全）</li>
 *   <li>commit 超时 → 重试（commit 必须最终成功，否则数据不一致）</li>
 * </ul>
 */
@Slf4j
@Component
public class TwoPhaseCommitProtocol {

    private final ClusterTransactionClient client;

    /** prepare 阶段超时（毫秒）。 */
    private final long prepareTimeoutMs;

    /** commit 阶段超时（毫秒）。 */
    private final long commitTimeoutMs;

    /** commit 最大重试次数。 */
    private final int maxCommitRetries;

    /** 单次操作重试间隔（毫秒）。 */
    private final long retryIntervalMs;

    public TwoPhaseCommitProtocol(
            ClusterTransactionClient client,
            @Value("${federated.transaction.prepare-timeout-ms:5000}") long prepareTimeoutMs,
            @Value("${federated.transaction.commit-timeout-ms:10000}") long commitTimeoutMs,
            @Value("${federated.transaction.max-commit-retries:3}") int maxCommitRetries,
            @Value("${federated.transaction.retry-interval-ms:200}") long retryIntervalMs) {
        this.client = client;
        this.prepareTimeoutMs = prepareTimeoutMs;
        this.commitTimeoutMs = commitTimeoutMs;
        this.maxCommitRetries = maxCommitRetries;
        this.retryIntervalMs = retryIntervalMs;
    }

    /**
     * 执行阶段 1：向所有参与集群发送 prepare。
     *
     * <p>任一集群失败或超时 → 整体失败，调用方应执行 rollback。
     *
     * @param clusterEndpoints 参与集群（集群名 → endpoint URL）
     * @param txId             全局事务 ID
     * @return prepare 结果
     */
    public PrepareResult executePrepare(Map<String, String> clusterEndpoints, String txId) {
        log.info("2PC prepare start: txId={} participants={}", txId, clusterEndpoints.keySet());

        Map<String, Boolean> results = new HashMap<>();
        List<String> prepared = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> timedOut = new ArrayList<>();
        Instant start = Instant.now();

        ReentrantLock lock = new ReentrantLock();
        List<Thread> threads = new ArrayList<>();

        for (Map.Entry<String, String> entry : clusterEndpoints.entrySet()) {
            String cluster = entry.getKey();
            String endpoint = entry.getValue();
            Thread t = new Thread(() -> {
                boolean ok = false;
                boolean timeout = false;
                try {
                    long deadline = System.currentTimeMillis() + prepareTimeoutMs;
                    ok = callWithTimeout(() -> client.prepare(cluster, endpoint, txId),
                            deadline, "prepare");
                    if (!ok && System.currentTimeMillis() >= deadline) {
                        timeout = true;
                    }
                } catch (Exception e) {
                    log.warn("Prepare exception: txId={} cluster={}", txId, cluster, e);
                }
                lock.lock();
                try {
                    results.put(cluster, ok);
                    if (ok) {
                        prepared.add(cluster);
                    } else if (timeout) {
                        timedOut.add(cluster);
                    } else {
                        failed.add(cluster);
                    }
                } finally {
                    lock.unlock();
                }
            }, "2pc-prepare-" + cluster);
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) {
            try {
                t.join(prepareTimeoutMs + 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Prepare join interrupted: txId={}", txId);
            }
        }

        Duration elapsed = Duration.between(start, Instant.now());
        boolean allPrepared = prepared.size() == clusterEndpoints.size();
        PrepareResult result = PrepareResult.builder()
                .allPrepared(allPrepared)
                .preparedClusters(prepared)
                .failedClusters(failed)
                .timedOutClusters(timedOut)
                .results(results)
                .elapsedMs(elapsed.toMillis())
                .build();

        log.info("2PC prepare done: txId={} allPrepared={} prepared={} failed={} timedOut={} elapsedMs={}",
                txId, allPrepared, prepared, failed, timedOut, result.getElapsedMs());
        return result;
    }

    /**
     * 执行阶段 2：向已 prepared 集群发送 commit。
     *
     * <p>commit 失败/超时 → 重试，达到最大重试次数仍失败 → 返回部分失败结果。
     *
     * @param preparedClusters 已 prepared 集群列表
     * @param clusterEndpoints 全部集群端点（用于查找 endpoint）
     * @param txId             全局事务 ID
     * @return commit 结果
     */
    public CommitResult executeCommit(List<String> preparedClusters,
                                      Map<String, String> clusterEndpoints,
                                      String txId) {
        log.info("2PC commit start: txId={} preparedClusters={}", txId, preparedClusters);

        Map<String, Boolean> results = new HashMap<>();
        List<String> committed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, Integer> retryCounts = new HashMap<>();
        Instant start = Instant.now();

        for (String cluster : preparedClusters) {
            String endpoint = clusterEndpoints.get(cluster);
            int retries = 0;
            boolean ok = false;
            while (retries <= maxCommitRetries) {
                try {
                    long deadline = System.currentTimeMillis() + commitTimeoutMs;
                    ok = callWithTimeout(() -> client.commit(cluster, endpoint, txId),
                            deadline, "commit");
                    if (ok) {
                        break;
                    }
                } catch (Exception e) {
                    log.warn("Commit exception: txId={} cluster={} retry={}", txId, cluster, retries, e);
                }
                retries++;
                if (retries <= maxCommitRetries) {
                    sleep(retryIntervalMs);
                }
            }

            results.put(cluster, ok);
            retryCounts.put(cluster, retries);
            if (ok) {
                committed.add(cluster);
            } else {
                failed.add(cluster);
            }
        }

        Duration elapsed = Duration.between(start, Instant.now());
        boolean allCommitted = committed.size() == preparedClusters.size();
        CommitResult result = CommitResult.builder()
                .allCommitted(allCommitted)
                .committedClusters(committed)
                .failedClusters(failed)
                .results(results)
                .retryCounts(retryCounts)
                .elapsedMs(elapsed.toMillis())
                .build();

        log.info("2PC commit done: txId={} allCommitted={} committed={} failed={} elapsedMs={}",
                txId, allCommitted, committed, failed, result.getElapsedMs());
        return result;
    }

    /**
     * 执行回滚：向所有参与过事务的集群发送 rollback。
     *
     * <p>rollback 失败也继续尝试其他集群（best-effort），最终返回每个集群的结果。
     *
     * @param participatedClusters 参与过事务的集群列表
     * @param clusterEndpoints      全部集群端点
     * @param txId                  全局事务 ID
     * @return rollback 结果
     */
    public RollbackResult executeRollback(List<String> participatedClusters,
                                          Map<String, String> clusterEndpoints,
                                          String txId) {
        log.info("2PC rollback start: txId={} clusters={}", txId, participatedClusters);

        Map<String, Boolean> results = new HashMap<>();
        List<String> rolledBack = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Instant start = Instant.now();

        for (String cluster : participatedClusters) {
            String endpoint = clusterEndpoints.get(cluster);
            boolean ok = false;
            try {
                ok = client.rollback(cluster, endpoint, txId);
            } catch (Exception e) {
                log.warn("Rollback exception: txId={} cluster={}", txId, cluster, e);
            }
            results.put(cluster, ok);
            if (ok) {
                rolledBack.add(cluster);
            } else {
                failed.add(cluster);
            }
        }

        Duration elapsed = Duration.between(start, Instant.now());
        RollbackResult result = RollbackResult.builder()
                .allRolledBack(rolledBack.size() == participatedClusters.size())
                .rolledBackClusters(rolledBack)
                .failedClusters(failed)
                .results(results)
                .elapsedMs(elapsed.toMillis())
                .build();

        log.info("2PC rollback done: txId={} rolledBack={} failed={} elapsedMs={}",
                txId, rolledBack, failed, result.getElapsedMs());
        return result;
    }

    /**
     * 带超时调用集群操作。
     *
     * <p>使用单独线程执行，主线程 join 到 deadline，超时返回 false。
     */
    private boolean callWithTimeout(CallableBoolean call, long deadline, String op) {
        boolean[] holder = new boolean[1];
        Thread t = new Thread(() -> holder[0] = call.call(), "2pc-" + op);
        t.start();
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            t.interrupt();
            return false;
        }
        try {
            t.join(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (t.isAlive()) {
            t.interrupt();
            log.warn("{} timed out", op);
            return false;
        }
        return holder[0];
    }

    private void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface CallableBoolean {
        boolean call();
    }

    /** Prepare 阶段结果。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PrepareResult {
        /** 是否所有集群都已 prepared。 */
        private boolean allPrepared;
        /** 已 prepared 的集群列表。 */
        private List<String> preparedClusters;
        /** prepare 失败的集群列表。 */
        private List<String> failedClusters;
        /** prepare 超时的集群列表。 */
        private List<String> timedOutClusters;
        /** 每个集群的 prepare 结果。 */
        private Map<String, Boolean> results;
        /** 总耗时（毫秒）。 */
        private long elapsedMs;
    }

    /** Commit 阶段结果。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommitResult {
        /** 是否所有集群都已 committed。 */
        private boolean allCommitted;
        /** 已 committed 的集群列表。 */
        private List<String> committedClusters;
        /** commit 失败的集群列表。 */
        private List<String> failedClusters;
        /** 每个集群的 commit 结果。 */
        private Map<String, Boolean> results;
        /** 每个集群的重试次数。 */
        private Map<String, Integer> retryCounts;
        /** 总耗时（毫秒）。 */
        private long elapsedMs;
    }

    /** Rollback 阶段结果。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RollbackResult {
        /** 是否所有集群都已 rolled back。 */
        private boolean allRolledBack;
        /** 已 rolled back 的集群列表。 */
        private List<String> rolledBackClusters;
        /** rollback 失败的集群列表。 */
        private List<String> failedClusters;
        /** 每个集群的 rollback 结果。 */
        private Map<String, Boolean> results;
        /** 总耗时（毫秒）。 */
        private long elapsedMs;
    }
}