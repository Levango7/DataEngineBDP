package com.levango7.dataenginebdp.governance.realtime.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 引擎"从来源刷新 + 降级"语义单测（台账 #6 阶段 3）。
 *
 * <p>契约：刷新成功 → 替换注册表；刷新失败 → **保留上次缓存**并标不健康；未启用 → no-op。
 */
class StreamingQualityRuleEngineRefreshTest {

    private static QualityRule rule(String id, String table) {
        return QualityRule.builder()
                .ruleId(id)
                .tenantId("tenant-a")
                .ruleType(QualityRule.RuleType.NOT_NULL)
                .ruleName("r-" + id)
                .tableIdentifier(table)
                .fieldName("f")
                .severity("WARN")
                .enabled(true)
                .build();
    }

    /** 可编程假来源。 */
    private static final class FakeSource implements QualityRuleSource {
        private final boolean enabled;
        private final List<QualityRule> rules;
        private final boolean fail;

        FakeSource(boolean enabled, List<QualityRule> rules, boolean fail) {
            this.enabled = enabled;
            this.rules = rules;
            this.fail = fail;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public List<QualityRule> fetchRules() {
            if (fail) {
                throw new IllegalStateException("rule-engine 不可达");
            }
            return rules;
        }
    }

    private StreamingQualityRuleEngine engine() {
        return new StreamingQualityRuleEngine(
                mock(QualityRuleEvaluator.class), mock(QualityAlertEmitter.class));
    }

    @Test
    void refreshReplacesRegistry() {
        StreamingQualityRuleEngine engine = engine();
        engine.registerRule(rule("old", "t0"));

        engine.refreshFromSource(new FakeSource(true, List.of(rule("1", "t1"), rule("2", "t2")), false));

        assertThat(engine.getRuleCount()).isEqualTo(2);
        assertThat(engine.getRuleRegistry()).containsKeys("1", "2").doesNotContainKey("old");
        assertThat(engine.isSourceHealthy()).isTrue();
        assertThat(engine.getLastRefreshAtMillis()).isPositive();
    }

    @Test
    void refreshFailureKeepsCacheAndMarksUnhealthy() {
        StreamingQualityRuleEngine engine = engine();
        engine.refreshFromSource(new FakeSource(true, List.of(rule("1", "t1")), false));
        assertThat(engine.getRuleCount()).isEqualTo(1);

        engine.refreshFromSource(new FakeSource(true, List.of(), true));

        assertThat(engine.getRuleCount()).isEqualTo(1); // 降级：缓存保留
        assertThat(engine.isSourceHealthy()).isFalse();
    }

    @Test
    void disabledSourceIsNoOp() {
        StreamingQualityRuleEngine engine = engine();
        engine.registerRule(rule("local", "t0"));

        engine.refreshFromSource(new FakeSource(false, List.of(rule("x", "tx")), false));

        assertThat(engine.getRuleCount()).isEqualTo(1);
        assertThat(engine.getRuleRegistry()).containsKey("local");
    }
}
