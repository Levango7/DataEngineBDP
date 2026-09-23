package com.levango7.dataenginebdp.encaps.crypto.intl;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AESProvider} 单元测试。
 *
 * <p>覆盖 AES-128/256 GCM/CBC 模式加密/解密、NIST FIPS 197 已知测试向量、
 * GCM 认证标签验证、密钥/IV 生成、异常分支、AES-256-GCM 吞吐性能测试等。</p>
 *
 * <h3>NIST FIPS 197 测试向量</h3>
 * <p>使用 NIST FIPS 197 Appendix B/C 中的 AES-128/256 标准测试向量验证
 * 算法正确性（通过 JDK Cipher 作为参考实现间接验证）。</p>
 */
class AESProviderTest {

    private AESProvider aesProvider;

    @BeforeEach
    void setUp() {
        aesProvider = new AESProvider();
    }

    // ===== NIST FIPS 197 已知测试向量（通过JDK Cipher验证） =====

    @Test
    @DisplayName("NIST FIPS 197 — AES-128-ECB 标准向量（Appendix B）")
    void nist_aes128_ecb() throws Exception {
        // NIST FIPS 197 Appendix B: AES-128
        // Key: 000102030405060708090a0b0c0d0e0f
        // Plaintext: 00112233445566778899aabbccddeeff
        // Ciphertext: 69c4e0d86a7b0430d8cdb78070b4c55a
        byte[] key = SHAProvider.fromHex("000102030405060708090a0b0c0d0e0f");
        byte[] plaintext = SHAProvider.fromHex("00112233445566778899aabbccddeeff");
        byte[] expectedCiphertext = SHAProvider.fromHex("69c4e0d86a7b0430d8cdb78070b4c55a");

        // 使用 JDK Cipher 直接验证 ECB 模式（AESProvider 不直接暴露 ECB，用 JDK 验证向量正确性）
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] actualCiphertext = cipher.doFinal(plaintext);

        assertThat(actualCiphertext).isEqualTo(expectedCiphertext);
    }

    @Test
    @DisplayName("NIST FIPS 197 — AES-256-ECB 标准向量（Appendix C.3）")
    void nist_aes256_ecb() throws Exception {
        // NIST FIPS 197 Appendix C.3: AES-256
        // Key: 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
        // Plaintext: 00112233445566778899aabbccddeeff
        // Ciphertext: 8ea2b7ca516745bfeafc49904b496089
        byte[] key = SHAProvider.fromHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] plaintext = SHAProvider.fromHex("00112233445566778899aabbccddeeff");
        byte[] expectedCiphertext = SHAProvider.fromHex("8ea2b7ca516745bfeafc49904b496089");

        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] actualCiphertext = cipher.doFinal(plaintext);

        assertThat(actualCiphertext).isEqualTo(expectedCiphertext);
    }

    @Test
    @DisplayName("NIST FIPS 197 — AES-128 密钥长度 = 16 字节")
    void nist_aes128_keyLength() {
        byte[] key = aesProvider.generateKey(128);
        assertThat(key).hasSize(16);
    }

    @Test
    @DisplayName("NIST FIPS 197 — AES-256 密钥长度 = 32 字节")
    void nist_aes256_keyLength() {
        byte[] key = aesProvider.generateKey(256);
        assertThat(key).hasSize(32);
    }

    // ===== GCM 模式加密/解密往返 =====

    @Test
    @DisplayName("AES-128-GCM — 加密→解密还原原文")
    void aes128Gcm_encryptDecrypt_shouldRoundTrip() {
        byte[] key = aesProvider.generateKey(128);
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = "aes-128-gcm-test".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesProvider.encrypt(plaintext, key, "GCM", iv);
        assertThat(ciphertext).isNotEmpty();
        // GCM 密文 = 明文长度 + 16字节标签
        assertThat(ciphertext).hasSize(plaintext.length + 16);

        byte[] decrypted = aesProvider.decrypt(ciphertext, key, "GCM", iv);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("AES-256-GCM — 加密→解密还原原文")
    void aes256Gcm_encryptDecrypt_shouldRoundTrip() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = "aes-256-gcm-test-data".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesProvider.encrypt(plaintext, key, "GCM", iv);
        assertThat(ciphertext).hasSize(plaintext.length + 16);

        byte[] decrypted = aesProvider.decrypt(ciphertext, key, "GCM", iv);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("AES-256-GCM — 空明文加密→解密")
    void aes256Gcm_emptyPlaintext_shouldRoundTrip() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = new byte[0];

        byte[] ciphertext = aesProvider.encrypt(plaintext, key, "GCM", iv);
        // 空明文 GCM 输出只有 16 字节标签
        assertThat(ciphertext).hasSize(16);

        byte[] decrypted = aesProvider.decrypt(ciphertext, key, "GCM", iv);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("AES-256-GCM — 大数据加密→解密（1MB）")
    void aes256Gcm_largeData_shouldRoundTrip() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = new byte[1024 * 1024];
        for (int i = 0; i < plaintext.length; i++) {
            plaintext[i] = (byte) (i & 0xff);
        }

        byte[] ciphertext = aesProvider.encrypt(plaintext, key, "GCM", iv);
        byte[] decrypted = aesProvider.decrypt(ciphertext, key, "GCM", iv);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    // ===== GCM 认证标签验证 =====

    @Test
    @DisplayName("GCM 认证 — 篡改密文后解密抛CryptoException（认证失败）")
    void gcm_tamperedCiphertext_shouldThrow() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = "auth-test".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesProvider.encrypt(plaintext, key, "GCM", iv);

        // 篡改密文
        byte[] tampered = Arrays.copyOf(ciphertext, ciphertext.length);
        tampered[0] ^= 0x01;

        assertThatThrownBy(() -> aesProvider.decrypt(tampered, key, "GCM", iv))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("GCM 认证 — 错误密钥解密抛CryptoException（认证失败）")
    void gcm_wrongKey_shouldThrow() {
        byte[] key1 = aesProvider.generateKey(256);
        byte[] key2 = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = "wrong-key-test".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesProvider.encrypt(plaintext, key1, "GCM", iv);

        assertThatThrownBy(() -> aesProvider.decrypt(ciphertext, key2, "GCM", iv))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("GCM 认证 — 错误IV解密抛CryptoException（认证失败）")
    void gcm_wrongIv_shouldThrow() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv1 = aesProvider.generateGcmIv();
        byte[] iv2 = aesProvider.generateGcmIv();
        byte[] plaintext = "wrong-iv-test".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesProvider.encrypt(plaintext, key, "GCM", iv1);

        assertThatThrownBy(() -> aesProvider.decrypt(ciphertext, key, "GCM", iv2))
                .isInstanceOf(CryptoException.class);
    }

    // ===== GCM AAD（附加认证数据） =====

    @Test
    @DisplayName("AES-GCM-AAD — 带附加认证数据加密→解密")
    void gcmWithAad_shouldRoundTrip() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = "aad-plaintext".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "additional-auth-data".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesProvider.encryptGcmWithAad(plaintext, key, iv, aad);
        byte[] decrypted = aesProvider.decryptGcmWithAad(ciphertext, key, iv, aad);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("AES-GCM-AAD — AAD不匹配时解密抛CryptoException")
    void gcmWithAad_mismatchedAad_shouldThrow() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = "aad-test".getBytes(StandardCharsets.UTF_8);
        byte[] aad1 = "aad1".getBytes(StandardCharsets.UTF_8);
        byte[] aad2 = "aad2".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesProvider.encryptGcmWithAad(plaintext, key, iv, aad1);

        assertThatThrownBy(() -> aesProvider.decryptGcmWithAad(ciphertext, key, iv, aad2))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("AES-GCM-AAD — null AAD 等价于无 AAD")
    void gcmWithAad_nullAad_shouldWorkAsNoAad() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = "null-aad-test".getBytes(StandardCharsets.UTF_8);

        byte[] ctWithNullAad = aesProvider.encryptGcmWithAad(plaintext, key, iv, null);
        byte[] ctNoAad = aesProvider.encrypt(plaintext, key, "GCM", iv);
        // 两者应该一致（同IV同密钥同明文）
        assertThat(ctWithNullAad).isEqualTo(ctNoAad);
    }

    // ===== CBC 模式加密/解密往返 =====

    @Test
    @DisplayName("AES-128-CBC — 加密→解密还原原文")
    void aes128Cbc_encryptDecrypt_shouldRoundTrip() {
        byte[] key = aesProvider.generateKey(128);
        byte[] iv = aesProvider.generateCbcIv();
        byte[] plaintext = "aes-128-cbc-test".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesProvider.encrypt(plaintext, key, "CBC", iv);
        assertThat(ciphertext).isNotEmpty();
        // CBC 密文是 16 字节对齐
        assertThat(ciphertext.length % 16).isEqualTo(0);

        byte[] decrypted = aesProvider.decrypt(ciphertext, key, "CBC", iv);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("AES-256-CBC — 加密→解密还原原文")
    void aes256Cbc_encryptDecrypt_shouldRoundTrip() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateCbcIv();
        byte[] plaintext = "aes-256-cbc-test-data".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesProvider.encrypt(plaintext, key, "CBC", iv);
        byte[] decrypted = aesProvider.decrypt(ciphertext, key, "CBC", iv);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("AES-256-CBC — 相同IV相同密钥相同明文产生相同密文（确定性）")
    void cbc_deterministicWithSameIv() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateCbcIv();
        byte[] plaintext = "deterministic-cbc".getBytes(StandardCharsets.UTF_8);

        byte[] ct1 = aesProvider.encrypt(plaintext, key, "CBC", iv);
        byte[] ct2 = aesProvider.encrypt(plaintext, key, "CBC", iv);
        assertThat(ct1).isEqualTo(ct2);
    }

    @Test
    @DisplayName("AES-256-CBC — 不同IV产生不同密文")
    void cbc_differentIv_shouldProduceDifferentCiphertext() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv1 = aesProvider.generateCbcIv();
        byte[] iv2 = aesProvider.generateCbcIv();
        byte[] plaintext = "different-iv-cbc".getBytes(StandardCharsets.UTF_8);

        byte[] ct1 = aesProvider.encrypt(plaintext, key, "CBC", iv1);
        byte[] ct2 = aesProvider.encrypt(plaintext, key, "CBC", iv2);
        assertThat(ct1).isNotEqualTo(ct2);
    }

    // ===== 密钥/IV 生成 =====

    @Test
    @DisplayName("generateKey(128) — 生成 16 字节密钥")
    void generateKey_128_shouldGenerate16Bytes() {
        byte[] key = aesProvider.generateKey(128);
        assertThat(key).hasSize(16);
    }

    @Test
    @DisplayName("generateKey(256) — 生成 32 字节密钥")
    void generateKey_256_shouldGenerate32Bytes() {
        byte[] key = aesProvider.generateKey(256);
        assertThat(key).hasSize(32);
    }

    @Test
    @DisplayName("generateKey(192) — 生成 24 字节密钥")
    void generateKey_192_shouldGenerate24Bytes() {
        byte[] key = aesProvider.generateKey(192);
        assertThat(key).hasSize(24);
    }

    @Test
    @DisplayName("generateKey — 不支持的长度抛CryptoException")
    void generateKey_unsupported_shouldThrow() {
        assertThatThrownBy(() -> aesProvider.generateKey(64))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> aesProvider.generateKey(100))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("generateKey() — 默认生成 256 位密钥")
    void generateKey_default_shouldBe256() {
        byte[] key = aesProvider.generateKey();
        assertThat(key).hasSize(32);
    }

    @Test
    @DisplayName("generateKey — 每次生成不同密钥")
    void generateKey_shouldGenerateDifferentKeys() {
        byte[] k1 = aesProvider.generateKey(256);
        byte[] k2 = aesProvider.generateKey(256);
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    @DisplayName("generateIv — 生成指定长度 IV")
    void generateIv_shouldGenerateCorrectLength() {
        assertThat(aesProvider.generateIv(12)).hasSize(12);
        assertThat(aesProvider.generateIv(16)).hasSize(16);
        assertThat(aesProvider.generateIv(32)).hasSize(32);
    }

    @Test
    @DisplayName("generateIv — 不合法长度抛CryptoException")
    void generateIv_invalidLength_shouldThrow() {
        assertThatThrownBy(() -> aesProvider.generateIv(0))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> aesProvider.generateIv(-1))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("generateGcmIv — 生成 12 字节 IV")
    void generateGcmIv_shouldGenerate12Bytes() {
        assertThat(aesProvider.generateGcmIv()).hasSize(12);
    }

    @Test
    @DisplayName("generateCbcIv — 生成 16 字节 IV")
    void generateCbcIv_shouldGenerate16Bytes() {
        assertThat(aesProvider.generateCbcIv()).hasSize(16);
    }

    // ===== 异常分支 =====

    @Test
    @DisplayName("encrypt — null入参抛CryptoException")
    void encrypt_nullArgs_shouldThrow() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();

        assertThatThrownBy(() -> aesProvider.encrypt(null, key, "GCM", iv))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> aesProvider.encrypt("test".getBytes(), null, "GCM", iv))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt — null入参抛CryptoException")
    void decrypt_nullArgs_shouldThrow() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();

        assertThatThrownBy(() -> aesProvider.decrypt(null, key, "GCM", iv))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> aesProvider.decrypt("test".getBytes(), null, "GCM", iv))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> aesProvider.decrypt("test".getBytes(), key, "GCM", null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("encrypt — 不支持的密钥长度抛CryptoException")
    void encrypt_invalidKeyLength_shouldThrow() {
        byte[] badKey = new byte[20]; // 不合法长度
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = "test".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> aesProvider.encrypt(plaintext, badKey, "GCM", iv))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("encrypt — 不支持的模式抛CryptoException")
    void encrypt_unsupportedMode_shouldThrow() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = "test".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> aesProvider.encrypt(plaintext, key, "ECB", iv))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("CBC — IV长度不合法抛CryptoException")
    void cbc_invalidIvLength_shouldThrow() {
        byte[] key = aesProvider.generateKey(256);
        byte[] badIv = new byte[10]; // CBC IV 必须 16 字节
        byte[] plaintext = "test".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> aesProvider.encrypt(plaintext, key, "CBC", badIv))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("encryptGcmWithAad — null入参抛CryptoException")
    void encryptGcmWithAad_nullArgs_shouldThrow() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();

        assertThatThrownBy(() -> aesProvider.encryptGcmWithAad(null, key, iv, null))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> aesProvider.encryptGcmWithAad("test".getBytes(), null, iv, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decryptGcmWithAad — null入参抛CryptoException")
    void decryptGcmWithAad_nullArgs_shouldThrow() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();

        assertThatThrownBy(() -> aesProvider.decryptGcmWithAad(null, key, iv, null))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 自动生成 IV =====

    @Test
    @DisplayName("encrypt — iv=null 时自动生成 IV")
    void encrypt_nullIv_shouldAutoGenerate() {
        byte[] key = aesProvider.generateKey(256);
        byte[] plaintext = "auto-iv-test".getBytes(StandardCharsets.UTF_8);

        // GCM 自动生成 IV
        byte[] ct = aesProvider.encrypt(plaintext, key, "GCM", null);
        assertThat(ct).isNotEmpty();

        // CBC 自动生成 IV
        byte[] ctCbc = aesProvider.encrypt(plaintext, key, "CBC", null);
        assertThat(ctCbc).isNotEmpty();
    }

    // ===== 模式名大小写不敏感 =====

    @Test
    @DisplayName("模式名大小写不敏感 — gcm/GCM/gCm 均可")
    void modeNameCaseInsensitive_shouldWork() {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = "case-test".getBytes(StandardCharsets.UTF_8);

        byte[] ct1 = aesProvider.encrypt(plaintext, key, "gcm", iv);
        byte[] ct2 = aesProvider.encrypt(plaintext, key, "GCM", iv);
        byte[] ct3 = aesProvider.encrypt(plaintext, key, "gCm", iv);

        assertThat(ct1).isEqualTo(ct2).isEqualTo(ct3);
    }

    // ===== JDK 一致性验证 =====

    @Test
    @DisplayName("JDK一致性 — AES-256-GCM 与 JDK Cipher 结果一致")
    void jdkConsistency_aes256Gcm() throws Exception {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateGcmIv();
        byte[] plaintext = "jdk-consistency-test".getBytes(StandardCharsets.UTF_8);

        // 我们的实现
        byte[] ourCt = aesProvider.encrypt(plaintext, key, "GCM", iv);

        // JDK 实现
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] jdkCt = cipher.doFinal(plaintext);

        assertThat(ourCt).isEqualTo(jdkCt);
    }

    @Test
    @DisplayName("JDK一致性 — AES-256-CBC 与 JDK Cipher 结果一致")
    void jdkConsistency_aes256Cbc() throws Exception {
        byte[] key = aesProvider.generateKey(256);
        byte[] iv = aesProvider.generateCbcIv();
        byte[] plaintext = "jdk-cbc-consistency".getBytes(StandardCharsets.UTF_8);

        // 我们的实现
        byte[] ourCt = aesProvider.encrypt(plaintext, key, "CBC", iv);

        // JDK 实现
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] jdkCt = cipher.doFinal(plaintext);

        assertThat(ourCt).isEqualTo(jdkCt);
    }

    // ===== AES-GCM 吞吐性能测试 =====
    //
    // 设计目标：断言"是否存在工程性吞吐退化"，而不是"这台机器有多快"。
    //
    // 实测依据（AMD Ryzen 9 7945HX / Windows 11 / Corretto 17，JDK 未启用 AES-NI 硬件加速；
    // 完整数据见 deliverables/gstack/flaky-aes-throughput-2026-09-23.md）：
    //   - 绝对吞吐对机器负载高度敏感：空载 48–66 MB/s，16 路 CPU 满载 20–22 MB/s（约 2.5–3x 漂移）。
    //     因此任何落在该区间内的固定绝对阈值都必然抖动 —— 原 40 MB/s 阈值正落在区间内，
    //     空载机器上单次采样即可低于 40 MB/s，属于"结构性 flaky"而非偶发。
    //   - 相对指标 AESProvider / 裸 JDK Cipher（同进程交错采样）实测中位数 0.94–1.03，
    //     在空载与满载下同样稳定，且不随机器快慢变化 —— 这才是可用于门禁的判据。
    //
    // 两条断言：
    //   1) 相对开销（主判据）：AESProvider 相对其所包装的裸 JDK Cipher 的开销 < 1.5x。
    //      实测中位数 ≤ 1.03（余量约 45%）。可捕捉 AESProvider 自身代码引入的 ≥1.5x 退化
    //      （如逐块重建 Cipher、整体复制明文、无谓同步等）。
    //   2) 绝对下限（兜底）：best-of-N 吞吐 > 5 MB/s。相对实测最差值 19.7 MB/s 有约 3.9x 余量，
    //      可捕捉 ≥4x 的灾难性退化，同时在任何负载下都不会误报。

    /** 单次吞吐采样所用数据块大小。1MB 工作集对 L2/L3 更友好，读数比 10MB 更稳，耗时低一个数量级。 */
    private static final int PERF_DATA_SIZE = 1024 * 1024;

    /** 采样次数。取 best-of-N 作为读数，是共享/高负载 CI 上标准的去噪手段。 */
    private static final int PERF_SAMPLES = 5;

    /** 绝对吞吐下限（MB/s）—— 仅用于兜底捕捉灾难性退化。 */
    private static final double MIN_THROUGHPUT_MBPS = 5.0;

    /** AESProvider 相对裸 JDK Cipher 的最大允许开销倍数（主判据）。 */
    private static final double MAX_OVERHEAD_FACTOR = 1.5;

    @Test
    @DisplayName("性能 — AES-256-GCM 吞吐无工程性退化（相对裸JDK开销 < 1.5x，绝对下限 5 MB/s）")
    void performance_aes256Gcm_throughput_shouldNotRegress() {
        assertNoThroughputRegression(256);
    }

    @Test
    @DisplayName("性能 — AES-128-GCM 吞吐无工程性退化（相对裸JDK开销 < 1.5x，绝对下限 5 MB/s）")
    void performance_aes128Gcm_throughput_shouldNotRegress() {
        assertNoThroughputRegression(128);
    }

    /**
     * 断言指定密钥长度的 AES-GCM 吞吐未出现工程性退化。
     *
     * <p>两侧测量在同一次迭代内背靠背进行（交错采样），因此共同承受当时的机器负载，
     * 比值不受负载影响；再取 best-of-N 与中位数，进一步压制单次抖动。</p>
     */
    private void assertNoThroughputRegression(int keySize) {
        byte[] key = aesProvider.generateKey(keySize);
        byte[] data = new byte[PERF_DATA_SIZE];
        new SecureRandom().nextBytes(data);

        double bestProvider = 0.0;
        double bestJdkBaseline = 0.0;
        double[] ratios = new double[PERF_SAMPLES];

        for (int i = 0; i < PERF_SAMPLES; i++) {
            double provider = aesProvider.measureThroughput(keySize, PERF_DATA_SIZE);
            double baseline = jdkGcmBaselineThroughput(key, data);
            bestProvider = Math.max(bestProvider, provider);
            bestJdkBaseline = Math.max(bestJdkBaseline, baseline);
            ratios[i] = provider / baseline;
        }
        double medianRatio = median(ratios);

        assertThat(bestProvider)
                .as("AES-%d-GCM best-of-%d throughput: %.2f MB/s (absolute floor: %.1f MB/s, "
                                + "JDK baseline: %.2f MB/s, median overhead ratio: %.2f)",
                        keySize, PERF_SAMPLES, bestProvider, MIN_THROUGHPUT_MBPS, bestJdkBaseline, medianRatio)
                .isGreaterThan(MIN_THROUGHPUT_MBPS);

        assertThat(medianRatio)
                .as("AES-%d-GCM AESProvider/JDK-baseline median overhead ratio: %.2f (allowed < %.2f); "
                                + "provider best: %.2f MB/s, baseline best: %.2f MB/s",
                        keySize, medianRatio, MAX_OVERHEAD_FACTOR, bestProvider, bestJdkBaseline)
                .isLessThan(MAX_OVERHEAD_FACTOR);
    }

    /**
     * 裸 JDK {@code Cipher} 的 AES-GCM 参考吞吐（MB/s）。
     *
     * <p>循环形状与 {@link AESProvider#measureThroughput(int, int)} 保持一致
     * （1 次预热 + 5 次 {@code getInstance/init/doFinal}），因此两侧可直接比较。</p>
     */
    private double jdkGcmBaselineThroughput(byte[] key, byte[] data) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
            byte[] iv = new byte[12];
            Cipher warmup = Cipher.getInstance("AES/GCM/NoPadding");
            warmup.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
            warmup.doFinal(data);

            int iterations = 5;
            long startTime = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                iv[11] = (byte) (i & 0xff);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
                cipher.doFinal(data);
            }
            long elapsedNanos = System.nanoTime() - startTime;
            return (double) data.length * iterations / (elapsedNanos / 1_000_000_000.0) / (1024 * 1024);
        } catch (Exception e) {
            throw new CryptoException("JDK GCM baseline measurement failed", e);
        }
    }

    /** 中位数（复制入参，不修改调用方数组）。 */
    private static double median(double[] values) {
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    @Test
    @DisplayName("性能 — measureThroughput 返回正值")
    void measureThroughput_shouldReturnPositiveValue() {
        double throughput = aesProvider.measureThroughput(256, 64 * 1024);
        assertThat(throughput).isPositive();
    }
}