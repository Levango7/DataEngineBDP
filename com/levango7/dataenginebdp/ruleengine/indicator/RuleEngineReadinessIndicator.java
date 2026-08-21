package com.shuqing.bigdata.ruleengine.indicator;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.common.health.indicator.ReadinessHealthIndicator;
import com.shuqing.bigdata.ruleengine.repository.RuleRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 规则引擎 Actuator readiness 健康指示器。
 *
 * <p>注册到 Spring Boot Actuator {@code /actuator/health/readiness} 端点，
 * 对应 K8s readinessProbe 语义。通过 {@link RuleRepository#count()} 探测
 * 规则存储连通性与已加载规则数量，异常时返回 DOWN 供 K8s 摘除流量。</p>
 *
 * <p>bean 名 {@code ruleEngineReadinessIndicator} 以 {@code readinessIndicator} 结尾，
 * Spring Boot 3.x 自动将其归入 readiness health group。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class RuleEngineReadinessIndicator extends ReadinessHealthIndicator {

    private static final String UNKNOWN_VERSION = "unknown";

    private final RuleRepository ruleRepository;

    /**
     * 构造指示器。
     *
     * @param ruleRepository 规则仓储，用于探测规则存储连通性
     */
    public RuleEngineReadinessIndicator(RuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Override
    protected String serviceName() {
        return "rule-engine";
    }

    @Override
    protected HealthResponse probeReadiness() {
        try {
            long ruleCount = ruleRepository.count();
            return HealthResponse.up(serviceName(), UNKNOWN_VERSION,
                    Map.of("ruleCount", ruleCount));
        } catch (Exception ex) {
            return HealthResponse.down(serviceName(), UNKNOWN_VERSION,
                    Map.of("error", ex.getClass().getSimpleName(),
                            "message", String.valueOf(ex.getMessage())));
        }
    }
}