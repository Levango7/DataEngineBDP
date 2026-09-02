package com.levango7.dataenginebdp.encaps.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.encaps.security.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证端点（Keycloak OIDC direct grant 代理；可选本地降级模式）。
 *
 * <p>前端 Login.vue 调 {@code POST /api/v1/auth/login}，
 * 本端点代理 Keycloak 的 password flow（directAccessGrants），
 * 返回前端 {@code LoginResult} 契约（token/expiresIn/refreshToken/user）。</p>
 *
 * <p>本地降级模式（{@code APP_SECURITY_LOCAL_AUTH_ENABLED=true}，
 * 默认 false）：无 Keycloak 的环境（集成测试栈 / 本地 E2E / 离线演示）
 * 用内置 admin 账号直接签发平台 JWT（HMAC，与 JwtAuthFilter 同密钥）。
 * 生产部署必须保持关闭（Keycloak 不可达时返回 503 而非降级）。</p>
 *
 * <p>配置（环境变量）：
 * <ul>
 *   <li>KEYCLOAK_TOKEN_URI: Keycloak token 端点（默认本地 dev 实例）</li>
 *   <li>KEYCLOAK_CLIENT_ID: 客户端 ID（默认 sq-console）</li>
 *   <li>APP_SECURITY_LOCAL_AUTH_ENABLED: 本地降级开关（默认 false）</li>
 *   <li>LOCAL_AUTH_USERNAME / LOCAL_AUTH_PASSWORD: 降级账号（默认 admin/admin）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
@Tag(name = "认证管理", description = "用户认证与授权端点")
public class AuthController {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = buildRestTemplate();

    @Value("${app.security.oidc.token-uri:}")
    private String tokenUri;

    @Value("${app.security.oidc.client-id:sq-console}")
    private String clientId;

    /** 本地降级认证开关（默认关；仅无 Keycloak 的测试/演示环境开启）。 */
    @Value("${app.security.local-auth.enabled:false}")
    private boolean localAuthEnabled;

    @Value("${app.security.local-auth.username:admin}")
    private String localUsername;

    @Value("${app.security.local-auth.password:admin}")
    private String localPassword;

    /** 与 JwtAuthFilter 同源的签名密钥（app.security.jwt.secret → JWT_SECRET 环境变量）。 */
    @Value("${app.security.jwt.secret:}")
    private String jwtSecret;

    /** 与 JwtAuthFilter 一致的 issuer 声明。 */
    @Value("${app.security.jwt.issuer:shuqing-bigdata}")
    private String jwtIssuer;


    /**
     * 登录请求体（对齐前端 LoginParams）。
     */
    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password,
            String captcha) {
    }

    /**
     * 登录：代理 Keycloak direct grant。
     *
     * @param req 用户名密码
     * @return LoginResult（token/expiresIn/refreshToken/user）
     */
    @AuditLog(action = "LOGIN", resource = "auth")
    @Operation(summary = "用户登录", description = "代理 Keycloak direct grant（password flow），"
            + "返回前端 LoginResult 契约（token/expiresIn/refreshToken/user）")

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        try {
            // 1. 调 Keycloak token 端点（password flow）
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            String body = "grant_type=password"
                    + "&client_id=" + clientId
                    + "&username=" + urlEncode(req.username())
                    + "&password=" + urlEncode(req.password());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> resp = restTemplate.postForEntity(tokenUri, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("Keycloak 登录失败: HTTP {}", resp.getStatusCode());
                return ResponseEntity.status(resp.getStatusCode())
                        .body(Map.of("error", "认证失败（用户名或密码错误）"));
            }

            // 2. 解析 token 响应
            JsonNode tokenResp = objectMapper.readTree(resp.getBody());
            String accessToken = tokenResp.path("access_token").asText();
            String refreshToken = tokenResp.path("refresh_token").asText("");
            long expiresIn = tokenResp.path("expires_in").asLong(3600);

            // 3. 从 access_token 解析用户信息
            JsonNode payload = decodeJwtPayload(accessToken);
            String username = payload.path("preferred_username").asText(req.username());
            String email = payload.path("email").asText("");

            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", payload.path("sub").asText());
            user.put("username", username);
            user.put("nickname", payload.path("name").asText(username));
            user.put("email", email);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("token", accessToken);
            result.put("expiresIn", expiresIn);
            result.put("refreshToken", refreshToken);
            result.put("user", user);
            return ResponseEntity.ok(result);
        } catch (RestClientException e) {
            log.error("Keycloak 不可达: {}", e.getMessage());
            // 本地降级：测试/演示环境（无 Keycloak）显式开启后用内置账号签发平台 JWT
            if (localAuthEnabled) {
                Map<String, Object> local = localLogin(req);
                if (local != null) {
                    return ResponseEntity.ok(local);
                }
                return ResponseEntity.status(401)
                        .body(Map.of("error", "认证失败（用户名或密码错误）"));
            }
            return ResponseEntity.status(503)
                    .body(Map.of("error", "认证服务暂时不可用，请稍后重试"));
        } catch (Exception e) {
            log.error("登录处理异常", e);
            // tokenUri 未配置时 restTemplate 抛 IllegalArgumentException 进此分支；
            // 同样给降级路径一次机会
            if (localAuthEnabled) {
                Map<String, Object> local = localLogin(req);
                if (local != null) {
                    return ResponseEntity.ok(local);
                }
                return ResponseEntity.status(401)
                        .body(Map.of("error", "认证失败（用户名或密码错误）"));
            }
            return ResponseEntity.status(500).body(Map.of("error", "登录失败，请稍后重试"));
        }
    }

    /**
     * 本地降级登录：校验内置账号并签发平台 JWT（HMAC，JwtAuthFilter 可验）。
     *
     * <p>仅在 {@code app.security.local-auth.enabled=true}（默认 false）时可达。
     * 返回结构与 Keycloak 主路径完全一致（token/expiresIn/refreshToken/user）。</p>
     *
     * @return LoginResult 形态；账号不符返回 null（由调用方回 401）
     */
    private Map<String, Object> localLogin(LoginRequest req) {
        if (!localUsername.equals(req.username()) || !localPassword.equals(req.password())) {
            log.warn("本地降级登录: 账号或密码不匹配, username={}", req.username());
            return null;
        }
        if (jwtSecret == null || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            log.error("本地降级登录失败: JWT_SECRET 未配置或不足 32 字节");
            return null;
        }
        long now = System.currentTimeMillis();
        long expiresIn = 3600;
        String token = io.jsonwebtoken.Jwts.builder()
                .issuer(jwtIssuer)
                .subject(localUsername)
                .claim("tenantId", "platform-admin")
                .claim("role", "admin")
                .issuedAt(new java.util.Date(now))
                .expiration(new java.util.Date(now + expiresIn * 1000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "local-admin");
        user.put("username", localUsername);
        user.put("nickname", "本地管理员");
        user.put("email", "");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("expiresIn", expiresIn);
        result.put("refreshToken", "");
        result.put("user", user);
        log.info("本地降级登录成功: username={}", localUsername);
        return result;
    }

    /**
     * 简单 URL 编码（避免中文用户名/密码问题）。 */
    private String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }


    /** 解码 JWT payload（base64url），不校验签名（签名由 JwtAuthFilter 负责）。 */
    private JsonNode decodeJwtPayload(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return objectMapper.createObjectNode();
        }
        String payload = parts[1];
        byte[] bytes = java.util.Base64.getUrlDecoder().decode(payload);
        return objectMapper.readTree(bytes);
    }

    /**
     * RestTemplate 必须设置超时（默认无超时会无限挂起——与 ElasticsearchIndexer
     * 同类问题，曾导致登录请求卡死）。
     */
    private RestTemplate buildRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        return new RestTemplate(factory);
    }
}
