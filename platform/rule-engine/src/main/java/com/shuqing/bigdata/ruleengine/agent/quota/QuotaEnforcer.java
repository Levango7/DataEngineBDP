package com.shuqing.bigdata.ruleengine.agent.quota;

import com.shuqing.bigdata.ruleengine.agent.core.Agent;
import com.shuqing.bigdata.ruleengine.agent.core.AgentContext;
import com.shuqing.bigdata.ruleengine.agent.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 配额强制执行器。
 *
 * <p>负责在 Agent 执行前后管理资源配额：
 * <ol>
 *   <li>{@link #resolveQuota}：解析生效配额（context 覆盖 &gt; 角色默认 &gt; 兜底）</li>
 *   <li>{@link #checkAndAcquire}：检查租户并发数与日调用次数，超限返回失败结果，否则计数+1</li>
 *   <li>{@link #release}：执行完成计数-1</li>
 *   <li>{@link #snapshot}：暴露当前计数，供监控与 REST 查询</li>
 * </ol>
 *
 * <p>并发安全：使用 {@link ConcurrentHashMap} + {@link AtomicInteger}，
 * 适用于高并发请求场景。日调用计数按 {@link LocalDate} 分桶，跨天自动失效。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class QuotaEnforcer {

    private static final Logger log = LoggerFactory.getLogger(QuotaEnforcer.class);

    /** 角色 → 默认配额 */
    private final Map<Agent.Role, AgentQuota> defaultQuotas;

    /** 兜底配额 */
    private final AgentQuota fallbackQuota;

    /** 并发计数：role → tenantId → 当前并发数 */
    private final Map<Agent.Role, Map<String, AtomicInteger>> concurrentCounters =
            new EnumMap<>(Agent.Role.class);

    /** 日调用计数：role → tenantId → 日期 → 当日调用数 */
    private final Map<Agent.Role, Map<String, Map<LocalDate, AtomicInteger>>> dailyCounters =
            new EnumMap<>(Agent.Role.class);

    /**
     * 使用默认配额构造。
     */
    public QuotaEnforcer() {
        this(AgentQuota.defaults(), AgentQuota.fallback());
    }

    /**
     * 自定义配额构造。
     *
     * @param defaultQuotas 角色 → 默认配额
     * @param fallbackQuota 兜底配额
     */
    public QuotaEnforcer(Map<Agent.Role, AgentQuota> defaultQuotas, AgentQuota fallbackQuota) {
        this.defaultQuotas = new EnumMap<>(Agent.Role.class);
        if (defaultQuotas != null) {
            this.defaultQuotas.putAll(defaultQuotas);
        }
        this.fallbackQuota = fallbackQuota == null ? AgentQuota.fallback() : fallbackQuota;
    }

    /**
     * 解析生效配额。
     *
     * <p>优先级：context 中显式指定的字段 &gt; 角色默认配额 &gt; 兜底配额。</p>
     *
     * @param role    Agent 角色
     * @param context 执行上下文
     * @return 生效配额，永不返回 {@code null}
     */
    public AgentQuota resolveQuota(Agent.Role role, AgentContext context) {
        Objects.requireNonNull(role, "role must not be null");
        AgentQuota base = defaultQuotas.getOrDefault(role, fallbackQuota);
        if (context == null) {
            return base;
        }
        AgentQuota override = AgentQuota.builder()
                .maxToolCalls(context.getMaxToolCalls())
                .maxDurationMs(context.getMaxDurationMs())
                .maxOutputChars(context.getMaxOutputChars())
                .build();
        return base.merge(override);
    }

    /**
     * 检查并获取配额。
     *
     * @param role     Agent 角色
     * @param quota    生效配额
     * @param tenantId 租户 ID
     * @return {@code null} 表示通过；否则返回失败结果
     */
    public AgentResult checkAndAcquire(Agent.Role role, AgentQuota quota, String tenantId) {
        Objects.requireNonNull(role, "role must not be null");
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }

        // 1. 并发数检查
        int currentConcurrent = concurrentCounter(role, tenantId).get();
        int maxConcurrent = quota.getMaxConcurrentExecutions() != null
                ? quota.getMaxConcurrentExecutions() : Integer.MAX_VALUE;
        if (currentConcurrent >= maxConcurrent) {
            log.warn("Quota exceeded: role={}, tenant={}, concurrent={}/{}",
                    role, tenantId, currentConcurrent, maxConcurrent);
            return AgentResult.failure(role, AgentResult.Status.QUOTA_EXCEEDED,
                    "CONCURRENT_LIMIT_EXCEEDED",
                    "Concurrent executions " + currentConcurrent + " >= limit " + maxConcurrent,
                    0L, tenantId, null);
        }

        // 2. 日调用检查
        int dailyLimit = quota.getDailyCallLimit() != null ? quota.getDailyCallLimit() : 0;
        if (dailyLimit > 0) {
            int todayCalls = dailyCounter(role, tenantId, LocalDate.now()).get();
            if (todayCalls >= dailyLimit) {
                log.warn("Quota exceeded: role={}, tenant={}, daily={}/{}",
                        role, tenantId, todayCalls, dailyLimit);
                return AgentResult.failure(role, AgentResult.Status.QUOTA_EXCEEDED,
                        "DAILY_LIMIT_EXCEEDED",
                        "Daily calls " + todayCalls + " >= limit " + dailyLimit,
                        0L, tenantId, null);
            }
        }

        // 3. 获取配额（计数+1）
        concurrentCounter(role, tenantId).incrementAndGet();
        dailyCounter(role, tenantId, LocalDate.now()).incrementAndGet();
        return null;
    }

    /**
     * 释放配额（并发计数-1）。
     *
     * <p>日调用计数不回退，仅并发计数回退。</p>
     *
     * @param role     Agent 角色
     * @param quota    生效配额（保留参数便于扩展）
     * @param tenantId 租户 ID
     */
    public void release(Agent.Role role, AgentQuota quota, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        AtomicInteger counter = concurrentCounter(role, tenantId);
        int newVal = counter.decrementAndGet();
        if (newVal < 0) {
            // 防御性：避免 release 多于 acquire 导致负数
            counter.set(0);
        }
    }

    /**
     * 获取当前并发数快照。
     *
     * @param role     Agent 角色
     * @param tenantId 租户 ID
     * @return 当前并发数
     */
    public int currentConcurrent(Agent.Role role, String tenantId) {
        return concurrentCounter(role, tenantId).get();
    }

    /**
     * 获取当日调用数快照。
     *
     * @param role     Agent 角色
     * @param tenantId 租户 ID
     * @return 当日调用数
     */
    public int currentDaily(Agent.Role role, String tenantId) {
        return dailyCounter(role, tenantId, LocalDate.now()).get();
    }

    /**
     * 获取角色默认配额。
     *
     * @param role Agent 角色
     * @return 默认配额；未配置返回兜底
     */
    public AgentQuota defaultQuotaOf(Agent.Role role) {
        return defaultQuotas.getOrDefault(role, fallbackQuota);
    }

    /**
     * 更新角色默认配额（运行时调整）。
     *
     * @param role  Agent 角色
     * @param quota 新配额
     */
    public void updateDefaultQuota(Agent.Role role, AgentQuota quota) {
        defaultQuotas.put(role, quota);
        log.info("Default quota updated for role {}: {}", role, quota);
    }

    /**
     * 重置某租户的并发计数（运维场景）。
     *
     * @param role     Agent 角色
     * @param tenantId 租户 ID
     */
    public void resetConcurrent(Agent.Role role, String tenantId) {
        concurrentCounters.getOrDefault(role, new ConcurrentHashMap<>()).remove(tenantId);
    }

    private AtomicInteger concurrentCounter(Agent.Role role, String tenantId) {
        return concurrentCounters
                .computeIfAbsent(role, r -> new ConcurrentHashMap<>())
                .computeIfAbsent(tenantId, t -> new AtomicInteger(0));
    }

    private AtomicInteger dailyCounter(Agent.Role role, String tenantId, LocalDate date) {
        return dailyCounters
                .computeIfAbsent(role, r -> new ConcurrentHashMap<>())
                .computeIfAbsent(tenantId, t -> new ConcurrentHashMap<>())
                .computeIfAbsent(date, d -> new AtomicInteger(0));
    }
}