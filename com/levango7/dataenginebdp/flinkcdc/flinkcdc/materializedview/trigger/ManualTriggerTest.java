package com.shuqing.bigdata.flinkcdc.materializedview.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ManualTrigger} 单元测试。
 *
 * @author shuqing-bigdata
 */
class ManualTriggerTest {

    @Nested
    @DisplayName("生命周期")
    class LifecycleTest {

        @Test
        @DisplayName("start/stop — 正常切换状态")
        void startStop() {
            ManualTrigger trigger = new ManualTrigger();
            assertThat(trigger.isRunning()).isFalse();
            trigger.start();
            assertThat(trigger.isRunning()).isTrue();
            trigger.stop();
            assertThat(trigger.isRunning()).isFalse();
        }

        @Test
        @DisplayName("重复 start — 无效果")
        void start_idempotent() {
            ManualTrigger trigger = new ManualTrigger();
            trigger.start();
            trigger.start();
            assertThat(trigger.isRunning()).isTrue();
            trigger.stop();
        }
    }

    @Nested
    @DisplayName("trigger — 手动触发")
    class TriggerTest {

        @Test
        @DisplayName("正常触发 — 返回事件并回调处理器")
        void trigger_success() {
            ManualTrigger trigger = new ManualTrigger();
            AtomicInteger eventCount = new AtomicInteger(0);
            trigger.registerHandler(e -> eventCount.incrementAndGet());
            trigger.start();

            RefreshEvent event = trigger.trigger("mv_test", "admin");
            assertThat(event).isNotNull();
            assertThat(event.getViewName()).isEqualTo("mv_test");
            assertThat(event.getSource()).isEqualTo(RefreshEvent.Source.MANUAL);
            assertThat(event.getReason()).contains("admin");
            assertThat(eventCount.get()).isEqualTo(1);
            assertThat(trigger.getTriggerCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("未启动 — 返回 null")
        void trigger_notRunning() {
            ManualTrigger trigger = new ManualTrigger();
            trigger.registerHandler(e -> {});
            RefreshEvent event = trigger.trigger("mv", "admin");
            assertThat(event).isNull();
        }

        @Test
        @DisplayName("未注册处理器 — 返回 null")
        void trigger_noHandler() {
            ManualTrigger trigger = new ManualTrigger();
            trigger.start();
            RefreshEvent event = trigger.trigger("mv", "admin");
            assertThat(event).isNull();
        }

        @Test
        @DisplayName("null viewName — 抛出 NPE")
        void trigger_nullName() {
            ManualTrigger trigger = new ManualTrigger();
            trigger.registerHandler(e -> {});
            trigger.start();
            assertThatThrownBy(() -> trigger.trigger(null, "admin"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null operator — 使用 unknown")
        void trigger_nullOperator() {
            ManualTrigger trigger = new ManualTrigger();
            trigger.registerHandler(e -> {});
            trigger.start();
            RefreshEvent event = trigger.trigger("mv", null);
            assertThat(event).isNotNull();
            assertThat(event.getReason()).contains("unknown");
        }

        @Test
        @DisplayName("多次触发 — 计数器累加")
        void trigger_multiple() {
            ManualTrigger trigger = new ManualTrigger();
            trigger.registerHandler(e -> {});
            trigger.start();
            trigger.trigger("mv", "a");
            trigger.trigger("mv", "b");
            trigger.trigger("mv", "c");
            assertThat(trigger.getTriggerCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("处理器抛异常 — 返回 null 不影响计数")
        void trigger_handlerException() {
            ManualTrigger trigger = new ManualTrigger();
            trigger.registerHandler(e -> { throw new RuntimeException("test"); });
            trigger.start();
            RefreshEvent event = trigger.trigger("mv", "admin");
            assertThat(event).isNull();
            assertThat(trigger.getTriggerCount()).isEqualTo(1);
        }
    }
}