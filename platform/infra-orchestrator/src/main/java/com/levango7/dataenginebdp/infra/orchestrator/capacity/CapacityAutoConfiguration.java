package com.levango7.dataenginebdp.infra.orchestrator.capacity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 容量规划自动配置（v2.1 生产化加固）。
 *
 * <p>当 {@code app.capacity.enabled=true} 时自动装配容量规划相关 Bean。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "app.capacity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CapacityAutoConfiguration {

    /**
     * Prometheus 指标采集服务 Bean。
     *
     * @param webClientBuilder WebClient 构建器
     * @param config           容量配置
     * @return PrometheusMetricsService
     */
    @Bean
    public PrometheusMetricsService prometheusMetricsService(
            WebClient.Builder webClientBuilder, CapacityConfig config) {
        return new PrometheusMetricsService(webClientBuilder, config);
    }
}