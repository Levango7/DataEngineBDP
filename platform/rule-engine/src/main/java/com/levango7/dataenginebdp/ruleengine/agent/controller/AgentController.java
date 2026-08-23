package com.levango7.dataenginebdp.ruleengine.agent.controller;

import com.levango7.dataenginebdp.ruleengine.agent.core.Agent;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentContext;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentResult;
import com.levango7.dataenginebdp.ruleengine.agent.service.AgentService;
import com.levango7.dataenginebdp.common.security.TenantContext;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agent REST 控制器。
 *
 * <p>提供 8 种内置 Agent 角色的统一 REST 入口：
 * <ul>
 *   <li>{@code POST /api/v1/agents/{role}/execute} — 执行指定角色 Agent</li>
 *   <li>{@code GET  /api/v1/agents}                 — 列出所有可用角色</li>
 *   <li>{@code GET  /api/v1/agents/describe}        — 描述所有角色元数据（含配额、白名单）</li>
 *   <li>{@code GET  /api/v1/agents/{role}/describe} — 描述单个角色元数据</li>
 * </ul>
 *
 * <p>租户与用户 ID 从 {@link TenantContext}（由 JwtAuthFilter 设置）获取，
 * 若未设置则使用 {@code anonymous} 兜底，便于非认证场景（如内部调用）使用。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
@Tag(name = "规则引擎-Agent", description = "内置Agent角色执行与元数据")
@RequestMapping("/api/v1/agents")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 执行指定角色 Agent。
     *
     * @param roleStr 角色名（PLANNING/SQL/VISUALIZATION/QUALITY/LINEAGE/DOCUMENTATION/CODE/AUDIT）
     * @param request 执行请求
     * @return 执行结果
     */
    @Operation(summary = "执行指定角色 Agent")
    @PostMapping("/{role}/execute")
    public ResponseEntity<AgentResult> execute(
            @PathVariable("role") String roleStr,
            @Valid @RequestBody AgentExecutionRequest request) {

        Agent.Role role = parseRole(roleStr);
        if (role == null) {
            return ResponseEntity.badRequest().build();
        }

        String tenantId = resolveTenant();
        String userId = resolveUser();
        String requestId = UUID.randomUUID().toString();

        AgentContext context = AgentContext.builder()
                .userInput(request.getUserInput())
                .input(request.getInput())
                .attributes(request.getAttributes())
                .tenantId(tenantId)
                .userId(userId)
                .maxToolCalls(request.getMaxToolCalls())
                .maxDurationMs(request.getMaxDurationMs())
                .maxOutputChars(request.getMaxOutputChars())
                .allowedTools(request.getAllowedTools())
                .traceId(request.getTraceId())
                .requestId(requestId)
                .build();

        log.info("Agent execute: role={}, tenant={}, user={}, request={}",
                role, tenantId, userId, requestId);

        AgentResult result = agentService.execute(role, context);

        HttpStatus status = switch (result.getStatus()) {
            case SUCCESS -> HttpStatus.OK;
            case INVALID_INPUT, TOOL_DENIED -> HttpStatus.BAD_REQUEST;
            case QUOTA_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(result);
    }

    /**
     * 列出所有可用角色。
     *
     * @return 角色名列表
     */
    @Operation(summary = "列出所有可用角色")
    @GetMapping
    public ResponseEntity<List<String>> listRoles() {
        Set<Agent.Role> roles = agentService.listRoles();
        List<String> names = roles.stream().map(Enum::name).sorted().toList();
        return ResponseEntity.ok(names);
    }

    /**
     * 描述所有角色元数据。
     *
     * @return 角色 → 元数据
     */
    @Operation(summary = "查询所有智能体角色元数据")
    @GetMapping("/describe")
    public ResponseEntity<Map<Agent.Role, Map<String, Object>>> describeAll() {
        return ResponseEntity.ok(agentService.describe());
    }

    /**
     * 描述单个角色元数据。
     *
     * @param roleStr 角色名
     * @return 元数据；未知角色返回 404
     */
    @Operation(summary = "描述单个角色元数据")
    @GetMapping("/{role}/describe")
    public ResponseEntity<?> describeOne(@PathVariable("role") String roleStr) {
        Agent.Role role = parseRole(roleStr);
        if (role == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_ROLE", "message", "Unknown role: " + roleStr));
        }
        Map<Agent.Role, Map<String, Object>> all = agentService.describe();
        Map<String, Object> meta = all.get(role);
        if (meta == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "ROLE_NOT_FOUND", "message", "Role " + role + " not registered"));
        }
        return ResponseEntity.ok(meta);
    }

    /**
     * 解析角色名。
     */
    private Agent.Role parseRole(String roleStr) {
        if (roleStr == null || roleStr.isBlank()) {
            return null;
        }
        try {
            return Agent.Role.valueOf(roleStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 从 TenantContext 获取租户 ID，兜底 anonymous。
     */
    private String resolveTenant() {
        String tenant = TenantContext.getTenantId();
        return (tenant == null || tenant.isBlank()) ? "anonymous" : tenant;
    }

    /**
     * 从 TenantContext 获取用户 ID，兜底 anonymous。
     */
    private String resolveUser() {
        String user = TenantContext.getUserId();
        return (user == null || user.isBlank()) ? "anonymous" : user;
    }

    /**
     * 兜底异常处理。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleError(Exception e) {
        log.error("AgentController error", e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "INTERNAL_ERROR");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}