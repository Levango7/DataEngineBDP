package com.levango7.dataenginebdp.infra.privatecloud.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查控制器。
 *
 * <p>放行路径（{@code /api/v1/health}），不走 JWT 认证，
 * 供 K8s liveness/readiness 探针与负载均衡健康检查使用。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    /** 返回私有云 Provider 服务健康状态 */
    @GetMapping
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "component", "infra-provider-private",
                "version", "0.1.0"
        );
    }
}