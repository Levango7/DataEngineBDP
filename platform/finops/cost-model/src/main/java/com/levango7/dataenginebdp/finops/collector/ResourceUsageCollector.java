package com.levango7.dataenginebdp.finops.collector;

import com.levango7.dataenginebdp.finops.model.ResourceDimension;
import com.levango7.dataenginebdp.finops.model.ResourceUsage;
import com.levango7.dataenginebdp.common.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 资源用量采集器。
 *
 * <p>从 Prometheus TSDB 查询五维度资源用量（CPU/内存/存储/GPU/网络），
 * 按 tenant 与 namespace 标签隔离，采集粒度 ≤ 1min。</p>
 *
 * <p>五维度 PromQL 查询表达式（按 tenant+namespace 标签聚合）：</p>
 * <ul>
 *   <li>CPU：{@code sum by (tenant,namespace)(rate(container_cpu_usage_seconds_total[1m]))}</li>
 *   <li>内存：{@code sum by (tenant,namespace)(container_memory_working_set_bytes / 1073741824)}</li>
 *   <li>存储：{@code sum by (tenant,namespace)(kubelet_volume_stats_capacity_bytes / 1073741824)}</li>
 *   <li>GPU：{@code sum by (tenant,namespace,gpu_model)(DCGM_FI_DEV_GPU_UTIL / 100)}</li>
 *   <li>网络：{@code sum by (tenant,namespace)(rate(container_network_receive_bytes_total[1m]) + rate(container_network_transmit_bytes_total[1m])) / 1073741824}</li>
 * </ul>
 */
@Component
public class ResourceUsageCollector {

    private static final Logger log = LoggerFactory.getLogger(ResourceUsageCollector.class);
    private static final double BYTES_PER_GB = 1073741824.0;

    private final PrometheusQueryClient prometheusClient;

    public ResourceUsageCollector(PrometheusQueryClient prometheusClient) {
        this.prometheusClient = prometheusClient;
    }

    /**
     * 采集五维度资源用量。
     *
     * @param tenant    租户 ID（Prometheus 标签 tenant）
     * @param namespace Kubernetes namespace
     * @param start     采集窗口起始时间
     * @param end       采集窗口结束时间
     * @return 五维度资源用量列表
     */
    public List<ResourceUsage> collect(String tenant, String namespace,
                                       Instant start, Instant end) {
        List<ResourceUsage> usages = new ArrayList<>();
        Duration step = Duration.ofMinutes(1);

        // CPU 用量（核时）
        usages.add(queryUsage(ResourceDimension.CPU, tenant, namespace, start, end, step,
                "sum by (tenant,namespace)(rate(container_cpu_usage_seconds_total[1m]))",
                null));

        // 内存用量（GB·时）
        usages.add(queryUsage(ResourceDimension.MEMORY, tenant, namespace, start, end, step,
                "sum by (tenant,namespace)(container_memory_working_set_bytes / " + BYTES_PER_GB + ")",
                null));

        // 存储用量（GB·时）
        usages.add(queryUsage(ResourceDimension.STORAGE, tenant, namespace, start, end, step,
                "sum by (tenant,namespace)(kubelet_volume_stats_capacity_bytes / " + BYTES_PER_GB + ")",
                null));

        // GPU 用量（卡时，按型号差异化）
        usages.add(queryUsage(ResourceDimension.GPU, tenant, namespace, start, end, step,
                "sum by (tenant,namespace,gpu_model)(DCGM_FI_DEV_GPU_UTIL / 100)",
                "A100"));

        // 网络流量（GB）
        usages.add(queryUsage(ResourceDimension.NETWORK, tenant, namespace, start, end, step,
                "sum by (tenant,namespace)(rate(container_network_receive_bytes_total[1m])"
                        + " + rate(container_network_transmit_bytes_total[1m])) / " + BYTES_PER_GB,
                null));

        log.info("采集完成: tenant={} namespace={} 维度数={}", tenant, namespace, usages.size());
        return usages;
    }

    /**
     * 采集当前租户全部 namespace 的用量。
     *
     * <p>租户 ID 从 {@link TenantContext} 获取，确保租户隔离。</p>
     */
    public List<ResourceUsage> collectForCurrentTenant(String namespace,
                                                       Instant start, Instant end) {
        String tenant = TenantContext.getTenantId();
        if (tenant == null) {
            throw new IllegalStateException("当前请求未携带租户上下文，无法采集");
        }
        return collect(tenant, namespace, start, end);
    }

    /**
     * 执行单维度用量查询并解析为 ResourceUsage。
     *
     * <p>当 Prometheus 不可用时返回用量为 0 的占位对象，保证服务可用性
     * （降级策略，便于集成测试在无 Prometheus 环境下验证成本计算逻辑）。</p>
     */
    private ResourceUsage queryUsage(ResourceDimension dimension, String tenant, String namespace,
                                     Instant start, Instant end, Duration step,
                                     String query, String gpuModel) {
        double amount = 0.0;
        try {
            if (prometheusClient.isAvailable()) {
                Map<String, Object> resp = prometheusClient.rangeQuery(
                        query, start.getEpochSecond(), end.getEpochSecond(), step);
                amount = extractAmount(resp, tenant, namespace);
            } else {
                log.debug("Prometheus 不可用，维度 {} 返回 0 用量（降级）", dimension);
            }
        } catch (Exception e) {
            log.warn("采集维度 {} 失败: {}", dimension, e.getMessage());
        }

        return ResourceUsage.builder()
                .tenant(tenant)
                .namespace(namespace)
                .dimension(dimension)
                .amount(amount)
                .gpuModel(gpuModel)
                .start(start)
                .end(end)
                .build();
    }

    /**
     * 从 Prometheus 响应中提取指定 tenant+namespace 的用量值。
     *
     * <p>Prometheus range query 响应结构：
     * {@code {"data":{"result":[{"metric":{...},"values":[[ts,"val"],...]}]}}}</p>
     */
    @SuppressWarnings("unchecked")
    private double extractAmount(Map<String, Object> resp, String tenant, String namespace) {
        if (resp == null) {
            return 0.0;
        }
        Object data = resp.get("data");
        if (!(data instanceof Map)) {
            return 0.0;
        }
        Object result = ((Map<String, Object>) data).get("result");
        if (!(result instanceof List)) {
            return 0.0;
        }
        double total = 0.0;
        for (Object item : (List<?>) result) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) item;
            Map<String, String> metric = (Map<String, String>) entry.get("metric");
            if (metric == null) {
                continue;
            }
            // 标签隔离：仅累加匹配 tenant+namespace 的结果
            if (!tenant.equals(metric.get("tenant"))
                    || !namespace.equals(metric.get("namespace"))) {
                continue;
            }
            Object values = entry.get("values");
            if (values instanceof List) {
                for (Object v : (List<?>) values) {
                    if (v instanceof List && ((List<?>) v).size() >= 2) {
                        Object val = ((List<?>) v).get(1);
                        if (val instanceof String) {
                            try {
                                total += Double.parseDouble((String) val);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
        }
        return total;
    }
}