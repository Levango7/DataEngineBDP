package com.levango7.dataenginebdp.sqlgateway.virtual;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 虚拟表元数据缓存。
 *
 * <p>使用 Caffeine 缓存虚拟表的 schema 信息（列定义），避免每次查询都访问数据库。
 * 缓存键为 {@code 租户ID:虚拟表名}，值为 {@link VirtualTableDefinition}。</p>
 *
 * <p>缓存策略：</p>
 * <ul>
 *   <li>最大容量 1000 条（可通过配置调整）；</li>
 *   <li>写入后 10 分钟过期；</li>
 *   <li>支持手动失效（虚拟表更新/删除时调用）。</li>
 * </ul>
 *
 * <p>缓存 miss 时由调用方（{@code VirtualTableService}）回源数据库查询并写入缓存。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class VirtualTableMetadataCache {

    private static final Logger log = LoggerFactory.getLogger(VirtualTableMetadataCache.class);

    private final Cache<String, VirtualTableDefinition> cache;

    /**
     * 构造缓存，使用默认配置。
     */
    public VirtualTableMetadataCache() {
        this(1000, Duration.ofMinutes(10));
    }

    /**
     * 构造缓存，指定容量与过期时间。
     *
     * @param maximumSize 最大缓存条数
     * @param expireAfterWrite 写入后过期时长
     */
    public VirtualTableMetadataCache(int maximumSize, Duration expireAfterWrite) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(expireAfterWrite)
                .recordStats()
                .build();
        log.info("虚拟表元数据缓存初始化完成 maxSize={} expireAfterWrite={}", maximumSize, expireAfterWrite);
    }

    /**
     * 生成缓存键。
     *
     * @param tenantId  租户 ID
     * @param tableName 虚拟表名
     * @return 缓存键
     */
    private static String cacheKey(String tenantId, String tableName) {
        return tenantId + ":" + tableName;
    }

    /**
     * 从缓存获取虚拟表定义。
     *
     * @param tenantId  租户 ID
     * @param tableName 虚拟表名
     * @return 虚拟表定义（若缓存命中）
     */
    public Optional<VirtualTableDefinition> get(String tenantId, String tableName) {
        VirtualTableDefinition def = cache.getIfPresent(cacheKey(tenantId, tableName));
        if (def != null) {
            log.debug("缓存命中 tenant={} table={}", tenantId, tableName);
        }
        return Optional.ofNullable(def);
    }

    /**
     * 写入缓存。
     *
     * @param definition 虚拟表定义
     */
    public void put(VirtualTableDefinition definition) {
        cache.put(cacheKey(definition.getTenantId(), definition.getTableName()), definition);
        log.debug("缓存写入 tenant={} table={}", definition.getTenantId(), definition.getTableName());
    }

    /**
     * 失效单条缓存（虚拟表更新/删除时调用）。
     *
     * @param tenantId  租户 ID
     * @param tableName 虚拟表名
     */
    public void invalidate(String tenantId, String tableName) {
        cache.invalidate(cacheKey(tenantId, tableName));
        log.debug("缓存失效 tenant={} table={}", tenantId, tableName);
    }

    /**
     * 失效指定租户的全部缓存。
     *
     * @param tenantId 租户 ID
     */
    public void invalidateTenant(String tenantId) {
        // Caffeine 不支持前缀删除，遍历清理
        cache.asMap().keySet().removeIf(key -> key.startsWith(tenantId + ":"));
        log.debug("租户缓存失效 tenant={}", tenantId);
    }

    /**
     * 清空全部缓存。
     */
    public void invalidateAll() {
        cache.invalidateAll();
        log.debug("全部缓存已清空");
    }

    /**
     * 获取缓存统计信息（命中率、条数等）。
     *
     * @return 统计信息 Map
     */
    public java.util.Map<String, Object> getStats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats stats = cache.stats();
        return java.util.Map.of(
                "hitCount", stats.hitCount(),
                "missCount", stats.missCount(),
                "hitRate", stats.hitRate(),
                "evictionCount", stats.evictionCount(),
                "estimatedSize", cache.estimatedSize()
        );
    }

    /**
     * 列出缓存中指定租户的全部虚拟表（用于调试）。
     *
     * @param tenantId 租户 ID
     * @return 虚拟表定义列表
     */
    public List<VirtualTableDefinition> listByTenant(String tenantId) {
        return cache.asMap().values().stream()
                .filter(def -> tenantId.equals(def.getTenantId()))
                .toList();
    }
}