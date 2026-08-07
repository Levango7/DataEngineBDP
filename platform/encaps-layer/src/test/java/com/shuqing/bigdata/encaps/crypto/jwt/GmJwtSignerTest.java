package com.shuqing.bigdata.encaps.crypto.jwt;

import com.shuqing.bigdata.encaps.crypto.gm.SM2Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GmJwtSigner} 与 {@link GmJwtVerifier} 单元测试。
 *
 * <p>覆盖国密 JWT 签名/验签往返、声明校验、防降级攻击、异常处理等。</p>
 */
class GmJwtSignerTest {

    private GmJwtSigner signer;
    private GmJwtVerifier verifier;
    private SM2Provider sm2;
    private SM2Provider.Sm2KeyPair keyPair;

    @BeforeEach
    void setUp() {
        signer = new GmJwtSigner("shuqing-bigdata");
        sm2 = signer.getSm2();
        keyPair = sm2.generateKeyPair();
        verifier = new GmJwtVerifier("shuqing-bigdata");
    }

    // ===== 签名/验签往返 =====

    @Test
    @DisplayName("签名 → 验签 往返 — 标准声明通过")
    void signVerify_roundTrip_shouldVerify() {
        Map<String, Object> claims = GmJwtSigner.newClaims();
        claims.put("tenantId", "tenant-001");
        claims.put("roles", "admin");

        String jwt = signer.sign(keyPair.getPrivateKeyD(), "user-123", claims, 3600);
        assertThat(jwt).isNotNull().isNotEmpty();
        // JWT 应为三段式
        assertThat(jwt.split("\\.")).hasSize(3);

        Map<String, Object> payload = verifier.verify(jwt, keyPair.getPublicKeyQ());
        assertThat(payload.get("sub")).isEqualTo("user-123");
        assertThat(payload.get("iss")).isEqualTo("shuqing-bigdata");
        assertThat(payload.get("tenantId")).isEqualTo("tenant-001");
        assertThat(payload.get("roles")).isEqualTo("admin");
        assertThat(payload.get("iat")).isNotNull();
        assertThat(payload.get("exp")).isNotNull();
    }

    @Test
    @DisplayName("签名 → 验签 — 带 keyId")
    void signVerify_withKeyId_shouldVerify() {
        String jwt = signer.sign(keyPair.getPrivateKeyD(), "user-123", null, 3600, "key-2024-001");
        Map<String, Object> header = verifier.extractHeader(jwt);
        assertThat(header.get("alg")).isEqualTo("SM3withSM2");
        assertThat(header.get("typ")).isEqualTo("JWT");
        assertThat(header.get("kid")).isEqualTo("key-2024-001");

        Map<String, Object> payload = verifier.verify(jwt, keyPair.getPublicKeyQ());
        assertThat(payload.get("sub")).isEqualTo("user-123");
    }

    @Test
    @DisplayName("签名 → 验签 — 空声明")
    void signVerify_emptyClaims_shouldVerify() {
        String jwt = signer.sign(keyPair.getPrivateKeyD(), "user-123", null, 3600);
        Map<String, Object> payload = verifier.verify(jwt, keyPair.getPublicKeyQ());
        assertThat(payload.get("sub")).isEqualTo("user-123");
    }

    @Test
    @DisplayName("签名 → 验签 — 多种声明类型")
    void signVerify_variousClaimTypes_shouldVerify() {
        Map<String, Object> claims = GmJwtSigner.newClaims();
        claims.put("stringClaim", "value");
        claims.put("intClaim", 42);
        claims.put("longClaim", 1234567890L);
        claims.put("boolClaim", true);

        String jwt = signer.sign(keyPair.getPrivateKeyD(), "user-123", claims, 3600);
        Map<String, Object> payload = verifier.verify(jwt, keyPair.getPublicKeyQ());

        assertThat(payload.get("stringClaim")).isEqualTo("value");
        assertThat(payload.get("intClaim")).isEqualTo(42);
        assertThat(payload.get("boolClaim")).isEqualTo(true);
    }

    // ===== 防降级攻击 =====

    @Test
    @DisplayName("验签 — 拒绝 alg=none 防降级")
    void verify_algNone_shouldReject() {
        // 构造 alg=none 的 JWT（使用非空 signature 避免 split 去尾空）
        String header = Base64UrlUtil.encodeString("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = Base64UrlUtil.encodeString("{\"sub\":\"user\"}");
        String jwt = header + "." + payload + "." + Base64UrlUtil.encode(new byte[]{1});
        assertThatThrownBy(() -> verifier.verify(jwt, keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("unsupported alg");
    }

    @Test
    @DisplayName("验签 — 拒绝 alg=HS256 防降级")
    void verify_algHs256_shouldReject() {
        String header = Base64UrlUtil.encodeString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = Base64UrlUtil.encodeString("{\"sub\":\"user\"}");
        String jwt = header + "." + payload + "." + Base64UrlUtil.encode(new byte[32]);
        assertThatThrownBy(() -> verifier.verify(jwt, keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("unsupported alg");
    }

    // ===== 签名篡改 =====

    @Test
    @DisplayName("验签 — 篡改 payload 应失败")
    void verify_tamperedPayload_shouldFail() {
        Map<String, Object> claims = GmJwtSigner.newClaims();
        claims.put("tenantId", "tenant-001");
        String jwt = signer.sign(keyPair.getPrivateKeyD(), "user-123", claims, 3600);

        // 篡改 payload
        String[] parts = jwt.split("\\.");
        String tamperedPayload = Base64UrlUtil.encodeString("{\"sub\":\"attacker\",\"iss\":\"shuqing-bigdata\"}");
        String tamperedJwt = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThatThrownBy(() -> verifier.verify(tamperedJwt, keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("验签 — 错误公钥应失败")
    void verify_wrongPublicKey_shouldFail() {
        String jwt = signer.sign(keyPair.getPrivateKeyD(), "user-123", null, 3600);
        SM2Provider.Sm2KeyPair wrongKey = sm2.generateKeyPair();
        assertThatThrownBy(() -> verifier.verify(jwt, wrongKey.getPublicKeyQ()))
                .isInstanceOf(JwtException.class);
    }

    // ===== 格式校验 =====

    @Test
    @DisplayName("验签 — 非三段式抛异常")
    void verify_invalidFormat_shouldThrow() {
        assertThatThrownBy(() -> verifier.verify("not.a.jwt.extra", keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("3 parts");
        assertThatThrownBy(() -> verifier.verify("only.two", keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("验签 — null/blank JWT 抛异常")
    void verify_nullJwt_shouldThrow() {
        assertThatThrownBy(() -> verifier.verify(null, keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> verifier.verify("", keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> verifier.verify("jwt", null))
                .isInstanceOf(JwtException.class);
    }

    // ===== 声明校验 =====

    @Test
    @DisplayName("验签 — issuer 不匹配应失败")
    void verify_issuerMismatch_shouldFail() {
        GmJwtSigner wrongSigner = new GmJwtSigner("wrong-issuer");
        String jwt = wrongSigner.sign(keyPair.getPrivateKeyD(), "user-123", null, 3600);
        assertThatThrownBy(() -> verifier.verify(jwt, keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("issuer");
    }

    @Test
    @DisplayName("验签 — 过期 JWT 应失败")
    void verify_expired_shouldFail() {
        // 通过 signRaw 手动设置 exp 为过去时间，避免等待
        Map<String, Object> rawPayload = GmJwtSigner.newClaims();
        rawPayload.put("sub", "user-123");
        rawPayload.put("iss", "shuqing-bigdata");
        rawPayload.put("iat", java.time.Instant.now().getEpochSecond() - 3600);
        rawPayload.put("exp", java.time.Instant.now().getEpochSecond() - 10);  // 10 秒前过期
        String jwt = signer.signRaw(keyPair.getPrivateKeyD(), rawPayload, null);

        // 使用 clockSkew=0 的严格验签器
        GmJwtVerifier strictVerifier = new GmJwtVerifier("shuqing-bigdata", null, 0);
        assertThatThrownBy(() -> strictVerifier.verify(jwt, keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("验签 — 仅验签不校验声明")
    void verifySignatureOnly_skipsClaimValidation() {
        GmJwtSigner rawSigner = new GmJwtSigner((String) null);
        SM2Provider rawSm2 = rawSigner.getSm2();
        SM2Provider.Sm2KeyPair rawKp = rawSm2.generateKeyPair();

        Map<String, Object> rawPayload = GmJwtSigner.newClaims();
        rawPayload.put("sub", "user-123");
        // 不含 exp/iss 等标准声明
        String jwt = rawSigner.signRaw(rawKp.getPrivateKeyD(), rawPayload, null);

        // verifySignatureOnly 应通过（不校验 iss/exp）
        Map<String, Object> payload = verifier.verifySignatureOnly(jwt, rawKp.getPublicKeyQ());
        assertThat(payload.get("sub")).isEqualTo("user-123");
    }

    // ===== signRaw =====

    @Test
    @DisplayName("signRaw — 自定义完整 payload")
    void signRaw_customPayload_shouldVerify() {
        Map<String, Object> rawPayload = GmJwtSigner.newClaims();
        rawPayload.put("sub", "user-raw");
        rawPayload.put("custom", "value");
        rawPayload.put("exp", System.currentTimeMillis() / 1000 + 3600);

        String jwt = signer.signRaw(keyPair.getPrivateKeyD(), rawPayload, "kid-raw");
        Map<String, Object> payload = verifier.verifySignatureOnly(jwt, keyPair.getPublicKeyQ());
        assertThat(payload.get("sub")).isEqualTo("user-raw");
        assertThat(payload.get("custom")).isEqualTo("value");
    }

    @Test
    @DisplayName("signRaw — null payload 视为空对象")
    void signRaw_nullPayload_shouldVerify() {
        String jwt = signer.signRaw(keyPair.getPrivateKeyD(), null, null);
        Map<String, Object> payload = verifier.verifySignatureOnly(jwt, keyPair.getPublicKeyQ());
        // null payload 生成空对象 {}，验签后应为空 Map
        assertThat(payload).isEmpty();
    }

    // ===== 异常处理 =====

    @Test
    @DisplayName("sign — null 私钥抛异常")
    void sign_nullPrivateKey_shouldThrow() {
        assertThatThrownBy(() -> signer.sign(null, "user", null, 3600))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("sign — blank subject 抛异常")
    void sign_blankSubject_shouldThrow() {
        assertThatThrownBy(() -> signer.sign(keyPair.getPrivateKeyD(), "", null, 3600))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> signer.sign(keyPair.getPrivateKeyD(), "  ", null, 3600))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("sign — 业务声明不能覆盖标准声明")
    void sign_reservedClaims_shouldNotOverride() {
        Map<String, Object> claims = GmJwtSigner.newClaims();
        claims.put("iss", "fake-issuer");  // 试图覆盖 iss
        claims.put("exp", 1L);  // 试图覆盖 exp

        String jwt = signer.sign(keyPair.getPrivateKeyD(), "user-123", claims, 3600);
        Map<String, Object> payload = verifier.verify(jwt, keyPair.getPublicKeyQ());
        // iss 应为 signer 的 defaultIssuer，不是 fake-issuer
        assertThat(payload.get("iss")).isEqualTo("shuqing-bigdata");
    }

    // ===== extractHeader =====

    @Test
    @DisplayName("extractHeader — 提取头部不验签")
    void extractHeader_withoutVerify() {
        String jwt = signer.sign(keyPair.getPrivateKeyD(), "user-123", null, 3600, "kid-001");
        Map<String, Object> header = verifier.extractHeader(jwt);
        assertThat(header.get("alg")).isEqualTo("SM3withSM2");
        assertThat(header.get("typ")).isEqualTo("JWT");
        assertThat(header.get("kid")).isEqualTo("kid-001");
    }

    @Test
    @DisplayName("extractHeader — 非法 JWT 抛异常")
    void extractHeader_invalidJwt_shouldThrow() {
        assertThatThrownBy(() -> verifier.extractHeader("invalid"))
                .isInstanceOf(JwtException.class);
    }

    // ===== audience / nbf 校验 =====

    @Test
    @DisplayName("验签 — audience 匹配通过")
    void verify_audienceMatch_shouldPass() {
        GmJwtVerifier audVerifier = new GmJwtVerifier("shuqing-bigdata", "api-client", 60);
        Map<String, Object> rawPayload = GmJwtSigner.newClaims();
        rawPayload.put("sub", "user-123");
        rawPayload.put("aud", "api-client");
        rawPayload.put("exp", java.time.Instant.now().getEpochSecond() + 3600);
        String jwt = signer.signRaw(keyPair.getPrivateKeyD(), rawPayload, null);
        Map<String, Object> payload = audVerifier.verify(jwt, keyPair.getPublicKeyQ());
        assertThat(payload.get("sub")).isEqualTo("user-123");
    }

    @Test
    @DisplayName("验签 — audience 不匹配应失败")
    void verify_audienceMismatch_shouldFail() {
        GmJwtVerifier audVerifier = new GmJwtVerifier("shuqing-bigdata", "expected-aud", 60);
        Map<String, Object> rawPayload = GmJwtSigner.newClaims();
        rawPayload.put("sub", "user-123");
        rawPayload.put("aud", "wrong-aud");
        rawPayload.put("exp", java.time.Instant.now().getEpochSecond() + 3600);
        String jwt = signer.signRaw(keyPair.getPrivateKeyD(), rawPayload, null);
        assertThatThrownBy(() -> audVerifier.verify(jwt, keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("audience");
    }

    @Test
    @DisplayName("验签 — audience 数组匹配通过")
    void verify_audienceArrayMatch_shouldPass() {
        GmJwtVerifier audVerifier = new GmJwtVerifier("shuqing-bigdata", "client-b", 60);
        // 构造 aud 为数组的 JWT
        String header = Base64UrlUtil.encodeString("{\"alg\":\"SM3withSM2\",\"typ\":\"JWT\"}");
        String payload = Base64UrlUtil.encodeString(
                "{\"sub\":\"user\",\"iss\":\"shuqing-bigdata\",\"aud\":[\"client-a\",\"client-b\"],\"exp\":"
                        + (java.time.Instant.now().getEpochSecond() + 3600) + "}");
        String signingInput = header + "." + payload;
        byte[] sig = signer.getSm2().sign(signingInput.getBytes(StandardCharsets.UTF_8), keyPair.getPrivateKeyD());
        String jwt = signingInput + "." + Base64UrlUtil.encode(sig);
        Map<String, Object> result = audVerifier.verify(jwt, keyPair.getPublicKeyQ());
        assertThat(result.get("sub")).isEqualTo("user");
    }

    @Test
    @DisplayName("验签 — nbf 未生效应失败")
    void verify_notYetValid_shouldFail() {
        GmJwtVerifier strictVerifier = new GmJwtVerifier("shuqing-bigdata", null, 0);
        Map<String, Object> rawPayload = GmJwtSigner.newClaims();
        rawPayload.put("sub", "user-123");
        rawPayload.put("nbf", java.time.Instant.now().getEpochSecond() + 100);  // 100 秒后生效
        rawPayload.put("exp", java.time.Instant.now().getEpochSecond() + 3600);
        String jwt = signer.signRaw(keyPair.getPrivateKeyD(), rawPayload, null);
        assertThatThrownBy(() -> strictVerifier.verify(jwt, keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("not yet valid");
    }

    @Test
    @DisplayName("验签 — 无 exp 声明不校验过期")
    void verify_noExp_shouldPass() {
        Map<String, Object> rawPayload = GmJwtSigner.newClaims();
        rawPayload.put("sub", "user-123");
        // 不含 exp
        String jwt = signer.signRaw(keyPair.getPrivateKeyD(), rawPayload, null);
        Map<String, Object> payload = verifier.verifySignatureOnly(jwt, keyPair.getPublicKeyQ());
        assertThat(payload.get("sub")).isEqualTo("user-123");
    }

    @Test
    @DisplayName("验签 — 非法 header JSON 抛异常")
    void verify_invalidHeaderJson_shouldThrow() {
        String header = Base64UrlUtil.encodeString("not-json");
        String payload = Base64UrlUtil.encodeString("{\"sub\":\"user\"}");
        String jwt = header + "." + payload + "." + Base64UrlUtil.encode(new byte[]{1});
        assertThatThrownBy(() -> verifier.verify(jwt, keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("验签 — 非法 payload JSON 抛异常")
    void verify_invalidPayloadJson_shouldThrow() {
        String header = Base64UrlUtil.encodeString("{\"alg\":\"SM3withSM2\",\"typ\":\"JWT\"}");
        String payload = Base64UrlUtil.encodeString("not-json");
        String jwt = header + "." + payload + "." + Base64UrlUtil.encode(new byte[]{1});
        assertThatThrownBy(() -> verifier.verify(jwt, keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("验签 — 非法 signature 编码抛异常")
    void verify_invalidSignatureEncoding_shouldThrow() {
        String header = Base64UrlUtil.encodeString("{\"alg\":\"SM3withSM2\",\"typ\":\"JWT\"}");
        String payload = Base64UrlUtil.encodeString("{\"sub\":\"user\"}");
        String jwt = header + "." + payload + ".@@invalid@@";
        assertThatThrownBy(() -> verifier.verify(jwt, keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("verifySignatureOnly — null JWT 抛异常")
    void verifySignatureOnly_nullJwt_shouldThrow() {
        assertThatThrownBy(() -> verifier.verifySignatureOnly(null, keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> verifier.verifySignatureOnly("jwt", null))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("verifySignatureOnly — 非三段式抛异常")
    void verifySignatureOnly_invalidFormat_shouldThrow() {
        assertThatThrownBy(() -> verifier.verifySignatureOnly("a.b", keyPair.getPublicKeyQ()))
                .isInstanceOf(JwtException.class);
    }
}