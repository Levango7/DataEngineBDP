package com.shuqing.bigdata.infra.xinchang;

import org.junit.jupiter.api.Test;

import com.shuqing.bigdata.infra.xinchang.model.ClusterCreateRequest;
import com.shuqing.bigdata.infra.xinchang.model.ClusterInfo;
import com.shuqing.bigdata.infra.xinchang.model.XinchangNodeSpec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link XinchangProviderApplication} 启动类与模型基础单元测试。
 *
 * <p>不启动 Spring 上下文，仅校验模型构造与枚举默认值，确保编译产物可被引用。</p>
 */
class XinchangProviderApplicationTest {

    @Test
    void mainClassShouldExist() {
        // 仅校验类可加载，不真正运行 main（避免启动 Spring）
        assertNotNull(XinchangProviderApplication.class);
    }

    @Test
    void clusterCreateRequestDefaultsShouldBeApplied() {
        ClusterCreateRequest request = ClusterCreateRequest.builder()
                .clusterName("ws-demo-cluster")
                .tenantId("tenant-001")
                .nodes(List.of())
                .build();
        assertEquals("v1.28.9", request.getK8sVersion(), "默认 K8s 版本应为 v1.28.9（与 SKE v0.1 对齐）");
        assertEquals("10.244.0.0/16", request.getPodCidr());
        assertEquals("10.96.0.0/12", request.getServiceCidr());
        assertTrue(request.isSkeEnabled(), "SKE 定制默认开启");
    }

    @Test
    void xinchangNodeSpecDefaultsShouldBeKunpengAndKylin() {
        XinchangNodeSpec node = XinchangNodeSpec.builder()
                .role("control-plane")
                .bmcIp("192.168.200.100")
                .pxeMac("aa:bb:cc:dd:ee:ff")
                .hostname("node-1")
                .build();
        assertEquals(XinchangNodeSpec.CpuArch.KUNPENG, node.getCpuArch(), "默认 CPU 架构应为鲲鹏 920");
        assertEquals(XinchangNodeSpec.OsType.KYLIN_V10, node.getOsType(), "默认 OS 应为麒麟 V10");
    }

    @Test
    void clusterInfoStatusEnumShouldContainAllPhases() {
        // 校验状态机覆盖创建/运行/扩缩容/销毁/失败全生命周期
        assertNotNull(ClusterInfo.Status.CREATING);
        assertNotNull(ClusterInfo.Status.RUNNING);
        assertNotNull(ClusterInfo.Status.SCALING);
        assertNotNull(ClusterInfo.Status.DESTROYING);
        assertNotNull(ClusterInfo.Status.DESTROYED);
        assertNotNull(ClusterInfo.Status.FAILED);
        assertEquals(6, ClusterInfo.Status.values().length);
    }
}