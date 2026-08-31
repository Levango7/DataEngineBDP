package com.levango7.dataenginebdp.ruleengine.engine;

import com.levango7.dataenginebdp.ruleengine.engine.BuiltinRuleTemplates.TemplateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内置质量规则模板库测试。
 *
 * 覆盖：11 模板渲染正确性 / SQL 注入防护 / 必填参数校验 / 区间参数校验。
 */
@DisplayName("BuiltinRuleTemplates 内置质量规则模板库")
class BuiltinRuleTemplatesTest {

    private static String render(String templateId, String table, String column) {
        return BuiltinRuleTemplates.renderSql(templateId, table, column, Map.of());
    }

    private static String render(String templateId, String table, String column, Map<String, String> params) {
        return BuiltinRuleTemplates.renderSql(templateId, table, column, params);
    }

    @Nested
    @DisplayName("模板清单")
    class ListTemplates {

        @Test
        @DisplayName("六大维度 × 11 模板，视图含完整元数据与示例 SQL")
        void shouldExposeElevenTemplatesAcrossSixDimensions() {
            List<Map<String, Object>> templates = BuiltinRuleTemplates.listTemplates();

            assertThat(templates).hasSize(11);
            var dimensions = templates.stream().map(t -> (String) t.get("dimension")).distinct().toList();
            assertThat(dimensions).containsExactlyInAnyOrder(
                    "completeness", "uniqueness", "accuracy", "consistency", "timeliness", "validity");
            for (Map<String, Object> t : templates) {
                String example = (String) t.get("sqlExample");
                assertThat(example).as("template %s", t.get("id")).startsWith("sql:SELECT ");
            }
        }
    }

    @Nested
    @DisplayName("完整性模板")
    class Completeness {

        @Test
        @DisplayName("not_null 默认只查 NULL")
        void notNull() {
            String sql = render("not_null", "ods.orders", "phone");
            assertThat(sql).isEqualTo(
                    "sql:SELECT COUNT(*) FROM ods.orders WHERE \"phone\" IS NULL");
        }

        @Test
        @DisplayName("not_null includeEmpty 同时查空字符串")
        void notNullIncludeEmpty() {
            String sql = render("not_null", "ods.orders", "phone", Map.of("includeEmpty", "true"));
            assertThat(sql).contains("IS NULL OR TRIM(CAST(\"phone\" AS VARCHAR(512))) = ''");
        }

        @Test
        @DisplayName("pk_not_null 主键非空")
        void pkNotNull() {
            String sql = render("pk_not_null", "dws.user_wide", "user_id");
            assertThat(sql).isEqualTo(
                    "sql:SELECT COUNT(*) FROM dws.user_wide WHERE \"user_id\" IS NULL");
        }

        @Test
        @DisplayName("row_count_range 行数区间，越界返回 1")
        void rowCountRange() {
            String sql = render("row_count_range", "ods.orders", null,
                    Map.of("minCount", "100", "maxCount", "100000"));
            assertThat(sql).contains("cnt < 100 OR cnt > 100000");
        }

        @Test
        @DisplayName("row_count_range 区间非法（min>max）拒绝")
        void rowCountRangeInvalid() {
            assertThatThrownBy(() -> render("row_count_range", "ods.orders", null,
                    Map.of("minCount", "1000", "maxCount", "10")))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("行数区间非法");
        }

        @Test
        @DisplayName("row_count_range 不需要目标列")
        void rowCountRangeNoColumnRequired() {
            String sql = render("row_count_range", "ods.orders", null,
                    Map.of("minCount", "1", "maxCount", "100"));
            assertThat(sql).startsWith("sql:SELECT CASE WHEN cnt");
        }
    }

    @Nested
    @DisplayName("唯一性模板")
    class Uniqueness {

        @Test
        @DisplayName("unique 单列查重复组")
        void uniqueSingle() {
            String sql = render("unique", "ods.orders", "order_no");
            assertThat(sql).contains("GROUP BY \"order_no\" HAVING COUNT(*) > 1");
        }

        @Test
        @DisplayName("unique 组合列（columns 参数）")
        void uniqueComposite() {
            String sql = render("unique", "dws.bill", "user_id",
                    Map.of("columns", "user_id, org_id"));
            assertThat(sql).contains("GROUP BY \"user_id\", \"org_id\"");
        }

        @Test
        @DisplayName("unique 组合列含非法标识符拒绝")
        void uniqueCompositeInjection() {
            assertThatThrownBy(() -> render("unique", "dws.bill", "user_id",
                    Map.of("columns", "user_id; DROP TABLE users")))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("非法标识符");
        }
    }

    @Nested
    @DisplayName("准确性模板")
    class Accuracy {

        @Test
        @DisplayName("regex_match 手机号正则（正则做字面量转义）")
        void regexMatch() {
            String sql = render("regex_match", "dwd.customer", "phone",
                    Map.of("regex", "^1[3-9][0-9]{9}$"));
            assertThat(sql).contains("NOT REGEXP_LIKE(CAST(\"phone\" AS VARCHAR(2048)), '^1[3-9][0-9]{9}$')");
        }

        @Test
        @DisplayName("value_range 值域闭区间")
        void valueRange() {
            String sql = render("value_range", "dws.order_wide", "amount",
                    Map.of("minValue", "0", "maxValue", "100000"));
            assertThat(sql).contains("CAST(\"amount\" AS DOUBLE) < 0.0 OR CAST(\"amount\" AS DOUBLE) > 100000.0");
        }

        @Test
        @DisplayName("not_null_if 条件非空")
        void notNullIf() {
            String sql = render("not_null_if", "dws.order_wide", "paid_at",
                    Map.of("conditionColumn", "status", "conditionValue", "PAID"));
            assertThat(sql).contains("\"status\" = 'PAID' AND \"paid_at\" IS NULL");
        }
    }

    @Nested
    @DisplayName("一致性/时效性/有效性模板")
    class CrossChecks {

        @Test
        @DisplayName("fk_reference 参照完整性（LEFT JOIN 孤儿记录）")
        void fkReference() {
            String sql = render("fk_reference", "dwd.order_item", "product_id",
                    Map.of("refTable", "dim.product", "refColumn", "product_id"));
            assertThat(sql).contains("LEFT JOIN dim.product r ON t.\"product_id\" = r.\"product_id\"");
            assertThat(sql).contains("r.\"product_id\" IS NULL");
        }

        @Test
        @DisplayName("freshness 数据新鲜度（N 小时内）")
        void freshness() {
            String sql = render("freshness", "ods.orders", "updated_at",
                    Map.of("maxHours", "24"));
            assertThat(sql).contains("< CURRENT_TIMESTAMP - INTERVAL '24' HOUR");
        }

        @Test
        @DisplayName("enum_whitelist 枚举白名单（含转义）")
        void enumWhitelist() {
            String sql = render("enum_whitelist", "dwd.customer", "status",
                    Map.of("allowedValues", "ACTIVE,DISABLED"));
            assertThat(sql).contains("NOT IN ('ACTIVE', 'DISABLED')");
        }

        @Test
        @DisplayName("enum_blacklist 枚举黑名单")
        void enumBlacklist() {
            String sql = render("enum_blacklist", "dwd.customer", "status",
                    Map.of("forbiddenValues", "DELETED,FRAUD"));
            assertThat(sql).contains("IN ('DELETED', 'FRAUD')");
        }
    }

    @Nested
    @DisplayName("安全与校验")
    class SecurityAndValidation {

        @Test
        @DisplayName("表名注入（分号/空格/注释/反引号/子查询）拒绝")
        void tableInjectionRejected() {
            List<String> bad = List.of(
                    "orders; DROP TABLE users",
                    "ods.orders-- comment",
                    "ods orders",
                    "ods.`orders`",
                    "(SELECT 1)");
            for (String table : bad) {
                assertThatThrownBy(() -> render("not_null", table, "phone"))
                        .as("table %s", table)
                        .isInstanceOf(TemplateException.class)
                        .hasMessageContaining("非法标识符");
            }
        }

        @Test
        @DisplayName("列名注入拒绝")
        void columnInjectionRejected() {
            assertThatThrownBy(() -> render("not_null", "ods.orders", "phone) OR 1=1 --"))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("非法标识符");
        }

        @Test
        @DisplayName("枚举参数单引号转义（防注入）")
        void enumQuoteEscaped() {
            String sql = render("enum_blacklist", "dwd.customer", "status",
                    Map.of("forbiddenValues", "O'Brien"));
            assertThat(sql).contains("'O''Brien'");
        }

        @Test
        @DisplayName("正则参数单引号转义")
        void regexQuoteEscaped() {
            String sql = render("regex_match", "dwd.customer", "name",
                    Map.of("regex", "a'b"));
            assertThat(sql).contains("'a''b'");
        }

        @Test
        @DisplayName("未知模板拒绝并提示可用列表")
        void unknownTemplate() {
            assertThatThrownBy(() -> render("no_such", "t", "c"))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("未知模板")
                    .hasMessageContaining("not_null");
        }

        @Test
        @DisplayName("缺必填参数拒绝")
        void missingRequiredParam() {
            assertThatThrownBy(() -> render("regex_match", "dwd.customer", "phone"))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("缺少必填参数: regex");
        }

        @Test
        @DisplayName("数值参数非数字拒绝")
        void nonNumericValue() {
            assertThatThrownBy(() -> render("freshness", "ods.orders", "updated_at",
                    Map.of("maxHours", "abc")))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("必须为整数");

            assertThatThrownBy(() -> render("value_range", "t", "c",
                    Map.of("minValue", "x", "maxValue", "10")))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("必须为数值");
        }

        @Test
        @DisplayName("合法 schema.table 两级标识符放行")
        void twoLevelIdentifierAllowed() {
            String sql = render("not_null", "my_schema.my_table", "my_col");
            assertThat(sql).startsWith("sql:SELECT COUNT(*) FROM my_schema.my_table");
        }
    }
}
