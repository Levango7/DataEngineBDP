package com.levango7.dataenginebdp.infra.orchestrator.service;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.NodeListBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.StatusDetails;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.apps.DeploymentListBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerList;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.api.model.events.v1.EventBuilder;
import io.fabric8.kubernetes.api.model.events.v1.EventList;
import io.fabric8.kubernetes.api.model.events.v1.EventListBuilder;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetricsList;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetricsListBuilder;
import io.fabric8.kubernetes.api.model.MicroTime;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.V1ApiextensionAPIGroupDSL;
import io.fabric8.kubernetes.client.V2AutoscalingAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AnyNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.ApiextensionsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AutoscalingAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MetricAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NetworkAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.NodeMetricOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.V1EventingAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link K8sClientService} P0-1 子资源读/写能力单测。
 *
 * <p>策略：沿用 {@code K8sClientServiceCloseTest} 夹具——以 mock 模式构造服务（不连真实集群），
 * 反射注入 mock 的 {@link KubernetesClient} 并取消 mockEnabled 短路，从而在不依赖真实集群的
 * 前提下验证 fabric8 DSL 调用链（读方法 list、写方法 create/delete/replace、CRD 探测、
 * genericKubernetesResources 快照创建）。</p>
 *
 * <p>为避免 deep-stubs 在泛型链（{@code inNamespace} 等）上的不确定性，一律显式逐层 mock
 * （fabric8 社区测试的标准做法）。</p>
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class K8sClientServiceSubResourceTest {

    private KubernetesClient client;
    private K8sClientService service;

    private void initWithMockClient() throws Exception {
        client = mock(KubernetesClient.class);
        service = new K8sClientService(true);
        inject(service, "client", client);
        inject(service, "mockEnabled", Boolean.FALSE);
    }

    @Nested
    @DisplayName("读方法：fabric8 DSL 接线")
    class Reads {

        @Test
        @DisplayName("listNodes/listDeployments/listPods 返回真实列表（跨 namespace）")
        void shouldListNodesWorkloadsAndPods() throws Exception {
            initWithMockClient();
            NonNamespaceOperation nodesOp = mock(NonNamespaceOperation.class);
            when(client.nodes()).thenReturn(nodesOp);
            NodeList nodeList = new NodeListBuilder()
                    .withItems(new NodeBuilder()
                            .withNewMetadata().withName("n1").endMetadata()
                            .build())
                    .build();
            when(nodesOp.list()).thenReturn(nodeList);

            AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
            MixedOperation deploymentsMixed = mock(MixedOperation.class);
            AnyNamespaceOperation deploymentsAny = mock(AnyNamespaceOperation.class);
            when(client.apps()).thenReturn(apps);
            when(apps.deployments()).thenReturn(deploymentsMixed);
            when(deploymentsMixed.inAnyNamespace()).thenReturn(deploymentsAny);
            when(deploymentsAny.list()).thenReturn(new DeploymentListBuilder()
                    .withItems(new DeploymentBuilder()
                            .withNewMetadata().withName("web").withNamespace("default")
                            .endMetadata().build())
                    .build());

            MixedOperation podsMixed = mock(MixedOperation.class);
            AnyNamespaceOperation podsAny = mock(AnyNamespaceOperation.class);
            when(client.pods()).thenReturn(podsMixed);
            when(podsMixed.inAnyNamespace()).thenReturn(podsAny);
            when(podsAny.list()).thenReturn(new PodListBuilder().withItems(
                    new PodBuilder().withNewMetadata().withName("p1")
                            .withNamespace("default").endMetadata()
                            .withNewSpec().withNodeName("n1").endSpec().build())
                    .build());

            List<Node> nodes = service.listNodes();
            List<Deployment> deployments = service.listDeployments();
            assertThat(nodes).hasSize(1);
            assertThat(nodes.get(0).getMetadata().getName()).isEqualTo("n1");
            assertThat(deployments).hasSize(1);
            assertThat(deployments.get(0).getMetadata().getName()).isEqualTo("web");
            assertThat(service.listPods().get(0).getSpec().getNodeName()).isEqualTo("n1");
            verify(nodesOp).list();
            verify(deploymentsAny).list();
            verify(podsAny).list();
        }

        @Test
        @DisplayName("listEvents 按 eventTime 倒序返回（service 内排序）")
        void shouldSortEventsByEventTimeDesc() throws Exception {
            initWithMockClient();
            io.fabric8.kubernetes.client.dsl.EventingAPIGroupDSL eventsDsl =
                    mock(io.fabric8.kubernetes.client.dsl.EventingAPIGroupDSL.class);
            V1EventingAPIGroupDSL eventsV1 = mock(V1EventingAPIGroupDSL.class);
            MixedOperation eventsMixed = mock(MixedOperation.class);
            AnyNamespaceOperation eventsAny = mock(AnyNamespaceOperation.class);
            when(client.events()).thenReturn(eventsDsl);
            when(eventsDsl.v1()).thenReturn(eventsV1);
            when(eventsV1.events()).thenReturn(eventsMixed);
            when(eventsMixed.inAnyNamespace()).thenReturn(eventsAny);
            when(eventsAny.list()).thenReturn(new EventListBuilder()
                    .withItems(
                            new EventBuilder()
                                    .withNewMetadata().withName("e-old").withNamespace("default")
                                    .endMetadata()
                                    .withEventTime(new MicroTime("2026-11-07T01:00:00.000Z"))
                                    .build(),
                            new EventBuilder()
                                    .withNewMetadata().withName("e-new").withNamespace("default")
                                    .endMetadata()
                                    .withEventTime(new MicroTime("2026-11-07T05:00:00.000Z"))
                                    .build(),
                            new EventBuilder()
                                    .withNewMetadata().withName("e-mid").withNamespace("default")
                                    .endMetadata()
                                    .withEventTime(new MicroTime("2026-11-07T03:00:00.000Z"))
                                    .build())
                    .build());

            List<String> names = service.listEvents().stream()
                    .map(e -> e.getMetadata().getName()).toList();
            assertThat(names).containsExactly("e-new", "e-mid", "e-old");
        }

        @Test
        @DisplayName("listNodeMetrics：metrics.k8s.io 可用时返回指标")
        void shouldReturnNodeMetricsWhenAvailable() throws Exception {
            initWithMockClient();
            MetricAPIGroupDSL top = mock(MetricAPIGroupDSL.class);
            NodeMetricOperation nodeMetric = mock(NodeMetricOperation.class);
            when(client.top()).thenReturn(top);
            when(top.nodes()).thenReturn(nodeMetric);
            when(nodeMetric.metrics()).thenReturn(new NodeMetricsListBuilder().withItems(
                    new io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetricsBuilder()
                            .withNewMetadata().withName("n1").endMetadata()
                            .withUsage(Map.of("cpu", new Quantity("2"))).build())
                    .build());

            assertThat(service.listNodeMetrics()).isNotNull();
            assertThat(service.listNodeMetrics()).hasSize(1);
        }

        @Test
        @DisplayName("listNodeMetrics：metrics.k8s.io 404（未部署 metrics-server）时返回 null（不抛）")
        void shouldReturnNullWhenMetricsApiMissing() throws Exception {
            initWithMockClient();
            MetricAPIGroupDSL top = mock(MetricAPIGroupDSL.class);
            NodeMetricOperation nodeMetric = mock(NodeMetricOperation.class);
            when(client.top()).thenReturn(top);
            when(top.nodes()).thenReturn(nodeMetric);
            when(nodeMetric.metrics())
                    .thenThrow(new KubernetesClientException("metrics.k8s.io 404", 404, null));

            assertThat(service.listNodeMetrics()).isNull();
        }

        @Test
        @DisplayName("mock 模式（未注入 client）读方法返回空列表 / metrics 返回 null")
        void shouldReturnEmptyInMockMode() {
            K8sClientService mockService = new K8sClientService(true);
            assertThat(mockService.listNodes()).isEmpty();
            assertThat(mockService.listPods()).isEmpty();
            assertThat(mockService.listEvents()).isEmpty();
            assertThat(mockService.listNodeMetrics()).isNull();
        }
    }

    @Nested
    @DisplayName("探测：VolumeSnapshot CRD")
    class SnapshotCrdProbe {

        @Test
        @DisplayName("CRD 存在 → true")
        void shouldReturnTrueWhenCrdPresent() throws Exception {
            initWithMockClient();
            ApiextensionsAPIGroupDSL apiextensions = mock(ApiextensionsAPIGroupDSL.class);
            V1ApiextensionAPIGroupDSL v1 = mock(V1ApiextensionAPIGroupDSL.class);
            NonNamespaceOperation crds = mock(NonNamespaceOperation.class);
            Resource crdRes = mock(Resource.class);
            when(client.apiextensions()).thenReturn(apiextensions);
            when(apiextensions.v1()).thenReturn(v1);
            when(v1.customResourceDefinitions()).thenReturn(crds);
            when(crds.withName("volumesnapshots.snapshot.storage.k8s.io")).thenReturn(crdRes);
            when(crdRes.get()).thenReturn(new CustomResourceDefinitionBuilder()
                    .withNewMetadata()
                    .withName("volumesnapshots.snapshot.storage.k8s.io").endMetadata()
                    .build());

            assertThat(service.volumeSnapshotCrdExists()).isTrue();
        }

        @Test
        @DisplayName("CRD 不存在 → false（调用方据此返回 501）")
        void shouldReturnFalseWhenCrdAbsent() throws Exception {
            initWithMockClient();
            ApiextensionsAPIGroupDSL apiextensions = mock(ApiextensionsAPIGroupDSL.class);
            V1ApiextensionAPIGroupDSL v1 = mock(V1ApiextensionAPIGroupDSL.class);
            NonNamespaceOperation crds = mock(NonNamespaceOperation.class);
            Resource crdRes = mock(Resource.class);
            when(client.apiextensions()).thenReturn(apiextensions);
            when(apiextensions.v1()).thenReturn(v1);
            when(v1.customResourceDefinitions()).thenReturn(crds);
            when(crds.withName("volumesnapshots.snapshot.storage.k8s.io")).thenReturn(crdRes);
            when(crdRes.get()).thenReturn(null);

            assertThat(service.volumeSnapshotCrdExists()).isFalse();
        }
    }

    @Nested
    @DisplayName("写方法：NetworkPolicy")
    class NetworkPolicyWrites {

        @Test
        @DisplayName("createNetworkPolicy 在请求的 namespace 创建并返回集群对象")
        void shouldCreateNetworkPolicyInRequestNamespace() throws Exception {
            initWithMockClient();
            NetworkAPIGroupDSL network = mock(NetworkAPIGroupDSL.class);
            MixedOperation npMixed = mock(MixedOperation.class);
            NonNamespaceOperation npNs = mock(NonNamespaceOperation.class);
            Resource npRes = mock(Resource.class);
            when(client.network()).thenReturn(network);
            when(network.networkPolicies()).thenReturn(npMixed);
            when(npMixed.inNamespace("default")).thenReturn(npNs);
            when(npNs.resource(any())).thenReturn(npRes);
            when(npRes.create()).thenReturn(POLICY);
            ArgumentCaptor<NetworkPolicy> captor = ArgumentCaptor.forClass(NetworkPolicy.class);

            assertThat(service.createNetworkPolicy(POLICY)).isSameAs(POLICY);
            verify(npNs).resource(captor.capture());
            assertThat(captor.getValue().getMetadata().getName()).isEqualTo("allow-web");
            assertThat(captor.getValue().getMetadata().getNamespace()).isEqualTo("default");
        }

        @Test
        @DisplayName("deleteNetworkPolicy：删除零条返回 false，成功返回 true")
        void shouldReturnDeleteResult() throws Exception {
            initWithMockClient();
            NetworkAPIGroupDSL network = mock(NetworkAPIGroupDSL.class);
            MixedOperation npMixed = mock(MixedOperation.class);
            NonNamespaceOperation npNs = mock(NonNamespaceOperation.class);
            Resource ghostRes = mock(Resource.class);
            Resource webRes = mock(Resource.class);
            when(client.network()).thenReturn(network);
            when(network.networkPolicies()).thenReturn(npMixed);
            when(npMixed.inNamespace("default")).thenReturn(npNs);
            when(npNs.withName("ghost")).thenReturn(ghostRes);
            when(ghostRes.delete()).thenReturn(List.of());
            when(npNs.withName("allow-web")).thenReturn(webRes);
            when(webRes.delete()).thenReturn(List.of(new StatusDetails()));

            assertThat(service.deleteNetworkPolicy("default", "ghost")).isFalse();
            assertThat(service.deleteNetworkPolicy("default", "allow-web")).isTrue();
        }
    }

    @Nested
    @DisplayName("写方法：HPA / PVC")
    class HpaAndPvcWrites {

        @Test
        @DisplayName("createHpa / replaceHpa 走 autoscaling().v2()")
        void shouldCreateAndReplaceHpa() throws Exception {
            initWithMockClient();
            AutoscalingAPIGroupDSL autoscaling = mock(AutoscalingAPIGroupDSL.class);
            V2AutoscalingAPIGroupDSL v2 = mock(V2AutoscalingAPIGroupDSL.class);
            MixedOperation hpaMixed = mock(MixedOperation.class);
            NonNamespaceOperation hpaNs = mock(NonNamespaceOperation.class);
            Resource hpaRes = mock(Resource.class);
            when(client.autoscaling()).thenReturn(autoscaling);
            when(autoscaling.v2()).thenReturn(v2);
            when(v2.horizontalPodAutoscalers()).thenReturn(hpaMixed);
            when(hpaMixed.inNamespace("default")).thenReturn(hpaNs);
            when(hpaNs.resource(any())).thenReturn(hpaRes);
            when(hpaRes.create()).thenReturn(HPA);
            when(hpaRes.replace()).thenReturn(HPA);

            assertThat(service.createHpa(HPA)).isSameAs(HPA);
            assertThat(service.replaceHpa(HPA)).isSameAs(HPA);
        }

        @Test
        @DisplayName("deleteHpa：空状态列表 → false")
        void shouldReturnDeleteHpaResult() throws Exception {
            initWithMockClient();
            AutoscalingAPIGroupDSL autoscaling = mock(AutoscalingAPIGroupDSL.class);
            V2AutoscalingAPIGroupDSL v2 = mock(V2AutoscalingAPIGroupDSL.class);
            MixedOperation hpaMixed = mock(MixedOperation.class);
            NonNamespaceOperation hpaNs = mock(NonNamespaceOperation.class);
            Resource hpaRes = mock(Resource.class);
            when(client.autoscaling()).thenReturn(autoscaling);
            when(autoscaling.v2()).thenReturn(v2);
            when(v2.horizontalPodAutoscalers()).thenReturn(hpaMixed);
            when(hpaMixed.inNamespace("default")).thenReturn(hpaNs);
            when(hpaNs.withName("ghost")).thenReturn(hpaRes);
            when(hpaRes.delete()).thenReturn(List.of());

            assertThat(service.deleteHpa("default", "ghost")).isFalse();
        }

        @Test
        @DisplayName("createPvc：persistentVolumeClaims().resource().create()")
        void shouldCreatePvc() throws Exception {
            initWithMockClient();
            MixedOperation pvcMixed = mock(MixedOperation.class);
            NonNamespaceOperation pvcNs = mock(NonNamespaceOperation.class);
            Resource pvcRes = mock(Resource.class);
            when(client.persistentVolumeClaims()).thenReturn(pvcMixed);
            when(pvcMixed.inNamespace("default")).thenReturn(pvcNs);
            when(pvcNs.resource(any())).thenReturn(pvcRes);
            when(pvcRes.create()).thenReturn(PVC);
            ArgumentCaptor<PersistentVolumeClaim> captor =
                    ArgumentCaptor.forClass(PersistentVolumeClaim.class);

            assertThat(service.createPvc(PVC)).isSameAs(PVC);
            verify(pvcNs).resource(captor.capture());
            assertThat(captor.getValue().getSpec().getStorageClassName()).isEqualTo("local-path");
        }

        @Test
        @DisplayName("mock 模式写操作：IllegalStateException（诚实失败，不假装创建成功）；CRD 探测返回 false")
        void shouldRefuseWritesInMockMode() {
            K8sClientService mockService = new K8sClientService(true);
            assertThatThrownBy(() -> mockService.createHpa(HPA))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("write refused");
            assertThatThrownBy(() -> mockService.deleteHpa("default", "x"))
                    .isInstanceOf(IllegalStateException.class);
            // CRD 探测不抛异常：无集群即无 CRD，如实返回 false（调用方据此返回 501）
            assertThat(mockService.volumeSnapshotCrdExists()).isFalse();
        }
    }

    @Nested
    @DisplayName("写方法：VolumeSnapshot 自定义资源")
    class SnapshotWrites {

        @Test
        @DisplayName("createVolumeSnapshot：genericKubernetesResources + spec.source/快照类")
        void shouldCreateVolumeSnapshotViaGenericResources() throws Exception {
            initWithMockClient();
            MixedOperation snapMixed = mock(MixedOperation.class);
            NonNamespaceOperation snapNs = mock(NonNamespaceOperation.class);
            Resource snapRes = mock(Resource.class);
            when(client.genericKubernetesResources(any(ResourceDefinitionContext.class)))
                    .thenReturn(snapMixed);
            when(snapMixed.inNamespace("default")).thenReturn(snapNs);
            when(snapNs.resource(any())).thenReturn(snapRes);
            when(snapRes.create()).thenReturn(CREATED_SNAPSHOT);

            GenericKubernetesResource result = service.createVolumeSnapshot(
                    "default", "data-pvc", "data-pvc-snapshot-20261107030000", "local-path");

            assertThat(result).isSameAs(CREATED_SNAPSHOT);
            ArgumentCaptor<ResourceDefinitionContext> ctxCap =
                    ArgumentCaptor.forClass(ResourceDefinitionContext.class);
            ArgumentCaptor<GenericKubernetesResource> resCap =
                    ArgumentCaptor.forClass(GenericKubernetesResource.class);
            verify(client).genericKubernetesResources(ctxCap.capture());
            verify(snapNs).resource(resCap.capture());

            ResourceDefinitionContext ctx = ctxCap.getValue();
            assertThat(ctx.getGroup()).isEqualTo("snapshot.storage.k8s.io");
            assertThat(ctx.getVersion()).isEqualTo("v1");
            assertThat(ctx.getKind()).isEqualTo("VolumeSnapshot");
            assertThat(ctx.getPlural()).isEqualTo("volumesnapshots");

            GenericKubernetesResource built = resCap.getValue();
            assertThat(built.getApiVersion()).isEqualTo("snapshot.storage.k8s.io/v1");
            assertThat(built.getKind()).isEqualTo("VolumeSnapshot");
            assertThat(built.getMetadata().getName()).isEqualTo("data-pvc-snapshot-20261107030000");
            assertThat(built.getMetadata().getNamespace()).isEqualTo("default");
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) built.getAdditionalProperties().get("spec");
            assertThat(spec).containsEntry("source", Map.of("persistentVolumeClaimName", "data-pvc"));
            assertThat(spec).containsEntry("volumeSnapshotClassName", "local-path");
        }
    }

    /* ============================ 夹具 ============================ */

    private static final NetworkPolicy POLICY = new NetworkPolicyBuilder()
            .withNewMetadata().withName("allow-web").withNamespace("default").endMetadata()
            .withNewSpec().withNewPodSelector()
            .addToMatchLabels("app", "web").endPodSelector().endSpec()
            .build();

    private static final HorizontalPodAutoscaler HPA = new HorizontalPodAutoscalerBuilder()
            .withNewMetadata().withName("web-hpa").withNamespace("default").endMetadata()
            .withNewSpec().withMinReplicas(2).withMaxReplicas(10)
            .withNewScaleTargetRef().withApiVersion("apps/v1").withKind("Deployment")
            .withName("web").endScaleTargetRef().endSpec()
            .build();

    private static final PersistentVolumeClaim PVC = new PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("data").withNamespace("default").endMetadata()
            .withNewSpec().withStorageClassName("local-path")
            .withNewResources().withRequests(Map.of("storage", new Quantity("100Gi")))
            .endResources().endSpec()
            .build();

    private static final GenericKubernetesResource CREATED_SNAPSHOT = new GenericKubernetesResource();

    /** 反射写入实例字段（含 final 字段，Java 17 允许非静态 final 字段注入）：沿用既有测试注入 client 的模式。 */
    private static void inject(K8sClientService service, String name, Object value) throws Exception {
        Field field = K8sClientService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
