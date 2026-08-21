package com.levango7.dataenginebdp.federated.scheduling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 拓扑感知调度器。
 *
 * <p>根据集群拓扑信息（region / zone / az）和拓扑约束，计算工作负载副本在多集群间的最优分布。
 *
 * <p>调度优先级（从高到低）：
 * <ol>
 *   <li>跨 region 分布（地理级容灾，最高优先级）</li>
 *   <li>跨 zone 分布（数据中心级容灾，次优先级）</li>
 *   <li>同 zone 内聚集（节省跨 zone 带宽，最低优先级）</li>
 * </ol>
 *
 * <p>调度算法：
 * <ul>
 *   <li>按拓扑域分组候选集群</li>
 *   <li>使用 round-robin 在拓扑域间分配副本，确保跨域分布</li>
 *   <li>同 zone 聚集模式下，优先填满一个 zone 再分配到下一个</li>
 *   <li>计算 maxSkew（最大偏斜度），确保分布均匀性</li>
 * </ul>
 */
@Slf4j
@Component
public class TopologyAwareScheduler {

    /** 默认 region 值（集群未标注 region 时使用）。 */
    public static final String DEFAULT_REGION = "default-region";

    /** 默认 zone 值。 */
    public static final String DEFAULT_ZONE = "default-zone";

    /** 默认 az 值。 */
    public static final String DEFAULT_AZ = "default-az";

    /**
     * 集群拓扑信息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClusterTopology {

        /** 集群名称。 */
        private String clusterName;

        /** 所在 region。 */
        @Builder.Default
        private String region = DEFAULT_REGION;

        /** 所在 zone。 */
        @Builder.Default
        private String zone = DEFAULT_ZONE;

        /** 所在 az（可用区）。 */
        @Builder.Default
        private String az = DEFAULT_AZ;

        /** 集群可用容量（剩余可调度副本数，默认 100）。 */
        @Builder.Default
        private int availableCapacity = 100;

        /** 集群标签（来自 Karmada Cluster 对象的 metadata.labels）。 */
        @Builder.Default
        private Map<String, String> labels = new HashMap<>();

        /** 集群是否可用（健康且未 cordon）。 */
        @Builder.Default
        private boolean available = true;
    }

    /**
     * 拓扑感知调度结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopologySpreadResult {

        /** 集群 → 分配副本数。 */
        @Builder.Default
        private Map<String, Integer> distribution = new LinkedHashMap<>();

        /** 涉及的 region 列表。 */
        @Builder.Default
        private List<String> regions = new ArrayList<>();

        /** 涉及的 zone 列表。 */
        @Builder.Default
        private List<String> zones = new ArrayList<>();

        /** 最大偏斜度（maxSkew，0 表示完全均匀）。 */
        private int maxSkew;

        /** 调度原因/日志。 */
        @Builder.Default
        private List<String> reasons = new ArrayList<>();

        /** 是否调度成功（至少分配到一个集群）。 */
        private boolean success;
    }

    /**
     * 计算拓扑感知分布。
     *
     * @param replicas       总副本数
     * @param candidates     候选集群拓扑列表
     * @param policy         调度策略
     * @return 分布结果
     */
    public TopologySpreadResult schedule(int replicas,
                                         List<ClusterTopology> candidates,
                                         SchedulingPolicy policy) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        TopologySpreadResult.TopologySpreadResultBuilder builder = TopologySpreadResult.builder();
        List<String> reasons = new ArrayList<>();

        if (replicas <= 0) {
            reasons.add("replicas <= 0, no scheduling needed");
            return builder.distribution(new LinkedHashMap<>()).maxSkew(0).success(false).reasons(reasons).build();
        }

        // 过滤可用集群
        List<ClusterTopology> available = candidates.stream()
                .filter(ClusterTopology::isAvailable)
                .filter(c -> c.getAvailableCapacity() > 0)
                .collect(Collectors.toList());
        if (available.isEmpty()) {
            reasons.add("no available cluster with positive capacity");
            return builder.distribution(new LinkedHashMap<>()).maxSkew(0).success(false).reasons(reasons).build();
        }
        reasons.add(String.format("available clusters: %d", available.size()));

        // 根据策略选择调度模式
        Map<String, Integer> distribution;
        if (policy.isSameZoneAggregation()) {
            distribution = scheduleAggregated(replicas, available, reasons);
        } else if (policy.isCrossRegionSpread() && hasMultipleRegions(available)) {
            distribution = scheduleCrossRegion(replicas, available, reasons);
        } else if (policy.isCrossZoneSpread() && hasMultipleZones(available)) {
            distribution = scheduleCrossZone(replicas, available, reasons);
        } else {
            distribution = scheduleSimple(replicas, available, reasons);
        }

        // 计算统计信息
        List<String> regions = distribution.keySet().stream()
                .map(name -> findCluster(name, available))
                .filter(Objects::nonNull)
                .map(ClusterTopology::getRegion)
                .distinct()
                .collect(Collectors.toList());
        List<String> zones = distribution.keySet().stream()
                .map(name -> findCluster(name, available))
                .filter(Objects::nonNull)
                .map(ClusterTopology::getZone)
                .distinct()
                .collect(Collectors.toList());

        int maxSkew = computeMaxSkew(distribution);
        reasons.add(String.format("final distribution: %s, maxSkew=%d", distribution, maxSkew));

        return builder
                .distribution(distribution)
                .regions(regions)
                .zones(zones)
                .maxSkew(maxSkew)
                .success(!distribution.isEmpty())
                .reasons(reasons)
                .build();
    }

    /**
     * 跨 region 分布：round-robin 在 region 间分配副本。
     */
    private Map<String, Integer> scheduleCrossRegion(int replicas,
                                                     List<ClusterTopology> available,
                                                     List<String> reasons) {
        reasons.add("mode: cross-region spread");
        Map<String, List<ClusterTopology>> byRegion = available.stream()
                .collect(Collectors.groupingBy(ClusterTopology::getRegion, TreeMap::new, Collectors.toList()));
        reasons.add(String.format("regions: %s", byRegion.keySet()));

        return roundRobinDistribute(replicas, byRegion);
    }

    /**
     * 跨 zone 分布：round-robin 在 zone 间分配副本。
     */
    private Map<String, Integer> scheduleCrossZone(int replicas,
                                                   List<ClusterTopology> available,
                                                   List<String> reasons) {
        reasons.add("mode: cross-zone spread");
        Map<String, List<ClusterTopology>> byZone = available.stream()
                .collect(Collectors.groupingBy(ClusterTopology::getZone, TreeMap::new, Collectors.toList()));
        reasons.add(String.format("zones: %s", byZone.keySet()));

        return roundRobinDistribute(replicas, byZone);
    }

    /**
     * 同 zone 聚集：优先填满一个 zone 再分配到下一个。
     */
    private Map<String, Integer> scheduleAggregated(int replicas,
                                                    List<ClusterTopology> available,
                                                    List<String> reasons) {
        reasons.add("mode: same-zone aggregation");
        Map<String, Integer> distribution = new LinkedHashMap<>();
        int remaining = replicas;

        // 按 zone 排序，优先选择容量大的 zone
        Map<String, List<ClusterTopology>> byZone = available.stream()
                .collect(Collectors.groupingBy(ClusterTopology::getZone, TreeMap::new, Collectors.toList()));

        for (Map.Entry<String, List<ClusterTopology>> entry : byZone.entrySet()) {
            if (remaining <= 0) {
                break;
            }
            // 同 zone 内按容量降序填充
            List<ClusterTopology> clusters = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(ClusterTopology::getAvailableCapacity).reversed())
                    .collect(Collectors.toList());
            for (ClusterTopology c : clusters) {
                if (remaining <= 0) {
                    break;
                }
                int assign = Math.min(remaining, c.getAvailableCapacity());
                distribution.put(c.getClusterName(), assign);
                remaining -= assign;
            }
        }
        reasons.add(String.format("aggregated into %d zone(s)", distribution.values().stream().mapToInt(i -> i > 0 ? 1 : 0).sum()));
        return distribution;
    }

    /**
     * 简单分布：候选集群按容量比例分配。
     */
    private Map<String, Integer> scheduleSimple(int replicas,
                                                List<ClusterTopology> available,
                                                List<String> reasons) {
        reasons.add("mode: simple proportional spread");
        return roundRobinDistribute(replicas,
                Collections.singletonMap("all", available));
    }

    /**
     * round-robin 分配：在每组中轮询分配副本。
     */
    private Map<String, Integer> roundRobinDistribute(int replicas,
                                                      Map<String, List<ClusterTopology>> groups) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        // 展平为按组轮询的集群序列
        List<List<ClusterTopology>> groupLists = new ArrayList<>(groups.values());
        // 每组内按容量降序
        groupLists.forEach(g -> g.sort(Comparator.comparingInt(ClusterTopology::getAvailableCapacity).reversed()));

        int maxGroupSize = groupLists.stream().mapToInt(List::size).max().orElse(0);
        if (maxGroupSize == 0) {
            return distribution;
        }

        // 构建 round-robin 序列：第 i 轮从每组取第 j 个集群
        List<ClusterTopology> sequence = new ArrayList<>();
        for (int j = 0; j < maxGroupSize; j++) {
            for (List<ClusterTopology> group : groupLists) {
                if (j < group.size()) {
                    sequence.add(group.get(j));
                }
            }
        }

        // round-robin 分配
        int remaining = replicas;
        int idx = 0;
        while (remaining > 0 && !sequence.isEmpty()) {
            ClusterTopology c = sequence.get(idx % sequence.size());
            int current = distribution.getOrDefault(c.getClusterName(), 0);
            if (current < c.getAvailableCapacity()) {
                distribution.put(c.getClusterName(), current + 1);
                remaining--;
            }
            idx++;
            // 防止所有集群都满的死循环
            if (idx > replicas * sequence.size() * 2) {
                break;
            }
        }
        return distribution;
    }

    /**
     * 计算最大偏斜度（maxSkew = max(count) - min(count)，仅统计有分配的集群）。
     */
    public int computeMaxSkew(Map<String, Integer> distribution) {
        if (distribution == null || distribution.isEmpty()) {
            return 0;
        }
        int max = distribution.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int min = distribution.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        return max - min;
    }

    /**
     * 获取集群拓扑视图（按 region → zone → cluster 分组）。
     *
     * @param topologies 集群拓扑列表
     * @return region → zone → cluster names
     */
    public Map<String, Map<String, List<String>>> getTopologyView(List<ClusterTopology> topologies) {
        Map<String, Map<String, List<String>>> view = new TreeMap<>();
        if (topologies == null) {
            return view;
        }
        for (ClusterTopology t : topologies) {
            view.computeIfAbsent(t.getRegion(), k -> new TreeMap<>())
                    .computeIfAbsent(t.getZone(), k -> new ArrayList<>())
                    .add(t.getClusterName());
        }
        return view;
    }

    /**
     * 校验拓扑分布是否满足约束。
     *
     * @param result     分布结果
     * @param policy     调度策略
     * @return 违反的约束描述列表，空列表表示满足
     */
    public List<String> validateConstraints(TopologySpreadResult result, SchedulingPolicy policy) {
        List<String> violations = new ArrayList<>();
        if (result == null || !result.isSuccess()) {
            violations.add("scheduling failed");
            return violations;
        }
        if (policy.isCrossRegionSpread() && result.getRegions().size() < 2 && result.getDistribution().size() >= 2) {
            violations.add("cross-region spread required but only 1 region used");
        }
        if (policy.isCrossZoneSpread() && result.getZones().size() < 2 && result.getDistribution().size() >= 2) {
            violations.add("cross-zone spread required but only 1 zone used");
        }
        if (policy.getMinTopologySpread() > 0 && result.getDistribution().size() < policy.getMinTopologySpread()) {
            violations.add(String.format("minTopologySpread=%d but only %d clusters used",
                    policy.getMinTopologySpread(), result.getDistribution().size()));
        }
        return violations;
    }

    /**
     * 收集所有候选集群的 region 集合。
     */
    public Set<String> collectRegions(List<ClusterTopology> candidates) {
        if (candidates == null) {
            return Collections.emptySet();
        }
        return candidates.stream().map(ClusterTopology::getRegion).collect(Collectors.toSet());
    }

    /**
     * 收集所有候选集群的 zone 集合。
     */
    public Set<String> collectZones(List<ClusterTopology> candidates) {
        if (candidates == null) {
            return Collections.emptySet();
        }
        return candidates.stream().map(ClusterTopology::getZone).collect(Collectors.toSet());
    }

    private boolean hasMultipleRegions(List<ClusterTopology> clusters) {
        return collectRegions(clusters).size() >= 2;
    }

    private boolean hasMultipleZones(List<ClusterTopology> clusters) {
        return collectZones(clusters).size() >= 2;
    }

    private ClusterTopology findCluster(String name, List<ClusterTopology> clusters) {
        return clusters.stream().filter(c -> c.getClusterName().equals(name)).findFirst().orElse(null);
    }
}