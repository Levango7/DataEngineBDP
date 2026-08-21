package com.shuqing.bigdata.tagengine.controller;

import com.shuqing.bigdata.common.health.dto.HealthResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标签画像引擎向后兼容健康端点。
 *
 * <p>保留原 {@code GET /health} 端点供现有 K8s 探针与运维大盘平滑迁移到
 * {@code /api/v1/health}、{@code /api/v1/health/liveness}、
 * {@code /api/v1/health/readiness} 三端点体系。</p>
 *
 * <p>本控制器委托 {@link HealthController#readiness()} 返回统一
 * {@link HealthResponse} 结构，与 {@code /api/v1/health} 响应一致，
 * 待上游消费者全部切换到新端点后可移除。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
public class TagEngineLegacyHealthController {

    private final HealthController healthController;

    /**
     * 构造兼容控制器。
     *
     * @param healthController 主健康控制器，委托其就绪探针
     */
    public TagEngineLegacyHealthController(HealthController healthController) {
        this.healthController = healthController;
    }

    /**
     * 向后兼容健康端点。
     *
     * @return 就绪探针结果，HTTP 200 或 503
     */
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return healthController.readiness();
    }
}