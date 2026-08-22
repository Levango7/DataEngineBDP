package com.levango7.dataenginebdp.ruleengine.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Map;

/**
 * 健康检查控制器。
 */
@RestController
@Tag(name = "规则引擎-健康检查", description = "规则引擎服务探针")
@RequestMapping("/api/v1/health")
public class HealthController {

    /** 返回规则引擎健康状态 */
    @GetMapping
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "component", "rule-engine",
                "version", "0.1.0"
        );
    }
}