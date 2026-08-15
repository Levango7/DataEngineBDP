package com.levango7.dataenginebdp.sqlgateway.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * SQL 查询结果缓存配置（任务 D）。
 *
 * <p>Caffeine 本地缓存：1000 条 / 60s TTL / LRU 淘汰。
 * 缓存键含 engine+sql+tenantId（租户隔离，防跨租户泄漏）。</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SQL_QUERY_CACHE = "sqlQuery";

    /** 只读 SQL 前缀白名单（写操作永不缓存，防脏读）。 */
    private static final String[] READ_ONLY_PREFIXES = {
            "SELECT", "SHOW", "DESC", "DESCRIBE", "WITH", "EXPLAIN"
    };

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(SQL_QUERY_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .recordStats());
        return manager;
    }

    /**
     * 判断 SQL 是否只读（可缓存）。
     *
     * <p>跳过前导空白/注释后检查前缀；非只读 SQL 永不缓存（防脏读）。</p>
     */
    public static boolean isReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        String trimmed = sql.trim();
        // 跳过行注释
        while (trimmed.startsWith("--")) {
            int nl = trimmed.indexOf('\n');
            if (nl < 0) {
                return false;
            }
            trimmed = trimmed.substring(nl + 1).trim();
        }
        // 跳过块注释
        while (trimmed.startsWith("/*")) {
            int end = trimmed.indexOf("*/");
            if (end < 0) {
                return false;
            }
            trimmed = trimmed.substring(end + 2).trim();
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        for (String prefix : READ_ONLY_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
