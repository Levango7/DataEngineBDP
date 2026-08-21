package com.shuqing.bigdata.infra.orchestrator.controller;

import com.shuqing.bigdata.common.health.controller.AbstractHealthController;
import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.infra.orchestrator.model.EnvironmentType;
import com.shuqing.bigdata.infra.orchestrator.registry.ProviderRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排层健康检查 Controller。
 *
 * <p>基于 {@link AbstractHealthController} 模板方法，提供三个端点：</p>
 * <ul>
 *   <li>{@code GET /api/v1/health/liveness} - 存活探针，仅检查进程存活，始终快速返回 UP。</li>
 *   <li>{@code GET /api/v1/health/readiness} - 就绪探针，探测 Provider 注册表完整性。</li>
 *   <li>{@code GET /api/v1/health} - 向后兼容端点，委托就绪探针。</li>
 * </ul>
 *
 * <p>readiness 通过 {@link ProviderRegistry#missingEnvironments()} 探测已注册 Provider
 * 覆盖度：全部 7 种环境均已注册返回 UP；存在缺失环境返回 DEGRADED（可服务流量但需告警），
 * 供 K8s readinessProbe 与运维大盘识别编排层降级状态。</p>
 *
 * <p>原响应顶层字段 {@code layer} / {@code totalEnvironments} / {@code registeredProviders} /
 * {@code missingEnvironments} / {@code registeredEnvironments} 已统一收敛至 {@code details}，
 * 顶层仅保留 {@code status} / {@code service} / {@code version} / {@code timestamp}。</p>
 *
 * <p>版本号从 {@link BuildProperties} 动态读取，未配置 build-info 时降级为 {@code "unknown"}。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
public class HealthController extends AbstractHealthController {

    private static final String LAYER = "L0.5";

    private final ProviderRegistry registry;

    /**
     * 构造 Controller。
     *
     * @param buildPropertiesProvider BuildProperties 可选注入提供者
     * @param registry                Provider 注册表，用于探测就绪状态
     */
    public HealthController(ObjectProvider<BuildProperties> buildPropertiesProvider,
                            ProviderRegistry registry) {
        super(buildPropertiesProvider);
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
            return HealthResponse.up(serviceName(), resolveVersion(), details);
        }
        return HealthResponse.degraded(serviceName(), resolveVersion(), details);
    }
}
