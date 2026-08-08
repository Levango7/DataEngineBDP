package com.shuqing.bigdata.infra.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.infra.cloud.model.CloudClusterInfo;
import com.shuqing.bigdata.infra.cloud.model.CloudClusterRequest;
import com.shuqing.bigdata.infra.cloud.model.VMSpec;
import com.shuqing.bigdata.infra.cloud.provider.CloudProvider;
import com.shuqing.bigdata.infra.cloud.repository.CloudClusterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link CloudProviderService} 路由逻辑单元测试。
 *
 * <p>使用 Mockito Mock 注入 Repository / K8sBootstrapService / Provider，
 * 验证 provider 路由与异常处理逻辑，不依赖真实云 SDK。</p>
 */
@DisplayName("CloudProviderService 路由测试")
@ExtendWith(MockitoExtension.class)
class CloudProviderServiceTest {

    @Mock
    private CloudClusterRepository repository;

    @Mock
    private K8sBootstrapService k8sBootstrapService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CloudProviderService service;

    @BeforeEach
    void setUp() {
        // 构造两个 Mock Provider
        CloudProvider huawei = new StubCloudProvider("huawei");
        CloudProvider ali = new StubCloudProvider("ali");
        service = new CloudProviderService(List.of(huawei, ali), repository, k8sBootstrapService, objectMapper);
    }

    @Test
    @DisplayName("listSupportedProviders 返回已注册的 provider 列表")
    void listSupportedProvidersShouldReturnRegistered() {
        List<String> providers = service.listSupportedProviders();
        assertEquals(2, providers.size());
        // 不依赖注册顺序，验证包含关系
        assertTrue(providers.containsAll(List.of("huawei", "ali")), "providers should contain huawei and ali");
    }

    @Test
    @DisplayName("不支持的 provider 抛 IllegalArgumentException")
    void unsupportedProviderShouldThrow() {
        CloudClusterRequest request = buildSampleRequest();
        assertThrows(IllegalArgumentException.class,
                () -> service.createCluster("aws", request));
    }

    @Test
    @DisplayName("createCluster 路由到正确 provider 并返回集群信息")
    void createClusterShouldRouteToCorrectProvider() {
        CloudClusterRequest request = buildSampleRequest();
        CloudClusterInfo info = service.createCluster("huawei", request);
        assertEquals("huawei", info.getProvider());
        assertEquals("ws-test-cluster", info.getClusterName());
    }

    @Test
    @DisplayName("getCluster 返回 provider 查询结果")
    void getClusterShouldReturnProviderResult() {
        CloudClusterInfo info = service.getCluster("ali", "cluster-001");
        assertEquals("ali", info.getProvider());
    }

    private CloudClusterRequest buildSampleRequest() {
        return CloudClusterRequest.builder()
                .clusterName("ws-test-cluster")
                .workspaceId("ws-test")
                .nodeCount(3)
                .vmSpec(VMSpec.builder()
                        .instanceType("s6.large.2")
                        .imageId("img-xxx")
                        .systemDiskGb(50)
                        .build())
                .autoBootstrapK8s(false) // 测试中关闭异步引导
                .build();
    }

    /**
     * 测试用 Stub Provider。
     */
    static class StubCloudProvider implements CloudProvider {
        private final String name;

        StubCloudProvider(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public CloudClusterInfo createVMs(String clusterId, CloudClusterRequest request) {
            return CloudClusterInfo.builder()
                    .clusterId(clusterId)
                    .clusterName(request.getClusterName())
                    .provider(name)
                    .workspaceId(request.getWorkspaceId())
                    .status("CREATING")
                    .nodes(List.of())
                    .k8sBootstrapStatus("PENDING")
                    .build();
        }

        @Override
        public CloudClusterInfo destroyVMs(String clusterId) {
            return CloudClusterInfo.builder().clusterId(clusterId).provider(name).status("DELETED").build();
        }

        @Override
        public CloudClusterInfo startVMs(String clusterId) {
            return CloudClusterInfo.builder().clusterId(clusterId).provider(name).status("RUNNING").build();
        }

        @Override
        public CloudClusterInfo stopVMs(String clusterId) {
            return CloudClusterInfo.builder().clusterId(clusterId).provider(name).status("STOPPED").build();
        }

        @Override
        public CloudClusterInfo getVMInfo(String clusterId) {
            return CloudClusterInfo.builder().clusterId(clusterId).provider(name).status("RUNNING").build();
        }

        @Override
        public CloudClusterInfo scaleVMs(String clusterId, int targetNodeCount) {
            return CloudClusterInfo.builder().clusterId(clusterId).provider(name).status("RUNNING").build();
        }
    }
}