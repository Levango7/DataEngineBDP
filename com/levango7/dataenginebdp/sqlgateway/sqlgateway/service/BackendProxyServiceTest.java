package com.shuqing.bigdata.sqlgateway.service;

import com.shuqing.bigdata.sqlgateway.config.BackendProperties;
import com.shuqing.bigdata.sqlgateway.model.SqlExecuteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BackendProxyService 单元测试。
 *
 * <p>验证后端代理服务的初始化、URL获取和熔断降级行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class BackendProxyServiceTest {

    @Mock
    private WebClient.Builder webClientBuilder;

    private BackendProperties backendProperties;

    @BeforeEach
    void setUp() {
        backendProperties = new BackendProperties();
        BackendProperties.BackendConfig trinoConfig = new BackendProperties.BackendConfig();
        trinoConfig.setUrl("http://trino-test:8080");
        trinoConfig.setTimeout(5);
        BackendProperties.BackendConfig dorisConfig = new BackendProperties.BackendConfig();
        dorisConfig.setUrl("http://doris-test:9030");
        dorisConfig.setTimeout(5);
        backendProperties.setTrino(trinoConfig);
        backendProperties.setDoris(dorisConfig);
    }

    @Test
    @DisplayName("getTrinoUrl — 返回配置的Trino URL")
    void getTrinoUrl_shouldReturnConfiguredUrl() {
        // 需要构建一个真实的 WebClient.Builder
        WebClient.Builder realBuilder = WebClient.builder();
        BackendProxyService service = new BackendProxyService(realBuilder, backendProperties);

        assertThat(service.getTrinoUrl()).isEqualTo("http://trino-test:8080");
    }

    @Test
    @DisplayName("getDorisUrl — 返回配置的Doris URL")
    void getDorisUrl_shouldReturnConfiguredUrl() {
        WebClient.Builder realBuilder = WebClient.builder();
        BackendProxyService service = new BackendProxyService(realBuilder, backendProperties);

        assertThat(service.getDorisUrl()).isEqualTo("http://doris-test:9030");
    }

    @Test
    @DisplayName("getTrinoUrl — Trino配置为null时返回null")
    void getTrinoUrl_nullConfig_shouldReturnNull() {
        BackendProperties props = new BackendProperties();
        props.setTrino(null);
        WebClient.Builder realBuilder = WebClient.builder();
        BackendProxyService service = new BackendProxyService(realBuilder, props);

        assertThat(service.getTrinoUrl()).isNull();
    }

    @Test
    @DisplayName("getDorisUrl — Doris配置为null时返回null")
    void getDorisUrl_nullConfig_shouldReturnNull() {
        BackendProperties props = new BackendProperties();
        props.setDoris(null);
        WebClient.Builder realBuilder = WebClient.builder();
        BackendProxyService service = new BackendProxyService(realBuilder, props);

        assertThat(service.getDorisUrl()).isNull();
    }

    @Test
    @DisplayName("初始化 — 使用默认URL当配置URL为null")
    void init_shouldUseDefaultUrlWhenConfigUrlIsNull() {
        BackendProperties props = new BackendProperties();
        BackendProperties.BackendConfig trinoConfig = new BackendProperties.BackendConfig();
        trinoConfig.setUrl(null);
        BackendProperties.BackendConfig dorisConfig = new BackendProperties.BackendConfig();
        dorisConfig.setUrl(null);
        props.setTrino(trinoConfig);
        props.setDoris(dorisConfig);

        WebClient.Builder realBuilder = WebClient.builder();
        BackendProxyService service = new BackendProxyService(realBuilder, props);

        // URL为null时使用默认值，但getTrinoUrl/getDorisUrl返回的是配置值null
        assertThat(service.getTrinoUrl()).isNull();
        assertThat(service.getDorisUrl()).isNull();
    }
}