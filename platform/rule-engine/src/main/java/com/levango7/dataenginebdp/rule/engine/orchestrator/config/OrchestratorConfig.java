package com.levango7.dataenginebdp.rule.engine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 编排引擎配置。
 *
 * <p>绑定 application.yml 中 {@code app.orchestrator} 前缀的配置项，
 * 集中管理调度器线程池、默认重试、告警通道等参数。</p>
 *
 * <p>配置示例：
 * <pre>
 * app:
 *   orchestrator:
 *     scheduler:
 *       thread-pool-size: 8
 *       default-timeout-seconds: 300
 *     retry:
 *       default-max-retries: 3
 *       default-backoff-strategy: EXPONENTIAL
 *       default-backoff-interval-ms: 500
 *     alert:
 *       email:
 *         enabled: false
 *         from: orchestrator@shuqing.local
 *         recipients: ops@shuqing.local
 *       webhook:
 *         enabled: false
 *         url: ""
 * </pre>
 * </p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.orchestrator")
public class OrchestratorConfig {

    /** 调度器配置 */
    private Scheduler scheduler = new Scheduler();

    /** 重试默认配置 */
    private Retry retry = new Retry();

    /** 告警配置 */
    private Alert alert = new Alert();

    @Data
    public static class Scheduler {
        /** 调度线程池大小 */
        private int threadPoolSize = 8;
        /** 默认任务超时（秒） */
        private long defaultTimeoutSeconds = 300L;
    }

    @Data
    public static class Retry {
        /** 默认最大重试次数（不含首次） */
        private int defaultMaxRetries = 3;
        /** 默认退避策略：FIXED / EXPONENTIAL */
        private String defaultBackoffStrategy = "EXPONENTIAL";
        /** 默认退避基准间隔（毫秒） */
        private long defaultBackoffIntervalMs = 500L;
    }

    @Data
    public static class Alert {
        /** 邮件告警配置 */
        private Email email = new Email();
        /** Webhook 告警配置 */
        private Webhook webhook = new Webhook();
    }

    @Data
    public static class Email {
        private boolean enabled = false;
        private String from = "orchestrator@shuqing.local";
        /** 收件人列表，逗号分隔 */
        private String recipients = "";
    }

    @Data
    public static class Webhook {
        private boolean enabled = false;
        private String url = "";
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 5000;
    }
}