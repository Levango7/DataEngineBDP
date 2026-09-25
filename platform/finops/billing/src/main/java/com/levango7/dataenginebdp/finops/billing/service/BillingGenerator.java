package com.levango7.dataenginebdp.finops.billing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.finops.billing.collector.PrometheusClient;
import com.levango7.dataenginebdp.finops.billing.model.BillingGenerateRequest;
import com.levango7.dataenginebdp.finops.billing.model.BillingGenerateResponse;
import com.levango7.dataenginebdp.finops.billing.model.BillingItem;
import com.levango7.dataenginebdp.finops.billing.model.BillingModel;
import com.levango7.dataenginebdp.finops.billing.model.UsageRecord;
import com.levango7.dataenginebdp.finops.billing.repository.BillingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>当 Prometheus 不可用或未采集到任何租户用量时序时，若请求中带有计量汇入数据
 * （{@link BillingGenerateRequest#getUsageData()}）则降级出账并标注 DEGRADED；
 * 否则**拒绝出账**（{@link BillingDataUnavailableException}）。0 元账单会被下游
 * 当作真实结算依据，属于资损风险，因此不再静默降级为 0。</p>
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

    /** 资源类型 → 内置单价（计量汇入项未带 amount/unitPrice 时查此表） */
    private static final Map<String, BigDecimal> DEFAULT_PRICE_BY_TYPE = Map.of(
            "CPU", PRICE_CPU,
            "MEMORY", PRICE_MEMORY,
            "STORAGE", PRICE_STORAGE,
            "NETWORK", PRICE_NETWORK,
            "GPU", PRICE_GPU_DEFAULT);

    /** 内置单价表支持的定价配置名（其他取值一律拒绝，避免静默用错价格） */
    private static final String DEFAULT_PRICING_CONFIG = "default";

    private final PrometheusClient prometheusClient;
    private final BillingRepository billingRepository;
    private final ObjectMapper objectMapper;

    /**
     * 无租户用量时序（Prometheus 有响应但无任何匹配序列）时是否允许出 0 元账单。
     *
     * <p>默认 false：宁可让出账失败并暴露标签配置问题，也不产出可被误当成结算
     * 依据的 0 元账单。存量闲置租户可通过 {@code app.billing.allow-empty-usage=true} 放开。</p>
     */
    @Value("${app.billing.allow-empty-usage:false}")
    boolean allowEmptyUsage;

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

        // 2. 定价配置校验：内置单价表只支持 default，其他配置名必须显式拒绝（历史缺陷：被静默忽略）
        String pricingConfig = request.getPricingConfigName();
        if (pricingConfig != null && !pricingConfig.isBlank()
                && !DEFAULT_PRICING_CONFIG.equals(pricingConfig)) {
            throw new BillingRequestInvalidException(
                    "不支持的定价配置: " + pricingConfig
                            + "（billing 内置单价表仅支持 " + DEFAULT_PRICING_CONFIG
                            + "；自定义定价需接入 cost-model 的 PricingConfig）");
        }

        // 3. 幂等检查
        Optional<BillingModel> existing = billingRepository.findByTenantIdAndBillingPeriod(tenantId, period);
        if (existing.isPresent() && !request.isOverwrite()) {
            log.info("账单已存在（幂等返回）: tenant={}, period={}, id={}", tenantId, period, existing.get().getId());
            return toResponse(existing.get());
        }

        // 4. 采集五维度用量并计算金额（Prometheus 不可用/无匹配时序时拒绝出 0 元账单）
        CollectionResult collected = collectAndPrice(tenantId, request.getNamespace(), start, end);
        List<BillingItem> items = collected.items();
        boolean degraded = collected.infraUsageMissing();

        List<UsageRecord> usageData = request.getUsageData();
        boolean hasUsageData = usageData != null && !usageData.isEmpty();
        if (hasUsageData) {
            items.addAll(priceUsageRecords(usageData, request.getNamespace()));
        }

        // Prometheus 侧一无所获：仅在计量汇入兜底或显式放开时才继续出账
        if (degraded && !hasUsageData && !allowEmptyUsage) {
            throw new BillingDataUnavailableException(
                    "未采集到租户 " + tenantId + " 的任何资源用量时序，拒绝生成 0 元账单"
                            + "（请检查 Prometheus 抓取配置与 tenant_id 标签注入；"
                            + "确需为闲置租户出 0 元账单请设置 app.billing.allow-empty-usage=true）");
        }

        BigDecimal totalAmount = items.stream()
                .map(BillingItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        // 5. 构建账单模型并持久化
        String itemsJson = serializeItems(items);
        BillingModel model = BillingModel.builder()
                .tenantId(tenantId)
                .billingPeriod(period)
                .itemsJson(itemsJson)
                .totalAmount(totalAmount)
                .status(degraded ? "GENERATED_DEGRADED" : "GENERATED")
                .generatedAt(Instant.now())
                .periodStart(start)
                .periodEnd(end)
                .note(buildNote(items, degraded, hasUsageData))
                .build();

        if (degraded) {
            log.warn("账单降级生成（缺少资源用量时序，仅含计量汇入项或全零）: tenant={}, period={}, items={}",
                    tenantId, period, items.size());
        }

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
     * 采集结果：五维度明细项 + 是否存在"资源用量缺失"。
     *
     * <p>{@code infraUsageMissing} 为 true 表示 Prometheus 不可用，或所有维度都没有
     * 任何一条匹配本租户的时序——此时用量不可信，需由调用方决定拒绝出账还是降级。</p>
     */
    private record CollectionResult(List<BillingItem> items, boolean infraUsageMissing) {
    }

    /**
     * 采集五维度用量并按单价计价。
     *
     * <p>PromQL 同时按 {@code tenant_id}（Prometheus relabel 从 namespace 标签注入）
     * 与 {@code tenant}（历史标签名）分组，保证两种标签约定都能取到数据；
     * 分组保留 namespace 以便按 workspace 过滤。</p>
     */
    private CollectionResult collectAndPrice(String tenant, String namespace, Instant start, Instant end) {
        List<BillingItem> items = new ArrayList<>();
        Duration step = Duration.ofMinutes(1);
        String ns = namespace == null ? "" : namespace;
        Collection c = new Collection(prometheusClient.isAvailable());

        // CPU 用量（核时）
        double cpuUsage = collectUsage(
                "sum by (tenant_id,tenant,namespace)(rate(container_cpu_usage_seconds_total[1m]))",
                tenant, ns, start, end, step, c);
        items.add(buildItem("CPU", ns, cpuUsage, PRICE_CPU, null));

        // 内存用量（GB·时）
        double memUsage = collectUsage(
                "sum by (tenant_id,tenant,namespace)(container_memory_working_set_bytes / " + BYTES_PER_GB + ")",
                tenant, ns, start, end, step, c);
        items.add(buildItem("MEMORY", ns, memUsage, PRICE_MEMORY, null));

        // 存储用量（GB·时）
        double storageUsage = collectUsage(
                "sum by (tenant_id,tenant,namespace)(kubelet_volume_stats_capacity_bytes / " + BYTES_PER_GB + ")",
                tenant, ns, start, end, step, c);
        items.add(buildItem("STORAGE", ns, storageUsage, PRICE_STORAGE, null));

        // GPU 用量（卡时，按型号差异化）—— 简化：用 default 单价
        double gpuUsage = collectUsage(
                "sum by (tenant_id,tenant,namespace,gpu_model)(DCGM_FI_DEV_GPU_UTIL / 100)",
                tenant, ns, start, end, step, c);
        items.add(buildItem("GPU", ns, gpuUsage, PRICE_GPU_DEFAULT, "default"));

        // 网络流量（GB）
        double netUsage = collectUsage(
                "sum by (tenant_id,tenant,namespace)(rate(container_network_receive_bytes_total[1m])"
                        + " + rate(container_network_transmit_bytes_total[1m])) / " + BYTES_PER_GB,
                tenant, ns, start, end, step, c);
        items.add(buildItem("NETWORK", ns, netUsage, PRICE_NETWORK, null));

        if (!c.available) {
            log.warn("Prometheus 不可用，资源用量维度全部缺失（tenant={}）", tenant);
        } else if (c.matchedSeries == 0) {
            log.warn("未采集到租户 {} 的任何用量时序（检查 tenant_id 标签注入与 namespace 约定）；"
                    + "不匹配时序数={}", tenant, c.mismatchedSeries);
        }
        return new CollectionResult(items, !c.available || c.matchedSeries == 0);
    }

    /** 单次账单采集的跨维度计数：用于区分"真的没用量"与"标签/抓取配置坏了"。 */
    private static final class Collection {
        private final boolean available;
        private int matchedSeries;
        private int mismatchedSeries;

        private Collection(boolean available) {
            this.available = available;
        }
    }

    /**
     * 执行单维度用量查询。
     *
     * <p>Prometheus 不可用或查询异常时返回 0，并把"无匹配时序"记录到 {@code c} 上，
     * 由 {@link #collectAndPrice} 汇总判断——不接受静默把故障当成 0 用量。</p>
     */
    @SuppressWarnings("unchecked")
    private double collectUsage(String query, String tenant, String namespace, Instant start, Instant end,
                                Duration step, Collection c) {
        if (!c.available) {
            log.debug("Prometheus 不可用，用量降级为 0");
            return 0.0;
        }
        try {
            Map<String, Object> resp = prometheusClient.rangeQuery(
                    query, start.getEpochSecond(), end.getEpochSecond(), step);
            return extractAmount(resp, tenant, namespace, c);
        } catch (Exception e) {
            log.warn("采集用量失败: query={}, err={}", query, e.getMessage());
            return 0.0;
        }
    }

    /**
     * 从 Prometheus 响应中提取指定 tenant+namespace 的用量值。
     */
    @SuppressWarnings("unchecked")
    private double extractAmount(Map<String, Object> resp, String tenant, String namespace, Collection c) {
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
            // 标签隔离：仅累加匹配本租户的结果（namespace 为空时不过滤）。
            // tenant_id 为 Prometheus relabel 从 namespace 标签注入的规范键；
            // tenant 为历史键名，两者取到即算匹配，取不到记为不匹配时序（可暴露标签配置问题）。
            if (!tenant.equals(tenantLabelOf(metric))) {
                c.mismatchedSeries++;
                continue;
            }
            if (!namespace.isEmpty() && !namespace.equals(metric.get("namespace"))) {
                c.mismatchedSeries++;
                continue;
            }
            c.matchedSeries++;
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
     * 取时序的租户标签值：优先 {@code tenant_id}（relabel 注入的规范键），回退 {@code tenant}。
     */
    private static String tenantLabelOf(Map<String, String> metric) {
        String byTenantId = metric.get("tenant_id");
        return byTenantId != null ? byTenantId : metric.get("tenant");
    }

    /**
     * 构建账单明细项并计算金额。
     */
    private BillingItem buildItem(String resourceType, String namespace, double usage,
                                  BigDecimal unitPrice, String gpuModel) {
        BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(usage))
                .setScale(4, RoundingMode.HALF_UP);
        return buildItem(resourceType, namespace, usage, unitPrice, amount, gpuModel);
    }

    /**
     * 构建账单明细项（金额已定，用于计量汇入项）。
     */
    private BillingItem buildItem(String resourceType, String namespace, double usage,
                                  BigDecimal unitPrice, BigDecimal amount, String gpuModel) {
        return buildItem(resourceType, namespace, usage, unitPrice, amount, gpuModel, null);
    }

    /**
     * 构建账单明细项（含来源引用，用于对账追溯）。
     */
    private BillingItem buildItem(String resourceType, String namespace, double usage,
                                  BigDecimal unitPrice, BigDecimal amount, String gpuModel, String sourceRef) {
        return BillingItem.builder()
                .resourceType(resourceType)
                .namespace(namespace)
                .usage(usage)
                .unitPrice(unitPrice)
                .amount(amount.setScale(4, RoundingMode.HALF_UP))
                .gpuModel(gpuModel)
                .sourceRef(sourceRef)
                .build();
    }

    /**
     * 为计量汇入项计价。
     *
     * <p>计价规则见 {@link UsageRecord}：amount 优先（上游已计价），否则用量 × 单价
     * （单价缺失时查内置表）。两者都定不了金额则抛 400，不静默按 0 元入账。</p>
     */
    private List<BillingItem> priceUsageRecords(List<UsageRecord> records, String defaultNamespace) {
        List<BillingItem> items = new ArrayList<>(records.size());
        for (UsageRecord record : records) {
            String type = record.getResourceType();
            double usage = record.getUsage() == null ? 0.0 : record.getUsage();
            String ns = record.getNamespace() != null ? record.getNamespace()
                    : (defaultNamespace == null ? "" : defaultNamespace);
            BigDecimal amount = record.getAmount();
            BigDecimal unitPrice = record.getUnitPrice();

            if (amount == null) {
                BigDecimal price = unitPrice != null ? unitPrice : DEFAULT_PRICE_BY_TYPE.get(type);
                if (price == null) {
                    throw new BillingRequestInvalidException(
                            "计量项无法计价: resourceType=" + type
                                    + "（未提供 amount 与 unitPrice，且不在内置单价表 "
                                    + DEFAULT_PRICE_BY_TYPE.keySet() + " 中）");
                }
                unitPrice = price;
                amount = price.multiply(BigDecimal.valueOf(usage));
            } else if (unitPrice == null && usage > 0.0) {
                // 上游给了金额没给单价：反推单价仅用于展示，金额仍以 amount 为准
                unitPrice = amount.divide(BigDecimal.valueOf(usage), 4, RoundingMode.HALF_UP);
            } else if (unitPrice == null) {
                unitPrice = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            }

            items.add(buildItem(type, ns, usage, unitPrice, amount, record.getGpuModel(), record.getSourceRef()));
            log.debug("计量汇入项已计价: type={}, usage={}, amount={}, sourceRef={}",
                    type, usage, amount, record.getSourceRef());
        }
        return items;
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
     *
     * @param items        账单明细项
     * @param degraded     是否降级生成（缺少资源用量时序）
     * @param hasUsageData 是否含计量汇入项
     */
    private String buildNote(List<BillingItem> items, boolean degraded, boolean hasUsageData) {
        StringBuilder sb = new StringBuilder();
        sb.append("出账闭环账单：共 ").append(items.size()).append(" 个明细项；");
        sb.append("维度：");
        items.forEach(i -> sb.append(i.getResourceType()).append("/"));
        sb.append("；由 finops-billing 从 Prometheus 采集生成");
        if (hasUsageData) {
            sb.append("，并合并计量汇入项");
        }
        if (degraded) {
            sb.append("；⚠ 降级生成（未采集到资源用量时序，金额不含资源维度或全零）");
        }
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