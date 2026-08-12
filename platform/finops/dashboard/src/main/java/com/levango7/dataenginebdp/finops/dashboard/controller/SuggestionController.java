package com.levango7.dataenginebdp.finops.dashboard.controller;

import com.levango7.dataenginebdp.finops.dashboard.model.DashboardResponse;
import com.levango7.dataenginebdp.finops.dashboard.model.IdleResource;
import com.levango7.dataenginebdp.finops.dashboard.model.OptimizationSuggestion;
import com.levango7.dataenginebdp.finops.dashboard.security.TenantContext;
import com.levango7.dataenginebdp.finops.dashboard.service.OptimizationEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 优化建议 REST API。
 *
 * <p>提供闲置清单与优化建议查询端点：</p>
 * <ul>
 *   <li>GET /api/v1/suggestions/idle  — 闲置资源清单（5 类闲置模式识别结果）</li>
 *   <li>GET /api/v1/suggestions/list  — 优化建议列表</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/suggestions")
public class SuggestionController {

    private static final Logger log = LoggerFactory.getLogger(SuggestionController.class);

    private final OptimizationEngine optimizationEngine;

    public SuggestionController(OptimizationEngine optimizationEngine) {
        this.optimizationEngine = optimizationEngine;
    }

    /**
     * 闲置资源清单。
     */
    @GetMapping("/idle")
    public ResponseEntity<DashboardResponse<IdleResource>> idle(
            @RequestParam(required = false) String namespace,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {

        String tenant = TenantContext.getTenantId();
        log.info("闲置清单请求: tenant={}, namespace={}, 窗口=[{},{}]", tenant, namespace, start, end);

        List<IdleResource> items = optimizationEngine.identifyIdleResources(tenant, namespace, start, end);
        double totalSaving = items.stream()
                .mapToDouble(IdleResource::getEstimatedSaving)
                .sum();

        DashboardResponse<IdleResource> resp = DashboardResponse.<IdleResource>builder()
                .items(items)
                .total(items.size())
                .start(start)
                .end(end)
                .tenant(tenant)
                .summary(Map.of(
                        "totalEstimatedSaving", totalSaving,
                        "idleResourceCount", items.size()
                ))
                .build();
        return ResponseEntity.ok(resp);
    }

    /**
     * 优化建议列表。
     */
    @GetMapping("/list")
    public ResponseEntity<DashboardResponse<OptimizationSuggestion>> list(
            @RequestParam(required = false) String namespace,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {

        String tenant = TenantContext.getTenantId();
        log.info("优化建议请求: tenant={}, namespace={}, 窗口=[{},{}]", tenant, namespace, start, end);

        List<OptimizationSuggestion> items = optimizationEngine.generateSuggestions(tenant, namespace, start, end);
        double totalSaving = items.stream()
                .mapToDouble(OptimizationSuggestion::getEstimatedMonthlySaving)
                .sum();

        DashboardResponse<OptimizationSuggestion> resp = DashboardResponse.<OptimizationSuggestion>builder()
                .items(items)
                .total(items.size())
                .start(start)
                .end(end)
                .tenant(tenant)
                .summary(Map.of(
                        "totalEstimatedMonthlySaving", totalSaving,
                        "suggestionCount", items.size()
                ))
                .build();
        return ResponseEntity.ok(resp);
    }
}