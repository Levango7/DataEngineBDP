package com.levango7.dataenginebdp.finops.controller;

import com.levango7.dataenginebdp.finops.collector.ResourceUsageCollector;
import com.levango7.dataenginebdp.finops.model.BillingMethod;
import com.levango7.dataenginebdp.finops.model.CostCalculationRequest;
import com.levango7.dataenginebdp.finops.model.CostCalculationResponse;
import com.levango7.dataenginebdp.finops.model.CostResult;
import com.levango7.dataenginebdp.finops.model.ResourceUsage;
import com.levango7.dataenginebdp.finops.security.TenantContext;
import com.levango7.dataenginebdp.finops.service.CostCalculationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 成本 REST API 控制器。
 *
 * <p>提供两个核心端点：</p>
 * <ul>
 *   <li>POST /api/v1/cost/calculate — 计算成本（支持按量/包年/阶梯三种计费方式）</li>
 *   <li>GET  /api/v1/cost/report   — 生成成本报告（从 Prometheus 采集用量后计算）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/cost")
public class CostController {

    private static final Logger log = LoggerFactory.getLogger(CostController.class);

    private final CostCalculationService costCalculationService;
    private final ResourceUsageCollector collector;

    public CostController(CostCalculationService costCalculationService,
                          ResourceUsageCollector collector) {
        this.costCalculationService = costCalculationService;
        this.collector = collector;
    }

    /**
     * 计算成本。
     *
     * <p>请求体含资源用量列表与计费方式，响应含按 tenant+namespace 分组的成本结果。</p>
     *
     * @param request 成本计算请求
     * @return 成本计算响应
     */
    @PostMapping("/calculate")
    public ResponseEntity<CostCalculationResponse> calculate(
            @Valid @RequestBody CostCalculationRequest request) {
        log.info("成本计算请求: 计费方式={}, 用量条数={}, 租户={}",
                request.getBillingMethod(), request.getUsages().size(),
                TenantContext.getTenantId());
        CostCalculationResponse response = costCalculationService.calculate(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 生成成本报告。
     *
     * <p>从 Prometheus 采集当前租户指定 namespace 在时间窗口内的五维度用量，
     * 按指定计费方式计算成本并返回。</p>
     *
     * @param namespace       Kubernetes namespace
     * @param billingMethod   计费方式（ON_DEMAND/RESERVED/TIERED）
     * @param pricingConfigName 定价配置名（可选，默认 default）
     * @param start           窗口起始时间（ISO-8601）
     * @param end             窗口结束时间（ISO-8601）
     * @return 成本计算响应
     */
    @GetMapping("/report")
    public ResponseEntity<CostCalculationResponse> report(
            @RequestParam String namespace,
            @RequestParam(defaultValue = "ON_DEMAND") BillingMethod billingMethod,
            @RequestParam(required = false) String pricingConfigName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {

        log.info("成本报告请求: namespace={}, 计费={}, 窗口=[{},{}], 租户={}",
                namespace, billingMethod, start, end, TenantContext.getTenantId());

        // 1. 从 Prometheus 采集五维度用量
        List<ResourceUsage> usages = collector.collectForCurrentTenant(namespace, start, end);

        // 2. 构造计算请求
        CostCalculationRequest request = CostCalculationRequest.builder()
                .usages(usages)
                .billingMethod(billingMethod)
                .pricingConfigName(pricingConfigName)
                .build();

        // 3. 计算成本
        CostCalculationResponse response = costCalculationService.calculate(request);
        return ResponseEntity.ok(response);
    }
}