package com.levango7.dataenginebdp.federated.scheduling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PropagationPolicyGenerator} 单元测试。
 */
class PropagationPolicyGeneratorTest {

    private PropagationPolicyGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new PropagationPolicyGenerator();
    }

    @Test
    void shouldGenerateBasicYamlStructure() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("test-policy")
                .namespace("default")
                .apiVersion("apps/v1")
                .kind("Deployment")
                .workloadName("my-app")
                .build();

        String yaml = generator.generate(policy);

        assertThat(yaml).contains("apiVersion: policy.karmada.io/v1alpha1");
        assertThat(yaml).contains("kind: PropagationPolicy");
        assertThat(yaml).contains("name: test-policy");
        assertThat(yaml).contains("namespace: default");
        assertThat(yaml).contains("apiVersion: apps/v1");
        assertThat(yaml).contains("kind: Deployment");
        assertThat(yaml).contains("name: my-app");
    }

    @Test
    void shouldGenerateClusterAffinity() {
        Map<String, String> affinity = new HashMap<>();
        affinity.put("env", "prod");
        affinity.put("region", "cn-east");

        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("affinity-policy")
                .clusterAffinity(affinity)
                .build();

        String yaml = generator.generate(policy);

        assertThat(yaml).contains("clusterAffinity:");
        assertThat(yaml).contains("labelSelector:");
        assertThat(yaml).contains("matchLabels:");
        assertThat(yaml).contains("env: prod");
        assertThat(yaml).contains("region: cn-east");
    }

    @Test
    void shouldGenerateReplicaSchedulingForSpread() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("spread-policy")
                .replicaDistribution(SchedulingPolicy.ReplicaDistribution.SPREAD)
                .weight(80)
                .build();

        String yaml = generator.generate(policy);

        assertThat(yaml).contains("replicaScheduling:");
        assertThat(yaml).contains("replicaSchedulingType: Divided");
        assertThat(yaml).contains("replicaDivisionPreference: Weighted");
        assertThat(yaml).contains("weightPreference:");
        assertThat(yaml).contains("weight: 80");
        assertThat(yaml).contains("placementPolicyType: SPREAD");
    }

    @Test
    void shouldGenerateReplicaSchedulingForDuplicated() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("duplicated-policy")
                .replicaDistribution(SchedulingPolicy.ReplicaDistribution.DUPLICATED)
                .build();

        String yaml = generator.generate(policy);

        assertThat(yaml).contains("replicaSchedulingType: Duplicated");
        assertThat(yaml).contains("placementPolicyType: DUPLICATED");
    }

    @Test
    void shouldGenerateReplicaSchedulingForAggregated() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("aggregated-policy")
                .replicaDistribution(SchedulingPolicy.ReplicaDistribution.AGGREGATED)
                .build();

        String yaml = generator.generate(policy);

        assertThat(yaml).contains("replicaSchedulingType: Divided");
        assertThat(yaml).contains("replicaDivisionPreference: Aggregated");
        assertThat(yaml).contains("placementPolicyType: AGGREGATED");
    }

    @Test
    void shouldGenerateTopologySpreadConstraints() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("topology-policy")
                .policyTypes(List.of(SchedulingPolicy.PolicyType.TOPOLOGY_AWARE))
                .topologyDomain(SchedulingPolicy.TopologyDomain.ZONE)
                .minTopologySpread(3)
                .build();

        String yaml = generator.generate(policy);

        assertThat(yaml).contains("topologySpreadConstraints:");
        assertThat(yaml).contains("maxSkew: 1");
        assertThat(yaml).contains("topologyKey: zone");
        assertThat(yaml).contains("whenUnsatisfiable: DoNotSchedule");
        assertThat(yaml).contains("minDomains: 3");
    }

    @Test
    void shouldGenerateClusterTolerations() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("toleration-policy")
                .clusterTolerations(List.of("env=test:NoSchedule", "dedicated=gpu:NoExecute"))
                .build();

        String yaml = generator.generate(policy);

        assertThat(yaml).contains("clusterTolerations:");
        assertThat(yaml).contains("key: env");
        assertThat(yaml).contains("value: test");
        assertThat(yaml).contains("effect: NoSchedule");
        assertThat(yaml).contains("key: dedicated");
        assertThat(yaml).contains("value: gpu");
        assertThat(yaml).contains("effect: NoExecute");
    }

    @Test
    void shouldValidateGeneratedYaml() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("valid-policy")
                .build();

        String yaml = generator.generate(policy);
        List<String> missing = generator.validate(yaml);

        assertThat(missing).isEmpty();
    }

    @Test
    void shouldExtractPolicyName() {
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("extract-name-test")
                .build();

        String yaml = generator.generate(policy);
        String name = generator.extractPolicyName(yaml);

        assertThat(name).isEqualTo("extract-name-test");
    }

    @Test
    void shouldGenerateAllWithMultiplePolicies() {
        SchedulingPolicy p1 = SchedulingPolicy.builder().name("p1").build();
        SchedulingPolicy p2 = SchedulingPolicy.builder().name("p2").build();

        String yaml = generator.generateAll(List.of(p1, p2));

        assertThat(yaml).contains("---");
        assertThat(yaml).contains("name: p1");
        assertThat(yaml).contains("name: p2");
    }
}