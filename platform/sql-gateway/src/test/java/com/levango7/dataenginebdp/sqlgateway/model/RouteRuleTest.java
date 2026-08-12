package com.levango7.dataenginebdp.sqlgateway.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RouteRule 模型测试。
 */
class RouteRuleTest {

    @Test
    @DisplayName("全参构造器 — 正确初始化所有字段")
    void allArgsConstructor_shouldInitializeFields() {
        RouteRule rule = new RouteRule("SELECT", "trino", 1, true);

        assertThat(rule.getPattern()).isEqualTo("SELECT");
        assertThat(rule.getEngine()).isEqualTo("trino");
        assertThat(rule.getPriority()).isEqualTo(1);
        assertThat(rule.getEnabled()).isTrue();
    }

    @Test
    @DisplayName("getter/setter — 所有字段正确存取")
    void allFields_shouldBeAccessible() {
        RouteRule rule = new RouteRule();
        rule.setId(1L);
        rule.setPattern("INSERT");
        rule.setEngine("doris");
        rule.setPriority(10);
        rule.setEnabled(false);

        assertThat(rule.getId()).isEqualTo(1L);
        assertThat(rule.getPattern()).isEqualTo("INSERT");
        assertThat(rule.getEngine()).isEqualTo("doris");
        assertThat(rule.getPriority()).isEqualTo(10);
        assertThat(rule.getEnabled()).isFalse();
    }

    @Test
    @DisplayName("toString — 不为空")
    void toString_shouldNotBeNull() {
        RouteRule rule = new RouteRule("SELECT", "trino", 1, true);
        assertThat(rule.toString()).isNotNull();
    }
}