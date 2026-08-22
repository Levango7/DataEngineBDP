package com.levango7.dataenginebdp.finops.dashboard.controller;

import com.levango7.dataenginebdp.finops.dashboard.model.CostTrendPoint;
import com.levango7.dataenginebdp.finops.dashboard.model.DashboardResponse;
import com.levango7.dataenginebdp.finops.dashboard.model.ResourceCostDetail;
import com.levango7.dataenginebdp.finops.dashboard.model.TopCostResource;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.finops.dashboard.service.CostDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * FinOps 看板 REST API。
 *
 * <p>提供四类看板数据查询端点：</p>
 * <ul>
 *   <li>GET /api/v1/dashboard/top10  — Top10 成本资源</li>
 *   <li>GET /api/v1/dashboard/trend  — 成本趋势</li>
 *   <li>GET /api/v1/dashboard/details — 成本明细</li>
 *   <li>GET /api/v1/dashboard/idle   — 闲置清单（由 SuggestionController 提供）</li>
 * </ul>
 */
@RestController
@Tag(name = "成本运营-成本看板", description = "Top10/趋势/明细看板")
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final CostDataService costDataService;

    public DashboardController(CostDataService costDataService) {
        this.costDataService = costDataService;
    }

    /**
     * Top10 成本资源。
     */
    @GetMapping("/top10")
    public ResponseEntity<DashboardResponse<TopCostResource>> top10(
            @RequestParam(required = false) String namespace,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {

        String tenant = TenantContext.getTenantId();
        log.info("Top10 看板请求: tenant={}, namespace={}, 窗口=[{},{}]", tenant, namespace, start, end);

        List<TopCostResource> items = costDataService.getTopCostResources(tenant, namespace, start, end);
        BigDecimal total = items.stream()
                .map(TopCostResource::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        DashboardResponse<TopCostResource> resp = DashboardResponse.<TopCostResource>builder()
                .items(items)
                .total(items.size())
                .start(start)
                .end(end)
                .tenant(tenant)
                .summary(Map.of("totalCost", total, "resourceCount", items.size()))
                .build();
        return ResponseEntity.ok(resp);
    }

    /**
     * 成本趋势。
     */
    @GetMapping("/trend")
    public ResponseEntity<DashboardResponse<CostTrendPoint>> trend(
            @RequestParam(required = false) String namespace,
            @RequestParam(defaultValue = "HOUR") String granularity,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {

        String tenant = TenantContext.getTenantId();
        log.info("趋势看板请求: tenant={}, granularity={}, 窗口=[{},{}]", tenant, granularity, start, end);

        List<CostTrendPoint> items = costDataService.getCostTrend(tenant, namespace, start, end, granularity);
        BigDecimal total = items.stream()
                .map(CostTrendPoint::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        DashboardResponse<CostTrendPoint> resp = DashboardResponse.<CostTrendPoint>builder()
                .items(items)
                .total(items.size())
                .start(start)
                .end(end)
                .tenant(tenant)
                .summary(Map.of("totalCost", total, "granularity", granularity))
                .build();
        return ResponseEntity.ok(resp);
    }

    /**
     * 成本明细。
     */
    @GetMapping("/details")
    public ResponseEntity<DashboardResponse<ResourceCostDetail>> details(
            @RequestParam(required = false) String namespace,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {

        String tenant = TenantContext.getTenantId();
        log.info("明细看板请求: tenant={}, namespace={}, 窗口=[{},{}]", tenant, namespace, start, end);

        List<ResourceCostDetail> items = costDataService.getCostDetails(tenant, namespace, start, end);
        BigDecimal total = items.stream()
                .map(ResourceCostDetail::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        DashboardResponse<ResourceCostDetail> resp = DashboardResponse.<ResourceCostDetail>builder()
                .items(items)
                .total(items.size())
                .start(start)
                .end(end)
                .tenant(tenant)
                .summary(Map.of("totalCost", total, "resourceCount", items.size()))
                .build();
        return ResponseEntity.ok(resp);
    }
}