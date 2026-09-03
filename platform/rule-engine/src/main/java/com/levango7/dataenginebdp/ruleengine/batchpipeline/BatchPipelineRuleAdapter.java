package com.levango7.dataenginebdp.ruleengine.batchpipeline;

import com.levango7.dataenginebdp.ruleengine.engine.BuiltinRuleTemplates;
import com.levango7.dataenginebdp.ruleengine.model.Rule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 平台质量规则 → batch-pipeline 八类规则适配器（M4 规则引擎归一）。
 *
 * <p>batch-pipeline 的 {@code batch_pipeline.quality.RuleEngine}（platform/batch-pipeline）
 * 支持 8 类行级规则：completeness / uniqueness / range / allowed_values / format /
 * date_valid / referential / outlier。本适配器把 rule-engine 内置质量模板
 * （{@link BuiltinRuleTemplates}，11 模板 × 6 维度）映射为该配置结构，产出可直接
 * 并入 {@code POST /api/v1/batches} body 的 {@code config.quality.rules} 片段，
 * 由 batch-pipeline 三引擎（python/polars/spark）执行真实校验。</p>
 *
 * <p>映射表（{@code GET /api/v1/quality/rules/batch-pipeline/mapping} 提供同源视图）：</p>
 * <table border="1">
 *   <tr><th>模板</th><th>batch-pipeline 类</th><th>说明</th></tr>
 *   <tr><td>not_null / pk_not_null</td><td>completeness.required_columns</td><td>batch-pipeline 对 null 与空串同等判缺</td></tr>
 *   <tr><td>unique</td><td>uniqueness.columns</td><td>columns 参数（组合键）优先</td></tr>
 *   <tr><td>regex_match</td><td>format</td><td>正则原样下发（Python re / Java rlike 兼容模式）</td></tr>
 *   <tr><td>value_range</td><td>range[]</td><td>闭区间 [min, max]</td></tr>
 *   <tr><td>enum_whitelist</td><td>allowed_values</td><td>逗号分隔枚举</td></tr>
 *   <tr><td>enum_blacklist</td><td>format</td><td>负向先行 {@code ^(?!(?:v1|v2)$)}；空串在
 *       batch-pipeline format 中视为通过（SQL 模板判违规），边界语义差异已知</td></tr>
 *   <tr><td>fk_reference</td><td>referential</td><td>目标仅支持 {@code 表.列}（batch-pipeline
 *       按点号二段切分，参考表名不能含 schema 前缀）</td></tr>
 *   <tr><td>row_count_range / not_null_if / freshness</td><td>—</td><td>无对应行级类，明确拒绝</td></tr>
 * </table>
 *
 * <p>职责边界（M4 归一分工）：规则管理/模板/执行留在 rule-engine；批量数据校验执行
 * 归 batch-pipeline；实时链路归 real-time-pipeline；联邦查询归 federated-query。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class BatchPipelineRuleAdapter {

    /** 单条规则翻译输入（与 QualityRuleController 的模板契约同构）。 */
    public record RuleSpec(String templateId, String targetTable, String targetColumn,
                           Map<String, String> params) {
    }

    /** 单条规则翻译结果：mapped=false 时 reason 说明原因。 */
    public record Translation(String templateId, String dataset, boolean mapped,
                              Map<String, Object> fragment, String reason, String note) {
    }

    /** 批量翻译结果：rules 为 config.quality.rules 的值（{数据集: 规则字典}）。 */
    public record TranslateResult(Map<String, Object> rules, List<Translation> mapped,
                                  List<Translation> unmapped) {
    }

    /** 可映射模板集合（其余模板在 translateOne 中给出明确拒绝原因）。 */
    private static final Set<String> SUPPORTED = Set.of(
            "not_null", "pk_not_null", "unique", "regex_match",
            "value_range", "enum_whitelist", "enum_blacklist", "fk_reference");

    /** enum_blacklist 负向先行中的正则元字符转义。 */
    private static final Pattern REGEX_META = Pattern.compile("([\\\\.\\[\\]{}()|\\^$*+?])");

    /**
     * 翻译单条模板规则。
     *
     * @param spec 模板规则规格
     * @return 翻译结果（fragment 为该规则贡献的 batch-pipeline 规则片段，已按数据集归位）
     */
    public Translation translateOne(RuleSpec spec) {
        String templateId = spec.templateId() == null ? "" : spec.templateId().trim().toLowerCase();
        String table = spec.targetTable() == null ? "" : spec.targetTable().trim();
        String column = spec.targetColumn() == null ? "" : spec.targetColumn().trim();
        Map<String, String> p = spec.params() == null ? Map.of() : spec.params();

        if (templateId.isEmpty() || table.isEmpty()) {
            return unmapped(spec, templateId, table, "templateId 与 targetTable 必填");
        }
        if (!BuiltinRuleTemplates.templateIds().contains(templateId)) {
            return unmapped(spec, templateId, table, "未知模板: " + templateId);
        }
        if (!templateId.equals("row_count_range") && column.isEmpty()) {
            return unmapped(spec, templateId, table, "targetColumn 必填（row_count_range 除外）");
        }

        return switch (templateId) {
            case "not_null", "pk_not_null" -> mapped(spec, templateId, table, Map.of(
                    "completeness", Map.of("required_columns", List.of(column))), null);
            case "unique" -> {
                String cols = p.getOrDefault("columns", column);
                yield mapped(spec, templateId, table, Map.of(
                        "uniqueness", Map.of("columns", splitCsv(cols))), null);
            }
            case "regex_match" -> {
                String regex = p.get("regex");
                if (regex == null || regex.isBlank()) {
                    yield unmapped(spec, templateId, table, "缺少必填参数: regex");
                }
                try {
                    Pattern.compile(regex);
                } catch (Exception e) {
                    yield unmapped(spec, templateId, table, "非法正则: " + e.getMessage());
                }
                yield mapped(spec, templateId, table, Map.of(
                        "format", Map.of(column, regex)), null);
            }
            case "value_range" -> {
                Double min = parseDouble(p.get("minValue"));
                Double max = parseDouble(p.get("maxValue"));
                if (min == null || max == null) {
                    yield unmapped(spec, templateId, table, "缺少/非法数值参数: minValue/maxValue");
                }
                if (max < min) {
                    yield unmapped(spec, templateId, table, "值域非法: max < min");
                }
                yield mapped(spec, templateId, table, Map.of(
                        "range", List.of(Map.of("column", column, "min", min, "max", max))), null);
            }
            case "enum_whitelist" -> {
                List<String> allowed = splitCsv(p.get("allowedValues"));
                if (allowed.isEmpty()) {
                    yield unmapped(spec, templateId, table, "缺少必填参数: allowedValues");
                }
                yield mapped(spec, templateId, table, Map.of(
                        "allowed_values", Map.of(column, allowed)), null);
            }
            case "enum_blacklist" -> {
                List<String> forbidden = splitCsv(p.get("forbiddenValues"));
                if (forbidden.isEmpty()) {
                    yield unmapped(spec, templateId, table, "缺少必填参数: forbiddenValues");
                }
                String joined = forbidden.stream()
                        .map(v -> REGEX_META.matcher(v).replaceAll("\\\\$1"))
                        .reduce((a, b) -> a + "|" + b).orElse("");
                String pattern = "^(?!(?:" + joined + ")$)";
                yield mapped(spec, templateId, table, Map.of(
                                "format", Map.of(column, pattern)),
                        "黑名单以负向先行正则表达；空串在 batch-pipeline format 中视为通过"
                                + "（SQL 模板判违规），null 始终通过，两边一致");
            }
            case "fk_reference" -> {
                String refTable = p.get("refTable");
                String refColumn = p.get("refColumn");
                if (refTable == null || refTable.isBlank() || refColumn == null || refColumn.isBlank()) {
                    yield unmapped(spec, templateId, table, "缺少必填参数: refTable/refColumn");
                }
                if (refTable.contains(".")) {
                    yield unmapped(spec, templateId, table,
                            "batch-pipeline referential 目标仅支持 表.列（参考表名不能含 schema 前缀）");
                }
                yield mapped(spec, templateId, table, Map.of(
                        "referential", Map.of(column, refTable + "." + refColumn)), null);
            }
            case "row_count_range" -> unmapped(spec, templateId, table,
                    "batch-pipeline 八类规则为行级校验，无表级行数波动类");
            case "not_null_if" -> unmapped(spec, templateId, table,
                    "条件非空需条件类规则，八类不支持");
            case "freshness" -> unmapped(spec, templateId, table,
                    "时效性检查无对应类（date_valid 仅支持静态时间窗）");
            default -> unmapped(spec, templateId, table, "未知模板: " + templateId);
        };
    }

    /**
     * 批量翻译并按数据集聚合为 config.quality.rules 片段。
     *
     * @param specs 模板规则规格列表
     * @return {rules: {数据集: 规则字典}, mapped: [...], unmapped: [...]}
     */
    public TranslateResult translateBatch(List<RuleSpec> specs) {
        Map<String, Object> rules = new LinkedHashMap<>();
        List<Translation> mapped = new ArrayList<>();
        List<Translation> unmapped = new ArrayList<>();
        if (specs != null) {
            for (RuleSpec spec : specs) {
                Translation t = translateOne(spec);
                if (t.mapped()) {
                    mapped.add(t);
                    mergeFragment(rules, t.dataset(), t.fragment());
                } else {
                    unmapped.add(t);
                }
            }
        }
        return new TranslateResult(rules, mapped, unmapped);
    }

    /**
     * 从已存储的平台规则恢复翻译规格（仅参数无关模板可完整恢复）。
     *
     * <p>存储约定（QualityRuleController.toRule）：type = {@code QUALITY_<checkType>}
     * （checkType 建议填模板 id），description = {@code quality rule on TABLE[.FIELD]}。
     * 模板参数未持久化，需要参数的模板将得到明确 unmapped 原因。</p>
     *
     * @param rule 已存储规则
     * @return 翻译规格（无法恢复时 templateId 为空，translateOne 会拒绝）
     */
    public RuleSpec specFromStoredRule(Rule rule) {
        String type = rule.getType() == null ? "" : rule.getType();
        String templateId = type.startsWith("QUALITY_")
                ? type.substring("QUALITY_".length()).toLowerCase() : "";
        String desc = rule.getDescription() == null ? "" : rule.getDescription();
        String prefix = "quality rule on ";
        String target = desc.startsWith(prefix) ? desc.substring(prefix.length()) : "";
        int lastDot = target.lastIndexOf('.');
        String table = lastDot >= 0 ? target.substring(0, lastDot) : target;
        String column = lastDot >= 0 ? target.substring(lastDot + 1) : "";
        return new RuleSpec(templateId, table, column, Map.of());
    }

    /** 片段并入数据集规则字典：List 类键追加、Map 类键合并（后写覆盖同键）。 */
    @SuppressWarnings("unchecked")
    private void mergeFragment(Map<String, Object> rules, String dataset, Map<String, Object> fragment) {
        Map<String, Object> dst = (Map<String, Object>) rules.computeIfAbsent(dataset,
                k -> new LinkedHashMap<String, Object>());
        for (Map.Entry<String, Object> e : fragment.entrySet()) {
            Object v = e.getValue();
            if (v instanceof List<?> list) {
                List<Object> acc = (List<Object>) dst.computeIfAbsent(e.getKey(),
                        k -> new ArrayList<Object>());
                acc.addAll(list);
                // 等价规则条目去重（保持首次出现顺序），避免同列规则在八类配置中重复计数
                List<Object> deduped = acc.stream().distinct().toList();
                acc.clear();
                acc.addAll(deduped);
            } else if (v instanceof Map<?, ?> map) {
                Map<String, Object> acc = (Map<String, Object>) dst.computeIfAbsent(e.getKey(),
                        k -> new LinkedHashMap<String, Object>());
                for (Map.Entry<String, Object> ie : ((Map<String, Object>) map).entrySet()) {
                    Object existing = acc.get(ie.getKey());
                    if (existing instanceof List<?> existingList && ie.getValue() instanceof List<?> incoming) {
                        List<Object> merged = new ArrayList<Object>(existingList);
                        merged.addAll(incoming);
                        acc.put(ie.getKey(), merged.stream().distinct().toList());
                    } else {
                        acc.put(ie.getKey(), ie.getValue());
                    }
                }
            } else {
                dst.put(e.getKey(), v);
            }
        }
    }

    private Translation mapped(RuleSpec spec, String templateId, String dataset,
                               Map<String, Object> fragment, String note) {
        return new Translation(templateId, dataset, true, fragment, null, note);
    }

    private Translation unmapped(RuleSpec spec, String templateId, String dataset, String reason) {
        return new Translation(templateId, dataset, false, null, reason, null);
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static Double parseDouble(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
