package com.levango7.dataenginebdp.infra.orchestrator.controller;

import com.levango7.dataenginebdp.infra.orchestrator.model.EnvironmentType;
import com.levango7.dataenginebdp.infra.orchestrator.registry.ProviderRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * 健康检查 Controller。
 *
 * <p>对应 {@code GET /api/v1/health}，无需鉴权，由 SecurityConfig permitAll 放行。
 * 返回编排层运行态、已注册 Provider 数量与缺失环境列表。</p>
 */
@RestController
@Tag(name = "基础设施编排-健康检查", description = "编排层与Provider注册表探针")
@RequestMapping("/api/v1/health")
public class HealthController {

    private final ProviderRegistry registry;

    /**
     * 构造 Controller。
     *
     * @param registry Provider 注册表
     */
    public HealthController(ProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * 健康检查。
     *
     * @return 健康状态
     */
    @Operation(summary = "健康检查")
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        int total = EnvironmentType.values().length;
        int registered = registry.size();
        List<EnvironmentType> missing = registry.missingEnvironments();
        String status = missing.isEmpty() ? "UP" : "DEGRADED";

        return ResponseEntity.ok(Map.of(
                "status", status,
                "service", "infra-orchestrator",
                "layer", "L0.5",
                "totalEnvironments", total,
                "registeredProviders", registered,
                "missingEnvironments", missing,
                "registeredEnvironments", registry.registeredEnvironments()));
    }
}