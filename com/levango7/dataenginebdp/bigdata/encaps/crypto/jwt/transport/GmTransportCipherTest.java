package com.shuqing.bigdata.encaps.crypto.jwt.transport;

import com.shuqing.bigdata.encaps.crypto.CryptoException;
import com.shuqing.bigdata.encaps.crypto.gm.SM2Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GmTransportCipher} 单元测试。
 *
 * <p>覆盖 SM2+SM4 数字信封加密/解密往返、密钥校验、异常处理等。</p>
 */
class GmTransportCipherTest {

    private SM2Provider sm2;
    private SM2Provider.Sm2KeyPair keyPair;
    private GmTransportCipher cipher;

    @BeforeEach
    void setUp() {
        sm2 = new SM2Provider();
        keyPair = sm2.generateKeyPair();
        cipher = new GmTransportCipher(keyPair.getPublicKeyQ(), keyPair.getPrivateKeyD());
    }

    // ===== 加密/解密往返 =====

    @Test
    @DisplayName("加密 → 解密 往返 — 字节")
    void encryptDecrypt_bytes_roundTrip() {
        byte[] plaintext = "sensitive-payload-123".getBytes(StandardCharsets.UTF_8);
        DigitalEnvelope envelope = cipher.encrypt(plaintext);
        byte[] decrypted = cipher.decrypt(envelope);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("加密 → 解密 往返 — 字符串")
    void encryptDecryptString_roundTrip() {
        String plaintext = "你好世界-Hello-敏感传输数据";
        String envelope = cipher.encryptString(plaintext);
        String decrypted = cipher.decryptString(envelope);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("加密 → 解密 往返 — 空明文")
    void encryptDecrypt_emptyPlaintext_roundTrip() {
        DigitalEnvelope envelope = cipher.encrypt(new byte[0]);
        byte[] decrypted = cipher.decrypt(envelope);
        assertThat(decrypted).isEqualTo(new byte[0]);
    }

    @Test
    @DisplayName("加密 → 解密 往返 — 大体积数据")
    void encryptDecrypt_largeData_roundTrip() {
        byte[] plaintext = new byte[1024 * 64];  // 64KB
        new SecureRandom().nextBytes(plaintext);
        DigitalEnvelope envelope = cipher.encrypt(plaintext);
        byte[] decrypted = cipher.decrypt(envelope);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    // ===== 信封属性 =====

    @Test
    @DisplayName("加密 — 每次生成不同会话密钥，信封不同")
    void encrypt_samePlaintext_differentEnvelope() {
        byte[] plaintext = "same".getBytes(StandardCharsets.UTF_8);
        DigitalEnvelope e1 = cipher.encrypt(plaintext);
        DigitalEnvelope e2 = cipher.encrypt(plaintext);
        assertThat(e1).isNotEqualTo(e2);
    }

    @Test
    @DisplayName("getAlgorithm — 返回 SM2+SM4-CBC")
    void getAlgorithm_returnsSm2Sm4Cbc() {
        assertThat(cipher.getAlgorithm()).isEqualTo("SM2+SM4-CBC");
    }

    @Test
    @DisplayName("isGm — 返回 true")
    void isGm_returnsTrue() {
        assertThat(cipher.isGm()).isTrue();
    }

    // ===== 仅加密/仅解密模式 =====

    @Test
    @DisplayName("仅公钥 — 可加密不可解密")
    void onlyPublicKey_canEncryptCannotDecrypt() {
        GmTransportCipher encryptOnly = new GmTransportCipher(keyPair.getPublicKeyQ());
        byte[] plaintext = "test".getBytes(StandardCharsets.UTF_8);
        DigitalEnvelope envelope = encryptOnly.encrypt(plaintext);
        assertThat(envelope).isNotNull();

        assertThatThrownBy(() -> encryptOnly.decrypt(envelope))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("privateKeyD");
    }

    @Test
    @DisplayName("仅私钥 — 可解密不可加密")
    void onlyPrivateKey_canDecryptCannotEncrypt() {
        GmTransportCipher decryptOnly = new GmTransportCipher(null, keyPair.getPrivateKeyD());
        // 先用完整 cipher 加密
        DigitalEnvelope envelope = cipher.encrypt("test".getBytes(StandardCharsets.UTF_8));
        // 用仅私钥 cipher 解密
        byte[] decrypted = decryptOnly.decrypt(envelope);
        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo("test");

        assertThatThrownBy(() -> decryptOnly.encrypt("test".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("publicKeyQ");
    }

    // ===== 异常处理 =====

    @Test
    @DisplayName("构造 — 公钥私钥都为 null 抛异常")
    void constructor_bothNull_throwsException() {
        assertThatThrownBy(() -> new GmTransportCipher(null, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("构造 — null SM2/SM4 抛异常")
    void constructor_nullProviders_throwsException() {
        assertThatThrownBy(() -> new GmTransportCipher(null, null, keyPair.getPublicKeyQ(), keyPair.getPrivateKeyD()))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("加密 — null 明文抛异常")
    void encrypt_nullPlaintext_throwsException() {
        assertThatThrownBy(() -> cipher.encrypt(null))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> cipher.encryptString(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("解密 — null 信封抛异常")
    void decrypt_nullEnvelope_throwsException() {
        assertThatThrownBy(() -> cipher.decrypt(null))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> cipher.decryptString(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("解密 — 错误私钥应失败")
    void decrypt_wrongPrivateKey_shouldFail() {
        DigitalEnvelope envelope = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8));
        SM2Provider.Sm2KeyPair wrongKey = sm2.generateKeyPair();
        GmTransportCipher wrongCipher = new GmTransportCipher(null, wrongKey.getPrivateKeyD());
        assertThatThrownBy(() -> wrongCipher.decrypt(envelope))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("解密 — 非法 Base64 信封抛异常")
    void decryptString_invalidBase64_throwsException() {
        assertThatThrownBy(() -> cipher.decryptString("@@@invalid@@@"))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 跨实例互通 =====

    @Test
    @DisplayName("跨实例互通 — A 加密 B 解密")
    void crossInstance_interop() {
        // 模拟发送方与接收方使用相同密钥对的不同实例
        GmTransportCipher sender = new GmTransportCipher(keyPair.getPublicKeyQ());
        GmTransportCipher receiver = new GmTransportCipher(null, keyPair.getPrivateKeyD());

        String payload = "cross-instance-payload";
        String envelope = sender.encryptString(payload);
        String decrypted = receiver.decryptString(envelope);
        assertThat(decrypted).isEqualTo(payload);
    }

    @Test
    @DisplayName("getSm2/getSm4 — 返回内部 Provider")
    void getProviders_returnsInternal() {
        assertThat(cipher.getSm2()).isNotNull();
        assertThat(cipher.getSm4()).isNotNull();
    }
}