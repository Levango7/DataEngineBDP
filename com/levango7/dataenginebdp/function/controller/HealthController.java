package com.shuqing.bigdata.function.controller;

import com.shuqing.bigdata.common.health.controller.AbstractHealthController;
import com.shuqing.bigdata.common.health.dto.HealthResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 函数服务健康检查控制器。
 *
 * <p>基于 {@link AbstractHealthController} 模板方法，提供三个端点：</p>
 * <ul>
 *   <li>{@code GET /api/v1/health/liveness} - 存活探针，仅检查进程存活，始终快速返回 UP。</li>
 *   <li>{@code GET /api/v1/health/readiness} - 就绪探针，探测函数运行时就绪状态。</li>
 *   <li>{@code GET /api/v1/health} - 统一向后兼容端点，委托就绪探针。</li>
 * </ul>
 *
 * <p>readiness 当前实现为轻量探测：函数服务为 Knative Serverless 运行时，
 * 无外部持久化依赖，故仅返回 UP + 服务名标识，供 K8s readinessProbe 摘除流量判断。</p>
 *
 * <p>版本号从 {@link BuildProperties} 动态读取，未配置 build-info 时降级为 {@code "unknown"}。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
public class HealthController extends AbstractHealthController {

    /**
     * 构造控制器。
     *
     * @param buildPropertiesProvider BuildProperties 可选注入提供者
     */
    public HealthController(ObjectProvider<BuildProperties> buildPropertiesProvider) {
        super(buildPropertiesProvider);
    }

    @Override
    protected String serviceName() {
        return "function-service";
    }

    @Override
    protected HealthResponse probeReadiness() {
        // 函数服务为 Serverless 运行时，无外部持久化依赖，
        // 此处仅做轻量就绪探测：返回 UP + 服务标识。
        return HealthResponse.up(serviceName(), resolveVersion(),
                Map.of("runtime", "knative-serverless"));
    }
}