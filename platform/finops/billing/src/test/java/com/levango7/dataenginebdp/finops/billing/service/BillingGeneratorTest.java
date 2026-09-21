package com.levango7.dataenginebdp.finops.billing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.finops.billing.collector.PrometheusClient;
import com.levango7.dataenginebdp.finops.billing.model.BillingGenerateRequest;
import com.levango7.dataenginebdp.finops.billing.model.BillingGenerateResponse;
import com.levango7.dataenginebdp.finops.billing.model.BillingItem;
import com.levango7.dataenginebdp.finops.billing.model.BillingModel;
import com.levango7.dataenginebdp.finops.billing.repository.BillingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BillingGenerator 单元测试。
 *
 * <p>用 mock 的 {@link PrometheusClient} 与 {@link BillingRepository} 驱动真实的
 * 计价 / 持久化 / 序列化逻辑，覆盖：</p>
 * <ul>
 *   <li>五维度用量 × 单价的计价结果（CPU 2.0 / 内存 0.5 / 存储 0.3 / GPU 8.0 / 网络 0.2）</li>
 *   <li>账期与采集窗口的推导分支（显式指定 / 由 start 推断 / 由账期补自然月）</li>
 *   <li>幂等返回与 overwrite 覆盖写</li>
 *   <li>Prometheus 不可用、查询抛异常、响应结构异常时的降级路径</li>
 *   <li>标签隔离（tenant / namespace 过滤）与脏采样点跳过</li>
 *   <li>明细项 JSON 序列化 / 反序列化失败时的降级</li>
 * </ul>
 */
class BillingGeneratorTest {

    private static final String TENANT = "tenant-A";
    private static final String NS = "ns-1";
    private static final Instant PERIOD_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-09-01T00:00:00Z");

    private PrometheusClient prometheusClient;
    private BillingRepository billingRepository;
    private BillingGenerator generator;

    @BeforeEach
    void setUp() {
        prometheusClient = mock(PrometheusClient.class);
        billingRepository = mock(BillingRepository.class);
        generator = new BillingGenerator(prometheusClient, billingRepository, new ObjectMapper());
        // 默认：save 原样返回入参，便于断言落库内容
        when(billingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------- 测试辅助：构造 Prometheus matrix 响应 ----------

    /** 单条时序：metric 标签 + values 采样点（Prometheus 原始结构 [ts, "value"]）。 */
    private static Map<String, Object> series(Object metric, Object values) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("metric", metric);
        entry.put("values", values);
        return entry;
    }

    /** Prometheus range query 响应外壳：{"status":"success","data":{"result":[...]}}。 */
    private static Map<String, Object> responseOf(List<Object> result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resultType", "matrix");
        data.put("result", result);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "success");
        resp.put("data", data);
        return resp;
    }

    private static Map<String, String> labels(String tenant, String namespace) {
        Map<String, String> metric = new LinkedHashMap<>();
        metric.put("tenant", tenant);
        metric.put("namespace", namespace);
        return metric;
    }

    /** 只包含 tenant 标签（无 namespace）的时序。 */
    private static Map<String, String> labels(String tenant) {
        Map<String, String> metric = new LinkedHashMap<>();
        metric.put("tenant", tenant);
        return metric;
    }

    private static List<Object> samples(double... values) {
        List<Object> points = new ArrayList<>();
        long ts = 1700000000L;
        for (double v : values) {
            points.add(List.of(ts++, String.valueOf(v)));
        }
        return points;
    }

    /** 按查询维度返回不同用量，用于验证五个维度各自的单价。 */
    private static double usageFor(String query) {
        if (query.contains("container_cpu_usage_seconds_total")) {
            return 3.0;
        }
        if (query.contains("container_memory_working_set_bytes")) {
            return 4.0;
        }
        if (query.contains("kubelet_volume_stats_capacity_bytes")) {
            return 10.0;
        }
        if (query.contains("DCGM_FI_DEV_GPU_UTIL")) {
            return 1.5;
        }
        if (query.contains("container_network_receive_bytes_total")) {
            return 5.0;
        }
        return 0.0;
    }

    /** Prometheus 可用，且每个维度返回一条匹配 tenant+namespace 的时序。 */
    private void givenUsagesCollected() {
        when(prometheusClient.isAvailable()).thenReturn(true);
        when(prometheusClient.rangeQuery(anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(inv -> responseOf(List.of(
                        series(labels(TENANT, NS), samples(usageFor(inv.getArgument(0)))))));
    }

    /** Prometheus 可用，且每个维度返回一条只带 tenant 标签的时序。 */
    private void givenUsagesCollectedWithoutNamespaceLabel() {
        when(prometheusClient.isAvailable()).thenReturn(true);
        when(prometheusClient.rangeQuery(anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(inv -> responseOf(List.of(
                        series(labels(TENANT), samples(usageFor(inv.getArgument(0)))))));
    }

    private static BillingModel existingBill(String id, String tenant, String period, String itemsJson) {
        return BillingModel.builder()
                .id(id)
                .tenantId(tenant)
                .billingPeriod(period)
                .itemsJson(itemsJson)
                .totalAmount(new BigDecimal("99.0000"))
                .status("GENERATED")
                .generatedAt(Instant.parse("2026-08-01T00:00:00Z"))
                .periodStart(PERIOD_START)
                .periodEnd(PERIOD_END)
                .note("已存在账单")
                .build();
    }

    private static BillingGenerateRequest request(String period) {
        return BillingGenerateRequest.builder()
                .billingPeriod(period)
                .start(PERIOD_START)
                .end(PERIOD_END)
                .build();
    }

    private static BillingItem itemOf(List<BillingItem> items, String resourceType) {
        return items.stream()
                .filter(i -> resourceType.equals(i.getResourceType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("账单缺少明细项: " + resourceType));
    }

    // ---------- 用例 1：五维度计价 ----------

    @Test
    void generate_pricesFiveDimensionsWithDefaultUnitPrices_andPersists() {
        givenUsagesCollected();
        BillingGenerateRequest request = BillingGenerateRequest.builder()
                .billingPeriod("2026-08")
                .start(PERIOD_START)
                .end(PERIOD_END)
                .namespace(NS)
                .build();

        BillingGenerateResponse response = generator.generate(TENANT, request);

        assertThat(response.getTenantId()).isEqualTo(TENANT);
        assertThat(response.getBillingPeriod()).isEqualTo("2026-08");
        assertThat(response.getStatus()).isEqualTo("GENERATED");
        assertThat(response.getPeriodStart()).isEqualTo(PERIOD_START);
        assertThat(response.getPeriodEnd()).isEqualTo(PERIOD_END);
        assertThat(response.getGeneratedAt()).isNotNull();

        List<BillingItem> items = response.getItems();
        assertThat(items).extracting(BillingItem::getResourceType)
                .containsExactly("CPU", "MEMORY", "STORAGE", "GPU", "NETWORK");
        assertThat(items).extracting(BillingItem::getNamespace).containsOnly(NS);

        // 单价 × 用量：CPU 3.0×2.0 / 内存 4.0×0.5 / 存储 10.0×0.3 / GPU 1.5×8.0 / 网络 5.0×0.2
        assertThat(itemOf(items, "CPU").getUsage()).isEqualTo(3.0);
        assertThat(itemOf(items, "CPU").getUnitPrice()).isEqualByComparingTo("2.0");
        assertThat(itemOf(items, "CPU").getAmount()).isEqualByComparingTo("6.0000");
        assertThat(itemOf(items, "CPU").getGpuModel()).isNull();

        assertThat(itemOf(items, "MEMORY").getAmount()).isEqualByComparingTo("2.0000");
        assertThat(itemOf(items, "STORAGE").getAmount()).isEqualByComparingTo("3.0000");

        assertThat(itemOf(items, "GPU").getUsage()).isEqualTo(1.5);
        assertThat(itemOf(items, "GPU").getUnitPrice()).isEqualByComparingTo("8.0");
        assertThat(itemOf(items, "GPU").getAmount()).isEqualByComparingTo("12.0000");
        assertThat(itemOf(items, "GPU").getGpuModel()).isEqualTo("default");

        assertThat(itemOf(items, "NETWORK").getAmount()).isEqualByComparingTo("1.0000");

        assertThat(response.getTotalAmount()).isEqualByComparingTo("24.0000");
        assertThat(response.getNote()).contains("共 5 个明细项")
                .contains("CPU/MEMORY/STORAGE/GPU/NETWORK/");

        ArgumentCaptor<BillingModel> saved = ArgumentCaptor.forClass(BillingModel.class);
        verify(billingRepository).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(saved.getValue().getBillingPeriod()).isEqualTo("2026-08");
        assertThat(saved.getValue().getStatus()).isEqualTo("GENERATED");
        assertThat(saved.getValue().getTotalAmount()).isEqualByComparingTo("24.0000");
        assertThat(saved.getValue().getItemsJson()).contains("\"resourceType\":\"CPU\"");
    }

    // ---------- 用例 2~5：账期与采集窗口推导 ----------

    @Test
    void generate_derivesPeriodFromStart_whenPeriodNull() {
        givenUsagesCollected();
        Instant start = Instant.parse("2026-08-15T10:30:00Z");
        Instant end = Instant.parse("2026-08-20T10:30:00Z");

        BillingGenerateResponse response = generator.generate(TENANT, BillingGenerateRequest.builder()
                .start(start)
                .end(end)
                .build());

        assertThat(response.getBillingPeriod()).isEqualTo("2026-08");
        assertThat(response.getPeriodStart()).isEqualTo(start);
        assertThat(response.getPeriodEnd()).isEqualTo(end);
    }

    @Test
    void generate_defaultsWindowToNaturalMonth_whenPeriodBlankAndWindowMissing() {
        givenUsagesCollected();

        BillingGenerateResponse response = generator.generate(TENANT, BillingGenerateRequest.builder()
                .billingPeriod("   ")
                .build());

        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        assertThat(response.getBillingPeriod()).isEqualTo(currentMonth.toString());
        assertThat(response.getPeriodStart())
                .isEqualTo(currentMonth.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        assertThat(response.getPeriodEnd())
                .isEqualTo(currentMonth.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    @Test
    void generate_defaultsMissingEndToNextMonthStart_whenOnlyStartGiven() {
        givenUsagesCollected();
        Instant start = Instant.parse("2026-08-10T00:00:00Z");

        BillingGenerateResponse response = generator.generate(TENANT, BillingGenerateRequest.builder()
                .billingPeriod("2026-08")
                .start(start)
                .build());

        assertThat(response.getPeriodStart()).isEqualTo(start);
        assertThat(response.getPeriodEnd()).isEqualTo(PERIOD_END);
    }

    @Test
    void generate_defaultsMissingStartToPeriodFirstDay_whenOnlyEndGiven() {
        givenUsagesCollected();
        Instant end = Instant.parse("2026-08-20T00:00:00Z");

        BillingGenerateResponse response = generator.generate(TENANT, BillingGenerateRequest.builder()
                .billingPeriod("2026-08")
                .end(end)
                .build());

        assertThat(response.getPeriodStart()).isEqualTo(PERIOD_START);
        assertThat(response.getPeriodEnd()).isEqualTo(end);
        assertThat(LocalDate.ofInstant(response.getPeriodStart(), ZoneOffset.UTC))
                .isEqualTo(LocalDate.of(2026, 8, 1));
    }

    // ---------- 用例 6~7：幂等与覆盖 ----------

    @Test
    void generate_returnsExistingBill_withoutRecollecting_whenNotOverwrite() {
        when(billingRepository.findByTenantIdAndBillingPeriod(TENANT, "2026-08"))
                .thenReturn(Optional.of(existingBill("bill-existing", TENANT, "2026-08", "[]")));
        when(prometheusClient.isAvailable()).thenReturn(true);

        BillingGenerateResponse response = generator.generate(TENANT, request("2026-08"));

        assertThat(response.getId()).isEqualTo("bill-existing");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("99.0000");
        assertThat(response.getNote()).isEqualTo("已存在账单");
        // 幂等返回：不重新采集、不重复落库
        verify(prometheusClient, never()).rangeQuery(anyString(), anyLong(), anyLong(), any());
        verify(billingRepository, never()).save(any());
    }

    @Test
    void generate_overwritesExistingBill_keepingOriginalId_whenOverwrite() {
        when(billingRepository.findByTenantIdAndBillingPeriod(TENANT, "2026-08"))
                .thenReturn(Optional.of(existingBill("bill-existing", TENANT, "2026-08", "[]")));
        givenUsagesCollected();

        BillingGenerateResponse response = generator.generate(TENANT, BillingGenerateRequest.builder()
                .billingPeriod("2026-08")
                .start(PERIOD_START)
                .end(PERIOD_END)
                .namespace(NS)
                .overwrite(true)
                .build());

        // 覆盖生成：金额按新采集结果重算，但复用原账单 ID
        assertThat(response.getId()).isEqualTo("bill-existing");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("24.0000");
        assertThat(response.getStatus()).isEqualTo("GENERATED");
        assertThat(response.getGeneratedAt()).isAfter(Instant.parse("2026-08-01T00:00:00Z"));

        ArgumentCaptor<BillingModel> saved = ArgumentCaptor.forClass(BillingModel.class);
        verify(billingRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo("bill-existing");
    }

    // ---------- 用例 8~9：Prometheus 降级路径 ----------

    @Test
    void generate_degradesToZeroUsage_whenPrometheusUnavailable() {
        when(prometheusClient.isAvailable()).thenReturn(false);

        BillingGenerateResponse response = generator.generate(TENANT, request("2026-08"));

        assertThat(response.getItems()).hasSize(5);
        assertThat(response.getItems()).allSatisfy(i -> {
            assertThat(i.getUsage()).isEqualTo(0.0);
            assertThat(i.getAmount()).isEqualByComparingTo("0.0000");
        });
        assertThat(response.getTotalAmount()).isEqualByComparingTo("0.0000");
        // 降级不中断出账：账单仍然落库
        verify(billingRepository).save(any());
        verify(prometheusClient, never()).rangeQuery(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    void generate_degradesToZeroUsage_whenRangeQueryThrows() {
        when(prometheusClient.isAvailable()).thenReturn(true);
        when(prometheusClient.rangeQuery(anyString(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("Prometheus 500"));

        BillingGenerateResponse response = generator.generate(TENANT, request("2026-08"));

        assertThat(response.getTotalAmount()).isEqualByComparingTo("0.0000");
        assertThat(response.getStatus()).isEqualTo("GENERATED");
        assertThat(response.getItems()).hasSize(5);
    }

    // ---------- 用例 10~12：响应结构异常与标签隔离 ----------

    @Test
    void generate_ignoresMalformedPrometheusPayload_andCountsOnlyWellFormedSamples() {
        when(prometheusClient.isAvailable()).thenReturn(true);
        List<Object> result = new ArrayList<>();
        result.add("not-a-map-entry");                                   // 非 Map 时序 → 跳过
        Map<String, Object> noMetric = new LinkedHashMap<>();
        noMetric.put("metric", null);                                    // metric 为 null → 跳过
        result.add(noMetric);
        result.add(series(labels("tenant-OTHER", NS), samples(100.0)));  // tenant 不匹配 → 跳过
        result.add(series(labels(TENANT, "ns-other"), samples(100.0)));  // namespace 不匹配 → 跳过
        result.add(series(labels(TENANT, NS), "values-not-a-list"));     // values 非 List → 跳过
        // values 内部脏点：非 List / 长度不足 / 非 String / 非数字 → 跳过，只保留最后一个合法点
        List<Object> dirty = new ArrayList<>();
        dirty.add("not-a-point");
        dirty.add(List.of(1L));
        dirty.add(List.of(1L, 2L));
        dirty.add(List.of(1L, "NaN-ish"));
        dirty.add(List.of(1L, "2.5"));
        result.add(series(labels(TENANT, NS), dirty));
        when(prometheusClient.rangeQuery(anyString(), anyLong(), anyLong(), any()))
                .thenReturn(responseOf(result));

        BillingGenerateResponse response = generator.generate(TENANT, BillingGenerateRequest.builder()
                .billingPeriod("2026-08")
                .start(PERIOD_START)
                .end(PERIOD_END)
                .namespace(NS)
                .build());

        // 每个维度只认 2.5 这一个合法采样点：(2.0+0.5+0.3+8.0+0.2) × 2.5 = 27.5
        assertThat(response.getItems()).hasSize(5);
        assertThat(response.getItems()).allSatisfy(i -> assertThat(i.getUsage()).isEqualTo(2.5));
        assertThat(response.getTotalAmount()).isEqualByComparingTo("27.5000");
    }

    @Test
    void generate_returnsZeroUsage_whenResponseShapeInvalid() {
        when(prometheusClient.isAvailable()).thenReturn(true);
        // 依次返回：响应为 null / data 不是 Map / result 不是 List
        when(prometheusClient.rangeQuery(anyString(), anyLong(), anyLong(), any()))
                .thenReturn(null,
                        Map.of("data", "not-a-map"),
                        Map.of("data", Map.of("result", "not-a-list")));

        // 每次 generate 会触发 5 次 rangeQuery，三次调用分别消费上面三种畸形响应
        assertThat(generator.generate(TENANT, request("2026-01")).getTotalAmount())
                .isEqualByComparingTo("0.0000");
        assertThat(generator.generate(TENANT, request("2026-02")).getTotalAmount())
                .isEqualByComparingTo("0.0000");
        assertThat(generator.generate(TENANT, request("2026-03")).getTotalAmount())
                .isEqualByComparingTo("0.0000");
    }

    @Test
    void generate_aggregatesAllNamespaces_whenNamespaceNotSpecified() {
        when(prometheusClient.isAvailable()).thenReturn(true);
        when(prometheusClient.rangeQuery(anyString(), anyLong(), anyLong(), any()))
                .thenReturn(responseOf(List.of(
                        series(labels(TENANT, "ns-1"), samples(1.0)),
                        series(labels(TENANT, "ns-2"), samples(2.0)))));

        BillingGenerateResponse response = generator.generate(TENANT, request("2026-08"));

        // namespace 未指定时不过滤：两个 namespace 的用量都计入（1.0 + 2.0 = 3.0）
        assertThat(response.getItems()).allSatisfy(i -> assertThat(i.getUsage()).isEqualTo(3.0));
        assertThat(response.getItems()).extracting(BillingItem::getNamespace).containsOnly("");
        // (2.0+0.5+0.3+8.0+0.2) × 3.0 = 33.0
        assertThat(response.getTotalAmount()).isEqualByComparingTo("33.0000");
    }

    // ---------- 用例 13：明细项序列化失败降级 ----------

    @Test
    void generate_degradesToEmptyItemsJson_whenItemsSerializationFails() {
        givenUsagesCollectedWithoutNamespaceLabel();
        BillingGenerator failing = new BillingGenerator(
                prometheusClient, billingRepository, new FailingWriteObjectMapper());

        BillingGenerateResponse response = failing.generate(TENANT, request("2026-08"));

        // 序列化失败不阻断出账：明细项降级为空数组，总金额仍按采集结果汇总
        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotalAmount()).isEqualByComparingTo("24.0000");
        ArgumentCaptor<BillingModel> saved = ArgumentCaptor.forClass(BillingModel.class);
        verify(billingRepository).save(saved.capture());
        assertThat(saved.getValue().getItemsJson()).isEqualTo("[]");
    }

    /** 仅用于覆盖序列化失败降级分支：写 JSON 时抛异常，读 JSON 走父类正常实现。 */
    private static class FailingWriteObjectMapper extends ObjectMapper {
        @Override
        public String writeValueAsString(Object value) throws JsonProcessingException {
            throw new JsonProcessingException("注入的序列化失败") {
            };
        }
    }

    // ---------- 用例 14~17：查询与列表 ----------

    @Test
    void getById_returnsResponseWithDeserializedItems_whenBelongsToTenant() {
        String itemsJson = "[{\"resourceType\":\"CPU\",\"namespace\":\"ns-1\",\"usage\":3.0,"
                + "\"unitPrice\":2.0,\"amount\":6.0,\"gpuModel\":null}]";
        when(billingRepository.findByIdAndTenantId("bill-1", TENANT))
                .thenReturn(Optional.of(existingBill("bill-1", TENANT, "2026-08", itemsJson)));

        Optional<BillingGenerateResponse> response = generator.getById("bill-1", TENANT);

        assertThat(response).isPresent();
        assertThat(response.get().getId()).isEqualTo("bill-1");
        assertThat(response.get().getTenantId()).isEqualTo(TENANT);
        assertThat(response.get().getItems()).singleElement().satisfies(i -> {
            assertThat(i.getResourceType()).isEqualTo("CPU");
            assertThat(i.getUsage()).isEqualTo(3.0);
            assertThat(i.getAmount()).isEqualByComparingTo("6.0");
        });
        assertThat(response.get().getNote()).isEqualTo("已存在账单");
    }

    @Test
    void getById_returnsEmpty_whenBillNotVisibleForTenant() {
        when(billingRepository.findByIdAndTenantId("bill-1", "tenant-B")).thenReturn(Optional.empty());

        assertThat(generator.getById("bill-1", "tenant-B")).isEmpty();
        verify(billingRepository).findByIdAndTenantId("bill-1", "tenant-B");
    }

    @Test
    void getById_returnsEmptyItems_whenItemsJsonBlankOrBroken() {
        when(billingRepository.findByIdAndTenantId("bill-blank", TENANT))
                .thenReturn(Optional.of(existingBill("bill-blank", TENANT, "2026-08", "   ")));
        when(billingRepository.findByIdAndTenantId("bill-broken", TENANT))
                .thenReturn(Optional.of(existingBill("bill-broken", TENANT, "2026-08", "{not-json")));

        assertThat(generator.getById("bill-blank", TENANT).orElseThrow().getItems()).isEmpty();
        assertThat(generator.getById("bill-broken", TENANT).orElseThrow().getItems()).isEmpty();
    }

    @Test
    void listByTenant_mapsAllBillsInRepositoryOrder() {
        when(billingRepository.findByTenantIdOrderByGeneratedAtDesc(TENANT)).thenReturn(List.of(
                existingBill("bill-new", TENANT, "2026-08", "[]"),
                existingBill("bill-old", TENANT, "2026-07", "[]")));

        List<BillingGenerateResponse> bills = generator.listByTenant(TENANT);

        assertThat(bills).extracting(BillingGenerateResponse::getId)
                .containsExactly("bill-new", "bill-old");
        assertThat(bills).allSatisfy(b -> {
            assertThat(b.getTenantId()).isEqualTo(TENANT);
            assertThat(b.getItems()).isEmpty();
            assertThat(b.getTotalAmount()).isEqualByComparingTo("99.0000");
        });
    }

    @Test
    void listByTenant_returnsEmptyList_whenTenantHasNoBill() {
        when(billingRepository.findByTenantIdOrderByGeneratedAtDesc("tenant-empty")).thenReturn(List.of());

        assertThat(generator.listByTenant("tenant-empty")).isEmpty();
    }
}
