package com.levango7.dataenginebdp.federated.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集群事务客户端的内存默认实现。
 *
 * <p>用于开发/测试环境，模拟集群 prepare/commit/rollback 行为。
 * 生产环境应替换为基于 mTLS WebClient 的真实实现
 * （通过定义自己的 {@link ClusterTransactionClient} Bean 覆盖此默认实现）。
 *
 * <p>本实现模拟"所有集群都正常响应"的场景，可通过 {@link #setFailingClusters(Set)}
 * 模拟集群失败。
 */
@Slf4j
@Component
@ConditionalOnMissingBean(ClusterTransactionClient.class)
public class InMemoryClusterTransactionClient implements ClusterTransactionClient {

    /** 模拟失败的集群集合（prepare/commit/rollback 都返回 false）。 */
    private final Set<String> failingClusters = ConcurrentHashMap.newKeySet();

    /** 模拟 prepare 失败的集群。 */
    private final Set<String> prepareFailingClusters = ConcurrentHashMap.newKeySet();

    /** 模拟 commit 失败的集群。 */
    private final Set<String> commitFailingClusters = ConcurrentHashMap.newKeySet();

    /** 已 prepared 的事务：txId → 集群集合。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Set<String>> preparedTx =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 已 committed 的事务：txId → 集群集合。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Set<String>> committedTx =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 已 rolled back 的事务：txId → 集群集合。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Set<String>> rolledBackTx =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public boolean prepare(String clusterName, String endpoint, String txId) {
        if (failingClusters.contains(clusterName) || prepareFailingClusters.contains(clusterName)) {
            log.debug("In-memory prepare FAIL: cluster={} txId={}", clusterName, txId);
            return false;
        }
        preparedTx.computeIfAbsent(txId, k -> ConcurrentHashMap.newKeySet()).add(clusterName);
        log.debug("In-memory prepare OK: cluster={} txId={}", clusterName, txId);
        return true;
    }

    @Override
    public boolean commit(String clusterName, String endpoint, String txId) {
        if (failingClusters.contains(clusterName) || commitFailingClusters.contains(clusterName)) {
            log.debug("In-memory commit FAIL: cluster={} txId={}", clusterName, txId);
            return false;
        }
        committedTx.computeIfAbsent(txId, k -> ConcurrentHashMap.newKeySet()).add(clusterName);
        log.debug("In-memory commit OK: cluster={} txId={}", clusterName, txId);
        return true;
    }

    @Override
    public boolean rollback(String clusterName, String endpoint, String txId) {
        if (failingClusters.contains(clusterName)) {
            log.debug("In-memory rollback FAIL: cluster={} txId={}", clusterName, txId);
            return false;
        }
        rolledBackTx.computeIfAbsent(txId, k -> ConcurrentHashMap.newKeySet()).add(clusterName);
        log.debug("In-memory rollback OK: cluster={} txId={}", clusterName, txId);
        return true;
    }

    /**
     * 设置模拟失败的集群（所有操作都失败）。
     */
    public void setFailingClusters(Set<String> clusters) {
        failingClusters.clear();
        failingClusters.addAll(clusters);
    }

    /**
     * 设置仅 prepare 失败的集群。
     */
    public void setPrepareFailingClusters(Set<String> clusters) {
        prepareFailingClusters.clear();
        prepareFailingClusters.addAll(clusters);
    }

    /**
     * 设置仅 commit 失败的集群。
     */
    public void setCommitFailingClusters(Set<String> clusters) {
        commitFailingClusters.clear();
        commitFailingClusters.addAll(clusters);
    }

    /**
     * 重置所有模拟状态。
     */
    public void reset() {
        failingClusters.clear();
        prepareFailingClusters.clear();
        commitFailingClusters.clear();
        preparedTx.clear();
        committedTx.clear();
        rolledBackTx.clear();
    }

    /**
     * 检查集群是否已 prepared 给定事务。
     */
    public boolean isPrepared(String txId, String clusterName) {
        Set<String> clusters = preparedTx.get(txId);
        return clusters != null && clusters.contains(clusterName);
    }

    /**
     * 检查集群是否已 committed 给定事务。
     */
    public boolean isCommitted(String txId, String clusterName) {
        Set<String> clusters = committedTx.get(txId);
        return clusters != null && clusters.contains(clusterName);
    }

    /**
     * 检查集群是否已 rolled back 给定事务。
     */
    public boolean isRolledBack(String txId, String clusterName) {
        Set<String> clusters = rolledBackTx.get(txId);
        return clusters != null && clusters.contains(clusterName);
    }
}