package com.shuqing.bigdata.encaps.indicator;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.common.health.indicator.ReadinessHealthIndicator;
import com.shuqing.bigdata.encaps.repository.TenantRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 封装层 Actuator readiness 健康指示器。
 *
 * <p>注册到 Spring Boot Actuator {@code /actuator/health/readiness} 端点，
 * 对应 K8s readinessProbe 语义。通过 {@link TenantRepository#count()} 探测
 * 租户数据库连通性，异常时返回 DOWN 供 K8s 摘除流量。</p>
 *
 * <p>bean 名 {@code encapsReadinessIndicator} 以 {@code readinessIndicator} 结尾，
 * Spring Boot 3.x 自动将其归入 readiness health group。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class EncapsReadinessIndicator extends ReadinessHealthIndicator {

    private static final String UNKNOWN_VERSION = "unknown";

    private final TenantRepository tenantRepository;

    /**
     * 构造指示器。
     *
     * @param tenantRepository 租户仓储，用于探测数据库连通性
     */
    public EncapsReadinessIndicator(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    protected String serviceName() {
        return "encaps-layer";
    }

    @Override
    protected HealthResponse probeReadiness() {
        try {
            long tenantCount = tenantRepository.count();
            return HealthResponse.up(serviceName(), UNKNOWN_VERSION,
                    Map.of("tenantCount", tenantCount));
        } catch (Exception ex) {
            return HealthResponse.down(serviceName(), UNKNOWN_VERSION,
                    Map.of("error", ex.getClass().getSimpleName(),
                            "message", String.valueOf(ex.getMessage())));
        }
    }
}