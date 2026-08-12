package com.levango7.dataenginebdp.encaps.crypto.jwt;

import com.levango7.dataenginebdp.encaps.crypto.gm.SM2Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GmJwtProcessor} 单元测试。
 *
 * <p>覆盖聚合处理器签名/验签往返、密钥对生成、组件协作等。</p>
 */
class GmJwtProcessorTest {

    private GmJwtProcessor processor;
    private SM2Provider.Sm2KeyPair keyPair;

    @BeforeEach
    void setUp() {
        processor = new GmJwtProcessor("shuqing-bigdata");
        keyPair = processor.getSm2().generateKeyPair();
    }

    @Test
    @DisplayName("签名 → 验签 往返 — 通过处理器")
    void signVerify_viaProcessor_shouldRoundTrip() {
        Map<String, Object> claims = GmJwtSigner.newClaims();
        claims.put("tenantId", "tenant-001");
        claims.put("roles", "admin");

        String jwt = processor.sign(keyPair.getPrivateKeyD(), "user-123", claims, 3600);
        Map<String, Object> payload = processor.verify(jwt, keyPair.getPublicKeyQ());

        assertThat(payload.get("sub")).isEqualTo("user-123");
        assertThat(payload.get("tenantId")).isEqualTo("tenant-001");
        assertThat(payload.get("iss")).isEqualTo("shuqing-bigdata");
    }

    @Test
    @DisplayName("签名 → 验签 — 带 keyId")
    void signVerify_withKeyId_shouldWork() {
        String jwt = processor.sign(keyPair.getPrivateKeyD(), "user-123", null, 3600, "kid-001");
        Map<String, Object> header = processor.extractHeader(jwt);
        assertThat(header.get("kid")).isEqualTo("kid-001");
        Map<String, Object> payload = processor.verify(jwt, keyPair.getPublicKeyQ());
        assertThat(payload.get("sub")).isEqualTo("user-123");
    }

    @Test
    @DisplayName("verifySignatureOnly — 不校验声明")
    void verifySignatureOnly_skipsValidation() {
        String jwt = processor.sign(keyPair.getPrivateKeyD(), "user-123", null, 3600);
        Map<String, Object> payload = processor.verifySignatureOnly(jwt, keyPair.getPublicKeyQ());
        assertThat(payload.get("sub")).isEqualTo("user-123");
    }

    @Test
    @DisplayName("getSm2/getSm3 — 共享 Provider")
    void getSm2Sm3_shouldBeShared() {
        assertThat(processor.getSm2()).isNotNull();
        assertThat(processor.getSm3()).isNotNull();
        // 签名器与验签器共享同一 SM2 实例
        assertThat(processor.getSigner().getSm2()).isSameAs(processor.getVerifier().getSm2());
    }

    @Test
    @DisplayName("默认构造 — 不校验 issuer")
    void defaultConstructor_noIssuerCheck() {
        GmJwtProcessor p = new GmJwtProcessor();
        SM2Provider.Sm2KeyPair kp = p.getSm2().generateKeyPair();
        // 不带 iss 声明也能验签通过
        String jwt = p.sign(kp.getPrivateKeyD(), "user", null, 3600);
        Map<String, Object> payload = p.verify(jwt, kp.getPublicKeyQ());
        assertThat(payload.get("sub")).isEqualTo("user");
    }

    @Test
    @DisplayName("注入构造 — null 抛异常")
    void injectConstructor_null_shouldThrow() {
        assertThatThrownBy(() -> new GmJwtProcessor(null, null))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> new GmJwtProcessor(new GmJwtSigner(), null))
                .isInstanceOf(JwtException.class);
    }
}