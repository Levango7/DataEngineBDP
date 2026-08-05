package com.shuqing.bigdata.infra.xinchang.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 健康检查 Controller。
 *
 * <p>对应 {@code GET /api/v1/health}，无需鉴权，由 SecurityConfig permitAll 放行。</p>
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    /**
     * 健康检查。
     *
     * @return 健康状态
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "infra-provider-xinchang",
                "provider", "xinchang",
                "supportedCpuArch", List.of("KUNPENG", "HYGON", "PHYTIUM", "ZHAOXIN"),
                "supportedOs", List.of("KYLIN_V10", "UOS")));
    }
}