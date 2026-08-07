package com.shuqing.bigdata.finops.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查控制器。
 */
@RestController
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