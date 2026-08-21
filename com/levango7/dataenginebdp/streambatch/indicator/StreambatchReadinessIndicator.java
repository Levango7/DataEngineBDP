package com.shuqing.bigdata.streambatch.indicator;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.common.health.indicator.ReadinessHealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 流批一体调度服务 Actuator readiness 健康指示器。
 *
 * <p>注册到 Spring Boot Actuator {@code /actuator/health/readiness} 端点，
 * 对应 K8s readinessProbe 语义。当前实现为轻量探测：Flink/Spark/Iceberg
 * 客户端依赖在离线构建下被注释（运行时由集群提供类路径），故仅返回 UP + 服务标识。
 * 待集群客户端在线后可注入提交器依赖做真实连通性探测。</p>
 *
 * <p>bean 名 {@code streambatchReadinessIndicator} 以 {@code readinessIndicator} 结尾，
 * Spring Boot 3.x 自动将其归入 readiness health group。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class StreambatchReadinessIndicator extends ReadinessHealthIndicator {

    private static final String UNKNOWN_VERSION = "unknown";

    @Override
    protected String serviceName() {
        return "stream-batch";
    }

    @Override
    protected HealthResponse probeReadiness() {
        // Flink/Spark/Iceberg 客户端在离线构建下被注释（运行时由集群提供类路径），
        // 此处仅做轻量就绪探测。
        return HealthResponse.up(serviceName(), UNKNOWN_VERSION,
                Map.of("submitter", "runtime-cluster-provided"));
    }
}