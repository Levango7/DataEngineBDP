package com.shuqing.bigdata.sqlgateway.controller;

import com.shuqing.bigdata.common.health.controller.AbstractHealthController;
import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.sqlgateway.virtual.DataSourceManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQL 网关健康检查控制器。
 *
 * <p>基于 {@link AbstractHealthController} 模板方法，提供三个端点：</p>
 * <ul>
 *   <li>{@code GET /api/v1/health/liveness} - 存活探针，仅检查进程存活，始终快速返回 UP。</li>
 *   <li>{@code GET /api/v1/health/readiness} - 就绪探针，探测虚拟表连接池状态。</li>
 *   <li>{@code GET /api/v1/health} - 向后兼容端点，委托就绪探针。</li>
 * </ul>
 *
 * <p>readiness 通过 {@link DataSourceManager#getStats()} 探测 HikariCP 连接池运行态，
 * 返回连接池数量与各池 active/idle/total/threadsAwaiting 统计，
 * 异常时返回 DOWN 供 K8s readinessProbe 摘除流量。</p>
 *
 * <p>版本号从 {@link BuildProperties} 动态读取，未配置 build-info 时降级为 {@code "unknown"}。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
public class HealthController extends AbstractHealthController {

    private final DataSourceManager dataSourceManager;

    /**
     * 构造控制器。
     *
     * @param buildPropertiesProvider BuildProperties 可选注入提供者
     * @param dataSourceManager       数据源连接池管理器，用于探测连接池状态
     */
    public HealthController(ObjectProvider<BuildProperties> buildPropertiesProvider,
                            DataSourceManager dataSourceManager) {
        super(buildPropertiesProvider);
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
            return HealthResponse.up(serviceName(), resolveVersion(), details);
        } catch (Exception ex) {
            return HealthResponse.down(serviceName(), resolveVersion(),
                    Map.of("error", ex.getClass().getSimpleName(),
                            "message", String.valueOf(ex.getMessage())));
        }
    }
}
