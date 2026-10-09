package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.common.MissingTenantContextException;
import com.levango7.dataenginebdp.encaps.quota.Quota;
import com.levango7.dataenginebdp.encaps.quota.QuotaRepository;
import com.levango7.dataenginebdp.encaps.security.AuditLog;
import com.levango7.dataenginebdp.common.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

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
@Tag(name = "封装租户-账户管理", description = "套餐/账单/配额查询")
@RequiredArgsConstructor
@RequestMapping("/api/v1/account")
public class AccountController {

    private final QuotaRepository quotaRepository;

    /**
     * 套餐档位目录（**唯一真源**：升级校验、账单月费与 {@code GET /plans} 都读这里）。
     *
     * <p>键集与前端 {@code frontend/src/api/account.ts} 的 {@code PlanTier} 一一对应；
     * 显示顺序由 {@link #PLAN_ORDER} 固化，供升级弹窗按稳定顺序渲染（台账 #62）。</p>
     */
    private static final Map<String, Map<String, Object>> PLANS;
    /** 档位显示顺序（低→高）。 */
    private static final List<String> PLAN_ORDER = List.of("free", "pro", "enterprise");
    static {
        Map<String, Map<String, Object>> plans = new LinkedHashMap<>();
        plans.put("free", Map.of("name", "免费版", "monthlyFee", 0, "cpu", "4", "memory", "8Gi"));
        plans.put("pro", Map.of("name", "专业版", "monthlyFee", 1999, "cpu", "16", "memory", "32Gi"));
        plans.put("enterprise", Map.of("name", "企业版", "monthlyFee", 9999, "cpu", "64", "memory", "128Gi"));
        PLANS = Map.copyOf(plans);
    }

    /** 当前套餐（根据配额量推断档位，轻量）。 */
    @Operation(summary = "当前套餐（根据配额量推断档位，轻量）")
    @GetMapping("/plan")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> plan() {
        String tenantId = tenantKey();
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

        Map<String, Object> planInfo = PLANS.get(tier);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", tier);
        body.put("planName", planInfo.get("name"));
        body.put("quotas", quotas.stream().map(this::quotaView).toList());
        return ResponseEntity.ok(body);
    }

    /** 套餐档位目录（升级弹窗的选项与价格真源；前端不再硬编码档位与月费）。 */
    @Operation(summary = "套餐档位目录（键/名称/月费/容量，按低→高排序）")
    @GetMapping("/plans")
    public ResponseEntity<Map<String, Object>> plans() {
        List<Map<String, Object>> list = PLAN_ORDER.stream()
                .map(key -> {
                    Map<String, Object> item = new LinkedHashMap<>(PLANS.get(key));
                    item.put("key", key);
                    return item;
                })
                .toList();
        return ResponseEntity.ok(Map.of("plans", list));
    }

    /** 账单明细（轻量：按配额套餐月费汇总）。 */
    @Operation(summary = "账单明细（轻量：按配额套餐月费汇总）")
    @GetMapping("/billing")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> billing() {
        String tenantId = tenantKey();
        List<Quota> quotas = quotaRepository.findByTenantId(tenantId);
        double cpuSum = quotas.stream().mapToDouble(q -> parseCpu(q.getCpuLimit())).sum();
        String tier = cpuSum > 32 ? "enterprise" : (cpuSum > 4 ? "pro" : "free");
        Map<String, Object> planInfo = PLANS.get(tier);
        double fee = ((Number) planInfo.get("monthlyFee")).doubleValue();

        // 键名以前端声明为准（frontend/src/api/account.ts 的 BillingItem：id/name/usage/cost）。
        // 此前这里是 item/amount/period，而 Account.vue 无条件取 row.cost.toLocaleString()——
        // 在 nightly 里一直没暴露，因为 /account 长期 403、页面从未拿到过数据。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(Map.of(
                "id", "subscription-" + tier,
                "name", "平台订阅费（" + planInfo.get("name") + "）",
                "usage", "CPU 配额合计 " + (long) cpuSum + " 核",
                "cost", fee)));
        body.put("totalCost", fee);
        return ResponseEntity.ok(body);
    }

    /** 升级套餐（轻量：记录并返回目标档费用；真实支付见 ROADMAP）。 */
    @Operation(summary = "升级套餐（轻量：记录并返回目标档费用；真实支付见 ROADMAP）")
    @AuditLog(action = "UPGRADE_PLAN", resource = "account")
    @PostMapping("/upgrade")
    public ResponseEntity<Map<String, Object>> upgrade(@RequestBody Map<String, String> req) {
        // R11 安全修复：校验租户上下文，fail-closed 拒绝无租户请求
        String tenantId = tenantKey();
        // 档位同样 fail-closed：此前缺键与未知值都被 getOrDefault 兜成 pro，等于按调用方
        // 没要求过的档位记账（前端弹窗默认值就是 flagship，见台账 #62）。真实计费接入前
        // 先把"静默换档"关掉，避免错账被当成正确路径依赖。
        String target = req.get("targetPlan");
        Map<String, Object> planInfo = target == null ? null
                : (Map<String, Object>) PLANS.get(target);
        if (planInfo == null) {
            log.warn("套餐升级被拒: 档位缺失或未知, tenant={}", tenantId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "targetPlan 缺失或不是受支持的套餐档位");
        }
        log.info("套餐升级请求: tenant={}, target={}", tenantId, target);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estimatedMonthlyFee", planInfo.get("monthlyFee"));
        body.put("status", "submitted");
        body.put("message", "升级已提交（支付/审批流程见 ROADMAP）");
        return ResponseEntity.ok(body);
    }

    /**
     * 取当前请求的租户业务键（字符串）。
     *
     * <p>此前这里 {@code Long.parseLong} 并在失败时抛 403，与 Quota/Workspace 控制器口径不一致
     * 且把非数字租户整个域拒掉；租户标识在本平台就是字符串业务键，故只保留"缺失即 403"。</p>
     *
     * @throws MissingTenantContextException 若 TenantContext 未设置租户 ID（→403）
     */
    private String tenantKey() {
        String tid = TenantContext.getTenantId();
        if (tid == null || tid.isBlank()) {
            throw new MissingTenantContextException();
        }
        return tid;
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
