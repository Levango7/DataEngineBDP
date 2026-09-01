package com.levango7.dataenginebdp.infra.xinchang.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置。
 *
 * <p>为 IPMI Redfish 调用与 K8s API 调用提供统一超时配置。</p>
 *
 * <p>Spring Boot 4 迁移说明：{@code RestTemplateBuilder} 移除了
 * {@code setConnectTimeout/setReadTimeout(Duration)}（超时统一由
 * {@code ClientHttpRequestFactorySettings} 表达）。此处超时直接在
 * {@link SimpleClientHttpRequestFactory} 上设置并经
 * {@code requestFactory(Supplier)} 注入，语义与原实现等价且不再依赖
 * 已删除的 Builder 超时方法。</p>
 */
@Configuration
public class RestTemplateConfig {

    /**
     * RestTemplate Bean。
     *
     * @param builder           Spring Boot RestTemplateBuilder
     * @param connectTimeoutMs  连接超时（毫秒）
     * @param readTimeoutMs     读取超时（毫秒）
     * @return RestTemplate
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder,
                                     @Value("${app.xinchang.ipmi.connect-timeout-ms:5000}") long connectTimeoutMs,
                                     @Value("${app.xinchang.ipmi.read-timeout-ms:30000}") long readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeoutMs);
        factory.setReadTimeout((int) readTimeoutMs);
        return builder
                .requestFactory(() -> factory)
                .build();
    }
}
