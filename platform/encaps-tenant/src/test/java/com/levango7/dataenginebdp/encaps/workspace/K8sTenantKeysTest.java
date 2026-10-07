package com.levango7.dataenginebdp.encaps.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link K8sTenantKeys} 的形态校验测试。
 *
 * <p>租户业务键从 Long 放开成字符串（台账 #56 方案③）后，它会流向 Namespace 名、
 * NetworkPolicy 的 {@code tenantId} 标签与 RoleBinding 组名——这三处各有字符集约束。
 * 这里锁住"既有取值照常可用 + 不合形态者当场拒绝"，避免把非法键交给 K8s 后得到
 * 一个难以归因的 API Server 错误。</p>
 */
class K8sTenantKeysTest {

    @Test
    @DisplayName("字符串租户键与历史数字键都可用")
    void acceptsValidKeys() {
        assertThat(K8sTenantKeys.requireSafe("platform-admin")).isEqualTo("platform-admin");
        assertThat(K8sTenantKeys.requireSafe("100")).isEqualTo("100");
        assertThat(K8sTenantKeys.requireSafe("tenant-a")).isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("大写、下划线、连字符首尾都不放行")
    void rejectsShapeViolations() {
        for (String bad : new String[]{"Tenant_A", "-lead", "trail-", "a--", "tenant a"}) {
            assertThatThrownBy(() -> K8sTenantKeys.requireSafe(bad))
                    .as("键 %s 不是合法的 K8s 名称/标签值", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不是合法的 K8s");
        }
    }

    @Test
    @DisplayName("空值与非 ASCII 键被拒")
    void rejectsNullBlankAndNonAscii() {
        assertThatThrownBy(() -> K8sTenantKeys.requireSafe(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> K8sTenantKeys.requireSafe("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> K8sTenantKeys.requireSafe("租户甲")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("长度上限取更严的 63：63 可过、64 不可")
    void enforcesLengthBound() {
        String at63 = "a" + "-b".repeat(31);   // 63 字符
        assertThat(at63).hasSize(63);
        assertThat(K8sTenantKeys.requireSafe(at63)).isEqualTo(at63);
        assertThatThrownBy(() -> K8sTenantKeys.requireSafe(at63 + "c"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
