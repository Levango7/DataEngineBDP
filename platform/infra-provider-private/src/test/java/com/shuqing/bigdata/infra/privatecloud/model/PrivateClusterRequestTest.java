package com.shuqing.bigdata.infra.privatecloud.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PrivateClusterRequest 模型测试。
 *
 * @author shuqing-bigdata
 */
class PrivateClusterRequestTest {

    @Test
    @DisplayName("构建集群请求 — 字段正确赋值")
    void build_shouldSetAllFields() {
        VMSpec cp = new VMSpec();
        cp.setRole("control-plane");
        cp.setCpu(4);
        cp.setMemoryMb(8192);

        VMSpec worker = new VMSpec();
        worker.setRole("worker");
        worker.setCpu(2);
        worker.setMemoryMb(4096);

        PrivateClusterRequest request = new PrivateClusterRequest();
        request.setClusterName("ws-demo-cluster");
        request.setControlPlane(cp);
        request.setWorkers(List.of(worker, worker));
        request.setK8sVersion("v1.30.0");
        request.setPodCidr("10.244.0.0/16");
        request.setServiceCidr("10.96.0.0/12");

        assertEquals("ws-demo-cluster", request.getClusterName());
        assertEquals("control-plane", request.getControlPlane().getRole());
        assertEquals(4, request.getControlPlane().getCpu());
        assertEquals(2, request.getWorkers().size());
        assertEquals("v1.30.0", request.getK8sVersion());
        assertEquals("10.244.0.0/16", request.getPodCidr());
        assertEquals("10.96.0.0/12", request.getServiceCidr());
    }

    @Test
    @DisplayName("VMSpec 默认构造 — 字段为 null")
    void vmSpec_defaultConstructor_fieldsNull() {
        VMSpec spec = new VMSpec();
        assertNull(spec.getRole());
        assertNull(spec.getCpu());
        assertNull(spec.getMemoryMb());
    }
}