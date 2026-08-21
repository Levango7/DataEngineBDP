package com.shuqing.bigdata.finops.indicator;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.common.health.indicator.ReadinessHealthIndicator;
import com.shuqing.bigdata.finops.collector.PrometheusQueryClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成本模型服务 Actuator readiness 健康指示器。
 *
 * <p>注册到 Spring Boot Actuator {@code /actuator/health/readiness} 端点，
 * 对应 K8s readinessProbe 语义。通过 {@link PrometheusQueryClient#isAvailable()}
 * 探测 Prometheus 连通性，不可用时返回 DOWN 供 K8s 摘除流量。</p>
 *
 * <p>bean 名 {@code costModelReadinessIndicator} 以 {@code readinessIndicator} 结尾，
 * Spring Boot 3.x 自动将其归入 readiness health group。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class CostModelReadinessIndicator extends ReadinessHealthIndicator {

    private static final String UNKNOWN_VERSION = "unknown";

    private final PrometheusQueryClient prometheusQueryClient;

    /**
     * 构造指示器。
     *
     * @param prometheusQueryClient Prometheus 查询客户端，用于探测连通性
     */
    public CostModelReadinessIndicator(PrometheusQueryClient prometheusQueryClient) {
        this.prometheusQueryClient = prometheusQueryClient;
    }

    @Override
    protected String serviceName() {
        return "cost-model";
    }

    @Override
    protected HealthResponse probeReadiness() {
        boolean available = prometheusQueryClient.isAvailable();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("prometheusUrl", prometheusQueryClient.getPrometheusUrl());
        if (!available) {
            details.put("prometheus", "unavailable");
            return HealthResponse.down(serviceName(), UNKNOWN_VERSION, details);
        }
        return HealthResponse.up(serviceName(), UNKNOWN_VERSION, details);
    }
}