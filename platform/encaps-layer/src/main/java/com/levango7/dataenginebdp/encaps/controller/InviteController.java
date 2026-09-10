package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.InviteCode;
import com.levango7.dataenginebdp.encaps.repository.InviteCodeRepository;
import com.levango7.dataenginebdp.encaps.repository.TenantRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 邀请码管理端点（/api/v1/invites）。
 *
 * <p>生成 8 位大写字母+数字的不可猜测邀请码（SecureRandom），
 * 绑定到指定租户与角色。默认 7 天有效，可由生成者撤销。</p>
 *
 * <p>员工在 /register 页面输入该码 → 后端校验通过后，
 * 创建 PENDING 状态的 UserRegistration，等待租户管理员审批。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/invites")
@Tag(name = "邀请码管理", description = "生成、查询、撤销租户邀请码")
public class InviteController {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LEN = 8;
    private static final int DEFAULT_TTL_DAYS = 7;
    private final SecureRandom rng = new SecureRandom();

    private final InviteCodeRepository inviteRepo;
    private final TenantRepository tenantRepo;

    public record CreateRequest(
            @NotNull Long tenantId,
            @NotBlank String role,
            String note,
            Integer ttlDays) {
    }

    public record ActivateRequest(
            @NotBlank String code,
            @NotBlank String username,
            @NotBlank String email,
            @NotBlank String fullName,
            String department,
            String employeeId) {
    }

    @Operation(summary = "生成邀请码")
    @PostMapping
    @Transactional
    public ResponseEntity<InviteCode> create(@Valid @RequestBody CreateRequest req) {
        if (!tenantRepo.existsById(req.tenantId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        int ttl = (req.ttlDays() == null || req.ttlDays() <= 0) ? DEFAULT_TTL_DAYS : req.ttlDays();
        InviteCode code = new InviteCode();
        code.setCode(generateUniqueCode());
        code.setTenantId(req.tenantId());
        code.setRole(req.role());
        code.setInvitedBy("platform-admin"); // 真实环境从 JWT 取
        code.setNote(req.note());
        code.setStatus("PENDING");
        code.setCreatedAt(LocalDateTime.now());
        code.setExpiresAt(LocalDateTime.now().plusDays(ttl));
        InviteCode saved = inviteRepo.save(code);
        log.info("生成邀请码: code={}, tenantId={}, role={}, ttl={}d",
            saved.getCode(), saved.getTenantId(), saved.getRole(), ttl);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @Operation(summary = "查询邀请码列表（按状态或租户过滤）")
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<InviteCode>> list(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String status) {
        if (tenantId != null) {
            return ResponseEntity.ok(inviteRepo.findByTenantIdOrderByCreatedAtDesc(tenantId));
        }
        if (status != null) {
            return ResponseEntity.ok(inviteRepo.findByStatusOrderByCreatedAtDesc(status));
        }
        return ResponseEntity.ok(inviteRepo.findAll());
    }

    @Operation(summary = "查询单个邀请码")
    @GetMapping("/{code}")
    @Transactional(readOnly = true)
    public ResponseEntity<InviteCode> get(@PathVariable String code) {
        return inviteRepo.findByCode(code.toUpperCase())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "撤销邀请码")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        return inviteRepo.findById(id).<ResponseEntity<Void>>map(code -> {
            code.setStatus("CANCELLED");
            inviteRepo.save(code);
            log.info("撤销邀请码: id={}, code={}", id, code.getCode());
            return ResponseEntity.<Void>noContent().build();
        }).orElseGet(() -> ResponseEntity.<Void>notFound().build());
    }

    /**
     * 员工输入邀请码预校验：返回租户名/角色/TTL（注册页第一步用，不创建账号）
     */
    @Operation(summary = "邀请码预览（不消耗）")
    @GetMapping("/{code}/preview")
    @Transactional(readOnly = true)
    public ResponseEntity<?> preview(@PathVariable String code) {
        return inviteRepo.findByCode(code.toUpperCase())
            .map(invite -> {
                if (!"PENDING".equals(invite.getStatus())) {
                    return ResponseEntity.status(HttpStatus.GONE).body(
                        Map.of("error", "邀请码不可用，状态：" + invite.getStatus()));
                }
                if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(LocalDateTime.now())) {
                    return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "邀请码已过期"));
                }
                var tenant = tenantRepo.findById(invite.getTenantId()).orElse(null);
                return ResponseEntity.ok(Map.of(
                    "code", invite.getCode(),
                    "role", invite.getRole(),
                    "tenantId", invite.getTenantId(),
                    "tenantName", tenant != null ? tenant.getDisplayName() : "(已删除)",
                    "tenantCode", tenant != null ? tenant.getName() : "",
                    "note", invite.getNote() == null ? "" : invite.getNote(),
                    "expiresAt", invite.getExpiresAt()
                ));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 生成 8 位 Base32 风格码，去除易混字符（0/O/1/I），避免肉眼读错 */
    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LEN);
            for (int i = 0; i < CODE_LEN; i++) {
                sb.append(ALPHABET.charAt(rng.nextInt(ALPHABET.length())));
            }
            String candidate = sb.toString();
            if (inviteRepo.findByCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("邀请码生成冲突，重试 20 次仍未找到唯一码");
    }
}
