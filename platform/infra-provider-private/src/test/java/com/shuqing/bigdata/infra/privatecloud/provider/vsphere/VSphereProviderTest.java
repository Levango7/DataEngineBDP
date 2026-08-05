package com.shuqing.bigdata.infra.privatecloud.provider.vsphere;

import com.shuqing.bigdata.infra.privatecloud.config.PrivateCloudProperties;
import com.shuqing.bigdata.infra.privatecloud.model.PrivateClusterInfo;
import com.shuqing.bigdata.infra.privatecloud.model.PrivateClusterRequest;
import com.shuqing.bigdata.infra.privatecloud.model.VMSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * VSphereProvider 测试。
 *
 * <p>使用 Mockito mock {@link VSphereClient}，避免真实 vCenter 调用。</p>
 *
 * @author shuqing-bigdata
 */
class VSphereProviderTest {

    private VSphereClient client;
    private VSphereProvider provider;

    @BeforeEach
    void setUp() {
        client = mock(VSphereClient.class);
        PrivateCloudProperties.VSphere config = new PrivateCloudProperties.VSphere();
        config.setTemplateVm("ubuntu-2204-k8s-template");
        when(client.getConfig()).thenReturn(config);

        provider = new VSphereProvider(client);
    }

    @Test
    @DisplayName("getType — 返回 vsphere")
    void getType_shouldReturnVsphere() {
        assertEquals("vsphere", provider.getType());
    }

    @Test
    @DisplayName("createVMs — 成功创建控制面 + 工作节点")
    void createVMs_shouldCreateControlPlaneAndWorkers() {
        when(client.cloneVm(anyString(), anyString()))
                .thenReturn("vm-1001")
                .thenReturn("vm-1002")
                .thenReturn("vm-1003");
        when(client.getVm(anyString())).thenReturn(
                "{\"value\":{\"power_state\":\"POWERED_ON\",\"ip_address\":\"192.168.1.10\"}}",
                "{\"value\":{\"power_state\":\"POWERED_ON\",\"ip_address\":\"192.168.1.11\"}}",
                "{\"value\":{\"power_state\":\"POWERED_ON\",\"ip_address\":\"192.168.1.12\"}}");

        PrivateClusterRequest request = new PrivateClusterRequest();
        request.setClusterName("ws-demo");
        VMSpec cp = new VMSpec();
        cp.setRole("control-plane");
        cp.setCpu(4);
        request.setControlPlane(cp);

        VMSpec worker = new VMSpec();
        worker.setRole("worker");
        worker.setCpu(2);
        request.setWorkers(List.of(worker, worker));

        List<PrivateClusterInfo.VMInfo> vms = provider.createVMs(request);

        assertEquals(3, vms.size());
        assertEquals("control-plane", vms.get(0).getRole());
        assertEquals("worker", vms.get(1).getRole());
        assertEquals("worker", vms.get(2).getRole());
        assertEquals("vm-1001", vms.get(0).getVmId());
        assertEquals("POWERED_ON", vms.get(0).getPowerState());
        assertEquals("192.168.1.10", vms.get(0).getIpAddress());

        verify(client, times(3)).cloneVm(anyString(), anyString());
        verify(client, times(3)).powerOn(anyString());
    }

    @Test
    @DisplayName("createVMs — 克隆失败时返回 null 但不抛异常")
    void createVMs_cloneFailure_shouldReturnNullVm() {
        when(client.cloneVm(anyString(), anyString()))
                .thenThrow(new RuntimeException("vCenter 不可达"));

        PrivateClusterRequest request = new PrivateClusterRequest();
        request.setClusterName("ws-demo");
        request.setControlPlane(new VMSpec());
        request.setWorkers(List.of(new VMSpec()));

        List<PrivateClusterInfo.VMInfo> vms = provider.createVMs(request);

        // 克隆失败，VM 信息为 null，列表中不含该 VM
        assertTrue(vms.isEmpty());
    }

    @Test
    @DisplayName("destroyVMs — 全部销毁成功返回 true")
    void destroyVMs_allSuccess_shouldReturnTrue() {
        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of(
                PrivateClusterInfo.VMInfo.builder().vmId("vm-1").name("n1").role("control-plane").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("vm-2").name("n2").role("worker").build()));

        boolean result = provider.destroyVMs(cluster);

        assertTrue(result);
        verify(client, times(2)).deleteVm(anyString());
    }

    @Test
    @DisplayName("destroyVMs — 部分销毁失败返回 false")
    void destroyVMs_partialFailure_shouldReturnFalse() {
        doThrow(new RuntimeException("删除失败"))
                .when(client).deleteVm("vm-2");

        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of(
                PrivateClusterInfo.VMInfo.builder().vmId("vm-1").name("n1").role("control-plane").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("vm-2").name("n2").role("worker").build()));

        boolean result = provider.destroyVMs(cluster);

        assertFalse(result);
    }

    @Test
    @DisplayName("destroyVMs — 空列表返回 true")
    void destroyVMs_emptyList_shouldReturnTrue() {
        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of());

        boolean result = provider.destroyVMs(cluster);

        assertTrue(result);
        verify(client, never()).deleteVm(anyString());
    }

    @Test
    @DisplayName("getVMInfo — 查询所有 VM 实时状态")
    void getVMInfo_shouldQueryAllVms() {
        when(client.getVm("vm-1")).thenReturn(
                "{\"value\":{\"power_state\":\"POWERED_ON\",\"ip_address\":\"10.0.0.1\"}}");
        when(client.getVm("vm-2")).thenReturn(
                "{\"value\":{\"power_state\":\"POWERED_OFF\",\"ip_address\":\"10.0.0.2\"}}");

        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of(
                PrivateClusterInfo.VMInfo.builder().vmId("vm-1").name("n1").role("control-plane").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("vm-2").name("n2").role("worker").build()));

        List<PrivateClusterInfo.VMInfo> result = provider.getVMInfo(cluster);

        assertEquals(2, result.size());
        assertEquals("POWERED_ON", result.get(0).getPowerState());
        assertEquals("10.0.0.1", result.get(0).getIpAddress());
        assertEquals("POWERED_OFF", result.get(1).getPowerState());
    }

    @Test
    @DisplayName("scaleVMs — 扩容工作节点")
    void scaleVMs_scaleUp_shouldAddWorkers() {
        when(client.cloneVm(anyString(), anyString()))
                .thenReturn("vm-3")
                .thenReturn("vm-4");
        when(client.getVm(anyString())).thenReturn(
                "{\"value\":{\"power_state\":\"POWERED_ON\",\"ip_address\":\"10.0.0.3\"}}",
                "{\"value\":{\"power_state\":\"POWERED_ON\",\"ip_address\":\"10.0.0.4\"}}");

        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of(
                PrivateClusterInfo.VMInfo.builder().vmId("vm-1").name("cp").role("control-plane").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("vm-2").name("w1").role("worker").build()));

        VMSpec workerSpec = new VMSpec();
        workerSpec.setRole("worker");
        List<PrivateClusterInfo.VMInfo> result = provider.scaleVMs(cluster, 3, workerSpec);

        long workers = result.stream().filter(v -> "worker".equals(v.getRole())).count();
        assertEquals(3, workers);
    }

    @Test
    @DisplayName("scaleVMs — 缩容工作节点")
    void scaleVMs_scaleDown_shouldRemoveWorkers() {
        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of(
                PrivateClusterInfo.VMInfo.builder().vmId("vm-1").name("cp").role("control-plane").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("vm-2").name("w1").role("worker").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("vm-3").name("w2").role("worker").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("vm-4").name("w3").role("worker").build()));

        List<PrivateClusterInfo.VMInfo> result = provider.scaleVMs(cluster, 1, null);

        long workers = result.stream().filter(v -> "worker".equals(v.getRole())).count();
        assertEquals(1, workers);
        verify(client, times(2)).deleteVm(anyString());
    }

    @Test
    @DisplayName("scaleVMs — 目标等于当前，无变更")
    void scaleVMs_sameCount_shouldNoop() {
        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of(
                PrivateClusterInfo.VMInfo.builder().vmId("vm-1").name("cp").role("control-plane").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("vm-2").name("w1").role("worker").build()));

        List<PrivateClusterInfo.VMInfo> result = provider.scaleVMs(cluster, 1, null);

        assertEquals(2, result.size());
        verify(client, never()).cloneVm(anyString(), anyString());
        verify(client, never()).deleteVm(anyString());
    }

    @Test
    @DisplayName("使用 imageRef 覆盖默认模板")
    void createVMs_shouldUseImageRefWhenProvided() {
        when(client.cloneVm(anyString(), eq("custom-template")))
                .thenReturn("vm-1001");
        when(client.getVm(anyString())).thenReturn(
                "{\"value\":{\"power_state\":\"POWERED_ON\"}}");

        PrivateClusterRequest request = new PrivateClusterRequest();
        request.setClusterName("ws-demo");
        VMSpec cp = new VMSpec();
        cp.setImageRef("custom-template");
        request.setControlPlane(cp);
        request.setWorkers(List.of());

        List<PrivateClusterInfo.VMInfo> vms = provider.createVMs(request);

        assertEquals(1, vms.size());
        verify(client).cloneVm(anyString(), eq("custom-template"));
    }

    @Test
    @org.junit.jupiter.api.Disabled("保留 Mockito 静态导入以备扩展")
    void placeholder() {
        Mockito.mockingDetails(client).isMock();
    }
}