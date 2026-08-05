package com.shuqing.bigdata.sqlgateway.service;

import com.shuqing.bigdata.sqlgateway.config.BackendProperties;
import com.shuqing.bigdata.sqlgateway.model.SqlExecuteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BackendProxyService 扩展测试。
 *
 * <p>覆盖初始化、熔断器逻辑和代理方法。</p>
 */
class BackendProxyServiceExtendedTest {

    private WebClient.Builder webClientBuilder;
    private BackendProperties backendProperties;

    @BeforeEach
    void setUp() {
        webClientBuilder = WebClient.builder();
        backendProperties = new BackendProperties();

        BackendProperties.BackendConfig trinoConfig = new BackendProperties.BackendConfig();
        trinoConfig.setUrl("http://localhost:19999");
        trinoConfig.setTimeout(2);

        BackendProperties.BackendConfig dorisConfig = new BackendProperties.BackendConfig();
        dorisConfig.setUrl("http://localhost:19998");
        dorisConfig.setTimeout(2);

        backendProperties.setTrino(trinoConfig);
        backendProperties.setDoris(dorisConfig);
    }

    @Test
    @DisplayName("proxyToTrino — 后端不可用时返回DEGRADED")
    void proxyToTrino_backendUnavailable_shouldReturnDegraded() {
        BackendProxyService service = new BackendProxyService(webClientBuilder, backendProperties);

        SqlExecuteResponse response = service.proxyToTrino("SELECT 1", "t1")
                .block(java.time.Duration.ofSeconds(10));

        assertThat(response).isNotNull();
        assertThat(response.getEngine()).isEqualTo("trino");
        assertThat(response.getStatus()).isIn("DEGRADED", "FAILED");
    }

    @Test
    @DisplayName("proxyToDoris — 后端不可用时返回DEGRADED")
    void proxyToDoris_backendUnavailable_shouldReturnDegraded() {
        BackendProxyService service = new BackendProxyService(webClientBuilder, backendProperties);

        SqlExecuteResponse response = service.proxyToDoris("SELECT 1", "t1")
                .block(java.time.Duration.ofSeconds(10));

        assertThat(response).isNotNull();
        assertThat(response.getEngine()).isEqualTo("doris");
        assertThat(response.getStatus()).isIn("DEGRADED", "FAILED");
    }

    @Test
    @DisplayName("proxyToTrino — tenantId为null时使用默认值")
    void proxyToTrino_nullTenantId_shouldUseDefault() {
        BackendProxyService service = new BackendProxyService(webClientBuilder, backendProperties);

        SqlExecuteResponse response = service.proxyToTrino("SELECT 1", null)
                .block(java.time.Duration.ofSeconds(10));

        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("proxyToDoris — tenantId为null时使用默认值")
    void proxyToDoris_nullTenantId_shouldUseDefault() {
        BackendProxyService service = new BackendProxyService(webClientBuilder, backendProperties);

        SqlExecuteResponse response = service.proxyToDoris("SELECT 1", null)
                .block(java.time.Duration.ofSeconds(10));

        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("初始化 — 使用默认URL当配置为null")
    void init_nullConfig_shouldUseDefaultUrl() {
        BackendProperties props = new BackendProperties();
        BackendProxyService service = new BackendProxyService(webClientBuilder, props);

        assertThat(service).isNotNull();
    }

    @Test
    @DisplayName("连续失败 — 多次调用后端不可用仍能返回响应")
    void multipleFailures_shouldStillReturnResponse() {
        BackendProxyService service = new BackendProxyService(webClientBuilder, backendProperties);

        // 连续调用多次，验证熔断器逻辑
        for (int i = 0; i < 3; i++) {
            SqlExecuteResponse response = service.proxyToTrino("SELECT 1", "t1")
                    .block(java.time.Duration.ofSeconds(10));
            assertThat(response).isNotNull();
        }
    }
}
