package com.shuqing.bigdata.encaps.workspace;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.ResourceQuotaSpecBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeerBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicySpecBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Workspace → K8s 资源翻译核心。
 *
 * <p>封装层核心职责：将上层 Workspace 抽象翻译为一组 K8s 资源原语，使客户无需感知 K8s/容器编排细节。
 * 翻译目标资源：</p>
 * <ul>
 *   <li>Namespace — Workspace 隔离边界</li>
 *   <li>NetworkPolicy — 网络隔离（租户内 Workspace 互通，跨租户隔离）</li>
 *   <li>RoleBinding — RBAC（租户用户绑定到 Workspace 的 Role）</li>
 *   <li>ResourceQuota — CPU/内存/存储配额</li>
 * </ul>
 *
 * <p>删除 Workspace 时级联删除 Namespace，K8s 自动回收其下全部资源。</p>
 *
 * <p>使用 fabric8 {@link KubernetesClient} 操作 K8s API。所有 K8s API 调用均捕获
 * {@link KubernetesClientException} 并向上抛出 {@link K8sTranslationException}，
 * 由业务层决定回滚与状态流转。</p>
 */
@Component
public class K8sWorkspaceTranslator {

    private static final Logger log = LoggerFactory.getLogger(K8sWorkspaceTranslator.class);

    /** NetworkPolicy 名称：租户内互通策略 */
    public static final String NP_TENANT_INTERNAL = "allow-same-tenant";
    /** NetworkPolicy 名称：默认拒绝全部入站 */
    public static final String NP_DENY_ALL = "deny-all-ingress";
    /** RoleBinding 名称：租户用户 → workspace-admin */
    public static final String RB_TENANT_ADMIN = "tenant-admin-binding";
    /** ResourceQuota 名称 */
    public static final String RQ_NAME = "workspace-quota";
    /** K8s Role：workspace-admin */
    public static final String ROLE_WORKSPACE_ADMIN = "workspace-admin";
    /** K8s RoleBinding 的 Subject kind */
    public static final String SUBJECT_KIND_GROUP = "Group";
    /** RBAC API group */
    private static final String RBAC_API_GROUP = "rbac.authorization.k8s.io";
    /** Namespace 标签：managedBy */
    private static final String MANAGED_BY = "encaps-layer";

    private final KubernetesClient k8sClient;

    /**
     * 构造翻译器。
     *
     * @param k8sClient fabric8 KubernetesClient（可由测试注入 mock）
     */
    @Autowired
    public K8sWorkspaceTranslator(KubernetesClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    /**
     * 创建 K8s Namespace。
     *
     * <p>Namespace 名称使用 {@code workspace.namespace}，并打上 {@code tenantId} 与
     * {@code workspaceId} 标签便于反查。</p>
     *
     * @param workspace Workspace 元数据
     * @return 已创建的 Namespace
     * @throws K8sTranslationException K8s API 调用失败
     */
    public Namespace createNamespace(Workspace workspace) {
        String ns = workspace.getNamespace();
        log.info("Creating K8s Namespace {} for workspace {}", ns, workspace.getId());
        try {
            Namespace namespace = new NamespaceBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                            .withName(ns)
                            .withLabels(buildNamespaceLabels(workspace))
                            .build())
                    .build();
            return k8sClient.namespaces().resource(namespace).create();
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to create Namespace " + ns + ": " + e.getMessage(), e);
        }
    }

    /**
     * 创建 NetworkPolicy：租户内 Workspace 互通，跨租户隔离。
     *
     * <p>策略语义：</p>
     * <ul>
     *   <li>默认拒绝全部入站（{@code deny-all-ingress}）</li>
     *   <li>允许来自同租户其他 Namespace 的入站（{@code allow-same-tenant}，
     *       通过 namespaceSelector 匹配 {@code tenantId} 标签）</li>
     * </ul>
     *
     * @param workspace Workspace 元数据
     * @return 已创建的 NetworkPolicy（允许同租户入站）
     * @throws K8sTranslationException K8s API 调用失败
     */
    public NetworkPolicy createNetworkPolicy(Workspace workspace) {
        String ns = workspace.getNamespace();
        String tenantId = String.valueOf(workspace.getTenantId());
        log.info("Creating NetworkPolicy in Namespace {} for tenant {}", ns, tenantId);
        try {
            // 1) 默认拒绝全部入站
            NetworkPolicy denyAll = new NetworkPolicyBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                            .withName(NP_DENY_ALL)
                            .withNamespace(ns)
                            .build())
                    .withSpec(new NetworkPolicySpecBuilder()
                            .withPodSelector(new LabelSelectorBuilder().build())
                            .withIngress()
                            .withPolicyTypes("Ingress")
                            .build())
                    .build();
            k8sClient.network().networkPolicies().resource(denyAll).create();

            // 2) 允许同租户 Namespace 入站
            Map<String, String> tenantMatchLabels = new HashMap<>();
            tenantMatchLabels.put("tenantId", tenantId);
            LabelSelector tenantSelector = new LabelSelectorBuilder()
                    .withMatchLabels(tenantMatchLabels)
                    .build();
            NetworkPolicyPeer sameTenantPeer = new NetworkPolicyPeerBuilder()
                    .withNamespaceSelector(tenantSelector)
                    .build();
            NetworkPolicy allowSameTenant = new NetworkPolicyBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                            .withName(NP_TENANT_INTERNAL)
                            .withNamespace(ns)
                            .build())
                    .withSpec(new NetworkPolicySpecBuilder()
                            .withPodSelector(new LabelSelectorBuilder().build())
                            .withIngress(new NetworkPolicyIngressRuleBuilder()
                                    .withFrom(sameTenantPeer)
                                    .build())
                            .withPolicyTypes("Ingress")
                            .build())
                    .build();
            return k8sClient.network().networkPolicies().resource(allowSameTenant).create();
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to create NetworkPolicy in " + ns + ": " + e.getMessage(), e);
        }
    }

    /**
     * 创建 RoleBinding：将租户用户组绑定到 Workspace 的 {@code workspace-admin} Role。
     *
     * <p>Subject 为 {@code Group: tenant-<tenantId>-admins}，RoleRef 指向
     * 命名空间级 {@code workspace-admin} Role。</p>
     *
     * @param workspace Workspace 元数据
     * @return 已创建的 RoleBinding
     * @throws K8sTranslationException K8s API 调用失败
     */
    public RoleBinding createRBAC(Workspace workspace) {
        String ns = workspace.getNamespace();
        String tenantId = String.valueOf(workspace.getTenantId());
        String groupName = "tenant-" + tenantId + "-admins";
        log.info("Creating RoleBinding in Namespace {} for group {}", ns, groupName);
        try {
            RoleBinding roleBinding = new RoleBindingBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                            .withName(RB_TENANT_ADMIN)
                            .withNamespace(ns)
                            .build())
                    .withSubjects(new SubjectBuilder()
                            .withKind(SUBJECT_KIND_GROUP)
                            .withName(groupName)
                            .withApiGroup(RBAC_API_GROUP)
                            .build())
                    .withRoleRef(new RoleRefBuilder()
                            .withKind("Role")
                            .withName(ROLE_WORKSPACE_ADMIN)
                            .withApiGroup(RBAC_API_GROUP)
                            .build())
                    .build();
            return k8sClient.rbac().roleBindings().resource(roleBinding).create();
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to create RoleBinding in " + ns + ": " + e.getMessage(), e);
        }
    }

    /**
     * 创建 ResourceQuota：CPU/内存/存储配额。
     *
     * <p>从 {@code workspace.resourceQuota} 解析键值对（格式 {@code cpu=4,memory=8Gi,storage=100Gi}），
     * 生成 K8s {@link ResourceQuota}，键名加上 {@code requests.} 与 {@code limits.} 前缀。</p>
     *
     * @param workspace Workspace 元数据
     * @return 已创建的 ResourceQuota
     * @throws K8sTranslationException K8s API 调用失败或配额格式非法
     */
    public ResourceQuota createResourceQuota(Workspace workspace) {
        String ns = workspace.getNamespace();
        log.info("Creating ResourceQuota in Namespace {} with quota={}", ns, workspace.getResourceQuota());
        try {
            Map<String, Quantity> hard = parseQuota(workspace.getResourceQuota());
            ResourceQuota quota = new ResourceQuotaBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                            .withName(RQ_NAME)
                            .withNamespace(ns)
                            .build())
                    .withSpec(new ResourceQuotaSpecBuilder()
                            .withHard(hard)
                            .build())
                    .build();
            return k8sClient.resourceQuotas().resource(quota).create();
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to create ResourceQuota in " + ns + ": " + e.getMessage(), e);
        }
    }

    /**
     * 删除 K8s Namespace（级联删除其下全部资源）。
     *
     * <p>K8s 删除 Namespace 时会自动回收该 Namespace 下的 NetworkPolicy、RoleBinding、
     * ResourceQuota、Pod、Service 等全部资源，无需逐项删除。</p>
     *
     * @param workspace Workspace 元数据
     * @return true 表示已删除；false 表示 Namespace 不存在
     * @throws K8sTranslationException K8s API 调用失败
     */
    public boolean deleteNamespace(Workspace workspace) {
        String ns = workspace.getNamespace();
        log.info("Deleting K8s Namespace {} for workspace {}", ns, workspace.getId());
        try {
            Namespace existing = k8sClient.namespaces().withName(ns).get();
            if (existing == null) {
                log.info("Namespace {} does not exist, skip deletion", ns);
                return false;
            }
            k8sClient.namespaces().withName(ns).delete();
            return true;
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to delete Namespace " + ns + ": " + e.getMessage(), e);
        }
    }

    /**
     * 查询 K8s Namespace 是否存在及其状态。
     *
     * @param workspace Workspace 元数据
     * @return Namespace 状态字符串（{@code Active}/{@code Terminating}/{@code NotFound}）
     */
    public String getNamespaceStatus(Workspace workspace) {
        String ns = workspace.getNamespace();
        try {
            Namespace namespace = k8sClient.namespaces().withName(ns).get();
            if (namespace == null) {
                return "NotFound";
            }
            String phase = namespace.getStatus() != null && namespace.getStatus().getPhase() != null
                    ? namespace.getStatus().getPhase() : "Unknown";
            return phase;
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to query Namespace " + ns + ": " + e.getMessage(), e);
        }
    }

    /* ------------------------------ 私有辅助 ------------------------------ */

    /**
     * 构建 Namespace 标签：{@code tenantId}、{@code workspaceId}、{@code managedBy}。
     */
    private Map<String, String> buildNamespaceLabels(Workspace workspace) {
        Map<String, String> labels = new HashMap<>();
        labels.put("tenantId", String.valueOf(workspace.getTenantId()));
        labels.put("workspaceId", String.valueOf(workspace.getId()));
        labels.put("managedBy", MANAGED_BY);
        return labels;
    }

    /**
     * 解析配额字符串为 K8s Quantity Map。
     *
     * <p>输入格式：{@code cpu=4,memory=8Gi,storage=100Gi}。
     * 输出键名转换规则：</p>
     * <ul>
     *   <li>{@code cpu} → {@code requests.cpu} + {@code limits.cpu}</li>
     *   <li>{@code memory} → {@code requests.memory} + {@code limits.memory}</li>
     *   <li>其他键 → {@code requests.<key>}</li>
     * </ul>
     *
     * @param quotaStr 配额字符串
     * @return K8s Quantity Map
     */
    private Map<String, Quantity> parseQuota(String quotaStr) {
        Map<String, Quantity> hard = new HashMap<>();
        if (quotaStr == null || quotaStr.isBlank()) {
            return hard;
        }
        String[] pairs = quotaStr.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String key = kv[0].trim();
            String value = kv[1].trim();
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            // cpu 与 memory 同时设置 requests 与 limits
            if ("cpu".equals(key) || "memory".equals(key)) {
                hard.put("requests." + key, new Quantity(value));
                hard.put("limits." + key, new Quantity(value));
            } else {
                hard.put("requests." + key, new Quantity(value));
            }
        }
        return hard;
    }

    /**
     * K8s 翻译异常。
     *
     * <p>包装 {@link KubernetesClientException}，由业务层捕获后决定 Workspace 状态流转
     * （如 CREATING → FAILED、DELETING → DELETED）。</p>
     */
    public static class K8sTranslationException extends RuntimeException {

        public K8sTranslationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
