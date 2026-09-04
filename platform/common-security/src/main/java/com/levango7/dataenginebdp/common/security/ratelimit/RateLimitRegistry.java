package com.levango7.dataenginebdp.common.security.ratelimit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 速率限制注册表：按"维度键 → 令牌桶"维护，附带定期清理过期桶防止内存泄漏。
 *
 * <p>维度键由调用方决定（如 {@code ip:1.2.3.4}、{@code tenant:t-001}）。
 * 每 {@code cleanupIntervalSeconds} 清扫一次长时间未访问的桶，
 * 默认闲置超过 10 分钟的桶会被回收。</p>
 */
public class RateLimitRegistry {

    private final List<RateLimitRule> rules;
    private final long idleEvictionNanos;
    private final AtomicLong lastCleanup = new AtomicLong(System.nanoTime());
    private final long cleanupIntervalNanos;

    /** 维度键 → (桶 + 最后访问时间)。 */
    private final Map<String, BucketEntry> buckets = new ConcurrentHashMap<>();

    private record BucketEntry(TokenBucket bucket, AtomicLong lastAccess) {
    }

    /**
     * 构造注册表。
     *
     * @param rules                   规则列表（有序，先匹配先用）
     * @param idleEvictionSeconds     桶闲置多久后回收
     * @param cleanupIntervalSeconds  清扫周期
     */
    public RateLimitRegistry(List<RateLimitRule> rules,
                             long idleEvictionSeconds,
                             long cleanupIntervalSeconds) {
        this.rules = List.copyOf(rules);
        this.idleEvictionNanos = idleEvictionSeconds * 1_000_000_000L;
        this.cleanupIntervalNanos = cleanupIntervalSeconds * 1_000_000_000L;
    }

    /**
     * 匹配路径对应的规则（先到先得）。
     *
     * @param path 请求路径
     * @return 命中规则；未命中返回 null（不限流）
     */
    public RateLimitRule matchRule(String path) {
        if (path == null) {
            return null;
        }
        for (RateLimitRule rule : rules) {
            if (path.startsWith(rule.pathPrefix())) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 对维度键尝试消耗一个令牌。
     *
     * @param rule 匹配到的规则
     * @param dimensionKey 维度键（ip:xxx / tenant:xxx）
     * @return true=放行；false=超限
     */
    public boolean tryAcquire(RateLimitRule rule, String dimensionKey) {
        maybeCleanup();
        BucketEntry entry = buckets.computeIfAbsent(
                rule.pathPrefix() + "|" + dimensionKey,
                k -> new BucketEntry(
                        new TokenBucket(rule.burstCapacity(), rule.ratePerMinute() / 60.0, TokenBucket.SYSTEM_NANOS),
                        new AtomicLong(System.nanoTime())));
        entry.lastAccess().set(System.nanoTime());
        return entry.bucket().tryConsume();
    }

    /** 惰性清扫：按上次清理时间间隔触发，回收闲置桶。 */
    private void maybeCleanup() {
        long now = System.nanoTime();
        long last = lastCleanup.get();
        if (now - last < cleanupIntervalNanos) {
            return;
        }
        if (!lastCleanup.compareAndSet(last, now)) {
            return;
        }
        buckets.entrySet().removeIf(e -> now - e.getValue().lastAccess().get() > idleEvictionNanos);
    }

    /** 当前桶数量（诊断用）。 */
    public int bucketCount() {
        return buckets.size();
    }
}
