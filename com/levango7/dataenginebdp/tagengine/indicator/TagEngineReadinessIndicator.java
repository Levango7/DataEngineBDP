package com.shuqing.bigdata.tagengine.indicator;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.common.health.indicator.ReadinessHealthIndicator;
import com.shuqing.bigdata.tagengine.store.TagStore;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标签画像引擎 Actuator readiness 健康指示器。
 *
 * <p>注册到 Spring Boot Actuator {@code /actuator/health/readiness} 端点，
 * 对应 K8s readinessProbe 语义。通过 {@link TagStore#listTagDefinitions(String)}
 * 以探测租户发起只读查询，探测标签存储连通性，异常时返回 DOWN 供 K8s 摘除流量。</p>
 *
 * <p>bean 名 {@code tagEngineReadinessIndicator} 以 {@code readinessIndicator} 结尾，
 * Spring Boot 3.x 自动将其归入 readiness health group。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class TagEngineReadinessIndicator extends ReadinessHealthIndicator {

    private static final String UNKNOWN_VERSION = "unknown";
    private static final String HEALTH_PROBE_TENANT = "__health_probe__";

    private final TagStore tagStore;

    /**
     * 构造指示器。
     *
     * @param tagStore 标签存储，用于探测存储连通性
     */
    public TagEngineReadinessIndicator(TagStore tagStore) {
        this.tagStore = tagStore;
    }

    @Override
    protected String serviceName() {
        return "tag-engine";
    }

    @Override
    protected HealthResponse probeReadiness() {
        try {
            int definitionCount = tagStore.listTagDefinitions(HEALTH_PROBE_TENANT).size();
            return HealthResponse.up(serviceName(), UNKNOWN_VERSION,
                    Map.of("tagDefinitionCount", definitionCount));
        } catch (Exception ex) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("error", ex.getClass().getSimpleName());
            details.put("message", String.valueOf(ex.getMessage()));
            return HealthResponse.down(serviceName(), UNKNOWN_VERSION, details);
        }
    }
}