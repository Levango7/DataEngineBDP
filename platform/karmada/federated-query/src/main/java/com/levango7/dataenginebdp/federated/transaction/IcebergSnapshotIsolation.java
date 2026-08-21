package com.levango7.dataenginebdp.federated.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Iceberg Snapshot Isolation 快照隔离管理。
 *
 * <p>为每个跨集群事务在 Iceberg 表上创建独立 snapshot，实现 MVCC 读已提交隔离级别。
 * 事务内所有读写都基于该 snapshot，事务提交时切换到新 snapshot，回滚时丢弃 snapshot。
 *
 * <p>核心能力：
 * <ul>
 *   <li>为事务分配独立 snapshotId（每表单调递增）</li>
 *   <li>提交 snapshot：将事务 snapshot 设为表的当前 snapshot</li>
 *   <li>回滚 snapshot：丢弃事务 snapshot，恢复到之前 snapshot</li>
 *   <li>引用计数管理：防止正在使用的 snapshot 被 Iceberg GC 清理</li>
 * </ul>
 *
 * <p>本实现使用内存数据结构模拟 Iceberg catalog，生产环境可替换为
 * {@code org.apache.iceberg.catalog.Catalog} 的 REST/JDBC 实现。
 */
@Slf4j
@Component
public class IcebergSnapshotIsolation {

    /** 每张表当前的 snapshotId（模拟 Iceberg current-snapshot）。 */
    private final Map<String, Long> currentSnapshots = new ConcurrentHashMap<>();

    /** 每张表的 snapshotId 生成器（单调递增）。 */
    private final Map<String, AtomicLong> snapshotGenerators = new ConcurrentHashMap<>();

    /** 引用计数：(tableId, snapshotId) → 引用数。 */
    private final Map<String, Integer> snapshotRefCounts = new ConcurrentHashMap<>();

    /** 事务在每张表上创建的 snapshot：(txId, tableId) → snapshotId。 */
    private final Map<String, Map<String, Long>> txSnapshots = new ConcurrentHashMap<>();

    /** 事务在每张表上创建 snapshot 之前的 previous snapshotId（用于回滚恢复）。 */
    private final Map<String, Map<String, Long>> txPreviousSnapshots = new ConcurrentHashMap<>();

    /** 已提交的 snapshot（标记为有效，不可回滚）：(txId, tableId) → true。 */
    private final Map<String, Map<String, Long>> committedSnapshots = new ConcurrentHashMap<>();

    /**
     * 为事务在指定表上创建独立 snapshot。
     *
     * <p>记录 previous snapshot（用于回滚），分配新 snapshotId，
     * 增加引用计数防止被 GC。
     *
     * @param txId    全局事务 ID
     * @param tableId Iceberg 表标识（如 catalog.db.table）
     * @return 新分配的 snapshotId
     */
    public synchronized long createSnapshot(String txId, String tableId) {
        log.debug("Creating snapshot for txId={} tableId={}", txId, tableId);

        long previous = currentSnapshots.getOrDefault(tableId, 0L);
        long snapshotId = nextSnapshotId(tableId);

        txSnapshots.computeIfAbsent(txId, k -> new HashMap<>()).put(tableId, snapshotId);
        txPreviousSnapshots.computeIfAbsent(txId, k -> new HashMap<>()).put(tableId, previous);

        String refKey = refKey(tableId, snapshotId);
        snapshotRefCounts.merge(refKey, 1, Integer::sum);

        log.debug("Snapshot created: txId={} tableId={} snapshotId={} previous={}",
                txId, tableId, snapshotId, previous);
        return snapshotId;
    }

    /**
     * 提交事务在指定表上的 snapshot。
     *
     * <p>将事务 snapshot 设为表当前 snapshot，标记为已提交（不可回滚），
     * 释放引用计数。
     *
     * @param txId       全局事务 ID
     * @param tableId    Iceberg 表标识
     * @param snapshotId 事务创建的 snapshotId
     * @return true 提交成功，false snapshot 不属于该事务
     */
    public synchronized boolean commitSnapshot(String txId, String tableId, long snapshotId) {
        Map<String, Long> txTableSnapshots = txSnapshots.get(txId);
        if (txTableSnapshots == null || !txTableSnapshots.containsKey(tableId)) {
            log.warn("Commit snapshot failed: txId={} tableId={} not found", txId, tableId);
            return false;
        }
        long expected = txTableSnapshots.get(tableId);
        if (expected != snapshotId) {
            log.warn("Commit snapshot mismatch: txId={} tableId={} expected={} actual={}",
                    txId, tableId, expected, snapshotId);
            return false;
        }

        currentSnapshots.put(tableId, snapshotId);
        committedSnapshots.computeIfAbsent(txId, k -> new HashMap<>()).put(tableId, snapshotId);

        releaseRef(tableId, snapshotId);
        log.info("Snapshot committed: txId={} tableId={} snapshotId={}", txId, tableId, snapshotId);
        return true;
    }

    /**
     * 回滚事务在指定表上的 snapshot。
     *
     * <p>恢复表当前 snapshot 到 previous snapshot，丢弃事务 snapshot，
     * 释放引用计数。已提交的 snapshot 不可回滚。
     *
     * @param txId    全局事务 ID
     * @param tableId Iceberg 表标识
     * @return true 回滚成功，false 已提交或不存在
     */
    public synchronized boolean rollbackSnapshot(String txId, String tableId) {
        Map<String, Long> committed = committedSnapshots.get(txId);
        if (committed != null && committed.containsKey(tableId)) {
            log.warn("Rollback snapshot failed (already committed): txId={} tableId={}", txId, tableId);
            return false;
        }

        Map<String, Long> txTableSnapshots = txSnapshots.get(txId);
        if (txTableSnapshots == null || !txTableSnapshots.containsKey(tableId)) {
            log.warn("Rollback snapshot failed (not found): txId={} tableId={}", txId, tableId);
            return false;
        }

        long snapshotId = txTableSnapshots.get(tableId);
        Map<String, Long> previousMap = txPreviousSnapshots.get(txId);
        long previous = previousMap != null ? previousMap.getOrDefault(tableId, 0L) : 0L;

        currentSnapshots.put(tableId, previous);
        releaseRef(tableId, snapshotId);

        log.info("Snapshot rolled back: txId={} tableId={} snapshotId={} restoredTo={}",
                txId, tableId, snapshotId, previous);
        return true;
    }

    /**
     * 获取事务在指定表上的 snapshotId。
     *
     * @param txId    全局事务 ID
     * @param tableId Iceberg 表标识
     * @return snapshotId，不存在返回 -1
     */
    public synchronized long getSnapshot(String txId, String tableId) {
        Map<String, Long> txTableSnapshots = txSnapshots.get(txId);
        if (txTableSnapshots == null) {
            return -1L;
        }
        return txTableSnapshots.getOrDefault(tableId, -1L);
    }

    /**
     * 获取表当前 snapshotId。
     *
     * @param tableId Iceberg 表标识
     * @return 当前 snapshotId，不存在返回 0
     */
    public long getCurrentSnapshot(String tableId) {
        return currentSnapshots.getOrDefault(tableId, 0L);
    }

    /**
     * 获取 snapshot 引用计数。
     *
     * @param tableId    Iceberg 表标识
     * @param snapshotId snapshotId
     * @return 引用计数
     */
    public int getRefCount(String tableId, long snapshotId) {
        return snapshotRefCounts.getOrDefault(refKey(tableId, snapshotId), 0);
    }

    /**
     * 释放事务所有未提交的 snapshot（事务回滚时调用）。
     *
     * @param txId 全局事务 ID
     */
    public synchronized void releaseAll(String txId) {
        Map<String, Long> snapshots = txSnapshots.get(txId);
        Map<String, Long> committed = committedSnapshots.get(txId);
        if (snapshots == null) {
            return;
        }
        for (Map.Entry<String, Long> entry : snapshots.entrySet()) {
            String tableId = entry.getKey();
            long snapshotId = entry.getValue();
            boolean isCommitted = committed != null && committed.containsKey(tableId);
            if (!isCommitted) {
                Map<String, Long> previousMap = txPreviousSnapshots.get(txId);
                long previous = previousMap != null ? previousMap.getOrDefault(tableId, 0L) : 0L;
                currentSnapshots.put(tableId, previous);
                releaseRef(tableId, snapshotId);
            }
        }
        log.info("Released all snapshots for txId={} committed={} uncommitted={}",
                txId, committed != null ? committed.size() : 0,
                snapshots.size() - (committed != null ? committed.size() : 0));
    }

    /**
     * 清理事务的所有跟踪记录（事务进入终态后调用）。
     *
     * @param txId 全局事务 ID
     */
    public synchronized void cleanup(String txId) {
        txSnapshots.remove(txId);
        txPreviousSnapshots.remove(txId);
        committedSnapshots.remove(txId);
    }

    private long nextSnapshotId(String tableId) {
        return snapshotGenerators
                .computeIfAbsent(tableId, k -> new AtomicLong(currentSnapshots.getOrDefault(k, 0L)))
                .incrementAndGet();
    }

    private void releaseRef(String tableId, long snapshotId) {
        String key = refKey(tableId, snapshotId);
        snapshotRefCounts.computeIfPresent(key, (k, v) -> v <= 1 ? null : v - 1);
    }

    private String refKey(String tableId, long snapshotId) {
        return tableId + "@" + snapshotId;
    }
}