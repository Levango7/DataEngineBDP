package com.shuqing.bigdata.ruleengine.scheduler.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskStatus 枚举单元测试。
 */
class TaskStatusTest {

    @Test
    @DisplayName("isTerminal — 终态返回 true")
    void isTerminal_true() {
        assertThat(TaskStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(TaskStatus.FAILED.isTerminal()).isTrue();
        assertThat(TaskStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(TaskStatus.REJECTED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("isTerminal — 中间态返回 false")
    void isTerminal_false() {
        assertThat(TaskStatus.QUEUED.isTerminal()).isFalse();
        assertThat(TaskStatus.ALLOCATING.isTerminal()).isFalse();
        assertThat(TaskStatus.RUNNING.isTerminal()).isFalse();
    }
}