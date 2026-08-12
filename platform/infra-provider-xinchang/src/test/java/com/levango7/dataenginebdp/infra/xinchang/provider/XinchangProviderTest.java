package com.levango7.dataenginebdp.infra.xinchang.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.infra.xinchang.model.ClusterCreateRequest;
import com.levango7.dataenginebdp.infra.xinchang.model.ClusterEntity;
import com.levango7.dataenginebdp.infra.xinchang.model.ClusterInfo;
import com.levango7.dataenginebdp.infra.xinchang.model.ClusterRepository;
import com.levango7.dataenginebdp.infra.xinchang.model.XinchangNodeSpec;
import com.levango7.dataenginebdp.infra.xinchang.service.K8sBootstrapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link XinchangProvider} 单元测试。
 *
 * <p>使用 Mockito mock IPMI/K8s/Repository，验证 Provider 主流程的状态机迁移与持久化调用。</p>
 */
class XinchangProviderTest {

    @Mock
    private ClusterRepository clusterRepository;
    @Mock
    private IpmiRedfishClient ipmiClient;
    @Mock
    private K8sBootstrapService k8sBootstrapService;

    private XinchangProvider provider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        provider = new XinchangProvider(clusterRepository, ipmiClient, k8sBootstrapService, new ObjectMapper());
        when(k8sBootstrapService.bootstrap(anyString(), any(ClusterCreateRequest.class)))
                .thenReturn("192.168.200.10");
    }

    @Test
    void providerTypeShouldBeXinchang() {
        assertEquals("xinchang", provider.providerType());
    }

    @Test
    void createClusterShouldPersistAndReturnRunning() {
        XinchangNodeSpec cp = XinchangNodeSpec.builder()
                .role("control-plane")
                .bmcIp("192.168.200.100")
                .pxeMac("aa:bb:cc:dd:ee:01")
                .hostname("cp-1")
                .build();
        XinchangNodeSpec worker = XinchangNodeSpec.builder()
                .role("worker")
                .bmcIp("192.168.200.101")
                .pxeMac("aa:bb:cc:dd:ee:02")
                .hostname("worker-1")
                .build();
        ClusterCreateRequest request = ClusterCreateRequest.builder()
                .clusterName("ws-demo-cluster")
                .tenantId("tenant-001")
                .nodes(List.of(cp, worker))
                .build();

        ClusterInfo info = provider.createCluster(request);

        assertNotNull(info);
        assertEquals(ClusterInfo.Status.RUNNING, info.getStatus());
        assertEquals("192.168.200.10", info.getControlPlaneEndpoint());
        // 验证 IPMI 对每个节点都发了开机指令
        verify(ipmiClient, times(2)).powerOnWithPxe(any(XinchangNodeSpec.class));
        // 验证 K8s bootstrap 被调用
        verify(k8sBootstrapService, times(1)).bootstrap(anyString(), any(ClusterCreateRequest.class));
        // 验证持久化至少 2 次（CREATING + RUNNING）
        verify(clusterRepository, times(2)).save(any(ClusterEntity.class));
    }

    @Test
    void createClusterWithoutControlPlaneShouldFail() {
        XinchangNodeSpec worker = XinchangNodeSpec.builder()
                .role("worker")
                .bmcIp("192.168.200.101")
                .pxeMac("aa:bb:cc:dd:ee:02")
                .hostname("worker-1")
                .build();
        ClusterCreateRequest request = ClusterCreateRequest.builder()
                .clusterName("ws-no-cp")
                .tenantId("tenant-001")
                .nodes(List.of(worker))
                .build();

        ClusterInfo info = provider.createCluster(request);

        assertEquals(ClusterInfo.Status.FAILED, info.getStatus());
    }

    @Test
    void destroyClusterShouldPowerOffAllNodesAndReturnDestroyed() {
        XinchangNodeSpec cp = XinchangNodeSpec.builder()
                .role("control-plane")
                .bmcIp("192.168.200.100")
                .pxeMac("aa:bb:cc:dd:ee:01")
                .hostname("cp-1")
                .build();
        ClusterEntity entity = ClusterEntity.builder()
                .clusterId("c-123")
                .clusterName("ws-demo")
                .tenantId("tenant-001")
                .k8sVersion("v1.28.9")
                .status(ClusterInfo.Status.RUNNING)
                .controlPlaneEndpoint("192.168.200.10")
                .nodesJson("[{\"role\":\"control-plane\",\"cpuArch\":\"KUNPENG\",\"osType\":\"KYLIN_V10\","
                        + "\"bmcIp\":\"192.168.200.100\",\"pxeMac\":\"aa:bb:cc:dd:ee:01\",\"hostname\":\"cp-1\"}]")
                .metadataJson("{}")
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();
        when(clusterRepository.findById("c-123")).thenReturn(Optional.of(entity));

        ClusterInfo info = provider.destroyCluster("c-123");

        assertNotNull(info);
        assertEquals(ClusterInfo.Status.DESTROYED, info.getStatus());
        verify(ipmiClient, times(1)).powerOff(any(XinchangNodeSpec.class));
        verify(k8sBootstrapService, times(1)).teardown(anyString(), anyString());
    }

    @Test
    void getClusterInfoShouldReturnNullIfNotFound() {
        when(clusterRepository.findById("not-exist")).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertNull(provider.getClusterInfo("not-exist"));
    }

    @Test
    void listClustersShouldDelegateToRepository() {
        when(clusterRepository.findByTenantId("tenant-001")).thenReturn(List.of());
        List<ClusterInfo> result = provider.listClusters("tenant-001");
        assertNotNull(result);
        assertEquals(0, result.size());
        verify(clusterRepository, times(1)).findByTenantId("tenant-001");
    }
}