package com.levango7.dataenginebdp.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwksPublicKeyProvider} 单元测试。
 *
 * <p><b>为什么用本地 HTTP 服务器而不是 mock</b>：本类的价值就在"从 JWKS JSON 里把
 * RSA 公钥正确还原"（JWK 的 n/e 是 Base64URL 的大整数，需拼成 X.509 SubjectPublicKeyInfo）。
 * 这条链路只有用<b>真实密钥对 + 真实 HTTP 端点</b>才能验证：拉到的公钥必须与生成时的
 * 公钥逐字节相等、且能验真签名、且拒绝假签名。mock 掉 HttpClient 等于把要测的逻辑测掉了。</p>
 *
 * <p>另用"轮换"场景验证按 kid 取钥与刷新语义。</p>
 */
class JwksPublicKeyProviderTest {

    private static final String KID = "key-1";
    private static final String KID_2 = "key-2";

    private HttpServer server;
    private JwksPublicKeyProvider provider;
    private KeyPair keyPair1;
    private KeyPair keyPair2;
    private int hitCount;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair1 = gen.generateKeyPair();
        keyPair2 = gen.generateKeyPair();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certs", exchange -> {
            hitCount++;
            byte[] body;
            try {
                body = jwksBody().getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        provider = new JwksPublicKeyProvider(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/certs",
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMinutes(10));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ===================== 正确性：拉到的公钥必须能验真签名 =====================

    @Test
    @DisplayName("resolve 返回的公钥与生成时一致，且能验证真实签名")
    void resolvedKey_matchesGeneratedKey_andVerifiesSignature() throws Exception {
        PublicKey resolved = provider.resolve(KID);

        assertThat(resolved).isEqualTo(keyPair1.getPublic());
        assertThat(resolved.getAlgorithm()).isEqualTo("RSA");

        // 用 Keycloak 侧私钥签、拉到的公钥验——这条通过才说明 DER 拼接真对了
        byte[] data = "header.payload".getBytes(StandardCharsets.UTF_8);
        byte[] sig = sign(data, keyPair1.getPrivate());
        assertThat(verify(data, sig, resolved)).isTrue();
    }

    @Test
    @DisplayName("用拉到的公钥拒绝另一把私钥签的名")
    void resolvedKey_rejectsForeignSignature() throws Exception {
        PublicKey resolved = provider.resolve(KID);

        byte[] data = "header.payload".getBytes(StandardCharsets.UTF_8);
        byte[] foreign = sign(data, keyPair2.getPrivate());
        assertThat(verify(data, foreign, resolved)).isFalse();
    }

    // ===================== 缓存：同 kid 二次解析不再打 HTTP =====================

    @Test
    @DisplayName("TTL 内缓存：二次 resolve 不再请求 JWKS")
    void cacheHits_withinTtl() {
        provider.resolve(KID);
        int afterFirst = hitCount;
        provider.resolve(KID);
        assertThat(hitCount).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("refresh 主动刷新会重新请求（密钥轮换场景）")
    void refresh_forcesRefetch() {
        provider.resolve(KID);
        int afterFirst = hitCount;
        provider.refresh(KID);
        assertThat(hitCount).isGreaterThan(afterFirst);
    }

    @Test
    @DisplayName("第二个 kid 也能取到（多密钥并存时按 kid 命中）")
    void secondKid_resolvesToSecondKey() {
        assertThat(provider.resolve(KID_2)).isEqualTo(keyPair2.getPublic());
        assertThat(provider.resolve(KID)).isEqualTo(keyPair1.getPublic());
    }

    // ===================== fail-fast：无 kid、无此 kid、端点故障都不放过 =====================

    @Test
    @DisplayName("token 无 kid → 拒绝（多密钥下无法定位）")
    void missingKid_failsFast() {
        assertThatThrownBy(() -> provider.resolve(null))
                .isInstanceOf(JwksPublicKeyProvider.JwtKeyException.class)
                .hasMessageContaining("kid");
        assertThatThrownBy(() -> provider.resolve("   "))
                .isInstanceOf(JwksPublicKeyProvider.JwtKeyException.class);
    }

    @Test
    @DisplayName("kid 不在 JWKS 中 → 拒绝")
    void unknownKid_failsFast() {
        assertThatThrownBy(() -> provider.resolve("no-such-kid"))
                .isInstanceOf(JwksPublicKeyProvider.JwtKeyException.class)
                .hasMessageContaining("no-such-kid");
    }

    @Test
    @DisplayName("端点返 500 → 拒绝（不 fail-open）")
    void endpointError_failsFast() throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certs", exchange -> {
            byte[] body = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        JwksPublicKeyProvider broken = new JwksPublicKeyProvider(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/certs",
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMinutes(10));

        assertThatThrownBy(() -> broken.resolve(KID))
                .isInstanceOf(JwksPublicKeyProvider.JwtKeyException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("jwksUri 为空 → 构造即拒绝")
    void blankUri_failsFastAtConstruction() {
        assertThatThrownBy(() -> new JwksPublicKeyProvider("  ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===================== helpers =====================

    private String jwksBody() throws Exception {
        Map<String, Object> jwk1 = jwkFor(keyPair1, KID);
        Map<String, Object> jwk2 = jwkFor(keyPair2, KID_2);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("keys", List.of(jwk1, jwk2));
        return new ObjectMapper().writeValueAsString(doc);
    }

    /** 按 RFC 7517 生成 RSA JWK（JWKS 的真实形态）。 */
    private static Map<String, Object> jwkFor(KeyPair kp, String kid) {
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", kid);
        jwk.put("n", b64u(pub.getModulus()));
        jwk.put("e", b64u(pub.getPublicExponent()));
        return jwk;
    }

    private static String b64u(BigInteger value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray());
    }

    private static byte[] sign(byte[] data, java.security.PrivateKey key) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(data);
        return sig.sign();
    }

    private static boolean verify(byte[] data, byte[] signature, PublicKey key) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(key);
        sig.update(data);
        return sig.verify(signature);
    }

}
