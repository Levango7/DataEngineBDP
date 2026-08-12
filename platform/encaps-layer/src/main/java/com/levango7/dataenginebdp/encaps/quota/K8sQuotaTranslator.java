package com.levango7.dataenginebdp.encaps.quota;

import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.LimitRangeBuilder;
import io.fabric8.kubernetes.api.model.LimitRangeItem;
import io.fabric8.kubernetes.api.model.LimitRangeItemBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.ResourceQuotaSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Quota → K8s 资源翻译核心。
 *
 * <p>封装层核心职责：将上层 Quota 抽象翻译为一组 K8s 资源原语，使客户无需感知 K8s 配额细节。
 * 翻译目标资源：</p>
 * <ul>
 *   <li>ResourceQuota — Workspace 级总量配额（CPU/内存/存储/Pod/PVC/Service）</li>
 *   <li>LimitRange — per-Container 限制（max/min CPU/内存）</li>
 * </ul>
 *
 * <p>翻译规则：</p>
 * <ul>
 *   <li>ResourceQuota 名称：{@code workspace-{workspaceId}-quota}</li>
 *   <li>LimitRange 名称：{@code workspace-{workspaceId}-limits}</li>
 *   <li>Namespace：通过 {@code namespaceResolver} 由 workspaceId 解析得到</li>
 * </ul>
 *
 * <p>使用 fabric8 {@link KubernetesClient} 操作 K8s API。所有 K8s API 调用均捕获
 * {@link KubernetesClientException} 并向上抛出 {@link K8sTranslationException}，
 * 由业务层决定回滚与状态流转。</p>
 */
@Component
public class K8sQuotaTranslator {

    private static final Logger log = LoggerFactory.getLogger(K8sQuotaTranslator.class);

    /** ResourceQuota 名称前缀：{@code workspace-{id}-quota} */
    public static final String RQ_NAME_PREFIX = "workspace-";
    public static final String RQ_NAME_SUFFIX = "-quota";
    /** LimitRange 名称前缀：{@code workspace-{id}-limits} */
    public static final String LR_NAME_SUFFIX = "-limits";
    /** LimitRange 类型：Container 级限制 */
    public static final String LR_TYPE_CONTAINER = "Container";
    /** K8s ResourceQuota hard 键名 */
    public static final String KEY_REQUESTS_CPU = "requests.cpu";
    public static final String KEY_REQUESTS_MEMORY = "requests.memory";
    public static final String KEY_REQUESTS_STORAGE = "requests.storage";
    public static final String KEY_PODS = "pods";
    public static final String KEY_PVC = "persistentvolumeclaims";
    public static final String KEY_SERVICES = "services";

    private final KubernetesClient k8sClient;
    private final NamespaceResolver namespaceResolver;

    /**
     * 构造翻译器。
     *
     * @param k8sClient         fabric8 KubernetesClient（可由测试注入 mock）
     * @param namespaceResolver Workspace ID → K8s Namespace 名称解析器
     */
    @Autowired
    public K8sQuotaTranslator(KubernetesClient k8sClient, NamespaceResolver namespaceResolver) {
        this.k8sClient = k8sClient;
        this.namespaceResolver = namespaceResolver;
    }

    /* ------------------------------ ResourceQuota ------------------------------ */

    /**
     * 创建 K8s ResourceQuota。
     *
     * <p>翻译规则：</p>
     * <pre>{@code
     * apiVersion: v1
     * kind: ResourceQuota
     * metadata:
     *   name: workspace-{workspaceId}-quota
     *   namespace: {workspace-namespace}
     * spec:
     *   hard:
     *     requests.cpu: {cpuLimit}
     *     requests.memory: {memoryLimit}
     *     requests.storage: {storageLimit}
     *     pods: {podLimit}
     *     persistentvolumeclaims: {pvcLimit}
     *     services: {serviceLimit}
     * }</pre>
     *
     * @param quota Quota 元数据
     * @return 已创建的 ResourceQuota
     * @throws K8sTranslationException K8s API 调用失败
     */
    public ResourceQuota createResourceQuota(Quota quota) {
        String ns = resolveNamespace(quota);
        String name = resourceQuotaName(quota);
        log.info("Creating K8s ResourceQuota {}/{} for quota {}", ns, name, quota.getId());
        try {
            Map<String, Quantity> hard = buildHard(quota);
            ResourceQuota rq = new ResourceQuotaBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                            .withName(name)
                            .withNamespace(ns)
                            .build())
                    .withSpec(new ResourceQuotaSpecBuilder()
                            .withHard(hard)
                            .build())
                    .build();
            return k8sClient.resourceQuotas().resource(rq).create();
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to create ResourceQuota " + ns + "/" + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * 更新 K8s ResourceQuota（server-side apply 替换 spec.hard）。
     *
     * @param quota Quota 元数据
     * @return 已更新的 ResourceQuota
     * @throws K8sTranslationException K8s API 调用失败
     */
    public ResourceQuota updateResourceQuota(Quota quota) {
        String ns = resolveNamespace(quota);
        String name = resourceQuotaName(quota);
        log.info("Updating K8s ResourceQuota {}/{} for quota {}", ns, name, quota.getId());
        try {
            Map<String, Quantity> hard = buildHard(quota);
            ResourceQuota rq = new ResourceQuotaBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                            .withName(name)
                            .withNamespace(ns)
                            .build())
                    .withSpec(new ResourceQuotaSpecBuilder()
                            .withHard(hard)
                            .build())
                    .build();
            return k8sClient.resourceQuotas().resource(rq).update();
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to update ResourceQuota " + ns + "/" + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * 删除 K8s ResourceQuota。
     *
     * @param quota Quota 元数据
     * @return true 表示已删除；false 表示 ResourceQuota 不存在
     * @throws K8sTranslationException K8s API 调用失败
     */
    public boolean deleteResourceQuota(Quota quota) {
        String ns = resolveNamespace(quota);
        String name = resourceQuotaName(quota);
        log.info("Deleting K8s ResourceQuota {}/{} for quota {}", ns, name, quota.getId());
        try {
            ResourceQuota existing = k8sClient.resourceQuotas().inNamespace(ns).withName(name).get();
            if (existing == null) {
                log.info("ResourceQuota {}/{} does not exist, skip deletion", ns, name);
                return false;
            }
            k8sClient.resourceQuotas().inNamespace(ns).withName(name).delete();
            return true;
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to delete ResourceQuota " + ns + "/" + name + ": " + e.getMessage(), e);
        }
    }

    /* ------------------------------ LimitRange ------------------------------ */

    /**
     * 创建 K8s LimitRange（per-Container 限制）。
     *
     * <p>翻译规则：</p>
     * <pre>{@code
     * apiVersion: v1
     * kind: LimitRange
     * metadata:
     *   name: workspace-{workspaceId}-limits
     *   namespace: {workspace-namespace}
     * spec:
     *   limits:
     *   - type: Container
     *     max:
     *       cpu: {maxCpuPerPod}
     *       memory: {maxMemoryPerPod}
     *     min:
     *       cpu: {minCpuPerPod}
     *       memory: {minMemoryPerPod}
     * }</pre>
     *
     * <p>当 Quota 中 max/min 字段全部为空时，跳过 LimitRange 创建并返回 null。</p>
     *
     * @param quota Quota 元数据
     * @return 已创建的 LimitRange；若未配置 per-Pod 限制则返回 null
     * @throws K8sTranslationException K8s API 调用失败
     */
    public LimitRange createLimitRange(Quota quota) {
        if (!hasLimitRangeFields(quota)) {
            log.info("Quota {} has no LimitRange fields, skip LimitRange creation", quota.getId());
            return null;
        }
        String ns = resolveNamespace(quota);
        String name = limitRangeName(quota);
        log.info("Creating K8s LimitRange {}/{} for quota {}", ns, name, quota.getId());
        try {
            LimitRangeItem item = buildLimitRangeItem(quota);
            LimitRange lr = new LimitRangeBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                            .withName(name)
                            .withNamespace(ns)
                            .build())
                    .withSpec(new io.fabric8.kubernetes.api.model.LimitRangeSpecBuilder()
                            .withLimits(item)
                            .build())
                    .build();
            return k8sClient.limitRanges().resource(lr).create();
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to create LimitRange " + ns + "/" + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * 更新 K8s LimitRange。
     *
     * @param quota Quota 元数据
     * @return 已更新的 LimitRange；若未配置 per-Pod 限制则返回 null
     * @throws K8sTranslationException K8s API 调用失败
     */
    public LimitRange updateLimitRange(Quota quota) {
        if (!hasLimitRangeFields(quota)) {
            // 若更新后无 LimitRange 字段，尝试删除既有 LimitRange
            deleteLimitRange(quota);
            return null;
        }
        String ns = resolveNamespace(quota);
        String name = limitRangeName(quota);
        log.info("Updating K8s LimitRange {}/{} for quota {}", ns, name, quota.getId());
        try {
            LimitRangeItem item = buildLimitRangeItem(quota);
            LimitRange lr = new LimitRangeBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                            .withName(name)
                            .withNamespace(ns)
                            .build())
                    .withSpec(new io.fabric8.kubernetes.api.model.LimitRangeSpecBuilder()
                            .withLimits(item)
                            .build())
                    .build();
            return k8sClient.limitRanges().resource(lr).update();
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to update LimitRange " + ns + "/" + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * 删除 K8s LimitRange。
     *
     * @param quota Quota 元数据
     * @return true 表示已删除；false 表示 LimitRange 不存在或未配置
     * @throws K8sTranslationException K8s API 调用失败
     */
    public boolean deleteLimitRange(Quota quota) {
        String ns = resolveNamespace(quota);
        String name = limitRangeName(quota);
        log.info("Deleting K8s LimitRange {}/{} for quota {}", ns, name, quota.getId());
        try {
            LimitRange existing = k8sClient.limitRanges().inNamespace(ns).withName(name).get();
            if (existing == null) {
                log.info("LimitRange {}/{} does not exist, skip deletion", ns, name);
                return false;
            }
            k8sClient.limitRanges().inNamespace(ns).withName(name).delete();
            return true;
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to delete LimitRange " + ns + "/" + name + ": " + e.getMessage(), e);
        }
    }

    /* ------------------------------ 用量查询 ------------------------------ */

    /**
     * 查询 Workspace 当前资源用量（已用 / 配额）。
     *
     * <p>从 K8s ResourceQuota 的 {@code status.used} 与 {@code status.hard} 读取，
     * 返回 {@code used} 与 {@code hard} 两个 Map。</p>
     *
     * @param workspaceId Workspace ID
     * @return 用量信息 {@code {used: Map, hard: Map}}；若 ResourceQuota 不存在返回空 Map
     * @throws K8sTranslationException K8s API 调用失败
     */
    public Map<String, Map<String, String>> getUsage(Long workspaceId) {
        String ns = namespaceResolver.resolve(workspaceId);
        String name = RQ_NAME_PREFIX + workspaceId + RQ_NAME_SUFFIX;
        log.info("Querying K8s ResourceQuota usage {}/{}", ns, name);
        try {
            ResourceQuota rq = k8sClient.resourceQuotas().inNamespace(ns).withName(name).get();
            Map<String, String> used = new HashMap<>();
            Map<String, String> hard = new HashMap<>();
            if (rq != null && rq.getStatus() != null) {
                if (rq.getStatus().getUsed() != null) {
                    rq.getStatus().getUsed().forEach((k, v) -> used.put(k, v != null ? v.getAmount() : ""));
                }
                if (rq.getStatus().getHard() != null) {
                    rq.getStatus().getHard().forEach((k, v) -> hard.put(k, v != null ? v.getAmount() : ""));
                }
            }
            Map<String, Map<String, String>> result = new HashMap<>();
            result.put("used", used);
            result.put("hard", hard);
            return result;
        } catch (KubernetesClientException e) {
            throw new K8sTranslationException(
                    "Failed to query ResourceQuota usage " + ns + "/" + name + ": " + e.getMessage(), e);
        }
    }

    /* ------------------------------ 私有辅助 ------------------------------ */

    /**
     * 构建 ResourceQuota spec.hard Map。
     */
    private Map<String, Quantity> buildHard(Quota quota) {
        Map<String, Quantity> hard = new HashMap<>();
        hard.put(KEY_REQUESTS_CPU, new Quantity(quota.getCpuLimit()));
        hard.put(KEY_REQUESTS_MEMORY, new Quantity(quota.getMemoryLimit()));
        hard.put(KEY_REQUESTS_STORAGE, new Quantity(quota.getStorageLimit()));
        hard.put(KEY_PODS, new Quantity(quota.getPodLimit()));
        hard.put(KEY_PVC, new Quantity(quota.getPvcLimit()));
        hard.put(KEY_SERVICES, new Quantity(quota.getServiceLimit()));
        return hard;
    }

    /**
     * 构建 LimitRangeItem（Container 级 max/min）。
     */
    private LimitRangeItem buildLimitRangeItem(Quota quota) {
        LimitRangeItemBuilder builder = new LimitRangeItemBuilder().withType(LR_TYPE_CONTAINER);
        Map<String, Quantity> max = new HashMap<>();
        if (quota.getMaxCpuPerPod() != null && !quota.getMaxCpuPerPod().isBlank()) {
            max.put("cpu", new Quantity(quota.getMaxCpuPerPod()));
        }
        if (quota.getMaxMemoryPerPod() != null && !quota.getMaxMemoryPerPod().isBlank()) {
            max.put("memory", new Quantity(quota.getMaxMemoryPerPod()));
        }
        if (!max.isEmpty()) {
            builder.withMax(max);
        }
        Map<String, Quantity> min = new HashMap<>();
        if (quota.getMinCpuPerPod() != null && !quota.getMinCpuPerPod().isBlank()) {
            min.put("cpu", new Quantity(quota.getMinCpuPerPod()));
        }
        if (quota.getMinMemoryPerPod() != null && !quota.getMinMemoryPerPod().isBlank()) {
            min.put("memory", new Quantity(quota.getMinMemoryPerPod()));
        }
        if (!min.isEmpty()) {
            builder.withMin(min);
        }
        return builder.build();
    }

    /**
     * 判断 Quota 是否配置了 LimitRange 字段。
     */
    private boolean hasLimitRangeFields(Quota quota) {
        return isNotBlank(quota.getMaxCpuPerPod())
                || isNotBlank(quota.getMaxMemoryPerPod())
                || isNotBlank(quota.getMinCpuPerPod())
                || isNotBlank(quota.getMinMemoryPerPod());
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 解析 Workspace 对应 K8s Namespace。
     */
    private String resolveNamespace(Quota quota) {
        String ns = namespaceResolver.resolve(quota.getWorkspaceId());
        if (ns == null || ns.isBlank()) {
            throw new K8sTranslationException(
                    "Cannot resolve namespace for workspaceId=" + quota.getWorkspaceId(), null);
        }
        return ns;
    }

    /**
     * 生成 ResourceQuota 名称：{@code workspace-{workspaceId}-quota}。
     */
    private String resourceQuotaName(Quota quota) {
        return RQ_NAME_PREFIX + quota.getWorkspaceId() + RQ_NAME_SUFFIX;
    }

    /**
     * 生成 LimitRange 名称：{@code workspace-{workspaceId}-limits}。
     */
    private String limitRangeName(Quota quota) {
        return RQ_NAME_PREFIX + quota.getWorkspaceId() + LR_NAME_SUFFIX;
    }

    /**
     * K8s 翻译异常。
     *
     * <p>包装 {@link KubernetesClientException}，由业务层捕获后决定 Quota 状态流转
     * （如 SETTING → FAILED、DELETING → DELETED）。</p>
     */
    public static class K8sTranslationException extends RuntimeException {

        public K8sTranslationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}