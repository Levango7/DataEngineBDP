package com.levango7.dataenginebdp.sqlgateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlGatewayConfig 测试。
 */
class SqlGatewayConfigTest {

    @Test
    @DisplayName("webClientBuilder — 返回非null的WebClient.Builder")
    void webClientBuilder_shouldReturnNonNullBuilder() {
        SqlGatewayConfig config = new SqlGatewayConfig();
        WebClient.Builder builder = config.webClientBuilder();

        assertThat(builder).isNotNull();
    }

    @Test
    @DisplayName("webClientBuilder — 可以基于builder创建WebClient")
    void webClientBuilder_shouldBeUsableToCreateClient() {
        SqlGatewayConfig config = new SqlGatewayConfig();
        WebClient.Builder builder = config.webClientBuilder();
        WebClient client = builder.baseUrl("http://localhost:8080").build();

        assertThat(client).isNotNull();
    }

    @Test
    @DisplayName("webClientBuilder — 多次调用返回不同实例")
    void webClientBuilder_shouldReturnDifferentInstances() {
        SqlGatewayConfig config = new SqlGatewayConfig();
        WebClient.Builder builder1 = config.webClientBuilder();
        WebClient.Builder builder2 = config.webClientBuilder();

        assertThat(builder1).isNotSameAs(builder2);
    }
}