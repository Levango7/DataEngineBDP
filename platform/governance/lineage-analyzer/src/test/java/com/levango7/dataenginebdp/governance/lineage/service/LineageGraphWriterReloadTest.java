package com.levango7.dataenginebdp.governance.lineage.service;

import com.levango7.dataenginebdp.governance.lineage.model.LineageEdge;
import com.levango7.dataenginebdp.governance.lineage.model.LineageGraph;
import com.levango7.dataenginebdp.governance.lineage.model.LineageNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LineageGraphWriter#reloadAdjacencyFromStore()} 重启回填测试。
 *
 * <p>复现的缺陷：查询面只读内存邻接表，而邻接表原先仅由 {@code write()} 填充。
 * prod 用 PostgreSQL 持久化边，进程重启后「库里有边、内存为空」，所有上下游查询
 * 静默返回空。本测试以「清空内存 + 从存储重建」等价模拟重启。</p>
 *
 * @author shuqing-bigdata
 */
@SpringBootTest
@DisplayName("血缘内存图重启回填测试")
class LineageGraphWriterReloadTest {

    @Autowired
    private LineageGraphWriter graphWriter;

    @Autowired
    private LineageNodeRepository nodeRepository;

    @Autowired
    private LineageEdgeRepository edgeRepository;

    @BeforeEach
    void setUp() {
        graphWriter.clear();
    }

    @AfterEach
    void tearDown() {
        graphWriter.clear();
    }

    @Test
    @DisplayName("重启后（内存清空）上下游查询仍应命中库里的边")
    void reloadRestoresAdjacencyFromStore() {
        LineageGraph graph = new LineageGraph("INSERT INTO dws.o SELECT * FROM ods.o", "hive", 0L);
        graph.addNode(new LineageNode("ods.o", LineageNode.NodeType.TABLE));
        graph.addNode(new LineageNode("dws.o", LineageNode.NodeType.TABLE));
        graph.addEdge(new LineageEdge("ods.o", "dws.o", LineageEdge.RelationType.TABLE_LINEAGE));
        graphWriter.write(graph);

        // 前置自证：写入后内存与查询面都命中
        assertEquals(Set.of("dws.o"), graphWriter.getDirectDownstream("ods.o"));

        // 真实重启模型：新进程 = 全新 writer 实例，内存邻接表天生为空，只有持久化存储有数据。
        // （直接对单例调 reload 会被 write() 已填充的内存掩盖，测不出回填是否真的来自存储。）
        LineageGraphWriter restarted = newRestartedWriter();
        assertEquals(0, restarted.getDownstreamMap().size(), "新实例内存图应为空");
        assertEquals(1, edgeRepository.count(), "边应已在持久化存储里");

        restarted.reloadAdjacencyFromStore();

        assertTrue(restarted.getDirectDownstream("ods.o").contains("dws.o"),
                "回填后 ods.o 的下游应含 dws.o（边来自存储，不是来自 write）");
        assertTrue(restarted.getDirectUpstream("dws.o").contains("ods.o"));
        // 回填后的邻接表应与"刚写完"的内存态完全一致，否则查询面行为会因重启而变
        assertEquals(graphWriter.getDownstreamMap(), restarted.getDownstreamMap());
        assertEquals(graphWriter.getUpstreamMap(), restarted.getUpstreamMap());
    }

    @Test
    @DisplayName("列级边不参与内存回填（与原 write 口径一致）")
    void reloadSkipsColumnLevelEdges() {
        LineageGraph graph = new LineageGraph("sql", "hive", 0L);
        graph.addNode(new LineageNode("a.x", LineageNode.NodeType.COLUMN));
        graph.addNode(new LineageNode("b.y", LineageNode.NodeType.COLUMN));
        graph.addEdge(new LineageEdge("a.x", "b.y", LineageEdge.RelationType.COLUMN_LINEAGE));
        graphWriter.write(graph);

        LineageGraphWriter restarted = newRestartedWriter();
        restarted.reloadAdjacencyFromStore();

        assertEquals(0, restarted.getDownstreamMap().size());
        assertEquals(2, restarted.getKnownTables().size(), "节点仍应作为已知表恢复");
    }

    /**
     * 构造一个"重启后的"写入器：共用同一套 Repository（=同一存储），内存图为空，
     * Nebula 客户端缺席（与 nebula.enabled=false 的默认部署一致）。
     *
     * @return 全新 LineageGraphWriter 实例
     */
    private LineageGraphWriter newRestartedWriter() {
        return new LineageGraphWriter(nodeRepository, edgeRepository,
                new ObjectProvider<NebulaGraphClient>() {
                    @Override
                    public NebulaGraphClient getIfAvailable() {
                        return null;
                    }
                });
    }
}
