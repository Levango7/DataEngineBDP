package com.levango7.dataenginebdp.sqlgateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SQL 网关后端配置属性。
 *
 * <p>绑定配置前缀 {@code sql-gateway.backends}，集中管理 Trino 与 Doris 后端的
 * 连接 URL、超时与重试参数。</p>
 *
 * <p>典型 application.yml 用法：</p>
 * <pre>
 * sql-gateway:
 *   backends:
 *     trino:
 *       url: http://trino-service:8080
 *       timeout: 30
 *       max-retries: 3
 *     doris:
 *       url: http://doris-fe-service:9030
 *       timeout: 30
 *       max-retries: 3
 * </pre>
 *
 * @author shuqing-bigdata
 */
@Data
@ConfigurationProperties(prefix = "sql-gateway.backends")
public class BackendProperties {

    /**
     * Trino 后端配置。
     */
    private BackendConfig trino = new BackendConfig();

    /**
     * Doris 后端配置。
     */
    private BackendConfig doris = new BackendConfig();

    /**
     * 单个后端的连接配置。
     */
    @Data
    public static class BackendConfig {

        /**
         * 后端基础 URL，例如 {@code http://trino-service:8080}。
         */
        private String url;

        /**
         * 响应超时时间（秒），默认 30。
         */
        private int timeout = 30;

        /**
         * 最大重试次数（当前版本预留，由调用方实现），默认 3。
         */
        private int maxRetries = 3;

        /**
         * 后端用户名（Doris 默认 root）。
         */
        private String username = "root";

        /**
         * 后端密码（生产环境必须通过配置或环境变量注入）。
         */
        private String password = "";

        /**
         * 租户专属凭证映射：tenantId → 独立账号/密码。
         * <p>命中时使用租户专属凭证连接后端，实现引擎侧真实的租户权限隔离；
         * 未命中时回退到 {@link #username}/{@link #password} 并记录告警日志。</p>
         */
        private java.util.Map<String, TenantCredential> tenantUsers = new java.util.HashMap<>();

        /**
         * 租户专属后端凭证。
         */
        @Data
        public static class TenantCredential {

            /** 租户专属用户名。 */
            private String username;

            /** 租户专属密码。 */
            private String password;
        }
    }
}