package com.levango7.dataenginebdp.infra.privatecloud.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PrivateClusterInfo 模型测试。
 *
 * @author shuqing-bigdata
 */
class PrivateClusterInfoTest {

    @Test
    @DisplayName("构建集群信息 — 字段正确赋值")
    void build_shouldSetAllFields() {
        PrivateClusterInfo.VMInfo vm = PrivateClusterInfo.VMInfo.builder()
                .vmId("vm-1234")
                .name("ws-demo-cp-1")
                .role("control-plane")
                .powerState("POWERED_ON")
                .ipAddress("192.168.1.10")
                .build();

        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L)
                .clusterName("ws-demo-cluster")
                .provider("vsphere")
                .tenantId("tenant-001")
                .status("RUNNING")
                .k8sVersion("v1.30.0")
                .controlPlaneCount(1)
                .workerCount(2)
                .vmJson("[]")
                .build();

        assertEquals(1L, cluster.getId());
        assertEquals("ws-demo-cluster", cluster.getClusterName());
        assertEquals("vsphere", cluster.getProvider());
        assertEquals("RUNNING", cluster.getStatus());
        assertEquals(1, cluster.getControlPlaneCount());
        assertEquals(2, cluster.getWorkerCount());

        assertEquals("vm-1234", vm.getVmId());
        assertEquals("POWERED_ON", vm.getPowerState());
        assertEquals("192.168.1.10", vm.getIpAddress());
    }

    @Test
    @DisplayName("VMInfo 默认构造 — 字段为 null")
    void vmInfo_defaultConstructor_fieldsNull() {
        PrivateClusterInfo.VMInfo vm = new PrivateClusterInfo.VMInfo();
        assertNull(vm.getVmId());
        assertNull(vm.getName());
        assertNull(vm.getRole());
    }
}