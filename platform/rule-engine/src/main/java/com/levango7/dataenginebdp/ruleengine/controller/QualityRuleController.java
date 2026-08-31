package com.levango7.dataenginebdp.ruleengine.controller;

import com.levango7.dataenginebdp.ruleengine.engine.BuiltinRuleTemplates;
import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.service.QualityCheckExecutionService;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据质量规则端点（ROADMAP 前后端接线：前端 /quality/rules）。
 *
 * <p>前端 QualityRule 契约（targetTable/checkType/threshold/actionOnFail）
 * 映射到通用 {@link Rule} 模型存储；list 遵循平台分页契约（CONVENTIONS §9.4）。</p>
 */
@Slf4j
@RestController
@Tag(name = "规则引擎-质量规则", description = "数据质量规则CRUD与执行")
@RequiredArgsConstructor
@RequestMapping("/api/v1/quality/rules")
public class QualityRuleController {

    private static final int MAX_PAGE_SIZE = 100;

    private final RuleService ruleService;
    private final QualityCheckExecutionService executionService;

    /** 创建/更新请求体（对齐前端 CreateRuleParams）。 */
    public record QualityRuleRequest(
            @NotBlank String name,
            @NotBlank String targetTable,
            String targetField,
            @NotBlank String checkType,
            @NotBlank String threshold,
            String actionOnFail,
            /** 内置模板 ID（可选；提供时按模板+参数生成可执行 SQL，走真实校验路径）。 */
            String templateId,
            /** 模板参数（regex/minValue/maxValue/allowedValues 等，模板必填项校验）。 */
            Map<String, String> templateParams) {
    }

    /**
     * 内置质量规则模板列表（六大维度 × 11 模板，含示例 SQL）。
     *
     * <p>背景：此前 expression 恒为 "threshold=..."（降级猜测路径），
     * 业务人员需手写 SQL 才能真实校验。模板库把非空/唯一/值域/正则/
     * 参照/新鲜度/枚举等固化为一等公民，前端下拉选择即可。</p>
     */
    @Operation(summary = "内置质量规则模板列表（六大维度×11模板，含示例SQL）")
    @GetMapping("/templates")
    public ResponseEntity<List<Map<String, Object>>> listTemplates() {
        return ResponseEntity.ok(BuiltinRuleTemplates.listTemplates());
    }

    /**
     * 模板预览：渲染为可执行 SQL（不落库）。
     *
     * <p>前端在创建规则前可预览模板生成的 SQL；400 返回非法参数详情。</p>
     */
    @Operation(summary = "模板预览：渲染为可执行 SQL（不落库）")
    @PostMapping("/templates/preview")
    public ResponseEntity<?> previewTemplate(@RequestBody TemplatePreviewRequest req) {
        try {
            String expr = BuiltinRuleTemplates.renderSql(
                    req.templateId(), req.targetTable(), req.targetColumn(), req.params());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("templateId", req.templateId());
            body.put("expression", expr);
            return ResponseEntity.ok(body);
        } catch (BuiltinRuleTemplates.TemplateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_template", "message", e.getMessage()));
        }
    }

    /** 模板预览请求体。 */
    public record TemplatePreviewRequest(
            String templateId,
            String targetTable,
            String targetColumn,
            Map<String, String> params) {
    }

    /** 列表（分页契约：page/pageSize 入参，{list,total,page,size} 出参）。 */
    @Operation(summary = "列表（分页契约：page/pageSize 入参，{list,total,page,size} 出参）")
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int current = Math.max(page, 1);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        List<Rule> all = ruleService.listAll().stream()
                .sorted(Comparator.comparing(Rule::getCreatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                                .reversed()
                        .thenComparing(Rule::getId,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        int total = all.size();
        int start = Math.min((current - 1) * size, total);
        int end = Math.min(start + size, total);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", all.subList(start, end).stream().map(this::toView).toList());
        body.put("total", total);
        body.put("page", current);
        body.put("size", size);
        return ResponseEntity.ok(body);
    }

    /** 详情。 */
    @Operation(summary = "查询质量详情")
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        Rule rule = ruleService.getById(id);
        if (rule == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toView(rule));
    }

    /** 创建（映射到 Rule 模型）。 */
    @Operation(summary = "创建（映射到 Rule 模型）")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody QualityRuleRequest req) {
        Rule rule = toRule(req);
        Rule saved = ruleService.create(rule);
        log.info("创建质量规则: id={}, name={}, table={}", saved.getId(), saved.getName(), req.targetTable());
        return ResponseEntity.ok(toView(saved));
    }

    /** 更新。 */
    @Operation(summary = "更新质量")
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
    @Operation(summary = "删除质量")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (ruleService.delete(id)) {
            return ResponseEntity.ok(Map.of("deleted", true));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 立即触发规则校验。
     *
     * <p>对齐前端 {@code quality.ts} 的 {@code runCheck}。
     * 调用 {@link QualityCheckExecutionService#executeCheck(Rule)} 执行校验，
     * 并在返回视图中回写 {@code lastCheckAt} 与 {@code lastResult}。</p>
     *
     * @param id 规则 ID
     * @return 200 + 规则视图（含校验结果）；404 若不存在
     */
    @Operation(summary = "立即触发规则校验")
    @PostMapping("/{id}/check")
    public ResponseEntity<?> check(@PathVariable Long id) {
        Rule rule = ruleService.getById(id);
        if (rule == null) {
            return ResponseEntity.notFound().build();
        }
        log.info("触发质量规则校验: id={}, name={}", id, rule.getName());
        QualityCheckExecutionService.CheckResult result = executionService.executeCheck(rule);
        Map<String, Object> view = toView(rule);
        view.put("lastCheckAt", result.lastCheckAt().toString());
        view.put("lastResult", Map.of(
                "passed", result.passed(),
                "message", result.message()));
        return ResponseEntity.ok(view);
    }

    /**
     * 查询通过率统计。
     *
     * <p>对齐前端 {@code quality.ts} 的 {@code getSummary}。
     * 对所有规则执行校验后，从 {@link QualityCheckExecutionService} 获取真实通过数与通过率。</p>
     *
     * @return 200 + 通过率统计
     */
    @Operation(summary = "查询通过率统计")
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        List<Rule> all = ruleService.listAll();
        int total = all.size();
        // 对所有规则执行校验，获取真实通过数（确保通过率反映当前规则集状态）
        all.forEach(executionService::executeCheck);
        long passed = executionService.getPassedCount();
        double passRate = total == 0 ? 0.0 : (passed * 100.0 / total);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("passed", passed);
        summary.put("passRate", passRate);
        return ResponseEntity.ok(summary);
    }

    /** 前端字段 → Rule 模型。
     *
     * <p>模板路径（templateId 非空）：expression 为模板渲染的可执行 SQL
     * （sql: 前缀，走 DqRuleExecutor 真实校验；checkType 建议填模板 id）。
     * 降级路径（无模板）：expression 为 "threshold=..."（形式校验，兼容旧行为）。</p>
     */
    private Rule toRule(QualityRuleRequest req) {
        Rule rule = new Rule();
        rule.setName(req.name());
        rule.setType("QUALITY_" + req.checkType().toUpperCase());
        if (req.templateId() != null && !req.templateId().isBlank()) {
            String expr = BuiltinRuleTemplates.renderSql(
                    req.templateId(), req.targetTable(), req.targetField(), req.templateParams());
            rule.setExpression(expr);
        } else {
            rule.setExpression("threshold=" + req.threshold());
        }
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
