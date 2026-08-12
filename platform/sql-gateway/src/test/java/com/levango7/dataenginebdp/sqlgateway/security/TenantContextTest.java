package com.levango7.dataenginebdp.sqlgateway.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TenantContext 单元测试。
 */
class TenantContextTest {

    @BeforeEach
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("setTenantId + getTenantId — 正确存取租户ID")
    void setAndGetTenantId_shouldWork() {
        TenantContext.setTenantId("tenant-001");

        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-001");
    }

    @Test
    @DisplayName("setUserId + getUserId — 正确存取用户ID")
    void setAndGetUserId_shouldWork() {
        TenantContext.setUserId("user-001");

        assertThat(TenantContext.getUserId()).isEqualTo("user-001");
    }

    @Test
    @DisplayName("clear — 清理后tenantId和userId均为null")
    void clear_shouldRemoveAllValues() {
        TenantContext.setTenantId("t1");
        TenantContext.setUserId("u1");

        TenantContext.clear();

        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
    }

    @Test
    @DisplayName("未设置时getTenantId返回null")
    void getTenantId_withoutSet_shouldReturnNull() {
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("未设置时getUserId返回null")
    void getUserId_withoutSet_shouldReturnNull() {
        assertThat(TenantContext.getUserId()).isNull();
    }
}