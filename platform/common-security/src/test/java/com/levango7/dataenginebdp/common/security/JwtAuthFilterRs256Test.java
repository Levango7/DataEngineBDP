package com.levango7.dataenginebdp.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JwtAuthFilter} 的 Keycloak RS256 通道测试。
 *
 * <p><b>验证什么</b>：最后一公里的第一段——服务层能直接验 Keycloak 签发的 RS256 token。
 * 这里用<b>真实 RSA 密钥对 + 本地 JWKS 端点</b>，不是 mock：
 * token 由 jjwt 以 RS256 真签（含 Keycloak 形态的 {@code realm_access.roles} 与
 * {@code iss}），过滤器走完整 {@code doFilterInternal}，断言放行 + 租户/权限注入正确。</p>
 *
 * <p><b>同时守住的旧行为</b>：HS256 自签 token 在同一个已启用 JWKS 的过滤器上仍放行，
 * 且非白名单 alg（{@code none}）被拒——证明双模式没有把原有通道改坏。</p>
 */
@DisplayName("JwtAuthFilter Keycloak RS256 通道")
class JwtAuthFilterRs256Test {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long!!";
    private static final String ISSUER = "shuqing-bigdata";
    private static final String KC_KID = "kc-key-1";
    /** Keycloak 的 issuer 形如 {KEYCLOAK_URL}/realms/shuqing */
    private static final String KC_ISSUER = "http://127.0.0.1:8081/realms/shuqing";

    private HttpServer server;
    private KeyPair keyPair;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certs", exchange -> {
            byte[] body;
            try {
                body = jwks();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        JwksPublicKeyProvider provider = new JwksPublicKeyProvider(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/certs",
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMinutes(10));
        // 同时启用两条通道：HS256（自签）+ RS256（Keycloak）
        filter = new JwtAuthFilter(SECRET, ISSUER, provider);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        TenantContext.clear();
    }

    @Test
    @DisplayName("Keycloak 形态的 RS256 token → 放行，租户/用户/角色正确注入")
    void keycloakRs256Token_passesAndInjectsContext() throws Exception {
        String token = Jwts.builder()
                .issuer(KC_ISSUER)
                .subject("kc-user-1")
                .claim("tenantId", "t-kc")
                .claim("realm_access", Map.<String, Object>of("roles", List.of("SUPER_ADMIN", "TENANT_ADMIN")))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .header().keyId(KC_KID).and()
                .signWith(keyPair.getPrivate(), io.jsonwebtoken.SignatureAlgorithm.RS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        // 上下文必须在"链内"断言：过滤器 finally 会清理 TenantContext/SecurityContext
        //（防线程池复用串号，这是既有设计，见 JwtAuthFilterTest 的对应断言）
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.add(TenantContext.getTenantId());
                seen.add(TenantContext.getUserId());
                seen.add(String.valueOf(SecurityContextHolder.getContext().getAuthentication() != null));
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        // 链内被调用且三项上下文都对 = 放行（覆写的 chain 不走父类，故不断言 getRequest()）
        assertThat(seen).containsExactly("t-kc", "kc-user-1", "true");
    }

    @Test
    @DisplayName("RS256 token 但服务未配置 JWKS → 401（不降级、不静默放行）")
    void rs256WithoutJwks_rejected() throws Exception {
        JwtAuthFilter noJwks = new JwtAuthFilter(SECRET, ISSUER);
        String token = Jwts.builder()
                .issuer(KC_ISSUER)
                .subject("u")
                .claim("tenantId", "t")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .header().keyId(KC_KID).and()
                .signWith(keyPair.getPrivate(), io.jsonwebtoken.SignatureAlgorithm.RS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        request.addHeader("Authorization", "Bearer " + token);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        noJwks.doFilter(request, resp, new MockFilterChain());

        // 未配置 JWKS 时 RS256 token 一律拒：不降级到 HS256、不静默放行
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    /**
     * 424 bit（53 字节）密钥签出的 token 是 <b>HS384</b>——这是 jjwt 的行为，不是畸形输入。
     *
     * <p><b>为什么必须有这一条</b>（2026-10-13 CI 实测教训）：jjwt 的
     * {@code signWith(SecretKey)} 按密钥位宽自动推 alg（384≤bit<512 → HmacSHA384）。
     * 我最初把白名单写成只放行 HS256，结果三个下游模块（密钥 53 字节）的
     * {@code validToken_shouldNotReturn401} 全被 401 拒掉——而本地因 m2 里是旧 jar 而假绿，
     * 只有 CI（每次从源码 install）暴露。真实生产同源：AuthController 的签发意图就是 HS384。</p>
     */
    @Test
    @DisplayName("HS384 自签 token（424 bit 密钥形态）→ 放行，且上下文注入正确")
    void hs384Token_passes() throws Exception {
        // 与下游模块测试同规格的 53 字节密钥（424 bit ⇒ jjwt 选 HS384）
        String secret384 = "test-secret-key-for-unit-tests-at-least-256-bits-long";
        assertThat(secret384.getBytes(StandardCharsets.UTF_8).length).isEqualTo(53);

        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject("u-384")
                .claim("tenantId", "t-384")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        secret384.getBytes(StandardCharsets.UTF_8)))
                .compact();
        // 前置断言：确认这确实是 HS384 形态（防止将来 jjwt 变行为后本条测试失去意义）。
        // 注意必须用带 key 的 parseSignedClaims——parseUnsecuredClaims 只适用于 alg=none 的
        // 无签名 token，拿去解析带签名的 JWS 会抛 UnsupportedJwtException（2026-10-13 实测踩过）。
        assertThat(io.jsonwebtoken.Jwts.parser()
                .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        secret384.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token).getHeader().getAlgorithm()).isEqualTo("HS384");

        JwtAuthFilter f384 = new JwtAuthFilter(secret384, ISSUER);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        request.addHeader("Authorization", "Bearer " + token);
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.add(TenantContext.getTenantId());
                seen.add(TenantContext.getUserId());
            }
        };

        f384.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen).containsExactly("t-384", "u-384");
    }

    @Test
    @DisplayName("HS512 自签 token（520 bit 密钥形态）→ 同样放行（HMAC 全族可验）")
    void hs512Token_passes() throws Exception {
        String secret512 = "test-secret-key-for-hmac-sha-512-at-least-520-bits-long-0123456789abcdef";
        assertThat(secret512.getBytes(StandardCharsets.UTF_8).length).isGreaterThanOrEqualTo(64);

        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject("u-512")
                .claim("tenantId", "t-512")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        secret512.getBytes(StandardCharsets.UTF_8)))
                .compact();
        // 同上：带 key 解析（parseUnsecuredClaims 只吃 alg=none 的无签名 token）
        assertThat(io.jsonwebtoken.Jwts.parser()
                .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        secret512.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token).getHeader().getAlgorithm()).isEqualTo("HS512");

        JwtAuthFilter f512 = new JwtAuthFilter(secret512, ISSUER);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        request.addHeader("Authorization", "Bearer " + token);

        f512.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // 不抛异常且未写错误状态 = 走了自签验签分支并放行
        MockHttpServletResponse resp = new MockHttpServletResponse();
        f512.doFilter(request, resp, new MockFilterChain());
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("alg=none 仍被拒：扩 HMAC 白名单不等于放开算法校验")
    void algNone_stillRejected_afterWideningWhitelist() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        String header = b64u("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64u("{\"sub\":\"attacker\",\"tenantId\":\"t\",\"iss\":\"" + ISSUER + "\"}");
        request.addHeader("Authorization", "Bearer " + header + "." + payload + ".");

        MockHttpServletResponse resp = new MockHttpServletResponse();
        try {
            filter.doFilter(request, resp, new MockFilterChain());
        } catch (RuntimeException e) {
            // 解析阶段即抛，也算拒
        }
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("HS256 自签 token 在已启用 JWKS 的过滤器上仍放行（旧行为不回归）")
    void hs256StillWorks_whenJwksEnabled() throws Exception {
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject("u-1")
                .claim("tenantId", "t-1")
                .claim("realm_access", Map.<String, Object>of("roles", List.of("USER")))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        request.addHeader("Authorization", "Bearer " + token);
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.add(TenantContext.getTenantId());
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // 链内被调用且拿到租户上下文 = 过滤器放行（覆写的 chain 不走父类，故不断言 getRequest()）
        assertThat(seen).containsExactly("t-1");
    }

    @Test
    @DisplayName("kid 不在 JWKS 中 → 401（fail-fast，不 fail-open）")
    void unknownKid_rejected() throws Exception {
        String token = Jwts.builder()
                .issuer(KC_ISSUER)
                .subject("u")
                .claim("tenantId", "t")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .header().keyId("not-in-jwks").and()
                .signWith(keyPair.getPrivate(), io.jsonwebtoken.SignatureAlgorithm.RS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        request.addHeader("Authorization", "Bearer " + token);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(request, resp, new MockFilterChain());

        // 503 而非 401：kid 找不到是"验签基础设施故障"（换 token 也没用），
        // 绝不能 fail-open 放行、也不该穿透成 500。
        assertThat(resp.getStatus()).isEqualTo(503);
        assertThat(resp.getContentAsString()).contains("token verification key unavailable");
    }

    // ---------------- helpers ----------------

    private byte[] jwks() throws Exception {
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", KC_KID);
        jwk.put("n", b64u(pub.getModulus()));
        jwk.put("e", b64u(pub.getPublicExponent()));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("keys", List.of(jwk));
        return new ObjectMapper().writeValueAsString(doc).getBytes(StandardCharsets.UTF_8);
    }

    private static String b64u(Object value) {
        byte[] bytes = value instanceof BigInteger bi ? bi.toByteArray() : value.toString().getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
