package com.levango7.dataenginebdp.sqlgateway.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查控制器。
 *
 * <p>提供 SQL 网关自身的健康状态端点，与 Spring Boot Actuator 的 {@code /actuator/health} 互补，
 * 用于业务探针与版本识别。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
@Tag(name = "SQL网关-健康检查", description = "SQL网关服务探针")
@RequestMapping("/api/v1/health")
public class HealthController {

    /**
     * 返回 SQL 网关健康状态。
     *
     * @return 包含 status / component / version 的健康信息
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("status", "UP");
        info.put("component", "sql-gateway");
        info.put("version", "0.1.0");
        return ResponseEntity.ok(info);
    }
}