package com.levango7.dataenginebdp.ruleengine.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RuleExecutionRequest 模型测试。
 */
class RuleExecutionRequestTest {

    @Test
    @DisplayName("getter/setter — 所有字段正确存取")
    void allFields_shouldBeAccessible() {
        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(1L);
        request.setContext(Map.of("key", "value"));
        request.setTenantId("t1");

        assertThat(request.getRuleId()).isEqualTo(1L);
        assertThat(request.getContext()).containsEntry("key", "value");
        assertThat(request.getTenantId()).isEqualTo("t1");
    }

    @Test
    @DisplayName("默认值 — 字段初始为null")
    void defaultValues_shouldBeNull() {
        RuleExecutionRequest request = new RuleExecutionRequest();

        assertThat(request.getRuleId()).isNull();
        assertThat(request.getContext()).isNull();
        assertThat(request.getTenantId()).isNull();
    }

    @Test
    @DisplayName("toString — 不为空")
    void toString_shouldNotBeNull() {
        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(1L);
        assertThat(request.toString()).isNotNull();
    }
}