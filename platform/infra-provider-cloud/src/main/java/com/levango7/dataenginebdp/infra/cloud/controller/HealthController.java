package com.levango7.dataenginebdp.infra.cloud.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Map;

/**
 * 健康检查控制器。
 *
 * <p>放行路径（无需 JWT），供 K8s liveness/readiness 探针与负载均衡健康检查使用。</p>
 */
@RestController
@Tag(name = "基础设施供应-云健康检查", description = "多云Provider探针")
@RequestMapping("/api/v1/health")
public class HealthController {

    /**
     * 健康检查端点。
     *
     * @return 服务状态
     */
    @GetMapping
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "infra-provider-cloud",
                "version", "0.1.0"
        );
    }
}