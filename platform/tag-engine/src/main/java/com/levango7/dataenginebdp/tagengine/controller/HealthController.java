package com.levango7.dataenginebdp.tagengine.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标签画像引擎健康检查端点。
 *
 * <p>GET {@code /health} 返回引擎存活状态与版本信息，供 K8s 探针与运维大盘使用。</p>
 */
@RestController
public class HealthController {

    /**
     * 健康检查。
     *
     * @return 包含 status / component / version 的健康信息
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("component", "tag-engine");
        body.put("version", "0.1.0");
        return body;
    }
}