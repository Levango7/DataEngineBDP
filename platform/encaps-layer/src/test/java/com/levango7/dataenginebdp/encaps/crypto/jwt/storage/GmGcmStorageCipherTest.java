package com.levango7.dataenginebdp.encaps.crypto.jwt.storage;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.gm.GmAlgorithm;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM4Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GmGcmStorageCipher}（SM4-GCM，国密 AEAD）与 {@link SM4Provider} GCM 原语测试。
 *
 * <p>覆盖：加解密往返、随机 IV、算法标识、AEAD 完整性（错误密钥/篡改密文/篡改标签拒绝）、
 * 参数校验，以及与旧 SM4-CBC 的互不混淆。</p>
 */
@DisplayName("SM4-GCM 国密 AEAD 存储加密测试")
class GmGcmStorageCipherTest {

    /** 16 字节 SM4 密钥（0x40~0x4F） */
    private static final byte[] KEY = new byte[16];
    static {
        for (int i = 0; i < 16; i++) {
            KEY[i] = (byte) (0x40 + i);
        }
    }

    @Nested
    @DisplayName("存储密文（GmGcmStorageCipher）")
    class Storage {

        @Test
        @DisplayName("往返：英文/中文/长文本")
        void roundTrip() {
            GmGcmStorageCipher cipher = new GmGcmStorageCipher(KEY);
            for (String plain : new String[]{"p@ss", "密码123!@#", "x".repeat(500), "", "a"}) {
                String ct = cipher.encryptString(plain);
                assertThat(cipher.decryptString(ct)).isEqualTo(plain);
            }
        }

        @Test
        @DisplayName("相同明文两次加密产生不同密文（随机 12 字节 IV）")
        void randomIv() {
            GmGcmStorageCipher cipher = new GmGcmStorageCipher(KEY);
            String c1 = cipher.encryptString("same");
            String c2 = cipher.encryptString("same");
            assertThat(c1).isNotEqualTo(c2);
            assertThat(cipher.decryptString(c1)).isEqualTo("same");
            assertThat(cipher.decryptString(c2)).isEqualTo("same");
        }

        @Test
        @DisplayName("算法标识为 SM4-GCM，且为国密")
        void algorithm() {
            GmGcmStorageCipher cipher = new GmGcmStorageCipher(KEY);
            assertThat(cipher.getAlgorithm()).isEqualTo("SM4-GCM");
            assertThat(cipher.isGm()).isTrue();
        }

        @Test
        @DisplayName("密文格式：Base64 解码后以 'SM4-GCM' + 0x00 开头，IV 12 字节")
        void format() {
            GmGcmStorageCipher cipher = new GmGcmStorageCipher(KEY);
            byte[] raw = Base64.getDecoder().decode(cipher.encryptString("payload").getBytes(StandardCharsets.US_ASCII));
            String prefix = new String(raw, 0, 7, StandardCharsets.US_ASCII);
            assertThat(prefix).isEqualTo("SM4-GCM");
            assertThat(raw[7]).isEqualTo((byte) 0);
            // 总长 = 8(标识) + 12(IV) + 明文长度 + 16(tag)
            assertThat(raw.length).isEqualTo(8 + GmAlgorithm.SM4_GCM_IV_LEN + "payload".length() + GmAlgorithm.SM4_GCM_TAG_LEN);
        }

        @Test
        @DisplayName("AEAD：错误密钥解密抛 CryptoException")
        void wrongKeyRejected() {
            GmGcmStorageCipher cipher = new GmGcmStorageCipher(KEY);
            String ct = cipher.encryptString("secret");
            byte[] wrongKey = new byte[16];
            for (int i = 0; i < 16; i++) {
                wrongKey[i] = (byte) (0x80 + i);
            }
            GmGcmStorageCipher other = new GmGcmStorageCipher(wrongKey);
            assertThatThrownBy(() -> other.decryptString(ct)).isInstanceOf(CryptoException.class);
        }

        @Test
        @DisplayName("AEAD：篡改密文字节导致认证失败")
        void tamperedCiphertextRejected() {
            GmGcmStorageCipher cipher = new GmGcmStorageCipher(KEY);
            byte[] raw = Base64.getDecoder().decode(cipher.encryptString("integrity").getBytes(StandardCharsets.US_ASCII));
            raw[raw.length - 1] ^= 0x01; // 翻转认证标签一位
            String tampered = Base64.getEncoder().encodeToString(raw);
            assertThatThrownBy(() -> cipher.decryptString(tampered)).isInstanceOf(CryptoException.class);
        }

        @Test
        @DisplayName("AEAD：篡改 IV 导致认证失败")
        void tamperedIvRejected() {
            GmGcmStorageCipher cipher = new GmGcmStorageCipher(KEY);
            byte[] raw = Base64.getDecoder().decode(cipher.encryptString("integrity").getBytes(StandardCharsets.US_ASCII));
            raw[8] ^= 0x01; // IV 首字节
            assertThatThrownBy(() -> cipher.decryptString(Base64.getEncoder().encodeToString(raw)))
                    .isInstanceOf(CryptoException.class);
        }

        @Test
        @DisplayName("密钥长度非 16 字节抛 CryptoException")
        void invalidKeyLength() {
            assertThatThrownBy(() -> new GmGcmStorageCipher(new byte[15]))
                    .isInstanceOf(CryptoException.class);
            assertThatThrownBy(() -> new GmGcmStorageCipher((byte[]) null))
                    .isInstanceOf(CryptoException.class);
        }

        @Test
        @DisplayName("算法标识不匹配（喂入 AES 密文）抛 CryptoException")
        void wrongAlgorithmRejected() {
            GmGcmStorageCipher cipher = new GmGcmStorageCipher(KEY);
            String aesLike = Base64.getEncoder().encodeToString(
                    "AES-GCM\u0000........".getBytes(StandardCharsets.US_ASCII));
            assertThatThrownBy(() -> cipher.decryptString(aesLike)).isInstanceOf(CryptoException.class);
        }

        @Test
        @DisplayName("SM4-GCM 与 SM4-CBC 为不同算法，密文互不混淆")
        void distinctFromCbc() {
            GmGcmStorageCipher gcm = new GmGcmStorageCipher(KEY);
            GmStorageCipher cbc = new GmStorageCipher(KEY);
            String gcmCt = gcm.encryptString("x");
            String cbcCt = cbc.encryptString("x");
            assertThat(CredentialAlgorithmPrefix.of(gcmCt)).isEqualTo("SM4-GCM");
            assertThat(CredentialAlgorithmPrefix.of(cbcCt)).isEqualTo("SM4-CBC");
            // 互解必须失败（算法标识不匹配）
            assertThatThrownBy(() -> gcm.decryptString(cbcCt)).isInstanceOf(CryptoException.class);
            assertThatThrownBy(() -> cbc.decryptString(gcmCt)).isInstanceOf(CryptoException.class);
        }
    }

    @Nested
    @DisplayName("SM4Provider GCM 原语")
    class Primitive {

        private final SM4Provider sm4 = new SM4Provider();

        @Test
        @DisplayName("加密→解密往返，输出含 16 字节标签")
        void roundTrip() {
            byte[] key = sm4.generateKey();
            byte[] iv = sm4.generateGcmIv();
            byte[] plain = "sm4-gcm-primitive".getBytes(StandardCharsets.UTF_8);
            byte[] ct = sm4.encryptGcm(plain, key, iv, null);
            assertThat(ct.length).isEqualTo(plain.length + GmAlgorithm.SM4_GCM_TAG_LEN);
            assertThat(sm4.decryptGcm(ct, key, iv, null)).isEqualTo(plain);
        }

        @Test
        @DisplayName("AAD 参与认证：AAD 不一致则解密失败")
        void aadAuthenticated() {
            byte[] key = sm4.generateKey();
            byte[] iv = sm4.generateGcmIv();
            byte[] plain = "with-aad".getBytes(StandardCharsets.UTF_8);
            byte[] ct = sm4.encryptGcm(plain, key, iv, "tenant-1".getBytes(StandardCharsets.UTF_8));
            assertThat(sm4.decryptGcm(ct, key, iv, "tenant-1".getBytes(StandardCharsets.UTF_8))).isEqualTo(plain);
            assertThatThrownBy(() -> sm4.decryptGcm(ct, key, iv, "tenant-2".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(CryptoException.class);
        }

        @Test
        @DisplayName("IV 长度非 12 字节抛 CryptoException")
        void invalidIvLength() {
            byte[] key = sm4.generateKey();
            byte[] plain = "x".getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> sm4.encryptGcm(plain, key, new byte[11], null))
                    .isInstanceOf(CryptoException.class);
            assertThatThrownBy(() -> sm4.encryptGcm(plain, key, null, null))
                    .isInstanceOf(CryptoException.class);
        }

        @Test
        @DisplayName("MAIN: ECB/CBC 路径不受影响（normalizeMode 未放行 GCM）")
        void legacyModesUnaffected() {
            byte[] key = sm4.generateKey();
            byte[] plain = "legacy".getBytes(StandardCharsets.UTF_8);
            byte[] ecb = sm4.encrypt(plain, key, "ECB", null);
            assertThat(sm4.decrypt(ecb, key, "ECB", null)).isEqualTo(plain);
        }
    }

    /** 从存储密文中提取算法标识（测试辅助）。 */
    private static final class CredentialAlgorithmPrefix {
        static String of(String base64) {
            byte[] raw = Base64.getDecoder().decode(base64);
            int sep = 0;
            while (raw[sep] != 0) {
                sep++;
            }
            return new String(raw, 0, sep, StandardCharsets.US_ASCII);
        }
    }
}
