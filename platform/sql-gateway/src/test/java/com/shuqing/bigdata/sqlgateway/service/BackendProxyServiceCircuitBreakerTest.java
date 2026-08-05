package com.shuqing.bigdata.sqlgateway.service;

import com.shuqing.bigdata.sqlgateway.config.BackendProperties;
import com.shuqing.bigdata.sqlgateway.model.SqlExecuteResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BackendProxyService 熔断器测试。
 *
 * <p>通过反射设置熔断器状态，验证熔断逻辑。</p>
 */
class BackendProxyServiceCircuitBreakerTest {

    private WebClient.Builder webClientBuilder = WebClient.builder();

    @Test
    @DisplayName("熔断器 — Trino熔断打开时直接返回DEGRADED")
    void circuitBreaker_trinoOpen_shouldReturnDegradedImmediately() throws Exception {
        BackendProperties props = new BackendProperties();
        BackendProperties.BackendConfig trinoConfig = new BackendProperties.BackendConfig();
        trinoConfig.setUrl("http://localhost:19999");
        props.setTrino(trinoConfig);
        props.setDoris(new BackendProperties.BackendConfig());

        BackendProxyService service = new BackendProxyService(webClientBuilder, props);

        // 通过反射设置熔断器为打开状态
        Field trinoOpenSince = BackendProxyService.class.getDeclaredField("trinoOpenSince");
        trinoOpenSince.setAccessible(true);
        ((AtomicLong) trinoOpenSince.get(service)).set(System.currentTimeMillis());

        SqlExecuteResponse response = service.proxyToTrino("SELECT 1", "t1")
                .block(java.time.Duration.ofSeconds(5));

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("DEGRADED");
        assertThat(response.getEngine()).isEqualTo("trino");
    }

    @Test
    @DisplayName("熔断器 — Doris熔断打开时直接返回DEGRADED")
    void circuitBreaker_dorisOpen_shouldReturnDegradedImmediately() throws Exception {
        BackendProperties props = new BackendProperties();
        props.setTrino(new BackendProperties.BackendConfig());
        BackendProperties.BackendConfig dorisConfig = new BackendProperties.BackendConfig();
        dorisConfig.setUrl("http://localhost:19998");
        props.setDoris(dorisConfig);

        BackendProxyService service = new BackendProxyService(webClientBuilder, props);

        // 通过反射设置Doris熔断器为打开状态
        Field dorisOpenSince = BackendProxyService.class.getDeclaredField("dorisOpenSince");
        dorisOpenSince.setAccessible(true);
        ((AtomicLong) dorisOpenSince.get(service)).set(System.currentTimeMillis());

        SqlExecuteResponse response = service.proxyToDoris("SELECT 1", "t1")
                .block(java.time.Duration.ofSeconds(5));

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("DEGRADED");
        assertThat(response.getEngine()).isEqualTo("doris");
    }

    @Test
    @DisplayName("熔断器 — 熔断超时后进入半开状态")
    void circuitBreaker_expired_shouldEnterHalfOpen() throws Exception {
        BackendProperties props = new BackendProperties();
        BackendProperties.BackendConfig trinoConfig = new BackendProperties.BackendConfig();
        trinoConfig.setUrl("http://localhost:19999");
        props.setTrino(trinoConfig);
        props.setDoris(new BackendProperties.BackendConfig());

        BackendProxyService service = new BackendProxyService(webClientBuilder, props);

        // 设置熔断器打开时间为很久以前（超过60秒恢复时间）
        Field trinoOpenSince = BackendProxyService.class.getDeclaredField("trinoOpenSince");
        trinoOpenSince.setAccessible(true);
        ((AtomicLong) trinoOpenSince.get(service)).set(System.currentTimeMillis() - 120000);

        // 应该进入半开状态，尝试恢复（会连接后端，后端不可用返回错误）
        SqlExecuteResponse response = service.proxyToTrino("SELECT 1", "t1")
                .block(java.time.Duration.ofSeconds(10));

        assertThat(response).isNotNull();
        // 半开状态会尝试连接后端，后端不可用返回DEGRADED或FAILED
        assertThat(response.getStatus()).isIn("DEGRADED", "FAILED");
    }

    @Test
    @DisplayName("熔断器 — 熔断未打开时正常请求后端")
    void circuitBreaker_notOpen_shouldProxyToBackend() throws Exception {
        BackendProperties props = new BackendProperties();
        BackendProperties.BackendConfig trinoConfig = new BackendProperties.BackendConfig();
        trinoConfig.setUrl("http://localhost:19999");
        props.setTrino(trinoConfig);
        props.setDoris(new BackendProperties.BackendConfig());

        BackendProxyService service = new BackendProxyService(webClientBuilder, props);

        // 熔断器未打开（默认状态），正常请求后端
        Field trinoOpenSince = BackendProxyService.class.getDeclaredField("trinoOpenSince");
        trinoOpenSince.setAccessible(true);
        // 确认初始状态为0（未熔断）
        assertThat(((AtomicLong) trinoOpenSince.get(service)).get()).isEqualTo(0L);

        SqlExecuteResponse response = service.proxyToTrino("SELECT 1", "t1")
                .block(java.time.Duration.ofSeconds(10));

        assertThat(response).isNotNull();
    }
}