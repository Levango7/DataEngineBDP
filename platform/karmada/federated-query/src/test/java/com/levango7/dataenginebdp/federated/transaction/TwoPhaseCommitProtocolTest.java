package com.levango7.dataenginebdp.federated.transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TwoPhaseCommitProtocol} 单元测试。
 *
 * <p>覆盖正常流程、部分失败、超时、重试场景。
 */
class TwoPhaseCommitProtocolTest {

    private ClusterTransactionClient client;
    private TwoPhaseCommitProtocol protocol;

    private static final Map<String, String> ENDPOINTS = Map.of(
            "cluster-a", "http://a:8090",
            "cluster-b", "http://b:8090",
            "cluster-c", "http://c:8090");

    @BeforeEach
    void setUp() {
        client = mock(ClusterTransactionClient.class);
        protocol = new TwoPhaseCommitProtocol(client, 2000, 2000, 3, 50);
    }

    @Test
    void shouldPrepareAllClustersWhenAllSucceed() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);

        TwoPhaseCommitProtocol.PrepareResult result = protocol.executePrepare(ENDPOINTS, "tx-1");

        assertThat(result.isAllPrepared()).isTrue();
        assertThat(result.getPreparedClusters()).containsExactlyInAnyOrder("cluster-a", "cluster-b", "cluster-c");
        assertThat(result.getFailedClusters()).isEmpty();
        assertThat(result.getTimedOutClusters()).isEmpty();
        verify(client, times(3)).prepare(anyString(), anyString(), anyString());
    }

    @Test
    void shouldReturnFailedClustersWhenSomePrepareFails() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        when(client.prepare("cluster-b", "http://b:8090", "tx-1")).thenReturn(false);

        TwoPhaseCommitProtocol.PrepareResult result = protocol.executePrepare(ENDPOINTS, "tx-1");

        assertThat(result.isAllPrepared()).isFalse();
        assertThat(result.getPreparedClusters()).containsExactlyInAnyOrder("cluster-a", "cluster-c");
        assertThat(result.getFailedClusters()).containsExactly("cluster-b");
    }

    @Test
    void shouldCommitAllPreparedClustersWhenAllSucceed() {
        when(client.commit(anyString(), anyString(), anyString())).thenReturn(true);

        TwoPhaseCommitProtocol.CommitResult result = protocol.executeCommit(
                List.of("cluster-a", "cluster-b"), ENDPOINTS, "tx-1");

        assertThat(result.isAllCommitted()).isTrue();
        assertThat(result.getCommittedClusters()).containsExactlyInAnyOrder("cluster-a", "cluster-b");
        assertThat(result.getFailedClusters()).isEmpty();
    }

    @Test
    void shouldRetryCommitOnFailureAndEventuallySucceed() {
        when(client.commit("cluster-a", "http://a:8090", "tx-1"))
                .thenReturn(false, false, true);
        when(client.commit("cluster-b", "http://b:8090", "tx-1")).thenReturn(true);

        TwoPhaseCommitProtocol.CommitResult result = protocol.executeCommit(
                List.of("cluster-a", "cluster-b"), ENDPOINTS, "tx-1");

        assertThat(result.isAllCommitted()).isTrue();
        assertThat(result.getCommittedClusters()).containsExactlyInAnyOrder("cluster-a", "cluster-b");
        assertThat(result.getRetryCounts().get("cluster-a")).isEqualTo(2);
        verify(client, times(3)).commit("cluster-a", "http://a:8090", "tx-1");
    }

    @Test
    void shouldMarkCommitFailedAfterMaxRetries() {
        when(client.commit("cluster-a", "http://a:8090", "tx-1")).thenReturn(false);
        when(client.commit("cluster-b", "http://b:8090", "tx-1")).thenReturn(true);

        TwoPhaseCommitProtocol.CommitResult result = protocol.executeCommit(
                List.of("cluster-a", "cluster-b"), ENDPOINTS, "tx-1");

        assertThat(result.isAllCommitted()).isFalse();
        assertThat(result.getCommittedClusters()).containsExactly("cluster-b");
        assertThat(result.getFailedClusters()).containsExactly("cluster-a");
        verify(client, times(4)).commit("cluster-a", "http://a:8090", "tx-1");
    }

    @Test
    void shouldRollbackAllParticipatedClusters() {
        when(client.rollback(anyString(), anyString(), anyString())).thenReturn(true);

        TwoPhaseCommitProtocol.RollbackResult result = protocol.executeRollback(
                List.of("cluster-a", "cluster-b", "cluster-c"), ENDPOINTS, "tx-1");

        assertThat(result.isAllRolledBack()).isTrue();
        assertThat(result.getRolledBackClusters())
                .containsExactlyInAnyOrder("cluster-a", "cluster-b", "cluster-c");
        verify(client, times(3)).rollback(anyString(), anyString(), anyString());
    }

    @Test
    void shouldContinueRollbackEvenIfSomeFail() {
        when(client.rollback(anyString(), anyString(), anyString())).thenReturn(true);
        when(client.rollback("cluster-b", "http://b:8090", "tx-1")).thenReturn(false);

        TwoPhaseCommitProtocol.RollbackResult result = protocol.executeRollback(
                List.of("cluster-a", "cluster-b", "cluster-c"), ENDPOINTS, "tx-1");

        assertThat(result.isAllRolledBack()).isFalse();
        assertThat(result.getRolledBackClusters()).containsExactlyInAnyOrder("cluster-a", "cluster-c");
        assertThat(result.getFailedClusters()).containsExactly("cluster-b");
    }

    @Test
    void shouldHandlePrepareTimeoutByTreatingAsFailure() {
        when(client.prepare(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(5000);
            return true;
        });
        TwoPhaseCommitProtocol shortTimeoutProtocol = new TwoPhaseCommitProtocol(client, 200, 200, 1, 10);

        TwoPhaseCommitProtocol.PrepareResult result = shortTimeoutProtocol.executePrepare(
                new HashMap<>(Map.of("cluster-a", "http://a:8090")), "tx-1");

        assertThat(result.isAllPrepared()).isFalse();
        assertThat(result.getPreparedClusters()).doesNotContain("cluster-a");
    }

    @Test
    void shouldNotCallCommitForEmptyPreparedList() {
        TwoPhaseCommitProtocol.CommitResult result = protocol.executeCommit(
                List.of(), ENDPOINTS, "tx-1");

        assertThat(result.isAllCommitted()).isTrue();
        assertThat(result.getCommittedClusters()).isEmpty();
        verify(client, never()).commit(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRecordElapsedMsForPrepare() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);

        TwoPhaseCommitProtocol.PrepareResult result = protocol.executePrepare(ENDPOINTS, "tx-1");

        assertThat(result.getElapsedMs()).isGreaterThanOrEqualTo(0);
    }
}