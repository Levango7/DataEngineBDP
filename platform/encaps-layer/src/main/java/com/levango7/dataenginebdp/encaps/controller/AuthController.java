package com.levango7.dataenginebdp.encaps.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            return ResponseEntity.status(502).body(Map.of("error", "认证服务不可用"));
        } catch (Exception e) {
            log.error("登录处理异常: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "登录失败: " + e.getMessage()));
        }
    }

    /** 简单 URL 编码（避免中文用户名/密码问题）。 */
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
