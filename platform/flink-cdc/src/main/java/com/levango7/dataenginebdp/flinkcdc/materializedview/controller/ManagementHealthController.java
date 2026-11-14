package com.levango7.dataenginebdp.flinkcdc.materializedview.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理面健康端点。
 *
 * <p>路径采用平台白名单口径 {@code /api/v1/health}——它是 common-security
 * {@code JwtAuthFilter.shouldNotFilter} 与安全过滤链共同放行的健康检查路径
 * （与 catalog / encaps-layer 等服务的既有口径一致）；探针只需一个健康信号，
 * 不再另设 {@code /healthz} 之类非白名单路径（那会被 JwtAuthFilter 401，
 * 属过滤器自拒、链级 permitAll 管不到）。</p>
 *
 * <p>输出为无依赖的就绪信号（Spring 上下文已就绪即 UP）；依赖级健康
 * （Doris/Iceberg 可达性）属后续段增强项，不做「假绿」。</p>
 */
@RestController
public class ManagementHealthController {

    /**
     * 健康检查。
     *
     * @return 状态 JSON
     */
    @GetMapping("/api/v1/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "flink-cdc-materializedview");
        return body;
    }
}
