package com.levango7.dataenginebdp.encaps.util;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CredentialEncryptor} 的 Profile 路由与双读迁移测试。
 *
 * <p>覆盖：#53 修复的核心——信创（xinchang）Profile 下凭据加密走 SM4，
 * 国际（international）Profile 下走 AES-GCM；切换 Profile 后历史密文仍可解密（双读）；
 * 显式 Profile 缺密钥时 fail-fast；Spring 属性解析与密钥解析。</p>
 */
@DisplayName("凭据加密器 Profile 路由与双读测试")
class CredentialEncryptorProfileTest {

    /** 32 字节 AES-256 密钥（0x00~0x1F） */
    private static final byte[] AES_KEY = new byte[32];
    /** 16 字节 SM4 密钥（0x40~0x4F） */
    private static final byte[] SM4_KEY = new byte[16];
    static {
        for (int i = 0; i < 32; i++) {
            AES_KEY[i] = (byte) i;
        }
        for (int i = 0; i < 16; i++) {
            SM4_KEY[i] = (byte) (0x40 + i);
        }
    }

    @Nested
    @DisplayName("Profile 决定主算法")
    class ProfileRouting {

        @Test
        @DisplayName("国际 Profile 使用 AES-GCM")
        void international_usesAes() {
            CredentialEncryptor enc = CredentialEncryptor.fromProfile(
                    CryptoProfile.INTERNATIONAL, null, AES_KEY);
            assertThat(enc.getAlgorithm()).isEqualTo("AES-GCM");
            assertThat(enc.isEncrypted(enc.encrypt("p@ss"))).isTrue();
            assertThat(enc.decrypt(enc.encrypt("p@ss"))).isEqualTo("p@ss");
        }

        @Test
        @DisplayName("信创 Profile 使用 SM4-CBC（#53 核心修复）")
        void xinchang_usesSm4() {
            CredentialEncryptor enc = CredentialEncryptor.fromProfile(
                    CryptoProfile.XINCHANG, SM4_KEY, null);
            assertThat(enc.getAlgorithm()).isEqualTo("SM4-CBC");
            String ciphertext = enc.encrypt("密码123");
            assertThat(ciphertext).doesNotContain("密码123");
            assertThat(enc.decrypt(ciphertext)).isEqualTo("密码123");
        }

        @Test
        @DisplayName("信创 Profile 缺 SM4 密钥 fail-fast（不静默降级）")
        void xinchang_withoutSm4Key_failsFast() {
            assertThatThrownBy(() -> CredentialEncryptor.fromProfile(
                    CryptoProfile.XINCHANG, null, AES_KEY))
                    .isInstanceOf(CryptoException.class)
                    .hasMessageContaining("SM4");
        }

        @Test
        @DisplayName("国际 Profile 缺 AES 密钥 fail-fast")
        void international_withoutAesKey_failsFast() {
            assertThatThrownBy(() -> CredentialEncryptor.fromProfile(
                    CryptoProfile.INTERNATIONAL, SM4_KEY, null))
                    .isInstanceOf(CryptoException.class)
                    .hasMessageContaining("AES");
        }
    }

    @Nested
    @DisplayName("双读迁移（切换算法后历史密文仍可解密）")
    class DualRead {

        @Test
        @DisplayName("信创上线后可解密存量 AES 密文，新写入为 SM4")
        void xinchang_decryptsLegacyAes_writesSm4() {
            // 旧版本（AES）产生的密文
            CredentialEncryptor legacy = CredentialEncryptor.fromProfile(
                    CryptoProfile.INTERNATIONAL, null, AES_KEY);
            String legacyCipher = legacy.encrypt("legacy-secret");
            assertThat(CredentialEncryptor.detectAlgorithm(legacyCipher)).isEqualTo("AES-GCM");

            // 升级为信创 Profile，同时保留 AES 密钥用于双读
            CredentialEncryptor upgraded = CredentialEncryptor.fromProfile(
                    CryptoProfile.XINCHANG, SM4_KEY, AES_KEY);

            // 历史密文仍可解密
            assertThat(upgraded.decrypt(legacyCipher)).isEqualTo("legacy-secret");
            // 新写入走 SM4
            String newCipher = upgraded.encrypt("new-secret");
            assertThat(CredentialEncryptor.detectAlgorithm(newCipher)).isEqualTo("SM4-CBC");
            assertThat(upgraded.decrypt(newCipher)).isEqualTo("new-secret");
            assertThat(upgraded.isEncrypted(legacyCipher)).isTrue();
            assertThat(upgraded.isEncrypted(newCipher)).isTrue();
        }

        @Test
        @DisplayName("国际 Profile 持有双密钥时可解密 SM4 密文")
        void international_decryptsSm4_whenBothKeysPresent() {
            CredentialEncryptor gm = CredentialEncryptor.fromProfile(
                    CryptoProfile.XINCHANG, SM4_KEY, null);
            String sm4Cipher = gm.encrypt("gm-secret");

            CredentialEncryptor dual = CredentialEncryptor.fromProfile(
                    CryptoProfile.INTERNATIONAL, SM4_KEY, AES_KEY);
            assertThat(dual.getAlgorithm()).isEqualTo("AES-GCM");
            assertThat(dual.decrypt(sm4Cipher)).isEqualTo("gm-secret");
        }

        @Test
        @DisplayName("无对应解密器时 decrypt 抛 CryptoException 而非返回原值")
        void noDecryptor_throws() {
            CredentialEncryptor gm = CredentialEncryptor.fromProfile(
                    CryptoProfile.XINCHANG, SM4_KEY, null);
            String sm4Cipher = gm.encrypt("gm-secret");

            CredentialEncryptor aesOnly = CredentialEncryptor.fromProfile(
                    CryptoProfile.INTERNATIONAL, null, AES_KEY);
            assertThat(aesOnly.isEncrypted(sm4Cipher)).isTrue();
            assertThatThrownBy(() -> aesOnly.decrypt(sm4Cipher))
                    .isInstanceOf(CryptoException.class)
                    .hasMessageContaining("SM4-CBC");
        }
    }

    @Nested
    @DisplayName("算法识别")
    class AlgorithmDetection {

        @Test
        @DisplayName("isEncrypted 同时识别 AES-GCM 与 SM4-CBC")
        void detectsBothAlgorithms() {
            CredentialEncryptor dual = CredentialEncryptor.fromProfile(
                    CryptoProfile.XINCHANG, SM4_KEY, AES_KEY);
            String aes = CredentialEncryptor.fromProfile(CryptoProfile.INTERNATIONAL, null, AES_KEY)
                    .encrypt("a");
            String sm4 = CredentialEncryptor.fromProfile(CryptoProfile.XINCHANG, SM4_KEY, null)
                    .encrypt("b");
            assertThat(dual.isEncrypted(aes)).isTrue();
            assertThat(dual.isEncrypted(sm4)).isTrue();
            assertThat(dual.isEncrypted("plain-text")).isFalse();
            assertThat(dual.isEncrypted("")).isFalse();
            assertThat(dual.isEncrypted(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("配置解析（CredentialEncryptorConfig）")
    class ConfigResolution {

        @Test
        @DisplayName("显式 app.crypto.active-profile 优先")
        void explicitProfileWins() {
            MockEnvironment env = new MockEnvironment()
                    .withProperty("spring.profiles.active", "international");
            CryptoProfile profile = CredentialEncryptorConfig.resolveProfile(
                    "xinchang", env, AES_KEY, SM4_KEY);
            assertThat(profile).isEqualTo(CryptoProfile.XINCHANG);
        }

        @Test
        @DisplayName("回退到 spring.profiles.active")
        void springProfileUsed() {
            MockEnvironment env = new MockEnvironment()
                    .withProperty("spring.profiles.active", "prod,xinchang");
            CryptoProfile profile = CredentialEncryptorConfig.resolveProfile(
                    null, env, AES_KEY, SM4_KEY);
            assertThat(profile).isEqualTo(CryptoProfile.XINCHANG);
        }

        @Test
        @DisplayName("均未声明时优先 AES（向后兼容）")
        void defaultsToAesForBackwardCompat() {
            assertThat(CredentialEncryptorConfig.resolveProfile(null, new MockEnvironment(), AES_KEY, SM4_KEY))
                    .isEqualTo(CryptoProfile.INTERNATIONAL);
        }

        @Test
        @DisplayName("仅配置 SM4 密钥时回退为信创")
        void sm4OnlyFallsBackToXinchang() {
            assertThat(CredentialEncryptorConfig.resolveProfile(null, new MockEnvironment(), null, SM4_KEY))
                    .isEqualTo(CryptoProfile.XINCHANG);
        }

        @Test
        @DisplayName("decodeSm4Key 解析 32 位 hex")
        void decodeSm4Key_valid() {
            byte[] key = CredentialEncryptorConfig.decodeSm4Key("0123456789abcdef0123456789abcdef");
            assertThat(key).hasSize(16);
            assertThat(key[0]).isEqualTo((byte) 0x01);
            assertThat(key[15]).isEqualTo((byte) 0xef);
        }

        @Test
        @DisplayName("decodeSm4Key 拒绝错误长度与非 hex")
        void decodeSm4Key_invalid() {
            assertThatThrownBy(() -> CredentialEncryptorConfig.decodeSm4Key("abcd"))
                    .isInstanceOf(CryptoException.class);
            assertThatThrownBy(() -> CredentialEncryptorConfig.decodeSm4Key("zz".repeat(16)))
                    .isInstanceOf(CryptoException.class);
            assertThat(CredentialEncryptorConfig.decodeSm4Key("  ")).isNull();
        }

        @Test
        @DisplayName("decodeAesKey 解析 Base64 32 字节并校验长度")
        void decodeAesKey_validAndInvalid() {
            String base64 = Base64.getEncoder().encodeToString(AES_KEY);
            assertThat(CredentialEncryptorConfig.decodeAesKey(base64)).hasSize(32);
            assertThat(CredentialEncryptorConfig.decodeAesKey("")).isNull();
            String shortBase64 = Base64.getEncoder().encodeToString(new byte[16]);
            assertThatThrownBy(() -> CredentialEncryptorConfig.decodeAesKey(shortBase64))
                    .isInstanceOf(CryptoException.class)
                    .hasMessageContaining("32");
        }
    }
}
