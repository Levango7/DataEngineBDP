package com.shuqing.bigdata.common.health.indicator;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Actuator liveness 健康指示器基类。
 *
 * <p>供 Spring Boot Actuator {@code /actuator/health/liveness} 端点使用，
 * 对应 K8s livenessProbe 语义。仅检查进程存活，<strong>不查外部依赖</strong>，
 * 始终快速返回 UP，避免因外部依赖抖动触发级联容器重启。</p>
 *
 * <p>使用方式：子类注册为 Spring Bean 并实现 {@link #serviceName()} 即可。</p>
 *
 * <p>子类示例：</p>
 * <pre>{@code
 * @Component
 * public class LineageLivenessIndicator extends LivenessHealthIndicator {
 *     @Override
 *     protected String serviceName() { return "lineage-analyzer"; }
 * }
 * }</pre>
 *
 * <p>Actuator 配置（由 {@link com.shuqing.bigdata.common.health.config.HealthModuleConfig} 自动配置）：</p>
 * <pre>{@code
 * management.endpoint.health.probes.enabled=true
 * management.endpoints.web.exposure.include=health,info
 * }</pre>
 *
 * <p>Spring Boot 3.x Actuator 会自动将实现 {@link HealthIndicator} 且 bean 名以
 * {@code "livenessIndicator"} 结尾的 bean 归入 liveness health group，
 * 暴露在 {@code /actuator/health/liveness}。</p>
 *
 * @author shuqing-bigdata
 */
public abstract class LivenessHealthIndicator implements HealthIndicator {

    /**
     * 返回模块服务名，写入 Health detail。
     *
     * @return 服务名
     */
    protected abstract String serviceName();

    /**
     * Actuator liveness 健康检查实现。
     *
     * <p>始终返回 UP，不查外部依赖。服务名写入 detail 便于运维大盘识别。</p>
     *
     * @return UP 状态的 Health
     */
    @Override
    public final Health health() {
        return Health.up()
                .withDetail("service", serviceName())
                .build();
    }
}