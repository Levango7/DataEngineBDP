package com.levango7.dataenginebdp.federated.scheduling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FederatedScheduler} 单元测试（亲和性/反亲和性/评分/调度决策）。
 */
class FederatedSchedulerTest {

    private FederatedScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new FederatedScheduler(new TopologyAwareScheduler(), new PropagationPolicyGenerator());
    }

    private TopologyAwareScheduler.ClusterTopology cluster(String name, String region, String zone,
                                                            Map<String, String> labels) {
        return TopologyAwareScheduler.ClusterTopology.builder()
                .clusterName(name)
                .region(region)
                .zone(zone)
                .availableCapacity(100)
                .available(true)
                .labels(labels == null ? new HashMap<>() : labels)
                .build();
    }

    @Test
    void shouldRegisterAndRetrievePolicy() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("my-policy")
                .namespace("default")
                .build();

        scheduler.registerPolicy(policy);

        assertThat(scheduler.getPolicy("my-policy")).isNotNull();
        assertThat(scheduler.listPolicies()).hasSize(1);
    }

    @Test
    void shouldFilterByClusterAffinity() {
        Map<String, String> affinity = new HashMap<>();
        affinity.put("env", "prod");

        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("affinity")
                .clusterAffinity(affinity)
                .replicaDistribution(SchedulingPolicy.ReplicaDistribution.SPREAD)
                .build();

        List<TopologyAwareScheduler.ClusterTopology> candidates = Arrays.asList(
                cluster("c1", "r1", "z1", Map.of("env", "prod")),
                cluster("c2", "r1", "z2", Map.of("env", "dev")),
                cluster("c3", "r2", "z3", Map.of("env", "prod"))
        );

        FederatedScheduler.SchedulingInput input = FederatedScheduler.SchedulingInput.builder()
                .workloadName("my-app")
                .replicas(2)
                .candidates(candidates)
                .inlinePolicy(policy)
                .build();

        FederatedScheduler.SchedulingDecision decision = scheduler.decide(input);

        assertThat(decision.isSuccess()).isTrue();
        assertThat(decision.getDistribution().keySet()).containsOnly("c1", "c3");
    }

    @Test
    void shouldFilterByClusterAntiAffinity() {
        Map<String, String> antiAffinity = new HashMap<>();
        antiAffinity.put("dedicated", "gpu");

        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("anti-affinity")
                .clusterAntiAffinity(antiAffinity)
                .replicaDistribution(SchedulingPolicy.ReplicaDistribution.SPREAD)
                .build();

        List<TopologyAwareScheduler.ClusterTopology> candidates = Arrays.asList(
                cluster("c1", "r1", "z1", Map.of("dedicated", "gpu")),
                cluster("c2", "r1", "z2", Map.of("dedicated", "cpu")),
                cluster("c3", "r2", "z3", new HashMap<>())
        );

        FederatedScheduler.SchedulingInput input = FederatedScheduler.SchedulingInput.builder()
                .workloadName("my-app")
                .replicas(2)
                .candidates(candidates)
                .inlinePolicy(policy)
                .build();

        FederatedScheduler.SchedulingDecision decision = scheduler.decide(input);

        assertThat(decision.isSuccess()).isTrue();
        assertThat(decision.getDistribution().keySet()).doesNotContain("c1");
    }

    @Test
    void shouldFailWhenNoClusterPassesFilter() {
        Map<String, String> affinity = new HashMap<>();
        affinity.put("env", "prod");

        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("strict-affinity")
                .clusterAffinity(affinity)
                .build();

        List<TopologyAwareScheduler.ClusterTopology> candidates = Arrays.asList(
                cluster("c1", "r1", "z1", Map.of("env", "dev")),
                cluster("c2", "r1", "z2", Map.of("env", "test"))
        );

        FederatedScheduler.SchedulingInput input = FederatedScheduler.SchedulingInput.builder()
                .workloadName("my-app")
                .replicas(2)
                .candidates(candidates)
                .inlinePolicy(policy)
                .build();

        FederatedScheduler.SchedulingDecision decision = scheduler.decide(input);

        assertThat(decision.isSuccess()).isFalse();
        assertThat(decision.getDistribution()).isEmpty();
    }

    @Test
    void shouldFailWhenNoPolicyResolved() {
        FederatedScheduler.SchedulingInput input = FederatedScheduler.SchedulingInput.builder()
                .workloadName("my-app")
                .replicas(2)
                .candidates(Arrays.asList(cluster("c1", "r1", "z1", null)))
                .policyName("non-existent")
                .build();

        FederatedScheduler.SchedulingDecision decision = scheduler.decide(input);

        assertThat(decision.isSuccess()).isFalse();
    }

    @Test
    void shouldScoreClustersAndPickHighest() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("scoring")
                .replicaDistribution(SchedulingPolicy.ReplicaDistribution.SPREAD)
                .build();

        List<TopologyAwareScheduler.ClusterTopology> candidates = Arrays.asList(
                TopologyAwareScheduler.ClusterTopology.builder()
                        .clusterName("small-cap").region("r1").zone("z1")
                        .availableCapacity(10).available(true).labels(new HashMap<>()).build(),
                TopologyAwareScheduler.ClusterTopology.builder()
                        .clusterName("big-cap").region("r1").zone("z1")
                        .availableCapacity(100).available(true).labels(new HashMap<>()).build()
        );

        FederatedScheduler.SchedulingInput input = FederatedScheduler.SchedulingInput.builder()
                .workloadName("my-app")
                .replicas(1)
                .candidates(candidates)
                .inlinePolicy(policy)
                .build();

        FederatedScheduler.SchedulingDecision decision = scheduler.decide(input);

        assertThat(decision.isSuccess()).isTrue();
        assertThat(decision.getScores()).hasSize(2);
        FederatedScheduler.ClusterScore bigScore = decision.getScores().stream()
                .filter(s -> s.getClusterName().equals("big-cap")).findFirst().orElseThrow();
        FederatedScheduler.ClusterScore smallScore = decision.getScores().stream()
                .filter(s -> s.getClusterName().equals("small-cap")).findFirst().orElseThrow();
        assertThat(bigScore.getCapacityScore()).isGreaterThan(smallScore.getCapacityScore());
    }

    @Test
    void shouldApplyLatencyFirstProfile() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("latency-first")
                .latencyFirst(true)
                .replicaDistribution(SchedulingPolicy.ReplicaDistribution.SPREAD)
                .build();

        Map<String, Double> latencyMap = new HashMap<>();
        latencyMap.put("c1", 100.0);
        latencyMap.put("c2", 10.0);
        latencyMap.put("c3", 50.0);

        List<TopologyAwareScheduler.ClusterTopology> candidates = Arrays.asList(
                cluster("c1", "r1", "z1", null),
                cluster("c2", "r1", "z2", null),
                cluster("c3", "r2", "z3", null)
        );

        FederatedScheduler.SchedulingInput input = FederatedScheduler.SchedulingInput.builder()
                .workloadName("my-app")
                .replicas(1)
                .candidates(candidates)
                .inlinePolicy(policy)
                .latencyMap(latencyMap)
                .build();

        FederatedScheduler.SchedulingDecision decision = scheduler.decide(input);

        assertThat(decision.isSuccess()).isTrue();
        assertThat(decision.getDistribution()).containsKey("c2");
    }

    @Test
    void shouldRecordDecisionHistory() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("history-test")
                .build();

        FederatedScheduler.SchedulingInput input = FederatedScheduler.SchedulingInput.builder()
                .workloadName("my-app")
                .replicas(1)
                .candidates(Arrays.asList(cluster("c1", "r1", "z1", null)))
                .inlinePolicy(policy)
                .build();

        scheduler.decide(input);
        scheduler.decide(input);

        List<FederatedScheduler.SchedulingDecision> history = scheduler.listDecisions(10);
        assertThat(history).hasSize(2);
    }

    @Test
    void shouldGeneratePropagationPolicyYaml() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("yaml-gen")
                .workloadName("my-app")
                .build();
        scheduler.registerPolicy(policy);

        String yaml = scheduler.generatePropagationPolicy("yaml-gen");

        assertThat(yaml).contains("apiVersion: policy.karmada.io/v1alpha1");
        assertThat(yaml).contains("name: yaml-gen");
        assertThat(yaml).contains("name: my-app");
    }

    @Test
    void shouldRemovePolicy() {
        SchedulingPolicy policy = SchedulingPolicy.builder().name("removable").build();
        scheduler.registerPolicy(policy);

        boolean removed = scheduler.removePolicy("removable");

        assertThat(removed).isTrue();
        assertThat(scheduler.getPolicy("removable")).isNull();
    }
}