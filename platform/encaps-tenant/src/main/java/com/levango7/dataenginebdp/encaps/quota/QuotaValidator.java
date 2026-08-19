package com.levango7.dataenginebdp.encaps.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 配额校验拦截器。
 *
 * <p>在资源创建前校验当前用量 + 新请求是否超过 Workspace 配额。
 * 通过 {@link K8sQuotaTranslator#getUsage(Long)} 读取 K8s ResourceQuota 的
 * {@code status.used} 与 {@code status.hard}，逐项比较。</p>
 *
 * <p>校验策略：</p>
 * <ul>
 *   <li>对每个 {@code resourceRequest} 中的键，若 {@code used + requested > hard} 则抛出
 *       {@link QuotaExceededException}</li>
 *   <li>数量型资源（pods/pvc/services）按整数比较</li>
 *   <li>容量型资源（cpu/memory/storage）按 K8s Quantity 解析后比较</li>
 *   <li>若 Workspace 未设置配额或 K8s ResourceQuota 不存在，跳过校验（允许创建）</li>
 * </ul>
 */
@Component
public class QuotaValidator {

    private static final Logger log = LoggerFactory.getLogger(QuotaValidator.class);

    private final K8sQuotaTranslator k8sTranslator;

    public QuotaValidator(K8sQuotaTranslator k8sTranslator) {
        this.k8sTranslator = k8sTranslator;
    }

    /**
     * 校验资源创建请求是否超过 Workspace 配额。
     *
     * @param workspaceId     Workspace ID
     * @param resourceRequest 新资源请求，键为 K8s ResourceQuota hard 键名
     *                        （如 {@code requests.cpu}/{@code pods}），值为请求量字符串
     * @throws QuotaExceededException 当超限时
     */
    public void validateQuota(Long workspaceId, Map<String, String> resourceRequest) {
        if (resourceRequest == null || resourceRequest.isEmpty()) {
            return;
        }
        Map<String, Map<String, String>> usage;
        try {
            usage = k8sTranslator.getUsage(workspaceId);
        } catch (K8sQuotaTranslator.K8sTranslationException e) {
            log.warn("Failed to query usage for workspace {}, skip quota validation: {}",
                    workspaceId, e.getMessage());
            return;
        }
        Map<String, String> used = usage.getOrDefault("used", Map.of());
        Map<String, String> hard = usage.getOrDefault("hard", Map.of());
        if (hard.isEmpty()) {
            // 未设置配额，允许创建
            return;
        }
        for (Map.Entry<String, String> entry : resourceRequest.entrySet()) {
            String key = entry.getKey();
            String requested = entry.getValue();
            if (requested == null || requested.isBlank()) {
                continue;
            }
            String hardValue = hard.get(key);
            if (hardValue == null || hardValue.isBlank()) {
                continue;
            }
            String usedValue = used.getOrDefault(key, "0");
            if (exceeds(usedValue, requested, hardValue, key)) {
                throw new QuotaExceededException(key, usedValue, hardValue, requested);
            }
        }
    }

    /**
     * 判断 {@code used + requested > hard}。
     *
     * <p>对数量型资源（pods/persistentvolumeclaims/services）按整数比较；
     * 对容量型资源（cpu/memory/storage）按 {@link QuantityComparator} 比较。</p>
     */
    private boolean exceeds(String used, String requested, String hard, String key) {
        if (isCountKey(key)) {
            try {
                long usedLong = parseLong(used);
                long reqLong = parseLong(requested);
                long hardLong = parseLong(hard);
                return usedLong + reqLong > hardLong;
            } catch (NumberFormatException e) {
                log.warn("Cannot parse count quota for key {}: used={}, requested={}, hard={}",
                        key, used, requested, hard);
                return false;
            }
        }
        return QuantityComparator.exceeds(used, requested, hard);
    }

    private boolean isCountKey(String key) {
        return K8sQuotaTranslator.KEY_PODS.equals(key)
                || K8sQuotaTranslator.KEY_PVC.equals(key)
                || K8sQuotaTranslator.KEY_SERVICES.equals(key);
    }

    private long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return 0L;
        }
        return Long.parseLong(s.trim());
    }
}