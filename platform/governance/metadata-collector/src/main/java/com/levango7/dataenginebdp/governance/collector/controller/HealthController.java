package com.levango7.dataenginebdp.governance.collector.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据采集服务健康检查端点。
 *
 * <p>GET {@code /api/v1/health} 返回服务存活状态、版本与已注册的 Collector 类型，
 * 供上层平台探针与运维大盘使用。</p>
 */
@RestController
@Tag(name = "数据治理-采集健康检查", description = "元数据采集服务探针")
@RequestMapping("/api/v1/health")
public class HealthController {

    private static final String VERSION = "0.1.0";

    @Operation(summary = "健康检查")
    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("component", "metadata-collector");
        body.put("version", VERSION);
        body.put("supportedSourceTypes", List.of("HIVE", "DORIS", "KAFKA", "FILESYSTEM"));
        return body;
    }
}