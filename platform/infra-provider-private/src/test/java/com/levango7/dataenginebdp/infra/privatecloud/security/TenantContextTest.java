package com.levango7.dataenginebdp.infra.privatecloud.security;

import com.levango7.dataenginebdp.common.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TenantContext 测试。
 *
 * @author shuqing-bigdata
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("设置租户与用户 ID — 可正确读取")
    void setAndGet_shouldReturnCorrectValues() {
        TenantContext.setTenantId("tenant-001");
        TenantContext.setUserId("user-001");

        assertEquals("tenant-001", TenantContext.getTenantId());
        assertEquals("user-001", TenantContext.getUserId());
    }

    @Test
    @DisplayName("clear — 清理后上下文为 null")
    void clear_shouldRemoveContext() {
        TenantContext.setTenantId("tenant-001");
        TenantContext.setUserId("user-001");
        TenantContext.clear();

        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getUserId());
    }

    @Test
    @DisplayName("未设置时 — 返回 null")
    void notSet_shouldReturnNull() {
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getUserId());
    }
}