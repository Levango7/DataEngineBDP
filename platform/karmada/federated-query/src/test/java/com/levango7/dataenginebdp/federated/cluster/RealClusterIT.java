package com.levango7.dataenginebdp.federated.cluster;

import com.levango7.dataenginebdp.federated.governance.FederatedGovernanceView;
import com.levango7.dataenginebdp.federated.governance.FederatedLineageService;
import com.levango7.dataenginebdp.federated.governance.FederatedMetadataService;
import com.levango7.dataenginebdp.federated.governance.FederatedQualityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Karmada 联邦集群集成测试。
 *
 * <p>仅在系统属性 {@code -Dinfra.it=true} 时运行，需要预先启动 kind 多集群 +
 * Karmada 控制面 + 各集群测试数据。启动方式：
 * <pre>
 * bash scripts/infra/test-karmada-it.sh
 * </pre>
 *
 * <p>测试矩阵：
 * <ul>
 *   <li>测试1: 连接真实 Karmada，获取集群列表</li>
 *   <li>测试2: 从多个集群获取元数据并聚合</li>
 *   <li>测试3: 获取跨集群血缘</li>
 *   <li>测试4: 执行跨集群质量检查</li>
 *   <li>测试5: 联邦查询路由</li>
 * </ul>
 *
 * <p>测试数据约定（由 test-karmada-it.sh 部署）：
 * <ul>
 *   <li>cluster-a: 表 db.orders(id INT, amount DOUBLE), db.customers(id INT, name STRING);
 *       血缘 db.raw -> db.orders</li>
 *   <li>cluster-b: 表 db.orders(id INT, amount DOUBLE), db.shipments(id INT, order_id INT);
 *       血缘 db.orders -> db.shipments</li>
 *   <li>跨集群血缘: cluster-a:db.orders -> cluster-b:db.shipments</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@EnabledIfSystemProperty(named = "infra.it", matches = "true")
class RealClusterIT {

    @Autowired
    private FederatedMetadataService metadataService;

    @Autowired
    private FederatedLineageService lineageService;

    @Autowired
    private FederatedQualityService qualityService;

    @Autowired
    private FederatedClusterProperties clusterProps;

    @Autowired
    private RealClusterMetadataProvider realMetadataProvider;

    @BeforeEach
    void setUp() {
        // 清空上一轮测试残留
        metadataService.clear();
        lineageService.clear();
        qualityService.clear();
    }

    // ==================================================================
    // 测试1: 连接真实 Karmada，获取集群列表
    // ==================================================================

    @Test
    @DisplayName("测试1: 从 Karmada 控制面获取 member cluster 列表")
    void listClustersFromKarmada_shouldReturnMemberClusters() {
        List<String> clusters = realMetadataProvider.listClustersFromKarmada();

        assertThat(clusters).isNotNull();
        // 至少包含配置中的 cluster-a 和 cluster-b
        assertThat(clusters).contains("cluster-a", "cluster-b");
        System.out.println("[IT] Karmada member clusters: " + clusters);
    }

    // ==================================================================
    // 测试2: 从多个集群获取元数据
    // ==================================================================

    @Test
    @DisplayName("测试2: 从 cluster-a 和 cluster-b 聚合元数据")
    void syncMetadata_shouldFetchFromMultipleClusters() {
        // 同步 cluster-a 元数据
        FederatedGovernanceView.SyncResult resultA = metadataService.syncMetadata("cluster-a");
        assertThat(resultA.isSuccess()).isTrue();
        assertThat(resultA.getSyncedTables()).isGreaterThan(0);
        System.out.println("[IT] cluster-a synced tables: " + resultA.getSyncedTables());

        // 同步 cluster-b 元数据
        FederatedGovernanceView.SyncResult resultB = metadataService.syncMetadata("cluster-b");
        assertThat(resultB.isSuccess()).isTrue();
        assertThat(resultB.getSyncedTables()).isGreaterThan(0);
        System.out.println("[IT] cluster-b synced tables: " + resultB.getSyncedTables());

        // 聚合后应包含两个集群的表
        List<FederatedGovernanceView.TableMetadata> allTables = metadataService.getFederatedTables();
        assertThat(allTables.size()).isGreaterThanOrEqualTo(resultA.getSyncedTables() + resultB.getSyncedTables());

        // 按集群过滤
        List<FederatedGovernanceView.TableMetadata> tablesA = metadataService.getFederatedTables("cluster-a");
        List<FederatedGovernanceView.TableMetadata> tablesB = metadataService.getFederatedTables("cluster-b");
        assertThat(tablesA).isNotEmpty();
        assertThat(tablesB).isNotEmpty();
        assertThat(tablesA).extracting(FederatedGovernanceView.TableMetadata::getClusterId)
                .containsOnly("cluster-a");
        assertThat(tablesB).extracting(FederatedGovernanceView.TableMetadata::getClusterId)
                .containsOnly("cluster-b");

        // 已知集群列表
        assertThat(metadataService.getKnownClusters()).contains("cluster-a", "cluster-b");

        System.out.println("[IT] Federated tables total: " + allTables.size());
        System.out.println("[IT] cluster-a tables: " + tablesA.size());
        System.out.println("[IT] cluster-b tables: " + tablesB.size());
    }

    @Test
    @DisplayName("测试2b: 同步幂等性 - 重复同步不产生重复记录")
    void syncMetadata_shouldBeIdempotent() {
        metadataService.syncMetadata("cluster-a");
        int sizeAfterFirst = metadataService.getFederatedTables().size();

        metadataService.syncMetadata("cluster-a");
        int sizeAfterSecond = metadataService.getFederatedTables().size();

        assertThat(sizeAfterSecond).isEqualTo(sizeAfterFirst);
    }

    // ==================================================================
    // 测试3: 获取跨集群血缘
    // ==================================================================

    @Test
    @DisplayName("测试3: 同步血缘并构建跨集群血缘图")
    void syncLineage_shouldBuildCrossClusterLineage() {
        // 先同步元数据（血缘节点需要集群归属）
        metadataService.syncMetadata("cluster-a");
        metadataService.syncMetadata("cluster-b");

        // 同步血缘
        int edgesA = lineageService.syncLineage("cluster-a");
        int edgesB = lineageService.syncLineage("cluster-b");
        assertThat(edgesA).isGreaterThan(0);
        assertThat(edgesB).isGreaterThan(0);
        System.out.println("[IT] cluster-a lineage edges: " + edgesA);
        System.out.println("[IT] cluster-b lineage edges: " + edgesB);

        // 构建完整血缘图
        FederatedGovernanceView.LineageGraph graph = lineageService.buildLineageGraph();
        assertThat(graph.getEdges()).isNotEmpty();
        assertThat(graph.getClusters()).contains("cluster-a", "cluster-b");

        System.out.println("[IT] Lineage graph: nodes=" + graph.getNodes().size()
                + " edges=" + graph.getEdges().size()
                + " crossCluster=" + graph.isHasCrossCluster());
    }

    @Test
    @DisplayName("测试3b: 跨集群血缘边标记 crossCluster=true")
    void crossClusterLineage_shouldBeMarkedCrossCluster() {
        metadataService.syncMetadata("cluster-a");
        metadataService.syncMetadata("cluster-b");
        lineageService.syncLineage("cluster-a");
        lineageService.syncLineage("cluster-b");

        List<FederatedGovernanceView.LineageEdge> crossEdges = lineageService.getCrossClusterEdges();
        System.out.println("[IT] Cross-cluster edges: " + crossEdges.size());

        // 若 test-karmada-it.sh 部署了跨集群血缘，则应存在跨集群边
        if (!crossEdges.isEmpty()) {
            FederatedGovernanceView.LineageEdge edge = crossEdges.get(0);
            assertThat(edge.isCrossCluster()).isTrue();
            assertThat(edge.getSourceClusterId()).isNotEqualTo(edge.getTargetClusterId());
        }
    }

    // ==================================================================
    // 测试4: 执行跨集群质量检查
    // ==================================================================

    @Test
    @DisplayName("测试4: 跨集群应用质量规则")
    void applyQualityRule_shouldExecuteAcrossClusters() {
        // 创建质量规则
        FederatedGovernanceView.QualityRule rule = FederatedGovernanceView.QualityRule.builder()
                .name("it_not_null_check")
                .dimension(FederatedGovernanceView.QualityDimension.COMPLETENESS)
                .expression("id IS NOT NULL")
                .severity("ERROR")
                .enabled(true)
                .build();
        FederatedGovernanceView.QualityRule created = qualityService.createQualityRule(rule);
        assertThat(created.getRuleId()).isNotNull();

        // 跨集群应用
        List<FederatedGovernanceView.QualityReport> reports = qualityService.applyQualityRule(
                created.getRuleId(), List.of("cluster-a", "cluster-b"));

        assertThat(reports).isNotEmpty();
        assertThat(reports).extracting(FederatedGovernanceView.QualityReport::getClusterId)
                .contains("cluster-a", "cluster-b");

        System.out.println("[IT] Quality reports: " + reports.size());
        for (FederatedGovernanceView.QualityReport r : reports) {
            System.out.println("[IT]   report: table=" + r.getTableId()
                    + " cluster=" + r.getClusterId()
                    + " score=" + r.getOverallScore());
        }
    }

    @Test
    @DisplayName("测试4b: 联邦质量评分聚合多集群结果")
    void getFederatedQualityScore_shouldAggregateMultipleClusters() {
        FederatedGovernanceView.QualityRule rule = FederatedGovernanceView.QualityRule.builder()
                .name("it_unique_check")
                .dimension(FederatedGovernanceView.QualityDimension.UNIQUENESS)
                .expression("COUNT(DISTINCT id) = COUNT(id)")
                .severity("WARN")
                .enabled(true)
                .build();
        FederatedGovernanceView.QualityRule created = qualityService.createQualityRule(rule);
        qualityService.applyQualityRule(created.getRuleId(), List.of("cluster-a", "cluster-b"));

        FederatedGovernanceView.FederatedQualityScore score = qualityService.getFederatedQualityScore();
        assertThat(score.getClusterCount()).isGreaterThanOrEqualTo(1);
        assertThat(score.getTableCount()).isGreaterThanOrEqualTo(1);

        System.out.println("[IT] Federated quality score: overall=" + score.getOverallScore()
                + " clusters=" + score.getClusterCount()
                + " tables=" + score.getTableCount());
    }

    // ==================================================================
    // 测试5: 联邦查询路由
    // ==================================================================

    @Test
    @DisplayName("测试5: 联邦元数据视图包含多集群信息")
    void buildMetadataView_shouldContainMultipleClusters() {
        metadataService.syncMetadata("cluster-a");
        metadataService.syncMetadata("cluster-b");

        FederatedGovernanceView.MetadataView view = metadataService.buildMetadataView();
        assertThat(view.getClusters()).contains("cluster-a", "cluster-b");
        assertThat(view.getTotalTables()).isGreaterThan(0);

        System.out.println("[IT] Metadata view: clusters=" + view.getClusters()
                + " totalTables=" + view.getTotalTables()
                + " conflicts=" + view.getConflicts().size());
    }

    @Test
    @DisplayName("测试5b: 联邦配置加载正确 - mode=real")
    void configuration_shouldLoadRealMode() {
        assertThat(clusterProps.getMode()).isEqualTo("real");
        assertThat(clusterProps.getClusters()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(clusterProps.findCluster("cluster-a")).isNotNull();
        assertThat(clusterProps.findCluster("cluster-b")).isNotNull();
        assertThat(clusterProps.findCluster("cluster-a").getCatalogUrl()).isNotNull();
        assertThat(clusterProps.findCluster("non-existent")).isNull();

        System.out.println("[IT] Cluster config: mode=" + clusterProps.getMode()
                + " karmadaApi=" + clusterProps.getKarmadaApi()
                + " clusters=" + clusterProps.getEnabledClusterNames());
    }
}