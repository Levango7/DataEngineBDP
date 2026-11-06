package com.levango7.dataenginebdp.encaps.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * 登录响应体的契约守卫。
 *
 * <p>前端 {@code User} 类型把 {@code tenantId} 与 {@code roles} 都声明为必填
 * （frontend/src/api/types.ts:70/72），路由守卫据此拦下 requiresRole 的页面
 * （frontend/src/router/index.ts:529-531）。本仓登录端点的两条分支以前都只回
 * id/username/nickname/email ⇒ 前端拿到的角色集合恒为空，任何受限页都会被弹回工作台。
 * 这里锁住"两条分支都必须回这两个字段，且与签进 token 的那一份同源"。</p>
 */
class AuthControllerLoginResponseTest {

    private static final String SECRET = "test-secret-key-for-login-contract-at-least-256-bits";
    private static final String ISSUER = "shuqing-bigdata";

    private AuthController newLocalAuthController() {
        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "tokenUri", "");
        ReflectionTestUtils.setField(controller, "clientId", "sq-console");
        ReflectionTestUtils.setField(controller, "localAuthEnabled", true);
        ReflectionTestUtils.setField(controller, "localUsername", "admin");
        ReflectionTestUtils.setField(controller, "localPassword", "admin");
        ReflectionTestUtils.setField(controller, "localAuthRoles", List.of("SUPER_ADMIN"));
        ReflectionTestUtils.setField(controller, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(controller, "jwtIssuer", ISSUER);
        return controller;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> userData(ResponseEntity<?> resp) {
        return (Map<String, Object>) ((Map<String, Object>) resp.getBody()).get("user");
    }

    @Test
    @DisplayName("本地降级分支必须返回 tenantId 与 roles，且与签进 JWT 的那一份同源")
    void localBranch_returnsTenantIdAndRoles() throws Exception {
        AuthController controller = newLocalAuthController();

        ResponseEntity<?> resp = controller.login(new AuthController.LoginRequest("admin", "admin", null));

        Map<String, Object> user = userData(resp);
        assertThat(user).containsEntry("tenantId", "platform-admin");
        assertThat((List<String>) user.get("roles")).containsExactly("SUPER_ADMIN");

        // 同源校验：响应体里的两个字段必须与 token claim 完全一致，不能各写一份
        String token = (String) ((Map<String, Object>) resp.getBody()).get("token");
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .requireIssuer(ISSUER).build().parseSignedClaims(token).getPayload();
        assertThat(claims.get("tenantId", String.class)).isEqualTo(user.get("tenantId"));
        assertThat(claims.get("realm_access", Map.class).get("roles"))
                .isEqualTo(user.get("roles"));
    }

    @Test
    @DisplayName("Keycloak 分支必须把 token 里的 tenantId 与 realm_access.roles 透出到响应体")
    void keycloakBranch_projectsClaimsIntoUser() {
        AuthController controller = newLocalAuthController();
        // 走主路径：给一个可达的 tokenUri 并由 MockRestServiceServer 应答
        ReflectionTestUtils.setField(controller, "tokenUri", "http://keycloak.invalid/realms/sq/protocol/openid-connect/token");

        String payload = "{\"sub\":\"kc-user-1\",\"preferred_username\":\"tenant-admin\","
                + "\"name\":\"租户管理员\",\"email\":\"t@example.cn\","
                + "\"tenantId\":\"42\",\"realm_access\":{\"roles\":[\"TENANT_ADMIN\",\"USER\"]}}";
        String accessToken = base64Url("{\"alg\":\"RS256\"}") + "."
                + base64Url(payload) + "." + base64Url("sig");
        String kcBody = "{\"access_token\":\"" + accessToken + "\",\"refresh_token\":\"r1\","
                + "\"expires_in\":3600,\"token_type\":\"Bearer\"}";

        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(controller, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(method(HttpMethod.POST))
                .andRespond(withSuccess(kcBody, MediaType.APPLICATION_JSON));

        ResponseEntity<?> resp = controller.login(
                new AuthController.LoginRequest("tenant-admin", "pw", null));

        Map<String, Object> user = userData(resp);
        assertThat(user).containsEntry("id", "kc-user-1");
        assertThat(user).containsEntry("tenantId", "42");
        assertThat((List<String>) user.get("roles")).containsExactly("TENANT_ADMIN", "USER");
        server.verify();
    }

    @Test
    @DisplayName("token 里没有角色声明时返回空列表而不是缺键（前端 roles: string[] 不可为 undefined）")
    void keycloakBranch_withoutRolesClaim_returnsEmptyList() {
        AuthController controller = newLocalAuthController();
        ReflectionTestUtils.setField(controller, "tokenUri", "http://keycloak.invalid/token");

        String payload = "{\"sub\":\"u2\",\"preferred_username\":\"u2\"}";
        String accessToken = base64Url("{\"alg\":\"RS256\"}") + "." + base64Url(payload) + ".sig";
        String kcBody = "{\"access_token\":\"" + accessToken + "\",\"expires_in\":3600}";

        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(controller, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(method(HttpMethod.POST))
                .andRespond(withSuccess(kcBody, MediaType.APPLICATION_JSON));

        Map<String, Object> user = userData(
                controller.login(new AuthController.LoginRequest("u2", "pw", null)));

        assertThat(user.get("roles")).isInstanceOf(List.class);
        assertThat((List<String>) user.get("roles")).isEmpty();
        assertThat(user).containsEntry("tenantId", "");
        server.verify();
    }

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    // ============ POST /me（2026-11-06 审计 #117 P0-2） ============

    /**
     * 前端启动时 hydrateUser 调 POST /auth/me 恢复会话（前端无 roles 时按空 roles 判权）。
     * 契约：从 Bearer token payload 解析，tenantId/roles 与 login 分支同源同构。
     */
    @Test
    @DisplayName("/me 必须从 Bearer token 解析出 tenantId 与 roles（同 login 契约）")
    void me_projectsTokenClaims() throws Exception {
        AuthController controller = new AuthController();
        String payload = """
                {"sub":"u-42","preferred_username":"zhangsan","email":"z@example.com",
                "name":"张三","tenantId":"t-99","realm_access":{"roles":["SUPER_ADMIN","USER"]}}
                """;
        String bearer = "Bearer " + base64Url("{\"alg\":\"none\"}") + "." + base64Url(payload) + ".sig";

        ResponseEntity<?> resp = controller.me(bearer);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) resp.getBody();
        assertThat(user).containsEntry("id", "u-42");
        assertThat(user).containsEntry("username", "zhangsan");
        assertThat(user).containsEntry("tenantId", "t-99");
        assertThat((List<String>) user.get("roles")).containsExactly("SUPER_ADMIN", "USER");
    }

    @Test
    @DisplayName("/me 无 Bearer 头必须 401（不静默返回空用户）")
    void me_withoutBearer_returns401() {
        AuthController controller = new AuthController();

        ResponseEntity<?> noHeader = controller.me(null);
        assertThat(noHeader.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<?> badHeader = controller.me("Basic abc");
        assertThat(badHeader.getStatusCode().value()).isEqualTo(401);
    }
}
