package com.levango7.dataenginebdp.encaps.crypto.jwt.storage;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.gm.GmAlgorithm;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM4Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GmStorageCipher} 单元测试。
 *
 * <p>覆盖 SM4-CBC 存储加密往返、密文格式、密钥校验、异常处理等。</p>
 */
class GmStorageCipherTest {

    private GmStorageCipher cipher;
    private byte[] key;

    @BeforeEach
    void setUp() {
        key = new byte[GmAlgorithm.SM4_KEY_LEN];
        new SecureRandom().nextBytes(key);
        cipher = new GmStorageCipher(key);
    }

    // ===== 加密/解密往返 =====

    @Test
    @DisplayName("加密 → 解密 往返 — 字节")
    void encryptDecrypt_bytes_roundTrip() {
        byte[] plaintext = "sensitive-data-123".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = cipher.encrypt(plaintext);
        byte[] decrypted = cipher.decrypt(ciphertext);

        assertThat(decrypted).isEqualTo(plaintext);
        assertThat(ciphertext).isNotEqualTo(plaintext);
    }

    @Test
    @DisplayName("加密 → 解密 往返 — 字符串")
    void encryptDecryptString_roundTrip() {
        String plaintext = "你好世界-Hello-World-敏感数据";
        String ciphertext = cipher.encryptString(plaintext);
        String decrypted = cipher.decryptString(ciphertext);

        assertThat(decrypted).isEqualTo(plaintext);
        assertThat(ciphertext).isNotEqualTo(plaintext);
    }

    @Test
    @DisplayName("加密 → 解密 往返 — 空明文")
    void encryptDecrypt_emptyPlaintext_roundTrip() {
        byte[] ciphertext = cipher.encrypt(new byte[0]);
        byte[] decrypted = cipher.decrypt(ciphertext);
        assertThat(decrypted).isEqualTo(new byte[0]);
    }

    @Test
    @DisplayName("加密 → 解密 往返 — 大体积数据")
    void encryptDecrypt_largeData_roundTrip() {
        byte[] plaintext = new byte[1024 * 64];  // 64KB
        new SecureRandom().nextBytes(plaintext);
        byte[] ciphertext = cipher.encrypt(plaintext);
        byte[] decrypted = cipher.decrypt(ciphertext);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    // ===== 密文格式 =====

    @Test
    @DisplayName("加密 — 每次生成不同 IV，密文不同")
    void encrypt_samePlaintext_differentCiphertext() {
        byte[] plaintext = "same-data".getBytes(StandardCharsets.UTF_8);
        byte[] c1 = cipher.encrypt(plaintext);
        byte[] c2 = cipher.encrypt(plaintext);
        assertThat(c1).isNotEqualTo(c2);
    }

    @Test
    @DisplayName("getAlgorithm — 返回 SM4-CBC")
    void getAlgorithm_returnsSm4Cbc() {
        assertThat(cipher.getAlgorithm()).isEqualTo("SM4-CBC");
    }

    @Test
    @DisplayName("isGm — 返回 true")
    void isGm_returnsTrue() {
        assertThat(cipher.isGm()).isTrue();
    }

    // ===== 异常处理 =====

    @Test
    @DisplayName("构造 — null 密钥抛异常")
    void constructor_nullKey_throwsException() {
        assertThatThrownBy(() -> new GmStorageCipher(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("构造 — 错误长度密钥抛异常")
    void constructor_wrongKeyLength_throwsException() {
        assertThatThrownBy(() -> new GmStorageCipher(new byte[15]))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> new GmStorageCipher(new byte[17]))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("构造 — null SM4 Provider 抛异常")
    void constructor_nullSm4_throwsException() {
        assertThatThrownBy(() -> new GmStorageCipher(null, key))
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
    @DisplayName("解密 — null 密文抛异常")
    void decrypt_nullCiphertext_throwsException() {
        assertThatThrownBy(() -> cipher.decrypt(null))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> cipher.decryptString(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("解密 — 算法不匹配抛异常")
    void decrypt_algorithmMismatch_throwsException() {
        // 构造一个 AES-GCM 格式的密文，用 SM4 解密应失败
        byte[] fakeCipher = new IntlStorageCipherTestHelper().encrypt("test");
        assertThatThrownBy(() -> cipher.decrypt(fakeCipher))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("解密 — 密文过短抛异常")
    void decrypt_tooShort_throwsException() {
        assertThatThrownBy(() -> cipher.decrypt(new byte[5]))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("解密 — 非法 Base64 抛异常")
    void decrypt_invalidBase64_throwsException() {
        assertThatThrownBy(() -> cipher.decrypt("@@@invalid@@@".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 注入构造 =====

    @Test
    @DisplayName("注入 SM4Provider — 正常工作")
    void injectSm4Provider_worksCorrectly() {
        SM4Provider sm4 = new SM4Provider();
        GmStorageCipher injected = new GmStorageCipher(sm4, key);
        String plain = "test-data";
        String cipher = injected.encryptString(plain);
        assertThat(injected.decryptString(cipher)).isEqualTo(plain);
    }

    // ===== 密钥隔离 =====

    @Test
    @DisplayName("不同密钥 — 解密应失败")
    void decryptWithDifferentKey_shouldFail() {
        byte[] plaintext = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = cipher.encrypt(plaintext);

        byte[] wrongKey = new byte[GmAlgorithm.SM4_KEY_LEN];
        new SecureRandom().nextBytes(wrongKey);
        // 确保密钥不同
        if (Arrays.equals(key, wrongKey)) {
            wrongKey[0] ^= 0x01;
        }
        GmStorageCipher wrongCipher = new GmStorageCipher(wrongKey);
        assertThatThrownBy(() -> wrongCipher.decrypt(ciphertext))
                .isInstanceOf(CryptoException.class);
    }

    /**
     * 测试辅助：构造 IntlStorageCipher 密文用于算法不匹配测试。
     */
    static class IntlStorageCipherTestHelper {
        byte[] encrypt(String text) {
            byte[] aesKey = new byte[16];
            new SecureRandom().nextBytes(aesKey);
            return new IntlStorageCipher(aesKey).encrypt(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}