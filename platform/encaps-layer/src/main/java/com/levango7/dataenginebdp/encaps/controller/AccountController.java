package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.quota.Quota;
import com.levango7.dataenginebdp.encaps.quota.QuotaRepository;
import com.levango7.dataenginebdp.encaps.security.AuditLog;
import com.levango7.dataenginebdp.encaps.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账户与配额端点（ROADMAP 前后端接线：前端 /account）。
 *
 * <p>套餐/账单/升级；配额数据复用 {@link QuotaRepository}（真实租户数据），
 * 定价与账单为轻量实现（完整计费见 finops cost-model）。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/account")
public class AccountController {

    private final QuotaRepository quotaRepository;

    /** 套餐档位（对齐前端 PlanTier）。 */
    private static final Map<String, Object> PLANS = Map.of(
            "free", Map.of("name", "免费版", "monthlyFee", 0, "cpu", "4", "memory", "8Gi"),
            "pro", Map.of("name", "专业版", "monthlyFee", 1999, "cpu", "16", "memory", "32Gi"),
            "enterprise", Map.of("name", "企业版", "monthlyFee", 9999, "cpu", "64", "memory", "128Gi"));

    /** 当前套餐（根据配额量推断档位，轻量）。 */
    @GetMapping("/plan")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> plan() {
        Long tenantId = tenantIdLong();
        List<Quota> quotas = quotaRepository.findByTenantId(tenantId);
        // 有配额 → 按 CPU 总量选档；无 → 免费版
        String tier = "free";
        double cpuSum = quotas.stream()
                .mapToDouble(q -> parseCpu(q.getCpuLimit())).sum();
        if (cpuSum > 32) {
            tier = "enterprise";
        } else if (cpuSum > 4) {
            tier = "pro";
        }

        Map<String, Object> planInfo = (Map<String, Object>) PLANS.get(tier);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", tier);
        body.put("planName", planInfo.get("name"));
        body.put("quotas", quotas.stream().map(this::quotaView).toList());
        return ResponseEntity.ok(body);
    }

    /** 账单明细（轻量：按配额套餐月费汇总）。 */
    @GetMapping("/billing")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> billing() {
        Long tenantId = tenantIdLong();
        List<Quota> quotas = quotaRepository.findByTenantId(tenantId);
        double cpuSum = quotas.stream().mapToDouble(q -> parseCpu(q.getCpuLimit())).sum();
        String tier = cpuSum > 32 ? "enterprise" : (cpuSum > 4 ? "pro" : "free");
        double fee = ((Number) ((Map<?, ?>) PLANS.get(tier)).get("monthlyFee")).doubleValue();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(Map.of(
                "item", "平台订阅费（" + tier + " 档）",
                "amount", fee,
                "period", "月")));
        body.put("totalCost", fee);
        return ResponseEntity.ok(body);
    }

    /** 升级套餐（轻量：记录并返回目标档费用；真实支付见 ROADMAP）。 */
    @AuditLog(action = "UPGRADE_PLAN", resource = "account")
    @PostMapping("/upgrade")
    public ResponseEntity<Map<String, Object>> upgrade(@RequestBody Map<String, String> req) {
        String target = req.getOrDefault("targetPlan", "pro");
        Map<String, Object> planInfo = (Map<String, Object>) PLANS.getOrDefault(target, PLANS.get("pro"));
        log.info("套餐升级请求: tenant={}, target={}", TenantContext.getTenantId(), target);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estimatedMonthlyFee", planInfo.get("monthlyFee"));
        body.put("status", "submitted");
        body.put("message", "升级已提交（支付/审批流程见 ROADMAP）");
        return ResponseEntity.ok(body);
    }

    /** TenantContext(字符串) → Long。 */
    private Long tenantIdLong() {
        String tid = TenantContext.getTenantId();
        if (tid == null || tid.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        try {
            return Long.parseLong(tid);
        } catch (NumberFormatException e) {
            // 非数字租户（Keycloak sub）：用 0 占位（配额按租户隔离时需调整）
            return 0L;
        }
    }

    /** 解析 CPU 限制为数字（支持 "4" / "4000m"）。 */
    private double parseCpu(String cpu) {
        if (cpu == null || cpu.isBlank()) {
            return 0;
        }
        try {
            if (cpu.endsWith("m")) {
                return Double.parseDouble(cpu.substring(0, cpu.length() - 1)) / 1000.0;
            }
            return Double.parseDouble(cpu);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 配额视图（对齐前端 QuotaItem 最小字段）。 */
    private Map<String, Object> quotaView(Quota q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workspaceId", String.valueOf(q.getWorkspaceId()));
        m.put("cpuLimit", q.getCpuLimit());
        m.put("memoryLimit", q.getMemoryLimit());
        m.put("storageLimit", q.getStorageLimit());
        m.put("podLimit", q.getPodLimit());
        return m;
    }
}
