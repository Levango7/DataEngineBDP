package com.levango7.dataenginebdp.encaps.security;

import com.levango7.dataenginebdp.encaps.model.UserRegistration;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册审批端点的角色与租户隔离测试（真实 JWT + MockMvc 全链路）。
 *
 * <p>回归背景：{@code /api/v1/registrations} 的 list 与 decision 曾完全没有角色校验，
 * 也没有按租户过滤——任意登录用户可 {@code findAll()} 列出所有租户的注册申请
 * （含姓名/邮箱/部门/工号），并可跨租户审批通过。本测试锁定修复后的四条不变量：</p>
 * <ol>
 *   <li>无 token / 仅 ROLE_USER 一律拒绝；</li>
 *   <li>租户管理员只能看到并审批本租户的记录，传入他人 tenantId 不能越界；</li>
 *   <li>跨租户审批返回 404（不泄露记录存在性）；</li>
 *   <li>全域待审视图仅平台超管可达。</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.security.jwt.secret=dev-secret-key-change-in-production-at-least-256-bits",
        "app.security.jwt.issuer=shuqing-bigdata",
        "app.security.oidc.enabled=false",
        "app.k8s.mock-enabled=true",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RegistrationTenantIsolationTest {

    private static final String SECRET = "dev-secret-key-change-in-production-at-least-256-bits";
    private static final String ISSUER = "shuqing-bigdata";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRegistrationRepository regRepo;

    private MockMvc mockMvc;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        regRepo.deleteAll();
        regRepo.save(registration("alice", 1L));
        regRepo.save(registration("bob", 2L));
    }

    private static UserRegistration registration(String username, Long tenantId) {
        UserRegistration reg = new UserRegistration();
        reg.setUsername(username);
        reg.setEmail(username + "@example.com");
        reg.setFullName(username);
        reg.setDepartment("数据部");
        reg.setEmployeeId("E-" + username);
        reg.setRole("USER");
        reg.setTenantId(tenantId);
        reg.setInviteCode("AAAAAAAA");
        reg.setStatus("PENDING");
        reg.setCreatedAt(LocalDateTime.now());
        return reg;
    }

    /** 签发带 realm_access.roles 的 JWT（角色名不带 ROLE_ 前缀，由过滤器补全）。 */
    private String token(String subject, String tenantId, String... roles) {
        return Jwts.builder()
                .subject(subject)
                .claim("tenantId", tenantId)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuer(ISSUER)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(signingKey)
                .compact();
    }

    private String tokenOf(String username) {
        return regRepo.findByUsername(username).orElseThrow().getId().toString();
    }

    // ---------- 1. 未认证 / 角色不足 ----------

    @Test
    @DisplayName("无 token 查询注册列表 → 401")
    void list_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/registrations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("仅 ROLE_USER 查询注册列表 → 403（历史缺陷：曾返回全部租户）")
    void list_rejectsPlainUser() throws Exception {
        mockMvc.perform(get("/api/v1/registrations")
                        .header("Authorization", "Bearer " + token("u1", "1", "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("仅 ROLE_USER 审批 → 403")
    void decision_rejectsPlainUser() throws Exception {
        mockMvc.perform(post("/api/v1/registrations/" + tokenOf("alice") + "/decision")
                        .header("Authorization", "Bearer " + token("u1", "1", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"note\":\"ok\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------- 2. 租户隔离 ----------

    @Test
    @DisplayName("租户 1 管理员只能看到租户 1 的注册申请")
    void list_scopedToCallerTenant() throws Exception {
        mockMvc.perform(get("/api/v1/registrations")
                        .header("Authorization", "Bearer " + token("adm1", "1", "TENANT_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].username").value("alice"))
                .andExpect(jsonPath("$.data[0].tenantId").value(1));
    }

    @Test
    @DisplayName("租户 1 管理员传 tenantId=2 也不能越界读取租户 2")
    void list_cannotWidenToOtherTenant() throws Exception {
        mockMvc.perform(get("/api/v1/registrations")
                        .param("tenantId", "2")
                        .header("Authorization", "Bearer " + token("adm1", "1", "TENANT_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].username").value("alice"));
    }

    @Test
    @DisplayName("租户 1 管理员审批租户 2 的申请 → 404（不泄露存在性）")
    void decision_crossTenant_returnsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/registrations/" + tokenOf("bob") + "/decision")
                        .header("Authorization", "Bearer " + token("adm1", "1", "TENANT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"note\":\"越权尝试\"}"))
                .andExpect(status().isNotFound());

        // 目标记录状态未被改动
        org.assertj.core.api.Assertions.assertThat(
                regRepo.findByUsername("bob").orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("租户 1 管理员审批本租户申请 → 200，审批人取自 JWT subject")
    void decision_ownTenant_succeeds() throws Exception {
        mockMvc.perform(post("/api/v1/registrations/" + tokenOf("alice") + "/decision")
                        .header("Authorization", "Bearer " + token("adm1", "1", "TENANT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"note\":\"欢迎加入\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvedBy").value("adm1"));
    }

    // ---------- 3. 平台超管全域视图 ----------

    @Test
    @DisplayName("平台超管不传 tenantId → 全域待审视图")
    void list_platformAdmin_seesAllTenants() throws Exception {
        mockMvc.perform(get("/api/v1/registrations")
                        .header("Authorization",
                                "Bearer " + token("root", "platform-admin", "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("平台超管可审批任意租户的申请")
    void decision_platformAdmin_crossTenantOk() throws Exception {
        mockMvc.perform(post("/api/v1/registrations/" + tokenOf("bob") + "/decision")
                        .header("Authorization",
                                "Bearer " + token("root", "platform-admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false,\"note\":\"资料不全\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.approvedBy").value("root"));
    }
}
