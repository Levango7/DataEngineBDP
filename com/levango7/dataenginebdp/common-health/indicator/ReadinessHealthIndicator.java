package com.shuqing.bigdata.common.health.indicator;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;


/**
 * Actuator readiness 健康指示器基类。
 *
 * <p>供 Spring Boot Actuator {@code /actuator/health/readiness} 端点使用，
 * 对应 K8s readinessProbe 语义。子类注入模块关键依赖（DB / 缓存 / 下游服务）
 * 并实现 {@link #probeReadiness()} 进行真实探测。</p>
 *
 * <p>与 {@link com.shuqing.bigdata.common.health.controller.AbstractHealthController#readiness()}
 * 的区别：本类供 Actuator 标准端点使用，返回 Actuator {@link Health} 结构；
 * AbstractHealthController 供业务自定义端点使用，返回 {@link HealthResponse} 结构。
 * 两者共享同一 {@link HealthResponse} 探测结果，由 {@link #toHealth(HealthResponse)} 转换。</p>
 *
 * <p>子类示例：</p>
 * <pre>{@code
 * @Component
 * public class LineageReadinessIndicator extends ReadinessHealthIndicator {
 *     private final LineageGraphWriter graphWriter;
 *
 *     public LineageReadinessIndicator(LineageGraphWriter graphWriter) {
 *         this.graphWriter = graphWriter;
 *     }
 *
 *     @Override
 *     protected String serviceName() { return "lineage-analyzer"; }
 *
 *     @Override
 *     protected HealthResponse probeReadiness() {
 *         int tables = graphWriter.getKnownTables().size();
 *         return HealthResponse.up(serviceName(), "unknown",
 *                 Map.of("knownTables", tables));
 *     }
 * }
 * }</pre>
 *
 * <p>Spring Boot 3.x Actuator 会自动将实现 {@link HealthIndicator} 且 bean 名以
 * {@code "readinessIndicator"} 结尾的 bean 归入 readiness health group，
 * 暴露在 {@code /actuator/health/readiness}。</p>
 *
 * @author shuqing-bigdata
 */
public abstract class ReadinessHealthIndicator implements HealthIndicator {

    /**
     * 返回模块服务名，写入 Health detail。
     *
     * @return 服务名
     */
    protected abstract String serviceName();

    /**
     * 探测模块关键依赖的就绪状态。
     *
     * <p>子类在此方法中检查 DB 连通性、缓存可用性、下游服务可达性等。
     * 方法应快速返回（建议超时 1-2s），避免阻塞 K8s 探针。</p>
     *
     * @return 就绪探测结果
     */
    protected abstract HealthResponse probeReadiness();

    /**
     * Actuator readiness 健康检查实现。
     *
     * <p>调用 {@link #probeReadiness()} 获取探测结果，转换为 Actuator {@link Health}：</p>
     * <ul>
     *   <li>UP -> {@link Health#up()}</li>
     *   <li>DEGRADED -> {@link Health#up()} 并写入 {@code status=DEGRADED} detail（Actuator 无 DEGRADED 状态，用 UP + detail 表达降级）</li>
     *   <li>DOWN -> {@link Health#down()}</li>
     * </ul>
     *
     * <p>探测异常时捕获并返回 DOWN，避免异常冒泡导致 Actuator 端点 500。</p>
     *
     * @return Actuator Health
     */
    @Override
    public final Health health() {
        HealthResponse response;
        try {
            response = probeReadiness();
        } catch (Exception ex) {
            return Health.down()
                    .withDetail("service", serviceName())
                    .withDetail("error", ex.getClass().getSimpleName())
                    .withDetail("message", String.valueOf(ex.getMessage()))
                    .build();
        }
        return toHealth(response);
    }

    /**
     * 将 {@link HealthResponse} 转换为 Actuator {@link Health}。
     *
     * <p>转换规则：</p>
     * <ul>
     *   <li>{@link HealthResponse.Status#UP} -> {@link Health#up()}</li>
     *   <li>{@link HealthResponse.Status#DEGRADED} -> {@link Health#up()} + detail {@code degraded=true}</li>
     *   <li>{@link HealthResponse.Status#DOWN} -> {@link Health#down()}</li>
     * </ul>
     *
     * <p>所有 details 透传到 Health detail，service 名始终写入。</p>
     *
     * @param response 探测结果
     * @return Actuator Health
     */
    protected static Health toHealth(HealthResponse response) {
        Health.Builder builder = switch (response.getStatus()) {
            case UP -> Health.up();
            case DEGRADED -> Health.up().withDetail("degraded", true);
            case DOWN -> Health.down();
        };
        builder.withDetail("service", response.getService());
        if (response.getVersion() != null) {
            builder.withDetail("version", response.getVersion());
        }
        response.getDetails().forEach(builder::withDetail);
        return builder.build();
    }
}