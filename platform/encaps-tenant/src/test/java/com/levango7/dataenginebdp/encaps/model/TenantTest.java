package com.levango7.dataenginebdp.encaps.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant 模型测试。
 *
 * <p>覆盖 Lombok 生成的 getter/setter/equals/hashCode/toString/constructor。</p>
 */
class TenantTest {

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setId(1L);
        tenant.setName("test-tenant");
        tenant.setDisplayName("Test Tenant");
        tenant.setNamespace("ns-test");
        tenant.setQuotaProfile("medium");
        tenant.setStatus("ACTIVE");
        tenant.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        tenant.setUpdatedAt(LocalDateTime.of(2024, 6, 1, 0, 0));
    }

    @Test
    @DisplayName("getter/setter — 所有字段正确存取")
    void allFields_shouldBeAccessible() {
        assertThat(tenant.getId()).isEqualTo(1L);
        assertThat(tenant.getName()).isEqualTo("test-tenant");
        assertThat(tenant.getDisplayName()).isEqualTo("Test Tenant");
        assertThat(tenant.getNamespace()).isEqualTo("ns-test");
        assertThat(tenant.getQuotaProfile()).isEqualTo("medium");
        assertThat(tenant.getStatus()).isEqualTo("ACTIVE");
        assertThat(tenant.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
        assertThat(tenant.getUpdatedAt()).isEqualTo(LocalDateTime.of(2024, 6, 1, 0, 0));
    }

    @Test
    @DisplayName("全参构造器 — 正确初始化所有字段")
    void allArgsConstructor_shouldInitializeAllFields() {
        Tenant t = new Tenant(2L, "name", "display", "ns", "large", "INACTIVE",
                LocalDateTime.now(), LocalDateTime.now());

        assertThat(t.getId()).isEqualTo(2L);
        assertThat(t.getName()).isEqualTo("name");
        assertThat(t.getDisplayName()).isEqualTo("display");
        assertThat(t.getNamespace()).isEqualTo("ns");
        assertThat(t.getQuotaProfile()).isEqualTo("large");
        assertThat(t.getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("无参构造器 — 创建空对象")
    void noArgsConstructor_shouldCreateEmptyObject() {
        Tenant t = new Tenant();

        assertThat(t.getId()).isNull();
        assertThat(t.getName()).isNull();
    }

    @Test
    @DisplayName("equals — 相同字段的对象相等")
    void equals_sameFields_shouldBeEqual() {
        Tenant other = new Tenant();
        other.setId(1L);
        other.setName("test-tenant");
        other.setDisplayName("Test Tenant");
        other.setNamespace("ns-test");
        other.setQuotaProfile("medium");
        other.setStatus("ACTIVE");
        other.setCreatedAt(tenant.getCreatedAt());
        other.setUpdatedAt(tenant.getUpdatedAt());

        assertThat(tenant).isEqualTo(other);
    }

    @Test
    @DisplayName("equals — 不同字段的对象不相等")
    void equals_differentFields_shouldNotBeEqual() {
        Tenant other = new Tenant();
        other.setId(2L); // 仅 id 不同
        other.setName("test-tenant");
        other.setDisplayName("Test Tenant");
        other.setNamespace("ns-test");
        other.setQuotaProfile("medium");
        other.setStatus("ACTIVE");
        other.setCreatedAt(tenant.getCreatedAt());
        other.setUpdatedAt(tenant.getUpdatedAt());

        assertThat(tenant).isNotEqualTo(other);
    }

    @Test
    @DisplayName("hashCode — equals 相等的对象 hashCode 必须一致（契约）")
    void hashCode_equalObjects_shouldHaveSameHash() {
        Tenant other = new Tenant();
        other.setId(1L);
        other.setName("test-tenant");
        other.setDisplayName("Test Tenant");
        other.setNamespace("ns-test");
        other.setQuotaProfile("medium");
        other.setStatus("ACTIVE");
        other.setCreatedAt(tenant.getCreatedAt());
        other.setUpdatedAt(tenant.getUpdatedAt());

        // equals 相等 → hashCode 必须相等（equals/hashCode 契约）
        assertThat(tenant).isEqualTo(other);
        assertThat(tenant.hashCode()).isEqualTo(other.hashCode());
    }

    @Test
    @DisplayName("hashCode — 不相等对象 hashCode 不同（合理分布）")
    void hashCode_unequalObjects_shouldDiffer() {
        Tenant other = new Tenant();
        other.setId(2L);
        other.setName("test-tenant");
        other.setDisplayName("Test Tenant");
        other.setNamespace("ns-test");
        other.setQuotaProfile("medium");
        other.setStatus("ACTIVE");
        other.setCreatedAt(tenant.getCreatedAt());
        other.setUpdatedAt(tenant.getUpdatedAt());

        assertThat(tenant).isNotEqualTo(other);
        assertThat(tenant.hashCode()).isNotEqualTo(other.hashCode());
    }

    @Test
    @DisplayName("toString — 包含字段信息")
    void toString_shouldContainFieldInfo() {
        String str = tenant.toString();

        assertThat(str).contains("test-tenant");
        assertThat(str).isNotNull();
    }
}