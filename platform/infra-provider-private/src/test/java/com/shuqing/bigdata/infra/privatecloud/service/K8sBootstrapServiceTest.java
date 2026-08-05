package com.shuqing.bigdata.infra.privatecloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.infra.privatecloud.config.PrivateCloudProperties;
import com.shuqing.bigdata.infra.privatecloud.model.PrivateClusterInfo;
import com.shuqing.bigdata.infra.privatecloud.model.PrivateClusterRequest;
import com.shuqing.bigdata.infra.privatecloud.model.VMSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K8sBootstrapService 测试。
 *
 * @author shuqing-bigdata
 */
class K8sBootstrapServiceTest {

    private K8sBootstrapService service;
    private PrivateCloudProperties properties;

    @BeforeEach
    void setUp() {
        properties = new PrivateCloudProperties();
        service = new K8sBootstrapService(properties);
    }

    @Test
    @DisplayName("生成控制面 cloud-init — 包含 kubeadm init 与 CIDR")
    void generateControlPlaneCloudInit_shouldContainKubeadmInit() {
        PrivateClusterRequest request = new PrivateClusterRequest();
        request.setClusterName("ws-demo");
        request.setControlPlane(new VMSpec());
        request.setWorkers(List.of(new VMSpec()));
        request.setK8sVersion("v1.30.0");
        request.setPodCidr("10.244.0.0/16");
        request.setServiceCidr("10.96.0.0/12");

        String userData = service.generateControlPlaneCloudInit(request);

        assertTrue(userData.startsWith("#cloud-config"));
        assertTrue(userData.contains("kubeadm init"));
        assertTrue(userData.contains("10.244.0.0/16"));
        assertTrue(userData.contains("10.96.0.0/12"));
        assertTrue(userData.contains("v1.30.0"));
    }

    @Test
    @DisplayName("生成工作节点 cloud-init — 包含 join 命令占位符")
    void generateWorkerCloudInit_shouldContainJoinPlaceholder() {
        PrivateClusterRequest request = new PrivateClusterRequest();
        request.setClusterName("ws-demo");

        String userData = service.generateWorkerCloudInit(request);

        assertTrue(userData.startsWith("#cloud-config"));
        assertTrue(userData.contains("__JOIN_COMMAND__"));
        assertTrue(userData.contains("kubeadm"));
    }

    @Test
    @DisplayName("使用默认配置 — 当 request 字段为 null")
    void generateControlPlaneCloudInit_shouldUseDefaultsWhenRequestNull() {
        PrivateClusterRequest request = new PrivateClusterRequest();
        request.setClusterName("ws-demo");
        request.setControlPlane(new VMSpec());
        request.setWorkers(List.of(new VMSpec()));

        String userData = service.generateControlPlaneCloudInit(request);

        assertTrue(userData.contains(properties.getK8sBootstrap().getK8sVersion()));
        assertTrue(userData.contains(properties.getK8sBootstrap().getPodCidr()));
    }

    @Test
    @DisplayName("序列化/反序列化 VM 列表 — 往返一致")
    void serializeDeserializeVms_shouldRoundTrip() {
        PrivateClusterInfo.VMInfo vm1 = PrivateClusterInfo.VMInfo.builder()
                .vmId("vm-1").name("node-1").role("control-plane")
                .powerState("POWERED_ON").ipAddress("192.168.1.10").build();
        PrivateClusterInfo.VMInfo vm2 = PrivateClusterInfo.VMInfo.builder()
                .vmId("vm-2").name("node-2").role("worker")
                .powerState("POWERED_ON").ipAddress("192.168.1.11").build();

        List<PrivateClusterInfo.VMInfo> vms = List.of(vm1, vm2);
        String json = service.serializeVms(vms);

        assertNotNull(json);
        assertTrue(json.contains("vm-1"));
        assertTrue(json.contains("vm-2"));

        List<PrivateClusterInfo.VMInfo> parsed = service.deserializeVms(json);
        assertEquals(2, parsed.size());
        assertEquals("vm-1", parsed.get(0).getVmId());
        assertEquals("worker", parsed.get(1).getRole());
    }

    @Test
    @DisplayName("反序列化空 JSON — 返回空列表")
    void deserializeVms_emptyJson_shouldReturnEmptyList() {
        List<PrivateClusterInfo.VMInfo> parsed = service.deserializeVms(null);
        assertTrue(parsed.isEmpty());

        parsed = service.deserializeVms("");
        assertTrue(parsed.isEmpty());
    }

    @Test
    @DisplayName("encodeCloudInit — 返回 base64 编码")
    void encodeCloudInit_shouldReturnBase64() {
        String userData = "#cloud-config\npackages: []";
        String encoded = service.encodeCloudInit(userData);

        assertNotNull(encoded);
        // base64 解码后应与原文一致
        String decoded = new String(java.util.Base64.getDecoder().decode(encoded));
        assertEquals(userData, decoded);
    }

    @Test
    @DisplayName("bootstrap — cloud-init 模式返回 true")
    void bootstrap_cloudInitMode_shouldReturnTrue() {
        properties.getK8sBootstrap().setMethod("cloud-init");
        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of(PrivateClusterInfo.VMInfo.builder()
                .vmId("vm-1").name("node-1").role("control-plane").build()));

        boolean result = service.bootstrap(cluster);
        assertTrue(result);
    }

    @Test
    @DisplayName("bootstrap — VM 列表为空返回 false")
    void bootstrap_emptyVms_shouldReturnFalse() {
        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").build();
        cluster.setVms(List.of());

        boolean result = service.bootstrap(cluster);
        assertFalse(result);
    }

    @Test
    @DisplayName("buildBootstrapSummary — 包含必要字段")
    void buildBootstrapSummary_shouldContainFields() {
        PrivateClusterInfo cluster = PrivateClusterInfo.builder()
                .id(1L).clusterName("ws-demo").status("RUNNING").k8sVersion("v1.30.0").build();
        cluster.setVms(List.of(PrivateClusterInfo.VMInfo.builder()
                .vmId("vm-1").name("node-1").role("control-plane").build()));

        var summary = service.buildBootstrapSummary(cluster);

        assertEquals(1L, summary.get("clusterId"));
        assertEquals("RUNNING", summary.get("status"));
        assertEquals("v1.30.0", summary.get("k8sVersion"));
        assertEquals(1, summary.get("vmCount"));
    }
}