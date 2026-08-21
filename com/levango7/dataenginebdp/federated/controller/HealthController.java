package com.shuqing.bigdata.federated.controller;

import com.shuqing.bigdata.common.health.controller.AbstractHealthController;
import com.shuqing.bigdata.common.health.dto.HealthResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 联邦查询服务健康检查控制器。
 *
 * <p>基于 {@link AbstractHealthController} 模板方法，提供三个端点：</p>
 * <ul>
 *   <li>{@code GET /api/v1/health/liveness} - 存活探针，仅检查进程存活，始终快速返回 UP。</li>
 *   <li>{@code GET /api/v1/health/readiness} - 就绪探针，探测联邦查询关键依赖。</li>
 *   <li>{@code GET /api/v1/health} - 统一向后兼容端点，委托就绪探针。</li>
 * </ul>
 *
 * <p>readiness 当前实现为轻量探测：联邦查询路由依赖（GlobalCatalog/ClusterTransport）
 * 在离线构建下被排除，故仅返回 UP + 服务名标识。待路由层在线后可注入
 * {@code FederatedQueryRouter} 等依赖做真实连通性探测。</p>
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
        return "federated-query";
    }

    @Override
    protected HealthResponse probeReadiness() {
        // 轻集群路由层（GlobalCatalogClient/ClusterTransport）在离线构建下被排除，
        // 此处仅做轻量就绪探测：返回 UP + 服务标识，待路由层在线后扩展为真实依赖探测。
        return HealthResponse.up(serviceName(), resolveVersion(),
                Map.of("router", "offline-build-stub"));
    }
}