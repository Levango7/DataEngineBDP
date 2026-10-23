package com.levango7.dataenginebdp.governance.realtime.quality;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RuleEngineRuleSource} 的默认配置契约（台账 #6 阶段 3 收口）。
 *
 * <p>阶段 3 的实质是"rule-engine 为质量规则唯一来源"。此前的接线默认
 * {@code source-enabled=false}，即链路建好但总开关关着 —— 债务因此长期不闭合。
 * 本测试锁定"默认开"，防止日后被无意改回关闭状态。
 *
 * <p>为什么不启 Spring 上下文：本模块测试均为纯单测，且 {@code @Value} 默认值
 * 就是契约本身；反射读取注解即可验证，无需承担上下文启动开销。
 */
class RuleEngineRuleSourceDefaultsTest {

    /** 取指定属性名的 {@code @Value} 占位符原文；按注解内容匹配，不依赖参数名（免 -parameters）。 */
    private static String valueExpression(String propertyName) {
        Constructor<?> ctor = RuleEngineRuleSource.class.getDeclaredConstructors()[0];
        for (var p : ctor.getParameters()) {
            Value v = p.getAnnotation(Value.class);
            if (v != null && v.value().contains(propertyName)) {
                return v.value();
            }
        }
        throw new AssertionError("未找到属性 " + propertyName + " 的 @Value 声明");
    }

    @Test
    void sourceEnabledDefaultsToTrue() {
        String expr = valueExpression("governance.quality.source-enabled");

        assertThat(expr)
                .as("source-enabled 默认值必须为 true：rule-engine 是质量规则唯一来源")
                .isEqualTo("${governance.quality.source-enabled:true}");
        assertThat(expr)
                .as("默认值不得回退为 false（那会静默关闭单一来源）")
                .doesNotContain(":false}");
    }

    @Test
    void ruleEngineUrlStillOverridable() {
        assertThat(valueExpression("governance.rule-engine.url"))
                .isEqualTo("${governance.rule-engine.url:http://rule-engine:8083}");
    }

    @Test
    void serviceTenantStillOverridable() {
        assertThat(valueExpression("governance.quality.service-tenant"))
                .isEqualTo("${governance.quality.service-tenant:platform-admin}");
    }
}