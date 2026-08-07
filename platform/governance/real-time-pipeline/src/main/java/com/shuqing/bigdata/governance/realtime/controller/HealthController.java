package com.shuqing.bigdata.governance.realtime.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查控制器。
 *
 * <p>提供 {@code GET /api/v1/health} 端点，供 Docker 集成测试与 K8s liveness/readiness 探针使用。
 * 与平台其他组件（Catalog Go 组件）保持一致的健康检查路径。
 */
@RestController
public class HealthController {

    @GetMapping("/api/v1/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "component", "real-time-governance-pipeline",
                "version", "0.1.0",
                "timestamp", java.time.Instant.now().toString()
        );
    }
}