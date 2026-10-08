package com.levango7.dataenginebdp.encaps.security;

import com.levango7.dataenginebdp.encaps.model.InviteCode;
import com.levango7.dataenginebdp.encaps.model.UserRegistration;
import com.levango7.dataenginebdp.encaps.repository.InviteCodeRepository;
import com.levango7.dataenginebdp.encaps.repository.TenantRepository;
import com.levango7.dataenginebdp.encaps.repository.UserRegistrationRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 台账 #56 方案③的契约测试：invite_codes / user_registrations 的 tenant_id 是字符串业务键。
 *
 * <p>背景：批次 B（PR #366）把 encaps-tenant 的 quotas/workspaces 从 bigint 放宽成
 * varchar(255)，其迁移 {@code V2__tenant_key_as_string.sql} 的注释里把
 * 「encaps-layer 的 invite_codes / user_registrations」一并点名为同批应改的对象，
 * 但实际只做了那两张 —— 本测试锁住补齐后的行为。</p>
 *
 * <p>核心事实：运行时租户键就是字符串 ——
 * {@code AuthController.localLogin()} 签发 token 时写死
 * {@code .claim("tenantId", "platform-admin")}，Keycloak 主路径不放该 claim、
 * token 原样透传（{@code sub} 为 UUID）。改动前本组接口的 {@code tenantId} 收参是
 * {@code Long}，非数字键在 Jackson 绑定阶段即 400，邀请码发不出来 ⇒ 注册申请
 * （归属由邀请码决定）随之不可用；而配额/工作空间域那时已经能用这些键了。</p>
 *
 * <p>下面第 1、2 条是该语义的回归位：任一被改回 {@code Long} 收参或把
 * {@code existsById} 校验加回来，本类必须转红。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.security.jwt.secret=dev-secret-key-change-in-production-at-least-256-bits",
        "app.security.jwt.issuer=shuqing-bigdata",
        "app.security.oidc.enabled=false",
        "app.k8s.mock-enabled=true",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class InviteRegistrationTenantKeyStringTest {

    private static final String SECRET = "dev-secret-key-change-in-production-at-least-256-bits";
    private static final String ISSUER = "shuqing-bigdata";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private InviteCodeRepository inviteRepo;

    @Autowired
    private UserRegistrationRepository regRepo;

    @Autowired
    private TenantRepository tenantRepo;

    private MockMvc mockMvc;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        inviteRepo.deleteAll();
        regRepo.deleteAll();
    }

    // ------------------------------------------------------------------
    // 1. 非数字租户键可发邀请码（改动前：Jackson 绑定到 Long 即 400）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("生成邀请码：非数字租户键 platform-admin 可用（台账 #56 方案③ 的核心回归位）")
    void createInvite_withNonNumericTenantKey_isAccepted() throws Exception {
        // 该键刻意不在 tenantRepo 里：改动前的 existsById 校验会 404。
        assertThat(tenantRepo.existsById(1L)).isFalse();

        mockMvc.perform(post("/api/v1/invites")
                        .header("Authorization", "Bearer " + token("root", "platform-admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"platform-admin","role":"USER","ttlDays":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tenantId").value("platform-admin"));

        InviteCode saved = inviteRepo.findAll().get(0);
        assertThat(saved.getTenantId()).isEqualTo("platform-admin");
    }

    @Test
    @DisplayName("生成邀请码：Keycloak sub 形态的 UUID 租户键可用（大小写混合形态一并覆盖）")
    void createInvite_withUuidTenantKey_isAccepted() throws Exception {
        String uuidKey = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

        mockMvc.perform(post("/api/v1/invites")
                        .header("Authorization", "Bearer " + token("root", uuidKey, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","role":"USER","ttlDays":1}
                                """.formatted(uuidKey)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tenantId").value(uuidKey));
    }

    @Test
    @DisplayName("生成邀请码：存量数字租户键以文本形态往返，行为不变（'1' 而非 1）")
    void createInvite_withNumericTenantKey_keepsBackwardCompatibility() throws Exception {
        mockMvc.perform(post("/api/v1/invites")
                        .header("Authorization", "Bearer " + token("root", "platform-admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"1","role":"USER","ttlDays":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tenantId").value("1"));
    }

    // ------------------------------------------------------------------
    // 2. 非法键 fail-closed（存在性校验换成形态校验，但闸门不撤）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("生成邀请码：空白 tenantId 被拒（形态校验，非静默接受）")
    void createInvite_withBlankTenantKey_isRejected() throws Exception {
        mockMvc.perform(post("/api/v1/invites")
                        .header("Authorization", "Bearer " + token("root", "platform-admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"   ","role":"USER","ttlDays":1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("生成邀请码：超长 tenantId（>255，列宽上限）被拒")
    void createInvite_withOverlongTenantKey_isRejected() throws Exception {
        String tooLong = "t".repeat(256);

        mockMvc.perform(post("/api/v1/invites")
                        .header("Authorization", "Bearer " + token("root", "platform-admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","role":"USER","ttlDays":1}
                                """.formatted(tooLong)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // 3. 租户隔离仍按字符串比较（改动前两侧都走 Long.valueOf，非数字上下文直接 403）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("注册列表：非数字租户上下文的 TENANT_ADMIN 能按本租户键过滤（改动前 403）")
    void listRegistrations_withNonNumericTenantContext_isScopedToCallerTenant() throws Exception {
        regRepo.save(registration("alice", "tenant-acme"));
        regRepo.save(registration("bob", "tenant-other"));

        mockMvc.perform(get("/api/v1/registrations")
                        .header("Authorization", "Bearer " + token("adm", "tenant-acme", "TENANT_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].username").value("alice"))
                .andExpect(jsonPath("$.data[0].tenantId").value("tenant-acme"));
    }

    @Test
    @DisplayName("注册列表：非数字租户上下文传他人 tenantId 仍不能越界")
    void listRegistrations_withForeignTenantKey_isBlocked() throws Exception {
        regRepo.save(registration("bob", "tenant-other"));

        mockMvc.perform(get("/api/v1/registrations")
                        .param("tenantId", "tenant-other")
                        .header("Authorization", "Bearer " + token("adm", "tenant-acme", "TENANT_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("注册审批：非数字租户上下文的 TENANT_ADMIN 能审批本租户记录（跨租户仍 404）")
    void decideRegistration_withNonNumericTenantContext_isScopedToCallerTenant() throws Exception {
        UserRegistration mine = registration("alice", "tenant-acme");
        UserRegistration theirs = registration("bob", "tenant-other");
        regRepo.save(mine);
        regRepo.save(theirs);

        mockMvc.perform(post("/api/v1/registrations/" + mine.getId() + "/decision")
                        .header("Authorization", "Bearer " + token("adm", "tenant-acme", "TENANT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approved":true}
                                """))
                .andExpect(status().isOk());

        // 跨租户：仍必须是 404（不泄露"存在但不属于你"）
        mockMvc.perform(post("/api/v1/registrations/" + theirs.getId() + "/decision")
                        .header("Authorization", "Bearer " + token("adm", "tenant-acme", "TENANT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approved":true}
                                """))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 4. 邀请码预览：外部租户键降级展示，不报错（曾因取不到 tenants 行而被误当作错误）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("邀请码预览：外部租户键降级展示租户名，端点不失败")
    void previewInvite_withExternalTenantKey_degradesGracefully() throws Exception {
        InviteCode invite = new InviteCode();
        invite.setCode("PREVIEWX");
        invite.setTenantId("platform-admin");
        invite.setRole("USER");
        invite.setStatus("PENDING");
        invite.setCreatedAt(LocalDateTime.now());
        invite.setExpiresAt(LocalDateTime.now().plusDays(1));
        inviteRepo.save(invite);

        mockMvc.perform(get("/api/v1/invites/PREVIEWX/preview")
                        .header("Authorization", "Bearer " + token("viewer", "tenant-acme", "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value("platform-admin"))
                .andExpect(jsonPath("$.data.tenantName").value("(外部租户)"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static UserRegistration registration(String username, String tenantKey) {
        UserRegistration reg = new UserRegistration();
        reg.setUsername(username);
        reg.setEmail(username + "@example.com");
        reg.setFullName(username);
        reg.setDepartment("数据部");
        reg.setEmployeeId("E-" + username);
        reg.setRole("USER");
        reg.setTenantId(tenantKey);
        reg.setInviteCode("AAAAAAAA");
        reg.setStatus("PENDING");
        reg.setCreatedAt(LocalDateTime.now());
        return reg;
    }

    private String token(String subject, String tenantKey, String... roles) {
        return Jwts.builder()
                .subject(subject)
                .claim("tenantId", tenantKey)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuer(ISSUER)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(signingKey)
                .compact();
    }
}
