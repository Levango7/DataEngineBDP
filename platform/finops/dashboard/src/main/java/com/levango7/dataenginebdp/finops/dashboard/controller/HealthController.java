package com.levango7.dataenginebdp.finops.dashboard.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Instant;
import java.util.Map;

/**
 * 健康检查控制器。
 *
 * <p>提供 GET /api/v1/health 端点，无需认证。</p>
 */
@RestController
@Tag(name = "成本运营-健康检查", description = "FinOps看板服务探针")
@RequestMapping("/api/v1/health")
public class HealthController {

    @Operation(summary = "健康检查")
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