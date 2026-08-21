package com.levango7.dataenginebdp.governance.lineage.service;

import com.levango7.dataenginebdp.governance.lineage.model.LineageEdge;
import com.levango7.dataenginebdp.governance.lineage.model.LineageNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NebulaGraphClient 真实容器集成测试（#14 完整验证）。
 *
 * <p>连接本地 NebulaGraph 容器（9669）验证真实 nGQL 写入：
 * CREATE SPACE/TAG/EDGE → INSERT VERTEX/EDGE 幂等 → FETCH/GO 查询验证。
 * 通过 {@code -Dnebula.it=true} 启用（容器运行时跑）；默认跳过。</p>
 *
 * <p>运行：{@code mvn test -Dtest=NebulaGraphClientIT -Dnebula.it=true}</p>
 *
 * <p>测试维度：
 * <ul>
 *   <li>连接真实容器并完成 Schema 初始化</li>
 *   <li>顶点 INSERT VERTEX 写入与幂等</li>
 *   <li>边 INSERT EDGE 写入与幂等</li>
 *   <li>字段级血缘节点 + 边写入</li>
 *   <li>查询验证（FETCH PROP ON / GO 1 STEPS）</li>
 *   <li>降级场景：不可达端口 → available=false，写入安全返回 false</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "nebula.it", matches = "true")
class NebulaGraphClientIT {

    /** 集成测试专用 space，与生产 space 隔离 */
    private static final String IT_SPACE = "it_lineage";
    /** 真实容器地址 */
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 9669;
    private static final String USERNAME = "root";
    private static final String PASSWORD = "nebula";

    private NebulaGraphClient client;

    @BeforeAll
    void setUp() {
        client = new NebulaGraphClient(HOST, PORT, USERNAME, PASSWORD, IT_SPACE);
    }

    @Test
    @Order(1)
    @DisplayName("连接真实 NebulaGraph 容器并完成 space/schema 初始化")
    void connect_realContainer_isAvailable() {
        assertThat(client.isAvailable())
                .as("应连上本地 NebulaGraph 容器（9669），space=%s", IT_SPACE)
                .isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("写入表级血缘节点（INSERT VERTEX）应成功")
    void writeNode_tableLevel_realContainerSucceeds() {
        LineageNode source = new LineageNode("ods.orders", LineageNode.NodeType.TABLE);
        source.setSchemaName("ods");
        source.setTableName("orders");
        source.setDisplayName("订单源表");

        LineageNode target = new LineageNode("dws.order_daily", LineageNode.NodeType.TABLE);
        target.setSchemaName("dws");
        target.setTableName("order_daily");
        target.setDisplayName("订单日汇总表");

        assertThat(client.writeNode(source))
                .as("源表节点写入真实 NebulaGraph 应成功").isTrue();
        assertThat(client.writeNode(target))
                .as("目标表节点写入真实 NebulaGraph 应成功").isTrue();
    }

    @Test
    @Order(11)
    @DisplayName("重复写入相同节点应幂等成功（INSERT VERTEX 覆盖语义）")
    void writeNode_idempotent_repeatWriteSucceeds() {
        LineageNode node = new LineageNode("ods.orders", LineageNode.NodeType.TABLE);
        node.setSchemaName("ods");
        node.setTableName("orders");

        boolean first = client.writeNode(node);
        boolean second = client.writeNode(node);
        assertThat(first).as("首次写入应成功").isTrue();
        assertThat(second).as("二次写入（幂等）应仍成功").isTrue();
    }

    @Test
    @Order(20)
    @DisplayName("写入表级血缘边（INSERT EDGE）应成功")
    void writeEdge_tableLevel_realContainerSucceeds() {
        LineageEdge edge = new LineageEdge(
                "ods.orders", "dws.order_daily", LineageEdge.RelationType.TABLE_LINEAGE);
        edge.setSourceSql("INSERT INTO dws.order_daily SELECT * FROM ods.orders");
        edge.setDialect("hive");
        edge.setExpression("direct");

        assertThat(client.writeEdge(edge))
                .as("表级边写入真实 NebulaGraph 应成功").isTrue();
    }

    @Test
    @Order(21)
    @DisplayName("重复写入相同边应幂等成功（INSERT EDGE 覆盖语义）")
    void writeEdge_idempotent_repeatWriteSucceeds() {
        LineageEdge edge = new LineageEdge(
                "ods.orders", "dws.order_daily", LineageEdge.RelationType.TABLE_LINEAGE);

        boolean first = client.writeEdge(edge);
        boolean second = client.writeEdge(edge);
        assertThat(first).as("首次写边应成功").isTrue();
        assertThat(second).as("二次写边（幂等）应仍成功").isTrue();
    }

    @Test
    @Order(30)
    @DisplayName("写入字段级血缘节点 + 边应成功")
    void writeColumnLevelLineage_realContainerSucceeds() {
        // 字段节点
        LineageNode srcCol = new LineageNode("ods.orders.order_id", LineageNode.NodeType.COLUMN);
        srcCol.setSchemaName("ods");
        srcCol.setTableName("orders");
        srcCol.setColumnName("order_id");

        LineageNode tgtCol = new LineageNode("dws.order_daily.gmv", LineageNode.NodeType.COLUMN);
        tgtCol.setSchemaName("dws");
        tgtCol.setTableName("order_daily");
        tgtCol.setColumnName("gmv");

        assertThat(client.writeNode(srcCol)).as("源字段节点写入应成功").isTrue();
        assertThat(client.writeNode(tgtCol)).as("目标字段节点写入应成功").isTrue();

        // 字段血缘边
        LineageEdge colEdge = new LineageEdge(
                "ods.orders.order_id", "dws.order_daily.gmv",
                LineageEdge.RelationType.COLUMN_LINEAGE);
        colEdge.setSourceSql("INSERT INTO dws.order_daily SELECT SUM(amount) AS gmv FROM ods.orders");
        colEdge.setDialect("hive");
        colEdge.setExpression("SUM(amount)");

        assertThat(client.writeEdge(colEdge)).as("字段级边写入应成功").isTrue();
    }

    @Test
    @Order(40)
    @DisplayName("通过 execute 直接执行 nGQL 查询应返回成功")
    void execute_directNgql_querySucceeds() {
        // FETCH PROP ON 查询刚写入的顶点
        boolean fetchOk = client.execute(
                "FETCH PROP ON lineage_node \"ods.orders\" YIELD vertex;");
        assertThat(fetchOk).as("FETCH PROP ON 顶点查询应成功").isTrue();

        // GO 1 STEPS 查询边
        boolean goOk = client.execute(
                "GO 1 STEPS FROM \"ods.orders\" OVER lineage_edge YIELD edge;");
        assertThat(goOk).as("GO 1 STEPS 边查询应成功").isTrue();
    }

    @Test
    @Order(50)
    @DisplayName("nGQL 字符串转义：含双引号与反斜杠的字段名应正确写入")
    void writeNode_specialChars_escapedCorrectly() {
        LineageNode node = new LineageNode(
                "ods.orders.\"weird\\col\"", LineageNode.NodeType.COLUMN);
        node.setSchemaName("ods");
        node.setTableName("orders");
        node.setColumnName("\"weird\\col\"");

        assertThat(client.writeNode(node))
                .as("含特殊字符的节点应正确转义后写入成功").isTrue();
    }

    @Test
    @Order(60)
    @DisplayName("降级场景：不可达端口构造的客户端 available=false 且写入安全返回 false")
    void fallback_unreachableHost_degradesSafely() {
        NebulaGraphClient unreachable = new NebulaGraphClient(
                "127.0.0.1", 1, USERNAME, PASSWORD, "lineage");
        assertThat(unreachable.isAvailable())
                .as("不可达端口应降级为 available=false").isFalse();

        LineageNode node = new LineageNode("ods.orders", LineageNode.NodeType.TABLE);
        assertThat(unreachable.writeNode(node))
                .as("不可用客户端 writeNode 应安全返回 false").isFalse();

        LineageEdge edge = new LineageEdge(
                "ods.orders", "dws.order_daily", LineageEdge.RelationType.TABLE_LINEAGE);
        assertThat(unreachable.writeEdge(edge))
                .as("不可用客户端 writeEdge 应安全返回 false").isFalse();
    }

    @Test
    @Order(70)
    @DisplayName("空值保护：null 节点/缺端点边应返回 false 不抛异常")
    void write_nullInputs_returnsFalseSafely() {
        assertThat(client.writeNode(null)).isFalse();

        LineageEdge noTarget = new LineageEdge(
                "ods.orders", null, LineageEdge.RelationType.TABLE_LINEAGE);
        assertThat(client.writeEdge(noTarget)).isFalse();

        LineageEdge noSource = new LineageEdge(
                null, "dws.order_daily", LineageEdge.RelationType.TABLE_LINEAGE);
        assertThat(client.writeEdge(noSource)).isFalse();
    }
}
