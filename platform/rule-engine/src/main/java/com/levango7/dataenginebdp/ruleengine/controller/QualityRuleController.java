package com.levango7.dataenginebdp.ruleengine.controller;

import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.service.RuleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据质量规则端点（ROADMAP 前后端接线：前端 /quality/rules）。
 *
 * <p>前端 QualityRule 契约（targetTable/checkType/threshold/actionOnFail）
 * 映射到通用 {@link Rule} 模型存储；list 返回 PagedResult 契约。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/quality/rules")
public class QualityRuleController {

    private final RuleService ruleService;

    /** 创建/更新请求体（对齐前端 CreateRuleParams）。 */
    public record QualityRuleRequest(
            @NotBlank String name,
            @NotBlank String targetTable,
            String targetField,
            @NotBlank String checkType,
            @NotBlank String threshold,
            String actionOnFail) {
    }

    /** 列表（分页契约，对齐前端 PagedResult）。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<Rule> all = ruleService.listAll();
        int total = all.size();
        int start = Math.min((page - 1) * size, total);
        int end = Math.min(start + size, total);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", all.subList(start, end).stream().map(this::toView).toList());
        body.put("total", total);
        body.put("page", page);
        body.put("size", size);
        return ResponseEntity.ok(body);
    }

    /** 详情。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        Rule rule = ruleService.getById(id);
        if (rule == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toView(rule));
    }

    /** 创建（映射到 Rule 模型）。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody QualityRuleRequest req) {
        Rule rule = toRule(req);
        Rule saved = ruleService.create(rule);
        log.info("创建质量规则: id={}, name={}, table={}", saved.getId(), saved.getName(), req.targetTable());
        return ResponseEntity.ok(toView(saved));
    }

    /** 更新。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody QualityRuleRequest req) {
        Rule existing = ruleService.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        Rule patch = toRule(req);
        patch.setId(id);
        return ResponseEntity.ok(toView(ruleService.update(id, patch)));
    }

    /** 删除。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (ruleService.delete(id)) {
            return ResponseEntity.ok(Map.of("deleted", true));
        }
        return ResponseEntity.notFound().build();
    }

    /** 前端字段 → Rule 模型（校验规则语义编码到 name/description/expression）。 */
    private Rule toRule(QualityRuleRequest req) {
        Rule rule = new Rule();
        rule.setName(req.name());
        rule.setType("QUALITY_" + req.checkType().toUpperCase());
        rule.setExpression("threshold=" + req.threshold());
        rule.setSeverity(req.actionOnFail() != null ? req.actionOnFail() : "WARN");
        rule.setEnabled(true);
        rule.setDescription("quality rule on " + req.targetTable()
                + (req.targetField() != null ? "." + req.targetField() : ""));
        return rule;
    }

    /** Rule → 前端 QualityRule 视图。 */
    private Map<String, Object> toView(Rule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r.getId()));
        m.put("name", r.getName());
        // 解析 targetTable/targetField（description 格式 "quality rule on TABLE[.FIELD]"，
        // TABLE 可含库名如 ods.orders，FIELD 为最后一段）
        String desc = r.getDescription() == null ? "" : r.getDescription();
        String prefix = "quality rule on ";
        String table = desc.startsWith(prefix) ? desc.substring(prefix.length()) : "";
        int lastDot = table.lastIndexOf('.');
        m.put("targetTable", lastDot >= 0 ? table.substring(0, lastDot) : table);
        m.put("targetField", lastDot >= 0 ? table.substring(lastDot + 1) : "");
        m.put("checkType", r.getType() != null ? r.getType().replace("QUALITY_", "").toLowerCase() : "");
        m.put("threshold", r.getExpression() != null
                ? r.getExpression().replace("threshold=", "") : "");
        m.put("actionOnFail", r.getSeverity());
        m.put("status", Boolean.TRUE.equals(r.getEnabled()) ? "enabled" : "disabled");
        m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        m.put("updatedAt", r.getUpdatedAt() == null ? null : r.getUpdatedAt().toString());
        return m;
    }
}
