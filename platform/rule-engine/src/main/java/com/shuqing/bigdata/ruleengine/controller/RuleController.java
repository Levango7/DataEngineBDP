package com.shuqing.bigdata.ruleengine.controller;

import com.shuqing.bigdata.ruleengine.model.Rule;
import com.shuqing.bigdata.ruleengine.model.RuleExecutionRequest;
import com.shuqing.bigdata.ruleengine.model.RuleExecutionResult;
import com.shuqing.bigdata.ruleengine.service.RuleExecutionService;
import com.shuqing.bigdata.ruleengine.service.RuleService;
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

import java.util.List;
import java.util.Map;

/**
 * 规则 REST 控制器。
 *
 * <p>提供规则 CRUD、执行与类型枚举端点。</p>
 */
@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private final RuleService ruleService;
    private final RuleExecutionService ruleExecutionService;

    public RuleController(RuleService ruleService, RuleExecutionService ruleExecutionService) {
        this.ruleService = ruleService;
        this.ruleExecutionService = ruleExecutionService;
    }

    /** 创建规则 */
    @PostMapping
    public ResponseEntity<Rule> createRule(@RequestBody Rule rule) {
        Rule created = ruleService.create(rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 列出所有规则 */
    @GetMapping
    public ResponseEntity<List<Rule>> listRules() {
        return ResponseEntity.ok(ruleService.listAll());
    }

    /** 获取单个规则 */
    @GetMapping("/{id}")
    public ResponseEntity<Rule> getRule(@PathVariable Long id) {
        Rule rule = ruleService.getById(id);
        if (rule == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(rule);
    }

    /** 更新规则 */
    @PutMapping("/{id}")
    public ResponseEntity<Rule> updateRule(@PathVariable Long id, @RequestBody Rule rule) {
        Rule updated = ruleService.update(id, rule);
        if (updated == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(updated);
    }

    /** 删除规则 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable Long id) {
        boolean removed = ruleService.delete(id);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    /** 执行规则 */
    @PostMapping("/execute")
    public ResponseEntity<RuleExecutionResult> executeRule(@RequestBody RuleExecutionRequest request) {
        RuleExecutionResult result = ruleExecutionService.execute(request);
        if ("ERROR".equals(result.getStatus()) && "RULE_NOT_FOUND".equals(result.getMessage())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** 列出规则类型 */
    @GetMapping("/types")
    public ResponseEntity<List<String>> listRuleTypes() {
        return ResponseEntity.ok(List.of("DQ", "MASK", "ALERT"));
    }
}