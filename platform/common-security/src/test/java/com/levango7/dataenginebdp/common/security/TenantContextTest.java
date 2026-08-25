package com.levango7.dataenginebdp.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TenantContext 单元测试：ThreadLocal 语义、隔离性与清理。
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void setAndGet_roundTripsTenantAndUser() {
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getUserId());

        TenantContext.setTenantId("tenant-001");
        TenantContext.setUserId("user-a");

        assertEquals("tenant-001", TenantContext.getTenantId());
        assertEquals("user-a", TenantContext.getUserId());
    }

    @Test
    void clear_resetsBothValues() {
        TenantContext.setTenantId("tenant-002");
        TenantContext.setUserId("user-b");

        TenantContext.clear();

        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getUserId());
    }

    @Test
    void threadLocals_areIsolatedAcrossThreads() throws Exception {
        TenantContext.setTenantId("main-tenant");

        String[] seen = new String[1];
        Thread other = new Thread(() -> {
            seen[0] = TenantContext.getTenantId();
            TenantContext.setTenantId("other-tenant");
        });
        other.start();
        other.join();

        assertNull(seen[0]);
        assertEquals("main-tenant", TenantContext.getTenantId());
    }
}
