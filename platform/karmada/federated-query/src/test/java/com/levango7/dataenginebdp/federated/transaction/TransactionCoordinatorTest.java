package com.levango7.dataenginebdp.federated.transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TransactionCoordinator} 单元测试。
 *
 * <p>覆盖事务状态机流转：ACTIVE → PREPARING → PREPARED → COMMITTING → COMMITTED
 * 及失败路径：→ ROLLING_BACK → ROLLED_BACK / FAILED。
 */
class TransactionCoordinatorTest {

    private ClusterTransactionClient client;
    private TwoPhaseCommitProtocol protocol;
    private IcebergSnapshotIsolation snapshotIsolation;
    private TransactionCoordinator coordinator;

    private static final Map<String, String> ENDPOINTS = Map.of(
            "cluster-a", "http://a:8090",
            "cluster-b", "http://b:8090");

    @BeforeEach
    void setUp() {
        client = mock(ClusterTransactionClient.class);
        protocol = new TwoPhaseCommitProtocol(client, 2000, 2000, 3, 50);
        snapshotIsolation = new IcebergSnapshotIsolation();
        coordinator = new TransactionCoordinator(protocol, snapshotIsolation);
    }

    @Test
    void shouldBeginTransactionWithActiveStatusAndSnapshots() {
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1", "db.t2"));

        TransactionLog tx = coordinator.getTransactionStatus(txId);
        assertThat(tx).isNotNull();
        assertThat(tx.getTxId()).isEqualTo(txId);
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.ACTIVE);
        assertThat(tx.getParticipants()).containsKeys("cluster-a", "cluster-b");
        assertThat(tx.getSnapshots()).hasSize(2);
        assertThat(tx.getSnapshots().get("db.t1")).isPositive();
        assertThat(tx.getSnapshots().get("db.t2")).isPositive();
        assertThat(tx.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldTransitionToPreparedWhenAllClustersPrepareSuccessfully() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));

        boolean ok = coordinator.prepare(txId);

        assertThat(ok).isTrue();
        TransactionLog tx = coordinator.getTransactionStatus(txId);
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.PREPARED);
        assertThat(tx.getPreparedClusters()).containsExactlyInAnyOrder("cluster-a", "cluster-b");
        assertThat(tx.getPreparingAt()).isNotNull();
        assertThat(tx.getPreparedAt()).isNotNull();
    }

    @Test
    void shouldTransitionToCommittedWhenAllClustersCommitSuccessfully() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        when(client.commit(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));
        coordinator.prepare(txId);

        boolean ok = coordinator.commit(txId);

        assertThat(ok).isTrue();
        TransactionLog tx = coordinator.getTransactionStatus(txId);
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.COMMITTED);
        assertThat(tx.getCommittedClusters()).containsExactlyInAnyOrder("cluster-a", "cluster-b");
        assertThat(tx.getCommittingAt()).isNotNull();
        assertThat(tx.getCommittedAt()).isNotNull();
    }

    @Test
    void shouldAutoRollbackWhenPrepareFails() {
        when(client.prepare(eq("cluster-a"), eq("http://a:8090"), anyString())).thenReturn(true);
        when(client.prepare(eq("cluster-b"), eq("http://b:8090"), anyString())).thenReturn(false);
        when(client.rollback(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));

        boolean ok = coordinator.prepare(txId);

        assertThat(ok).isFalse();
        TransactionLog tx = coordinator.getTransactionStatus(txId);
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.ROLLED_BACK);
        assertThat(tx.getRolledBackClusters()).contains("cluster-a");
    }

    @Test
    void shouldMarkFailedWhenCommitFailsAfterAllRetries() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        when(client.commit(eq("cluster-a"), eq("http://a:8090"), anyString())).thenReturn(true);
        when(client.commit(eq("cluster-b"), eq("http://b:8090"), anyString())).thenReturn(false);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));
        coordinator.prepare(txId);

        boolean ok = coordinator.commit(txId);

        assertThat(ok).isFalse();
        TransactionLog tx = coordinator.getTransactionStatus(txId);
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.FAILED);
        assertThat(tx.getFailureReason()).contains("commit failed");
    }

    @Test
    void shouldRollbackActiveTransaction() {
        when(client.rollback(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));

        boolean ok = coordinator.rollback(txId);

        assertThat(ok).isTrue();
        TransactionLog tx = coordinator.getTransactionStatus(txId);
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.ROLLED_BACK);
    }

    @Test
    void shouldNotRollbackAlreadyTerminalTransaction() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        when(client.commit(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of());
        coordinator.prepare(txId);
        coordinator.commit(txId);

        boolean ok = coordinator.rollback(txId);

        assertThat(ok).isFalse();
    }

    @Test
    void shouldNotPrepareNonExistentTransaction() {
        boolean ok = coordinator.prepare("nonexistent-tx");

        assertThat(ok).isFalse();
    }

    @Test
    void shouldNotCommitWhenStatusNotPrepared() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of());

        boolean ok = coordinator.commit(txId);

        assertThat(ok).isFalse();
        TransactionLog tx = coordinator.getTransactionStatus(txId);
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.ACTIVE);
    }

    @Test
    void shouldListAllTransactions() {
        String txId1 = coordinator.begin(ENDPOINTS, List.of());
        String txId2 = coordinator.begin(ENDPOINTS, List.of());

        assertThat(coordinator.listTransactions()).hasSize(2);
        assertThat(coordinator.listTransactions())
                .extracting(TransactionLog::getTxId)
                .containsExactlyInAnyOrder(txId1, txId2);
    }

    @Test
    void shouldReturnRecoverableTransactionsForIntermediateStates() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        when(client.commit(eq("cluster-a"), eq("http://a:8090"), anyString())).thenReturn(true);
        when(client.commit(eq("cluster-b"), eq("http://b:8090"), anyString())).thenReturn(false);
        String txId = coordinator.begin(ENDPOINTS, List.of());
        coordinator.prepare(txId);
        coordinator.commit(txId);

        TransactionLog tx = coordinator.getTransactionStatus(txId);
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.FAILED);

        String txId2 = coordinator.begin(ENDPOINTS, List.of());
        coordinator.prepare(txId2);
        TransactionLog tx2 = coordinator.getTransactionStatus(txId2);
        assertThat(tx2.getStatus()).isEqualTo(TransactionLog.Status.PREPARED);

        assertThat(coordinator.getRecoverableTransactions())
                .extracting(TransactionLog::getTxId)
                .contains(txId2);
    }
}