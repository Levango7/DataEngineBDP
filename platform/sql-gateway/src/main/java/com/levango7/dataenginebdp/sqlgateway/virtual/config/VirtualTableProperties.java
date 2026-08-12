package com.levango7.dataenginebdp.sqlgateway.virtual.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 虚拟表模块配置属性。
 *
 * <p>绑定 {@code application.yml} 中 {@code virtual-table} 前缀的配置项，
 * 供各组件按需注入。</p>
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * virtual-table:
 *   cache:
 *     maximum-size: 1000
 *     expire-after-write-minutes: 10
 *   materialization:
 *     scheduler-enabled: true
 *     default-refresh-interval-seconds: 300
 * }</pre>
 *
 * @author shuqing-bigdata
 */
@Configuration
@ConfigurationProperties(prefix = "virtual-table")
public class VirtualTableProperties {

    /**
     * 缓存配置。
     */
    private Cache cache = new Cache();

    /**
     * 物化配置。
     */
    private Materialization materialization = new Materialization();

    /**
     * 缓存配置项。
     */
    public static class Cache {
        private int maximumSize = 1000;
        private int expireAfterWriteMinutes = 10;

        public int getMaximumSize() {
            return maximumSize;
        }

        public void setMaximumSize(int maximumSize) {
            this.maximumSize = maximumSize;
        }

        public int getExpireAfterWriteMinutes() {
            return expireAfterWriteMinutes;
        }

        public void setExpireAfterWriteMinutes(int expireAfterWriteMinutes) {
            this.expireAfterWriteMinutes = expireAfterWriteMinutes;
        }
    }

    /**
     * 物化配置项。
     */
    public static class Materialization {
        private boolean schedulerEnabled = true;
        private int defaultRefreshIntervalSeconds = 300;

        public boolean isSchedulerEnabled() {
            return schedulerEnabled;
        }

        public void setSchedulerEnabled(boolean schedulerEnabled) {
            this.schedulerEnabled = schedulerEnabled;
        }

        public int getDefaultRefreshIntervalSeconds() {
            return defaultRefreshIntervalSeconds;
        }

        public void setDefaultRefreshIntervalSeconds(int defaultRefreshIntervalSeconds) {
            this.defaultRefreshIntervalSeconds = defaultRefreshIntervalSeconds;
        }
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public Materialization getMaterialization() {
        return materialization;
    }

    public void setMaterialization(Materialization materialization) {
        this.materialization = materialization;
    }
}