package com.levango7.dataenginebdp.infra.cloud.provider;

import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterInfo;
import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterRequest;
import com.levango7.dataenginebdp.infra.cloud.model.VMSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CloudProvider} SPI 接口契约测试。
 *
 * <p>使用 Mock Provider 验证接口契约，不依赖真实云 SDK。</p>
 */
@DisplayName("CloudProvider SPI 契约测试")
class CloudProviderTest {

    private CloudClusterRequest sampleRequest;

    @BeforeEach
    void setUp() {
        sampleRequest = CloudClusterRequest.builder()
                .clusterName("ws-test-cluster")
                .workspaceId("ws-test")
                .nodeCount(3)
                .vmSpec(VMSpec.builder()
                        .instanceType("s6.large.2")
                        .imageId("img-xxx")
                        .systemDiskGb(50)
                        .dataDiskGb(100)
                        .bandwidthMbps(5)
                        .allocatePublicIp(true)
                        .sshUsername("root")
                        .build())
                .autoBootstrapK8s(true)
                .build();
    }

    @Test
    @DisplayName("Mock Provider name() 返回正确标识")
    void mockProviderNameShouldMatch() {
        CloudProvider mock = new MockCloudProvider("huawei");
        assertEquals("huawei", mock.name());
    }

    @Test
    @DisplayName("Mock Provider createVMs 返回集群信息")
    void mockCreateShouldReturnClusterInfo() {
        CloudProvider mock = new MockCloudProvider("huawei");
        CloudClusterInfo info = mock.createVMs("cluster-001", sampleRequest);
        assertNotNull(info);
        assertEquals("cluster-001", info.getClusterId());
        assertEquals("huawei", info.getProvider());
        assertEquals(3, info.getNodes().size());
    }

    @Test
    @DisplayName("CloudProviderException 携带 message 与 cause")
    void cloudProviderExceptionShouldCarryContext() {
        CloudProvider.CloudProviderException ex = assertThrows(
                CloudProvider.CloudProviderException.class,
                () -> { throw new CloudProvider.CloudProviderException("boom", new RuntimeException("root")); });
        assertEquals("boom", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    /**
     * 测试用 Mock Provider。
     */
    static class MockCloudProvider implements CloudProvider {
        private final String name;

        MockCloudProvider(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public CloudClusterInfo createVMs(String clusterId, CloudClusterRequest request) {
            List<CloudClusterInfo.VMInfo> nodes = new java.util.ArrayList<>();
            for (int i = 0; i < request.getNodeCount(); i++) {
                nodes.add(CloudClusterInfo.VMInfo.builder()
                        .instanceId("inst-" + i)
                        .instanceName(request.getClusterName() + "-node-" + i)
                        .status("CREATING")
                        .controlPlane(i == 0)
                        .build());
            }
            return CloudClusterInfo.builder()
                    .clusterId(clusterId)
                    .clusterName(request.getClusterName())
                    .provider(name)
                    .workspaceId(request.getWorkspaceId())
                    .status("CREATING")
                    .nodes(nodes)
                    .k8sBootstrapStatus("PENDING")
                    .build();
        }

        @Override
        public CloudClusterInfo destroyVMs(String clusterId) {
            return CloudClusterInfo.builder().clusterId(clusterId).status("DELETED").build();
        }

        @Override
        public CloudClusterInfo startVMs(String clusterId) {
            return CloudClusterInfo.builder().clusterId(clusterId).status("RUNNING").build();
        }

        @Override
        public CloudClusterInfo stopVMs(String clusterId) {
            return CloudClusterInfo.builder().clusterId(clusterId).status("STOPPED").build();
        }

        @Override
        public CloudClusterInfo getVMInfo(String clusterId) {
            return CloudClusterInfo.builder().clusterId(clusterId).status("RUNNING").build();
        }

        @Override
        public CloudClusterInfo scaleVMs(String clusterId, int targetNodeCount) {
            return CloudClusterInfo.builder().clusterId(clusterId).status("RUNNING").build();
        }
    }
}