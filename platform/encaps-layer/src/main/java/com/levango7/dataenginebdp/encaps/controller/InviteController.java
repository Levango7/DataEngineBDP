package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.model.InviteCode;
import com.levango7.dataenginebdp.encaps.model.Tenant;
import com.levango7.dataenginebdp.encaps.repository.InviteCodeRepository;
import com.levango7.dataenginebdp.encaps.repository.TenantRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.LinkedHashMap;
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
 *
 * <p>安全控制（R8 修复）：
 * <ul>
 *   <li>生成/查询/撤销邀请码端点添加 {@code @PreAuthorize("hasRole('SUPER_ADMIN')")}，
 *       仅平台超管可操作，防止任意认证用户生成或遍历邀请码。</li>
 *   <li>list 端点添加租户隔离：从 {@link TenantContext} 读取当前租户 ID 强制过滤，
 *       防止跨租户遍历他租户邀请码。</li>
 *   <li>list 端点添加分页参数（page/pageSize），防止全量返回导致性能与信息泄露问题。</li>
 *   <li>create 端点 invitedBy 从 JWT（{@link TenantContext#getUserId()}）获取当前用户，
 *       不再硬编码为 {@code platform-admin}。</li>
 *   <li>preview 端点保持开放：员工注册时需要预校验邀请码。</li>
 * </ul></p>
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
    /** 平台管理员的特殊租户 ID（TenantContext 兜底值，非数字型）。 */
    private static final String PLATFORM_ADMIN_TENANT = "platform-admin";
    /** list 端点每页最大条数，防止过大分页导致性能问题。 */
    private static final int MAX_PAGE_SIZE = 200;
    /** list 端点默认每页条数。 */
    private static final int DEFAULT_PAGE_SIZE = 50;
    /**
     * 租户键长度上限，与 {@code invite_codes.tenant_id}/{@code user_registrations.tenant_id}
     * 的 varchar(255) 列宽一致。
     */
    private static final int MAX_TENANT_KEY_LENGTH = 255;
    /** 租户键无法映射回本平台 tenants 表时的展示占位（外部租户，非“已删除”）。 */
    private static final String EXTERNAL_TENANT_LABEL = "(外部租户)";
    private final SecureRandom rng = new SecureRandom();

    private final InviteCodeRepository inviteRepo;
    private final TenantRepository tenantRepo;

    public record CreateRequest(
            @NotBlank @Size(max = 255) String tenantId,
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

    @Operation(summary = "生成邀请码（SUPER_ADMIN）")
    @PostMapping
    @Transactional
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<InviteCode> create(@Valid @RequestBody CreateRequest req) {
        // 台账 #56 方案③：租户键是字符串业务键，不再要求它对应 tenants.id 的自增主键。
        // 原先的 tenantRepo.existsById(tenantId) 是拿业务键查 bigint 主键，语义已断
        // （Keycloak 的 sub UUID / platform-admin 这类键在 tenants 表里本就没有行），
        // 对它们一律 404 会让整个邀请注册域对非数字租户不可用 —— 而配额/工作空间域
        // 在 V2__tenant_key_as_string.sql 之后已经能用这些键了。
        // 改按“键的形态”校验：非空 + 长度上限（与 varchar(255) 列宽一致）。
        // 存在性不再由本平台裁决；真正的闸门在下游 —— 注册申请仍需超管审批才能落地
        // （RegistrationController.decide 的 canAccessTenant + @PreAuthorize）。
        String tenantKey = requireTenantKey(req.tenantId());
        int ttl = (req.ttlDays() == null || req.ttlDays() <= 0) ? DEFAULT_TTL_DAYS : req.ttlDays();
        // R8 修复：invitedBy 从 JWT（TenantContext）获取当前用户，不再硬编码 platform-admin
        String invitedBy = currentUserId();
        InviteCode code = new InviteCode();
        code.setCode(generateUniqueCode());
        code.setTenantId(tenantKey);
        code.setRole(req.role());
        code.setInvitedBy(invitedBy);
        code.setNote(req.note());
        code.setStatus("PENDING");
        code.setCreatedAt(LocalDateTime.now());
        code.setExpiresAt(LocalDateTime.now().plusDays(ttl));
        InviteCode saved = inviteRepo.save(code);
        log.info("生成邀请码: code={}, tenantId={}, role={}, ttl={}d, invitedBy={}",
            saved.getCode(), saved.getTenantId(), saved.getRole(), ttl, invitedBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @Operation(summary = "查询邀请码列表（SUPER_ADMIN，租户隔离+分页）")
    @GetMapping
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> list(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {

        // 分页参数校验与上限保护
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        if (pageSize <= 0) {
            safeSize = DEFAULT_PAGE_SIZE;
        }
        PageRequest pageRequest = PageRequest.of(safePage, safeSize, Sort.by("createdAt").descending());

        // R8 修复：租户隔离——从 TenantContext 读取当前租户 ID 强制过滤
        String effectiveTenantId = resolveTenantForList(tenantId);
        if (effectiveTenantId == null) {
            // 无法确定租户范围（缺少租户上下文且非平台管理员），拒绝访问
            return ResponseEntity.status(403).body(Map.of(
                    "error", "缺少租户上下文，无法确定查询范围",
                    "messageKey", "error.auth.forbidden"));
        }

        Page<InviteCode> result;
        if (status != null && !status.isBlank()) {
            // 同时按租户和状态过滤：先按租户查分页，再在内存中按状态过滤
            // （避免引入复杂 Specification，保持简单）
            result = inviteRepo.findByTenantIdOrderByCreatedAtDesc(effectiveTenantId, pageRequest);
            // 内存过滤状态（分页后）：如果状态过滤后条数变化，前端应理解 total 是租户维度总数
            List<InviteCode> filtered = result.getContent().stream()
                    .filter(c -> status.equals(c.getStatus()))
                    .toList();
            Map<String, Object> body = pageBody(filtered, result, safePage, safeSize);
            return ResponseEntity.ok(body);
        }
        result = inviteRepo.findByTenantIdOrderByCreatedAtDesc(effectiveTenantId, pageRequest);
        return ResponseEntity.ok(pageBody(result.getContent(), result, safePage, safeSize));
    }

    @Operation(summary = "查询单个邀请码（SUPER_ADMIN）")
    @GetMapping("/{code}")
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<InviteCode> get(@PathVariable String code) {
        return inviteRepo.findByCode(code.toUpperCase())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "撤销邀请码（SUPER_ADMIN）")
    @DeleteMapping("/{id}")
    @Transactional
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
     *
     * <p>此端点保持开放（无 @PreAuthorize），供员工在 /register 页面预校验邀请码。</p>
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
                var tenant = findPlatformTenantOrNull(invite.getTenantId());
                return ResponseEntity.ok(Map.of(
                    "code", invite.getCode(),
                    "role", invite.getRole(),
                    "tenantId", invite.getTenantId(),
                    // 台账 #56 方案③：租户键是业务键，不再等于 tenants.id。
                    // 能映射回本平台租户主键的（存量数字键）仍显示真实租户名；
                    // 映射不回的（Keycloak sub / platform-admin 这类外部键）
                    // 显示为外部租户，不伪造名称。
                    "tenantName", tenant != null ? tenant.getDisplayName() : EXTERNAL_TENANT_LABEL,
                    "tenantCode", tenant != null ? tenant.getName() : "",
                    "note", invite.getNote() == null ? "" : invite.getNote(),
                    "expiresAt", invite.getExpiresAt()
                ));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ==================== 安全辅助 ====================

    /**
     * 校验并归一化请求传入的租户业务键。
     *
     * <p>口径与全仓既有的 35 张 {@code varchar} {@code tenant_id} 表一致
     * （见 {@code encaps-tenant} 的 {@code currentTenantKey()}）：**只校验非空与长度**，
     * 不做字符集限制。</p>
     *
     * <p>刻意不使用 DNS-1123 那套字符集（{@code K8sTenantKeys}）—— 它是
     * “K8s 名称/标签值”场景的约束（大写字母、下划线、点号均不可），
     * 用在纯存储列上属过度收窄：会拒绝大写形态的 Keycloak {@code sub} UUID，
     * 把本次要放开的键重新关上。</p>
     *
     * @param tenantKey 请求传入的租户键
     * @return 去空白后的租户键
     * @throws org.springframework.web.server.ResponseStatusException 为空或超长时返回 400
     */
    private String requireTenantKey(String tenantKey) {
        if (tenantKey == null || tenantKey.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "tenantId 不能为空");
        }
        String trimmed = tenantKey.trim();
        if (trimmed.length() > MAX_TENANT_KEY_LENGTH) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "tenantId 长度不得超过 " + MAX_TENANT_KEY_LENGTH);
        }
        return trimmed;
    }

    /**
     * 尝试把租户业务键映射回本平台 {@code Tenant} 实体，映射不到则返回 {@code null}。
     *
     * <p>存量数字键（{@code "1"}）能解析回 {@code tenants.id}，显示名行为不变；
     * 非数字的外部键（Keycloak {@code sub} / {@code platform-admin}）在 tenants 表里
     * 本就没有行，返回 {@code null} 由调用方降级展示，**不因取不到就报错** ——
     * 否则等于把刚放开的非数字键在预览端点上又关掉。</p>
     */
    private Tenant findPlatformTenantOrNull(String tenantKey) {
        if (tenantKey == null || tenantKey.isBlank()) {
            return null;
        }
        try {
            return tenantRepo.findById(Long.valueOf(tenantKey.trim())).orElse(null);
        } catch (NumberFormatException e) {
            // 外部租户键（非数字）不是错误：本平台没有对应 tenants 行，属预期情形。
            return null;
        }
    }

    /**
     * 从 TenantContext 获取当前用户 ID（来自 JWT sub claim）。
     *
     * @return 当前用户 ID；若上下文缺失则回退 {@code unknown}
     */
    private String currentUserId() {
        String userId = TenantContext.getUserId();
        return (userId == null || userId.isBlank()) ? "unknown" : userId;
    }

    /**
     * 为 list 端点解析有效的租户业务键（租户隔离核心）。
     *
     * <p>台账 #56 方案③：租户键是字符串，参数与上下文直接按字符串比较，
     * 不再做 {@code Long.valueOf} 往返 —— 那会把 Keycloak {@code sub} UUID 这类
     * 合法键误判为“无法解析”而 403。</p>
     *
     * <p>逻辑：
     * <ul>
     *   <li>从 TenantContext 读取当前租户键（String）。</li>
     *   <li>若为 {@code platform-admin}：使用请求传入的 tenantId 参数，
     *       允许平台管理员查看指定租户；未传则返回 null（拒绝访问，防止全量遍历）。</li>
     *   <li>其他租户：强制使用上下文中的键过滤，忽略请求传入的 tenantId 参数
     *       （防止越权遍历他租户邀请码）；若传入值与上下文不匹配，记录警告。</li>
     *   <li>若上下文缺失：返回 null（拒绝访问）。</li>
     * </ul></p>
     *
     * @param requestedTenantId 请求传入的 tenantId 参数（可为 null）
     * @return 用于过滤的有效租户业务键；null 表示无法确定范围，应拒绝访问
     */
    private String resolveTenantForList(String requestedTenantId) {
        String ctxTenantId = TenantContext.getTenantId();
        if (ctxTenantId == null || ctxTenantId.isBlank()) {
            // 缺少租户上下文，拒绝访问
            return null;
        }
        // 平台管理员：允许按传入的 tenantId 过滤
        if (PLATFORM_ADMIN_TENANT.equals(ctxTenantId)) {
            return requestedTenantId; // 可能为 null（拒绝全量遍历）
        }
        // 普通租户：强制使用上下文中的租户键
        if (requestedTenantId != null && !requestedTenantId.equals(ctxTenantId)) {
            log.warn("租户隔离拦截: ctxTenant={}, requestedTenant={}", ctxTenantId, requestedTenantId);
        }
        return ctxTenantId;
    }

    /** 构造分页响应体。 */
    private Map<String, Object> pageBody(List<InviteCode> items, Page<InviteCode> page, int pageNo, int pageSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", page.getTotalElements());
        body.put("totalPages", page.getTotalPages());
        body.put("page", pageNo);
        body.put("pageSize", pageSize);
        return body;
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
