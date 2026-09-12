package com.levango7.dataenginebdp.tagengine.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.tagengine.model.AudienceRequest;
import com.levango7.dataenginebdp.tagengine.model.AudienceResult;
import com.levango7.dataenginebdp.tagengine.service.AudienceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Map;

/**
 * 人群圈选 REST 控制器。
 *
 * <p>统一前缀：{@code /api/v1/audiences}</p>
 * <ul>
 *   <li>POST /select — 人群圈选，返回 count 与可选 user_id 列表</li>
 * </ul>
 *
 * <p>对应详细设计 §5 人群圈选、§6 接口 {@code POST /api/tag/v1/segment}。</p>
 *
 * <p>R12 安全修复：添加类级 @PreAuthorize + fail-closed 租户隔离，
 * tenantId 从 JWT（TenantContext）获取并强制注入请求体，不信任客户端传入。</p>
 */
@RestController
@Tag(name = "标签引擎-人群圈选", description = "按标签条件圈选人群")
@RequestMapping("/api/v1/audiences")
@PreAuthorize("isAuthenticated()")
public class AudienceController {

    private final AudienceService audienceService;

    public AudienceController(AudienceService audienceService) {
        this.audienceService = audienceService;
    }

    /**
     * 人群圈选。
     *
     * @param req 圈选请求
     * @return 圈选结果
     */
    @Operation(summary = "人群圈选")
    @PostMapping("/select")
    public ResponseEntity<AudienceResult> select(@RequestBody AudienceRequest req) {
        // R12 安全修复：fail-closed 租户校验 + 强制注入 tenantId（不信任请求体）
        String tenantId = requireTenant();
        req.setTenantId(tenantId);
        return ResponseEntity.ok(audienceService.selectAudience(req));
    }

    /**
     * 从 TenantContext 获取租户 ID，缺失则 fail-closed（R12 安全修复）。
     *
     * @return 当前请求的租户 ID
     * @throws IllegalStateException 若 TenantContext 未设置租户 ID
     */
    private static String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /**
     * 异常处理：缺少租户上下文返回 403（R12 安全修复）。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "message", e.getMessage()));
    }
}
