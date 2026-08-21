package com.levango7.dataenginebdp.federated.transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IcebergSnapshotIsolation} 单元测试。
 */
class IcebergSnapshotIsolationTest {

    private IcebergSnapshotIsolation snapshotIsolation;

    @BeforeEach
    void setUp() {
        snapshotIsolation = new IcebergSnapshotIsolation();
    }

    @Test
    void shouldCreateDistinctSnapshotForDifferentTransactions() {
        long s1 = snapshotIsolation.createSnapshot("tx-1", "db.t1");
        long s2 = snapshotIsolation.createSnapshot("tx-2", "db.t1");

        assertThat(s1).isPositive();
        assertThat(s2).isPositive();
        assertThat(s1).isNotEqualTo(s2);
        assertThat(snapshotIsolation.getSnapshot("tx-1", "db.t1")).isEqualTo(s1);
        assertThat(snapshotIsolation.getSnapshot("tx-2", "db.t1")).isEqualTo(s2);
    }

    @Test
    void shouldIncrementRefCountOnCreate() {
        long s1 = snapshotIsolation.createSnapshot("tx-1", "db.t1");
        long s2 = snapshotIsolation.createSnapshot("tx-2", "db.t1");

        assertThat(snapshotIsolation.getRefCount("db.t1", s1)).isEqualTo(1);
        assertThat(snapshotIsolation.getRefCount("db.t1", s2)).isEqualTo(1);
    }

    @Test
    void shouldCommitSnapshotAndAdvanceCurrentSnapshot() {
        long initial = snapshotIsolation.getCurrentSnapshot("db.t1");
        long snapshot = snapshotIsolation.createSnapshot("tx-1", "db.t1");

        boolean committed = snapshotIsolation.commitSnapshot("tx-1", "db.t1", snapshot);

        assertThat(committed).isTrue();
        assertThat(snapshotIsolation.getCurrentSnapshot("db.t1")).isEqualTo(snapshot);
        assertThat(snapshotIsolation.getCurrentSnapshot("db.t1")).isNotEqualTo(initial);
    }

    @Test
    void shouldRollbackSnapshotAndRestorePrevious() {
        long initial = snapshotIsolation.getCurrentSnapshot("db.t1");
        long snapshot = snapshotIsolation.createSnapshot("tx-1", "db.t1");

        boolean rolledBack = snapshotIsolation.rollbackSnapshot("tx-1", "db.t1");

        assertThat(rolledBack).isTrue();
        assertThat(snapshotIsolation.getCurrentSnapshot("db.t1")).isEqualTo(initial);
    }

    @Test
    void shouldNotRollbackAfterCommit() {
        long snapshot = snapshotIsolation.createSnapshot("tx-1", "db.t1");
        snapshotIsolation.commitSnapshot("tx-1", "db.t1", snapshot);

        boolean rolledBack = snapshotIsolation.rollbackSnapshot("tx-1", "db.t1");

        assertThat(rolledBack).isFalse();
        assertThat(snapshotIsolation.getCurrentSnapshot("db.t1")).isEqualTo(snapshot);
    }

    @Test
    void shouldRejectCommitWithMismatchedSnapshotId() {
        snapshotIsolation.createSnapshot("tx-1", "db.t1");

        boolean committed = snapshotIsolation.commitSnapshot("tx-1", "db.t1", 99999L);

        assertThat(committed).isFalse();
    }

    @Test
    void shouldReleaseAllUncommittedSnapshotsOnRollback() {
        long initialT1 = snapshotIsolation.getCurrentSnapshot("db.t1");
        long initialT2 = snapshotIsolation.getCurrentSnapshot("db.t2");
        snapshotIsolation.createSnapshot("tx-1", "db.t1");
        snapshotIsolation.createSnapshot("tx-1", "db.t2");

        snapshotIsolation.releaseAll("tx-1");

        assertThat(snapshotIsolation.getCurrentSnapshot("db.t1")).isEqualTo(initialT1);
        assertThat(snapshotIsolation.getCurrentSnapshot("db.t2")).isEqualTo(initialT2);
    }

    @Test
    void shouldKeepCommittedSnapshotAfterReleaseAll() {
        long s1 = snapshotIsolation.createSnapshot("tx-1", "db.t1");
        snapshotIsolation.commitSnapshot("tx-1", "db.t1", s1);
        snapshotIsolation.createSnapshot("tx-1", "db.t2");

        snapshotIsolation.releaseAll("tx-1");

        assertThat(snapshotIsolation.getCurrentSnapshot("db.t1")).isEqualTo(s1);
    }

    @Test
    void shouldCleanupAllTrackingAfterTerminal() {
        long snapshot = snapshotIsolation.createSnapshot("tx-1", "db.t1");
        snapshotIsolation.commitSnapshot("tx-1", "db.t1", snapshot);

        snapshotIsolation.cleanup("tx-1");

        assertThat(snapshotIsolation.getSnapshot("tx-1", "db.t1")).isEqualTo(-1L);
    }
}