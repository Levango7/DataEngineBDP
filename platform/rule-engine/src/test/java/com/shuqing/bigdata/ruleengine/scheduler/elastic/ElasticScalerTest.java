package com.shuqing.bigdata.ruleengine.scheduler.elastic;

import com.shuqing.bigdata.ruleengine.scheduler.config.SchedulerProperties;
import com.shuqing.bigdata.ruleengine.scheduler.priority.PriorityTaskQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ElasticScaler 单元测试。
 *
 * <p>mock {@link WorkerPool} 与 {@link LoadMonitor}，纯粹验证扩缩决策逻辑，
 * 避免真实 worker 线程并发消费队列导致评估时负载已变化。</p>
 *
 * <p>直接调用包可见的 {@link ElasticScaler#evaluate()} 验证决策，
 * 不启动定时循环。</p>
 */
class ElasticScalerTest {

    private ElasticScaler scaler;

    @AfterEach
    void tearDown() {
        if (scaler != null) {
            scaler.stop();
        }
    }

    private SchedulerProperties.Elastic elasticConfig(boolean enabled, double up, double down, long cooldown) {
        SchedulerProperties.Elastic c = new SchedulerProperties.Elastic();
        c.setEnabled(enabled);
        c.setScaleUpThreshold(up);
        c.setScaleDownThreshold(down);
        c.setCooldownMs(cooldown);
        c.setEvalIntervalMs(60000L);
        return c;
    }

    @Test
    @DisplayName("evaluate — 高负载触发扩容 scaleTo(current+1)")
    void evaluate_highLoad_scalesUp() {
        WorkerPool pool = mock(WorkerPool.class);
        LoadMonitor monitor = mock(LoadMonitor.class);
        when(pool.size()).thenReturn(1);
        when(pool.getMaxSize()).thenReturn(8);
        when(pool.getMinSize()).thenReturn(1);
        when(monitor.avgLoad()).thenReturn(5.0);
        when(monitor.queueSize()).thenReturn(5);
        when(monitor.activeTaskCount()).thenReturn(0);
        when(pool.scaleTo(2)).thenReturn(2);

        scaler = new ElasticScaler(pool, monitor, elasticConfig(true, 2.0, 0.5, 1000L));
        scaler.evaluate();

        verify(pool).scaleTo(2);
    }

    @Test
    @DisplayName("evaluate — 低负载触发缩容 scaleTo(current-1)")
    void evaluate_lowLoad_scalesDown() {
        WorkerPool pool = mock(WorkerPool.class);
        LoadMonitor monitor = mock(LoadMonitor.class);
        when(pool.size()).thenReturn(4);
        when(pool.getMaxSize()).thenReturn(8);
        when(pool.getMinSize()).thenReturn(1);
        when(monitor.avgLoad()).thenReturn(0.0);
        when(pool.scaleTo(3)).thenReturn(3);

        scaler = new ElasticScaler(pool, monitor, elasticConfig(true, 2.0, 0.5, 0L));
        scaler.evaluate();

        verify(pool).scaleTo(3);
    }

    @Test
    @DisplayName("evaluate — 冷却期内不缩容")
    void evaluate_cooldownBlocksScaleDown() {
        WorkerPool pool = mock(WorkerPool.class);
        LoadMonitor monitor = mock(LoadMonitor.class);
        when(pool.size()).thenReturn(2);
        when(pool.getMaxSize()).thenReturn(8);
        when(pool.getMinSize()).thenReturn(1);
        // 第一次高负载触发扩容
        when(monitor.avgLoad()).thenReturn(5.0);
        when(pool.scaleTo(3)).thenReturn(3);

        scaler = new ElasticScaler(pool, monitor, elasticConfig(true, 2.0, 0.5, 100000L));
        scaler.evaluate();
        verify(pool).scaleTo(3); // 扩容发生

        // 之后低负载，冷却期内不应缩容
        when(monitor.avgLoad()).thenReturn(0.0);
        scaler.evaluate();
        verify(pool, never()).scaleTo(1); // 未缩容
    }

    @Test
    @DisplayName("evaluate — 已达 maxSize 不扩容")
    void evaluate_atMax_noScaleUp() {
        WorkerPool pool = mock(WorkerPool.class);
        LoadMonitor monitor = mock(LoadMonitor.class);
        when(pool.size()).thenReturn(2);
        when(pool.getMaxSize()).thenReturn(2);
        when(pool.getMinSize()).thenReturn(1);
        when(monitor.avgLoad()).thenReturn(10.0);

        scaler = new ElasticScaler(pool, monitor, elasticConfig(true, 2.0, 0.5, 1000L));
        scaler.evaluate();

        verify(pool, never()).scaleTo(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("evaluate — 已达 minSize 不缩容")
    void evaluate_atMin_noScaleDown() {
        WorkerPool pool = mock(WorkerPool.class);
        LoadMonitor monitor = mock(LoadMonitor.class);
        when(pool.size()).thenReturn(1);
        when(pool.getMaxSize()).thenReturn(8);
        when(pool.getMinSize()).thenReturn(1);
        when(monitor.avgLoad()).thenReturn(0.0);

        scaler = new ElasticScaler(pool, monitor, elasticConfig(true, 2.0, 0.5, 0L));
        scaler.evaluate();

        verify(pool, never()).scaleTo(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("evaluate — 负载在 [scaleDown, scaleUp] 之间不调整")
    void evaluate_midLoad_noAction() {
        WorkerPool pool = mock(WorkerPool.class);
        LoadMonitor monitor = mock(LoadMonitor.class);
        when(pool.size()).thenReturn(2);
        when(pool.getMaxSize()).thenReturn(8);
        when(pool.getMinSize()).thenReturn(1);
        when(monitor.avgLoad()).thenReturn(1.0); // 0.5 < 1.0 < 2.0

        scaler = new ElasticScaler(pool, monitor, elasticConfig(true, 2.0, 0.5, 0L));
        scaler.evaluate();

        verify(pool, never()).scaleTo(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("start — enabled=false 不启动")
    void start_disabled() {
        WorkerPool pool = mock(WorkerPool.class);
        LoadMonitor monitor = mock(LoadMonitor.class);
        scaler = new ElasticScaler(pool, monitor, elasticConfig(false, 2.0, 0.5, 1000L));
        scaler.start();
        assertThat(scaler.isRunning()).isFalse();
    }

    @Test
    @DisplayName("start — enabled=true 启动成功")
    void start_enabled() {
        WorkerPool pool = mock(WorkerPool.class);
        LoadMonitor monitor = mock(LoadMonitor.class);
        scaler = new ElasticScaler(pool, monitor, elasticConfig(true, 2.0, 0.5, 1000L));
        scaler.start();
        assertThat(scaler.isRunning()).isTrue();
    }
}
