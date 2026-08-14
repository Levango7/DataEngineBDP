package com.levango7.dataenginebdp.infra.cloud.service;

import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterInfo;
import com.levango7.dataenginebdp.infra.cloud.provider.CloudProvider;
import com.levango7.dataenginebdp.infra.cloud.repository.CloudClusterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * K8sBootstrapService 真实 VM 轮询测试（waitForVMsRunning）。
 */
@ExtendWith(MockitoExtension.class)
class K8sBootstrapServiceTest {

    @Mock
    private CloudClusterRepository repository;

    @Mock
    private CloudProviderService providerService;

    @Mock
    private CloudProvider provider;

    private K8sBootstrapService newService() {
        return new K8sBootstrapService(repository, providerService,
                "/opt/ske/bootstrap.sh", 6443);
    }

    private CloudClusterInfo clusterWithVms(String... statuses) {
        List<CloudClusterInfo.VMInfo> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            nodes.add(CloudClusterInfo.VMInfo.builder()
                    .instanceId("i-" + i)
                    .instanceName("node-" + i)
                    .status(statuses[i])
                    .build());
        }
        return CloudClusterInfo.builder()
                .clusterId("cluster-1")
                .provider("ali")
                .nodes(nodes)
                .build();
    }

    private boolean invokeWaitForVmsRunning(K8sBootstrapService svc, CloudClusterInfo info) throws Exception {
        Method m = K8sBootstrapService.class.getDeclaredMethod("waitForVMsRunning", CloudClusterInfo.class);
        m.setAccessible(true);
        return (boolean) m.invoke(svc, info);
    }

    @Test
    void waitForVMsRunning_returnsTrueWhenAllRunning() throws Exception {
        when(providerService.getProvider("ali")).thenReturn(provider);
        when(provider.getVMInfo(anyString()))
                .thenReturn(clusterWithVms("CREATING", "RUNNING"))
                .thenReturn(clusterWithVms("RUNNING", "RUNNING"));

        K8sBootstrapService svc = newService();
        boolean ok = invokeWaitForVmsRunning(svc, clusterWithVms("CREATING", "RUNNING"));
        assertThat(ok).isTrue();
    }

    @Test
    void waitForVMsRunning_returnsFalseWhenNoNodes() throws Exception {
        K8sBootstrapService svc = newService();
        CloudClusterInfo empty = CloudClusterInfo.builder()
                .clusterId("cluster-1").provider("ali").nodes(null).build();
        boolean ok = invokeWaitForVmsRunning(svc, empty);
        assertThat(ok).isFalse();
    }

    @Test
    void waitForVMsRunning_providerErrorIsRetried() throws Exception {
        // provider 抛异常 → 重试后成功
        when(providerService.getProvider("ali")).thenReturn(provider);
        when(provider.getVMInfo(anyString()))
                .thenThrow(new RuntimeException("ecs timeout"))
                .thenReturn(clusterWithVms("RUNNING"));

        K8sBootstrapService svc = newService();
        boolean ok = invokeWaitForVmsRunning(svc, clusterWithVms("RUNNING"));
        assertThat(ok).isTrue();
    }
}
