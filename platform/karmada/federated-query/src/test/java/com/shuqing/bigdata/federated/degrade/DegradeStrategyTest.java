package com.shuqing.bigdata.federated.degrade;

import com.shuqing.bigdata.federated.config.FederatedQueryProperties;
import com.shuqing.bigdata.federated.model.ClusterQueryResult;
import com.shuqing.bigdata.federated.model.TableLocation;
import com.shuqing.bigdata.federated.transport.ClusterTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DegradeStrategy} 与 {@link NetworkFailureDetector} 单元测试。
 */
class DegradeStrategyTest {

    private FederatedQueryProperties props;
    private NetworkFailureDetector detector;
    private AlertNotifier alertNotifier;
    private ClusterTransport transport;
    private DegradeStrategy strategy;

    @BeforeEach
    void setUp() {
        props = new FederatedQueryProperties();
        props.setLocalCluster("local-cluster");
        FederatedQueryProperties.DegradeConfig deg = new FederatedQueryProperties.DegradeConfig();
        deg.setEnabled(true);
        deg.setFailureThreshold(3);
        deg.setCooldown(Duration.ofSeconds(30));
        deg.setQueryTimeout(Duration.ofSeconds(30));
        props.setDegrade(deg);

        FederatedQueryProperties.ClusterEndpoint localEp = new FederatedQueryProperties.ClusterEndpoint();
        localEp.setUrl("http://localhost:8092");
        localEp.setEnabled(true);
        localEp.setLocal(true);
        props.getClusters().put("local-cluster", localEp);

        detector = new NetworkFailureDetector(props);
        alertNotifier = new AlertNotifier(props);
        transport = mock(ClusterTransport.class);
        strategy = new DegradeStrategy(props, detector, alertNotifier, transport);
    }

    @Test
    void shouldDegrade_afterThresholdFailures() {
        for (int i = 0; i < 3; i++) {
            detector.recordFailure("cce-cluster", "timeout");
        }
        assertThat(detector.shouldDegrade("cce-cluster")).isTrue();
        assertThat(detector.shouldDegrade("local-cluster")).isFalse();
    }

    @Test
    void shouldNotDegrade_belowThreshold() {
        detector.recordFailure("cce-cluster", "timeout");
        detector.recordFailure("cce-cluster", "timeout");
        assertThat(detector.shouldDegrade("cce-cluster")).isFalse();
    }

    @Test
    void decide_shouldDegradeUnreachableCluster() {
        for (int i = 0; i < 3; i++) {
            detector.recordFailure("cce-cluster", "timeout");
        }
        DegradeStrategy.DegradeDecision decision = strategy.decide(
                List.of("cce-cluster", "local-cluster"), Collections.emptyMap());

        assertThat(decision.degraded()).isTrue();
        assertThat(decision.actualClusters()).containsExactly("local-cluster");
        assertThat(decision.alerts()).hasSize(1);
    }

    @Test
    void handleFailure_shouldDegradeToLocal() {
        ClusterQueryResult failed = ClusterQueryResult.builder()
                .cluster("cce-cluster")
                .success(false)
                .error("connection refused")
                .build();
        ClusterQueryResult localOk = ClusterQueryResult.builder()
                .cluster("local-cluster")
                .success(true)
                .rows(Collections.emptyList())
                .build();
        when(transport.execute(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(localOk);

        ClusterQueryResult result = strategy.handleFailure(
                "cce-cluster", failed, "SELECT 1", "default", Collections.emptyMap());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isDegraded()).isTrue();
        assertThat(result.getCluster()).isEqualTo("local-cluster");
    }

    @Test
    void recordSuccess_shouldResetFailureCount() {
        detector.recordFailure("cce-cluster", "timeout");
        detector.recordFailure("cce-cluster", "timeout");
        detector.recordFailure("cce-cluster", "timeout");
        assertThat(detector.shouldDegrade("cce-cluster")).isTrue();
        detector.recordSuccess("cce-cluster");
        // 失败计数已重置，但降级标记仍在冷却期内
        // 重置后再次失败不应立即降级
        detector.reset("cce-cluster");
        detector.recordFailure("cce-cluster", "timeout");
        assertThat(detector.shouldDegrade("cce-cluster")).isFalse();
    }
}