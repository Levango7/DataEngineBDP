package com.levango7.dataenginebdp.infra.orchestrator.registry;

import com.levango7.dataenginebdp.infra.orchestrator.model.EnvironmentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProviderDescriptor} 单元测试，覆盖 URL 拼接方法。
 */
class ProviderDescriptorTest {

    @Test
    void shouldBuildHealthUrl() {
        ProviderDescriptor descriptor = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.XINCHANG)
                .name("xinchang")
                .baseUrl("http://xinchang:8090")
                .healthEndpoint("/actuator/health")
                .build();

        assertThat(descriptor.getHealthUrl()).isEqualTo("http://xinchang:8090/actuator/health");
    }

    @Test
    void shouldBuildRestBaseUrlForXinchang() {
        ProviderDescriptor descriptor = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.XINCHANG)
                .name("xinchang")
                .baseUrl("http://xinchang:8090")
                .build();

        assertThat(descriptor.getRestBaseUrl()).isEqualTo("http://xinchang:8090/api/v1/clusters/xinchang");
    }

    @Test
    void shouldBuildRestBaseUrlForCloudHuawei() {
        ProviderDescriptor descriptor = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.CLOUD_HUAWEI)
                .name("cloud-huawei")
                .baseUrl("http://cloud:8092")
                .build();

        assertThat(descriptor.getRestBaseUrl()).isEqualTo("http://cloud:8092/api/v1/clusters/cloud/huawei");
    }

    @Test
    void shouldBuildClusterUrl() {
        ProviderDescriptor descriptor = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.CLOUD_HUAWEI)
                .name("cloud-huawei")
                .baseUrl("http://cloud:8092")
                .build();

        assertThat(descriptor.getClusterUrl("abc-123"))
                .isEqualTo("http://cloud:8092/api/v1/clusters/cloud/huawei/abc-123");
    }

    @Test
    void shouldBuildScaleUrl() {
        ProviderDescriptor descriptor = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.BAREMETAL)
                .name("baremetal")
                .baseUrl("http://baremetal:8091")
                .build();

        assertThat(descriptor.getScaleUrl("cluster-1"))
                .isEqualTo("http://baremetal:8091/api/v1/clusters/baremetal/cluster-1/scale");
    }

    @Test
    void shouldStripTrailingSlashFromBaseUrl() {
        ProviderDescriptor descriptor = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.XINCHANG)
                .name("xinchang")
                .baseUrl("http://xinchang:8090/")
                .build();

        assertThat(descriptor.getRestBaseUrl()).isEqualTo("http://xinchang:8090/api/v1/clusters/xinchang");
    }

    @Test
    void shouldUseDefaultHealthEndpoint() {
        ProviderDescriptor descriptor = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.XINCHANG)
                .name("xinchang")
                .baseUrl("http://xinchang:8090")
                .build();

        assertThat(descriptor.getHealthEndpoint()).isEqualTo("/actuator/health");
        assertThat(descriptor.isEnabled()).isTrue();
        assertThat(descriptor.getImplementationLanguage()).isEqualTo("java");
    }
}