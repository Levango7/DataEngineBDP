package com.shuqing.bigdata.function.indicator;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.common.health.indicator.ReadinessHealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 函数服务 Actuator readiness 健康指示器。
 *
 * <p>注册到 Spring Boot Actuator {@code /actuator/health/readiness} 端点，
 * 对应 K8s readinessProbe 语义。函数服务为 Knative Serverless 运行时，
 * 无外部持久化依赖，故仅返回 UP + 服务标识。</p>
 *
 * <p>bean 名 {@code functionReadinessIndicator} 以 {@code readinessIndicator} 结尾，
 * Spring Boot 3.x 自动将其归入 readiness health group。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class FunctionReadinessIndicator extends ReadinessHealthIndicator {

    private static final String UNKNOWN_VERSION = "unknown";

    @Override
    protected String serviceName() {
        return "function-service";
    }

    @Override
    protected HealthResponse probeReadiness() {
        // 函数服务为 Serverless 运行时，无外部持久化依赖，此处仅做轻量就绪探测。
        return HealthResponse.up(serviceName(), UNKNOWN_VERSION,
                Map.of("runtime", "knative-serverless"));
    }
}