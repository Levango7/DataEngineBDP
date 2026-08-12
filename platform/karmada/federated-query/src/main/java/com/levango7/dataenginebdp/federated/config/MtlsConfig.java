package com.levango7.dataenginebdp.federated.config;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.ClientAuth;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * mTLS 跨集群传输配置（复用 Phase 1 Istio mTLS）。
 *
 * <p>当 {@code federated.mtls.enabled=true} 时，构造一个装载双向证书的
 * {@link WebClient}，用于跨集群查询的安全传输。Istio sidecar 在生产环境自动注入
 * mTLS，本配置提供 Pod 内 WebClient 到 sidecar 的 TLS 终结能力，以及在
 * 无 sidecar 的纯 Java-to-Java 场景下的端到端 mTLS。
 *
 * <p>配置示例（application.yml）：
 * <pre>
 * federated:
 *   mtls:
 *     enabled: true
 *     trust-store-path: /etc/istio/tls/ca.p12
 *     trust-store-password: ${MTLS_TRUSTSTORE_PASSWORD}
 *     key-store-path: /etc/istio/tls/client.p12
 *     key-store-password: ${MTLS_KEYSTORE_PASSWORD}
 *     key-alias: client
 *     verify-hostname: true
 * </pre>
 */
@Configuration
public class MtlsConfig {

    /**
     * 构造基于 mTLS 的 {@link WebClient}。
     *
     * <p>当 mTLS 未启用时，返回一个普通的 WebClient（用于本地开发与测试）。
     */
    @Bean
    public WebClient clusterWebClient(FederatedQueryProperties props) {
        FederatedQueryProperties.MtlsConfig mtls = props.getMtls();
        HttpClient httpClient = HttpClient.create();

        if (mtls != null && mtls.isEnabled()) {
            SslContext sslContext = buildSslContext(mtls);
            httpClient = httpClient.secure(sslSpec -> sslSpec.sslContext(sslContext)
                    .handshakeTimeout(java.time.Duration.ofSeconds(10)));
        } else {
            // 未启用 mTLS：使用 JDK 默认 SSL 上下文（仍可走 https）。
            httpClient = httpClient.secure();
        }

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /**
     * 构造 Netty {@link SslContext}，装载客户端密钥库与信任库。
     */
    private SslContext buildSslContext(FederatedQueryProperties.MtlsConfig mtls) {
        try {
            SslContextBuilder builder = SslContextBuilder.forClient();

            // 装载客户端密钥库（双向认证）。
            if (mtls.getKeyStorePath() != null && !mtls.getKeyStorePath().isBlank()) {
                KeyManagerFactory kmf = loadKeyManagerFactory(
                        mtls.getKeyStorePath(),
                        mtls.getKeyStorePassword(),
                        mtls.getKeyStoreType());
                builder.keyManager(kmf);
            }

            // 装载信任库（CA 证书）。
            if (mtls.getTrustStorePath() != null && !mtls.getTrustStorePath().isBlank()) {
                TrustManagerFactory tmf = loadTrustManagerFactory(
                        mtls.getTrustStorePath(),
                        mtls.getTrustStorePassword(),
                        mtls.getTrustStoreType());
                builder.trustManager(tmf);
            }

            // 要求服务端认证（mTLS）。
            builder.clientAuth(ClientAuth.REQUIRE);

            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build mTLS SslContext: " + e.getMessage(), e);
        }
    }

    private KeyManagerFactory loadKeyManagerFactory(String path, String password, String type) throws Exception {
        KeyStore ks = KeyStore.getInstance(type != null ? type : "PKCS12");
        try (InputStream is = new FileInputStream(path)) {
            ks.load(is, password != null ? password.toCharArray() : null);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password != null ? password.toCharArray() : null);
        return kmf;
    }

    private TrustManagerFactory loadTrustManagerFactory(String path, String password, String type) throws Exception {
        KeyStore ts = KeyStore.getInstance(type != null ? type : "PKCS12");
        try (InputStream is = new FileInputStream(path)) {
            ts.load(is, password != null ? password.toCharArray() : null);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        return tmf;
    }
}