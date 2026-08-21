package com.shuqing.bigdata.federated.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 跨集群查询服务配置属性。
 *
 * <p>对应 application.yml 中 {@code federated} 前缀的配置段。
 */
@Data
@ConfigurationProperties(prefix = "federated")
public class FederatedQueryProperties {

    /** 全局 Catalog 客户端配置（复用 Phase 1 platform/catalog REST API）。 */
    private CatalogConfig catalog = new CatalogConfig();

    /** mTLS 跨集群传输配置。 */
    private MtlsConfig mtls = new MtlsConfig();

    /** 降级策略配置。 */
    private DegradeConfig degrade = new DegradeConfig();

    /** 集群拓扑：集群名 → 端点配置。 */
    private Map<String, ClusterEndpoint> clusters = new HashMap<>();

    /** 本地集群名（降级时仅查本地表）。 */
    private String localCluster = "local-cluster";

    /** 查询归并配置。 */
    private MergeConfig merge = new MergeConfig();

    @Data
    public static class CatalogConfig {
        /** Catalog 服务基础 URL，例如 http://catalog:8080/api/v1/catalog。 */
        private String baseUrl = "http://localhost:8080/api/v1/catalog";
        /** 连接超时。 */
        private Duration connectTimeout = Duration.ofSeconds(5);
        /** 响应超时。 */
        private Duration responseTimeout = Duration.ofSeconds(10);
        /** 是否启用缓存（表元数据缓存）。 */
        private boolean cacheEnabled = true;
        /** 缓存 TTL。 */
        private Duration cacheTtl = Duration.ofMinutes(5);
    }

    @Data
    public static class MtlsConfig {
        /** 是否启用 mTLS。 */
        private boolean enabled = false;
        /** 信任库路径（PKCS12/JKS）。 */
        private String trustStorePath;
        /** 信任库密码。 */
        private String trustStorePassword;
        /** 密钥库路径。 */
        private String keyStorePath;
        /** 密钥库密码。 */
        private String keyStorePassword;
        /** 密钥别名。 */
        private String keyAlias;
        /** 信任库类型（PKCS12/JKS）。 */
        private String trustStoreType = "PKCS12";
        /** 密钥库类型。 */
        private String keyStoreType = "PKCS12";
        /** 是否校验主机名。 */
        private boolean verifyHostname = true;
    }

    @Data
    public static class DegradeConfig {
        /** 是否启用降级策略。 */
        private boolean enabled = true;
        /** 连接超时阈值。 */
        private Duration connectTimeout = Duration.ofSeconds(10);
        /** 查询超时阈值。 */
        private Duration queryTimeout = Duration.ofSeconds(30);
        /** 最大重试次数。 */
        int maxRetries = 2;
        /** 失败窗口：窗口内失败次数达到阈值则降级。 */
        private int failureWindow = 5;
        /** 失败阈值。 */
        private int failureThreshold = 3;
        /** 降级冷却时间（避免抖动）。 */
        private Duration cooldown = Duration.ofSeconds(30);
        /** 告警 webhook URL（可选）。 */
        private String alertWebhookUrl;
        /** 是否启用告警。 */
        private boolean alertEnabled = true;
    }

    @Data
    public static class MergeConfig {
        /** 归并策略：CONCAT / UNION / JOIN。 */
        private String strategy = "CONCAT";
        /** 单分片最大行数。 */
        private int maxRowsPerShard = 10000;
        /** 并行度。 */
        private int parallelism = 4;
    }

    /**
     * 集群端点配置。
     */
    @Data
    public static class ClusterEndpoint {
        /** 集群 HTTP/S 端点 URL，例如 https://xinchang-cluster:8091。 */
        private String url;
        /** 集群类型：xinchang / local / cloud。 */
        private String type;
        /** 集群厂商：kylin / kubernetes / huawei-cce。 */
        private String vendor;
        /** 架构：arm64 / amd64。 */
        private String arch;
        /** 区域。 */
        private String region;
        /** 环境：staging / production。 */
        private String env;
        /** 是否启用。 */
        private boolean enabled = true;
        /** 是否本地集群。 */
        private boolean local;
    }
}