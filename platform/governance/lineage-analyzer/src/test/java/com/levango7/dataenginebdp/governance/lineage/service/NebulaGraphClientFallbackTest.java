package com.levango7.dataenginebdp.governance.lineage.service;

import com.levango7.dataenginebdp.governance.lineage.model.LineageEdge;
import com.levango7.dataenginebdp.governance.lineage.model.LineageNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NebulaGraphClient 降级安全测试（#14）。
 *
 * <p>NebulaGraph 不可达时初始化降级（available=false），
 * writeNode/writeEdge 应安全返回 false 而非抛异常。
 * 真实 nGQL 写入需 NebulaGraph 容器（网络受限时由 CI/生产验证）。</p>
 */
class NebulaGraphClientFallbackTest {

    /** 不可达地址构造 → 初始化降级 available=false。 */
    private NebulaGraphClient unreachableClient() {
        return new NebulaGraphClient("127.0.0.1", 1, "root", "nebula", "lineage");
    }

    @Test
    void init_unreachableHost_degradesToUnavailable() {
        NebulaGraphClient client = unreachableClient();
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void writeNode_whenUnavailable_returnsFalseSafely() {
        NebulaGraphClient client = unreachableClient();
        LineageNode node = new LineageNode();
        node.setFullName("ods.orders.id");
        node.setTableName("orders");
        assertThat(client.writeNode(node)).isFalse();
    }

    @Test
    void writeNode_nullNode_returnsFalse() {
        NebulaGraphClient client = unreachableClient();
        assertThat(client.writeNode(null)).isFalse();
    }

    @Test
    void writeEdge_whenUnavailable_returnsFalseSafely() {
        NebulaGraphClient client = unreachableClient();
        LineageEdge edge = new LineageEdge();
        edge.setSourceFullName("ods.orders.id");
        edge.setTargetFullName("dws.order_daily.gmv");
        assertThat(client.writeEdge(edge)).isFalse();
    }

    @Test
    void writeEdge_missingEndpoint_returnsFalse() {
        NebulaGraphClient client = unreachableClient();
        LineageEdge edge = new LineageEdge();
        edge.setSourceFullName("ods.orders.id"); // 缺 target
        assertThat(client.writeEdge(edge)).isFalse();
    }
}
