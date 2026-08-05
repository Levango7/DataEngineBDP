package com.shuqing.bigdata.ruleengine.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RuleExecutionResult 模型测试。
 */
class RuleExecutionResultTest {

    @Test
    @DisplayName("builder — 构建完整执行结果")
    void builder_shouldBuildCompleteResult() {
        RuleExecutionResult result = RuleExecutionResult.builder()
                .ruleId(1L)
                .status("PASS")
                .message("SIMULATED")
                .details(Map.of("type", "DQ"))
                .durationMs(10L)
                .executedAt(LocalDateTime.now())
                .build();

        assertThat(result.getRuleId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("SIMULATED");
        assertThat(result.getDetails()).containsEntry("type", "DQ");
        assertThat(result.getDurationMs()).isEqualTo(10L);
        assertThat(result.getExecutedAt()).isNotNull();
    }

    @Test
    @DisplayName("setter — 修改字段值")
    void setter_shouldModifyFields() {
        RuleExecutionResult result = RuleExecutionResult.builder().build();
        result.setStatus("FAIL");
        result.setDurationMs(500L);

        assertThat(result.getStatus()).isEqualTo("FAIL");
        assertThat(result.getDurationMs()).isEqualTo(500L);
    }

    @Test
    @DisplayName("toString — 不为空")
    void toString_shouldNotBeNull() {
        RuleExecutionResult result = RuleExecutionResult.builder()
                .ruleId(1L)
                .status("PASS")
                .build();
        assertThat(result.toString()).isNotNull();
    }
}