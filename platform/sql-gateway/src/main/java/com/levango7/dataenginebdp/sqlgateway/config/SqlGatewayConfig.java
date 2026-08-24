package com.levango7.dataenginebdp.sqlgateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

/**
 * SQL 网关基础配置类。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>启用 {@link BackendProperties} 配置绑定；</li>
 *   <li>提供预配置的 {@link WebClient.Builder}：连接超时 5s、响应超时 30s、
 *       读写超时通过 Netty handler 兜底，确保后端不可用时快速失败。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Configuration
@EnableConfigurationProperties({BackendProperties.class, ExecuteProperties.class})
public class SqlGatewayConfig {

    /**
     * 连接建立超时时间（毫秒）。
     */
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    /**
     * 响应超时时间（秒）。
     */
    private static final int RESPONSE_TIMEOUT_SECONDS = 30;

    /**
     * 读写超时（秒），通过 Netty handler 在连接级别兜底。
     */
    private static final int READ_WRITE_TIMEOUT_SECONDS = 30;

    /**
     * 构造一个预配置的 {@link WebClient.Builder}：
     * <ul>
     *   <li>连接超时 5 秒（{@link ChannelOption#CONNECT_TIMEOUT_MILLIS}）；</li>
     *   <li>响应超时 30 秒（{@code responseTimeout}）；</li>
     *   <li>读写超时 30 秒（Netty {@link ReadTimeoutHandler}/{@link WriteTimeoutHandler}）。</li>
     * </ul>
     * 调用方可在此基础上继续链式配置（如 baseUrl、defaultHeader）后再 build。
     *
     * @return WebClient.Builder 实例
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(java.time.Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)));
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}