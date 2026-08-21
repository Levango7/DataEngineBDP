package com.shuqing.bigdata.sqlgateway.indicator;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.common.health.indicator.ReadinessHealthIndicator;
import com.shuqing.bigdata.sqlgateway.virtual.DataSourceManager;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQL 网关 Actuator readiness 健康指示器。
 *
 * <p>注册到 Spring Boot Actuator {@code /actuator/health/readiness} 端点，
 * 对应 K8s readinessProbe 语义。通过 {@link DataSourceManager#getStats()} 探测
 * HikariCP 连接池运行态，异常时返回 DOWN 供 K8s 摘除流量。</p>
 *
 * <p>bean 名 {@code sqlGatewayReadinessIndicator} 以 {@code readinessIndicator} 结尾，
 * Spring Boot 3.x 自动将其归入 readiness health group。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class SqlGatewayReadinessIndicator extends ReadinessHealthIndicator {

    private static final String UNKNOWN_VERSION = "unknown";

    private final DataSourceManager dataSourceManager;

    /**
     * 构造指示器。
     *
     * @param dataSourceManager 数据源连接池管理器，用于探测连接池状态
     */
    public SqlGatewayReadinessIndicator(DataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    @Override
    protected String serviceName() {
        return "sql-gateway";
    }

    @Override
    protected HealthResponse probeReadiness() {
        try {
            Map<String, Object> poolStats = dataSourceManager.getStats();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("poolCount", poolStats.size());
            details.put("pools", poolStats);
            return HealthResponse.up(serviceName(), UNKNOWN_VERSION, details);
        } catch (Exception ex) {
            return HealthResponse.down(serviceName(), UNKNOWN_VERSION,
                    Map.of("error", ex.getClass().getSimpleName(),
                            "message", String.valueOf(ex.getMessage())));
        }
    }
}