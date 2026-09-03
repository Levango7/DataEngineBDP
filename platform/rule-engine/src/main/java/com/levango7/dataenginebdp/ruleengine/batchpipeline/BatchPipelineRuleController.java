package com.levango7.dataenginebdp.ruleengine.batchpipeline;

import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.service.RuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * batch-pipeline 规则适配端点（M4 规则引擎归一）。
 *
 * <p>把平台质量规则翻译为 batch-pipeline 八类规则配置
 * （{@code config.quality.rules} 片段），供提交
 * {@code POST /api/v1/batches}（platform/batch-pipeline）时下发给
 * 三引擎（python/polars/spark）执行真实校验。规则管理/模板本身仍在本模块，
 * 批量行级校验执行归 batch-pipeline。</p>
 *
 * <p>端点：
 * <ul>
 *   <li>{@code POST /api/v1/quality/rules/batch-pipeline/translate} - 按模板规格批量翻译</li>
 *   <li>{@code POST /api/v1/quality/rules/batch-pipeline/translate/by-ids} - 按已存储规则 ID 翻译
 *       （模板参数未持久化，需参数的模板返回明确 unmapped 原因）</li>
 *   <li>{@code GET  /api/v1/quality/rules/batch-pipeline/mapping} - 模板 → 八类规则映射表</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Slf4j
@RestController
@Tag(name = "规则引擎-batch-pipeline适配", description = "平台质量规则翻译为batch-pipeline八类规则配置")
@RequiredArgsConstructor
@RequestMapping("/api/v1/quality/rules/batch-pipeline")
public class BatchPipelineRuleController {

    private final BatchPipelineRuleAdapter adapter;
    private final RuleService ruleService;

    /** 翻译请求体（translate）。 */
    public record TranslateRequest(List<BatchPipelineRuleAdapter.RuleSpec> rules) {
    }

    /** 按存储规则 ID 翻译请求体（translate/by-ids）。 */
    public record TranslateByIdsRequest(List<Long> ids) {
    }

    /**
     * 按模板规格批量翻译。
     *
     * @param req 模板规则规格列表
     * @return {rules: {数据集: 规则字典}, mapped, unmapped, datasetCount, mappedCount, unmappedCount}
     */
    @Operation(summary = "按模板规格批量翻译为 batch-pipeline 八类规则配置")
    @PostMapping("/translate")
    public ResponseEntity<?> translate(@RequestBody TranslateRequest req) {
        if (req == null || req.rules() == null) {
            return badRequest("invalid_request", "rules 必填（模板规则规格数组）");
        }
        BatchPipelineRuleAdapter.TranslateResult result = adapter.translateBatch(req.rules());
        log.info("batch-pipeline 规则翻译: mapped={}, unmapped={}, datasets={}",
                result.mapped().size(), result.unmapped().size(), result.rules().size());
        return ResponseEntity.ok(toView(result));
    }

    /**
     * 按已存储规则 ID 批量翻译。
     *
     * @param req 规则 ID 列表
     * @return 同 translate；未找到的 ID 记入 unmapped
     */
    @Operation(summary = "按已存储规则 ID 批量翻译（参数无关模板）")
    @PostMapping("/translate/by-ids")
    public ResponseEntity<?> translateByIds(@RequestBody TranslateByIdsRequest req) {
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            return badRequest("invalid_request", "ids 必填（规则 ID 数组）");
        }
        List<BatchPipelineRuleAdapter.RuleSpec> specs = new ArrayList<>();
        List<Map<String, Object>> notFound = new ArrayList<>();
        for (Long id : req.ids()) {
            Rule rule = ruleService.getById(id);
            if (rule == null) {
                notFound.add(Map.of("id", id, "reason", "规则不存在"));
                continue;
            }
            specs.add(adapter.specFromStoredRule(rule));
        }
        BatchPipelineRuleAdapter.TranslateResult result = adapter.translateBatch(specs);
        List<Map<String, Object>> unmapped = new ArrayList<>(notFound);
        for (BatchPipelineRuleAdapter.Translation t : result.unmapped()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("templateId", t.templateId());
            m.put("dataset", t.dataset());
            m.put("reason", t.reason());
            unmapped.add(m);
        }
        Map<String, Object> view = toView(new BatchPipelineRuleAdapter.TranslateResult(
                result.rules(), result.mapped(), List.of()));
        view.put("unmapped", unmapped);
        view.put("unmappedCount", unmapped.size());
        log.info("batch-pipeline 规则翻译(按ID): ids={}, mapped={}, unmapped={}",
                req.ids().size(), result.mapped().size(), unmapped.size());
        return ResponseEntity.ok(view);
    }

    /**
     * 模板 → batch-pipeline 八类规则映射表（静态同源视图，供前端/文档）。
     *
     * @return 映射条目列表
     */
    @Operation(summary = "模板 → batch-pipeline 八类规则映射表")
    @GetMapping("/mapping")
    public ResponseEntity<List<Map<String, Object>>> mapping() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("not_null", "completeness", "非空检查 → required_columns（null 与空串同等判缺）"));
        rows.add(row("pk_not_null", "completeness", "主键完整性 → required_columns"));
        rows.add(row("unique", "uniqueness", "唯一性 → columns（columns 参数组合键优先）"));
        rows.add(row("regex_match", "format", "正则格式 → format 正则原样下发"));
        rows.add(row("value_range", "range", "值域 → 闭区间 [minValue, maxValue]"));
        rows.add(row("enum_whitelist", "allowed_values", "枚举白名单 → 允许值列表"));
        rows.add(row("enum_blacklist", "format",
                "枚举黑名单 → 负向先行 ^(?!(?:v1|v2)$)；空串在 format 中视为通过（边界语义差异）"));
        rows.add(row("fk_reference", "referential",
                "参照完整性 → 表.列（参考表名不能含 schema 前缀）"));
        rows.add(row("row_count_range", "—", "不支持：八类为行级校验，无表级行数波动类"));
        rows.add(row("not_null_if", "—", "不支持：条件非空需条件类规则"));
        rows.add(row("freshness", "—", "不支持：date_valid 仅支持静态时间窗"));
        return ResponseEntity.ok(rows);
    }

    private static Map<String, Object> row(String templateId, String batchPipelineClass, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateId", templateId);
        m.put("batchPipelineClass", batchPipelineClass);
        m.put("supported", !"—".equals(batchPipelineClass));
        m.put("note", note);
        return m;
    }

    private static Map<String, Object> toView(BatchPipelineRuleAdapter.TranslateResult result) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rules", result.rules());
        view.put("datasetCount", result.rules().size());
        view.put("mappedCount", result.mapped().size());
        view.put("unmappedCount", result.unmapped().size());
        view.put("mapped", result.mapped());
        view.put("unmapped", result.unmapped());
        return view;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.badRequest().body(body);
    }
}
