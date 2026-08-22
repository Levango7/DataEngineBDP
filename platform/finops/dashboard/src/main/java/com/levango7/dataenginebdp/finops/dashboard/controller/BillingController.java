package com.levango7.dataenginebdp.finops.dashboard.controller;

import com.levango7.dataenginebdp.finops.dashboard.collector.QueryBillingClient;
import com.levango7.dataenginebdp.common.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.Map;

/**
 * 计费账单端点（dashboard 侧代理）。
 *
 * <p>从 {@link TenantContext} 取租户（不信任 URL 参数），透传调用
 * cost-model 的账单聚合接口。</p>
 */
@Slf4j
@RestController
@Tag(name = "成本运营-账单代理", description = "dashboard侧计费账单透传")
@RequiredArgsConstructor
@RequestMapping("/api/v1/dashboard/billing")
public class BillingController {

    private final QueryBillingClient queryBillingClient;

    /**
     * 查询当前租户的查询计费账单。
     *
     * @param startDate 起始日期（yyyy-MM-dd，可空）
     * @param endDate   结束日期（yyyy-MM-dd，可空）
     * @return cost-model 透传响应
     */
    @GetMapping("/tenant")
    public ResponseEntity<?> tenantBilling(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "缺少租户上下文（TenantContext 未设置）"));
        }

        try {
            Map<String, Object> billing = queryBillingClient.fetchTenantBilling(tenantId, startDate, endDate);
            return ResponseEntity.ok(billing);
        } catch (Exception e) {
            log.warn("账单查询失败: tenant={}, err={}", tenantId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "账单服务暂不可用: " + e.getMessage()));
        }
    }

    /**
     * 查询当前租户的按日账单趋势（透传 cost-model）。
     *
     * @param startDate 起始日期（yyyy-MM-dd，可空）
     * @param endDate   结束日期（yyyy-MM-dd，可空）
     * @return cost-model 透传响应（含 points）
     */
    @GetMapping("/tenant/trend")
    public ResponseEntity<?> tenantBillingTrend(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "缺少租户上下文（TenantContext 未设置）"));
        }

        try {
            Map<String, Object> trend = queryBillingClient.fetchTenantBillingTrend(tenantId, startDate, endDate);
            return ResponseEntity.ok(trend);
        } catch (Exception e) {
            log.warn("账单趋势查询失败: tenant={}, err={}", tenantId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "账单趋势服务暂不可用: " + e.getMessage()));
        }
    }
}