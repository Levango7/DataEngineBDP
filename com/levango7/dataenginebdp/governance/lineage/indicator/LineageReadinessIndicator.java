package com.shuqing.bigdata.governance.lineage.indicator;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.common.health.indicator.ReadinessHealthIndicator;
import com.shuqing.bigdata.governance.lineage.service.LineageGraphWriter;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 血缘分析服务 Actuator readiness 健康指示器。
 *
 * <p>注册到 Spring Boot Actuator {@code /actuator/health/readiness} 端点，
 * 对应 K8s readinessProbe 语义。通过 {@link LineageGraphWriter#getKnownTables()}
 * 探测图谱写入器状态，返回已知表数量供运维大盘识别。</p>
 *
 * <p>bean 名 {@code lineageReadinessIndicator} 以 {@code readinessIndicator} 结尾，
 * Spring Boot 3.x 自动将其归入 readiness health group。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class LineageReadinessIndicator extends ReadinessHealthIndicator {

    private static final String UNKNOWN_VERSION = "unknown";

    private final LineageGraphWriter graphWriter;

    /**
     * 构造指示器。
     *
     * @param graphWriter 图谱写入器，用于探测就绪状态
     */
    public LineageReadinessIndicator(LineageGraphWriter graphWriter) {
        this.graphWriter = graphWriter;
    }

    @Override
    protected String serviceName() {
        return "lineage-analyzer";
    }

    @Override
    protected HealthResponse probeReadiness() {
        int knownTables = graphWriter.getKnownTables().size();
        return HealthResponse.up(serviceName(), UNKNOWN_VERSION,
                Map.of("knownTables", knownTables));
    }
}