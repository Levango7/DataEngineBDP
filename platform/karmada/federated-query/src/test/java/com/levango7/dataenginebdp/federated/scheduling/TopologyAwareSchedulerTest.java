package com.levango7.dataenginebdp.federated.scheduling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TopologyAwareScheduler} 单元测试。
 */
class TopologyAwareSchedulerTest {

    private TopologyAwareScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TopologyAwareScheduler();
    }

    private TopologyAwareScheduler.ClusterTopology cluster(String name, String region, String zone) {
        return TopologyAwareScheduler.ClusterTopology.builder()
                .clusterName(name)
                .region(region)
                .zone(zone)
                .availableCapacity(100)
                .available(true)
                .build();
    }

    @Test
    void shouldScheduleAcrossRegions() {
        List<TopologyAwareScheduler.ClusterTopology> candidates = Arrays.asList(
                cluster("c1", "us-east", "us-east-1a"),
                cluster("c2", "us-west", "us-west-1a"),
                cluster("c3", "eu-central", "eu-central-1a")
        );
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("cross-region")
                .crossRegionSpread(true)
                .crossZoneSpread(true)
                .build();

        TopologyAwareScheduler.TopologySpreadResult result =
                scheduler.schedule(6, candidates, policy);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRegions()).hasSize(3);
        assertThat(result.getDistribution().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(6);
    }

    @Test
    void shouldScheduleAcrossZonesWhenSingleRegion() {
        List<TopologyAwareScheduler.ClusterTopology> candidates = Arrays.asList(
                cluster("c1", "us-east", "us-east-1a"),
                cluster("c2", "us-east", "us-east-1b"),
                cluster("c3", "us-east", "us-east-1c")
        );
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("cross-zone")
                .crossRegionSpread(true)
                .crossZoneSpread(true)
                .build();

        TopologyAwareScheduler.TopologySpreadResult result =
                scheduler.schedule(3, candidates, policy);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getZones()).hasSize(3);
        assertThat(result.getMaxSkew()).isLessThanOrEqualTo(1);
    }

    @Test
    void shouldAggregateIntoSingleZoneWhenSameZoneAggregation() {
        List<TopologyAwareScheduler.ClusterTopology> candidates = Arrays.asList(
                cluster("c1", "us-east", "zone-a"),
                cluster("c2", "us-east", "zone-a"),
                cluster("c3", "us-west", "zone-b")
        );
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("aggregated")
                .sameZoneAggregation(true)
                .build();

        TopologyAwareScheduler.TopologySpreadResult result =
                scheduler.schedule(2, candidates, policy);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getZones().size()).isLessThanOrEqualTo(2);
    }

    @Test
    void shouldReturnEmptyWhenNoAvailableCluster() {
        List<TopologyAwareScheduler.ClusterTopology> candidates = Arrays.asList(
                TopologyAwareScheduler.ClusterTopology.builder()
                        .clusterName("c1")
                        .available(false)
                        .build()
        );
        SchedulingPolicy policy = SchedulingPolicy.builder().name("no-candidate").build();

        TopologyAwareScheduler.TopologySpreadResult result =
                scheduler.schedule(3, candidates, policy);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDistribution()).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenReplicasZero() {
        List<TopologyAwareScheduler.ClusterTopology> candidates = Arrays.asList(
                cluster("c1", "r1", "z1")
        );
        SchedulingPolicy policy = SchedulingPolicy.builder().name("zero-replicas").build();

        TopologyAwareScheduler.TopologySpreadResult result =
                scheduler.schedule(0, candidates, policy);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDistribution()).isEmpty();
    }

    @Test
    void shouldComputeMaxSkewCorrectly() {
        Map<String, Integer> distribution = new HashMap<>();
        distribution.put("c1", 3);
        distribution.put("c2", 1);
        distribution.put("c3", 2);

        int skew = scheduler.computeMaxSkew(distribution);

        assertThat(skew).isEqualTo(2);
    }

    @Test
    void shouldGetTopologyViewGroupByRegionAndZone() {
        List<TopologyAwareScheduler.ClusterTopology> topologies = Arrays.asList(
                cluster("c1", "r1", "z1"),
                cluster("c2", "r1", "z2"),
                cluster("c3", "r2", "z3")
        );

        Map<String, Map<String, List<String>>> view = scheduler.getTopologyView(topologies);

        assertThat(view).containsKeys("r1", "r2");
        assertThat(view.get("r1")).containsKeys("z1", "z2");
        assertThat(view.get("r1").get("z1")).containsExactly("c1");
        assertThat(view.get("r2").get("z3")).containsExactly("c3");
    }

    @Test
    void shouldValidateConstraintsForCrossRegionSpread() {
        List<TopologyAwareScheduler.ClusterTopology> candidates = Arrays.asList(
                cluster("c1", "r1", "z1"),
                cluster("c2", "r1", "z2")
        );
        SchedulingPolicy policy = SchedulingPolicy.builder()
                .name("require-cross-region")
                .crossRegionSpread(true)
                .build();

        TopologyAwareScheduler.TopologySpreadResult result =
                scheduler.schedule(2, candidates, policy);

        List<String> violations = scheduler.validateConstraints(result, policy);
        assertThat(violations).anyMatch(v -> v.contains("cross-region"));
    }

    @Test
    void shouldCollectRegionsAndZones() {
        List<TopologyAwareScheduler.ClusterTopology> candidates = Arrays.asList(
                cluster("c1", "r1", "z1"),
                cluster("c2", "r2", "z2"),
                cluster("c3", "r1", "z3")
        );

        assertThat(scheduler.collectRegions(candidates)).containsExactlyInAnyOrder("r1", "r2");
        assertThat(scheduler.collectZones(candidates)).containsExactlyInAnyOrder("z1", "z2", "z3");
    }
}