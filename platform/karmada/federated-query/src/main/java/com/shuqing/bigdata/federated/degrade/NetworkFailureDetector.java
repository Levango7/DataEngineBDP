package com.shuqing.bigdata.federated.degrade;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网络中断检测器。
 *
 * <p>基于滑动窗口失败计数：每个集群维护一个失败计数器，达到阈值则标记该集群
 * 不可达，进入降级状态。冷却期内不再重复触发告警。
 */
@Component
public class NetworkFailureDetector {

    private final com.shuqing.bigdata.federated.config.FederatedQueryProperties props;
    private final ConcurrentHashMap<String, ClusterState> states = new ConcurrentHashMap<>();

    public NetworkFailureDetector(com.shuqing.bigdata.federated.config.FederatedQueryProperties props) {
        this.props = props;
    }

    /**
     * 记录一次失败。
     */
    public void recordFailure(String cluster, String reason) {
        ClusterState state = states.computeIfAbsent(cluster, k -> new ClusterState());
        state.failureCount.incrementAndGet();
        state.lastFailureAt = Instant.now();
        state.lastFailureReason = reason;
    }

    /**
     * 记录一次成功（重置失败计数）。
     */
    public void recordSuccess(String cluster) {
        ClusterState state = states.get(cluster);
        if (state != null) {
            int prev = state.failureCount.getAndSet(0);
            if (prev > 0) {
                state.recoveredAt = Instant.now();
            }
        }
    }

    /**
     * 判断集群是否应被降级（失败次数达阈值且未过冷却期）。
     */
    public boolean shouldDegrade(String cluster) {
        if (!props.getDegrade().isEnabled()) {
            return false;
        }
        ClusterState state = states.get(cluster);
        if (state == null) {
            return false;
        }
        if (state.failureCount.get() < props.getDegrade().getFailureThreshold()) {
            return false;
        }
        // 冷却期内持续降级
        if (state.degradedAt != null) {
            long cooldownMs = props.getDegrade().getCooldown().toMillis();
            return Instant.now().isBefore(state.degradedAt.plusMillis(cooldownMs));
        }
        // 首次达到阈值：标记降级
        state.degradedAt = Instant.now();
        return true;
    }

    /**
     * 获取集群状态快照。
     */
    public ClusterStateSnapshot getSnapshot(String cluster) {
        ClusterState s = states.get(cluster);
        if (s == null) {
            return new ClusterStateSnapshot(cluster, 0, null, null, false, null);
        }
        return new ClusterStateSnapshot(
                cluster,
                s.failureCount.get(),
                s.lastFailureAt,
                s.lastFailureReason,
                s.degradedAt != null,
                s.degradedAt);
    }

    /**
     * 重置某集群状态（恢复后调用）。
     */
    public void reset(String cluster) {
        states.remove(cluster);
    }

    @Data
    static class ClusterState {
        final AtomicInteger failureCount = new AtomicInteger(0);
        volatile Instant lastFailureAt;
        volatile String lastFailureReason;
        volatile Instant degradedAt;
        volatile Instant recoveredAt;
    }

    /** 集群状态快照（不可变）。 */
    public record ClusterStateSnapshot(
            String cluster,
            int failureCount,
            Instant lastFailureAt,
            String lastFailureReason,
            boolean degraded,
            Instant degradedAt) {}
}