package com.levango7.dataenginebdp.finops.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Map;

/**
 * 健康检查控制器。
 */
@RestController
@Tag(name = "成本运营-健康检查", description = "成本模型服务探针")
@RequestMapping("/api/v1/health")
public class HealthController {

    /** 返回成本模型服务健康状态 */
    @GetMapping
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "component", "cost-model",
                "version", "0.1.0"
        );
    }
}