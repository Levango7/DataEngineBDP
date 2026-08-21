package com.levango7.dataenginebdp.federated.scheduling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 联邦调度服务。
 *
 * <p>整合亲和性 / 反亲和性 / 拓扑感知三种策略，对候选集群打分并选择最优分布。
 *
 * <p>调度流程：
 * <ol>
 *   <li>过滤：根据亲和性/反亲和性标签选择器过滤候选集群</li>
 *   <li>打分：对每个候选集群按策略组合打分</li>
 *   <li>排序：按总分降序排列</li>
 *   <li>分布：调用 {@link TopologyAwareScheduler} 计算副本分布</li>
 *   <li>记录：保存调度决策和原因日志</li>
 * </ol>
 *
 * <p>评分规则（每项 0-100，加权平均）：
 * <ul>
 *   <li>亲和性评分：匹配 clusterAffinity 标签数 / 总标签数 × 100</li>
 *   <li>反亲和性评分：未匹配 clusterAntiAffinity 标签数 / 总标签数 × 100</li>
 *   <li>拓扑评分：拓扑分布均匀度（maxSkck 越小分越高）</li>
 *   <li>容量评分：availableCapacity / maxCapacity × 100</li>
 *   <li>延迟评分：latencyFirst 模式下，延迟越低分越高（外部输入）</li>
 *   <li>成本评分：costFirst 模式下，成本越低分越高（外部输入）</li>
 *   <li>可用性评分：availabilityFirst 模式下，可用性越高分越高（外部输入）</li>
 * </ul>
 */
@Slf4j
@Service
public class FederatedScheduler {

    private final TopologyAwareScheduler topologyScheduler;
    private final PropagationPolicyGenerator policyGenerator;

    /** 内存策略存储（生产环境可替换为 JPA 持久化）。 */
    private final Map<String, SchedulingPolicy> policyStore = new ConcurrentHashMap<>();

    /** 调度决策历史（最近 1000 条）。 */
    private final List<SchedulingDecision> decisionHistory = Collections.synchronizedList(new ArrayList<>(1000));

    /** 默认权重：亲和性 30 / 反亲和性 20 / 拓扑 30 / 容量 20。 */
    private static final double W_AFFINITY = 0.30;
    private static final double W_ANTI_AFFINITY = 0.20;
    private static final double W_TOPOLOGY = 0.30;
    private static final double W_CAPACITY = 0.20;

    public FederatedScheduler(TopologyAwareScheduler topologyScheduler,
                              PropagationPolicyGenerator policyGenerator) {
        this.topologyScheduler = topologyScheduler;
        this.policyGenerator = policyGenerator;
    }

    /**
     * 集群评分。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClusterScore {

        /** 集群名。 */
        private String clusterName;

        /** 亲和性评分（0-100）。 */
        private double affinityScore;

        /** 反亲和性评分（0-100）。 */
        private double antiAffinityScore;

        /** 拓扑评分（0-100）。 */
        private double topologyScore;

        /** 容量评分（0-100）。 */
        private double capacityScore;

        /** 加权总分（0-100）。 */
        private double totalScore;

        /** 评分原因。 */
        @Builder.Default
        private List<String> reasons = new ArrayList<>();
    }

    /**
     * 调度决策。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SchedulingDecision {

        /** 决策 ID。 */
        private String decisionId;

        /** 关联策略名。 */
        private String policyName;

        /** 工作负载名称。 */
        private String workloadName;

        /** 输入副本数。 */
        private int replicas;

        /** 候选集群数。 */
        private int candidateCount;

        /** 选中集群 → 副本数。 */
        @Builder.Default
        private Map<String, Integer> distribution = new LinkedHashMap<>();

        /** 每个候选集群的评分。 */
        @Builder.Default
        private List<ClusterScore> scores = new ArrayList<>();

        /** 调度原因日志。 */
        @Builder.Default
        private List<String> reasons = new ArrayList<>();

        /** 决策时间戳。 */
        private Instant timestamp;

        /** 是否成功。 */
        private boolean success;
    }

    /**
     * 调度决策输入。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SchedulingInput {

        /** 工作负载名称。 */
        private String workloadName;

        /** 副本数。 */
        private int replicas;

        /** 候选集群拓扑列表。 */
        @Builder.Default
        private List<TopologyAwareScheduler.ClusterTopology> candidates = new ArrayList<>();

        /** 调度策略名（引用已注册策略）。 */
        private String policyName;

        /** 内联调度策略（覆盖 policyName 引用）。 */
        private SchedulingPolicy inlinePolicy;

        /** 集群延迟信息（clusterName → latencyMs，latencyFirst 模式使用）。 */
        @Builder.Default
        private Map<String, Double> latencyMap = new LinkedHashMap<>();

        /** 集群成本信息（clusterName → cost，costFirst 模式使用）。 */
        @Builder.Default
        private Map<String, Double> costMap = new LinkedHashMap<>();

        /** 集群可用性信息（clusterName → availability 0-1，availabilityFirst 模式使用）。 */
        @Builder.Default
        private Map<String, Double> availabilityMap = new LinkedHashMap<>();
    }

    /**
     * 执行调度决策。
     *
     * @param input 调度输入
     * @return 调度决策
     */
    public SchedulingDecision decide(SchedulingInput input) {
        Objects.requireNonNull(input, "input must not be null");
        List<String> reasons = new ArrayList<>();
        String decisionId = "dec-" + System.nanoTime();
        reasons.add(String.format("decisionId=%s, workload=%s, replicas=%d",
                decisionId, input.getWorkloadName(), input.getReplicas()));

        SchedulingPolicy policy = resolvePolicy(input, reasons);
        if (policy == null) {
            return failedDecision(decisionId, input, reasons, "no policy resolved");
        }

        List<TopologyAwareScheduler.ClusterTopology> candidates = input.getCandidates();
        if (candidates == null || candidates.isEmpty()) {
            return failedDecision(decisionId, input, reasons, "no candidate clusters");
        }
        reasons.add(String.format("candidates: %d", candidates.size()));

        // 1. 过滤候选集群（亲和性/反亲和性）
        List<TopologyAwareScheduler.ClusterTopology> filtered = filterCandidates(candidates, policy, reasons);
        if (filtered.isEmpty()) {
            return failedDecision(decisionId, input, reasons, "no cluster passed affinity filter");
        }
        reasons.add(String.format("after filter: %d", filtered.size()));

        // 2. 对每个候选集群打分
        List<ClusterScore> scores = scoreClusters(filtered, policy, input);
        reasons.add(String.format("scored %d clusters", scores.size()));

        // 3. 按总分排序
        List<TopologyAwareScheduler.ClusterTopology> sorted = filtered.stream()
                .sorted(Comparator.comparingDouble(
                        (TopologyAwareScheduler.ClusterTopology c) ->
                                findScore(scores, c.getClusterName()).getTotalScore()).reversed())
                .collect(Collectors.toList());
        reasons.add(String.format("top cluster: %s (score=%.2f)",
                sorted.get(0).getClusterName(),
                findScore(scores, sorted.get(0).getClusterName()).getTotalScore()));

        // 4. 拓扑感知分布
        TopologyAwareScheduler.TopologySpreadResult spread =
                topologyScheduler.schedule(input.getReplicas(), sorted, policy);
        reasons.addAll(spread.getReasons());

        // 5. 构造决策
        SchedulingDecision decision = SchedulingDecision.builder()
                .decisionId(decisionId)
                .policyName(policy.getName())
                .workloadName(input.getWorkloadName())
                .replicas(input.getReplicas())
                .candidateCount(candidates.size())
                .distribution(spread.getDistribution())
                .scores(scores)
                .reasons(reasons)
                .timestamp(Instant.now())
                .success(spread.isSuccess())
                .build();

        // 6. 记录历史
        addHistory(decision);

        log.info("Scheduling decision: id={}, success={}, distribution={}",
                decisionId, decision.isSuccess(), decision.getDistribution());
        return decision;
    }

    /**
     * 注册调度策略。
     */
    public SchedulingPolicy registerPolicy(SchedulingPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(policy.getName(), "policy name must not be null");
        policyStore.put(policy.getName(), policy);
        log.info("Registered scheduling policy: {}", policy.getName());
        return policy;
    }

    /**
     * 获取已注册策略。
     */
    public SchedulingPolicy getPolicy(String name) {
        return policyStore.get(name);
    }

    /**
     * 列出所有已注册策略。
     */
    public List<SchedulingPolicy> listPolicies() {
        return new ArrayList<>(policyStore.values());
    }

    /**
     * 删除策略。
     */
    public boolean removePolicy(String name) {
        boolean removed = policyStore.remove(name) != null;
        if (removed) {
            log.info("Removed scheduling policy: {}", name);
        }
        return removed;
    }

    /**
     * 生成策略对应的 PropagationPolicy YAML。
     */
    public String generatePropagationPolicy(String policyName) {
        SchedulingPolicy policy = policyStore.get(policyName);
        if (policy == null) {
            throw new IllegalArgumentException("policy not found: " + policyName);
        }
        return policyGenerator.generate(policy);
    }

    /**
     * 获取调度决策历史。
     */
    public List<SchedulingDecision> listDecisions(int limit) {
        int n = Math.min(limit, decisionHistory.size());
        return decisionHistory.subList(decisionHistory.size() - n, decisionHistory.size());
    }

    /**
     * 获取集群拓扑视图。
     */
    public Map<String, Map<String, List<String>>> getTopologyView(
            List<TopologyAwareScheduler.ClusterTopology> topologies) {
        return topologyScheduler.getTopologyView(topologies);
    }

    // ==================== 内部方法 ====================

    private SchedulingPolicy resolvePolicy(SchedulingInput input, List<String> reasons) {
        if (input.getInlinePolicy() != null) {
            reasons.add("using inline policy");
            return input.getInlinePolicy();
        }
        if (input.getPolicyName() != null) {
            SchedulingPolicy p = policyStore.get(input.getPolicyName());
            if (p != null) {
                reasons.add(String.format("using registered policy: %s", input.getPolicyName()));
                return p;
            }
            reasons.add(String.format("policy not found: %s", input.getPolicyName()));
        }
        return null;
    }

    private List<TopologyAwareScheduler.ClusterTopology> filterCandidates(
            List<TopologyAwareScheduler.ClusterTopology> candidates,
            SchedulingPolicy policy,
            List<String> reasons) {
        Map<String, String> affinity = policy.getClusterAffinity();
        Map<String, String> antiAffinity = policy.getClusterAntiAffinity();

        List<TopologyAwareScheduler.ClusterTopology> filtered = candidates.stream()
                .filter(c -> {
                    // 亲和性过滤：集群 labels 必须包含所有 affinity 键值对
                    if (affinity != null && !affinity.isEmpty()) {
                        for (Map.Entry<String, String> e : affinity.entrySet()) {
                            String actual = c.getLabels() == null ? null : c.getLabels().get(e.getKey());
                            if (!e.getValue().equals(actual)) {
                                return false;
                            }
                        }
                    }
                    // 反亲和性过滤：集群 labels 不能匹配任何 antiAffinity 键值对
                    if (antiAffinity != null && !antiAffinity.isEmpty()) {
                        for (Map.Entry<String, String> e : antiAffinity.entrySet()) {
                            String actual = c.getLabels() == null ? null : c.getLabels().get(e.getKey());
                            if (e.getValue().equals(actual)) {
                                return false;
                            }
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

        if (affinity != null && !affinity.isEmpty()) {
            reasons.add(String.format("affinity filter: %s", affinity));
        }
        if (antiAffinity != null && !antiAffinity.isEmpty()) {
            reasons.add(String.format("anti-affinity filter: %s", antiAffinity));
        }
        return filtered;
    }

    private List<ClusterScore> scoreClusters(List<TopologyAwareScheduler.ClusterTopology> candidates,
                                             SchedulingPolicy policy,
                                             SchedulingInput input) {
        int maxCapacity = candidates.stream()
                .mapToInt(TopologyAwareScheduler.ClusterTopology::getAvailableCapacity)
                .max().orElse(1);

        return candidates.stream().map(c -> {
            double aff = scoreAffinity(c, policy);
            double anti = scoreAntiAffinity(c, policy);
            double topo = scoreTopology(c, candidates, policy);
            double cap = scoreCapacity(c, maxCapacity);

            // profile 加权
            double total = W_AFFINITY * aff + W_ANTI_AFFINITY * anti + W_TOPOLOGY * topo + W_CAPACITY * cap;

            List<String> r = new ArrayList<>();
            r.add(String.format("aff=%.1f, anti=%.1f, topo=%.1f, cap=%.1f -> total=%.2f",
                    aff, anti, topo, cap, total));

            // latency/cost/availability 加权
            if (policy.isLatencyFirst() && input.getLatencyMap() != null
                    && input.getLatencyMap().containsKey(c.getClusterName())) {
                double latScore = scoreLatency(input.getLatencyMap(), c.getClusterName());
                total = 0.5 * total + 0.5 * latScore;
                r.add(String.format("latency-first: latScore=%.1f, adjusted total=%.2f", latScore, total));
            }
            if (policy.isCostFirst() && input.getCostMap() != null
                    && input.getCostMap().containsKey(c.getClusterName())) {
                double costScore = scoreCost(input.getCostMap(), c.getClusterName());
                total = 0.5 * total + 0.5 * costScore;
                r.add(String.format("cost-first: costScore=%.1f, adjusted total=%.2f", costScore, total));
            }
            if (policy.isAvailabilityFirst() && input.getAvailabilityMap() != null
                    && input.getAvailabilityMap().containsKey(c.getClusterName())) {
                double availScore = input.getAvailabilityMap().get(c.getClusterName()) * 100.0;
                total = 0.5 * total + 0.5 * availScore;
                r.add(String.format("availability-first: availScore=%.1f, adjusted total=%.2f", availScore, total));
            }

            return ClusterScore.builder()
                    .clusterName(c.getClusterName())
                    .affinityScore(aff)
                    .antiAffinityScore(anti)
                    .topologyScore(topo)
                    .capacityScore(cap)
                    .totalScore(total)
                    .reasons(r)
                    .build();
        }).collect(Collectors.toList());
    }

    private double scoreAffinity(TopologyAwareScheduler.ClusterTopology c, SchedulingPolicy policy) {
        Map<String, String> affinity = policy.getClusterAffinity();
        if (affinity == null || affinity.isEmpty()) {
            return 100.0;
        }
        int matched = 0;
        for (Map.Entry<String, String> e : affinity.entrySet()) {
            String actual = c.getLabels() == null ? null : c.getLabels().get(e.getKey());
            if (e.getValue().equals(actual)) {
                matched++;
            }
        }
        return 100.0 * matched / affinity.size();
    }

    private double scoreAntiAffinity(TopologyAwareScheduler.ClusterTopology c, SchedulingPolicy policy) {
        Map<String, String> antiAffinity = policy.getClusterAntiAffinity();
        if (antiAffinity == null || antiAffinity.isEmpty()) {
            return 100.0;
        }
        int notMatched = 0;
        for (Map.Entry<String, String> e : antiAffinity.entrySet()) {
            String actual = c.getLabels() == null ? null : c.getLabels().get(e.getKey());
            if (!e.getValue().equals(actual)) {
                notMatched++;
            }
        }
        return 100.0 * notMatched / antiAffinity.size();
    }

    private double scoreTopology(TopologyAwareScheduler.ClusterTopology c,
                                 List<TopologyAwareScheduler.ClusterTopology> all,
                                 SchedulingPolicy policy) {
        // 拓扑评分：集群所在 region/zone 内集群数越少，分越高（鼓励跨域分布）
        long sameRegion = all.stream()
                .filter(x -> x.getRegion().equals(c.getRegion())).count();
        long sameZone = all.stream()
                .filter(x -> x.getZone().equals(c.getZone())).count();
        // region 内集群少 → 高分；zone 内集群少 → 高分
        double regionScore = 100.0 / Math.max(1, sameRegion);
        double zoneScore = 100.0 / Math.max(1, sameZone);
        return 0.5 * regionScore + 0.5 * zoneScore;
    }

    private double scoreCapacity(TopologyAwareScheduler.ClusterTopology c, int maxCapacity) {
        if (maxCapacity <= 0) {
            return 0.0;
        }
        return 100.0 * c.getAvailableCapacity() / maxCapacity;
    }

    private double scoreLatency(Map<String, Double> latencyMap, String clusterName) {
        double lat = latencyMap.getOrDefault(clusterName, Double.MAX_VALUE);
        double minLat = latencyMap.values().stream().mapToDouble(d -> d).min().orElse(lat);
        double maxLat = latencyMap.values().stream().mapToDouble(d -> d).max().orElse(lat);
        if (maxLat <= minLat) {
            return 100.0;
        }
        // 延迟越低分越高
        return 100.0 * (maxLat - lat) / (maxLat - minLat);
    }

    private double scoreCost(Map<String, Double> costMap, String clusterName) {
        double cost = costMap.getOrDefault(clusterName, Double.MAX_VALUE);
        double minCost = costMap.values().stream().mapToDouble(d -> d).min().orElse(cost);
        double maxCost = costMap.values().stream().mapToDouble(d -> d).max().orElse(cost);
        if (maxCost <= minCost) {
            return 100.0;
        }
        return 100.0 * (maxCost - cost) / (maxCost - minCost);
    }

    private ClusterScore findScore(List<ClusterScore> scores, String name) {
        return scores.stream().filter(s -> s.getClusterName().equals(name)).findFirst()
                .orElse(ClusterScore.builder().clusterName(name).totalScore(0).build());
    }

    private void addHistory(SchedulingDecision decision) {
        decisionHistory.add(decision);
        // 限制历史大小
        while (decisionHistory.size() > 1000) {
            decisionHistory.remove(0);
        }
    }

    private SchedulingDecision failedDecision(String decisionId, SchedulingInput input,
                                              List<String> reasons, String cause) {
        reasons.add("FAILED: " + cause);
        log.warn("Scheduling failed: id={}, cause={}", decisionId, cause);
        return SchedulingDecision.builder()
                .decisionId(decisionId)
                .policyName(input.getPolicyName())
                .workloadName(input.getWorkloadName())
                .replicas(input.getReplicas())
                .candidateCount(input.getCandidates() == null ? 0 : input.getCandidates().size())
                .distribution(new LinkedHashMap<>())
                .scores(new ArrayList<>())
                .reasons(reasons)
                .timestamp(Instant.now())
                .success(false)
                .build();
    }
}