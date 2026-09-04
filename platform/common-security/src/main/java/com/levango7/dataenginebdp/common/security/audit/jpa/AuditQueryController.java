package com.levango7.dataenginebdp.common.security.audit.jpa;

import com.levango7.dataenginebdp.common.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计日志查询端点（C2，合规检索）。
 *
 * <p>统一前缀 {@code /api/v1/audit}。权限双闸：
 * <ul>
 *   <li>方法级 {@code @PreAuthorize("hasRole('SUPER_ADMIN')}")（路由层拒非管理员）</li>
 *   <li>运行期租户隔离：查询的 tenantId 缺省绑定为当前登录租户——
 *       平台管理员（platform-admin）不传 tenantId 时返回 403，
 *       防止越权遍历他租户审计记录（即使鉴权层被绕过）</li>
 * </ul></p>
 */
@Tag(name = "审计合规", description = "审计日志查询（等保三级检索）")
@RestController
@RequestMapping("/api/v1/audit")
public class AuditQueryController {

    /** 平台管理员的特殊租户 ID（TenantContext 兜底值）。 */
    private static final String PLATFORM_ADMIN_TENANT = "platform-admin";

    private final AuditQueryService queryService;

    public AuditQueryController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 组合条件分页查询审计事件。
     *
     * @param userId     操作人
     * @param tenantId   租户（不传 = 当前租户；平台管理员必填）
     * @param action     动作名（LOGIN/CREATE_DATASOURCE/...）
     * @param resource   资源类型
     * @param resourceId 资源 ID
     * @param fromIso    起始时间 ISO-8601
     * @param toIso      结束时间 ISO-8601
     * @param page       页码（0 起）
     * @param size       每页数（上限 200）
     */
    @Operation(summary = "审计日志查询（SUPER_ADMIN）")
    @GetMapping("/logs")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> query(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String fromIso,
            @RequestParam(required = false) String toIso,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        // 运行期租户隔离：缺省绑定当前租户
        String effectiveTenant = tenantId != null && !tenantId.isBlank()
                ? tenantId
                : TenantContext.getTenantId();
        if (effectiveTenant == null || effectiveTenant.isBlank()) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "缺少租户上下文", "messageKey", "error.auth.forbidden"));
        }
        // 平台管理员必须显式指定租户，防越权遍历
        if (PLATFORM_ADMIN_TENANT.equals(effectiveTenant) && (tenantId == null || tenantId.isBlank())) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "平台管理员查询审计须显式指定 tenantId",
                    "messageKey", "error.auth.forbidden"));
        }

        Instant from = fromIso != null ? Instant.parse(fromIso) : null;
        Instant to = toIso != null ? Instant.parse(toIso) : null;
        int safeSize = Math.min(Math.max(size, 1), 200);

        Page<AuditLogEntity> result = queryService.query(userId, effectiveTenant, action,
                resource, resourceId, from, to,
                PageRequest.of(page, safeSize, Sort.by(Sort.Direction.DESC, "timestamp")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", result.getTotalElements());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("list", result.getContent());
        return ResponseEntity.ok(body);
    }
}
