package com.shuqing.bigdata.infra.orchestrator.indicator;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.common.health.indicator.ReadinessHealthIndicator;
import com.shuqing.bigdata.infra.orchestrator.model.EnvironmentType;
import com.shuqing.bigdata.infra.orchestrator.registry.ProviderRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排层 Actuator readiness 健康指示器。
 *
 * <p>注册到 Spring Boot Actuator {@code /actuator/health/readiness} 端点，
 * 对应 K8s readinessProbe 语义。通过 {@link ProviderRegistry#missingEnvironments()}
 * 探测 Provider 注册表覆盖度：全部环境均已注册返回 UP；存在缺失返回 DEGRADED。</p>
 *
 * <p>bean 名 {@code infraOrchestratorReadinessIndicator} 以 {@code readinessIndicator} 结尾，
 * Spring Boot 3.x 自动将其归入 readiness health group。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class InfraOrchestratorReadinessIndicator extends ReadinessHealthIndicator {

    private static final String UNKNOWN_VERSION = "unknown";
    private static final String LAYER = "L0.5";

    private final ProviderRegistry registry;

    /**
     * 构造指示器。
     *
     * @param registry Provider 注册表，用于探测就绪状态
     */
    public InfraOrchestratorReadinessIndicator(ProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected String serviceName() {
        return "infra-orchestrator";
    }

    @Override
    protected HealthResponse probeReadiness() {
        int total = EnvironmentType.values().length;
        int registered = registry.size();
        List<EnvironmentType> missing = registry.missingEnvironments();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("layer", LAYER);
        details.put("totalEnvironments", total);
        details.put("registeredProviders", registered);
        details.put("missingEnvironments", missing);
        details.put("registeredEnvironments", registry.registeredEnvironments());

        if (missing.isEmpty()) {
            return HealthResponse.up(serviceName(), UNKNOWN_VERSION, details);
        }
        return HealthResponse.degraded(serviceName(), UNKNOWN_VERSION, details);
    }
}