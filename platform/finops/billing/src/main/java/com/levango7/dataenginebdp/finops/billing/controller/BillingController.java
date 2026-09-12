package com.levango7.dataenginebdp.finops.billing.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.finops.billing.model.BillingGenerateRequest;
import com.levango7.dataenginebdp.finops.billing.model.BillingGenerateResponse;
import com.levango7.dataenginebdp.finops.billing.service.BillingGenerator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 账单生成 REST API。
 *
 * <p>出账闭环起点：POST 触发账单生成，从 Prometheus 采集租户级成本，
 * 产出租户级成本账单 JSON 并持久化。</p>
 *
 * <p>租户 ID 强制从 {@link TenantContext} 获取，不信任请求参数，防止跨租户越权出账。</p>
 *
 * <p>接口契约（出账闭环设计 v1.0）：</p>
 * <ul>
 *   <li>{@code POST /api/finops/v1/billing/generate} 触发账单生成</li>
 *   <li>{@code GET  /api/finops/v1/billing/{id}} 查询账单详情</li>
 *   <li>{@code GET  /api/finops/v1/billing} 查询当前租户全部账单</li>
 * </ul>
 */
@Slf4j
@RestController
@Tag(name = "出账闭环-账单生成", description = "FinOps 租户级成本账单生成")
@RequiredArgsConstructor
@RequestMapping("/api/finops/v1/billing")
public class BillingController {

    private final BillingGenerator billingGenerator;

    /**
     * 触发账单生成。
     *
     * <p>从 Prometheus 采集当前租户在指定账期内的资源用量，
     * 按单价计算各项金额，汇总成账单并持久化。</p>
     *
     * @param request 生成请求（含账期与采集窗口，均可选）
     * @return 生成的账单
     */
    @Operation(summary = "触发租户级成本账单生成")
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@Valid @RequestBody(required = false) BillingGenerateRequest request) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "缺少租户上下文（TenantContext 未设置）"));
        }

        if (request == null) {
            request = BillingGenerateRequest.builder().build();
        }
        log.info("收到账单生成请求: tenant={}, period={}", tenantId, request.getBillingPeriod());

        BillingGenerateResponse response = billingGenerator.generate(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 查询账单详情。
     *
     * @param billingId 账单 ID
     * @return 账单详情
     */
    @Operation(summary = "查询账单详情")
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable("id") String billingId) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "缺少租户上下文（TenantContext 未设置）"));
        }

        return billingGenerator.getById(billingId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "账单不存在", "id", billingId)));
    }

    /**
     * 查询当前租户全部账单。
     *
     * @return 账单列表（按生成时间倒序）
     */
    @Operation(summary = "查询当前租户全部账单")
    @GetMapping
    public ResponseEntity<?> listByTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "缺少租户上下文（TenantContext 未设置）"));
        }

        List<BillingGenerateResponse> bills = billingGenerator.listByTenant(tenantId);
        return ResponseEntity.ok(Map.of(
                "tenant", tenantId,
                "count", bills.size(),
                "bills", bills));
    }
}