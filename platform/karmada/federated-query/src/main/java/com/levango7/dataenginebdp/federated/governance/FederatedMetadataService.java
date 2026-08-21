package com.levango7.dataenginebdp.federated.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨集群元数据统一治理服务。
 *
 * <p>职责：
 * <ul>
 *   <li>聚合多集群的表/数据库/列元数据</li>
 *   <li>提供跨集群表列表（标注来源集群）与表详情（合并多集群信息）</li>
 *   <li>同步指定集群元数据（{@link #syncMetadata(String)}）</li>
 *   <li>元数据冲突检测（同名表不同 schema）</li>
 *   <li>元数据版本管理</li>
 * </ul>
 *
 * <p>存储：内存 {@link ConcurrentHashMap}，生产环境可替换为持久化实现。
 * 集群间数据获取通过 {@link ClusterMetadataProvider} 接口抽象，便于 Mock。
 *
 * <p>验收标准：
 * <ul>
 *   <li>跨集群元数据聚合覆盖 ≥ 2 集群</li>
 *   <li>同名表 schema 不一致时能检测出冲突</li>
 *   <li>元数据同步幂等，重复同步不产生重复记录</li>
 * </ul>
 */
@Slf4j
@Service
public class FederatedMetadataService {

    /** tableId → 表元数据。 */
    private final ConcurrentHashMap<String, FederatedGovernanceView.TableMetadata> tableMetadataStore = new ConcurrentHashMap<>();

    /** clusterId → 该集群的表 ID 列表。 */
    private final ConcurrentHashMap<String, List<String>> clusterTablesIndex = new ConcurrentHashMap<>();

    /** clusterId → 当前元数据版本号。 */
    private final ConcurrentHashMap<String, AtomicLong> clusterVersionStore = new ConcurrentHashMap<>();

    /** 全局元数据版本号。 */
    private final AtomicLong globalVersion = new AtomicLong(0);

    private final ClusterMetadataProvider metadataProvider;

    public FederatedMetadataService(ClusterMetadataProvider metadataProvider) {
        this.metadataProvider = metadataProvider;
    }

    /**
     * 获取跨集群表列表（标注来源集群）。
     *
     * @return 表元数据列表
     */
    public List<FederatedGovernanceView.TableMetadata> getFederatedTables() {
        return new ArrayList<>(tableMetadataStore.values());
    }

    /**
     * 获取跨集群表列表，按集群过滤。
     *
     * @param clusterId 集群 ID（为 null/空则返回全部）
     * @return 表元数据列表
     */
    public List<FederatedGovernanceView.TableMetadata> getFederatedTables(String clusterId) {
        if (clusterId == null || clusterId.isEmpty()) {
            return getFederatedTables();
        }
        List<String> tableIds = clusterTablesIndex.getOrDefault(clusterId, Collections.emptyList());
        List<FederatedGovernanceView.TableMetadata> result = new ArrayList<>();
        for (String id : tableIds) {
            FederatedGovernanceView.TableMetadata meta = tableMetadataStore.get(id);
            if (meta != null) {
                result.add(meta);
            }
        }
        return result;
    }

    /**
     * 获取表详情（合并多集群信息）。
     *
     * @param tableId 表 ID
     * @return 表元数据，不存在返回 null
     */
    public FederatedGovernanceView.TableMetadata getFederatedTable(String tableId) {
        return tableMetadataStore.get(tableId);
    }

    /**
     * 同步指定集群元数据。
     *
     * <p>从 {@link ClusterMetadataProvider} 拉取集群最新元数据并更新本地存储。
     * 同步是幂等的：相同元数据重复同步不会产生重复记录，仅更新版本号。
     *
     * @param clusterId 集群 ID
     * @return 同步结果
     */
    public FederatedGovernanceView.SyncResult syncMetadata(String clusterId) {
        long start = System.currentTimeMillis();
        try {
            List<FederatedGovernanceView.TableMetadata> tables = metadataProvider.fetchTableMetadata(clusterId);
            if (tables == null) {
                tables = Collections.emptyList();
            }

            // 移除该集群旧的、不再存在的表元数据
            List<String> oldTableIds = clusterTablesIndex.getOrDefault(clusterId, Collections.emptyList());
            java.util.Set<String> newTableIds = new java.util.HashSet<>();
            for (FederatedGovernanceView.TableMetadata t : tables) {
                newTableIds.add(t.getTableId());
            }
            for (String oldId : oldTableIds) {
                if (!newTableIds.contains(oldId)) {
                    tableMetadataStore.remove(oldId);
                }
            }

            // 写入新元数据
            long version = clusterVersionStore
                    .computeIfAbsent(clusterId, k -> new AtomicLong(0))
                    .incrementAndGet();
            Instant now = Instant.now();
            List<String> tableIdList = new ArrayList<>();
            for (FederatedGovernanceView.TableMetadata t : tables) {
                t.setVersion(version);
                t.setSyncedAt(now);
                tableMetadataStore.put(t.getTableId(), t);
                tableIdList.add(t.getTableId());
            }
            clusterTablesIndex.put(clusterId, tableIdList);
            globalVersion.incrementAndGet();

            long elapsed = System.currentTimeMillis() - start;
            log.info("Metadata synced: cluster={} tables={} version={} elapsedMs={}",
                    clusterId, tables.size(), version, elapsed);
            return FederatedGovernanceView.SyncResult.builder()
                    .clusterId(clusterId)
                    .success(true)
                    .syncedTables(tables.size())
                    .elapsedMs(elapsed)
                    .syncedAt(now)
                    .build();
        } catch (Exception e) {
            log.error("Metadata sync failed: cluster={} err={}", clusterId, e.getMessage(), e);
            return FederatedGovernanceView.SyncResult.builder()
                    .clusterId(clusterId)
                    .success(false)
                    .syncedTables(0)
                    .error(e.getMessage())
                    .elapsedMs(System.currentTimeMillis() - start)
                    .syncedAt(Instant.now())
                    .build();
        }
    }

    /**
     * 检测元数据冲突（同名表在不同集群 schema 不一致）。
     *
     * @return 冲突列表
     */
    public List<FederatedGovernanceView.MetadataConflict> detectConflicts() {
        // fullName → (clusterId → TableMetadata)
        Map<String, Map<String, FederatedGovernanceView.TableMetadata>> byFullName = new LinkedHashMap<>();
        for (FederatedGovernanceView.TableMetadata t : tableMetadataStore.values()) {
            byFullName.computeIfAbsent(t.getFullName(), k -> new LinkedHashMap<>())
                    .put(t.getClusterId(), t);
        }

        List<FederatedGovernanceView.MetadataConflict> conflicts = new ArrayList<>();
        Instant now = Instant.now();
        for (Map.Entry<String, Map<String, FederatedGovernanceView.TableMetadata>> entry : byFullName.entrySet()) {
            Map<String, FederatedGovernanceView.TableMetadata> clusterTables = entry.getValue();
            if (clusterTables.size() < 2) {
                continue;
            }
            // 检查 schema 是否一致
            String conflictType = analyzeConflict(clusterTables);
            if (conflictType != null) {
                conflicts.add(FederatedGovernanceView.MetadataConflict.builder()
                        .fullName(entry.getKey())
                        .clusterTables(clusterTables)
                        .conflictType(conflictType)
                        .description("Table " + entry.getKey() + " has " + conflictType
                                + " across " + clusterTables.size() + " clusters")
                        .detectedAt(now)
                        .build());
            }
        }
        return conflicts;
    }

    /**
     * 构建元数据视图。
     *
     * @return 元数据视图
     */
    public FederatedGovernanceView.MetadataView buildMetadataView() {
        List<FederatedGovernanceView.TableMetadata> tables = getFederatedTables();
        List<String> clusters = new ArrayList<>(clusterTablesIndex.keySet());
        List<FederatedGovernanceView.MetadataConflict> conflicts = detectConflicts();
        return FederatedGovernanceView.MetadataView.builder()
                .tables(tables)
                .clusters(clusters)
                .totalTables(tables.size())
                .conflicts(conflicts)
                .generatedAt(Instant.now())
                .build();
    }

    /**
     * 获取全局元数据版本号。
     */
    public long getGlobalVersion() {
        return globalVersion.get();
    }

    /**
     * 获取指定集群的元数据版本号。
     */
    public long getClusterVersion(String clusterId) {
        AtomicLong v = clusterVersionStore.get(clusterId);
        return v == null ? 0 : v.get();
    }

    /**
     * 获取已知集群列表。
     */
    public List<String> getKnownClusters() {
        return new ArrayList<>(clusterTablesIndex.keySet());
    }

    /**
     * 直接注册表元数据（用于测试或手动导入）。
     */
    public void registerTableMetadata(FederatedGovernanceView.TableMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(metadata.getTableId(), "tableId must not be null");
        Objects.requireNonNull(metadata.getClusterId(), "clusterId must not be null");
        long version = clusterVersionStore
                .computeIfAbsent(metadata.getClusterId(), k -> new AtomicLong(0))
                .incrementAndGet();
        metadata.setVersion(version);
        metadata.setSyncedAt(Instant.now());
        tableMetadataStore.put(metadata.getTableId(), metadata);
        clusterTablesIndex
                .computeIfAbsent(metadata.getClusterId(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(metadata.getTableId());
        globalVersion.incrementAndGet();
    }

    /**
     * 清空所有元数据（用于测试）。
     */
    public void clear() {
        tableMetadataStore.clear();
        clusterTablesIndex.clear();
        clusterVersionStore.clear();
        globalVersion.set(0);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 分析同名表是否冲突，返回冲突类型；无冲突返回 null。
     */
    private String analyzeConflict(Map<String, FederatedGovernanceView.TableMetadata> clusterTables) {
        List<FederatedGovernanceView.TableMetadata> list = new ArrayList<>(clusterTables.values());
        FederatedGovernanceView.TableMetadata reference = list.get(0);

        boolean columnMismatch = false;
        boolean typeMismatch = false;

        for (int i = 1; i < list.size(); i++) {
            FederatedGovernanceView.TableMetadata other = list.get(i);
            // 比较列名集合
            List<String> refCols = columnNames(reference);
            List<String> otherCols = columnNames(other);
            if (!refCols.equals(otherCols)) {
                columnMismatch = true;
            }
            // 比较列类型
            if (!columnTypesEqual(reference, other)) {
                typeMismatch = true;
            }
        }

        if (typeMismatch) {
            return "TYPE_MISMATCH";
        }
        if (columnMismatch) {
            return "COLUMN_MISMATCH";
        }
        return null;
    }

    private List<String> columnNames(FederatedGovernanceView.TableMetadata meta) {
        List<String> names = new ArrayList<>();
        if (meta.getColumns() == null) {
            return names;
        }
        for (FederatedGovernanceView.ColumnMetadata c : meta.getColumns()) {
            names.add(c.getName());
        }
        return names;
    }

    private boolean columnTypesEqual(FederatedGovernanceView.TableMetadata a, FederatedGovernanceView.TableMetadata b) {
        List<FederatedGovernanceView.ColumnMetadata> colsA = a.getColumns();
        List<FederatedGovernanceView.ColumnMetadata> colsB = b.getColumns();
        if (colsA == null && colsB == null) {
            return true;
        }
        if (colsA == null || colsB == null) {
            return false;
        }
        if (colsA.size() != colsB.size()) {
            return false;
        }
        Map<String, String> typeA = new LinkedHashMap<>();
        for (FederatedGovernanceView.ColumnMetadata c : colsA) {
            typeA.put(c.getName(), c.getType());
        }
        for (FederatedGovernanceView.ColumnMetadata c : colsB) {
            String refType = typeA.get(c.getName());
            if (refType == null || !refType.equals(c.getType())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 集群元数据提供者接口（抽象集群间元数据获取，便于 Mock）。
     */
    public interface ClusterMetadataProvider {
        /**
         * 拉取指定集群的表元数据列表。
         *
         * @param clusterId 集群 ID
         * @return 表元数据列表
         */
        List<FederatedGovernanceView.TableMetadata> fetchTableMetadata(String clusterId);
    }
}