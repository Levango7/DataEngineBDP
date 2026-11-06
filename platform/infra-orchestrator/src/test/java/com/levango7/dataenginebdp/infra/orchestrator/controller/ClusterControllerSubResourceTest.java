package com.levango7.dataenginebdp.infra.orchestrator.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.infra.orchestrator.model.ClusterInfo;
import com.levango7.dataenginebdp.infra.orchestrator.model.EnvironmentType;
import com.levango7.dataenginebdp.infra.orchestrator.service.K8sClientService;
import com.levango7.dataenginebdp.infra.orchestrator.service.ProviderRegistryService;
import com.levango7.dataenginebdp.infra.orchestrator.service.SupplyOrchestrator;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpecBuilder;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.api.model.events.v1.EventBuilder;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetricsBuilder;
import io.fabric8.kubernetes.api.model.MicroTime;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ClusterController} P0-1 集群子资源端点测试（nodes/components/cnis/events/summary/
 * network policies/scale summary/storage usage/HPA CRUD/PVC CRUD/snapshot）。
 *
 * <p>策略（既有 ClusterControllerTest 夹具的扩展）：SupplyOrchestrator / ProviderRegistryService /
 * K8sClientService 一律 Mockito mock，standalone MockMvc（不带 Spring Security 过滤器链，
 * @PreAuthorize 在 standalone 模式下不生效，租户守卫由 TenantContext ThreadLocal 直接驱动），
 * 每请求前设置租户上下文模拟 JwtAuthFilter 行为。</p>
 */
class ClusterControllerSubResourceTest {

    private static final String TENANT = "tenant-a";
    private static final String ENV = "XINCHANG";
    private static final String CLUSTER = "cluster-1";

    private SupplyOrchestrator orchestrator;
    private ProviderRegistryService providerRegistryService;
    private K8sClientService k8sClientService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orchestrator = mock(SupplyOrchestrator.class);
        providerRegistryService = mock(ProviderRegistryService.class);
        k8sClientService = mock(K8sClientService.class);
        ClusterController controller =
                new ClusterController(orchestrator, providerRegistryService, k8sClientService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        TenantContext.setTenantId(TENANT);
        when(orchestrator.getClusterInfo(any(), any())).thenReturn(ownedCluster(TENANT));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /* ============================ 读端点 ============================ */

    @Nested
    @DisplayName("GET .../nodes")
    class Nodes {

        @Test
        @DisplayName("happy path：200 + ClusterNode 形状（metrics 可用）")
        void shouldReturnNodesWithMetrics() throws Exception {
            when(k8sClientService.listNodes()).thenReturn(List.of(
                    new NodeBuilder()
                            .withNewMetadata().withName("n1")
                            .addToLabels("node-role.kubernetes.io/control-plane", "")
                            .endMetadata()
                            .withNewSpec().endSpec()
                            .withNewStatus()
                            .addNewCondition().withType("Ready").withStatus("True").endCondition()
                            .withCapacity(Map.of("cpu", new Quantity("8"), "memory", new Quantity("16Gi"),
                                    "pods", new Quantity("110")))
                            .endStatus()
                            .build()));
            when(k8sClientService.listNodeMetrics()).thenReturn(List.of(
                    new NodeMetricsBuilder()
                            .withNewMetadata().withName("n1").endMetadata()
                            .withUsage(Map.of("cpu", new Quantity("2"), "memory", new Quantity("4Gi")))
                            .build()));
            when(k8sClientService.listPods()).thenReturn(List.of(
                    new PodBuilder().withNewMetadata().withName("p1").withNamespace("default").endMetadata()
                            .withNewSpec().withNodeName("n1").endSpec()
                            .withNewStatus().withPhase("Running").endStatus().build()));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/nodes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("n1"))
                    .andExpect(jsonPath("$[0].roles[0]").value("master"))
                    .andExpect(jsonPath("$[0].status").value("Ready"))
                    .andExpect(jsonPath("$[0].cpuCapacity").value(8.0))
                    .andExpect(jsonPath("$[0].cpuUsed").value(2.0))
                    .andExpect(jsonPath("$[0].memUsed").value(4.0))
                    .andExpect(jsonPath("$[0].podCount").value(1))
                    .andExpect(jsonPath("$[0].podCapacity").value(110))
                    .andExpect(jsonPath("$[0].metricsAvailable").value(true));
        }

        @Test
        @DisplayName("metrics.k8s.io 不可用时 cpuUsed/memUsed 为 null（不编造数量）")
        void shouldReturnNullUsageWhenMetricsUnavailable() throws Exception {
            when(k8sClientService.listNodes()).thenReturn(List.of(
                    new NodeBuilder().withNewMetadata().withName("n1").endMetadata()
                            .withNewSpec().endSpec()
                            .withNewStatus().withCapacity(Map.of("cpu", new Quantity("4"))).endStatus()
                            .build()));
            when(k8sClientService.listNodeMetrics()).thenReturn(null);
            when(k8sClientService.listPods()).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/nodes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].cpuUsed").value(nullValue()))
                    .andExpect(jsonPath("$[0].memUsed").value(nullValue()))
                    .andExpect(jsonPath("$[0].metricsAvailable").value(false));
        }

        @Test
        @DisplayName("租户不属该集群：400 + cluster not found（R13 拒绝）")
        void shouldRejectTenantMismatch() throws Exception {
            when(orchestrator.getClusterInfo(any(), any())).thenReturn(ownedCluster("tenant-other"));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/nodes"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("cluster not found: " + CLUSTER));
        }

        @Test
        @DisplayName("非法 environment：400")
        void shouldRejectUnknownEnvironment() throws Exception {
            mockMvc.perform(get("/api/v1/clusters/NOPE/" + CLUSTER + "/nodes"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET .../components")
    class Components {

        @Test
        @DisplayName("happy path：200 + Deployment/StatefulSet/DaemonSet 合并")
        void shouldMergeWorkloadTypes() throws Exception {
            when(k8sClientService.listDeployments()).thenReturn(List.of(
                    new DeploymentBuilder()
                            .withNewMetadata().withName("web").withNamespace("default").endMetadata()
                            .withNewSpec().withReplicas(3)
                            .withNewTemplate().withNewSpec().addNewContainer().withImage("nginx:1.25")
                            .endContainer().endSpec().endTemplate()
                            .endSpec()
                            .withNewStatus().withReplicas(3).withReadyReplicas(3).endStatus()
                            .build()));
            when(k8sClientService.listStatefulSets()).thenReturn(List.of(
                    new StatefulSetBuilder()
                            .withNewMetadata().withName("db").withNamespace("default").endMetadata()
                            .withNewSpec().withReplicas(2)
                            .withNewTemplate().withNewSpec().addNewContainer().withImage("pg:16")
                            .endContainer().endSpec().endTemplate()
                            .endSpec()
                            .withNewStatus().withReplicas(2).withReadyReplicas(1).endStatus()
                            .build()));
            when(k8sClientService.listDaemonSets()).thenReturn(List.of(
                    new DaemonSetBuilder()
                            .withNewMetadata().withName("kube-flannel").withNamespace("kube-system")
                            .endMetadata()
                            .withNewSpec().withNewTemplate().withNewSpec().addNewContainer()
                            .withImage("flannel:v0.25").endContainer().endSpec().endTemplate()
                            .endSpec()
                            .withNewStatus().withDesiredNumberScheduled(3).withNumberReady(2).endStatus()
                            .build()));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/components"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("web"))
                    .andExpect(jsonPath("$[0].type").value("Deployment"))
                    .andExpect(jsonPath("$[0].status").value("healthy"))
                    .andExpect(jsonPath("$[0].meta").value("Deployment · default · 3/3"))
                    .andExpect(jsonPath("$[0].images[0]").value("nginx:1.25"))
                    .andExpect(jsonPath("$[1].name").value("db"))
                    .andExpect(jsonPath("$[1].status").value("warning"))
                    .andExpect(jsonPath("$[2].name").value("kube-flannel"))
                    .andExpect(jsonPath("$[2].type").value("DaemonSet"));
        }

        @Test
        @DisplayName("租户不属：400 + cluster not found")
        void shouldRejectTenantMismatch() throws Exception {
            when(orchestrator.getClusterInfo(any(), any())).thenReturn(ownedCluster("tenant-other"));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/components"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("cluster not found: " + CLUSTER));
        }
    }

    @Nested
    @DisplayName("GET .../network/cnis")
    class Cnis {

        @Test
        @DisplayName("happy path：集群真实探测到 CNI 时 detected=true")
        void shouldDetectCniFromCluster() throws Exception {
            when(k8sClientService.listDaemonSets()).thenReturn(List.of(
                    new DaemonSetBuilder().withNewMetadata().withName("calico-node")
                            .withNamespace("kube-system").endMetadata().build()));
            when(k8sClientService.listNodes()).thenReturn(List.of(
                    new NodeBuilder().withNewMetadata().withName("n1").endMetadata()
                            .withNewSpec().withPodCIDR("10.244.0.0/24").endSpec().build()));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/network/cnis"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cni").value("calico"))
                    .andExpect(jsonPath("$.detected").value(true))
                    .andExpect(jsonPath("$.nodePodCidrs[0]").value("10.244.0.0/24"))
                    .andExpect(jsonPath("$.cnis[0]").value("calico"))
                    .andExpect(jsonPath("$.mtu").value(1450));
        }

        @Test
        @DisplayName("未探测到 CNI 时沿用 k3s 默认常量并标注 source")
        void shouldFallBackToDefaultConstant() throws Exception {
            when(k8sClientService.listDaemonSets()).thenReturn(List.of());
            when(k8sClientService.listNodes()).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/network/cnis"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cni").value("flannel"))
                    .andExpect(jsonPath("$.detected").value(false))
                    .andExpect(jsonPath("$.podCidr").value("10.244.0.0/16"))
                    .andExpect(jsonPath("$.source.cni")
                            .value("default-constant（未探测到 CNI DaemonSet，沿用 k3s 默认 flannel）"));
        }

        @Test
        @DisplayName("租户不属：400")
        void shouldRejectTenantMismatch() throws Exception {
            when(orchestrator.getClusterInfo(any(), any())).thenReturn(ownedCluster("tenant-other"));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/network/cnis"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET .../scale/events")
    class ScaleEvents {

        @Test
        @DisplayName("happy path：200 + K8s Event 原始字段")
        void shouldReturnK8sEvents() throws Exception {
            when(k8sClientService.listEvents()).thenReturn(List.of(
                    event("Normal", "SuccessfulRescale", "New size: 3; reason: cpu above target",
                            "Deployment", "web", "2026-11-07T03:00:00.000Z")));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/scale/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].type").value("Normal"))
                    .andExpect(jsonPath("$[0].reason").value("SuccessfulRescale"))
                    .andExpect(jsonPath("$[0].objectKind").value("Deployment"))
                    .andExpect(jsonPath("$[0].objectName").value("web"))
                    .andExpect(jsonPath("$[0].message").value("New size: 3; reason: cpu above target"))
                    .andExpect(jsonPath("$[0].timestamp").value("2026-11-07T03:00:00.000Z"));
        }

        @Test
        @DisplayName("租户不属：400")
        void shouldRejectTenantMismatch() throws Exception {
            when(orchestrator.getClusterInfo(any(), any())).thenReturn(ownedCluster("tenant-other"));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/scale/events"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET .../scale/summary")
    class ScaleSummary {

        @Test
        @DisplayName("happy path：200 + 节点数/副本/Pending 聚合")
        void shouldAggregateSummary() throws Exception {
            when(k8sClientService.listNodes()).thenReturn(List.of(
                    new NodeBuilder().withNewMetadata().withName("n1").endMetadata().build()));
            when(k8sClientService.listDeployments()).thenReturn(List.of(
                    new DeploymentBuilder()
                            .withNewMetadata().withName("web").withNamespace("default").endMetadata()
                            .withNewSpec().withReplicas(3).endSpec()
                            .withNewStatus().withReplicas(3).withReadyReplicas(3).endStatus().build()));
            when(k8sClientService.listStatefulSets()).thenReturn(List.of(
                    new StatefulSetBuilder()
                            .withNewMetadata().withName("db").withNamespace("default").endMetadata()
                            .withNewSpec().withReplicas(2).endSpec()
                            .withNewStatus().withReplicas(2).withReadyReplicas(1).endStatus().build()));
            when(k8sClientService.listPods()).thenReturn(List.of(
                    new PodBuilder().withNewMetadata().withName("p1").withNamespace("default").endMetadata()
                            .withNewSpec().endSpec().withNewStatus().withPhase("Pending").endStatus().build(),
                    new PodBuilder().withNewMetadata().withName("p2").withNamespace("default").endMetadata()
                            .withNewSpec().endSpec().withNewStatus().withPhase("Running").endStatus().build()));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/scale/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nodeCount").value(1))
                    .andExpect(jsonPath("$.totalReplicas").value(5))
                    .andExpect(jsonPath("$.readyReplicas").value(4))
                    .andExpect(jsonPath("$.pendingPods").value(1))
                    .andExpect(jsonPath("$.podCount").value(2));
        }

        @Test
        @DisplayName("租户不属：400")
        void shouldRejectTenantMismatch() throws Exception {
            when(orchestrator.getClusterInfo(any(), any())).thenReturn(ownedCluster("tenant-other"));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/scale/summary"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("NetworkPolicy 端点")
    class NetworkPolicyEndpoints {

        @Test
        @DisplayName("GET .../network/policies：200 + 真实端口/选择器")
        void shouldListPoliciesWithRealPorts() throws Exception {
            when(k8sClientService.listNetworkPolicies()).thenReturn(List.of(
                    ingressPolicy()));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/network/policies"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("allow-web"))
                    .andExpect(jsonPath("$[0].namespace").value("default"))
                    .andExpect(jsonPath("$[0].type").value("ingress"))
                    .andExpect(jsonPath("$[0].ports[0]").value(80))
                    .andExpect(jsonPath("$[0].selector").value("app=web"));
        }

        @Test
        @DisplayName("POST .../network/policies：201 + 视图回显")
        void shouldCreatePolicy() throws Exception {
            when(k8sClientService.createNetworkPolicy(any())).thenAnswer(inv -> {
                NetworkPolicy built = inv.getArgument(0);
                // fabric8 创建响应回显（真实集群亦返回带 status 的完整对象）
                return built;
            });

            mockMvc.perform(post("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/network/policies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"allow-web\",\"namespace\":\"default\","
                                    + "\"type\":\"ingress\",\"ports\":[80,443],\"selector\":\"app=web\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("allow-web"))
                    .andExpect(jsonPath("$.namespace").value("default"))
                    .andExpect(jsonPath("$.type").value("ingress"))
                    .andExpect(jsonPath("$.ports[1]").value(443))
                    .andExpect(jsonPath("$.selector").value("app=web"));
        }

        @Test
        @DisplayName("POST 非法 type：400 + 明确错误")
        void shouldRejectInvalidType() throws Exception {
            mockMvc.perform(post("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/network/policies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"bad\",\"namespace\":\"default\","
                                    + "\"type\":\"whatever\",\"ports\":[80],\"selector\":\"app=web\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(
                            "invalid network policy type: whatever, supported: ingress|egress|both"));
        }

        @Test
        @DisplayName("DELETE 已存在策略：204")
        void shouldDeletePolicy() throws Exception {
            when(k8sClientService.listNetworkPolicies()).thenReturn(List.of(ingressPolicy()));
            when(k8sClientService.deleteNetworkPolicy("default", "allow-web")).thenReturn(true);

            mockMvc.perform(delete("/api/v1/clusters/" + ENV + "/" + CLUSTER
                    + "/network/policies/allow-web"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("DELETE 策略不存在：400 + networkpolicy not found")
        void should400WhenPolicyMissing() throws Exception {
            when(k8sClientService.listNetworkPolicies()).thenReturn(List.of());

            mockMvc.perform(delete("/api/v1/clusters/" + ENV + "/" + CLUSTER
                    + "/network/policies/ghost"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("networkpolicy not found: ghost"));
        }

        @Test
        @DisplayName("租户不属：400")
        void shouldRejectTenantMismatch() throws Exception {
            when(orchestrator.getClusterInfo(any(), any())).thenReturn(ownedCluster("tenant-other"));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/network/policies"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("HPA 端点")
    class HpaEndpoints {

        @Test
        @DisplayName("POST .../hpa：201 + 阈值回显")
        void shouldCreateHpa() throws Exception {
            when(k8sClientService.createHpa(any())).thenAnswer(inv -> inv.getArgument(0));

            mockMvc.perform(post("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/hpa")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"web-hpa\",\"namespace\":\"default\","
                                    + "\"targetDeployment\":\"web\",\"minReplicas\":2,"
                                    + "\"maxReplicas\":10,\"cpuThreshold\":80}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("web-hpa"))
                    .andExpect(jsonPath("$.targetDeployment").value("web"))
                    .andExpect(jsonPath("$.minReplicas").value(2))
                    .andExpect(jsonPath("$.maxReplicas").value(10))
                    .andExpect(jsonPath("$.cpuThreshold").value(80));
        }

        @Test
        @DisplayName("PUT .../hpa/{name}：200 + min/max 更新生效")
        void shouldUpdateHpa() throws Exception {
            when(k8sClientService.replaceHpa(any())).thenAnswer(inv -> inv.getArgument(0));

            mockMvc.perform(put("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/hpa/web-hpa")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"web-hpa\",\"namespace\":\"default\","
                                    + "\"targetDeployment\":\"web\",\"minReplicas\":3,"
                                    + "\"maxReplicas\":20,\"cpuThreshold\":70}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.minReplicas").value(3))
                    .andExpect(jsonPath("$.maxReplicas").value(20))
                    .andExpect(jsonPath("$.cpuThreshold").value(70));
        }

        @Test
        @DisplayName("PUT HPA 不存在（K8s 404）：400 + hpa not found")
        void should400WhenHpaMissingOnUpdate() throws Exception {
            when(k8sClientService.replaceHpa(any()))
                    .thenThrow(new KubernetesClientException("HTTP 404 Not Found", 404, null));

            mockMvc.perform(put("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/hpa/ghost")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"ghost\",\"namespace\":\"default\","
                                    + "\"targetDeployment\":\"web\","
                                    + "\"minReplicas\":1,\"maxReplicas\":5,\"cpuThreshold\":80}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("hpa not found: ghost"));
        }

        @Test
        @DisplayName("DELETE 已存在 HPA：204（命名空间按 name 解析）")
        void shouldDeleteHpa() throws Exception {
            when(k8sClientService.listHpas()).thenReturn(List.of(hpa("web-hpa")));
            when(k8sClientService.deleteHpa("default", "web-hpa")).thenReturn(true);

            mockMvc.perform(delete("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/hpa/web-hpa"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("DELETE HPA 不存在：400 + hpa not found")
        void should400WhenHpaMissingOnDelete() throws Exception {
            when(k8sClientService.listHpas()).thenReturn(List.of());

            mockMvc.perform(delete("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/hpa/ghost"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("hpa not found: ghost"));
        }

        @Test
        @DisplayName("minReplicas > maxReplicas：400")
        void shouldRejectMinGreaterThanMax() throws Exception {
            mockMvc.perform(post("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/hpa")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"bad\",\"namespace\":\"default\","
                                    + "\"targetDeployment\":\"web\",\"minReplicas\":10,"
                                    + "\"maxReplicas\":2,\"cpuThreshold\":80}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error")
                            .value("minReplicas must not exceed maxReplicas: 10>2"));
        }

        @Test
        @DisplayName("租户不属：400")
        void shouldRejectTenantMismatch() throws Exception {
            when(orchestrator.getClusterInfo(any(), any())).thenReturn(ownedCluster("tenant-other"));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/hpa"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("存储端点（usage / PVC / snapshot）")
    class StorageEndpoints {

        @Test
        @DisplayName("GET .../storage/usage：200 + requested/allocated 字节口径")
        void shouldAggregateStorageUsage() throws Exception {
            when(k8sClientService.listPersistentVolumeClaims()).thenReturn(List.of(
                    pvc("data", "local-path", "100Gi", "100Gi"),
                    pvc("logs", "nfs", "50Gi", null)));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/storage/usage"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalCapacityBytes").value(150L * 1024 * 1024 * 1024))
                    .andExpect(jsonPath("$.usedCapacityBytes").value(100L * 1024 * 1024 * 1024))
                    .andExpect(jsonPath("$.byStorageClass[0].name").value("local-path"))
                    .andExpect(jsonPath("$.byStorageClass[0].capacity").value(100L * 1024 * 1024 * 1024))
                    .andExpect(jsonPath("$.byStorageClass[1].name").value("nfs"))
                    .andExpect(jsonPath("$.byStorageClass[1].used").value(0))
                    .andExpect(jsonPath("$.units").value("bytes"));
        }

        @Test
        @DisplayName("POST .../storage/pvcs：201")
        void shouldCreatePvc() throws Exception {
            when(k8sClientService.createPvc(any())).thenAnswer(inv -> inv.getArgument(0));

            mockMvc.perform(post("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/storage/pvcs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"data\",\"namespace\":\"default\","
                                    + "\"storageClassName\":\"local-path\",\"capacity\":\"100Gi\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("data"))
                    .andExpect(jsonPath("$.namespace").value("default"))
                    .andExpect(jsonPath("$.storageClassName").value("local-path"))
                    .andExpect(jsonPath("$.capacity").value("100Gi"));
        }

        @Test
        @DisplayName("POST 非法 capacity：400")
        void shouldRejectInvalidCapacity() throws Exception {
            mockMvc.perform(post("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/storage/pvcs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"data\",\"namespace\":\"default\","
                                    + "\"storageClassName\":\"local-path\",\"capacity\":\"not-a-size\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid capacity: not-a-size"));
        }

        @Test
        @DisplayName("DELETE 已存在 PVC：204")
        void shouldDeletePvc() throws Exception {
            when(k8sClientService.listPersistentVolumeClaims()).thenReturn(List.of(
                    pvc("data", "local-path", "100Gi", "100Gi")));
            when(k8sClientService.deletePvc("default", "data")).thenReturn(true);

            mockMvc.perform(delete("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/storage/pvcs/data"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("DELETE PVC 不存在：400 + pvc not found")
        void should400WhenPvcMissing() throws Exception {
            when(k8sClientService.listPersistentVolumeClaims()).thenReturn(List.of());

            mockMvc.perform(delete("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/storage/pvcs/ghost"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("pvc not found: ghost"));
        }

        @Test
        @DisplayName("POST snapshot：CRD 存在 + PVC 存在 → 201 + snapshotName")
        void shouldCreateSnapshotWhenCrdPresent() throws Exception {
            when(k8sClientService.volumeSnapshotCrdExists()).thenReturn(true);
            when(k8sClientService.listPersistentVolumeClaims()).thenReturn(List.of(
                    pvc("data", "local-path", "100Gi", "100Gi")));

            mockMvc.perform(post("/api/v1/clusters/" + ENV + "/" + CLUSTER
                    + "/storage/pvcs/data/snapshot"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.snapshotName")
                            .value(org.hamcrest.Matchers.startsWith("data-snapshot-")))
                    .andExpect(jsonPath("$.pvcName").value("data"))
                    .andExpect(jsonPath("$.namespace").value("default"));
        }

        @Test
        @DisplayName("POST snapshot：CRD 不存在 → 501 + 明确 error（诚实失败）")
        void should501WhenSnapshotCrdMissing() throws Exception {
            when(k8sClientService.volumeSnapshotCrdExists()).thenReturn(false);

            mockMvc.perform(post("/api/v1/clusters/" + ENV + "/" + CLUSTER
                    + "/storage/pvcs/data/snapshot"))
                    .andExpect(status().isNotImplemented())
                    .andExpect(jsonPath("$.error")
                            .value("VolumeSnapshot CRD (volumesnapshots.snapshot.storage.k8s.io) "
                                    + "not installed in this cluster; "
                                    + "snapshot capability unavailable"));
        }

        @Test
        @DisplayName("POST snapshot：CRD 存在但 PVC 不存在 → 400 + pvc not found")
        void should400WhenSnapshotSourcePvcMissing() throws Exception {
            when(k8sClientService.volumeSnapshotCrdExists()).thenReturn(true);
            when(k8sClientService.listPersistentVolumeClaims()).thenReturn(List.of());

            mockMvc.perform(post("/api/v1/clusters/" + ENV + "/" + CLUSTER
                    + "/storage/pvcs/ghost/snapshot"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("pvc not found: ghost"));
        }

        @Test
        @DisplayName("租户不属：400")
        void shouldRejectTenantMismatch() throws Exception {
            when(orchestrator.getClusterInfo(any(), any())).thenReturn(ownedCluster("tenant-other"));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/storage/usage"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("cluster not found: " + CLUSTER));
        }
    }

    @Nested
    @DisplayName("GET .../hpa / .../storage/pvcs（既有端点回归）")
    class ExistingEndpointsRegression {

        @Test
        @DisplayName("hpaToView 现在含 cpuThreshold/memoryThreshold（对 infra.ts 契约补齐）")
        void shouldIncludeThresholdsInExistingHpaView() throws Exception {
            when(k8sClientService.listHpas()).thenReturn(List.of(hpaWithMetrics()));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/hpa"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("web-hpa"))
                    .andExpect(jsonPath("$[0].cpuThreshold").value(80))
                    .andExpect(jsonPath("$[0].minReplicas").value(2));
        }

        @Test
        @DisplayName("storage/pvcs 既有端点不受影响：200")
        void shouldKeepExistingPvcsEndpoint() throws Exception {
            when(k8sClientService.listPersistentVolumeClaims()).thenReturn(List.of(
                    pvc("data", "local-path", "100Gi", "100Gi")));

            mockMvc.perform(get("/api/v1/clusters/" + ENV + "/" + CLUSTER + "/storage/pvcs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("data"))
                    .andExpect(jsonPath("$[0].capacity").value("100Gi"));
        }
    }

    /* ============================ 夹具 ============================ */

    private static ClusterInfo ownedCluster(String tenantId) {
        return ClusterInfo.builder()
                .clusterId(CLUSTER)
                .clusterName("cluster-1")
                .tenantId(tenantId)
                .environment(EnvironmentType.XINCHANG)
                .build();
    }

    private static NetworkPolicy ingressPolicy() {
        return new NetworkPolicyBuilder()
                .withNewMetadata().withName("allow-web").withNamespace("default").endMetadata()
                .withNewSpec()
                .withPodSelector(new io.fabric8.kubernetes.api.model.LabelSelectorBuilder()
                        .addToMatchLabels("app", "web").build())
                .addNewIngress()
                .addNewPort().withProtocol("TCP")
                .withPort(new io.fabric8.kubernetes.api.model.IntOrString(80)).endPort()
                .endIngress()
                .endSpec()
                .build();
    }

    private static HorizontalPodAutoscaler hpa(String name) {
        return new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withName(name).withNamespace("default").endMetadata()
                .withNewSpec()
                .withMinReplicas(2).withMaxReplicas(10)
                .withNewScaleTargetRef().withApiVersion("apps/v1").withKind("Deployment")
                .withName("web").endScaleTargetRef()
                .endSpec()
                .withNewStatus().withCurrentReplicas(2).endStatus()
                .build();
    }

    private static HorizontalPodAutoscaler hpaWithMetrics() {
        return new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withName("web-hpa").withNamespace("default").endMetadata()
                .withNewSpec()
                .withMinReplicas(2).withMaxReplicas(10)
                .withNewScaleTargetRef().withApiVersion("apps/v1").withKind("Deployment")
                .withName("web").endScaleTargetRef()
                .addToMetrics(new MetricSpecBuilder().withType("Resource")
                        .withNewResource().withName("cpu").withNewTarget()
                        .withType("Utilization").withAverageUtilization(80).endTarget()
                        .endResource().build())
                .endSpec()
                .withNewStatus().withCurrentReplicas(2).endStatus()
                .build();
    }

    private static io.fabric8.kubernetes.api.model.PersistentVolumeClaim pvc(
            String name, String sc, String requested, String allocated) {
        PersistentVolumeClaimBuilder b = new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName(name).withNamespace("default").endMetadata()
                .withNewSpec()
                .withStorageClassName(sc)
                .withNewResources()
                .withRequests(Map.of("storage", new Quantity(requested)))
                .endResources()
                .endSpec();
        if (allocated != null) {
            b.withNewStatus().withPhase("Bound")
                    .withCapacity(Map.of("storage", new Quantity(allocated))).endStatus();
        } else {
            b.withNewStatus().withPhase("Pending").endStatus();
        }
        return b.build();
    }

    private static Event event(String type, String reason, String message, String objKind,
                               String objName, String time) {
        ObjectReference ref = new ObjectReferenceBuilder()
                .withKind(objKind).withName(objName).withNamespace("default").build();
        return new EventBuilder()
                .withNewMetadata().withName("evt-1").withNamespace("default").endMetadata()
                .withType(type).withReason(reason).withNote(message).withRegarding(ref)
                .withEventTime(new MicroTime(time))
                .build();
    }
}
