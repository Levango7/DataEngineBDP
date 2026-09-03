package com.levango7.dataenginebdp.ruleengine.batchpipeline;

import com.levango7.dataenginebdp.ruleengine.model.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BatchPipelineRuleAdapter} 单元测试。
 *
 * <p>映射语义对齐 platform/batch-pipeline {@code batch_pipeline.quality.RuleEngine}
 * 的八类规则（format 类正则按 python re.match / Spark rlike 的锚定语义验证）。</p>
 *
 * @author shuqing-bigdata
 */
@DisplayName("平台质量规则 → batch-pipeline 八类规则适配测试")
class BatchPipelineRuleAdapterTest {

    private final BatchPipelineRuleAdapter adapter = new BatchPipelineRuleAdapter();

    private static BatchPipelineRuleAdapter.RuleSpec spec(String templateId, String table,
                                                          String column, Map<String, String> params) {
        return new BatchPipelineRuleAdapter.RuleSpec(templateId, table, column, params);
    }

    @Test
    @DisplayName("not_null / pk_not_null → completeness.required_columns")
    void notNullMapsToCompleteness() {
        for (String t : List.of("not_null", "pk_not_null")) {
            BatchPipelineRuleAdapter.Translation tr = adapter.translateOne(spec(t, "orders", "order_id", Map.of()));
            assertTrue(tr.mapped(), t);
            assertEquals("orders", tr.dataset());
            assertEquals(Map.of("completeness", Map.of("required_columns", List.of("order_id"))),
                    tr.fragment());
        }
    }

    @Test
    @DisplayName("unique：默认目标列；columns 参数（组合键）优先")
    void uniqueMapsToUniqueness() {
        BatchPipelineRuleAdapter.Translation tr = adapter.translateOne(spec("unique", "orders", "order_id", Map.of()));
        assertTrue(tr.mapped());
        assertEquals(Map.of("uniqueness", Map.of("columns", List.of("order_id"))), tr.fragment());

        BatchPipelineRuleAdapter.Translation combo = adapter.translateOne(
                spec("unique", "orders", "order_id", Map.of("columns", "order_id, sku")));
        assertTrue(combo.mapped());
        assertEquals(List.of("order_id", "sku"),
                ((Map<?, ?>) combo.fragment().get("uniqueness")).get("columns"));
    }

    @Test
    @DisplayName("regex_match → format（非法正则拒绝）")
    void regexMatchMapsToFormat() {
        BatchPipelineRuleAdapter.Translation tr = adapter.translateOne(
                spec("regex_match", "orders", "order_id", Map.of("regex", "^ORD-\\d{8}$")));
        assertTrue(tr.mapped());
        assertEquals(Map.of("format", Map.of("order_id", "^ORD-\\d{8}$")), tr.fragment());

        assertFalse(adapter.translateOne(spec("regex_match", "orders", "order_id", Map.of())).mapped());
        assertFalse(adapter.translateOne(
                spec("regex_match", "orders", "order_id", Map.of("regex", "[invalid"))).mapped(),
                "非法正则应拒绝");
    }

    @Test
    @DisplayName("value_range → range 闭区间；参数非法拒绝")
    void valueRangeMapsToRange() {
        BatchPipelineRuleAdapter.Translation tr = adapter.translateOne(
                spec("value_range", "orders", "quantity", Map.of("minValue", "1", "maxValue", "1000")));
        assertTrue(tr.mapped());
        assertEquals(List.of(Map.of("column", "quantity", "min", 1.0, "max", 1000.0)),
                tr.fragment().get("range"));

        assertFalse(adapter.translateOne(
                spec("value_range", "orders", "quantity", Map.of("minValue", "x", "maxValue", "1"))).mapped());
        assertFalse(adapter.translateOne(
                spec("value_range", "orders", "quantity", Map.of("minValue", "5", "maxValue", "1"))).mapped(),
                "max < min 应拒绝");
    }

    @Test
    @DisplayName("enum_whitelist → allowed_values")
    void enumWhitelistMapsToAllowedValues() {
        BatchPipelineRuleAdapter.Translation tr = adapter.translateOne(
                spec("enum_whitelist", "orders", "status", Map.of("allowedValues", "completed, pending,cancelled")));
        assertTrue(tr.mapped());
        assertEquals(Map.of("allowed_values", Map.of("status", List.of("completed", "pending", "cancelled"))),
                tr.fragment());
    }

    @Test
    @DisplayName("enum_blacklist → format 负向先行正则；对齐 re.match/rlike 锚定语义")
    void enumBlacklistMapsToNegativeLookaheadFormat() {
        BatchPipelineRuleAdapter.Translation tr = adapter.translateOne(
                spec("enum_blacklist", "orders", "status", Map.of("forbiddenValues", "DELETED,FRAUD")));
        assertTrue(tr.mapped());
        String pattern = (String) ((Map<?, ?>) tr.fragment().get("format")).get("status");
        assertEquals("^(?!(?:DELETED|FRAUD)$)", pattern);
        assertNotNullStr(tr.note());

        // 锚定语义：黑名单值违规、普通值放行、黑名单前缀超集放行（与 SQL 模板 IN 判定一致）
        Pattern p = Pattern.compile(pattern);
        assertFalse(p.matcher("DELETED").find(), "DELETED 应违规");
        assertFalse(p.matcher("FRAUD").find(), "FRAUD 应违规");
        assertTrue(p.matcher("ACTIVE").find(), "ACTIVE 应放行");
        assertTrue(p.matcher("DELETEDX").find(), "DELETEDX（非精确匹配）应放行");
    }

    private static void assertNotNullStr(String s) {
        assertTrue(s != null && !s.isBlank());
    }

    @Test
    @DisplayName("fk_reference → referential 表.列；schema 前缀参考表拒绝")
    void fkReferenceMapsToReferential() {
        BatchPipelineRuleAdapter.Translation tr = adapter.translateOne(
                spec("fk_reference", "orders", "customer_id", Map.of("refTable", "customers", "refColumn", "customer_id")));
        assertTrue(tr.mapped());
        assertEquals(Map.of("referential", Map.of("customer_id", "customers.customer_id")), tr.fragment());

        BatchPipelineRuleAdapter.Translation schemaQualified = adapter.translateOne(
                spec("fk_reference", "orders", "customer_id",
                        Map.of("refTable", "my_schema.dim_product", "refColumn", "product_id")));
        assertFalse(schemaQualified.mapped());
        assertTrue(schemaQualified.reason().contains("schema"));
    }

    @Test
    @DisplayName("不可映射模板给出明确原因：row_count_range / not_null_if / freshness / 未知")
    void unsupportedTemplatesRejectedWithReason() {
        Map<String, String> expected = Map.of(
                "row_count_range", "行级",
                "not_null_if", "条件",
                "freshness", "date_valid",
                "unknown_tpl", "未知模板");
        for (String t : expected.keySet()) {
            BatchPipelineRuleAdapter.Translation tr = adapter.translateOne(spec(t, "orders", "c", Map.of()));
            assertFalse(tr.mapped(), t);
            assertNull(tr.fragment());
            assertTrue(tr.reason().contains(expected.get(t)), t + " → " + tr.reason());
        }
    }

    @Test
    @DisplayName("缺 targetTable / targetColumn 拒绝")
    void missingRequiredSpecFieldsRejected() {
        assertFalse(adapter.translateOne(spec("not_null", "", "c", Map.of())).mapped());
        assertFalse(adapter.translateOne(spec("not_null", "orders", "", Map.of())).mapped());
    }

    @Test
    @DisplayName("批量翻译按数据集聚合，类内合并（列表去重、字典覆盖）")
    void batchTranslationGroupsByDatasetAndMerges() {
        BatchPipelineRuleAdapter.TranslateResult result = adapter.translateBatch(List.of(
                spec("not_null", "orders", "order_id", Map.of()),
                spec("pk_not_null", "orders", "order_id", Map.of()),
                spec("not_null", "orders", "customer_id", Map.of()),
                spec("unique", "orders", "order_id", Map.of()),
                spec("value_range", "orders", "quantity", Map.of("minValue", "1", "maxValue", "1000")),
                spec("enum_whitelist", "customers", "tier", Map.of("allowedValues", "bronze,silver")),
                spec("row_count_range", "orders", "", Map.of("minCount", "1", "maxCount", "10"))));

        assertEquals(2, result.rules().size());
        Map<?, ?> orders = (Map<?, ?>) result.rules().get("orders");
        // 重复的 order_id 去重，customer_id 追加
        assertEquals(List.of("order_id", "customer_id"),
                ((Map<?, ?>) orders.get("completeness")).get("required_columns"));
        assertEquals(List.of("order_id"), ((Map<?, ?>) orders.get("uniqueness")).get("columns"));
        assertEquals(1, ((List<?>) orders.get("range")).size());

        Map<?, ?> customers = (Map<?, ?>) result.rules().get("customers");
        assertEquals(Map.of("tier", List.of("bronze", "silver")),
                customers.get("allowed_values"));

        assertEquals(6, result.mapped().size());
        assertEquals(1, result.unmapped().size());
    }

    @Test
    @DisplayName("specFromStoredRule：type 前缀 + description 恢复模板与目标")
    void specFromStoredRuleRecoversTemplateAndTarget() {
        Rule rule = new Rule();
        rule.setType("QUALITY_NOT_NULL");
        rule.setDescription("quality rule on ods.orders.order_id");

        BatchPipelineRuleAdapter.RuleSpec spec = adapter.specFromStoredRule(rule);
        assertEquals("not_null", spec.templateId());
        assertEquals("ods.orders", spec.targetTable());
        assertEquals("order_id", spec.targetColumn());

        // 参数无关模板可完整翻译；需要参数的模板给出明确 unmapped 原因
        assertTrue(adapter.translateOne(spec).mapped());

        Rule regexRule = new Rule();
        regexRule.setType("QUALITY_REGEX_MATCH");
        regexRule.setDescription("quality rule on ods.orders.order_id");
        BatchPipelineRuleAdapter.Translation tr =
                adapter.translateOne(adapter.specFromStoredRule(regexRule));
        assertFalse(tr.mapped());
        assertTrue(tr.reason().contains("regex"));
    }
}
