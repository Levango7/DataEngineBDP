package com.levango7.dataenginebdp.flinkcdc.materializedview.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 物化视图自动刷新全局配置。
 *
 * <p>通过 Spring Boot {@code @ConfigurationProperties} 绑定 {@code application.yml} 中
 * {@code materializedview.*} 前缀的配置项，控制物化视图刷新框架的全局行为。</p>
 *
 * <p>配置示例（application.yml）：</p>
 * <pre>{@code
 * materializedview:
 *   enabled: true
 *   doris:
 *     fe-hosts: "127.0.0.1:8030"
 *     username: "root"
 *     password: ""
 *     database: "report"
 *   refresh:
 *     default-scheduled-interval: PT5M
 *     default-batch-threshold: 100
 *     default-debounce-window: PT30S
 *     max-concurrent-refreshes: 5
 *     refresh-timeout: PT10M
 *   cdc:
 *     listen-topics: "cdc-orders,cdc-products"
 *     consumer-group: "mv-refresh-group"
 * }</pre>
 *
 * @author shuqing-bigdata
 */
@Configuration
@ConfigurationProperties(prefix = "materializedview")
public class MaterializedViewConfig {

    private static final Logger log = LoggerFactory.getLogger(MaterializedViewConfig.class);

    /** 全局开关：是否启用物化视图自动刷新。 */
    private boolean enabled = true;

    /** Doris 连接配置。 */
    private DorisConfig doris = new DorisConfig();

    /** 刷新策略默认值配置。 */
    private RefreshDefaults refresh = new RefreshDefaults();

    /** CDC 监听配置。 */
    private CdcConfig cdc = new CdcConfig();

    /** 默认构造器。 */
    public MaterializedViewConfig() {
    }

    /**
     * Doris FE 连接配置。
     */
    public static class DorisConfig {
        /** Doris FE 主机列表（逗号分隔，格式 host:port）。 */
        private String feHosts = "127.0.0.1:8030";

        /** Doris 用户名。 */
        private String username = "root";

        /** Doris 密码。 */
        private String password = "";

        /** 默认目标数据库。 */
        private String database = "report";

        /** Stream Load 连接超时（毫秒）。 */
        private long connectTimeoutMs = 10000L;

        /** Stream Load 读超时（毫秒）。 */
        private long readTimeoutMs = 60000L;

        public String getFeHosts() {
            return feHosts;
        }

        public void setFeHosts(String feHosts) {
            this.feHosts = feHosts;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        @Override
        public String toString() {
            return "DorisConfig{feHosts='" + feHosts + "', username='" + username
                    + "', database='" + database + "'}";
        }
    }

    /**
     * 刷新策略默认值。
     */
    public static class RefreshDefaults {
        /** 默认定时刷新周期。 */
        private Duration defaultScheduledInterval = Duration.ofMinutes(5);

        /** 默认事件触发批量阈值。 */
        private int defaultBatchThreshold = 100;

        /** 默认去抖窗口。 */
        private Duration defaultDebounceWindow = Duration.ofSeconds(30);

        /** 最大并发刷新数。 */
        private int maxConcurrentRefreshes = 5;

        /** 单次刷新超时时间。 */
        private Duration refreshTimeout = Duration.ofMinutes(10);

        public Duration getDefaultScheduledInterval() {
            return defaultScheduledInterval;
        }

        public void setDefaultScheduledInterval(Duration defaultScheduledInterval) {
            this.defaultScheduledInterval = defaultScheduledInterval;
        }

        public int getDefaultBatchThreshold() {
            return defaultBatchThreshold;
        }

        public void setDefaultBatchThreshold(int defaultBatchThreshold) {
            this.defaultBatchThreshold = defaultBatchThreshold;
        }

        public Duration getDefaultDebounceWindow() {
            return defaultDebounceWindow;
        }

        public void setDefaultDebounceWindow(Duration defaultDebounceWindow) {
            this.defaultDebounceWindow = defaultDebounceWindow;
        }

        public int getMaxConcurrentRefreshes() {
            return maxConcurrentRefreshes;
        }

        public void setMaxConcurrentRefreshes(int maxConcurrentRefreshes) {
            this.maxConcurrentRefreshes = maxConcurrentRefreshes;
        }

        public Duration getRefreshTimeout() {
            return refreshTimeout;
        }

        public void setRefreshTimeout(Duration refreshTimeout) {
            this.refreshTimeout = refreshTimeout;
        }

        @Override
        public String toString() {
            return "RefreshDefaults{interval=" + defaultScheduledInterval
                    + ", batchThreshold=" + defaultBatchThreshold
                    + ", maxConcurrent=" + maxConcurrentRefreshes + '}';
        }
    }

    /**
     * CDC 监听配置。
     */
    public static class CdcConfig {
        /** 监听的 CDC Topic 列表（逗号分隔）。 */
        private String listenTopics = "";

        /** 消费组 ID。 */
        private String consumerGroup = "mv-refresh-group";

        /** 是否从最早位点开始消费。 */
        private boolean fromEarliest = false;

        public String getListenTopics() {
            return listenTopics;
        }

        public void setListenTopics(String listenTopics) {
            this.listenTopics = listenTopics;
        }

        /**
         * 解析监听 Topic 列表。
         *
         * @return Topic 列表
         */
        public List<String> parsedTopics() {
            if (listenTopics == null || listenTopics.isBlank()) {
                return Collections.emptyList();
            }
            List<String> topics = new ArrayList<>();
            for (String t : listenTopics.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) {
                    topics.add(trimmed);
                }
            }
            return topics;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }

        public boolean isFromEarliest() {
            return fromEarliest;
        }

        public void setFromEarliest(boolean fromEarliest) {
            this.fromEarliest = fromEarliest;
        }

        @Override
        public String toString() {
            return "CdcConfig{listenTopics='" + listenTopics + "', consumerGroup='" + consumerGroup + "'}";
        }
    }

    // ===== getter / setter =====

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DorisConfig getDoris() {
        return doris;
    }

    public void setDoris(DorisConfig doris) {
        this.doris = Objects.requireNonNullElse(doris, new DorisConfig());
    }

    public RefreshDefaults getRefresh() {
        return refresh;
    }

    public void setRefresh(RefreshDefaults refresh) {
        this.refresh = Objects.requireNonNullElse(refresh, new RefreshDefaults());
    }

    public CdcConfig getCdc() {
        return cdc;
    }

    public void setCdc(CdcConfig cdc) {
        this.cdc = Objects.requireNonNullElse(cdc, new CdcConfig());
    }

    /**
     * 校验配置合法性并记录日志。
     *
     * @throws IllegalStateException 若配置不合法
     */
    public void validate() {
        if (enabled) {
            if (doris.feHosts == null || doris.feHosts.isBlank()) {
                throw new IllegalStateException("Doris FE 主机列表不能为空");
            }
            if (refresh.maxConcurrentRefreshes <= 0) {
                throw new IllegalStateException("maxConcurrentRefreshes 必须为正数");
            }
        }
        log.info("物化视图配置: enabled={}, doris={}, refresh={}, cdc={}",
                enabled, doris, refresh, cdc);
    }

    @Override
    public String toString() {
        return "MaterializedViewConfig{enabled=" + enabled
                + ", doris=" + doris
                + ", refresh=" + refresh
                + ", cdc=" + cdc + '}';
    }
}