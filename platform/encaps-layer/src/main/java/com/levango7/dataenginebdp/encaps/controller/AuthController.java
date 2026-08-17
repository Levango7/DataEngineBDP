package com.levango7.dataenginebdp.encaps.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.encaps.crypto.jwt.GmJwtProcessor;
import com.levango7.dataenginebdp.encaps.crypto.jwt.JwtAlgorithm;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM2Provider;
import com.levango7.dataenginebdp.encaps.security.AuditLog;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证端点（Keycloak OIDC direct grant 代理）。
 *
 * <p>前端 Login.vue 调 {@code POST /api/v1/auth/login}，
 * 本端点代理 Keycloak 的 password flow（directAccessGrants），
 * 返回前端 {@code LoginResult} 契约（token/expiresIn/refreshToken/user）。
 *
 * <p>配置（环境变量）：
 * <ul>
 *   <li>KEYCLOAK_TOKEN_URI: Keycloak token 端点（默认本地 dev 实例）</li>
 *   <li>KEYCLOAK_CLIENT_ID: 客户端 ID（默认 sq-console）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = buildRestTemplate();

    @Value("${app.security.oidc.token-uri:http://127.0.0.1:18040/realms/shuqing/protocol/openid-connect/token}")
    private String tokenUri;

    @Value("${app.security.oidc.client-id:sq-console}")
    private String clientId;

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    @Value("${app.security.jwt.issuer}")
    private String jwtIssuer;

    @Value("${app.security.jwt.expiry:3600}")
    private long jwtExpiry;

    /** JWT 签名算法：HS384（默认）/ SM3withSM2（信创环境） */
    @Value("${app.security.jwt.algorithm:HS384}")
    private String jwtAlgorithm;

    /** SM2 私钥 D 值 hex 串（信创模式；空表示自动生成临时密钥对） */
    @Value("${app.security.jwt.sm2-private-key:}")
    private String sm2PrivateKeyHex;

    /** SM2 公钥 Q 值 hex 串（信创模式；空表示自动生成临时密钥对） */
    @Value("${app.security.jwt.sm2-public-key:}")
    private String sm2PublicKeyHex;

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
            log.warn("Keycloak 不可达: {}，使用本地登录回退", e.getMessage());
            return localLoginFallback(req);
        } catch (Exception e) {
            log.error("登录处理异常: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "登录失败: " + e.getMessage()));
        }
    }

    /**
     * 本地登录回退：当 Keycloak 不可达时，使用预设用户验证并生成 HMAC JWT token。
     *
     * <p>预设用户（多租户隔离：不同用户归属不同租户）：
     * <ul>
     *   <li>admin/admin：管理员，userId=admin，email=admin@local，tenantId=tenant-001</li>
     *   <li>user/user：普通用户，userId=user，email=user@local，tenantId=tenant-002</li>
     * </ul>
     *
     * <p>生成的 JWT 与 {@link com.levango7.dataenginebdp.encaps.security.JwtAuthFilter}
     * 使用相同的 HMAC 密钥与 issuer，确保后续请求可通过 JwtAuthFilter 验证。
     *
     * @param req 登录请求（用户名/密码）
     * @return 登录成功返回 200 + LoginResult；失败返回 401
     */
    private ResponseEntity<?> localLoginFallback(LoginRequest req) {
        // 1. 预设用户校验（admin 属于 tenant-001，user 属于 tenant-002，实现多租户隔离）
        String username = req.username();
        String password = req.password();
        String userId;
        String email;
        String nickname;
        if ("admin".equals(username) && "admin".equals(password)) {
            userId = "admin";
            email = "admin@local";
            nickname = "管理员";
            // admin 属于 tenant-001
        } else if ("user".equals(username) && "user".equals(password)) {
            userId = "user";
            email = "user@local";
            nickname = "普通用户";
            // user 属于 tenant-002
        } else {
            log.warn("本地登录失败，用户名或密码错误: username={}", username);
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }

        // 2. 生成 JWT token（与 JwtAuthFilter 使用相同密钥/issuer/算法）
        //    多租户映射：admin -> tenant-001, user -> tenant-002（修复 M-F-05：避免硬编码 default）
        String tenantId = "admin".equals(userId) ? "tenant-001" : "tenant-002";

        String accessToken = signJwt(userId, tenantId, username, email);

        // 3. 组装返回结果（与 Keycloak 登录成功格式一致）
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", userId);
        user.put("username", username);
        user.put("nickname", nickname);
        user.put("email", email);
        user.put("tenantId", tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", accessToken);
        result.put("expiresIn", jwtExpiry);
        result.put("refreshToken", "");
        result.put("user", user);

        log.info("本地登录回退成功: username={}, userId={}, tenantId={}", username, userId, tenantId);
        return ResponseEntity.ok(result);
    }

    /** 简单 URL 编码（避免中文用户名/密码问题）。 */
    private String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 签发 JWT，按配置算法分发：
     * <ul>
     *   <li>SM3withSM2（信创）：使用 {@link GmJwtProcessor} 签发</li>
     *   <li>HS384/HS256（默认）：使用 jjwt 签发</li>
     * </ul>
     *
     * @param userId   用户 ID（subject）
     * @param tenantId 租户 ID（写入 tenantId claim）
     * @param username 用户名（写入 preferred_username claim）
     * @param email    邮箱（写入 email claim）
     * @return JWT 字符串
     */
    private String signJwt(String userId, String tenantId, String username, String email) {
        // SM3withSM2（信创环境）
        if (JwtAlgorithm.SM3_WITH_SM2.equalsIgnoreCase(jwtAlgorithm)) {
            byte[] privateKeyD;
            if (sm2PrivateKeyHex != null && !sm2PrivateKeyHex.isBlank()) {
                privateKeyD = hexToBytes(sm2PrivateKeyHex.trim());
            } else {
                // 开发环境：自动生成临时密钥对
                SM2Provider sm2 = new SM2Provider();
                SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
                privateKeyD = kp.getPrivateKeyD();
                log.warn("自动生成临时 SM2 密钥对签发 JWT，仅限开发环境；"
                        + "生产环境必须配置 JWT_SM2_PRIVATE_KEY/JWT_SM2_PUBLIC_KEY");
            }
            GmJwtProcessor processor = new GmJwtProcessor(jwtIssuer);
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("tenantId", tenantId);
            claims.put("preferred_username", username);
            claims.put("email", email);
            return processor.sign(privateKeyD, userId, claims, jwtExpiry);
        }

        // HMAC-SHA（默认/非信创）
        SecretKey signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);
        Date exp = new Date(nowMillis + jwtExpiry * 1000L);
        return Jwts.builder()
                .subject(userId)
                .issuer(jwtIssuer)
                .issuedAt(now)
                .expiration(exp)
                .claim("tenantId", tenantId)
                .claim("preferred_username", username)
                .claim("email", email)
                .signWith(signingKey)
                .compact();
    }

    /**
     * hex 字符串转字节数组。
     *
     * @param hex hex 串
     * @return 字节数组
     */
    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        String s = hex.toLowerCase();
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
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
