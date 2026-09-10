package com.levango7.dataenginebdp.encaps.controller;

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
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/registrations")
@Tag(name = "用户注册审批", description = "提交注册、查询待审、批准与拒绝")
public class RegistrationController {

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

    @Operation(summary = "查询注册列表（按状态/租户过滤）")
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<UserRegistration>> list(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String status) {
        if (tenantId != null) {
            return ResponseEntity.ok(regRepo.findByTenantIdOrderByCreatedAtDesc(tenantId));
        }
        if (status != null) {
            return ResponseEntity.ok(regRepo.findByStatusOrderByCreatedAtDesc(status));
        }
        return ResponseEntity.ok(regRepo.findAll());
    }

    @Operation(summary = "审批（批准/拒绝）")
    @PostMapping("/{id}/decision")
    @Transactional
    public ResponseEntity<?> decide(@PathVariable Long id, @Valid @RequestBody ApproveRequest req) {
        return regRepo.findById(id).map(reg -> {
            if (!"PENDING".equals(reg.getStatus())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "该申请已审批（状态：" + reg.getStatus() + "）"));
            }
            reg.setStatus(Boolean.TRUE.equals(req.approved()) ? "APPROVED" : "REJECTED");
            reg.setApprovedBy("tenant-admin"); // 真实环境从 JWT 取
            reg.setApproveNote(req.note());
            reg.setApprovedAt(LocalDateTime.now());
            UserRegistration saved = regRepo.save(reg);
            log.info("注册审批: id={}, username={}, decision={}",
                saved.getId(), saved.getUsername(), saved.getStatus());
            return ResponseEntity.ok(saved);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
