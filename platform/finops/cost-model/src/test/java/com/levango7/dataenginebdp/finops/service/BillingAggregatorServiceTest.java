package com.levango7.dataenginebdp.finops.service;

import com.levango7.dataenginebdp.finops.model.CostResult;
import com.levango7.dataenginebdp.finops.model.QueryMeteringRecord;
import com.levango7.dataenginebdp.finops.model.ResourceDimension;
import com.levango7.dataenginebdp.finops.model.TieredPricingTier;
import com.levango7.dataenginebdp.finops.repository.QueryMeteringRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * BillingAggregatorService 单元测试（H2 + Repo 真实，PricingConfig 用 mock 默认分层）。
 */
@DataJpaTest
@Import(BillingAggregatorService.class)
class BillingAggregatorServiceTest {

    @Autowired
    private QueryMeteringRepository meteringRepository;

    @Autowired
    private BillingAggregatorService billingAggregatorService;

    @MockBean
    private PricingConfigService pricingConfigService;

    @MockBean
    private TieredBillingStrategy tieredBillingStrategy;

    private QueryMeteringRecord record(String tenantId, long bytes, boolean estimated,
                                       String engine, Instant createdAt) {
        return QueryMeteringRecord.builder()
                .tenantId(tenantId)
                .engine(engine)
                .bytesScanned(bytes)
                .estimated(estimated)
                .clientRequestId("req-" + System.nanoTime())
                .createdAt(createdAt)
                .build();
    }

    @Test
    void aggregateQueryBilling_emptyWindowReturnsZeroCost() {
        when(tieredBillingStrategy.calculate(any(), any()))
                .thenReturn(CostResult.builder()
                        .totalCost(BigDecimal.ZERO)
                        .dimensionUsages(new java.util.HashMap<>())
                        .build());

        CostResult result = billingAggregatorService
                .aggregateQueryBilling("tenant_a",
                        Instant.now().minus(1, ChronoUnit.DAYS), Instant.now());

        assertThat(result.getTotalCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getNote()).contains("0 次查询");
    }

    @Test
    void aggregateQueryBilling_sumsRealAndEstimatedBytes() {
        Instant now = Instant.now();
        meteringRepository.save(record("tenant_a", 1024L * 1024 * 1024 * 1024, false, "trino", now.minusSeconds(60)));
        meteringRepository.save(record("tenant_a", 3L * 1024 * 1024 * 1024 * 1024, true, "trino", now.minusSeconds(30)));
        meteringRepository.save(record("tenant_b", 5L * 1024 * 1024 * 1024 * 1024, false, "doris", now.minusSeconds(30)));

        // SCANNED_DATA 1TB 用量，单价 1.5 → 1.5
        when(tieredBillingStrategy.calculate(any(), any()))
                .thenAnswer(inv -> {
                    List<com.levango7.dataenginebdp.finops.model.ResourceUsage> usages = inv.getArgument(0);
                    double tb = usages.stream()
                            .filter(u -> u.getDimension() == ResourceDimension.SCANNED_DATA)
                            .mapToDouble(com.levango7.dataenginebdp.finops.model.ResourceUsage::getAmount)
                            .sum();
                    return CostResult.builder()
                            .tenant("tenant_a")
                            .billingMethod(com.levango7.dataenginebdp.finops.model.BillingMethod.TIERED)
                            .start(now.minus(1, ChronoUnit.DAYS))
                            .end(now)
                            .totalCost(java.math.BigDecimal.valueOf(tb * 1.5).setScale(4, java.math.RoundingMode.HALF_UP))
                            .dimensionCosts(java.util.Map.of(ResourceDimension.SCANNED_DATA,
                                    java.math.BigDecimal.valueOf(tb * 1.5)))
                            .dimensionUsages(java.util.Map.of(ResourceDimension.SCANNED_DATA, tb))
                            .note("test")
                            .build();
                });

        CostResult result = billingAggregatorService
                .aggregateQueryBilling("tenant_a",
                        now.minus(1, ChronoUnit.DAYS), now);

        // tenant_a: 真实 1TB + 估算 3TB = 4TB → 4 × 1.5 = 6.0
        assertThat(result.getTotalCost()).isEqualByComparingTo("6.0000");
        assertThat(result.getNote()).contains("2 次查询").contains("引擎分布");
        // tenant_b 的记录被隔离，不计入
        assertThat(meteringRepository.count()).isEqualTo(3);
    }

    @Test
    void cleanup_removesOldRecords() {
        Instant now = Instant.now();
        meteringRepository.save(record("tenant_a", 10L, true, "trino", now.minus(30, ChronoUnit.DAYS)));
        meteringRepository.save(record("tenant_a", 20L, true, "trino", now));

        long deleted = billingAggregatorService.cleanup("tenant_a", now.minus(7, ChronoUnit.DAYS));

        assertThat(deleted).isEqualTo(1); // 只删 30 天前那一条
        assertThat(meteringRepository.count()).isEqualTo(1);
    }

    @Test
    void aggregateDailyQueryBilling_groupsByDaySorted() {
        Instant now = Instant.now();
        // 同一天两条 + 前一天一条
        meteringRepository.save(record("tenant_a", 100L, true, "trino", now.minus(10, ChronoUnit.MINUTES)));
        meteringRepository.save(record("tenant_a", 200L, true, "trino", now.minus(5, ChronoUnit.MINUTES)));
        meteringRepository.save(record("tenant_a", 300L, true, "trino", now.minus(1, ChronoUnit.DAYS)));
        // 其他租户不参与
        meteringRepository.save(record("tenant_b", 999L, true, "doris", now.minus(1, ChronoUnit.MINUTES)));

        when(tieredBillingStrategy.calculate(any(), any()))
                .thenReturn(CostResult.builder()
                        .totalCost(BigDecimal.valueOf(1.0))
                        .dimensionUsages(new java.util.HashMap<>())
                        .build());

        var points = billingAggregatorService.aggregateDailyQueryBilling(
                "tenant_a", now.minus(2, ChronoUnit.DAYS), now.plusSeconds(60));

        // 2 个不同的日点，升序（先旧后新）
        assertThat(points).hasSize(2);
        assertThat(points.get(0).getDay()).isLessThan(points.get(1).getDay());
        // 最新日包含两条记录 → queryCount=2, 字节=300
        var latest = points.get(1);
        assertThat(latest.getQueryCount()).isEqualTo(2);
        assertThat(latest.getBytesScanned()).isEqualTo(300L);
        // 旧日 queryCount=1
        assertThat(points.get(0).getQueryCount()).isEqualTo(1);
        assertThat(points.get(0).getBytesScanned()).isEqualTo(300L);
    }

    @Test
    void aggregateDailyQueryBilling_emptyReturnsEmptyList() {
        when(tieredBillingStrategy.calculate(any(), any()))
                .thenReturn(CostResult.builder().totalCost(BigDecimal.ZERO).dimensionUsages(new java.util.HashMap<>()).build());

        var points = billingAggregatorService.aggregateDailyQueryBilling(
                "tenant_a", Instant.now().minus(1, ChronoUnit.DAYS), Instant.now());

        assertThat(points).isEmpty();
    }
}