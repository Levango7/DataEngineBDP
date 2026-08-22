package com.levango7.dataenginebdp.finops.service;

import com.levango7.dataenginebdp.finops.model.CostResult;
import com.levango7.dataenginebdp.finops.model.QueryMeteringRecord;
import com.levango7.dataenginebdp.finops.model.ResourceDimension;
import com.levango7.dataenginebdp.finops.model.ResourceUsage;
import com.levango7.dataenginebdp.finops.repository.QueryMeteringRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 租户账单聚合服务。
 *
 * <p>将查询计量记录（bytesScanned）按租户×时间窗聚合成
 * SCANNED_DATA 用量，并复用分层定价计算账单金额。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingAggregatorService {

    /** 1 TB = 1024^4 字节。 */
    private static final double BYTES_PER_TB = 1024d * 1024 * 1024 * 1024;

    private final QueryMeteringRepository meteringRepository;
    private final PricingConfigService pricingConfigService;
    private final TieredBillingStrategy tieredBillingStrategy;

    /**
     * 聚合某租户在 [start, end) 窗口内的查询账单。
     *
     * @param tenantId 租户 ID（须由调用方经 TenantContext 校验）
     * @param start    窗口起始（含）
     * @param end      窗口结束（不含）
     * @return 账单结果
     */
    @Transactional(readOnly = true)
    public CostResult aggregateQueryBilling(String tenantId, Instant start, Instant end) {
        List<QueryMeteringRecord> records = meteringRepository
                .findByTenantIdAndCreatedAtBetweenOrderByCreatedAtAsc(tenantId, start, end);

        // 汇总扫描字节（估计与真实分开统计，便于审计）
        long estimatedBytes = 0;
        long realBytes = 0;
        int queryCount = 0;
        Map<String, Integer> engineCounts = new HashMap<>();
        for (QueryMeteringRecord r : records) {
            if (r.isEstimated()) {
                estimatedBytes += r.getBytesScanned();
            } else {
                realBytes += r.getBytesScanned();
            }
            queryCount++;
            engineCounts.merge(r.getEngine(), 1, Integer::sum);
        }
        long totalBytes = estimatedBytes + realBytes;
        double totalTb = totalBytes / BYTES_PER_TB;

        // 构造 SCANNED_DATA 用量（单位 TB）参与分层定价
        List<ResourceUsage> usages = new ArrayList<>();
        if (totalTb > 0) {
            usages.add(ResourceUsage.builder()
                    .tenant(tenantId)
                    .namespace("query-metering")
                    .dimension(ResourceDimension.SCANNED_DATA)
                    .amount(totalTb)
                    .start(start)
                    .end(end)
                    .build());
        }

        CostResult costResult = tieredBillingStrategy.calculate(
                usages, pricingConfigService.getDefault());

        // 补充查询统计维度（不参与金额，仅审计展示）
        Map<ResourceDimension, Double> safeUsages = new HashMap<>();
        if (costResult.getDimensionUsages() != null) {
            safeUsages.putAll(costResult.getDimensionUsages());
        }
        safeUsages.put(ResourceDimension.SCANNED_DATA, totalTb);
        costResult.setDimensionUsages(safeUsages);
        costResult.setNote(buildNote(queryCount, engineCounts, estimatedBytes, realBytes,
                costResult.getTotalCost()));

        log.info("租户账单聚合完成: tenant={}, queries={}, totalTB={}, estBytes={}, realBytes={}, cost={}",
                tenantId, queryCount, String.format("%.4f", totalTb), estimatedBytes, realBytes,
                costResult.getTotalCost());
        return costResult;
    }

    private String buildNote(int queryCount, Map<String, Integer> engineCounts,
                             long estimatedBytes, long realBytes, BigDecimal cost) {
        StringBuilder sb = new StringBuilder();
        sb.append("查询计量账单：共 ").append(queryCount).append(" 次查询；");
        sb.append("估算扫描 ").append(String.format("%.3f TB", estimatedBytes / BYTES_PER_TB)).append("；");
        sb.append("真实扫描 ").append(String.format("%.3f TB", realBytes / BYTES_PER_TB)).append("；");
        sb.append("引擎分布 ").append(engineCounts).append("；");
        sb.append("金额（分层定价）").append(cost == null ? "0" : cost.toPlainString()).append(" 元");
        return sb.toString();
    }

    /**
     * 清理某租户指定时间前的计量记录（保留期清理）。
     *
     * @param tenantId     租户 ID
     * @param before       保留截止时间
     * @return 删除条数
     */
    @Transactional
    public long cleanup(String tenantId, Instant before) {
        return meteringRepository.deleteByTenantIdAndCreatedAtBefore(tenantId, before);
    }

    /** 分桶工具：按天切分时间窗（预留，供明细报表扩展）。 */
    List<Instant[]> splitByDay(Instant start, Instant end) {
        List<Instant[]> buckets = new ArrayList<>();
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            Instant next = cursor.plus(1, ChronoUnit.DAYS);
            if (next.isAfter(end)) {
                next = end;
            }
            buckets.add(new Instant[]{cursor, next});
            cursor = next;
        }
        return buckets;
    }

    /**
     * 按日趋势聚合：将 [start, end) 窗口内的计量按天分组，逐日计算
     * 扫描字节、查询次数与成本（分层定价）。
     *
     * <p>单次查询 + 内存分组，避免按天循环查库。</p>
     *
     * @param tenantId 租户 ID
     * @param start    窗口起始（含）
     * @param end      窗口结束（不含）
     * @return 按日账单点列表（按时间升序，缺数据的日期不产生点）
     */
    @Transactional(readOnly = true)
    public List<com.levango7.dataenginebdp.finops.model.DailyBillingPoint> aggregateDailyQueryBilling(
            String tenantId, Instant start, Instant end) {
        List<QueryMeteringRecord> records = meteringRepository
                .findByTenantIdAndCreatedAtBetweenOrderByCreatedAtAsc(tenantId, start, end);
        if (records.isEmpty()) {
            return List.of();
        }

        // 按天分组：dayKey(yyyy-MM-dd) → list
        Map<String, List<QueryMeteringRecord>> byDay = new HashMap<>();
        for (QueryMeteringRecord r : records) {
            String day = r.getCreatedAt().toString().substring(0, 10); // ISO-8601 UTC 日
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(r);
        }

        List<com.levango7.dataenginebdp.finops.model.DailyBillingPoint> points = new ArrayList<>();
        byDay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    String day = e.getKey();
                    List<QueryMeteringRecord> dayRecords = e.getValue();
                    long bytes = dayRecords.stream().mapToLong(QueryMeteringRecord::getBytesScanned).sum();
                    double tb = bytes / BYTES_PER_TB;

                    List<ResourceUsage> usages = new ArrayList<>();
                    if (tb > 0) {
                        usages.add(ResourceUsage.builder()
                                .tenant(tenantId)
                                .namespace("query-metering")
                                .dimension(ResourceDimension.SCANNED_DATA)
                                .amount(tb)
                                .start(start)
                                .end(end)
                                .build());
                    }
                    BigDecimal cost = tieredBillingStrategy.calculate(
                            usages, pricingConfigService.getDefault()).getTotalCost();
                    points.add(com.levango7.dataenginebdp.finops.model.DailyBillingPoint.builder()
                            .day(day)
                            .bytesScanned(bytes)
                            .tbScanned(tb)
                            .queryCount(dayRecords.size())
                            .cost(cost)
                            .build());
                });
        log.info("租户按日账单趋势聚合完成: tenant={}, 天数={}", tenantId, points.size());
        return points;
    }
}