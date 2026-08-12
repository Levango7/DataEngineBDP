package com.levango7.dataenginebdp.encaps.crypto.jwt.storage;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.intl.AESProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IntlStorageCipher} 单元测试。
 *
 * <p>覆盖 AES-GCM 存储加密往返、密文格式、密钥校验、异常处理等。</p>
 */
class IntlStorageCipherTest {

    private IntlStorageCipher cipher;
    private byte[] key;

    @BeforeEach
    void setUp() {
        key = new byte[32];  // AES-256
        new SecureRandom().nextBytes(key);
        cipher = new IntlStorageCipher(key);
    }

    @Test
    @DisplayName("加密 → 解密 往返 — 字节")
    void encryptDecrypt_bytes_roundTrip() {
        byte[] plaintext = "sensitive-data-123".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = cipher.encrypt(plaintext);
        byte[] decrypted = cipher.decrypt(ciphertext);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("加密 → 解密 往返 — 字符串")
    void encryptDecryptString_roundTrip() {
        String plaintext = "Hello-World-敏感数据";
        String ciphertext = cipher.encryptString(plaintext);
        String decrypted = cipher.decryptString(ciphertext);
        assertThat(decrypted).isEqualTo(plaintext);
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
        byte[] plaintext = new byte[1024 * 64];
        new SecureRandom().nextBytes(plaintext);
        byte[] ciphertext = cipher.encrypt(plaintext);
        byte[] decrypted = cipher.decrypt(ciphertext);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("AES-128 密钥 — 正常工作")
    void aes128Key_worksCorrectly() {
        byte[] aes128Key = new byte[16];
        new SecureRandom().nextBytes(aes128Key);
        IntlStorageCipher c = new IntlStorageCipher(aes128Key);
        String plain = "test";
        String cipher = c.encryptString(plain);
        assertThat(c.decryptString(cipher)).isEqualTo(plain);
    }

    @Test
    @DisplayName("加密 — 每次生成不同 IV，密文不同")
    void encrypt_samePlaintext_differentCiphertext() {
        byte[] plaintext = "same".getBytes(StandardCharsets.UTF_8);
        byte[] c1 = cipher.encrypt(plaintext);
        byte[] c2 = cipher.encrypt(plaintext);
        assertThat(c1).isNotEqualTo(c2);
    }

    @Test
    @DisplayName("getAlgorithm — 返回 AES-GCM")
    void getAlgorithm_returnsAesGcm() {
        assertThat(cipher.getAlgorithm()).isEqualTo("AES-GCM");
    }

    @Test
    @DisplayName("isGm — 返回 false")
    void isGm_returnsFalse() {
        assertThat(cipher.isGm()).isFalse();
    }

    // ===== 异常处理 =====

    @Test
    @DisplayName("构造 — null 密钥抛异常")
    void constructor_nullKey_throwsException() {
        assertThatThrownBy(() -> new IntlStorageCipher(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("构造 — 错误长度密钥抛异常")
    void constructor_wrongKeyLength_throwsException() {
        assertThatThrownBy(() -> new IntlStorageCipher(new byte[15]))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> new IntlStorageCipher(new byte[20]))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("构造 — null AES Provider 抛异常")
    void constructor_nullAes_throwsException() {
        assertThatThrownBy(() -> new IntlStorageCipher(null, key))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("加密 — null 明文抛异常")
    void encrypt_nullPlaintext_throwsException() {
        assertThatThrownBy(() -> cipher.encrypt(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("解密 — null 密文抛异常")
    void decrypt_nullCiphertext_throwsException() {
        assertThatThrownBy(() -> cipher.decrypt(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("解密 — 密文过短抛异常")
    void decrypt_tooShort_throwsException() {
        assertThatThrownBy(() -> cipher.decrypt(new byte[5]))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("解密 — 算法不匹配抛异常")
    void decrypt_algorithmMismatch_throwsException() {
        // 构造一个 SM4-CBC 格式的密文
        byte[] gmKey = new byte[16];
        new SecureRandom().nextBytes(gmKey);
        GmStorageCipher gmCipher = new GmStorageCipher(gmKey);
        byte[] gmCiphertext = gmCipher.encrypt("test".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> cipher.decrypt(gmCiphertext))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("解密 — 错误密钥应失败（GCM 标签验证）")
    void decrypt_wrongKey_shouldFail() {
        byte[] plaintext = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = cipher.encrypt(plaintext);

        byte[] wrongKey = new byte[32];
        new SecureRandom().nextBytes(wrongKey);
        IntlStorageCipher wrongCipher = new IntlStorageCipher(wrongKey);
        assertThatThrownBy(() -> wrongCipher.decrypt(ciphertext))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("注入 AESProvider — 正常工作")
    void injectAesProvider_worksCorrectly() {
        AESProvider aes = new AESProvider();
        IntlStorageCipher injected = new IntlStorageCipher(aes, key);
        String plain = "test";
        String cipher = injected.encryptString(plain);
        assertThat(injected.decryptString(cipher)).isEqualTo(plain);
    }
}