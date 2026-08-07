package com.shuqing.bigdata.ruleengine.scheduler.priority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskPriority 枚举单元测试。
 */
class TaskPriorityTest {

    @Test
    @DisplayName("权重：HIGH > MEDIUM > LOW")
    void weight_shouldBeOrdered() {
        assertThat(TaskPriority.HIGH.weight()).isGreaterThan(TaskPriority.MEDIUM.weight());
        assertThat(TaskPriority.MEDIUM.weight()).isGreaterThan(TaskPriority.LOW.weight());
    }

    @Test
    @DisplayName("权重值：HIGH=100, MEDIUM=50, LOW=10")
    void weight_values() {
        assertThat(TaskPriority.HIGH.weight()).isEqualTo(100);
        assertThat(TaskPriority.MEDIUM.weight()).isEqualTo(50);
        assertThat(TaskPriority.LOW.weight()).isEqualTo(10);
    }

    @Test
    @DisplayName("fromStringOrDefault — 合法值正确解析（大小写不敏感）")
    void fromString_validCaseInsensitive() {
        assertThat(TaskPriority.fromStringOrDefault("HIGH")).isEqualTo(TaskPriority.HIGH);
        assertThat(TaskPriority.fromStringOrDefault("high")).isEqualTo(TaskPriority.HIGH);
        assertThat(TaskPriority.fromStringOrDefault("Low")).isEqualTo(TaskPriority.LOW);
    }

    @Test
    @DisplayName("fromStringOrDefault — null/空白返回 MEDIUM")
    void fromString_blank_returnsMedium() {
        assertThat(TaskPriority.fromStringOrDefault(null)).isEqualTo(TaskPriority.MEDIUM);
        assertThat(TaskPriority.fromStringOrDefault("")).isEqualTo(TaskPriority.MEDIUM);
        assertThat(TaskPriority.fromStringOrDefault("  ")).isEqualTo(TaskPriority.MEDIUM);
    }

    @Test
    @DisplayName("fromStringOrDefault — 非法值返回 MEDIUM")
    void fromString_invalid_returnsMedium() {
        assertThat(TaskPriority.fromStringOrDefault("URGENT")).isEqualTo(TaskPriority.MEDIUM);
        assertThat(TaskPriority.fromStringOrDefault("xyz")).isEqualTo(TaskPriority.MEDIUM);
    }
}