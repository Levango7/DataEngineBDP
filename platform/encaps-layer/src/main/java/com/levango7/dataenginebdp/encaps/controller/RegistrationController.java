package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.model.InviteCode;
import com.levango7.dataenginebdp.encaps.model.UserRegistration;
import com.levango7.dataenginebdp.encaps.repository.InviteCodeRepository;
import com.levango7.dataenginebdp.encaps.repository.TenantRepository;
import com.levango7.dataenginebdp.encaps.repository.UserRegistrationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户注册与审批端点（/api/v1/registrations）。
 *
 * <p>流程：
 * 1) 员工 POST /api/v1/registrations {code, username, email, fullName, dept, empId} →
 *    校验邀请码、查重 username → 创建 PENDING 注册记录 → 返回 201
 * 2) 租户管理员在 /admin/approvals 看到该条 → POST /api/v1/registrations/{id}/approve
 *    或 /reject → 状态变更（真实生产会下发 JWT，本演示只改状态）</p>
 *
 * <p><b>权限与隔离</b>（历史缺陷：本控制器全部端点零角色校验、零租户隔离，
 * 任意登录用户可列出并审批所有租户的注册申请）：</p>
 * <ul>
 *   <li>{@code list} 与 {@code decision} 要求 {@code SUPER_ADMIN} 或 {@code TENANT_ADMIN} 角色，
 *       角色来自 JWT 的 {@code realm_access.roles}（Keycloak realm 需授予对应角色）；</li>
 *   <li>非平台超管的查询/审批一律按 {@link TenantContext} 的租户强制过滤，
 *       请求参数里的 {@code tenantId} 只做收窄、不能越界；</li>
 *   <li>审批他人租户的申请返回 404（与账单读取一致，不泄露记录存在性）；</li>
 *   <li>{@code submit} 保持对已登录用户开放——它只能凭有效邀请码创建申请，
 *       归属租户由邀请码决定，不接受调用方传入的 tenantId。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/registrations")
@Tag(name = "用户注册审批", description = "提交注册、查询待审、批准与拒绝")
public class RegistrationController {

    /** 平台超管的租户上下文取值（与 InviteController 约定一致） */
    private static final String PLATFORM_ADMIN_TENANT = "platform-admin";

    private final UserRegistrationRepository regRepo;
    private final InviteCodeRepository inviteRepo;
    private final TenantRepository tenantRepo;

    public record SubmitRequest(
            @NotBlank String code,
            @NotBlank String username,
            @NotBlank String email,
            @NotBlank String fullName,
            String department,
            String employeeId) {
    }

    public record ApproveRequest(
            @NotNull Boolean approved,
            String note) {
    }

    @Operation(summary = "提交注册申请（消耗邀请码）")
    @PostMapping
    @Transactional
    public ResponseEntity<?> submit(@Valid @RequestBody SubmitRequest req) {
        InviteCode invite = inviteRepo.findByCode(req.code().toUpperCase()).orElse(null);
        if (invite == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "邀请码不存在"));
        }
        if (!"PENDING".equals(invite.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "邀请码已被使用或撤销（状态：" + invite.getStatus() + "）"));
        }
        if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "邀请码已过期"));
        }
        if (regRepo.findByUsername(req.username()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "用户名在租户内已存在"));
        }

        UserRegistration reg = new UserRegistration();
        reg.setUsername(req.username());
        reg.setEmail(req.email());
        reg.setFullName(req.fullName());
        reg.setDepartment(req.department());
        reg.setEmployeeId(req.employeeId());
        reg.setRole(invite.getRole());
        reg.setTenantId(invite.getTenantId());
        reg.setInviteCode(invite.getCode());
        reg.setStatus("PENDING");
        reg.setCreatedAt(LocalDateTime.now());
        UserRegistration saved = regRepo.save(reg);

        invite.setStatus("ACTIVE");
        invite.setActivatedAt(LocalDateTime.now());
        invite.setActivatedBy(req.username());
        inviteRepo.save(invite);

        log.info("用户注册申请提交: username={}, tenantId={}, role={}, invite={}",
            saved.getUsername(), saved.getTenantId(), saved.getRole(), invite.getCode());

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @Operation(summary = "查询注册列表（SUPER_ADMIN/TENANT_ADMIN，强制租户隔离）")
    @GetMapping
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<?> list(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String status) {

        Long effectiveTenantId = resolveTenantForList(tenantId);
        if (effectiveTenantId == null && !isPlatformAdmin()) {
            // 普通租户无法从上下文确定租户范围 → 拒绝，绝不回落到全量查询
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "缺少租户上下文，无法确定查询范围",
                        "messageKey", "error.auth.forbidden"));
        }

        // 平台超管不传 tenantId：全域待审视图（仅超管可达此分支）
        List<UserRegistration> rows = effectiveTenantId != null
            ? regRepo.findByTenantIdOrderByCreatedAtDesc(effectiveTenantId)
            : regRepo.findAllByOrderByCreatedAtDesc();

        if (status != null && !status.isBlank()) {
            rows = rows.stream().filter(r -> status.equals(r.getStatus())).toList();
        }
        return ResponseEntity.ok(rows);
    }

    @Operation(summary = "审批（批准/拒绝，限本租户；跨租户返回 404）")
    @PostMapping("/{id}/decision")
    @Transactional
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public ResponseEntity<?> decide(@PathVariable Long id, @Valid @RequestBody ApproveRequest req) {
        UserRegistration reg = regRepo.findById(id).orElse(null);
        if (reg == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canAccessTenant(reg.getTenantId())) {
            // 与账单读取同样的策略：跨租户一律 404，不泄露"该申请存在但不属于你"
            log.warn("跨租户审批被拒: id={}, regTenant={}, ctxTenant={}",
                id, reg.getTenantId(), TenantContext.getTenantId());
            return ResponseEntity.notFound().build();
        }
        if (!"PENDING".equals(reg.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "该申请已审批（状态：" + reg.getStatus() + "）"));
        }

        reg.setStatus(Boolean.TRUE.equals(req.approved()) ? "APPROVED" : "REJECTED");
        reg.setApprovedBy(currentActor());
        reg.setApproveNote(req.note());
        reg.setApprovedAt(LocalDateTime.now());
        UserRegistration saved = regRepo.save(reg);
        log.info("注册审批: id={}, username={}, tenantId={}, decision={}, actor={}",
            saved.getId(), saved.getUsername(), saved.getTenantId(), saved.getStatus(), saved.getApprovedBy());
        return ResponseEntity.ok(saved);
    }

    /** 当前上下文是否为平台超管。 */
    private boolean isPlatformAdmin() {
        return PLATFORM_ADMIN_TENANT.equals(TenantContext.getTenantId());
    }

    /**
     * 解析查询应使用的租户 ID。
     *
     * <p>平台超管：沿用请求参数（可为 null，表示全域）；
     * 普通租户：一律以 {@link TenantContext} 为准，参数只用于收窄，
     * 传入他人租户 ID 时忽略并记录告警。</p>
     */
    private Long resolveTenantForList(Long requestedTenantId) {
        String ctxTenantId = TenantContext.getTenantId();
        if (isPlatformAdmin()) {
            return requestedTenantId;
        }
        if (ctxTenantId == null || ctxTenantId.isBlank()) {
            return null;
        }
        try {
            Long ctxLong = Long.valueOf(ctxTenantId);
            if (requestedTenantId != null && !requestedTenantId.equals(ctxLong)) {
                log.warn("租户隔离拦截: ctxTenant={}, requestedTenant={}（按上下文租户过滤）",
                    ctxTenantId, requestedTenantId);
            }
            return ctxLong;
        } catch (NumberFormatException e) {
            log.warn("无法解析租户上下文为 Long: {}", ctxTenantId);
            return null;
        }
    }

    /** 当前调用方是否有权操作该租户的记录。 */
    private boolean canAccessTenant(Long recordTenantId) {
        if (isPlatformAdmin()) {
            return true;
        }
        String ctxTenantId = TenantContext.getTenantId();
        return ctxTenantId != null && recordTenantId != null
            && ctxTenantId.equals(String.valueOf(recordTenantId));
    }

    /** 审批人：取 JWT subject（真实操作者），无上下文时标记 unknown 而非硬编码角色名。 */
    private String currentActor() {
        String userId = TenantContext.getUserId();
        return userId == null || userId.isBlank() ? "unknown" : userId;
    }
}
