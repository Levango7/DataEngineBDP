package com.levango7.dataenginebdp.encaps.crypto.gm;

import com.levango7.dataenginebdp.encaps.crypto.AlgorithmType;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DefaultGmProvider} 单元测试。
 *
 * <p>DefaultGmProvider 是国密算法的占位实现（SHA-256withRSA 签名、SHA-256 摘要、AES 对称加密），
 * 用于 SPI 框架在没有真实国密库时的默认运行。本测试覆盖其签名/验签、加解密、摘要、密钥对生成
 * 及异常处理。</p>
 */
class DefaultGmProviderTest {

    private DefaultGmProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DefaultGmProvider();
    }

    // ===== 元信息 =====

    @Test
    @DisplayName("元信息 — providerName=GM-Provider, profile=XINCHANG, algorithm=SIGN")
    void metadata_shouldMatchGmDefaults() {
        assertThat(provider.getProviderName()).isEqualTo("GM-Provider");
        assertThat(provider.getSupportedProfile()).isEqualTo(CryptoProfile.XINCHANG);
        assertThat(provider.getAlgorithmType()).isEqualTo(AlgorithmType.SIGN);
    }

    // ===== 摘要（SHA-256 占位） =====

    @Test
    @DisplayName("digest — 输出 32 字节（SHA-256）")
    void digest_shouldReturn32Bytes() {
        byte[] data = "test-data".getBytes(StandardCharsets.UTF_8);
        byte[] digest = provider.digest(data);
        assertThat(digest).hasSize(32);
    }

    @Test
    @DisplayName("digest — 相同输入产生相同摘要（确定性）")
    void digest_sameInput_shouldBeDeterministic() {
        byte[] data = "deterministic-test".getBytes(StandardCharsets.UTF_8);
        assertThat(provider.digest(data)).isEqualTo(provider.digest(data));
    }

    @Test
    @DisplayName("digest(null) — 抛 CryptoException")
    void digest_null_shouldThrow() {
        assertThatThrownBy(() -> provider.digest(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("digestHex — 返回十六进制摘要串")
    void digestHex_shouldReturnHexString() {
        byte[] data = "hex-digest-test".getBytes(StandardCharsets.UTF_8);
        String hex = provider.digestHex(data);
        assertThat(hex).hasSize(64); // 32 字节 → 64 hex 字符
        assertThat(hex).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("constantTimeEquals — 相等返回 true，不等返回 false")
    void constantTimeEquals_shouldCompareCorrectly() {
        byte[] a = {0x01, 0x02, 0x03};
        byte[] b = {0x01, 0x02, 0x03};
        byte[] c = {0x01, 0x02, 0x04};
        assertThat(provider.constantTimeEquals(a, b)).isTrue();
        assertThat(provider.constantTimeEquals(a, c)).isFalse();
    }

    @Test
    @DisplayName("constantTimeEquals — null 入参返回 false")
    void constantTimeEquals_null_shouldReturnFalse() {
        byte[] a = {0x01};
        assertThat(provider.constantTimeEquals(null, a)).isFalse();
        assertThat(provider.constantTimeEquals(a, null)).isFalse();
        assertThat(provider.constantTimeEquals(null, null)).isFalse();
    }

    // ===== 签名/验签（SHA256withRSA 占位） =====

    @Test
    @DisplayName("sign/verifySign — RSA 密钥对签名验签往返")
    void signVerify_roundTrip_shouldVerify() {
        KeyPair kp = provider.getKeyPair();
        byte[] data = "default-gm-sign-test".getBytes(StandardCharsets.UTF_8);

        byte[] signature = provider.sign(data, kp.getPrivate());
        boolean verified = provider.verifySign(data, signature, kp.getPublic());

        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("verifySign — 篡改数据后验签失败")
    void verifySign_tamperedData_shouldFail() {
        KeyPair kp = provider.getKeyPair();
        byte[] data = "original".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "tampered".getBytes(StandardCharsets.UTF_8);

        byte[] signature = provider.sign(data, kp.getPrivate());
        assertThat(provider.verifySign(tampered, signature, kp.getPublic())).isFalse();
    }

    @Test
    @DisplayName("sign(null) — 抛 CryptoException")
    void sign_nullData_shouldThrow() {
        KeyPair kp = provider.getKeyPair();
        assertThatThrownBy(() -> provider.sign(null, kp.getPrivate()))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("sign — null key 抛 CryptoException")
    void sign_nullKey_shouldThrow() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> provider.sign(data, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("verifySign — null 入参抛 CryptoException")
    void verifySign_null_shouldThrow() {
        KeyPair kp = provider.getKeyPair();
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        byte[] sig = provider.sign(data, kp.getPrivate());
        assertThatThrownBy(() -> provider.verifySign(null, sig, kp.getPublic()))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> provider.verifySign(data, null, kp.getPublic()))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> provider.verifySign(data, sig, null))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 对称加解密（AES 占位） =====

    @Test
    @DisplayName("encrypt/decrypt — AES 对称密钥往返")
    void encryptDecrypt_symmetric_shouldRoundTrip() throws Exception {
        // 生成 AES 密钥
        javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance("AES");
        kg.init(128);
        javax.crypto.SecretKey aesKey = kg.generateKey();

        byte[] plain = "default-gm-aes-test".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = provider.encrypt(plain, aesKey);
        byte[] recovered = provider.decrypt(cipher, aesKey);

        assertThat(recovered).isEqualTo(plain);
    }

    @Test
    @DisplayName("encrypt(null) — 抛 CryptoException")
    void encrypt_null_shouldThrow() throws Exception {
        javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance("AES");
        javax.crypto.SecretKey aesKey = kg.generateKey();
        assertThatThrownBy(() -> provider.encrypt(null, aesKey))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> provider.encrypt("x".getBytes(StandardCharsets.UTF_8), null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt(null) — 抛 CryptoException")
    void decrypt_null_shouldThrow() throws Exception {
        javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance("AES");
        javax.crypto.SecretKey aesKey = kg.generateKey();
        assertThatThrownBy(() -> provider.decrypt(null, aesKey))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> provider.decrypt(new byte[16], null))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 密钥对生成 =====

    @Test
    @DisplayName("getKeyPair — 返回 RSA 2048 密钥对")
    void getKeyPair_shouldReturnRsaKeyPair() {
        KeyPair kp = provider.getKeyPair();
        assertThat(kp).isNotNull();
        assertThat(kp.getPrivate()).isNotNull();
        assertThat(kp.getPublic()).isNotNull();
        assertThat(kp.getPublic().getAlgorithm()).isEqualTo("RSA");
    }
}