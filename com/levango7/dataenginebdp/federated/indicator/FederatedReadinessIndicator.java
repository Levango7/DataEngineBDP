package com.shuqing.bigdata.federated.indicator;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.common.health.indicator.ReadinessHealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 联邦查询服务 Actuator readiness 健康指示器。
 *
 * <p>注册到 Spring Boot Actuator {@code /actuator/health/readiness} 端点，
 * 对应 K8s readinessProbe 语义。当前实现为轻量探测：跨集群路由层
 * （GlobalCatalogClient/ClusterTransport）在离线构建下被排除，
 * 故仅返回 UP + 服务标识。待路由层在线后可注入路由依赖做真实连通性探测。</p>
 *
 * <p>bean 名 {@code federatedReadinessIndicator} 以 {@code readinessIndicator} 结尾，
 * Spring Boot 3.x 自动将其归入 readiness health group。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class FederatedReadinessIndicator extends ReadinessHealthIndicator {

    private static final String UNKNOWN_VERSION = "unknown";

    @Override
    protected String serviceName() {
        return "federated-query";
    }

    @Override
    protected HealthResponse probeReadiness() {
        // 跨集群路由层在离线构建下被排除，此处仅做轻量就绪探测。
        return HealthResponse.up(serviceName(), UNKNOWN_VERSION,
                Map.of("router", "offline-build-stub"));
    }
}