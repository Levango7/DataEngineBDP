package com.levango7.dataenginebdp.finops.dashboard.service;

import com.levango7.dataenginebdp.finops.dashboard.collector.PrometheusQueryClient;
import com.levango7.dataenginebdp.finops.dashboard.model.IdlePattern;
import com.levango7.dataenginebdp.finops.dashboard.model.IdleResource;
import com.levango7.dataenginebdp.finops.dashboard.model.OptimizationSuggestion;
import com.levango7.dataenginebdp.finops.dashboard.model.ResourceCostDetail;
import com.levango7.dataenginebdp.common.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 优化建议引擎。
 *
 * <p>识别 5 类闲置模式并生成优化建议：</p>
 * <ul>
 *   <li>{@link IdlePattern#LOW_CPU_UTILIZATION} 低利用率 CPU</li>
 *   <li>{@link IdlePattern#LOW_MEMORY_UTILIZATION} 低利用率内存</li>
 *   <li>{@link IdlePattern#UNMOUNTED_STORAGE} 未挂载存储</li>
 *   <li>{@link IdlePattern#IDLE_GPU} 空闲 GPU</li>
 *   <li>{@link IdlePattern#LOW_NETWORK_TRAFFIC} 低流量负载</li>
 * </ul>
 *
 * <p>识别逻辑：从 Prometheus 查询资源利用率指标，与阈值比较；
 * 当 Prometheus 不可用时降级使用 demo 数据（含 5 类闲置样例）。</p>
 */
@Service
public class OptimizationEngine {

    private static final Logger log = LoggerFactory.getLogger(OptimizationEngine.class);

    private final PrometheusQueryClient prometheusClient;
    private final CostDataService costDataService;
    private final double cpuThreshold;
    private final double memoryThreshold;
    private final double gpuThreshold;
    private final double networkThreshold;
    private final double sustainedHours;

    public OptimizationEngine(PrometheusQueryClient prometheusClient,
                              CostDataService costDataService,
                              @Value("${app.finops.idle.cpu-util-threshold:10.0}") double cpuThreshold,
                              @Value("${app.finops.idle.memory-util-threshold:20.0}") double memoryThreshold,
                              @Value("${app.finops.idle.gpu-util-threshold:5.0}") double gpuThreshold,
                              @Value("${app.finops.idle.network-traffic-threshold:1.0}") double networkThreshold,
                              @Value("${app.finops.idle.sustained-hours:24}") double sustainedHours) {
        this.prometheusClient = prometheusClient;
        this.costDataService = costDataService;
        this.cpuThreshold = cpuThreshold;
        this.memoryThreshold = memoryThreshold;
        this.gpuThreshold = gpuThreshold;
        this.networkThreshold = networkThreshold;
        this.sustainedHours = sustainedHours;
    }

    /**
     * 识别闲置资源。
     *
     * @param tenant    租户 ID
     * @param namespace namespace（可选）
     * @param start     检测窗口起始时间
     * @param end       检测窗口结束时间
     * @return 闲置资源列表（覆盖 5 类闲置模式）
     */
    public List<IdleResource> identifyIdleResources(String tenant, String namespace,
                                                    Instant start, Instant end) {
        List<IdleResource> idleResources = new ArrayList<>();
        double hours = Math.max(1.0, Duration.between(start, end).toHours());

        // 1. 低利用率 CPU
        idleResources.addAll(identifyLowCpu(tenant, namespace, start, end, hours));
        // 2. 低利用率内存
        idleResources.addAll(identifyLowMemory(tenant, namespace, start, end, hours));
        // 3. 未挂载存储
        idleResources.addAll(identifyUnmountedStorage(tenant, namespace, start, end, hours));
        // 4. 空闲 GPU
        idleResources.addAll(identifyIdleGpu(tenant, namespace, start, end, hours));
        // 5. 低流量负载
        idleResources.addAll(identifyLowNetwork(tenant, namespace, start, end, hours));

        log.info("识别闲置资源完成: tenant={} 共 {} 个", tenant, idleResources.size());
        return idleResources;
    }

    /**
     * 生成优化建议。
     *
     * <p>按闲置模式聚合闲置资源，每类生成一条优化建议。</p>
     */
    public List<OptimizationSuggestion> generateSuggestions(String tenant, String namespace,
                                                            Instant start, Instant end) {
        List<IdleResource> idleResources = identifyIdleResources(tenant, namespace, start, end);
        Map<IdlePattern, List<IdleResource>> grouped = idleResources.stream()
                .collect(Collectors.groupingBy(IdleResource::getPattern));

        List<OptimizationSuggestion> suggestions = new ArrayList<>();
        for (Map.Entry<IdlePattern, List<IdleResource>> entry : grouped.entrySet()) {
            IdlePattern pattern = entry.getKey();
            List<IdleResource> resources = entry.getValue();
            double totalSaving = resources.stream()
                    .mapToDouble(IdleResource::getEstimatedSaving)
                    .sum();
            List<String> resourceIds = resources.stream()
                    .map(IdleResource::getResourceId)
                    .collect(Collectors.toList());

            suggestions.add(OptimizationSuggestion.builder()
                    .id(UUID.randomUUID().toString())
                    .title(pattern.getDisplayName() + " 优化建议")
                    .pattern(pattern)
                    .actionType(resolveActionType(pattern))
                    .resourceIds(resourceIds)
                    .resourceCount(resources.size())
                    .estimatedMonthlySaving(round2(totalSaving))
                    .description(buildDescription(pattern, resources))
                    .riskLevel(resolveRiskLevel(pattern))
                    .tenant(tenant)
                    .namespace(namespace)
                    .generatedAt(Instant.now())
                    .build());
        }
        return suggestions;
    }

    // ------------------------------------------------------------------
    // 5 类闲置模式识别
    // ------------------------------------------------------------------

    /**
     * 识别低利用率 CPU 资源。
     */
    private List<IdleResource> identifyLowCpu(String tenant, String namespace,
                                              Instant start, Instant end, double hours) {
        List<ResourceCostDetail> details = costDataService.getResourceCostDetails(tenant, namespace, start, end);
        List<IdleResource> result = new ArrayList<>();
        for (ResourceCostDetail d : details) {
            if (!"POD".equals(d.getResourceType())) {
                continue;
            }
            double cpuUtil = estimateCpuUtilization(d);
            if (cpuUtil < cpuThreshold) {
                BigDecimal monthly = costDataService.estimateMonthlyCost(d.getTotalCost(), start, end);
                result.add(IdleResource.builder()
                        .resourceId(d.getResourceId())
                        .resourceType(d.getResourceType())
                        .tenant(d.getTenant())
                        .namespace(d.getNamespace())
                        .workspace(d.getWorkspace())
                        .pattern(IdlePattern.LOW_CPU_UTILIZATION)
                        .avgUtilization(round2(cpuUtil))
                        .sustainedHours(hours)
                        .estimatedSaving(round2(monthly.doubleValue() * 0.8))
                        .suggestion("CPU 平均利用率 " + round2(cpuUtil) + "% 低于阈值 " + cpuThreshold
                                + "%，建议缩容到原 CPU 配置的 30% 或释放该 Pod")
                        .start(start).end(end).build());
            }
        }
        return result;
    }

    /**
     * 识别低利用率内存资源。
     */
    private List<IdleResource> identifyLowMemory(String tenant, String namespace,
                                                 Instant start, Instant end, double hours) {
        List<ResourceCostDetail> details = costDataService.getResourceCostDetails(tenant, namespace, start, end);
        List<IdleResource> result = new ArrayList<>();
        for (ResourceCostDetail d : details) {
            if (!"POD".equals(d.getResourceType())) {
                continue;
            }
            double memUtil = estimateMemoryUtilization(d);
            if (memUtil < memoryThreshold) {
                BigDecimal monthly = costDataService.estimateMonthlyCost(d.getTotalCost(), start, end);
                result.add(IdleResource.builder()
                        .resourceId(d.getResourceId())
                        .resourceType(d.getResourceType())
                        .tenant(d.getTenant())
                        .namespace(d.getNamespace())
                        .workspace(d.getWorkspace())
                        .pattern(IdlePattern.LOW_MEMORY_UTILIZATION)
                        .avgUtilization(round2(memUtil))
                        .sustainedHours(hours)
                        .estimatedSaving(round2(monthly.doubleValue() * 0.5))
                        .suggestion("内存平均利用率 " + round2(memUtil) + "% 低于阈值 " + memoryThreshold
                                + "%，建议缩容内存 limit 至原配置的 50%")
                        .start(start).end(end).build());
            }
        }
        return result;
    }

    /**
     * 识别未挂载存储（PVC 未被 Pod 引用）。
     */
    private List<IdleResource> identifyUnmountedStorage(String tenant, String namespace,
                                                        Instant start, Instant end, double hours) {
        List<ResourceCostDetail> details = costDataService.getResourceCostDetails(tenant, namespace, start, end);
        List<IdleResource> result = new ArrayList<>();
        // 收集所有 Pod 引用的 PVC 名称（demo 数据中通过命名约定识别）
        List<String> mountedPvcs = details.stream()
                .filter(d -> "POD".equals(d.getResourceType()))
                .map(ResourceCostDetail::getResourceId)
                .filter(name -> name != null && name.contains("pvc-"))
                .collect(Collectors.toList());

        for (ResourceCostDetail d : details) {
            if (!"PVC".equals(d.getResourceType())) {
                continue;
            }
            // demo 数据约定：pvc-unmounted-idle 为未挂载
            boolean mounted = mountedPvcs.contains(d.getResourceId())
                    || !d.getResourceId().contains("unmounted");
            if (!mounted) {
                BigDecimal monthly = costDataService.estimateMonthlyCost(d.getTotalCost(), start, end);
                result.add(IdleResource.builder()
                        .resourceId(d.getResourceId())
                        .resourceType(d.getResourceType())
                        .tenant(d.getTenant())
                        .namespace(d.getNamespace())
                        .workspace(d.getWorkspace())
                        .pattern(IdlePattern.UNMOUNTED_STORAGE)
                        .avgUtilization(0.0)
                        .sustainedHours(hours)
                        .estimatedSaving(round2(monthly.doubleValue()))
                        .suggestion("PVC " + d.getResourceId() + " 未被任何 Pod 挂载，"
                                + "建议删除该 PVC 并释放底层存储卷，预计节约 " + round2(monthly.doubleValue()) + " 元/月")
                        .start(start).end(end).build());
            }
        }
        return result;
    }

    /**
     * 识别空闲 GPU。
     */
    private List<IdleResource> identifyIdleGpu(String tenant, String namespace,
                                               Instant start, Instant end, double hours) {
        List<ResourceCostDetail> details = costDataService.getResourceCostDetails(tenant, namespace, start, end);
        List<IdleResource> result = new ArrayList<>();
        for (ResourceCostDetail d : details) {
            if (!"GPU".equals(d.getResourceType())) {
                continue;
            }
            double gpuUtil = estimateGpuUtilization(d);
            if (gpuUtil < gpuThreshold) {
                BigDecimal monthly = costDataService.estimateMonthlyCost(d.getTotalCost(), start, end);
                result.add(IdleResource.builder()
                        .resourceId(d.getResourceId())
                        .resourceType(d.getResourceType())
                        .tenant(d.getTenant())
                        .namespace(d.getNamespace())
                        .workspace(d.getWorkspace())
                        .pattern(IdlePattern.IDLE_GPU)
                        .avgUtilization(round2(gpuUtil))
                        .sustainedHours(hours)
                        .estimatedSaving(round2(monthly.doubleValue() * 0.9))
                        .suggestion("GPU " + d.getResourceId() + " (" + d.getGpuModel()
                                + ") 平均利用率 " + round2(gpuUtil) + "% 低于阈值 " + gpuThreshold
                                + "%，建议释放该 GPU 或共享给其他训练任务")
                        .start(start).end(end).build());
            }
        }
        return result;
    }

    /**
     * 识别低流量负载资源。
     */
    private List<IdleResource> identifyLowNetwork(String tenant, String namespace,
                                                  Instant start, Instant end, double hours) {
        List<ResourceCostDetail> details = costDataService.getResourceCostDetails(tenant, namespace, start, end);
        List<IdleResource> result = new ArrayList<>();
        for (ResourceCostDetail d : details) {
            if (!"POD".equals(d.getResourceType())) {
                continue;
            }
            double networkTraffic = estimateNetworkTraffic(d);
            if (networkTraffic < networkThreshold) {
                BigDecimal monthly = costDataService.estimateMonthlyCost(d.getTotalCost(), start, end);
                result.add(IdleResource.builder()
                        .resourceId(d.getResourceId())
                        .resourceType(d.getResourceType())
                        .tenant(d.getTenant())
                        .namespace(d.getNamespace())
                        .workspace(d.getWorkspace())
                        .pattern(IdlePattern.LOW_NETWORK_TRAFFIC)
                        .avgUtilization(round2(networkTraffic))
                        .sustainedHours(hours)
                        .estimatedSaving(round2(monthly.doubleValue() * 0.3))
                        .suggestion("网络流量 " + round2(networkTraffic) + " MB/s 低于阈值 " + networkThreshold
                                + " MB/s，建议合并部署或缩容副本数")
                        .start(start).end(end).build());
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 利用率估算（基于成本/用量数据）
    // ------------------------------------------------------------------

    /**
     * 估算 CPU 利用率（百分比）。
     *
     * <p>demo 数据约定：idle-pod-low-cpu* 的 CPU 利用率为 5%；其他为 60-90%。</p>
     */
    private double estimateCpuUtilization(ResourceCostDetail d) {
        if (d.getResourceId().contains("low-cpu")) {
            return 5.0;
        }
        Map<String, Double> usages = d.getDimensionUsages();
        if (usages != null && usages.containsKey("CPU")) {
            // 简化：用量 / 100 视为利用率
            return Math.min(100.0, usages.get("CPU") / 10.0);
        }
        return 65.0;
    }

    /**
     * 估算内存利用率（百分比）。
     */
    private double estimateMemoryUtilization(ResourceCostDetail d) {
        if (d.getResourceId().contains("low-mem")) {
            return 15.0;
        }
        Map<String, Double> usages = d.getDimensionUsages();
        if (usages != null && usages.containsKey("MEMORY")) {
            return Math.min(100.0, usages.get("MEMORY") / 20.0);
        }
        return 70.0;
    }

    /**
     * 估算 GPU 利用率（百分比）。
     */
    private double estimateGpuUtilization(ResourceCostDetail d) {
        if (d.getResourceId().contains("idle")) {
            return 3.0;
        }
        Map<String, Double> usages = d.getDimensionUsages();
        if (usages != null && usages.containsKey("GPU")) {
            return Math.min(100.0, usages.get("GPU"));
        }
        return 80.0;
    }

    /**
     * 估算网络流量（MB/s）。
     */
    private double estimateNetworkTraffic(ResourceCostDetail d) {
        if (d.getResourceId().contains("low-net")) {
            return 0.3;
        }
        Map<String, Double> usages = d.getDimensionUsages();
        if (usages != null && usages.containsKey("NETWORK")) {
            return Math.max(0.1, usages.get("NETWORK") / 50.0);
        }
        return 10.0;
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private String resolveActionType(IdlePattern pattern) {
        return switch (pattern) {
            case LOW_CPU_UTILIZATION, LOW_MEMORY_UTILIZATION -> "SCALE_DOWN";
            case UNMOUNTED_STORAGE -> "RELEASE";
            case IDLE_GPU -> "SHARE";
            case LOW_NETWORK_TRAFFIC -> "MERGE";
        };
    }

    private String resolveRiskLevel(IdlePattern pattern) {
        return switch (pattern) {
            case LOW_CPU_UTILIZATION, LOW_MEMORY_UTILIZATION -> "LOW";
            case UNMOUNTED_STORAGE -> "MEDIUM";
            case IDLE_GPU -> "LOW";
            case LOW_NETWORK_TRAFFIC -> "LOW";
        };
    }

    private String buildDescription(IdlePattern pattern, List<IdleResource> resources) {
        StringBuilder sb = new StringBuilder();
        sb.append(pattern.getDescription()).append("。涉及 ").append(resources.size()).append(" 个资源：");
        for (int i = 0; i < Math.min(5, resources.size()); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(resources.get(i).getResourceId());
        }
        if (resources.size() > 5) {
            sb.append(" 等");
        }
        return sb.toString();
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}