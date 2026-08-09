package com.levango7.dataenginebdp.flinkcdc.materializedview.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RefreshPolicy} 单元测试。
 *
 * @author shuqing-bigdata
 */
class RefreshPolicyTest {

    @Nested
    @DisplayName("Mode 枚举")
    class ModeTest {

        @Test
        @DisplayName("fromCode — 正确解析所有模式")
        void fromCode_allModes() {
            assertThat(RefreshPolicy.Mode.fromCode("scheduled")).isEqualTo(RefreshPolicy.Mode.SCHEDULED);
            assertThat(RefreshPolicy.Mode.fromCode("event-triggered")).isEqualTo(RefreshPolicy.Mode.EVENT_TRIGGERED);
            assertThat(RefreshPolicy.Mode.fromCode("manual")).isEqualTo(RefreshPolicy.Mode.MANUAL);
        }

        @Test
        @DisplayName("fromCode — 大小写不敏感")
        void fromCode_caseInsensitive() {
            assertThat(RefreshPolicy.Mode.fromCode("SCHEDULED")).isEqualTo(RefreshPolicy.Mode.SCHEDULED);
            assertThat(RefreshPolicy.Mode.fromCode("MANUAL")).isEqualTo(RefreshPolicy.Mode.MANUAL);
        }

        @Test
        @DisplayName("fromCode — 下划线转连字符")
        void fromCode_underscoreToHyphen() {
            assertThat(RefreshPolicy.Mode.fromCode("event_triggered")).isEqualTo(RefreshPolicy.Mode.EVENT_TRIGGERED);
        }

        @Test
        @DisplayName("fromCode — null 抛出 NPE")
        void fromCode_null_throwsNpe() {
            assertThatThrownBy(() -> RefreshPolicy.Mode.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("fromCode — 未知模式抛出异常")
        void fromCode_unknown_throws() {
            assertThatThrownBy(() -> RefreshPolicy.Mode.fromCode("realtime"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("工厂方法")
    class FactoryMethodTest {

        @Test
        @DisplayName("scheduled — 正确创建定时策略")
        void scheduled() {
            RefreshPolicy policy = RefreshPolicy.scheduled(Duration.ofMinutes(10));
            assertThat(policy.getMode()).isEqualTo(RefreshPolicy.Mode.SCHEDULED);
            assertThat(policy.getInterval()).isEqualTo(Duration.ofMinutes(10));
            assertThat(policy.isAutoRetry()).isTrue();
        }

        @Test
        @DisplayName("scheduled — 零或负周期抛出异常")
        void scheduled_invalidInterval() {
            assertThatThrownBy(() -> RefreshPolicy.scheduled(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RefreshPolicy.scheduled(Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("eventTriggered — 正确创建事件触发策略")
        void eventTriggered() {
            RefreshPolicy policy = RefreshPolicy.eventTriggered(50, Duration.ofSeconds(10));
            assertThat(policy.getMode()).isEqualTo(RefreshPolicy.Mode.EVENT_TRIGGERED);
            assertThat(policy.getBatchThreshold()).isEqualTo(50);
            assertThat(policy.getDebounceWindow()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("eventTriggered — 零或负阈值抛出异常")
        void eventTriggered_invalidThreshold() {
            assertThatThrownBy(() -> RefreshPolicy.eventTriggered(0, Duration.ofSeconds(10)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RefreshPolicy.eventTriggered(-1, Duration.ofSeconds(10)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("manual — 正确创建手动策略")
        void manual() {
            RefreshPolicy policy = RefreshPolicy.manual();
            assertThat(policy.getMode()).isEqualTo(RefreshPolicy.Mode.MANUAL);
            assertThat(policy.isAutoRetry()).isFalse();
            assertThat(policy.getMaxRetries()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("validate — 配置校验")
    class ValidateTest {

        @Test
        @DisplayName("SCHEDULED — 合法配置通过")
        void validate_scheduled_ok() {
            RefreshPolicy policy = RefreshPolicy.scheduled(Duration.ofMinutes(5));
            policy.validate();
        }

        @Test
        @DisplayName("SCHEDULED — 零周期抛出异常")
        void validate_scheduled_zeroInterval() {
            RefreshPolicy policy = RefreshPolicy.scheduled(Duration.ofMinutes(5));
            policy.setInterval(Duration.ZERO);
            assertThatThrownBy(policy::validate).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("EVENT_TRIGGERED — 合法配置通过")
        void validate_eventTriggered_ok() {
            RefreshPolicy policy = RefreshPolicy.eventTriggered(100, Duration.ofSeconds(30));
            policy.validate();
        }

        @Test
        @DisplayName("EVENT_TRIGGERED — 零阈值抛出异常")
        void validate_eventTriggered_zeroThreshold() {
            RefreshPolicy policy = RefreshPolicy.eventTriggered(100, Duration.ofSeconds(30));
            policy.setBatchThreshold(0);
            assertThatThrownBy(policy::validate).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("MANUAL — 通过校验")
        void validate_manual_ok() {
            RefreshPolicy.manual().validate();
        }
    }

    @Nested
    @DisplayName("equals / hashCode / toString")
    class ObjectMethodsTest {

        @Test
        @DisplayName("equals — 相同配置")
        void equals_same() {
            RefreshPolicy p1 = RefreshPolicy.scheduled(Duration.ofMinutes(5));
            RefreshPolicy p2 = RefreshPolicy.scheduled(Duration.ofMinutes(5));
            assertThat(p1).isEqualTo(p2);
            assertThat(p1.hashCode()).isEqualTo(p2.hashCode());
        }

        @Test
        @DisplayName("equals — 不同配置")
        void equals_different() {
            RefreshPolicy p1 = RefreshPolicy.scheduled(Duration.ofMinutes(5));
            RefreshPolicy p2 = RefreshPolicy.scheduled(Duration.ofMinutes(10));
            assertThat(p1).isNotEqualTo(p2);
        }

        @Test
        @DisplayName("toString — 包含 mode")
        void toString_containsMode() {
            assertThat(RefreshPolicy.manual().toString()).contains("MANUAL");
        }
    }
}