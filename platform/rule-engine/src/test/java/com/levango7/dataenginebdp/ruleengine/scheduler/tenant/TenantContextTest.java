package com.levango7.dataenginebdp.ruleengine.scheduler.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调度域 TenantContext 单元测试。
 */
class TenantContextTest {

    @BeforeEach
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("bind + get — 正确绑定与读取")
    void bindAndGet() {
        TenantContext.bind("tenant-001", "task-001");
        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-001");
        assertThat(TenantContext.getTaskId()).isEqualTo("task-001");
    }

    @Test
    @DisplayName("clear — 清理后均为 null")
    void clear_removesAll() {
        TenantContext.bind("t1", "tk1");
        TenantContext.clear();
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.getTaskId()).isNull();
    }

    @Test
    @DisplayName("未绑定时返回 null")
    void unbound_returnsNull() {
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.getTaskId()).isNull();
    }
}