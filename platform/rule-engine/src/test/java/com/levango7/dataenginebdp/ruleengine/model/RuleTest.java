package com.levango7.dataenginebdp.ruleengine.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule 模型测试。
 */
class RuleTest {

    private Rule rule;

    @BeforeEach
    void setUp() {
        rule = new Rule();
        rule.setId(1L);
        rule.setName("dq-rule");
        rule.setDescription("数据质量检查规则");
        rule.setType("DQ");
        rule.setExpression("NOT NULL");
        rule.setSeverity("ERROR");
        rule.setEnabled(true);
        rule.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        rule.setUpdatedAt(LocalDateTime.of(2024, 6, 1, 0, 0));
    }

    @Test
    @DisplayName("getter/setter — 所有字段正确存取")
    void allFields_shouldBeAccessible() {
        assertThat(rule.getId()).isEqualTo(1L);
        assertThat(rule.getName()).isEqualTo("dq-rule");
        assertThat(rule.getDescription()).isEqualTo("数据质量检查规则");
        assertThat(rule.getType()).isEqualTo("DQ");
        assertThat(rule.getExpression()).isEqualTo("NOT NULL");
        assertThat(rule.getSeverity()).isEqualTo("ERROR");
        assertThat(rule.getEnabled()).isTrue();
        assertThat(rule.getCreatedAt()).isNotNull();
        assertThat(rule.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("全参构造器 — 正确初始化所有字段")
    void allArgsConstructor_shouldInitializeAllFields() {
        Rule r = new Rule(2L, "mask-rule", "脱敏规则", "MASK", "PHONE_MASK", "WARN",
                false, LocalDateTime.now(), LocalDateTime.now());

        assertThat(r.getId()).isEqualTo(2L);
        assertThat(r.getName()).isEqualTo("mask-rule");
        assertThat(r.getType()).isEqualTo("MASK");
        assertThat(r.getEnabled()).isFalse();
    }

    @Test
    @DisplayName("toString — 包含字段信息")
    void toString_shouldContainFieldInfo() {
        String str = rule.toString();
        assertThat(str).isNotNull();
        assertThat(str).contains("dq-rule");
    }
}