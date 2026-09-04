package com.levango7.dataenginebdp.common.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RateLimitRegistry 单元测试：规则匹配与桶生命周期。
 */
class RateLimitRegistryTest {

    private static final List<RateLimitRule> RULES = List.of(
            new RateLimitRule("/api/v1/auth", 5, 1.0),
            new RateLimitRule("/api/v1/dev-ml", 100, 1.0),
            new RateLimitRule("/api/", 600, 1.0)
    );

    @Test
    @DisplayName("规则匹配：登录前缀最严优先，通用 /api/ 兜底，非 API 路径不限")
    void ruleMatching() {
        RateLimitRegistry registry = new RateLimitRegistry(RULES, 600, 60);

        assertThat(registry.matchRule("/api/v1/auth/login")).isNotNull();
        assertThat(registry.matchRule("/api/v1/auth/login").ratePerMinute()).isEqualTo(5);

        assertThat(registry.matchRule("/api/v1/dev-ml/jobs")).isNotNull();
        assertThat(registry.matchRule("/api/v1/dev-ml/jobs").ratePerMinute()).isEqualTo(100);

        assertThat(registry.matchRule("/api/v1/tenants").ratePerMinute()).isEqualTo(600);

        assertThat(registry.matchRule("/actuator/health")).isNull();
        assertThat(registry.matchRule(null)).isNull();
    }

    @Test
    @DisplayName("维度隔离：同规则下不同租户互不影响配额")
    void tenantIsolation() {
        RateLimitRegistry registry = new RateLimitRegistry(
                List.of(new RateLimitRule("/api/v1/auth", 2, 1.0)), 600, 60);
        RateLimitRule rule = registry.matchRule("/api/v1/auth/login");

        // 租户 A 打满 2 个
        assertThat(registry.tryAcquire(rule, "tenant:A")).isTrue();
        assertThat(registry.tryAcquire(rule, "tenant:A")).isTrue();
        assertThat(registry.tryAcquire(rule, "tenant:A")).isFalse();

        // 租户 B 配额独立
        assertThat(registry.tryAcquire(rule, "tenant:B")).isTrue();
    }

    @Test
    @DisplayName("同一维度不同规则使用独立桶（前缀隔离）")
    void ruleIsolation() {
        RateLimitRegistry registry = new RateLimitRegistry(RULES, 600, 60);
        RateLimitRule authRule = registry.matchRule("/api/v1/auth/login");
        RateLimitRule apiRule = registry.matchRule("/api/v1/tenants");

        // auth 规则容量 5：打满 5 次
        for (int i = 0; i < 5; i++) {
            assertThat(registry.tryAcquire(authRule, "tenant:A")).as("auth 第 %d 次", i + 1).isTrue();
        }
        assertThat(registry.tryAcquire(authRule, "tenant:A")).as("auth 超容量应拒绝").isFalse();
        // 不影响它在通用 API 规则下的独立配额
        assertThat(registry.tryAcquire(apiRule, "tenant:A")).as("api 规则桶独立").isTrue();
    }

    @Test
    @DisplayName("桶清理：闲置桶被回收，活跃桶保留")
    void idleEviction() {
        // 闲置回收 1 秒，清扫间隔 0（每次访问都触发清扫检查）
        RateLimitRegistry registry = new RateLimitRegistry(
                List.of(new RateLimitRule("/api/", 100, 1.0)), 1, 0);
        RateLimitRule rule = registry.matchRule("/api/v1/x");

        registry.tryAcquire(rule, "tenant:stale");
        registry.tryAcquire(rule, "tenant:active");
        int before = registry.bucketCount();
        assertThat(before).isEqualTo(2);

        // 模拟 stale 桶闲置超 1 秒：等真实时间过去
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // active 桶持续访问保活，stale 桶闲置应被回收
        registry.tryAcquire(rule, "tenant:active");

        assertThat(registry.bucketCount())
                .as("闲置桶应被回收，活跃桶保留")
                .isEqualTo(1);
    }
}
