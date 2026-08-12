package com.levango7.dataenginebdp.infra.orchestrator.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient 配置 - 用于调用各下游 Provider 的 REST API。
 *
 * <p>L0.5 编排层通过 {@link WebClient} 异步调用四环境 Provider（信创/裸金属/公有云/私有云），
 * 此处统一配置连接超时、读超时、响应缓冲上限，避免单个 Provider 慢响应拖垮编排层。</p>
 *
 * <p>配置项（{@code app.orchestrator.webclient} 前缀）：</p>
 * <ul>
 *   <li>{@code connect-timeout-ms}：连接超时，默认 5s</li>
 *   <li>{@code read-timeout-ms}：读超时，默认 30s</li>
 *   <li>{@code write-timeout-ms}：写超时，默认 30s</li>
 *   <li>{@code response-timeout-ms}：整体响应超时，默认 60s</li>
 *   <li>{@code max-in-memory-size-mb}：响应缓冲上限，默认 16MB</li>
 * </ul>
 */
@Configuration
public class OrchestratorConfig {

    /**
     * 构造全局共享的 {@link WebClient}。
     *
     * <p>使用 Reactor Netty 作为底层 HTTP 客户端，配置连接/读/写超时。
     * 调用各 Provider 时通过 {@code WebClient.builder().baseUrl(...)} 派生实例。</p>
     *
     * @param connectTimeoutMs  连接超时（毫秒）
     * @param readTimeoutMs     读超时（毫秒）
     * @param writeTimeoutMs    写超时（毫秒）
     * @param responseTimeoutMs 响应超时（毫秒）
     * @param maxInMemorySizeMb 响应缓冲上限（MB）
     * @return 配置好的 WebClient
     */
    @Bean
    public WebClient webClient(
            @Value("${app.orchestrator.webclient.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${app.orchestrator.webclient.read-timeout-ms:30000}") int readTimeoutMs,
            @Value("${app.orchestrator.webclient.write-timeout-ms:30000}") int writeTimeoutMs,
            @Value("${app.orchestrator.webclient.response-timeout-ms:60000}") int responseTimeoutMs,
            @Value("${app.orchestrator.webclient.max-in-memory-size-mb:16}") int maxInMemorySizeMb) {

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(writeTimeoutMs, TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(
                        maxInMemorySizeMb * 1024 * 1024))
                .build();
    }
}