package com.levango7.dataenginebdp.finops.controller;

import com.levango7.dataenginebdp.finops.model.CostResult;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.finops.service.BillingAggregatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 租户账单查询（成本计费）。
 *
 * <p>租户 ID 强制从 {@link TenantContext} 获取，不信任 URL 参数，防止跨租户越权查账单。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/finops/billing")
public class BillingController {

    private final BillingAggregatorService billingAggregatorService;

    /**
     * 查询当前租户的查询计费账单。
     *
     * <p>默认按自然日窗口聚合，支持 startDate/endDate 覆盖。
     *
     * @param date 起止日期（yyyy-MM-dd），默认今天
     * @return 账单结果
     */
    @GetMapping("/tenant")
    public ResponseEntity<?> currentTenantBilling(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "缺少租户上下文（TenantContext 未设置）"));
        }

        LocalDate startDay = startDate == null ? LocalDate.now(ZoneOffset.UTC) : startDate;
        LocalDate endDay = endDate == null ? startDay : endDate;
        if (endDay.isBefore(startDay)) {
            endDay = startDay.plusDays(1); // 防御：结束早于开始则回退次日
        }
        // 窗口为 [startDay 零点, endDay 次日零点)，endDay 本身仍被计为不包含
        Instant start = startDay.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = endDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        CostResult result = billingAggregatorService.aggregateQueryBilling(tenantId, start, end);
        return ResponseEntity.ok(Map.of(
                "tenant", tenantId,
                "start", start.toString(),
                "end", end.toString(),
                "billingMethod", result.getBillingMethod(),
                "totalCost", result.getTotalCost(),
                "usages", result.getDimensionUsages(),
                "note", result.getNote()));
    }

    /**
     * 查询当前租户的按日账单趋势（趋势图数据源）。
     *
     * @param startDate 起始日期（yyyy-MM-dd，可空）
     * @param endDate   结束日期（yyyy-MM-dd，可空）
     * @return 按日账单点列表
     */
    @GetMapping("/tenant/trend")
    public ResponseEntity<?> currentTenantBillingTrend(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "缺少租户上下文（TenantContext 未设置）"));
        }

        LocalDate startDay = startDate == null ? LocalDate.now(ZoneOffset.UTC).minusDays(6) : startDate;
        LocalDate endDay = endDate == null ? LocalDate.now(ZoneOffset.UTC) : endDate;
        Instant start = startDay.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = endDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        var points = billingAggregatorService.aggregateDailyQueryBilling(tenantId, start, end);
        return ResponseEntity.ok(Map.of(
                "tenant", tenantId,
                "start", start.toString(),
                "end", end.toString(),
                "points", points));
    }
}