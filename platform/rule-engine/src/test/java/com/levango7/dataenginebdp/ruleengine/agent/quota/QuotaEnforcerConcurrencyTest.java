package com.levango7.dataenginebdp.ruleengine.agent.quota;

import com.levango7.dataenginebdp.ruleengine.agent.core.Agent;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QuotaEnforcer 并发正确性回归（2026-10-12）。
 *
 * <p>背景：checkAndAcquire 原实现是"读计数 → 判限 → incrementAndGet"的 check-then-act，
 * 并发线程可同时读到 max-1 而双双通过（配额超发、多租户隔离承诺被突破）。
 * 修法=先占后查（原子增量 → 判超 → 回滚）。本测试用真实多线程 + 起跑闩锁复现竞争窗口。</p>
 */
class QuotaEnforcerConcurrencyTest {

    @Test
    @DisplayName("并发准入不得超发：16 线程抢 3 个名额，恰好 3 个通过")
    void concurrentAcquire_neverExceedsLimit() throws Exception {
        QuotaEnforcer enforcer = new QuotaEnforcer();
        AgentQuota quota = AgentQuota.builder()
                .maxConcurrentExecutions(3)
                .dailyCallLimit(0)   // 只验并发维度
                .build();
        final int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger passed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    fire.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                AgentResult r = enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t-concurrent");
                if (r == null) {
                    passed.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
                // 采样观察到的并发快照（准入结束后不许超过上限）
                maxObserved.accumulateAndGet(enforcer.currentConcurrent(Agent.Role.SQL, "t-concurrent"),
                        Math::max);
                done.countDown();
            });
            t.setDaemon(true);
            t.start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "线程未全部就绪");
        fire.countDown();   // 同时放行，最大化竞争
        assertTrue(done.await(10, TimeUnit.SECONDS), "线程未在期限内完成");

        assertEquals(3, passed.get(), "通过数应恰好等于并发上限（超发=check-then-act 竞态复发）");
        assertEquals(threads - 3, failed.get());
        assertTrue(maxObserved.get() <= 3, "并发计数快照不得越过上限，实测 " + maxObserved.get());
        assertEquals(3, enforcer.currentConcurrent(Agent.Role.SQL, "t-concurrent"));
    }

    @Test
    @DisplayName("回滚不污染计数：超限被拒后名额仍可被后续调用获取")
    void rejectedAcquire_rollsBackOwnIncrement() {
        QuotaEnforcer enforcer = new QuotaEnforcer();
        AgentQuota quota = AgentQuota.builder()
                .maxConcurrentExecutions(1)
                .dailyCallLimit(0)
                .build();

        assertNull(enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t-rollback"));
        // 超限：拒绝并回滚自己的增量
        AgentResult rejected = enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t-rollback");
        assertEquals("CONCURRENT_LIMIT_EXCEEDED", rejected.getErrorCode());
        assertEquals(1, enforcer.currentConcurrent(Agent.Role.SQL, "t-rollback"),
                "被拒调用不得把计数留在 2");

        // 释放后名额应可再次获取（若回滚有泄漏，这里会因为计数虚高而误拒）
        enforcer.release(Agent.Role.SQL, quota, "t-rollback");
        assertEquals(0, enforcer.currentConcurrent(Agent.Role.SQL, "t-rollback"));
        assertNull(enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t-rollback"));
    }

    @Test
    @DisplayName("日额度超限时并发额度一并回滚（本次未执行不应占用并发名额）")
    void dailyLimitExceeded_releasesConcurrentSlot() {
        QuotaEnforcer enforcer = new QuotaEnforcer();
        AgentQuota quota = AgentQuota.builder()
                .maxConcurrentExecutions(5)
                .dailyCallLimit(1)
                .build();

        assertNull(enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t-daily"));
        AgentResult rejected = enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t-daily");
        assertEquals("DAILY_LIMIT_EXCEEDED", rejected.getErrorCode());

        assertEquals(1, enforcer.currentConcurrent(Agent.Role.SQL, "t-daily"),
                "被日额度拒绝的调用不应占用并发名额");
        assertEquals(1, enforcer.currentDaily(Agent.Role.SQL, "t-daily"),
                "被拒调用不增加日计数");
    }
}
