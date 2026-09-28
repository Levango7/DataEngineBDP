package com.levango7.dataenginebdp.encaps.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封装层健康检查端点。
 *
 * <p>GET {@code /api/v1/health} 返回封装层自身存活状态与版本信息，供 K8s 探针与
 * 集成测试（tests/integration/k3s 链路4）使用。此前模块缺失该端点：请求落到 404，
 * 再被安全过滤链的错误派发改写为 403，集成测试 setup 全部超时失败。</p>
 */
@RestController
@Tag(name = "封装层-健康检查", description = "封装层存活探针")
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
