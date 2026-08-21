package com.shuqing.bigdata.governance.lineage.controller;

import com.shuqing.bigdata.common.health.controller.AbstractHealthController;
import com.shuqing.bigdata.common.health.dto.HealthResponse;
import com.shuqing.bigdata.governance.lineage.service.LineageGraphWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 血缘分析服务健康检查控制器。
 *
 * <p>基于 {@link AbstractHealthController} 模板方法，提供三个端点：</p>
 * <ul>
 *   <li>{@code GET /api/v1/health/liveness} - 存活探针，仅检查进程存活，始终快速返回 UP。</li>
 *   <li>{@code GET /api/v1/health/readiness} - 就绪探针，探测图谱写入器状态与已知表规模。</li>
 *   <li>{@code GET /api/v1/health} - 向后兼容端点，委托就绪探针。</li>
 * </ul>
 *
 * <p>readiness 通过 {@link LineageGraphWriter#getKnownTables()} 探测图谱写入器状态，
 * 返回已知表数量供运维大盘识别。原 {@code status()} 端点响应结构已统一为
 * {@link HealthResponse}，{@code knownTables} 移入 {@code details}。</p>
 *
 * <p>版本号从 {@link BuildProperties} 动态读取，替代原硬编码缺失的版本字段，
 * 未配置 build-info 时降级为 {@code "unknown"}。</p>
 *
 * <p>Actuator 集成已迁移至独立的 {@code LineageLivenessIndicator} 与
 * {@code LineageReadinessIndicator}，实现 liveness/readiness 分离。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
public class HealthController extends AbstractHealthController {

    private final LineageGraphWriter graphWriter;

    /**
     * 构造控制器。
     *
     * @param buildPropertiesProvider BuildProperties 可选注入提供者
     * @param graphWriter             图谱写入器，用于探测就绪状态
     */
    public HealthController(ObjectProvider<BuildProperties> buildPropertiesProvider,
                            LineageGraphWriter graphWriter) {
        super(buildPropertiesProvider);
        this.graphWriter = graphWriter;
    }

    @Override
    protected String serviceName() {
        return "lineage-analyzer";
    }

    @Override
    protected HealthResponse probeReadiness() {
        int knownTables = graphWriter.getKnownTables().size();
        return HealthResponse.up(serviceName(), resolveVersion(),
                Map.of("knownTables", knownTables));
    }
}
