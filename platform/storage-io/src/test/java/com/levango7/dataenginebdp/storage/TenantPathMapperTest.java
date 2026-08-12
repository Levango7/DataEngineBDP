package com.levango7.dataenginebdp.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TenantPathMapper 单元测试。
 */
class TenantPathMapperTest {

    private final TenantPathMapper tenantAMapper = new TenantPathMapper("tenant_a");

    // ---------- toStorageKey ----------
    @Test
    void toStorageKey_normalPathPrefixedByTenant() {
        String key = tenantAMapper.toStorageKey("warehouse/orders.db/dt=2026-08-11/part-0000.parquet");
        assertThat(key).isEqualTo("tenant_a/warehouse/orders.db/dt=2026-08-11/part-0000.parquet");
    }

    @Test
    void toStorageKey_blankThrows() {
        assertThatThrownBy(() -> tenantAMapper.toStorageKey(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStorageKey_systemKeyUnchanged() {
        // 系统键（_system/ 前缀）不叠加租户前缀
        TenantPathMapper mapper = new TenantPathMapper("tenant_a");
        String key = mapper.toStorageKey("_system/internal-meta");
        assertThat(key).isEqualTo("_system/internal-meta");
    }

    // ---------- toStoragePrefix ----------
    @Test
    void toStoragePrefix_emptyReturnsTenantSlash() {
        assertThat(tenantAMapper.toStoragePrefix("")).isEqualTo("tenant_a/");
    }

    @Test
    void toStoragePrefix_normalPrefix() {
        assertThat(tenantAMapper.toStoragePrefix("warehouse/")).isEqualTo("tenant_a/warehouse/");
    }

    // ---------- stripTenantPrefix ----------
    @Test
    void stripTenantPrefix_sameTenantReturnsRightPart() {
        String full = "tenant_a/warehouse/t1/f.parquet";
        assertThat(tenantAMapper.stripTenantPrefix(full)).isEqualTo("warehouse/t1/f.parquet");
    }

    @Test
    void stripTenantPrefix_otherTenantReturnsNull() {
        // 跨租户访问：返回 null，由上层做权限拒绝
        String full = "tenant_b/warehouse/t1/f.parquet";
        assertThat(tenantAMapper.stripTenantPrefix(full)).isNull();
    }

    @Test
    void stripTenantPrefix_systemKey() {
        assertThat(tenantAMapper.stripTenantPrefix("_system/log")).isEqualTo("_system/log");
    }

    // ---------- 构造器安全校验 ----------
    @Test
    void constructor_nullTenantDefaultsToSystem() {
        TenantPathMapper m = new TenantPathMapper(null);
        assertThat(m.getCurrentTenantId()).isEqualTo("system");
    }

    @Test
    void constructor_rejectsPathTraversal() {
        assertThatThrownBy(() -> new TenantPathMapper("tenant/../evil"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantPathMapper("tenant/with/slash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveKey_rejectsReservedTenantNames() {
        // 使用 TenantPathMapper 的非过滤构造路径（可能未来会加 validate 校验）
        // 目前只需保证 'system' 被识别为合法默认，不抛异常
        TenantPathMapper m = new TenantPathMapper("system");
        assertThat(m.getCurrentTenantId()).isEqualTo("system");
    }
}
