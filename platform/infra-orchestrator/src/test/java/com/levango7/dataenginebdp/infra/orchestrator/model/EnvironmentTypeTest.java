package com.levango7.dataenginebdp.infra.orchestrator.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EnvironmentType} 单元测试。
 *
 * <p>覆盖：7 种枚举值完整性、路由路径构造、分类判断、字符串解析。</p>
 */
class EnvironmentTypeTest {

    @Test
    void shouldHaveExactlySevenEnvironmentTypes() {
        EnvironmentType[] values = EnvironmentType.values();
        assertThat(values).hasSize(7);
        assertThat(EnvironmentType.allValues()).containsExactlyInAnyOrder(
                "XINCHANG", "BAREMETAL",
                "CLOUD_HUAWEI", "CLOUD_ALI", "CLOUD_TENCENT",
                "PRIVATE_VSPHERE", "PRIVATE_OPENSTACK");
    }

    @ParameterizedTest
    @EnumSource(EnvironmentType.class)
    void shouldHaveNonBlankProviderKindAndSubType(EnvironmentType type) {
        assertThat(type.getProviderKind()).isNotBlank();
        assertThat(type.getSubType()).isNotBlank();
        assertThat(type.getDescription()).isNotBlank();
    }

    @Test
    void xinchangShouldRouteToXinchangProvider() {
        assertThat(EnvironmentType.XINCHANG.getRestPathPrefix())
                .isEqualTo("/api/v1/clusters/xinchang");
        assertThat(EnvironmentType.XINCHANG.getProviderKind()).isEqualTo("xinchang");
        assertThat(EnvironmentType.XINCHANG.isBareMetal()).isTrue();
        assertThat(EnvironmentType.XINCHANG.isCloud()).isFalse();
        assertThat(EnvironmentType.XINCHANG.isPrivateCloud()).isFalse();
    }

    @Test
    void baremetalShouldRouteToBaremetalProvider() {
        assertThat(EnvironmentType.BAREMETAL.getRestPathPrefix())
                .isEqualTo("/api/v1/clusters/baremetal");
        assertThat(EnvironmentType.BAREMETAL.isBareMetal()).isTrue();
    }

    @Test
    void cloudHuaweiShouldRouteToCloudHuawei() {
        assertThat(EnvironmentType.CLOUD_HUAWEI.getRestPathPrefix())
                .isEqualTo("/api/v1/clusters/cloud/huawei");
        assertThat(EnvironmentType.CLOUD_HUAWEI.isCloud()).isTrue();
        assertThat(EnvironmentType.CLOUD_HUAWEI.getSubType()).isEqualTo("huawei");
    }

    @Test
    void cloudAliShouldRouteToCloudAli() {
        assertThat(EnvironmentType.CLOUD_ALI.getRestPathPrefix())
                .isEqualTo("/api/v1/clusters/cloud/ali");
        assertThat(EnvironmentType.CLOUD_ALI.isCloud()).isTrue();
    }

    @Test
    void cloudTencentShouldRouteToCloudTencent() {
        assertThat(EnvironmentType.CLOUD_TENCENT.getRestPathPrefix())
                .isEqualTo("/api/v1/clusters/cloud/tencent");
        assertThat(EnvironmentType.CLOUD_TENCENT.isCloud()).isTrue();
    }

    @Test
    void privateVsphereShouldRouteToPrivateVsphere() {
        assertThat(EnvironmentType.PRIVATE_VSPHERE.getRestPathPrefix())
                .isEqualTo("/api/v1/clusters/private/vsphere");
        assertThat(EnvironmentType.PRIVATE_VSPHERE.isPrivateCloud()).isTrue();
    }

    @Test
    void privateOpenstackShouldRouteToPrivateOpenstack() {
        assertThat(EnvironmentType.PRIVATE_OPENSTACK.getRestPathPrefix())
                .isEqualTo("/api/v1/clusters/private/openstack");
        assertThat(EnvironmentType.PRIVATE_OPENSTACK.isPrivateCloud()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"xinchang", "XINCHANG", "  XINCHANG  ", "baremetal", "CLOUD_HUAWEI"})
    void fromStringShouldParseCaseInsensitive(String input) {
        assertThat(EnvironmentType.fromString(input)).isNotNull();
    }

    @Test
    void fromStringShouldRejectUnknownValue() {
        assertThatThrownBy(() -> EnvironmentType.fromString("UNKNOWN_ENV"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown environment type");
    }

    @Test
    void fromStringShouldRejectBlankValue() {
        assertThatThrownBy(() -> EnvironmentType.fromString(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnvironmentType.fromString(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allTypesShouldReturnAllSevenTypes() {
        Set<EnvironmentType> types = EnvironmentType.allTypes();
        assertThat(types).hasSize(7);
        assertThat(types).contains(EnvironmentType.values());
    }
}