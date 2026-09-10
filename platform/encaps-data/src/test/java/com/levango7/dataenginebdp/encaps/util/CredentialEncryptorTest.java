package com.levango7.dataenginebdp.encaps.util;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CredentialEncryptor} 单元测试。
 *
 * <p>覆盖加密往返、密文不可逆推、空值处理、密钥校验、环境变量加载等场景。</p>
 */
@DisplayName("凭据加密器（AES-256-GCM）测试")
class CredentialEncryptorTest {

    /** 测试用 AES-256 密钥（32 字节，0x00~0x1F） */
    private static final byte[] TEST_KEY = new byte[32];
    static {
        for (int i = 0; i < 32; i++) {
            TEST_KEY[i] = (byte) i;
        }
    }

    /** 测试用密钥的 Base64 编码 */
    private static final String TEST_KEY_BASE64 = Base64.getEncoder().encodeToString(TEST_KEY);

    private static CredentialEncryptor encryptor;

    @BeforeAll
    static void setUp() {
        encryptor = new CredentialEncryptor(TEST_KEY);
    }

    // ===== 加密往返测试 =====

    @Nested
    @DisplayName("加密往返（encrypt → decrypt 恢复原文）")
    class RoundTrip {

        @Test
        @DisplayName("普通密码往返")
        void roundTrip_normalPassword() {
            String plaintext = "my-secret-p@ssw0rd";
            String ciphertext = encryptor.encrypt(plaintext);
            String decrypted = encryptor.decrypt(ciphertext);

            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("长密码往返（128 字符）")
        void roundTrip_longPassword() {
            String plaintext = "a".repeat(128);
            String ciphertext = encryptor.encrypt(plaintext);
            String decrypted = encryptor.decrypt(ciphertext);

            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("含中文密码往返")
        void roundTrip_unicodePassword() {
            String plaintext = "密码123!@#";
            String ciphertext = encryptor.encrypt(plaintext);
            String decrypted = encryptor.decrypt(ciphertext);

            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("含特殊字符密码往返")
        void roundTrip_specialChars() {
            String plaintext = "P@$$w0rd!#$%^&*()_+-=[]{}|;':\",./<>?`~";
            String ciphertext = encryptor.encrypt(plaintext);
            String decrypted = encryptor.decrypt(ciphertext);

            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("单字符密码往返")
        void roundTrip_singleChar() {
            String plaintext = "x";
            String ciphertext = encryptor.encrypt(plaintext);
            String decrypted = encryptor.decrypt(ciphertext);

            assertThat(decrypted).isEqualTo(plaintext);
        }
    }

    // ===== 密文安全性测试 =====

    @Nested
    @DisplayName("密文安全性")
    class CiphertextSecurity {

        @Test
        @DisplayName("密文不等于明文")
        void ciphertext_notEqualToPlaintext() {
            String plaintext = "secret123";
            String ciphertext = encryptor.encrypt(plaintext);

            assertThat(ciphertext).isNotEqualTo(plaintext);
        }

        @Test
        @DisplayName("密文不包含明文片段")
        void ciphertext_doesNotContainPlaintext() {
            String plaintext = "very-secret-password";
            String ciphertext = encryptor.encrypt(plaintext);

            assertThat(ciphertext).doesNotContain(plaintext);
        }

        @Test
        @DisplayName("相同明文两次加密产生不同密文（随机 IV）")
        void encrypt_samePlaintextDifferentCiphertext() {
            String plaintext = "same-password";
            String c1 = encryptor.encrypt(plaintext);
            String c2 = encryptor.encrypt(plaintext);

            // GCM 模式使用随机 IV，相同明文应产生不同密文
            assertThat(c1).isNotEqualTo(c2);
            // 但都能解密为同一明文
            assertThat(encryptor.decrypt(c1)).isEqualTo(plaintext);
            assertThat(encryptor.decrypt(c2)).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("密文标识为加密格式")
        void isEncrypted_detectsCiphertext() {
            String plaintext = "test-pwd";
            String ciphertext = encryptor.encrypt(plaintext);

            assertThat(encryptor.isEncrypted(ciphertext)).isTrue();
            assertThat(encryptor.isEncrypted(plaintext)).isFalse();
        }
    }

    // ===== 空值与边界处理 =====

    @Nested
    @DisplayName("空值与边界处理")
    class EdgeCases {

        @Test
        @DisplayName("空字符串加密返回空字符串")
        void encrypt_emptyString() {
            assertThat(encryptor.encrypt("")).isEmpty();
        }

        @Test
        @DisplayName("空字符串解密返回空字符串")
        void decrypt_emptyString() {
            assertThat(encryptor.decrypt("")).isEmpty();
        }

        @Test
        @DisplayName("isEncrypted 对 null/空返回 false")
        void isEncrypted_nullOrEmpty() {
            assertThat(encryptor.isEncrypted(null)).isFalse();
            assertThat(encryptor.isEncrypted("")).isFalse();
        }

        @Test
        @DisplayName("encrypt null 抛 NullPointerException")
        void encrypt_nullThrows() {
            assertThatThrownBy(() -> encryptor.encrypt(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("decrypt null 抛 NullPointerException")
        void decrypt_nullThrows() {
            assertThatThrownBy(() -> encryptor.decrypt(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ===== 密钥校验测试 =====

    @Nested
    @DisplayName("密钥校验")
    class KeyValidation {

        @Test
        @DisplayName("32 字节密钥可用（AES-256）")
        void key_32bytesAccepted() {
            byte[] key32 = new byte[32];
            // 不抛异常即通过
            CredentialEncryptor enc = new CredentialEncryptor(key32);
            assertThat(enc.encrypt("test")).isNotEmpty();
        }

        @Test
        @DisplayName("16 字节密钥被拒绝（强制 AES-256）")
        void key_16bytesRejected() {
            assertThatThrownBy(() -> new CredentialEncryptor(new byte[16]))
                    .isInstanceOf(CryptoException.class)
                    .hasMessageContaining("32 字节");
        }

        @Test
        @DisplayName("非 16/32 字节密钥抛 CryptoException")
        void key_invalidLengthThrows() {
            assertThatThrownBy(() -> new CredentialEncryptor(new byte[20]))
                    .isInstanceOf(CryptoException.class)
                    .hasMessageContaining("32 字节");
        }

        @Test
        @DisplayName("null 密钥抛 NullPointerException")
        void key_nullThrows() {
            assertThatThrownBy(() -> new CredentialEncryptor(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("错误密钥解密抛 CryptoException（AEAD 标签验证失败）")
        void decrypt_wrongKeyThrows() {
            // 用密钥 A 加密
            CredentialEncryptor encA = new CredentialEncryptor(TEST_KEY);
            String ciphertext = encA.encrypt("secret");

            // 用密钥 B 解密（应失败）
            byte[] wrongKey = new byte[32];
            for (int i = 0; i < 32; i++) {
                wrongKey[i] = (byte) (i + 100);
            }
            CredentialEncryptor encB = new CredentialEncryptor(wrongKey);

            assertThatThrownBy(() -> encB.decrypt(ciphertext))
                    .isInstanceOf(CryptoException.class);
        }
    }

    // ===== 工厂方法测试 =====

    @Nested
    @DisplayName("工厂方法")
    class FactoryMethods {

        @Test
        @DisplayName("fromBase64Key 正确构造")
        void fromBase64Key_valid() {
            CredentialEncryptor enc = CredentialEncryptor.fromBase64Key(TEST_KEY_BASE64);
            String plaintext = "factory-test";
            assertThat(enc.decrypt(enc.encrypt(plaintext))).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("fromBase64Key 空字符串抛异常")
        void fromBase64Key_emptyThrows() {
            assertThatThrownBy(() -> CredentialEncryptor.fromBase64Key(""))
                    .isInstanceOf(CryptoException.class)
                    .hasMessageContaining("不可为空");
        }

        @Test
        @DisplayName("fromBase64Key 非法 Base64 抛异常")
        void fromBase64Key_invalidBase64Throws() {
            assertThatThrownBy(() -> CredentialEncryptor.fromBase64Key("!!!not-base64!!!"))
                    .isInstanceOf(CryptoException.class);
        }

        @Test
        @DisplayName("fromBase64Key 解码后长度非 32 字节抛异常")
        void fromBase64Key_wrongLengthThrows() {
            // 16 字节密钥的 Base64（长度合法但不是 32 字节）
            String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
            // 注意：16 字节也是合法的 AES-128 密钥，IntlStorageCipher 接受 16 或 32
            // 但 CredentialEncryptor 强制要求 32 字节
            assertThatThrownBy(() -> CredentialEncryptor.fromBase64Key(shortKey))
                    .isInstanceOf(CryptoException.class)
                    .hasMessageContaining("32 字节");
        }
    }
}