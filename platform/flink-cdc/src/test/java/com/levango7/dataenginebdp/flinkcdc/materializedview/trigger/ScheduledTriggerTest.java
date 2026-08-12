package com.levango7.dataenginebdp.flinkcdc.materializedview.trigger;

import com.levango7.dataenginebdp.flinkcdc.materializedview.model.AggregationType;
import com.levango7.dataenginebdp.flinkcdc.materializedview.model.MaterializedViewDef;
import com.levango7.dataenginebdp.flinkcdc.materializedview.model.RefreshPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ScheduledTrigger} 单元测试。
 *
 * @author shuqing-bigdata
 */
class ScheduledTriggerTest {

    private MaterializedViewDef scheduledView(String name, Duration interval) {
        return MaterializedViewDef.builder()
                .name(name).database("d").targetTable("t_" + name)
                .addSourceTable("shop.orders").addDimension("region")
                .addMetric("total", AggregationType.SUM)
                .refreshPolicy(RefreshPolicy.scheduled(interval))
                .build();
    }

    @Nested
    @DisplayName("初始化")
    class InitTest {

        @Test
        @DisplayName("仅注册 SCHEDULED 模式视图")
        void init_onlyScheduled() {
            MaterializedViewDef schedView = scheduledView("mv1", Duration.ofMinutes(5));
            MaterializedViewDef eventView = MaterializedViewDef.builder()
                    .name("mv2").database("d").targetTable("t")
                    .addSourceTable("s.t").addDimension("dim")
                    .addMetric("m", AggregationType.COUNT)
                    .refreshPolicy(RefreshPolicy.eventTriggered(10, Duration.ofSeconds(1)))
                    .build();
            ScheduledTrigger trigger = new ScheduledTrigger(List.of(schedView, eventView));
            assertThat(trigger.getScheduledViewNames()).containsExactly("mv1");
        }

        @Test
        @DisplayName("禁用的视图不注册")
        void init_disabled() {
            MaterializedViewDef view = scheduledView("mv", Duration.ofMinutes(5));
            view.setEnabled(false);
            ScheduledTrigger trigger = new ScheduledTrigger(List.of(view));
            assertThat(trigger.getScheduledViewNames()).isEmpty();
        }

        @Test
        @DisplayName("getInterval — 返回注册的周期")
        void getInterval() {
            ScheduledTrigger trigger = new ScheduledTrigger(
                    List.of(scheduledView("mv", Duration.ofMinutes(10))));
            assertThat(trigger.getInterval("mv")).isEqualTo(Duration.ofMinutes(10));
            assertThat(trigger.getInterval("unknown")).isNull();
        }
    }

    @Nested
    @DisplayName("生命周期")
    class LifecycleTest {

        @Test
        @DisplayName("start/stop — 正常切换状态")
        void startStop() {
            ScheduledTrigger trigger = new ScheduledTrigger(
                    List.of(scheduledView("mv", Duration.ofMinutes(5))));
            assertThat(trigger.isRunning()).isFalse();
            trigger.start();
            assertThat(trigger.isRunning()).isTrue();
            trigger.stop();
            assertThat(trigger.isRunning()).isFalse();
        }

        @Test
        @DisplayName("无 SCHEDULED 视图 — start 仍成功")
        void start_empty() {
            ScheduledTrigger trigger = new ScheduledTrigger(List.of());
            trigger.start();
            assertThat(trigger.isRunning()).isTrue();
            trigger.stop();
        }

        @Test
        @DisplayName("重复 start — 无效果")
        void start_idempotent() {
            ScheduledTrigger trigger = new ScheduledTrigger(
                    List.of(scheduledView("mv", Duration.ofMinutes(5))));
            trigger.start();
            trigger.start();
            assertThat(trigger.isRunning()).isTrue();
            trigger.stop();
        }
    }

    @Nested
    @DisplayName("triggerNow — 手动触发定时任务")
    class TriggerNowTest {

        @Test
        @DisplayName("已注册视图 — 触发成功")
        void triggerNow_registered() {
            ScheduledTrigger trigger = new ScheduledTrigger(
                    List.of(scheduledView("mv", Duration.ofMinutes(5))));
            AtomicInteger eventCount = new AtomicInteger(0);
            trigger.registerHandler(e -> eventCount.incrementAndGet());
            trigger.start();
            trigger.triggerNow("mv");
            assertThat(eventCount.get()).isEqualTo(1);
            trigger.stop();
        }

        @Test
        @DisplayName("未注册视图 — 抛出异常")
        void triggerNow_unknown() {
            ScheduledTrigger trigger = new ScheduledTrigger(List.of());
            trigger.start();
            assertThatThrownBy(() -> trigger.triggerNow("unknown"))
                    .isInstanceOf(IllegalArgumentException.class);
            trigger.stop();
        }
    }
}