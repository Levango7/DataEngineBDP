package com.levango7.dataenginebdp.infra.privatecloud.provider.openstack;

import com.levango7.dataenginebdp.infra.privatecloud.config.PrivateCloudProperties;
import com.levango7.dataenginebdp.infra.privatecloud.model.PrivateClusterInfo;
import com.levango7.dataenginebdp.infra.privatecloud.model.PrivateClusterRequest;
import com.levango7.dataenginebdp.infra.privatecloud.model.VMSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * OpenStackProvider 测试。
 *
 * <p>使用 Mockito mock {@link OpenStackClient}，避免真实 OpenStack 调用。</p>
 *
 * @author shuqing-bigdata
 */
class OpenStackProviderTest {

    private OpenStackClient client;
    private OpenStackProvider provider;

    @BeforeEach
    void setUp() {
        client = mock(OpenStackClient.class);
        PrivateCloudProperties.OpenStack config = new PrivateCloudProperties.OpenStack();
        config.setImageId("img-default");
        config.setFlavorId("flavor-default");
        config.setExternalNetwork("public");
        when(client.getConfig()).thenReturn(config);

        provider = new OpenStackProvider(client);
    }

    @Test
    @DisplayName("getType — 返回 openstack")
    void getType_shouldReturnOpenstack() {
        assertEquals("openstack", provider.getType());
    }

    @Test
    @DisplayName("createVMs — 成功创建控制面 + 工作节点")
    void createVMs_shouldCreateControlPlaneAndWorkers() {
        when(client.createServer(anyString(), anyString(), anyString()))
                .thenReturn("srv-1")
                .thenReturn("srv-2")
                .thenReturn("srv-3");
        when(client.getServer(anyString())).thenReturn(
                "{\"server\":{\"status\":\"ACTIVE\",\"accessIPv4\":\"10.0.0.1\"}}",
                "{\"server\":{\"status\":\"ACTIVE\",\"accessIPv4\":\"10.0.0.2\"}}",
                "{\"server\":{\"status\":\"ACTIVE\",\"accessIPv4\":\"10.0.0.3\"}}");
        when(client.allocateFloatingIp(anyString()))
                .thenReturn("203.0.113.1")
                .thenReturn("203.0.113.2")
                .thenReturn("203.0.113.3");

        PrivateClusterRequest request = new PrivateClusterRequest();
        request.setClusterName("ws-demo");
        VMSpec cp = new VMSpec();
        cp.setRole("control-plane");
        request.setControlPlane(cp);
        request.setWorkers(List.of(new VMSpec(), new VMSpec()));

        List<PrivateClusterInfo.VMInfo> vms = provider.createVMs(request);

        assertEquals(3, vms.size());
        assertEquals("control-plane", vms.get(0).getRole());
        assertEquals("ACTIVE", vms.get(0).getPowerState());
        assertEquals("10.0.0.1", vms.get(0).getIpAddress());
        assertEquals("203.0.113.1", vms.get(0).getFloatingIp());
    }

    @Test
    @DisplayName("createVMs — 创建失败返回空列表")
    void createVMs_createFailure_shouldReturnEmptyList() {
        when(client.createServer(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Nova 不可达"));

        PrivateClusterRequest request = new PrivateClusterRequest();
        request.setClusterName("ws-demo");
        request.setControlPlane(new VMSpec());
        request.setWorkers(List.of(new VMSpec()));

        List<PrivateClusterInfo.VMInfo> vms = provider.createVMs(request);

        assertTrue(vms.isEmpty());
    }

    @Test
    @DisplayName("createVMs — 分配浮动 IP 失败不阻塞流程")
    void createVMs_floatingIpFailure_shouldNotBlock() {
        when(client.createServer(anyString(), anyString(), anyString()))
                .thenReturn("srv-1");
        when(client.getServer(anyString())).thenReturn(
                "{\"server\":{\"status\":\"ACTIVE\",\"accessIPv4\":\"10.0.0.1\"}}");
        when(client.allocateFloatingIp(anyString()))
                .thenThrow(new RuntimeException("无可用浮动 IP"));

        PrivateClusterRequest request = new PrivateClusterRequest();
        request.setClusterName("ws-demo");
        request.setControlPlane(new VMSpec());
        request.setWorkers(List.of());

        List<PrivateClusterInfo.VMInfo> vms = provider.createVMs(request);

        assertEquals(1, vms.size());
        assertEquals("ACTIVE", vms.get(0).getPowerState());
        assertNull(vms.get(0).getFloatingIp());
    }

    @Test
    @DisplayName("destroyVMs — 全部销毁成功返回 true")
    void destroyVMs_allSuccess_shouldReturnTrue() {
        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of(
                PrivateClusterInfo.VMInfo.builder().vmId("s1").name("n1").role("control-plane").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("s2").name("n2").role("worker").build()));

        boolean result = provider.destroyVMs(cluster);

        assertTrue(result);
        verify(client, times(2)).deleteServer(anyString());
    }

    @Test
    @DisplayName("destroyVMs — 部分失败返回 false")
    void destroyVMs_partialFailure_shouldReturnFalse() {
        doThrow(new RuntimeException("删除失败")).when(client).deleteServer("s2");

        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of(
                PrivateClusterInfo.VMInfo.builder().vmId("s1").name("n1").role("control-plane").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("s2").name("n2").role("worker").build()));

        boolean result = provider.destroyVMs(cluster);

        assertFalse(result);
    }

    @Test
    @DisplayName("getVMInfo — 查询所有实例实时状态")
    void getVMInfo_shouldQueryAllServers() {
        when(client.getServer("s1")).thenReturn(
                "{\"server\":{\"status\":\"ACTIVE\",\"accessIPv4\":\"10.0.0.1\"}}");
        when(client.getServer("s2")).thenReturn(
                "{\"server\":{\"status\":\"SHUTOFF\",\"accessIPv4\":\"10.0.0.2\"}}");

        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of(
                PrivateClusterInfo.VMInfo.builder().vmId("s1").name("n1").role("control-plane").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("s2").name("n2").role("worker").build()));

        List<PrivateClusterInfo.VMInfo> result = provider.getVMInfo(cluster);

        assertEquals(2, result.size());
        assertEquals("ACTIVE", result.get(0).getPowerState());
        assertEquals("SHUTOFF", result.get(1).getPowerState());
    }

    @Test
    @DisplayName("scaleVMs — 扩容工作节点")
    void scaleVMs_scaleUp_shouldAddWorkers() {
        when(client.createServer(anyString(), anyString(), anyString()))
                .thenReturn("s3")
                .thenReturn("s4");
        when(client.getServer(anyString())).thenReturn(
                "{\"server\":{\"status\":\"ACTIVE\",\"accessIPv4\":\"10.0.0.3\"}}",
                "{\"server\":{\"status\":\"ACTIVE\",\"accessIPv4\":\"10.0.0.4\"}}");
        when(client.allocateFloatingIp(anyString()))
                .thenReturn("203.0.113.3")
                .thenReturn("203.0.113.4");

        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of(
                PrivateClusterInfo.VMInfo.builder().vmId("s1").name("cp").role("control-plane").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("s2").name("w1").role("worker").build()));

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
                PrivateClusterInfo.VMInfo.builder().vmId("s1").name("cp").role("control-plane").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("s2").name("w1").role("worker").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("s3").name("w2").role("worker").build(),
                PrivateClusterInfo.VMInfo.builder().vmId("s4").name("w3").role("worker").build()));

        List<PrivateClusterInfo.VMInfo> result = provider.scaleVMs(cluster, 1, null);

        long workers = result.stream().filter(v -> "worker".equals(v.getRole())).count();
        assertEquals(1, workers);
        verify(client, times(2)).deleteServer(anyString());
    }

    @Test
    @DisplayName("使用 imageRef 覆盖默认镜像")
    void createVMs_shouldUseImageRefWhenProvided() {
        when(client.createServer(anyString(), eq("custom-img"), anyString()))
                .thenReturn("s1");
        when(client.getServer(anyString())).thenReturn(
                "{\"server\":{\"status\":\"ACTIVE\"}}");

        PrivateClusterRequest request = new PrivateClusterRequest();
        request.setClusterName("ws-demo");
        VMSpec cp = new VMSpec();
        cp.setImageRef("custom-img");
        request.setControlPlane(cp);
        request.setWorkers(List.of());

        provider.createVMs(request);

        verify(client).createServer(anyString(), eq("custom-img"), anyString());
    }

    @Test
    @DisplayName("使用 flavorId 覆盖默认 flavor")
    void createVMs_shouldUseFlavorIdWhenProvided() {
        when(client.createServer(anyString(), anyString(), eq("custom-flavor")))
                .thenReturn("s1");
        when(client.getServer(anyString())).thenReturn(
                "{\"server\":{\"status\":\"ACTIVE\"}}");

        PrivateClusterRequest request = new PrivateClusterRequest();
        request.setClusterName("ws-demo");
        VMSpec cp = new VMSpec();
        cp.setFlavorId("custom-flavor");
        request.setControlPlane(cp);
        request.setWorkers(List.of());

        provider.createVMs(request);

        verify(client).createServer(anyString(), anyString(), eq("custom-flavor"));
    }
}