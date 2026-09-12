package com.levango7.dataenginebdp.ruleengine.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.ruleengine.model.BatchRuleExecutionRequest;
import com.levango7.dataenginebdp.ruleengine.model.BatchRuleExecutionResult;
import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionRequest;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionResult;
import com.levango7.dataenginebdp.ruleengine.service.RuleExecutionService;
import com.levango7.dataenginebdp.ruleengine.service.RuleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * 规则 REST 控制器。
 *
 * <p>提供规则 CRUD、执行与类型枚举端点。</p>
 *
 * <p><b>多租户隔离</b>（R10 安全修复）：所有端点通过 {@link #requireTenant()}
 * 从 {@link TenantContext} 取得租户 ID，缺失则抛 {@link IllegalStateException}
 * （fail-closed）。list 按 tenantId 过滤；get/update/delete 按 (id, tenantId)
 * 联合校验；create 时写入 tenantId；execute/executeBatch 按 tenantId 隔离。</p>
 *
 * <p><b>鉴权</b>（R8 安全修复）：类级 {@code @PreAuthorize("isAuthenticated()")}
 * 要求所有端点认证通过。</p>
 */
@RestController
@Tag(name = "规则引擎-规则管理", description = "规则CRUD与执行")
@RequestMapping("/api/v1/rules")
@PreAuthorize("isAuthenticated()")
public class RuleController {

    private final RuleService ruleService;
    private final RuleExecutionService ruleExecutionService;

    public RuleController(RuleService ruleService, RuleExecutionService ruleExecutionService) {
        this.ruleService = ruleService;
        this.ruleExecutionService = ruleExecutionService;
    }

    /** 创建规则 */
    @Operation(summary = "创建规则")
    @PostMapping
    public ResponseEntity<Rule> createRule(@RequestBody Rule rule) {
        String tenantId = requireTenant();
        rule.setTenantId(tenantId);
        Rule created = ruleService.create(rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 列出所有规则（按租户隔离） */
    @Operation(summary = "列出所有规则（按租户隔离）")
    @GetMapping
    public ResponseEntity<List<Rule>> listRules() {
        String tenantId = requireTenant();
        return ResponseEntity.ok(ruleService.findByTenantId(tenantId));
    }

    /** 获取单个规则（按租户隔离） */
    @Operation(summary = "获取单个规则（按租户隔离）")
    @GetMapping("/{id}")
    public ResponseEntity<?> getRule(@PathVariable Long id) {
        String tenantId = requireTenant();
        Rule rule = ruleService.getByIdAndTenantId(id, tenantId);
        if (rule == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "rule_not_found", "message", "Rule " + id + " not found"));
        }
        return ResponseEntity.ok(rule);
    }

    /** 更新规则（按租户隔离） */
    @Operation(summary = "更新规则（按租户隔离）")
    @PutMapping("/{id}")
    public ResponseEntity<?> updateRule(@PathVariable Long id, @RequestBody Rule rule) {
        String tenantId = requireTenant();
        Rule updated = ruleService.update(id, rule, tenantId);
        if (updated == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "rule_not_found", "message", "Rule " + id + " not found"));
        }
        return ResponseEntity.ok(updated);
    }

    /** 删除规则（按租户隔离） */
    @Operation(summary = "删除规则（按租户隔离）")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteRule(@PathVariable Long id) {
        String tenantId = requireTenant();
        boolean removed = ruleService.delete(id, tenantId);
        if (!removed) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "rule_not_found", "message", "Rule " + id + " not found"));
        }
        return ResponseEntity.noContent().build();
    }

    /** 执行规则（按租户隔离） */
    @Operation(summary = "执行规则（按租户隔离）")
    @PostMapping("/execute")
    public ResponseEntity<RuleExecutionResult> executeRule(@RequestBody RuleExecutionRequest request) {
        String tenantId = requireTenant();
        request.setTenantId(tenantId);
        RuleExecutionResult result = ruleExecutionService.execute(request);
        if ("ERROR".equals(result.getStatus()) && "rule_not_found".equals(result.getMessage())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** 批量执行规则（任务 F：并行 + 单条失败隔离；按租户隔离） */
    @Operation(summary = "批量执行规则（任务 F：并行 + 单条失败隔离；按租户隔离）")
    @PostMapping("/execute/batch")
    public ResponseEntity<BatchRuleExecutionResult> executeBatch(
            @RequestBody BatchRuleExecutionRequest request) {
        String tenantId = requireTenant();
        request.setTenantId(tenantId);
        return ResponseEntity.ok(ruleExecutionService.executeBatch(request));
    }

    /** 列出规则类型 */
    @Operation(summary = "列出规则类型")
    @GetMapping("/types")
    public ResponseEntity<List<String>> listRuleTypes() {
        requireTenant();
        return ResponseEntity.ok(List.of("DQ", "MASK", "ALERT"));
    }

    /**
     * 从 TenantContext 获取租户 ID，缺失则 fail-closed。
     *
     * @return 当前请求的租户 ID
     * @throws IllegalStateException 若 TenantContext 未设置租户 ID
     */
    private static String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }
}
