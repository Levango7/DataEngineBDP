package com.levango7.dataenginebdp.ruleengine.controller;

import com.levango7.dataenginebdp.ruleengine.model.BatchRuleExecutionRequest;
import com.levango7.dataenginebdp.ruleengine.model.BatchRuleExecutionResult;
import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionRequest;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionResult;
import com.levango7.dataenginebdp.ruleengine.service.RuleExecutionService;
import com.levango7.dataenginebdp.ruleengine.service.RuleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 */
@RestController
@Tag(name = "规则引擎-规则管理", description = "规则CRUD与执行")
@RequestMapping("/api/v1/rules")
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
        Rule created = ruleService.create(rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 列出所有规则 */
    @Operation(summary = "列出所有规则")
    @GetMapping
    public ResponseEntity<List<Rule>> listRules() {
        return ResponseEntity.ok(ruleService.listAll());
    }

    /** 获取单个规则 */
    @Operation(summary = "获取单个规则")
    @GetMapping("/{id}")
    public ResponseEntity<?> getRule(@PathVariable Long id) {
        Rule rule = ruleService.getById(id);
        if (rule == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "rule_not_found", "message", "Rule " + id + " not found"));
        }
        return ResponseEntity.ok(rule);
    }

    /** 更新规则 */
    @Operation(summary = "更新规则")
    @PutMapping("/{id}")
    public ResponseEntity<?> updateRule(@PathVariable Long id, @RequestBody Rule rule) {
        Rule updated = ruleService.update(id, rule);
        if (updated == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "rule_not_found", "message", "Rule " + id + " not found"));
        }
        return ResponseEntity.ok(updated);
    }

    /** 删除规则 */
    @Operation(summary = "删除规则")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteRule(@PathVariable Long id) {
        boolean removed = ruleService.delete(id);
        if (!removed) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "rule_not_found", "message", "Rule " + id + " not found"));
        }
        return ResponseEntity.noContent().build();
    }

    /** 执行规则 */
    @Operation(summary = "执行规则")
    @PostMapping("/execute")
    public ResponseEntity<RuleExecutionResult> executeRule(@RequestBody RuleExecutionRequest request) {
        RuleExecutionResult result = ruleExecutionService.execute(request);
        if ("ERROR".equals(result.getStatus()) && "RULE_NOT_FOUND".equals(result.getMessage())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** 批量执行规则（任务 F：并行 + 单条失败隔离） */
    @Operation(summary = "批量执行规则（任务 F：并行 + 单条失败隔离）")
    @PostMapping("/execute/batch")
    public ResponseEntity<BatchRuleExecutionResult> executeBatch(
            @RequestBody BatchRuleExecutionRequest request) {
        return ResponseEntity.ok(ruleExecutionService.executeBatch(request));
    }

    /** 列出规则类型 */
    @Operation(summary = "列出规则类型")
    @GetMapping("/types")
    public ResponseEntity<List<String>> listRuleTypes() {
        return ResponseEntity.ok(List.of("DQ", "MASK", "ALERT"));
    }
}