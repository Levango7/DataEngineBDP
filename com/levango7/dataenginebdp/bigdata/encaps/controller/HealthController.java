package com.shuqing.bigdata.encaps.controller;

import com.shuqing.bigdata.common.health.controller.AbstractHealthController;
import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.encaps.repository.TenantRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 封装层健康检查端点。
 *
 * <p>基于 {@link AbstractHealthController} 模板方法，提供三个端点：</p>
 * <ul>
 *   <li>{@code GET /api/v1/health/liveness} - 存活探针，仅检查进程存活，始终快速返回 UP。</li>
 *   <li>{@code GET /api/v1/health/readiness} - 就绪探针，探测租户数据库连通性。</li>
 *   <li>{@code GET /api/v1/health} - 向后兼容端点，委托就绪探针。</li>
 * </ul>
 *
 * <p>readiness 通过 {@link TenantRepository#count()} 探测数据库连接，
 * 异常时返回 DOWN 并携带错误信息，供 K8s readinessProbe 摘除流量。</p>
 *
 * <p>版本号从 {@link BuildProperties} 动态读取，未配置 build-info 时降级为 {@code "unknown"}。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
public class HealthController extends AbstractHealthController {

    private final TenantRepository tenantRepository;

    /**
     * 构造控制器。
     *
     * @param buildPropertiesProvider BuildProperties 可选注入提供者
     * @param tenantRepository        租户仓储，用于探测数据库连通性
     */
    public HealthController(ObjectProvider<BuildProperties> buildPropertiesProvider,
                            TenantRepository tenantRepository) {
        super(buildPropertiesProvider);
        this.tenantRepository = tenantRepository;
    }

    @Override
    protected String serviceName() {
        return "encaps-layer";
    }

    @Override
    protected HealthResponse probeReadiness() {
        try {
            long tenantCount = tenantRepository.count();
            return HealthResponse.up(serviceName(), resolveVersion(),
                    Map.of("tenantCount", tenantCount));
        } catch (Exception ex) {
            return HealthResponse.down(serviceName(), resolveVersion(),
                    Map.of("error", ex.getClass().getSimpleName(),
                            "message", String.valueOf(ex.getMessage())));
        }
    }
}
