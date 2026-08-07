package com.shuqing.bigdata.sqlgateway.rewrite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RewriteRule 单元测试。
 *
 * @author shuqing-bigdata
 */
class RewriteRuleTest {

    @Test
    @DisplayName("typeEnum — 合法类型返回对应枚举")
    void typeEnum_validType_shouldReturnEnum() {
        RewriteRule rule = new RewriteRule("r1", "EXACT_MATCH", "mv1",
                "sales", 1, true, "测试规则");
        assertThat(rule.typeEnum()).isEqualTo(RewriteRuleType.EXACT_MATCH);
    }

    @Test
    @DisplayName("typeEnum — 大小写不敏感")
    void typeEnum_caseInsensitive_shouldReturnEnum() {
        RewriteRule rule = new RewriteRule("r1", "agg_rollup", "mv1",
                "sales", 1, true, null);
        assertThat(rule.typeEnum()).isEqualTo(RewriteRuleType.AGG_ROLLUP);
    }

    @Test
    @DisplayName("typeEnum — 非法类型返回 null")
    void typeEnum_invalidType_shouldReturnNull() {
        RewriteRule rule = new RewriteRule("r1", "UNKNOWN_TYPE", "mv1",
                "sales", 1, true, null);
        assertThat(rule.typeEnum()).isNull();
    }

    @Test
    @DisplayName("typeEnum — null 类型返回 null")
    void typeEnum_nullType_shouldReturnNull() {
        RewriteRule rule = new RewriteRule();
        rule.setRuleName("r1");
        assertThat(rule.typeEnum()).isNull();
    }

    @Test
    @DisplayName("全参构造器 — 正确赋值所有字段")
    void constructor_shouldSetAllFields() {
        RewriteRule rule = new RewriteRule("rule-sales", "EXACT_MATCH", "mv_sales",
                "sales", 10, true, "销售物化视图规则");
        assertThat(rule.getRuleName()).isEqualTo("rule-sales");
        assertThat(rule.getRuleType()).isEqualTo("EXACT_MATCH");
        assertThat(rule.getTargetView()).isEqualTo("mv_sales");
        assertThat(rule.getSourceTablePattern()).isEqualTo("sales");
        assertThat(rule.getPriority()).isEqualTo(10);
        assertThat(rule.getEnabled()).isTrue();
        assertThat(rule.getDescription()).isEqualTo("销售物化视图规则");
    }
}