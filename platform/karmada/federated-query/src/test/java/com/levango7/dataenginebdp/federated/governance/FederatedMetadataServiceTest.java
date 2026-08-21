package com.levango7.dataenginebdp.federated.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FederatedMetadataService} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>元数据聚合（多集群）</li>
 *   <li>冲突检测（同名表 schema 不一致）</li>
 *   <li>同步幂等性</li>
 *   <li>版本管理</li>
 *   <li>边界条件（空集群/不存在的表）</li>
 * </ul>
 */
class FederatedMetadataServiceTest {

    private FederatedMetadataService.ClusterMetadataProvider provider;
    private FederatedMetadataService service;

    @BeforeEach
    void setUp() {
        provider = mock(FederatedMetadataService.ClusterMetadataProvider.class);
        service = new FederatedMetadataService(provider);
    }

    @Test
    void getFederatedTables_shouldAggregateMultipleClusters() {
        when(provider.fetchTableMetadata("cluster-a"))
                .thenReturn(List.of(buildTable("cluster-a:db.orders", "db", "orders", "cluster-a")));
        when(provider.fetchTableMetadata("cluster-b"))
                .thenReturn(List.of(buildTable("cluster-b:db.customers", "db", "customers", "cluster-b")));

        service.syncMetadata("cluster-a");
        service.syncMetadata("cluster-b");

        List<FederatedGovernanceView.TableMetadata> tables = service.getFederatedTables();
        assertThat(tables).hasSize(2);
        assertThat(tables).extracting(FederatedGovernanceView.TableMetadata::getClusterId)
                .containsExactlyInAnyOrder("cluster-a", "cluster-b");
    }

    @Test
    void getFederatedTable_shouldReturnTableById() {
        FederatedGovernanceView.TableMetadata table = buildTable(
                "cluster-a:db.orders", "db", "orders", "cluster-a");
        when(provider.fetchTableMetadata("cluster-a")).thenReturn(List.of(table));

        service.syncMetadata("cluster-a");

        FederatedGovernanceView.TableMetadata result = service.getFederatedTable("cluster-a:db.orders");
        assertThat(result).isNotNull();
        assertThat(result.getTable()).isEqualTo("orders");
        assertThat(result.getClusterId()).isEqualTo("cluster-a");
    }

    @Test
    void getFederatedTable_shouldReturnNullForNonExistent() {
        assertThat(service.getFederatedTable("non-existent")).isNull();
    }

    @Test
    void detectConflicts_shouldDetectSchemaMismatch() {
        // 集群 A 和集群 B 同名表 db.orders，但列不同
        FederatedGovernanceView.TableMetadata tableA = FederatedGovernanceView.TableMetadata.builder()
                .tableId("cluster-a:db.orders")
                .database("db")
                .table("orders")
                .fullName("db.orders")
                .clusterId("cluster-a")
                .columns(List.of(
                        FederatedGovernanceView.ColumnMetadata.builder().name("id").type("INT").ordinal(0).build(),
                        FederatedGovernanceView.ColumnMetadata.builder().name("amount").type("DOUBLE").ordinal(1).build()))
                .build();
        FederatedGovernanceView.TableMetadata tableB = FederatedGovernanceView.TableMetadata.builder()
                .tableId("cluster-b:db.orders")
                .database("db")
                .table("orders")
                .fullName("db.orders")
                .clusterId("cluster-b")
                .columns(List.of(
                        FederatedGovernanceView.ColumnMetadata.builder().name("id").type("INT").ordinal(0).build(),
                        FederatedGovernanceView.ColumnMetadata.builder().name("amount").type("STRING").ordinal(1).build()))
                .build();

        service.registerTableMetadata(tableA);
        service.registerTableMetadata(tableB);

        List<FederatedGovernanceView.MetadataConflict> conflicts = service.detectConflicts();
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getFullName()).isEqualTo("db.orders");
        assertThat(conflicts.get(0).getConflictType()).isEqualTo("TYPE_MISMATCH");
    }

    @Test
    void detectConflicts_shouldReturnEmptyWhenNoConflict() {
        FederatedGovernanceView.TableMetadata tableA = buildTable(
                "cluster-a:db.orders", "db", "orders", "cluster-a");
        FederatedGovernanceView.TableMetadata tableB = buildTable(
                "cluster-b:db.orders", "db", "orders", "cluster-b");
        // 两个表 schema 相同（buildTable 默认列相同）
        service.registerTableMetadata(tableA);
        service.registerTableMetadata(tableB);

        List<FederatedGovernanceView.MetadataConflict> conflicts = service.detectConflicts();
        assertThat(conflicts).isEmpty();
    }

    @Test
    void syncMetadata_shouldBeIdempotent() {
        FederatedGovernanceView.TableMetadata table = buildTable(
                "cluster-a:db.orders", "db", "orders", "cluster-a");
        when(provider.fetchTableMetadata("cluster-a")).thenReturn(List.of(table));

        FederatedGovernanceView.SyncResult r1 = service.syncMetadata("cluster-a");
        FederatedGovernanceView.SyncResult r2 = service.syncMetadata("cluster-a");

        assertThat(r1.isSuccess()).isTrue();
        assertThat(r2.isSuccess()).isTrue();
        assertThat(r1.getSyncedTables()).isEqualTo(1);
        assertThat(r2.getSyncedTables()).isEqualTo(1);
        // 两次同步后表数量仍为 1（幂等）
        assertThat(service.getFederatedTables()).hasSize(1);
    }

    @Test
    void syncMetadata_shouldHandleEmptyCluster() {
        when(provider.fetchTableMetadata("empty-cluster")).thenReturn(Collections.emptyList());

        FederatedGovernanceView.SyncResult result = service.syncMetadata("empty-cluster");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSyncedTables()).isEqualTo(0);
        assertThat(service.getFederatedTables()).isEmpty();
    }

    @Test
    void syncMetadata_shouldHandleProviderException() {
        when(provider.fetchTableMetadata(anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        FederatedGovernanceView.SyncResult result = service.syncMetadata("bad-cluster");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("connection refused");
    }

    @Test
    void versionManagement_shouldIncrementOnSync() {
        when(provider.fetchTableMetadata("cluster-a"))
                .thenReturn(List.of(buildTable("cluster-a:db.orders", "db", "orders", "cluster-a")));

        assertThat(service.getClusterVersion("cluster-a")).isEqualTo(0);
        service.syncMetadata("cluster-a");
        assertThat(service.getClusterVersion("cluster-a")).isEqualTo(1);
        service.syncMetadata("cluster-a");
        assertThat(service.getClusterVersion("cluster-a")).isEqualTo(2);
        assertThat(service.getGlobalVersion()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void getFederatedTablesByCluster_shouldFilterByCluster() {
        when(provider.fetchTableMetadata("cluster-a"))
                .thenReturn(List.of(buildTable("cluster-a:db.t1", "db", "t1", "cluster-a")));
        when(provider.fetchTableMetadata("cluster-b"))
                .thenReturn(List.of(buildTable("cluster-b:db.t2", "db", "t2", "cluster-b")));

        service.syncMetadata("cluster-a");
        service.syncMetadata("cluster-b");

        assertThat(service.getFederatedTables("cluster-a")).hasSize(1);
        assertThat(service.getFederatedTables("cluster-b")).hasSize(1);
        assertThat(service.getFederatedTables("cluster-a").get(0).getClusterId()).isEqualTo("cluster-a");
    }

    private FederatedGovernanceView.TableMetadata buildTable(String tableId, String database,
                                                              String table, String clusterId) {
        return FederatedGovernanceView.TableMetadata.builder()
                .tableId(tableId)
                .database(database)
                .table(table)
                .fullName(database + "." + table)
                .clusterId(clusterId)
                .tableType("MANAGED")
                .columns(new ArrayList<>(Arrays.asList(
                        FederatedGovernanceView.ColumnMetadata.builder().name("id").type("INT").ordinal(0).build(),
                        FederatedGovernanceView.ColumnMetadata.builder().name("name").type("STRING").ordinal(1).build())))
                .build();
    }
}