package com.levango7.dataenginebdp.infra.orchestrator.registry;

import com.levango7.dataenginebdp.infra.orchestrator.model.EnvironmentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EnvironmentProfile} 单元测试，覆盖内置默认值与 yml 覆盖逻辑。
 */
class EnvironmentProfileTest {

    @Test
    void shouldReturnBuiltinDefaultsForXinchang() {
        EnvironmentProfile profile = new EnvironmentProfile();
        EnvironmentProfile.ProfileDefaults defaults = profile.getOrDefault(EnvironmentType.XINCHANG);

        assertThat(defaults.getDefaultCpuCores()).isEqualTo(16);
        assertThat(defaults.getDefaultMemoryGb()).isEqualTo(64);
        assertThat(defaults.getDefaultDiskGb()).isEqualTo(500);
        assertThat(defaults.getDefaultK8sVersion()).isEqualTo("v1.28.9");
        assertThat(defaults.getDefaultNetworkType()).isEqualTo("vlan");
    }

    @Test
    void shouldReturnBuiltinDefaultsForBaremetal() {
        EnvironmentProfile profile = new EnvironmentProfile();
        EnvironmentProfile.ProfileDefaults defaults = profile.getOrDefault(EnvironmentType.BAREMETAL);

        assertThat(defaults.getDefaultCpuCores()).isEqualTo(32);
        assertThat(defaults.getDefaultMemoryGb()).isEqualTo(128);
        assertThat(defaults.getDefaultK8sVersion()).isEqualTo("v1.29.2");
    }

    @Test
    void shouldReturnBuiltinDefaultsForCloud() {
        EnvironmentProfile profile = new EnvironmentProfile();

        EnvironmentProfile.ProfileDefaults huawei = profile.getOrDefault(EnvironmentType.CLOUD_HUAWEI);
        EnvironmentProfile.ProfileDefaults ali = profile.getOrDefault(EnvironmentType.CLOUD_ALI);
        EnvironmentProfile.ProfileDefaults tencent = profile.getOrDefault(EnvironmentType.CLOUD_TENCENT);

        assertThat(huawei.getDefaultCpuCores()).isEqualTo(8);
        assertThat(huawei.getDefaultNetworkType()).isEqualTo("vpc");
        assertThat(ali.getDefaultCpuCores()).isEqualTo(8);
        assertThat(tencent.getDefaultCpuCores()).isEqualTo(8);
    }

    @Test
    void shouldReturnBuiltinDefaultsForPrivateCloud() {
        EnvironmentProfile profile = new EnvironmentProfile();

        EnvironmentProfile.ProfileDefaults vsphere = profile.getOrDefault(EnvironmentType.PRIVATE_VSPHERE);
        EnvironmentProfile.ProfileDefaults openstack = profile.getOrDefault(EnvironmentType.PRIVATE_OPENSTACK);

        assertThat(vsphere.getDefaultCpuCores()).isEqualTo(8);
        assertThat(vsphere.getDefaultMemoryGb()).isEqualTo(16);
        assertThat(openstack.getDefaultNetworkType()).isEqualTo("vlan");
    }

    @Test
    void shouldReturnYmlOverrideWhenPresent() {
        EnvironmentProfile profile = new EnvironmentProfile();
        EnvironmentProfile.ProfileDefaults override = EnvironmentProfile.ProfileDefaults.builder()
                .defaultCpuCores(64)
                .defaultMemoryGb(256)
                .defaultDiskGb(2000)
                .defaultK8sVersion("v1.30.0")
                .defaultNetworkType("vlan")
                .build();
        profile.setProfiles(Map.of("XINCHANG", override));

        EnvironmentProfile.ProfileDefaults result = profile.getOrDefault(EnvironmentType.XINCHANG);
        assertThat(result.getDefaultCpuCores()).isEqualTo(64);
        assertThat(result.getDefaultMemoryGb()).isEqualTo(256);
        assertThat(result.getDefaultK8sVersion()).isEqualTo("v1.30.0");
    }

    @Test
    void allProfilesShouldReturnAllSevenTypes() {
        EnvironmentProfile profile = new EnvironmentProfile();
        Map<EnvironmentType, EnvironmentProfile.ProfileDefaults> all = profile.allProfiles();

        assertThat(all).hasSize(7);
        assertThat(all.keySet()).containsExactlyInAnyOrder(EnvironmentType.values());
    }
}