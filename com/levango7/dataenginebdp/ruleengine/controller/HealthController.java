package com.shuqing.bigdata.ruleengine.controller;

import com.shuqing.bigdata.common.health.controller.AbstractHealthController;
import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.ruleengine.repository.RuleRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 规则引擎健康检查控制器。
 *
 * <p>基于 {@link AbstractHealthController} 模板方法，提供三个端点：</p>
 * <ul>
 *   <li>{@code GET /api/v1/health/liveness} - 存活探针，仅检查进程存活，始终快速返回 UP。</li>
 *   <li>{@code GET /api/v1/health/readiness} - 就绪探针，探测规则存储连通性与规则加载状态。</li>
 *   <li>{@code GET /api/v1/health} - 向后兼容端点，委托就绪探针。</li>
 * </ul>
 *
 * <p>readiness 通过 {@link RuleRepository#count()} 探测规则存储连通性与已加载规则数量，
 * 异常时返回 DOWN 并携带错误信息，供 K8s readinessProbe 摘除流量。</p>
 *
 * <p>版本号从 {@link BuildProperties} 动态读取，未配置 build-info 时降级为 {@code "unknown"}。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
public class HealthController extends AbstractHealthController {

    private final RuleRepository ruleRepository;

    /**
     * 构造控制器。
     *
     * @param buildPropertiesProvider BuildProperties 可选注入提供者
     * @param ruleRepository          规则仓储，用于探测规则存储连通性
     */
    public HealthController(ObjectProvider<BuildProperties> buildPropertiesProvider,
                            RuleRepository ruleRepository) {
        super(buildPropertiesProvider);
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
            return HealthResponse.up(serviceName(), resolveVersion(),
                    Map.of("ruleCount", ruleCount));
        } catch (Exception ex) {
            return HealthResponse.down(serviceName(), resolveVersion(),
                    Map.of("error", ex.getClass().getSimpleName(),
                            "message", String.valueOf(ex.getMessage())));
        }
    }
}
