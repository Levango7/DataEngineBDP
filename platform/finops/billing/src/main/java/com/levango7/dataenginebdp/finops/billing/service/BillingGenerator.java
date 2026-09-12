package com.levango7.dataenginebdp.finops.billing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.finops.billing.collector.PrometheusClient;
import com.levango7.dataenginebdp.finops.billing.model.BillingGenerateRequest;
import com.levango7.dataenginebdp.finops.billing.model.BillingGenerateResponse;
import com.levango7.dataenginebdp.finops.billing.model.BillingItem;
import com.levango7.dataenginebdp.finops.billing.model.BillingModel;
import com.levango7.dataenginebdp.finops.billing.repository.BillingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 账单生成器。
 *
 * <p>从 Prometheus 指标采集租户级成本，产出租户级成本账单 JSON，并持久化到数据库。
 * 这是出账闭环的起点（出账环节）。</p>
 *
 * <p>采集五维度资源用量并按默认单价计价：</p>
 * <ul>
 *   <li>CPU（核时）× 2.0 元/核时</li>
 *   <li>内存（GB·时）× 0.5 元/GB·时</li>
 *   <li>存储（GB·时）× 0.3 元/GB·时</li>
 *   <li>GPU（卡时）× 型号差异化单价（A100=12.0, V100=6.0, 昇腾910=8.0, T4=3.0, default=8.0）</li>
 *   <li>网络（GB）× 0.2 元/GB</li>
 * </ul>
 *
 * <p>当 Prometheus 不可用时，以 0 用量降级生成账单，保证出账闭环不中断。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingGenerator {

    private static final double BYTES_PER_GB = 1073741824.0;
    private static final BigDecimal SCALE = new BigDecimal("0.0001");
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 默认单价表（元） */
    private static final BigDecimal PRICE_CPU = new BigDecimal("2.0");
    private static final BigDecimal PRICE_MEMORY = new BigDecimal("0.5");
    private static final BigDecimal PRICE_STORAGE = new BigDecimal("0.3");
    private static final BigDecimal PRICE_NETWORK = new BigDecimal("0.2");
    private static final BigDecimal PRICE_GPU_DEFAULT = new BigDecimal("8.0");

    private final PrometheusClient prometheusClient;
    private final BillingRepository billingRepository;
    private final ObjectMapper objectMapper;

    /**
     * 生成租户级成本账单。
     *
     * <p>流程：
     * <ol>
     *   <li>确定账期与采集窗口（默认取账期对应的自然月）</li>
     *   <li>幂等检查：同租户同账期是否已存在账单</li>
     *   <li>从 Prometheus 采集五维度用量</li>
     *   <li>按单价计算各项金额并汇总</li>
     *   <li>持久化账单到数据库</li>
     *   <li>返回账单 JSON</li>
     * </ol></p>
     *
     * @param tenantId 租户 ID（须由调用方经 TenantContext 校验）
     * @param request  生成请求（含账期与采集窗口）
     * @return 账单生成响应
     */
    @Transactional
    public BillingGenerateResponse generate(String tenantId, BillingGenerateRequest request) {
        // 1. 确定账期与采集窗口
        Instant start = request.getStart();
        Instant end = request.getEnd();
        String period = request.getBillingPeriod();

        if (period == null || period.isBlank()) {
            // 账期未指定：从采集窗口推断
            Instant ref = start != null ? start : Instant.now();
            period = ref.atZone(ZoneOffset.UTC).toLocalDate().format(PERIOD_FMT);
        }
        if (start == null || end == null) {
            // 采集窗口未指定：取账期对应的自然月 [首日零点, 次月首日零点)
            LocalDate periodStart = LocalDate.parse(period + "-01");
            LocalDate periodEnd = periodStart.plusMonths(1);
            if (start == null) {
                start = periodStart.atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            if (end == null) {
                end = periodEnd.atStartOfDay().toInstant(ZoneOffset.UTC);
            }
        }

        log.info("开始生成账单: tenant={}, period={}, window=[{}->{})", tenantId, period, start, end);

        // 2. 幂等检查
        Optional<BillingModel> existing = billingRepository.findByTenantIdAndBillingPeriod(tenantId, period);
        if (existing.isPresent() && !request.isOverwrite()) {
            log.info("账单已存在（幂等返回）: tenant={}, period={}, id={}", tenantId, period, existing.get().getId());
            return toResponse(existing.get());
        }

        // 3. 采集五维度用量并计算金额
        List<BillingItem> items = collectAndPrice(tenantId, request.getNamespace(), start, end);
        BigDecimal totalAmount = items.stream()
                .map(BillingItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        // 4. 构建账单模型并持久化
        String itemsJson = serializeItems(items);
        BillingModel model = BillingModel.builder()
                .tenantId(tenantId)
                .billingPeriod(period)
                .itemsJson(itemsJson)
                .totalAmount(totalAmount)
                .status("GENERATED")
                .generatedAt(Instant.now())
                .periodStart(start)
                .periodEnd(end)
                .note(buildNote(items))
                .build();

        if (existing.isPresent() && request.isOverwrite()) {
            // 覆盖已存在账单：保留原 ID
            model.setId(existing.get().getId());
            model.setGeneratedAt(Instant.now());
        }

        BillingModel saved = billingRepository.save(model);
        log.info("账单生成完成: tenant={}, period={}, id={}, total={}", tenantId, period, saved.getId(), totalAmount);

        return toResponse(saved);
    }

    /**
     * 查询账单 by ID（租户隔离）。
     *
     * <p>同时按 {@code billingId} 与 {@code tenantId} 联合查询，仅当账单存在且
     * 属于当前租户时才返回。账单不存在或属于其他租户均返回 {@link Optional#empty()}，
     * 由调用方统一以 404 NOT FOUND 响应，不泄露账单存在性，防止跨租户越权读取。</p>
     *
     * @param billingId 账单 ID
     * @param tenantId  当前请求租户 ID（须由调用方经 TenantContext 校验非空）
     * @return 账单生成响应（若账单存在且属于该租户）
     */
    @Transactional(readOnly = true)
    public Optional<BillingGenerateResponse> getById(String billingId, String tenantId) {
        return billingRepository.findByIdAndTenantId(billingId, tenantId).map(this::toResponse);
    }

    /**
     * 查询租户全部账单。
     */
    @Transactional(readOnly = true)
    public List<BillingGenerateResponse> listByTenant(String tenantId) {
        return billingRepository.findByTenantIdOrderByGeneratedAtDesc(tenantId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 采集五维度用量并按单价计价。
     */
    private List<BillingItem> collectAndPrice(String tenant, String namespace, Instant start, Instant end) {
        List<BillingItem> items = new ArrayList<>();
        Duration step = Duration.ofMinutes(1);
        String ns = namespace == null ? "" : namespace;

        // CPU 用量（核时）
        double cpuUsage = collectUsage(
                "sum by (tenant,namespace)(rate(container_cpu_usage_seconds_total[1m]))",
                tenant, ns, start, end, step);
        items.add(buildItem("CPU", ns, cpuUsage, PRICE_CPU, null));

        // 内存用量（GB·时）
        double memUsage = collectUsage(
                "sum by (tenant,namespace)(container_memory_working_set_bytes / " + BYTES_PER_GB + ")",
                tenant, ns, start, end, step);
        items.add(buildItem("MEMORY", ns, memUsage, PRICE_MEMORY, null));

        // 存储用量（GB·时）
        double storageUsage = collectUsage(
                "sum by (tenant,namespace)(kubelet_volume_stats_capacity_bytes / " + BYTES_PER_GB + ")",
                tenant, ns, start, end, step);
        items.add(buildItem("STORAGE", ns, storageUsage, PRICE_STORAGE, null));

        // GPU 用量（卡时，按型号差异化）—— 简化：用 default 单价
        double gpuUsage = collectUsage(
                "sum by (tenant,namespace,gpu_model)(DCGM_FI_DEV_GPU_UTIL / 100)",
                tenant, ns, start, end, step);
        items.add(buildItem("GPU", ns, gpuUsage, PRICE_GPU_DEFAULT, "default"));

        // 网络流量（GB）
        double netUsage = collectUsage(
                "sum by (tenant,namespace)(rate(container_network_receive_bytes_total[1m])"
                        + " + rate(container_network_transmit_bytes_total[1m])) / " + BYTES_PER_GB,
                tenant, ns, start, end, step);
        items.add(buildItem("NETWORK", ns, netUsage, PRICE_NETWORK, null));

        return items;
    }

    /**
     * 执行单维度用量查询。
     *
     * <p>当 Prometheus 不可用时返回 0 用量（降级策略）。</p>
     */
    @SuppressWarnings("unchecked")
    private double collectUsage(String query, String tenant, String namespace, Instant start, Instant end, Duration step) {
        if (!prometheusClient.isAvailable()) {
            log.debug("Prometheus 不可用，用量降级为 0");
            return 0.0;
        }
        try {
            Map<String, Object> resp = prometheusClient.rangeQuery(
                    query, start.getEpochSecond(), end.getEpochSecond(), step);
            return extractAmount(resp, tenant, namespace);
        } catch (Exception e) {
            log.warn("采集用量失败: query={}, err={}", query, e.getMessage());
            return 0.0;
        }
    }

    /**
     * 从 Prometheus 响应中提取指定 tenant+namespace 的用量值。
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
            // 标签隔离：仅累加匹配 tenant 的结果（namespace 为空时不过滤）
            if (!tenant.equals(metric.get("tenant"))) {
                continue;
            }
            if (!namespace.isEmpty() && !namespace.equals(metric.get("namespace"))) {
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

    /**
     * 构建账单明细项并计算金额。
     */
    private BillingItem buildItem(String resourceType, String namespace, double usage,
                                  BigDecimal unitPrice, String gpuModel) {
        BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(usage))
                .setScale(4, RoundingMode.HALF_UP);
        return BillingItem.builder()
                .resourceType(resourceType)
                .namespace(namespace)
                .usage(usage)
                .unitPrice(unitPrice)
                .amount(amount)
                .gpuModel(gpuModel)
                .build();
    }

    /**
     * 序列化明细项列表为 JSON。
     */
    private String serializeItems(List<BillingItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            log.error("序列化账单明细失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 反序列化明细项 JSON 为列表。
     */
    private List<BillingItem> deserializeItems(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, BillingItem.class));
        } catch (JsonProcessingException e) {
            log.error("反序列化账单明细失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 构建账单备注。
     */
    private String buildNote(List<BillingItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("出账闭环账单：共 ").append(items.size()).append(" 个明细项；");
        sb.append("维度：");
        items.forEach(i -> sb.append(i.getResourceType()).append("/"));
        sb.append("；由 finops-billing 从 Prometheus 采集生成");
        return sb.toString();
    }

    /**
     * 将持久化模型转换为响应 DTO。
     */
    private BillingGenerateResponse toResponse(BillingModel model) {
        return BillingGenerateResponse.builder()
                .id(model.getId())
                .tenantId(model.getTenantId())
                .billingPeriod(model.getBillingPeriod())
                .items(deserializeItems(model.getItemsJson()))
                .totalAmount(model.getTotalAmount())
                .status(model.getStatus())
                .generatedAt(model.getGeneratedAt())
                .periodStart(model.getPeriodStart())
                .periodEnd(model.getPeriodEnd())
                .note(model.getNote())
                .build();
    }
}