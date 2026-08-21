package com.shuqing.bigdata.streambatch.controller;

import com.shuqing.bigdata.common.health.controller.AbstractHealthController;
import com.shuqing.bigdata.common.health.dto.HealthResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 流批一体调度服务健康检查控制器。
 *
 * <p>基于 {@link AbstractHealthController} 模板方法，提供三个端点：</p>
 * <ul>
 *   <li>{@code GET /api/v1/health/liveness} - 存活探针，仅检查进程存活，始终快速返回 UP。</li>
 *   <li>{@code GET /api/v1/health/readiness} - 就绪探针，探测调度服务就绪状态。</li>
 *   <li>{@code GET /api/v1/health} - 统一向后兼容端点，委托就绪探针。</li>
 * </ul>
 *
 * <p>readiness 当前实现为轻量探测：Flink/Spark/Iceberg 客户端依赖在离线构建下
 * 被注释（运行时由集群提供类路径），故仅返回 UP + 服务名标识。待集群客户端
 * 在线后可注入 {@code FlinkStreamSubmitter} / {@code SparkBatchSubmitter}
 * 等依赖做真实连通性探测。</p>
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
        return "stream-batch";
    }

    @Override
    protected HealthResponse probeReadiness() {
        // Flink/Spark/Iceberg 客户端在离线构建下被注释（运行时由集群提供类路径），
        // 此处仅做轻量就绪探测：返回 UP + 服务标识，待集群客户端在线后扩展为真实依赖探测。
        return HealthResponse.up(serviceName(), resolveVersion(),
                Map.of("submitter", "runtime-cluster-provided"));
    }
}