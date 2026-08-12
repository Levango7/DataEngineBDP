package com.levango7.dataenginebdp.sqlgateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BackendProperties 配置模型测试。
 */
class BackendPropertiesTest {

    @Test
    @DisplayName("默认值 — Trino和Doris配置已初始化")
    void defaultValues_shouldHaveBackendConfigs() {
        BackendProperties props = new BackendProperties();

        assertThat(props.getTrino()).isNotNull();
        assertThat(props.getDoris()).isNotNull();
        assertThat(props.getTrino().getTimeout()).isEqualTo(30);
        assertThat(props.getDoris().getTimeout()).isEqualTo(30);
        assertThat(props.getTrino().getMaxRetries()).isEqualTo(3);
        assertThat(props.getDoris().getMaxRetries()).isEqualTo(3);
    }

    @Test
    @DisplayName("setter — 修改后端URL")
    void setter_shouldModifyUrl() {
        BackendProperties props = new BackendProperties();
        props.getTrino().setUrl("http://custom-trino:8080");
        props.getDoris().setUrl("http://custom-doris:9030");

        assertThat(props.getTrino().getUrl()).isEqualTo("http://custom-trino:8080");
        assertThat(props.getDoris().getUrl()).isEqualTo("http://custom-doris:9030");
    }

    @Test
    @DisplayName("BackendConfig — setter修改timeout和maxRetries")
    void backendConfig_shouldAllowModification() {
        BackendProperties.BackendConfig config = new BackendProperties.BackendConfig();
        config.setUrl("http://test:8080");
        config.setTimeout(60);
        config.setMaxRetries(5);

        assertThat(config.getUrl()).isEqualTo("http://test:8080");
        assertThat(config.getTimeout()).isEqualTo(60);
        assertThat(config.getMaxRetries()).isEqualTo(5);
    }
}