package com.shuqing.bigdata.finops.dashboard.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 健康检查控制器。
 *
 * <p>提供 GET /api/v1/health 端点，无需认证。</p>
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "finops-dashboard",
                "version", "0.1.0",
                "timestamp", Instant.now().toString()
        );
    }
}