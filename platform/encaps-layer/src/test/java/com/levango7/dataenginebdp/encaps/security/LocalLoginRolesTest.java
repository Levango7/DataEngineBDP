package com.levango7.dataenginebdp.encaps.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地降级登录的角色声明测试（联调前提）。
 *
 * <p>回归背景：鉴权链路（JwtAuthFilter）只认 JWT 的 {@code realm_access.roles}，
 * 而本地降级登录只写了 {@code role: "admin"} → 该 claim 不被识别，权限兜底为 ROLE_USER，
 * 于是本地管理员访问 {@code /api/v1/tenants}、{@code /api/v1/invites} 一律 403，
 * 租户管理与邀请审批在本地根本无法验证（前端三页切真后被这个问题挡死）。</p>
 *
 * <p>本测试钉住两件事：登录 token 必须带 SUPER_ADMIN 角色声明；带上该 token 后
 * 平台管理端点不再 403。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.security.jwt.secret=dev-secret-key-change-in-production-at-least-256-bits",
        "app.security.jwt.issuer=shuqing-bigdata",
        "app.security.oidc.enabled=false",
        "app.security.local-auth.enabled=true",
        "app.security.local-auth.username=admin",
        "app.security.local-auth.password=Local-Test-Pwd-2026",
        "app.k8s.mock-enabled=true",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class LocalLoginRolesTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** 解出 JWT payload（不验签，只看声明）。 */
    private static JsonNode payload(String token) throws Exception {
        String segment = token.split("\\.")[1];
        byte[] decoded = Base64.getUrlDecoder().decode(segment);
        return new ObjectMapper().readTree(new String(decoded, StandardCharsets.UTF_8));
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Local-Test-Pwd-2026\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = new ObjectMapper().readTree(body);
        // ApiResponseAdvice 包装后业务体在 data 下；未包装时直接取 token
        JsonNode data = json.has("data") && json.get("data") != null ? json.get("data") : json;
        String token = data.path("token").asText();
        assertThat(token).as("登录应返回 token，实际响应: %s", body).isNotBlank();
        return token;
    }

    @Test
    @DisplayName("本地降级登录的 token 带 realm_access.roles=SUPER_ADMIN")
    void localLoginTokenCarriesRealmRoles() throws Exception {
        JsonNode claims = payload(login());

        assertThat(claims.path("tenantId").asText()).isEqualTo("platform-admin");
        assertThat(claims.path("realm_access").path("roles").isArray()).isTrue();
        assertThat(claims.path("realm_access").path("roles").get(0).asText()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    @DisplayName("带该 token 访问平台管理端点不再 403（原缺陷：一律 403）")
    void localAdminTokenCanCallTenantEndpoints() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/v1/tenants")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("匿名访问平台管理端点仍是 401（修复未削弱鉴权）")
    void anonymousStillRejected() throws Exception {
        mockMvc.perform(get("/api/v1/tenants"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("仅 ROLE_USER 的 token 访问平台管理端点仍是 403（不越权）")
    void plainUserTokenStillForbidden() throws Exception {
        // 用同一密钥签一个没有 SUPER_ADMIN 的 token
        String token = io.jsonwebtoken.Jwts.builder()
                .subject("someone")
                .claim("tenantId", "tenant-1")
                .claim("realm_access", java.util.Map.of("roles", java.util.List.of("USER")))
                .issuer("shuqing-bigdata")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "dev-secret-key-change-in-production-at-least-256-bits"
                                .getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/api/v1/tenants")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
