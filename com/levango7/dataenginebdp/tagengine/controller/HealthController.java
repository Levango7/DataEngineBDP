package com.shuqing.bigdata.tagengine.controller;

import com.shuqing.bigdata.common.health.controller.AbstractHealthController;
import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.tagengine.store.TagStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标签画像引擎健康检查控制器。
 *
 * <p>基于 {@link AbstractHealthController} 模板方法，提供三个端点：</p>
 * <ul>
 *   <li>{@code GET /api/v1/health/liveness} - 存活探针，仅检查进程存活，始终快速返回 UP。</li>
 *   <li>{@code GET /api/v1/health/readiness} - 就绪探针，探测标签存储连通性。</li>
 *   <li>{@code GET /api/v1/health} - 统一向后兼容端点，委托就绪探针。</li>
 * </ul>
 *
 * <p>readiness 通过 {@link TagStore#listTagDefinitions(String)} 以探测租户
 * {@code __health_probe__} 发起一次只读查询，探测标签存储（Mock/Doris）连通性，
 * 异常时返回 DOWN 供 K8s readinessProbe 摘除流量。</p>
 *
 * <p>原 {@code GET /health} 端点由 {@link TagEngineLegacyHealthController} 保留向后兼容，
 * 委托本控制器的就绪探针结果。</p>
 *
 * <p>版本号从 {@link BuildProperties} 动态读取，未配置 build-info 时降级为 {@code "unknown"}。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
public class HealthController extends AbstractHealthController {

    /** readiness 探测使用的只读探测租户，不产生任何写入副作用。 */
    private static final String HEALTH_PROBE_TENANT = "__health_probe__";

    private final TagStore tagStore;

    /**
     * 构造控制器。
     *
     * @param buildPropertiesProvider BuildProperties 可选注入提供者
     * @param tagStore                标签存储，用于探测存储连通性
     */
    public HealthController(ObjectProvider<BuildProperties> buildPropertiesProvider,
                            TagStore tagStore) {
        super(buildPropertiesProvider);
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
            return HealthResponse.up(serviceName(), resolveVersion(),
                    Map.of("tagDefinitionCount", definitionCount));
        } catch (Exception ex) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("error", ex.getClass().getSimpleName());
            details.put("message", String.valueOf(ex.getMessage()));
            return HealthResponse.down(serviceName(), resolveVersion(), details);
        }
    }
}
