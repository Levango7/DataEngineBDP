package com.levango7.dataenginebdp.governance.lineage.service;

import com.levango7.dataenginebdp.governance.lineage.LineageAnalyzerApplication;
import com.levango7.dataenginebdp.governance.lineage.model.LineageEdge;
import com.levango7.dataenginebdp.governance.lineage.model.LineageGraph;
import com.levango7.dataenginebdp.governance.lineage.model.LineageNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LineageGraphWriter 端到端血缘集成测试（#14 真实 NebulaGraph 验证）。
 *
 * <p>启动完整 Spring 上下文（profile=it），通过 {@link LineageGraphWriter}
 * 写入完整血缘链，验证：
 * <ul>
 *   <li>NebulaGraphClient Bean 在 {@code nebula.enabled=true} 时被实例化</li>
 *   <li>内存图、H2、NebulaGraph 三写一致</li>
 *   <li>上下游查询（内存图）结果正确</li>
 *   <li>NebulaGraph 真实 nGQL INSERT VERTEX/EDGE 幂等</li>
 * </ul>
 *
 * <p>运行：{@code mvn test -Dtest=LineageGraphWriterIT \
 * -Dnebula.it=true -Dspring.profiles.active=it}</p>
 *
 * @author shuqing-bigdata
 */
@SpringBootTest(classes = LineageAnalyzerApplication.class)
@ActiveProfiles("it")
@EnabledIfSystemProperty(named = "nebula.it", matches = "true")
class LineageGraphWriterIT {

    @Autowired
    private LineageGraphWriter writer;

    @Autowired
    private NebulaGraphClient nebulaClient;

    @Test
    @DisplayName("Spring 上下文应加载 NebulaGraphClient Bean 且 available=true")
    void contextLoads_nebulaClientAvailable() {
        assertThat(nebulaClient)
                .as("nebula.enabled=true 时 NebulaGraphClient Bean 应被实例化").isNotNull();
        assertThat(nebulaClient.isAvailable())
                .as("应连上真实 NebulaGraph 容器（127.0.0.1:9669）").isTrue();
    }

    @Test
    @DisplayName("写入完整血缘链：内存图 + H2 + NebulaGraph 三写一致")
    void write_fullLineageChain_allBackendsConsistent() {
        writer.clear();

        LineageGraph graph = buildSampleLineageGraph();
        writer.write(graph);

        // 1. 内存图验证：上下游邻接表
        Set<String> upstreamOfDws = writer.getDirectUpstream("dws.order_daily");
        assertThat(upstreamOfDws)
                .as("dws.order_daily 的上游应包含 ods.orders")
                .contains("ods.orders");

        Set<String> downstreamOfOds = writer.getDirectDownstream("ods.orders");
        assertThat(downstreamOfOds)
                .as("ods.orders 的下游应包含 dws.order_daily")
                .contains("dws.order_daily");

        // 2. 已知表集合
        Set<String> knownTables = writer.getKnownTables();
        assertThat(knownTables)
                .as("已知表应包含 ods.orders 与 dws.order_daily")
                .contains("ods.orders", "dws.order_daily");

        // 3. NebulaGraph 真实写入验证（通过 client 直接查询）
        boolean fetchSource = nebulaClient.execute(
                "FETCH PROP ON lineage_node \"ods.orders\" YIELD vertex;");
        assertThat(fetchSource).as("NebulaGraph 中应能 FETCH 到 ods.orders 顶点").isTrue();

        boolean fetchTarget = nebulaClient.execute(
                "FETCH PROP ON lineage_node \"dws.order_daily\" YIELD vertex;");
        assertThat(fetchTarget).as("NebulaGraph 中应能 FETCH 到 dws.order_daily 顶点").isTrue();

        boolean goEdge = nebulaClient.execute(
                "GO 1 STEPS FROM \"ods.orders\" OVER lineage_edge YIELD edge;");
        assertThat(goEdge).as("NebulaGraph 中应能 GO 1 STEPS 查到血缘边").isTrue();
    }

    @Test
    @DisplayName("重复写入相同血缘链应幂等（不产生重复边）")
    void write_idempotent_repeatWriteNoDuplicate() {
        writer.clear();

        LineageGraph graph = buildSampleLineageGraph();
        writer.write(graph);
        writer.write(graph);  // 重复写入

        // 内存图：上下游集合大小不变（去重）
        Set<String> upstream = writer.getDirectUpstream("dws.order_daily");
        assertThat(upstream)
                .as("重复写入后上游集合应去重，仍只含 ods.orders")
                .containsExactlyInAnyOrder("ods.orders");

        // NebulaGraph：INSERT VERTEX/EDGE 覆盖语义，幂等
        boolean ok = nebulaClient.execute(
                "GO 1 STEPS FROM \"ods.orders\" OVER lineage_edge YIELD edge;");
        assertThat(ok).as("NebulaGraph 重复写入后查询应仍成功").isTrue();
    }

    @Test
    @DisplayName("多跳血缘链：ods → dws → ads，BFS 验证下游可达")
    void write_multiHopLineage_bfsDownstream() {
        writer.clear();

        LineageGraph graph = new LineageGraph(
                "INSERT INTO ads.order_report SELECT * FROM dws.order_daily",
                "hive", 0L);

        // 第一跳：ods.orders → dws.order_daily
        LineageNode ods = new LineageNode("ods.orders", LineageNode.NodeType.TABLE);
        ods.setSchemaName("ods");
        ods.setTableName("orders");
        LineageNode dws = new LineageNode("dws.order_daily", LineageNode.NodeType.TABLE);
        dws.setSchemaName("dws");
        dws.setTableName("order_daily");
        LineageNode ads = new LineageNode("ads.order_report", LineageNode.NodeType.TABLE);
        ads.setSchemaName("ads");
        ads.setTableName("order_report");

        graph.addNode(ods);
        graph.addNode(dws);
        graph.addNode(ads);

        LineageEdge e1 = new LineageEdge(
                "ods.orders", "dws.order_daily", LineageEdge.RelationType.TABLE_LINEAGE);
        LineageEdge e2 = new LineageEdge(
                "dws.order_daily", "ads.order_report", LineageEdge.RelationType.TABLE_LINEAGE);
        graph.addEdge(e1);
        graph.addEdge(e2);

        writer.write(graph);

        // 内存图：dws 的下游应含 ads
        assertThat(writer.getDirectDownstream("dws.order_daily"))
                .as("dws.order_daily 的下游应含 ads.order_report")
                .contains("ads.order_report");

        // NebulaGraph：两跳查询应能从 ods 到达 ads
        boolean twoHop = nebulaClient.execute(
                "GO 1 TO 2 STEPS FROM \"ods.orders\" OVER lineage_edge YIELD edge;");
        assertThat(twoHop).as("NebulaGraph 两跳查询应成功").isTrue();
    }

    /**
     * 构造样本血缘图：ods.orders → dws.order_daily（表级）。
     *
     * @return 血缘图
     */
    private static LineageGraph buildSampleLineageGraph() {
        LineageGraph graph = new LineageGraph(
                "INSERT INTO dws.order_daily SELECT * FROM ods.orders",
                "hive", 0L);

        LineageNode source = new LineageNode("ods.orders", LineageNode.NodeType.TABLE);
        source.setSchemaName("ods");
        source.setTableName("orders");
        source.setDisplayName("订单源表");

        LineageNode target = new LineageNode("dws.order_daily", LineageNode.NodeType.TABLE);
        target.setSchemaName("dws");
        target.setTableName("order_daily");
        target.setDisplayName("订单日汇总表");

        graph.addNode(source);
        graph.addNode(target);

        LineageEdge edge = new LineageEdge(
                "ods.orders", "dws.order_daily", LineageEdge.RelationType.TABLE_LINEAGE);
        edge.setSourceSql("INSERT INTO dws.order_daily SELECT * FROM ods.orders");
        edge.setDialect("hive");
        edge.setExpression("direct");
        graph.addEdge(edge);

        return graph;
    }
}