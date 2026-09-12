package com.levango7.dataenginebdp.tagengine.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.tagengine.model.TagQuery;
import com.levango7.dataenginebdp.tagengine.model.UserProfile;
import com.levango7.dataenginebdp.tagengine.service.ProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * 用户画像查询 REST 控制器。
 *
 * <p>统一前缀：{@code /api/v1/profiles}</p>
 * <ul>
 *   <li>GET  /{userId}     — 单用户画像，返回 200 或 404</li>
 *   <li>POST /query        — 按标签条件查询用户列表，返回 200</li>
 *   <li>POST /count        — 按标签条件统计人数，返回 200</li>
 * </ul>
 *
 * <p>对应详细设计 §6 接口 {@code GET /api/tag/v1/portrait/{userId}}。</p>
 *
 * <p>R12 安全修复：添加类级 @PreAuthorize + fail-closed 租户隔离，
 * tenantId 从 JWT（TenantContext）获取并强制注入查询条件，不信任客户端传入。</p>
 */
@RestController
@Tag(name = "标签引擎-用户画像", description = "用户画像查询与标签筛选")
@RequestMapping("/api/v1/profiles")
@PreAuthorize("isAuthenticated()")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 获取单用户画像。
     *
     * @param userId 用户 ID
     * @return 用户画像
     */
    @Operation(summary = "获取单用户画像")
    @GetMapping("/{userId}")
    public ResponseEntity<UserProfile> getProfile(@PathVariable String userId) {
        // R13 安全修复：fail-closed 租户校验 + 显式传递 tenantId 实现租户隔离
        String tenantId = requireTenant();
        UserProfile profile = profileService.getProfile(userId, tenantId);
        if (profile == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(profile);
    }

    /**
     * 按标签条件查询用户列表。
     *
     * @param query 标签查询条件
     * @return 命中用户画像列表
     */
    @Operation(summary = "按标签条件查询用户列表")
    @PostMapping("/query")
    public ResponseEntity<List<UserProfile>> queryByTags(@RequestBody TagQuery query) {
        // R12 安全修复：fail-closed 租户校验 + 强制注入 tenantId（不信任请求体）
        String tenantId = requireTenant();
        query.setTenantId(tenantId);
        return ResponseEntity.ok(profileService.queryByTags(query));
    }

    /**
     * 按标签条件统计人数。
     *
     * @param query 标签查询条件
     * @return 命中用户数
     */
    @Operation(summary = "按标签条件统计人数")
    @PostMapping("/count")
    public ResponseEntity<CountResponse> countByTags(@RequestBody TagQuery query) {
        // R12 安全修复：fail-closed 租户校验 + 强制注入 tenantId（不信任请求体）
        String tenantId = requireTenant();
        query.setTenantId(tenantId);
        long count = profileService.countByTags(query);
        return ResponseEntity.status(HttpStatus.OK).body(new CountResponse(count));
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

    /**
     * 计数响应体。
     *
     * @param count 命中用户数
     */
    public record CountResponse(long count) {
    }
}
