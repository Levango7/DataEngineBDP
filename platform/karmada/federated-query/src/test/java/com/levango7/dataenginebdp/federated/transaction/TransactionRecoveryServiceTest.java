package com.levango7.dataenginebdp.federated.transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TransactionRecoveryService} 单元测试。
 *
 * <p>覆盖 PREPARED/PREPARING/COMMITTING 三种恢复场景。
 */
class TransactionRecoveryServiceTest {

    private ClusterTransactionClient client;
    private TwoPhaseCommitProtocol protocol;
    private IcebergSnapshotIsolation snapshotIsolation;
    private TransactionCoordinator coordinator;
    private TransactionRecoveryService recoveryService;

    private static final Map<String, String> ENDPOINTS = Map.of(
            "cluster-a", "http://a:8090",
            "cluster-b", "http://b:8090");

    @BeforeEach
    void setUp() {
        client = mock(ClusterTransactionClient.class);
        protocol = new TwoPhaseCommitProtocol(client, 2000, 2000, 3, 50);
        snapshotIsolation = new IcebergSnapshotIsolation();
        coordinator = new TransactionCoordinator(protocol, snapshotIsolation);
        recoveryService = new TransactionRecoveryService(coordinator, protocol, 1000, 5);
    }

    @Test
    void shouldRecoverPreparedTransactionByCommitting() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        when(client.commit(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));
        coordinator.prepare(txId);
        TransactionLog tx = coordinator.getTransactionStatus(txId);
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.PREPARED);

        boolean recovered = recoveryService.recover(tx);

        assertThat(recovered).isTrue();
        TransactionLog after = coordinator.getTransactionStatus(txId);
        assertThat(after.getStatus()).isEqualTo(TransactionLog.Status.COMMITTED);
    }

    @Test
    void shouldRollbackPreparingTransactionWhenTimedOut() {
        when(client.rollback(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));
        TransactionLog tx = coordinator.getTransactionStatus(txId);
        tx.setStatus(TransactionLog.Status.PREPARING);
        tx.setPreparingAt(Instant.now().minus(10, ChronoUnit.SECONDS));

        boolean recovered = recoveryService.recover(tx);

        assertThat(recovered).isTrue();
        TransactionLog after = coordinator.getTransactionStatus(txId);
        assertThat(after.getStatus()).isEqualTo(TransactionLog.Status.ROLLED_BACK);
    }

    @Test
    void shouldNotRollbackPreparingTransactionWhenNotTimedOut() {
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));
        TransactionLog tx = coordinator.getTransactionStatus(txId);
        tx.setStatus(TransactionLog.Status.PREPARING);
        tx.setPreparingAt(Instant.now());

        boolean recovered = recoveryService.recover(tx);

        assertThat(recovered).isFalse();
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.PREPARING);
    }

    @Test
    void shouldRetryCommitForCommittingTransaction() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        when(client.commit(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));
        TransactionLog tx = coordinator.getTransactionStatus(txId);
        tx.setStatus(TransactionLog.Status.COMMITTING);
        tx.setPreparedClusters(List.of("cluster-a", "cluster-b"));
        tx.setParticipants(new java.util.HashMap<>(ENDPOINTS));

        boolean recovered = recoveryService.recover(tx);

        assertThat(recovered).isTrue();
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.COMMITTED);
        assertThat(tx.getRetryCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldMarkFailedWhenCommitRetryExhausted() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        when(client.commit(anyString(), anyString(), anyString())).thenReturn(false);
        TransactionRecoveryService strictRecovery = new TransactionRecoveryService(
                coordinator, protocol, 1000, 1);
        String txId = coordinator.begin(ENDPOINTS, List.of("db.t1"));
        TransactionLog tx = coordinator.getTransactionStatus(txId);
        tx.setStatus(TransactionLog.Status.COMMITTING);
        tx.setPreparedClusters(List.of("cluster-a", "cluster-b"));
        tx.setParticipants(new java.util.HashMap<>(ENDPOINTS));
        tx.setRetryCount(1);

        boolean recovered = strictRecovery.recover(tx);

        assertThat(recovered).isTrue();
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.FAILED);
        assertThat(tx.getFailureReason()).contains("retry exhausted");
    }

    @Test
    void shouldRecoverMultiplePendingTransactionsInOneScan() {
        when(client.prepare(anyString(), anyString(), anyString())).thenReturn(true);
        when(client.commit(anyString(), anyString(), anyString())).thenReturn(true);
        String txId1 = coordinator.begin(ENDPOINTS, List.of());
        coordinator.prepare(txId1);
        String txId2 = coordinator.begin(ENDPOINTS, List.of());
        coordinator.prepare(txId2);

        recoveryService.recoverPendingTransactions();

        assertThat(coordinator.getTransactionStatus(txId1).getStatus())
                .isEqualTo(TransactionLog.Status.COMMITTED);
        assertThat(coordinator.getTransactionStatus(txId2).getStatus())
                .isEqualTo(TransactionLog.Status.COMMITTED);
        assertThat(recoveryService.getLastRecoveredCount()).isEqualTo(2);
        assertThat(recoveryService.getRecoveryScanCount()).isEqualTo(1);
        assertThat(recoveryService.getLastScanAt()).isNotNull();
    }

    @Test
    void shouldHandleNoPendingTransactionsGracefully() {
        recoveryService.recoverPendingTransactions();

        assertThat(recoveryService.getLastRecoveredCount()).isEqualTo(0);
        assertThat(recoveryService.getRecoveryScanCount()).isEqualTo(1);
    }

    @Test
    void shouldForceRollbackWhenPreparingAtIsNull() {
        when(client.rollback(anyString(), anyString(), anyString())).thenReturn(true);
        String txId = coordinator.begin(ENDPOINTS, List.of());
        TransactionLog tx = coordinator.getTransactionStatus(txId);
        tx.setStatus(TransactionLog.Status.PREPARING);
        tx.setPreparingAt(null);

        boolean recovered = recoveryService.recover(tx);

        assertThat(recovered).isTrue();
        assertThat(tx.getStatus()).isEqualTo(TransactionLog.Status.ROLLED_BACK);
    }
}