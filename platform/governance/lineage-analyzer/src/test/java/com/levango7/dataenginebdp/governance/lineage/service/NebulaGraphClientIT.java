package com.levango7.dataenginebdp.governance.lineage.service;

import com.levango7.dataenginebdp.governance.lineage.model.LineageEdge;
import com.levango7.dataenginebdp.governance.lineage.model.LineageNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NebulaGraphClient 真实容器集成测试（#14 完整验证）。
 *
 * <p>连接本地 NebulaGraph 容器（9669）验证真实 nGQL 写入：
 * CREATE SPACE/TAG/EDGE → INSERT VERTEX/EDGE 幂等。
 * 通过 {@code -Dnebula.it=true} 启用（容器运行时跑）；默认跳过。</p>
 *
 * <p>运行：{@code mvn test -Dtest=NebulaGraphClientIT -Dnebula.it=true}</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "nebula.it", matches = "true")
class NebulaGraphClientIT {

    private NebulaGraphClient client;

    @BeforeAll
    void setUp() {
        client = new NebulaGraphClient("127.0.0.1", 9669, "root", "nebula", "it_lineage");
    }

    @Test
    void connect_realContainer_isAvailable() {
        assertThat(client.isAvailable())
                .as("应连上本地 NebulaGraph 容器（9669）").isTrue();
    }

    @Test
    void writeNode_writeEdge_realContainerSucceeds() {
        LineageNode source = new LineageNode();
        source.setFullName("ods.orders.order_id");
        source.setTableName("orders");

        LineageNode target = new LineageNode();
        target.setFullName("dws.order_daily.gmv");
        target.setTableName("order_daily");

        LineageEdge edge = new LineageEdge();
        edge.setSourceFullName("ods.orders.order_id");
        edge.setTargetFullName("dws.order_daily.gmv");

        boolean nodeOk = client.writeNode(source) && client.writeNode(target);
        boolean edgeOk = client.writeEdge(edge);

        assertThat(nodeOk).as("节点写入真实 NebulaGraph 应成功").isTrue();
        assertThat(edgeOk).as("边写入真实 NebulaGraph 应成功").isTrue();

        // 幂等：再次写入应仍成功（IF NOT EXISTS 语义）
        assertThat(client.writeEdge(edge)).isTrue();
    }
}
