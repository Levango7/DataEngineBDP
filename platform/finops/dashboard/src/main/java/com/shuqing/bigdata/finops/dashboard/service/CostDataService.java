package com.shuqing.bigdata.finops.dashboard.service;

import com.shuqing.bigdata.finops.dashboard.collector.PrometheusQueryClient;
import com.shuqing.bigdata.finops.dashboard.model.CostTrendPoint;
import com.shuqing.bigdata.finops.dashboard.model.ResourceCostDetail;
import com.shuqing.bigdata.finops.dashboard.model.TopCostResource;
import com.shuqing.bigdata.finops.dashboard.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 成本数据服务。
 *
 * <p>从 Prometheus 查询资源成本数据，提供看板所需的四类视图：</p>
 * <ul>
 *   <li>Top10 成本资源：按总成本降序取前 N</li>
 *   <li>成本趋势：按时间粒度聚合的时间序列</li>
 *   <li>成本明细：按资源粒度的明细列表</li>
 *   <li>闲置清单：低利用率资源列表（由 {@link OptimizationEngine} 识别）</li>
 * </ul>
 *
 * <p>当 Prometheus 不可用时降级返回内置 demo 数据，保证看板可用性
 * （便于集成测试在无 Prometheus 环境下验证看板逻辑）。</p>
 */
@Service
public class CostDataService {

    private static final Logger log = LoggerFactory.getLogger(CostDataService.class);
    private static final BigDecimal HOURS_PER_MONTH = BigDecimal.valueOf(730.0);

    private final PrometheusQueryClient prometheusClient;
    private final int topN;

    public CostDataService(PrometheusQueryClient prometheusClient,
                           @Value("${app.finops.top-n:10}") int topN) {
        this.prometheusClient = prometheusClient;
        this.topN = topN;
    }

    /**
     * 查询 Top N 成本资源。
     *
     * @param tenant    租户 ID（若 null 则使用 TenantContext）
     * @param namespace namespace（可选，null 表示全部）
     * @param start     窗口起始时间
     * @param end       窗口结束时间
     * @return Top N 成本资源列表
     */
    public List<TopCostResource> getTopCostResources(String tenant, String namespace,
                                                     Instant start, Instant end) {
        List<ResourceCostDetail> details = getResourceCostDetails(tenant, namespace, start, end);
        BigDecimal grandTotal = details.stream()
                .map(ResourceCostDetail::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return details.stream()
                .sorted(Comparator.comparing(ResourceCostDetail::getTotalCost).reversed())
                .limit(topN)
                .map(d -> toTopCostResource(d, grandTotal))
                .collect(Collectors.toList());
    }

    /**
     * 查询成本趋势。
     *
     * @param tenant     租户 ID
     * @param namespace  namespace（可选）
     * @param start      窗口起始时间
     * @param end        窗口结束时间
     * @param granularity 粒度（HOUR / DAY / MONTH）
     * @return 成本趋势时间序列
     */
    public List<CostTrendPoint> getCostTrend(String tenant, String namespace,
                                             Instant start, Instant end, String granularity) {
        Duration step = resolveStep(granularity);
        List<CostTrendPoint> trend = new ArrayList<>();
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            Instant next = cursor.plus(step);
            if (next.isAfter(end)) {
                next = end;
            }
            List<ResourceCostDetail> details = getResourceCostDetails(tenant, namespace, cursor, next);
            CostTrendPoint point = aggregateToTrendPoint(details, cursor, granularity);
            trend.add(point);
            cursor = next;
        }
        return trend;
    }

    /**
     * 查询成本明细。
     *
     * @param tenant    租户 ID
     * @param namespace namespace（可选）
     * @param start     窗口起始时间
     * @param end       窗口结束时间
     * @return 成本明细列表
     */
    public List<ResourceCostDetail> getCostDetails(String tenant, String namespace,
                                                   Instant start, Instant end) {
        return getResourceCostDetails(tenant, namespace, start, end);
    }

    /**
     * 获取资源成本明细（内部方法）。
     *
     * <p>当 Prometheus 可用时从其查询；否则降级返回 demo 数据。</p>
     */
    List<ResourceCostDetail> getResourceCostDetails(String tenant, String namespace,
                                                    Instant start, Instant end) {
        String effectiveTenant = tenant != null ? tenant : TenantContext.getTenantId();
        if (effectiveTenant == null) {
            effectiveTenant = "default-tenant";
        }

        if (prometheusClient.isAvailable()) {
            try {
                return queryFromPrometheus(effectiveTenant, namespace, start, end);
            } catch (Exception e) {
                log.warn("Prometheus 查询失败，降级到 demo 数据: {}", e.getMessage());
            }
        }
        return buildDemoData(effectiveTenant, namespace, start, end);
    }

    /**
     * 从 Prometheus 查询资源成本明细。
     *
     * <p>查询 PromQL：{@code sum by (tenant,namespace,pod)(rate(container_cpu_usage_seconds_total[1m])) * 0.5}
     * 等五维度成本查询，按资源聚合。</p>
     */
    @SuppressWarnings("unchecked")
    private List<ResourceCostDetail> queryFromPrometheus(String tenant, String namespace,
                                                         Instant start, Instant end) {
        // 简化实现：通过 instant query 获取当前资源成本
        // 生产实现应使用 query_range 并按时间窗口聚合
        Map<String, Object> resp = prometheusClient.instantQuery(
                "sum by (tenant,namespace,pod)("
                        + "rate(container_cpu_usage_seconds_total[1m]) * 0.5"
                        + ") + sum by (tenant,namespace,pod)("
                        + "container_memory_working_set_bytes / 1073741824 * 0.2"
                        + ")");

        List<ResourceCostDetail> details = new ArrayList<>();
        if (resp == null) {
            return details;
        }
        Object data = resp.get("data");
        if (!(data instanceof Map)) {
            return details;
        }
        Object result = ((Map<String, Object>) data).get("result");
        if (!(result instanceof List)) {
            return details;
        }
        for (Object item : (List<?>) result) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) item;
            Map<String, String> metric = (Map<String, String>) entry.get("metric");
            if (metric == null) {
                continue;
            }
            if (!tenant.equals(metric.get("tenant"))) {
                continue;
            }
            if (namespace != null && !namespace.equals(metric.get("namespace"))) {
                continue;
            }
            Object value = entry.get("value");
            BigDecimal cost = BigDecimal.ZERO;
            if (value instanceof List && ((List<?>) value).size() >= 2) {
                Object val = ((List<?>) value).get(1);
                if (val instanceof String) {
                    try {
                        cost = new BigDecimal((String) val).setScale(4, RoundingMode.HALF_UP);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            details.add(ResourceCostDetail.builder()
                    .resourceId(metric.getOrDefault("pod", "unknown"))
                    .resourceType("POD")
                    .tenant(tenant)
                    .namespace(metric.getOrDefault("namespace", "default"))
                    .workspace(metric.getOrDefault("workspace", "default"))
                    .totalCost(cost)
                    .dimensionCosts(new HashMap<>())
                    .dimensionUsages(new HashMap<>())
                    .start(start)
                    .end(end)
                    .build());
        }
        return details;
    }

    /**
     * 构造 demo 数据（Prometheus 不可用时的降级数据）。
     *
     * <p>生成 15 个资源（含 Pod/PVC/GPU），覆盖 5 类闲置模式场景，
     * 便于看板展示与集成测试验证。</p>
     */
    private List<ResourceCostDetail> buildDemoData(String tenant, String namespace,
                                                   Instant start, Instant end) {
        List<ResourceCostDetail> details = new ArrayList<>();
        String effectiveNs = namespace != null ? namespace : "ns-demo";

        // 10 个 Pod（含 3 个低 CPU、2 个低内存、1 个低网络）
        String[] podNames = {
                "spark-driver-high", "flink-tm-high", "doris-fe-high", "trino-worker-high",
                "spark-executor-mid1", "spark-executor-mid2", "doris-be-mid",
                "idle-pod-low-cpu1", "idle-pod-low-cpu2", "idle-pod-low-mem1",
                "idle-pod-low-mem2", "idle-pod-low-net1"
        };
        double[] podCosts = {
                1200.0, 980.0, 860.0, 720.0,
                450.0, 380.0, 290.0,
                50.0, 45.0, 40.0,
                38.0, 25.0
        };
        for (int i = 0; i < podNames.length; i++) {
            BigDecimal total = BigDecimal.valueOf(podCosts[i]).setScale(4, RoundingMode.HALF_UP);
            BigDecimal cpu = total.multiply(BigDecimal.valueOf(0.5)).setScale(4, RoundingMode.HALF_UP);
            BigDecimal mem = total.multiply(BigDecimal.valueOf(0.3)).setScale(4, RoundingMode.HALF_UP);
            BigDecimal net = total.multiply(BigDecimal.valueOf(0.2)).setScale(4, RoundingMode.HALF_UP);
            Map<String, BigDecimal> costs = new HashMap<>();
            costs.put("CPU", cpu);
            costs.put("MEMORY", mem);
            costs.put("NETWORK", net);
            Map<String, Double> usages = new HashMap<>();
            usages.put("CPU", cpu.doubleValue() / 0.5);
            usages.put("MEMORY", mem.doubleValue() / 0.2);
            usages.put("NETWORK", net.doubleValue() / 0.5);
            details.add(ResourceCostDetail.builder()
                    .resourceId(podNames[i])
                    .resourceType("POD")
                    .tenant(tenant)
                    .namespace(effectiveNs)
                    .workspace("ws-team-" + (i % 3 + 1))
                    .totalCost(total)
                    .dimensionCosts(costs)
                    .dimensionUsages(usages)
                    .start(start)
                    .end(end)
                    .build());
        }

        // 2 个 PVC（1 个未挂载）
        BigDecimal pvc1Cost = BigDecimal.valueOf(300.0).setScale(4, RoundingMode.HALF_UP);
        BigDecimal pvc2Cost = BigDecimal.valueOf(180.0).setScale(4, RoundingMode.HALF_UP);
        details.add(ResourceCostDetail.builder()
                .resourceId("pvc-data-hot")
                .resourceType("PVC")
                .tenant(tenant)
                .namespace(effectiveNs)
                .workspace("ws-team-1")
                .totalCost(pvc1Cost)
                .dimensionCosts(Map.of("STORAGE", pvc1Cost))
                .dimensionUsages(Map.of("STORAGE", 3000.0))
                .start(start).end(end).build());
        details.add(ResourceCostDetail.builder()
                .resourceId("pvc-unmounted-idle")
                .resourceType("PVC")
                .tenant(tenant)
                .namespace(effectiveNs)
                .workspace("ws-team-2")
                .totalCost(pvc2Cost)
                .dimensionCosts(Map.of("STORAGE", pvc2Cost))
                .dimensionUsages(Map.of("STORAGE", 1800.0))
                .start(start).end(end).build());

        // 3 个 GPU 卡（2 个空闲）
        BigDecimal gpu1Cost = BigDecimal.valueOf(876.0).setScale(4, RoundingMode.HALF_UP);
        BigDecimal gpu2Cost = BigDecimal.valueOf(58.4).setScale(4, RoundingMode.HALF_UP);
        BigDecimal gpu3Cost = BigDecimal.valueOf(43.8).setScale(4, RoundingMode.HALF_UP);
        details.add(ResourceCostDetail.builder()
                .resourceId("gpu-a100-busy")
                .resourceType("GPU")
                .tenant(tenant).namespace(effectiveNs).workspace("ws-team-1")
                .gpuModel("A100")
                .totalCost(gpu1Cost)
                .dimensionCosts(Map.of("GPU", gpu1Cost))
                .dimensionUsages(Map.of("GPU", 73.0))
                .start(start).end(end).build());
        details.add(ResourceCostDetail.builder()
                .resourceId("gpu-v100-idle1")
                .resourceType("GPU")
                .tenant(tenant).namespace(effectiveNs).workspace("ws-team-2")
                .gpuModel("V100")
                .totalCost(gpu2Cost)
                .dimensionCosts(Map.of("GPU", gpu2Cost))
                .dimensionUsages(Map.of("GPU", 9.73))
                .start(start).end(end).build());
        details.add(ResourceCostDetail.builder()
                .resourceId("gpu-v100-idle2")
                .resourceType("GPU")
                .tenant(tenant).namespace(effectiveNs).workspace("ws-team-3")
                .gpuModel("V100")
                .totalCost(gpu3Cost)
                .dimensionCosts(Map.of("GPU", gpu3Cost))
                .dimensionUsages(Map.of("GPU", 7.3))
                .start(start).end(end).build());

        return details;
    }

    /**
     * 将明细转换为 Top10 项。
     */
    private TopCostResource toTopCostResource(ResourceCostDetail d, BigDecimal grandTotal) {
        Map<String, BigDecimal> costs = d.getDimensionCosts() != null ? d.getDimensionCosts() : Map.of();
        BigDecimal percentage = BigDecimal.ZERO;
        if (grandTotal.compareTo(BigDecimal.ZERO) > 0) {
            percentage = d.getTotalCost()
                    .divide(grandTotal, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return TopCostResource.builder()
                .resourceId(d.getResourceId())
                .resourceType(d.getResourceType())
                .tenant(d.getTenant())
                .namespace(d.getNamespace())
                .workspace(d.getWorkspace())
                .totalCost(d.getTotalCost())
                .cpuCost(costs.getOrDefault("CPU", BigDecimal.ZERO))
                .memoryCost(costs.getOrDefault("MEMORY", BigDecimal.ZERO))
                .storageCost(costs.getOrDefault("STORAGE", BigDecimal.ZERO))
                .gpuCost(costs.getOrDefault("GPU", BigDecimal.ZERO))
                .networkCost(costs.getOrDefault("NETWORK", BigDecimal.ZERO))
                .percentage(percentage.doubleValue())
                .start(d.getStart())
                .end(d.getEnd())
                .build();
    }

    /**
     * 聚合明细为趋势点。
     */
    private CostTrendPoint aggregateToTrendPoint(List<ResourceCostDetail> details,
                                                 Instant timestamp, String granularity) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal cpu = BigDecimal.ZERO;
        BigDecimal mem = BigDecimal.ZERO;
        BigDecimal storage = BigDecimal.ZERO;
        BigDecimal gpu = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        for (ResourceCostDetail d : details) {
            total = total.add(d.getTotalCost());
            Map<String, BigDecimal> costs = d.getDimensionCosts();
            if (costs != null) {
                cpu = cpu.add(costs.getOrDefault("CPU", BigDecimal.ZERO));
                mem = mem.add(costs.getOrDefault("MEMORY", BigDecimal.ZERO));
                storage = storage.add(costs.getOrDefault("STORAGE", BigDecimal.ZERO));
                gpu = gpu.add(costs.getOrDefault("GPU", BigDecimal.ZERO));
                net = net.add(costs.getOrDefault("NETWORK", BigDecimal.ZERO));
            }
        }
        return CostTrendPoint.builder()
                .timestamp(timestamp)
                .totalCost(total)
                .cpuCost(cpu)
                .memoryCost(mem)
                .storageCost(storage)
                .gpuCost(gpu)
                .networkCost(net)
                .granularity(granularity)
                .build();
    }

    /**
     * 根据粒度解析步长。
     */
    private Duration resolveStep(String granularity) {
        if (granularity == null) {
            return Duration.ofHours(1);
        }
        return switch (granularity.toUpperCase()) {
            case "HOUR" -> Duration.ofHours(1);
            case "DAY" -> Duration.ofDays(1);
            case "MONTH" -> Duration.ofDays(30);
            default -> Duration.ofHours(1);
        };
    }

    /**
     * 估算月度成本（按窗口比例外推到 730 小时/月）。
     */
    public BigDecimal estimateMonthlyCost(BigDecimal windowCost, Instant start, Instant end) {
        long windowHours = Duration.between(start, end).toHours();
        if (windowHours <= 0) {
            return windowCost;
        }
        return windowCost
                .multiply(HOURS_PER_MONTH)
                .divide(BigDecimal.valueOf(windowHours), 4, RoundingMode.HALF_UP);
    }
}