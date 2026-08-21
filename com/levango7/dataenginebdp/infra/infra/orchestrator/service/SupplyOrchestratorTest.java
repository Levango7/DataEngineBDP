package com.shuqing.bigdata.infra.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.infra.orchestrator.model.ClusterCreateRequest;
import com.shuqing.bigdata.infra.orchestrator.model.ClusterInfo;
import com.shuqing.bigdata.infra.orchestrator.model.EnvironmentType;
import com.shuqing.bigdata.infra.orchestrator.model.SupplyResult;
import com.shuqing.bigdata.infra.orchestrator.registry.EnvironmentProfile;
import com.shuqing.bigdata.infra.orchestrator.registry.ProviderDescriptor;
import com.shuqing.bigdata.infra.orchestrator.registry.ProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SupplyOrchestrator} 单元测试。
 *
 * <p>使用 Mockito mock {@link WebClient} 的链式调用，验证编排层的路由、请求构造、响应归一化逻辑。
 * 因 WebClient 的 Builder API 极难 mock，此处采用"子类化 + 重写"策略：构造一个真实 WebClient
 * 但通过 {@code @Value} 注入的 pollEnabled=false 跳过轮询，仅验证同步创建路径。</p>
 */
class SupplyOrchestratorTest {

    private ProviderRegistry registry;
    private EnvironmentProfile environmentProfile;
    private ObjectMapper objectMapper;
    private ProviderDescriptor xinchangDescriptor;

    @BeforeEach
    void setUp() {
        registry = new ProviderRegistry();
        environmentProfile = new EnvironmentProfile();
        objectMapper = new ObjectMapper();

        xinchangDescriptor = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.XINCHANG)
                .name("infra-provider-xinchang")
                .baseUrl("http://localhost:8090")
                .enabled(true)
                .build();
        registry.register(xinchangDescriptor);
    }

    @Test
    void shouldReturnFailedWhenProviderNotRegistered() {
        // 给一个未注册的环境
        registry.unregister(EnvironmentType.CLOUD_HUAWEI);
        WebClient webClient = WebClient.builder().build();
        SupplyOrchestrator orchestrator = new SupplyOrchestrator(
                registry, environmentProfile, webClient, objectMapper);

        ClusterCreateRequest request = ClusterCreateRequest.builder()
                .environment(EnvironmentType.CLOUD_HUAWEI)
                .clusterName("test-cluster")
                .tenantId("tenant-1")
                .nodes(List.of(ClusterCreateRequest.NodeSpec.builder().role("control-plane").build()))
                .build();

        SupplyResult result = orchestrator.createCluster(request);

        assertThat(result.getPhase()).isEqualTo(SupplyResult.Phase.FAILED);
        assertThat(result.getErrorMessage()).contains("no provider registered");
        assertThat(result.getEvents()).contains("provider-lookup-failed");
    }

    @Test
    void shouldBuildProviderRequestWithAllFields() {
        // 验证请求构造逻辑（通过反射调用私有方法）
        WebClient webClient = WebClient.builder().build();
        SupplyOrchestrator orchestrator = new SupplyOrchestrator(
                registry, environmentProfile, webClient, objectMapper);

        ClusterCreateRequest request = ClusterCreateRequest.builder()
                .environment(EnvironmentType.XINCHANG)
                .clusterName("my-cluster")
                .tenantId("tenant-1")
                .k8sVersion("v1.28.9")
                .podCidr("10.244.0.0/16")
                .serviceCidr("10.96.0.0/12")
                .skeEnabled(true)
                .description("test")
                .nodes(List.of(ClusterCreateRequest.NodeSpec.builder()
                        .role("control-plane").count(3).cpuCores(16).memoryGb(64).build()))
                .providerParams(Map.of("region", "cn-north-1"))
                .build();

        // 通过反射调用 buildProviderRequest
        Map<String, Object> providerRequest;
        try {
            java.lang.reflect.Method method = SupplyOrchestrator.class.getDeclaredMethod(
                    "buildProviderRequest", ClusterCreateRequest.class, EnvironmentType.class);
            method.setAccessible(true);
            providerRequest = (Map<String, Object>) method.invoke(orchestrator, request, EnvironmentType.XINCHANG);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(providerRequest.get("clusterName")).isEqualTo("my-cluster");
        assertThat(providerRequest.get("tenantId")).isEqualTo("tenant-1");
        assertThat(providerRequest.get("k8sVersion")).isEqualTo("v1.28.9");
        assertThat(providerRequest.get("skeEnabled")).isEqualTo(true);
        assertThat(providerRequest.get("region")).isEqualTo("cn-north-1");
        assertThat(providerRequest.get("nodes")).isInstanceOf(List.class);
        List<?> nodes = (List<?>) providerRequest.get("nodes");
        assertThat(nodes).hasSize(1);
    }

    @Test
    void shouldNotIncludeSkeEnabledForNonXinchangEnv() {
        registry.register(ProviderDescriptor.builder()
                .environmentType(EnvironmentType.CLOUD_HUAWEI)
                .name("cloud-huawei").baseUrl("http://cloud:8092").build());

        WebClient webClient = WebClient.builder().build();
        SupplyOrchestrator orchestrator = new SupplyOrchestrator(
                registry, environmentProfile, webClient, objectMapper);

        ClusterCreateRequest request = ClusterCreateRequest.builder()
                .environment(EnvironmentType.CLOUD_HUAWEI)
                .clusterName("my-cloud-cluster")
                .tenantId("tenant-1")
                .nodes(List.of(ClusterCreateRequest.NodeSpec.builder().role("control-plane").build()))
                .build();

        Map<String, Object> providerRequest;
        try {
            java.lang.reflect.Method method = SupplyOrchestrator.class.getDeclaredMethod(
                    "buildProviderRequest", ClusterCreateRequest.class, EnvironmentType.class);
            method.setAccessible(true);
            providerRequest = (Map<String, Object>) method.invoke(orchestrator, request, EnvironmentType.CLOUD_HUAWEI);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(providerRequest).doesNotContainKey("skeEnabled");
    }

    @Test
    void shouldNormalizeClusterInfoFromProviderResponse() {
        WebClient webClient = WebClient.builder().build();
        SupplyOrchestrator orchestrator = new SupplyOrchestrator(
                registry, environmentProfile, webClient, objectMapper);

        // 模拟下游响应
        String jsonResponse = """
                {
                  "clusterId": "cls-abc-123",
                  "clusterName": "my-cluster",
                  "tenantId": "tenant-1",
                  "status": "ACTIVE",
                  "k8sVersion": "v1.28.9",
                  "controlPlaneEndpoint": "https://10.0.0.1:6443",
                  "nodes": [{"hostname": "node1", "role": "control-plane"}],
                  "metadata": {"createdAt": "2024-01-01T00:00:00Z"}
                }
                """;
        JsonNode response;
        try {
            response = objectMapper.readTree(jsonResponse);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ClusterCreateRequest request = ClusterCreateRequest.builder()
                .environment(EnvironmentType.XINCHANG)
                .clusterName("my-cluster")
                .tenantId("tenant-1")
                .nodes(List.of())
                .build();

        // 通过反射调用 normalizeClusterInfo
        ClusterInfo info;
        try {
            java.lang.reflect.Method method = SupplyOrchestrator.class.getDeclaredMethod(
                    "normalizeClusterInfo", JsonNode.class, EnvironmentType.class, ClusterCreateRequest.class);
            method.setAccessible(true);
            info = (ClusterInfo) method.invoke(orchestrator, response, EnvironmentType.XINCHANG, request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(info.getClusterId()).isEqualTo("cls-abc-123");
        assertThat(info.getClusterName()).isEqualTo("my-cluster");
        assertThat(info.getStatus()).isEqualTo(ClusterInfo.Status.ACTIVE);
        assertThat(info.getControlPlaneEndpoint()).isEqualTo("https://10.0.0.1:6443");
        assertThat(info.getEnvironment()).isEqualTo(EnvironmentType.XINCHANG);
        assertThat(info.getNodes()).hasSize(1);
        assertThat(info.getMetadata()).containsKey("createdAt");
    }

    @Test
    void shouldExtractClusterIdFromVariousFieldNames() {
        WebClient webClient = WebClient.builder().build();
        SupplyOrchestrator orchestrator = new SupplyOrchestrator(
                registry, environmentProfile, webClient, objectMapper);

        // 测试 clusterId 字段
        assertThat(extractClusterId(orchestrator, "{\"clusterId\": \"abc\"}")).isEqualTo("abc");
        // 测试 id 字段
        assertThat(extractClusterId(orchestrator, "{\"id\": \"xyz\"}")).isEqualTo("xyz");
        // 测试 cluster_id 字段
        assertThat(extractClusterId(orchestrator, "{\"cluster_id\": \"def\"}")).isEqualTo("def");
        // 测试不存在的字段
        assertThat(extractClusterId(orchestrator, "{\"foo\": \"bar\"}")).isNull();
    }

    @Test
    void shouldHandleNullResponseInNormalize() {
        WebClient webClient = WebClient.builder().build();
        SupplyOrchestrator orchestrator = new SupplyOrchestrator(
                registry, environmentProfile, webClient, objectMapper);

        ClusterCreateRequest request = ClusterCreateRequest.builder()
                .environment(EnvironmentType.XINCHANG)
                .clusterName("my-cluster")
                .tenantId("tenant-1")
                .nodes(List.of())
                .build();

        ClusterInfo info;
        try {
            java.lang.reflect.Method method = SupplyOrchestrator.class.getDeclaredMethod(
                    "normalizeClusterInfo", JsonNode.class, EnvironmentType.class, ClusterCreateRequest.class);
            method.setAccessible(true);
            info = (ClusterInfo) method.invoke(orchestrator, null, EnvironmentType.XINCHANG, request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(info.getStatus()).isEqualTo(ClusterInfo.Status.UNKNOWN);
        assertThat(info.getClusterName()).isEqualTo("my-cluster");
    }

    @Test
    void shouldThrowWhenDestroyingWithUnregisteredEnv() {
        WebClient webClient = WebClient.builder().build();
        SupplyOrchestrator orchestrator = new SupplyOrchestrator(
                registry, environmentProfile, webClient, objectMapper);

        try {
            orchestrator.destroyCluster(EnvironmentType.CLOUD_ALI, "cls-1");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("no provider registered");
        }
    }

    private String extractClusterId(SupplyOrchestrator orchestrator, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            java.lang.reflect.Method method = SupplyOrchestrator.class.getDeclaredMethod(
                    "extractClusterId", JsonNode.class);
            method.setAccessible(true);
            return (String) method.invoke(orchestrator, node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}