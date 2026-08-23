package com.levango7.dataenginebdp.encaps.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封装层健康检查端点。
 *
 * <p>GET {@code /api/v1/health} 返回封装层自身的存活状态与版本信息，
 * 供上层平台探针与运维大盘使用。</p>
 */
@RestController
@Tag(name = "封装网关-健康检查", description = "封装层存活探针")
@RequestMapping("/api/v1/health")
public class HealthController {

    @Operation(summary = "健康检查")
    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("component", "encaps-layer");
        body.put("version", "0.1.0");
        return body;
    }
}