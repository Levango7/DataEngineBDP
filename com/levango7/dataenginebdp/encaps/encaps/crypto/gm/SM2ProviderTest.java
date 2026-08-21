package com.shuqing.bigdata.encaps.crypto.gm;

import com.shuqing.bigdata.encaps.crypto.CryptoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SM2Provider} 单元测试。
 *
 * <p>覆盖 GB/T 32918《信息安全技术 SM2 椭圆曲线公钥密码算法》签名/验签、加密/解密往返、
 * 密钥对生成、密钥格式、异常处理等。</p>
 *
 * <h3>国标对照</h3>
 * <ul>
 *   <li>GB/T 32918.2 — 数字签名（SM3withSM2）</li>
 *   <li>GB/T 32918.4 — 公钥加密（C1||C3||C2）</li>
 *   <li>GB/T 32918.5 — 推荐曲线 sm2p256v1（256 bit）</li>
 * </ul>
 */
class SM2ProviderTest {

    private SM2Provider sm2;

    @BeforeEach
    void setUp() {
        sm2 = new SM2Provider();
    }

    // ===== 密钥对生成 =====

    @Test
    @DisplayName("generateKeyPair — 私钥 32 字节，公钥 65 字节（未压缩）")
    void generateKeyPair_shouldHaveCorrectLengths() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();

        assertThat(kp.getPrivateKeyD()).hasSize(GmAlgorithm.SM2_PRIVATE_KEY_LEN);
        assertThat(kp.getPublicKeyQ()).hasSize(GmAlgorithm.SM2_PUBLIC_KEY_LEN);
        // 未压缩点编码以 0x04 开头
        assertThat(kp.getPublicKeyQ()[0]).isEqualTo((byte) 0x04);
    }

    @Test
    @DisplayName("generateKeyPair — 多次生成应不同（随机性）")
    void generateKeyPair_multipleCalls_shouldDiffer() {
        SM2Provider.Sm2KeyPair kp1 = sm2.generateKeyPair();
        SM2Provider.Sm2KeyPair kp2 = sm2.generateKeyPair();
        SM2Provider.Sm2KeyPair kp3 = sm2.generateKeyPair();

        assertThat(kp1.getPrivateKeyD()).isNotEqualTo(kp2.getPrivateKeyD());
        assertThat(kp2.getPrivateKeyD()).isNotEqualTo(kp3.getPrivateKeyD());
    }

    @Test
    @DisplayName("generateJcaKeyPair — 返回 java.security.KeyPair，算法=EC")
    void generateJcaKeyPair_shouldReturnEcKeyPair() {
        KeyPair kp = sm2.generateJcaKeyPair();

        assertThat(kp).isNotNull();
        assertThat(kp.getPrivate()).isNotNull();
        assertThat(kp.getPublic()).isNotNull();
        assertThat(kp.getPublic().getAlgorithm()).isEqualTo("EC");
    }

    // ===== 签名/验签往返（GB/T 32918.2） =====

    @Test
    @DisplayName("签名→验签 往返 — 相同密钥对验签通过")
    void signVerify_roundTrip_shouldVerify() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        byte[] data = "shuqing-bigdata-sm2-sign-test".getBytes(StandardCharsets.UTF_8);

        byte[] signature = sm2.sign(data, kp.getPrivateKeyD());
        boolean verified = sm2.verify(data, signature, kp.getPublicKeyQ());

        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("签名 — 不同数据产生不同签名值")
    void sign_differentData_shouldProduceDifferentSignature() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        byte[] d1 = "data-one".getBytes(StandardCharsets.UTF_8);
        byte[] d2 = "data-two".getBytes(StandardCharsets.UTF_8);

        byte[] s1 = sm2.sign(d1, kp.getPrivateKeyD());
        byte[] s2 = sm2.sign(d2, kp.getPrivateKeyD());

        assertThat(s1).isNotEqualTo(s2);
    }

    @Test
    @DisplayName("验签 — 篡改数据后验签失败")
    void verify_tamperedData_shouldFail() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        byte[] data = "original-data".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "tampered-data".getBytes(StandardCharsets.UTF_8);

        byte[] signature = sm2.sign(data, kp.getPrivateKeyD());
        boolean verified = sm2.verify(tampered, signature, kp.getPublicKeyQ());

        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("验签 — 篡改签名后验签失败")
    void verify_tamperedSignature_shouldFail() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        byte[] data = "sign-test-data".getBytes(StandardCharsets.UTF_8);

        byte[] signature = sm2.sign(data, kp.getPrivateKeyD());
        // 篡改签名最后一个字节
        byte[] tamperedSig = signature.clone();
        tamperedSig[tamperedSig.length - 1] ^= 0x01;

        boolean verified = sm2.verify(data, tamperedSig, kp.getPublicKeyQ());
        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("验签 — 使用错误公钥验签失败")
    void verify_wrongPublicKey_shouldFail() {
        SM2Provider.Sm2KeyPair kp1 = sm2.generateKeyPair();
        SM2Provider.Sm2KeyPair kp2 = sm2.generateKeyPair();
        byte[] data = "cross-key-test".getBytes(StandardCharsets.UTF_8);

        byte[] signature = sm2.sign(data, kp1.getPrivateKeyD());
        boolean verified = sm2.verify(data, signature, kp2.getPublicKeyQ());

        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("签名 — 空数据可签名且验签通过")
    void sign_emptyData_shouldSignAndVerify() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        byte[] data = new byte[0];

        byte[] signature = sm2.sign(data, kp.getPrivateKeyD());
        boolean verified = sm2.verify(data, signature, kp.getPublicKeyQ());

        assertThat(verified).isTrue();
    }

    // ===== 加密/解密往返（GB/T 32918.4） =====

    @Test
    @DisplayName("加密→解密 往返 — 恢复原始明文")
    void encryptDecrypt_roundTrip_shouldRecoverPlain() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        byte[] plain = "shuqing-bigdata-sm2-encrypt-test".getBytes(StandardCharsets.UTF_8);

        byte[] cipher = sm2.encrypt(plain, kp.getPublicKeyQ());
        byte[] recovered = sm2.decrypt(cipher, kp.getPrivateKeyD());

        assertThat(recovered).isEqualTo(plain);
    }

    @Test
    @DisplayName("加密 — 相同明文多次加密产生不同密文（随机性）")
    void encrypt_samePlain_shouldProduceDifferentCipher() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        byte[] plain = "randomness-test".getBytes(StandardCharsets.UTF_8);

        byte[] c1 = sm2.encrypt(plain, kp.getPublicKeyQ());
        byte[] c2 = sm2.encrypt(plain, kp.getPublicKeyQ());

        assertThat(c1).isNotEqualTo(c2);
    }

    @Test
    @DisplayName("加密 — 空明文可加密且解密往返")
    void encrypt_emptyPlain_shouldRoundTrip() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        byte[] plain = new byte[0];

        byte[] cipher = sm2.encrypt(plain, kp.getPublicKeyQ());
        byte[] recovered = sm2.decrypt(cipher, kp.getPrivateKeyD());

        assertThat(recovered).isEqualTo(plain);
    }

    @Test
    @DisplayName("解密 — 使用错误私钥解密失败")
    void decrypt_wrongPrivateKey_shouldFail() {
        SM2Provider.Sm2KeyPair kp1 = sm2.generateKeyPair();
        SM2Provider.Sm2KeyPair kp2 = sm2.generateKeyPair();
        byte[] plain = "cross-key-decrypt-test".getBytes(StandardCharsets.UTF_8);

        byte[] cipher = sm2.encrypt(plain, kp1.getPublicKeyQ());
        // 用 kp2 私钥解密应失败（抛异常或返回错误结果）
        assertThatThrownBy(() -> sm2.decrypt(cipher, kp2.getPrivateKeyD()))
                .isInstanceOf(CryptoException.class);
    }

    // ===== JCA 密钥提取 =====

    @Test
    @DisplayName("extractD/extractQ — 从 JCA KeyPair 提取并完成签名往返")
    void extractFromJca_shouldSignAndVerify() {
        KeyPair jcaKp = sm2.generateJcaKeyPair();
        byte[] d = sm2.extractD(jcaKp.getPrivate());
        byte[] q = sm2.extractQ(jcaKp.getPublic());

        assertThat(d).hasSize(GmAlgorithm.SM2_PRIVATE_KEY_LEN);
        assertThat(q).hasSize(GmAlgorithm.SM2_PUBLIC_KEY_LEN);

        byte[] data = "jca-key-extract-test".getBytes(StandardCharsets.UTF_8);
        byte[] signature = sm2.sign(data, d);
        assertThat(sm2.verify(data, signature, q)).isTrue();
    }

    // ===== 异常处理 =====

    @Test
    @DisplayName("sign(null) — 抛 CryptoException")
    void sign_nullData_shouldThrow() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        assertThatThrownBy(() -> sm2.sign(null, kp.getPrivateKeyD()))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("verify(null) — 抛 CryptoException")
    void verify_nullData_shouldThrow() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        byte[] sig = sm2.sign("x".getBytes(StandardCharsets.UTF_8), kp.getPrivateKeyD());
        assertThatThrownBy(() -> sm2.verify(null, sig, kp.getPublicKeyQ()))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> sm2.verify("x".getBytes(StandardCharsets.UTF_8), null, kp.getPublicKeyQ()))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("encrypt(null) — 抛 CryptoException")
    void encrypt_nullPlain_shouldThrow() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        assertThatThrownBy(() -> sm2.encrypt(null, kp.getPublicKeyQ()))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt(null) — 抛 CryptoException")
    void decrypt_nullCipher_shouldThrow() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        assertThatThrownBy(() -> sm2.decrypt(null, kp.getPrivateKeyD()))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("sign — 非法私钥抛 CryptoException")
    void sign_invalidPrivateKey_shouldThrow() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> sm2.sign(data, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("encrypt — 非法公钥抛 CryptoException")
    void encrypt_invalidPublicKey_shouldThrow() {
        byte[] plain = "test".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> sm2.encrypt(plain, null))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> sm2.encrypt(plain, new byte[10]))
                .isInstanceOf(CryptoException.class);
    }

    // ===== JCA 密钥提取边界分支 =====

    @Test
    @DisplayName("extractD(null) — 抛 CryptoException")
    void extractD_null_shouldThrow() {
        assertThatThrownBy(() -> sm2.extractD(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("extractQ(null) — 抛 CryptoException")
    void extractQ_null_shouldThrow() {
        assertThatThrownBy(() -> sm2.extractQ(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("extractQ — 非 BC 公钥回退到 JCA getEncoded 分支（未压缩格式）")
    void extractQ_nonBcPublicKey_shouldFallbackToJca() {
        // 构造一个非 BC 的自定义 ECPublicKey，getEncoded 返回 SM2 曲线上点的未压缩编码
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        byte[] qBytes = kp.getPublicKeyQ(); // 65 字节未压缩 04||X||Y

        java.security.interfaces.ECPublicKey customKey = createCustomEcPublicKey(qBytes);

        // customKey 不是 BCECPublicKey，走回退分支，getEncoded 返回 04 开头的未压缩格式
        byte[] extracted = sm2.extractQ(customKey);
        assertThat(extracted).isEqualTo(qBytes);
    }

    @Test
    @DisplayName("extractQ — 非 BC 公钥回退到 decodePoint 分支（压缩格式）")
    void extractQ_nonBcPublicKey_compressedFormat_shouldDecode() {
        // 构造一个非 BC 的自定义 ECPublicKey，getEncoded 返回压缩格式点编码
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        // 用 BC 曲线将未压缩公钥转为压缩格式
        org.bouncycastle.asn1.x9.X9ECParameters curveParams =
                org.bouncycastle.asn1.gm.GMNamedCurves.getByName("sm2p256v1");
        org.bouncycastle.math.ec.ECPoint q = curveParams.getCurve().decodePoint(kp.getPublicKeyQ());
        byte[] compressedQ = q.getEncoded(true); // 压缩格式 02/03||X

        java.security.interfaces.ECPublicKey customKey = createCustomEcPublicKey(compressedQ);

        // customKey 不是 BCECPublicKey，走回退分支，getEncoded 返回压缩格式 → decodePoint → 未压缩输出
        byte[] extracted = sm2.extractQ(customKey);
        assertThat(extracted).hasSize(65);
        assertThat(extracted[0]).isEqualTo((byte) 0x04);
    }

    /**
     * 创建自定义 ECPublicKey（非 BC 实现），用于测试 extractQ 的回退分支。
     *
     * @param encoded 点编码（未压缩 04||X||Y 或压缩 02/03||X）
     * @return 自定义 ECPublicKey 实例
     */
    private static java.security.interfaces.ECPublicKey createCustomEcPublicKey(byte[] encoded) {
        return new java.security.interfaces.ECPublicKey() {
            @Override
            public java.security.spec.ECPoint getW() {
                return new java.security.spec.ECPoint(java.math.BigInteger.ZERO, java.math.BigInteger.ZERO);
            }
            @Override
            public java.security.spec.ECParameterSpec getParams() {
                return null;
            }
            @Override
            public String getAlgorithm() { return "EC"; }
            @Override
            public String getFormat() { return "RAW"; }
            @Override
            public byte[] getEncoded() { return encoded.clone(); }
        };
    }

    @Test
    @DisplayName("extractD — 非 EC 私钥抛 CryptoException（ClassCastException 包装）")
    void extractD_nonEcPrivateKey_shouldThrow() {
        // RSA 私钥不是 ECPrivateKey，应抛 CryptoException
        try {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(512);
            java.security.KeyPair rsaKp = kpg.generateKeyPair();
            assertThatThrownBy(() -> sm2.extractD(rsaKp.getPrivate()))
                    .isInstanceOf(CryptoException.class);
        } catch (Exception e) {
            // 如果 RSA 生成失败（如某些环境限制），跳过本测试
            assertThat(e).isNotNull();
        }
    }

    // ===== normalizeToLength 分支覆盖 =====

    @Test
    @DisplayName("generateKeyPair — 私钥 D 值规范化（覆盖 normalizeToLength 各分支）")
    void generateKeyPair_shouldNormalizePrivateKeyD() {
        // 多次生成密钥对，覆盖 normalizeToLength 的不同分支
        // BigInteger.toByteArray() 可能返回 33 字节（带前导零）或 31 字节（高位为零）
        for (int i = 0; i < 20; i++) {
            SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
            assertThat(kp.getPrivateKeyD()).hasSize(GmAlgorithm.SM2_PRIVATE_KEY_LEN);
            assertThat(kp.getPublicKeyQ()).hasSize(GmAlgorithm.SM2_PUBLIC_KEY_LEN);
        }
    }

    @Test
    @DisplayName("extractD — 从 JCA 密钥提取 D 值规范化（覆盖 normalizeToLength）")
    void extractD_fromJca_shouldNormalize() {
        for (int i = 0; i < 10; i++) {
            KeyPair jcaKp = sm2.generateJcaKeyPair();
            byte[] d = sm2.extractD(jcaKp.getPrivate());
            assertThat(d).hasSize(GmAlgorithm.SM2_PRIVATE_KEY_LEN);
        }
    }

    // ===== toPrivateKey / toPublicKey 异常分支 =====

    @Test
    @DisplayName("decrypt — 非法私钥（全零 D 值）抛 CryptoException")
    void decrypt_invalidPrivateKey_shouldThrow() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        byte[] plain = "test".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = sm2.encrypt(plain, kp.getPublicKeyQ());
        // 全零私钥不是有效 SM2 私钥
        byte[] zeroD = new byte[32];
        assertThatThrownBy(() -> sm2.decrypt(cipher, zeroD))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("verify — 非法公钥（无效点编码）抛 CryptoException")
    void verify_invalidPublicKey_shouldThrow() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        byte[] sig = sm2.sign(data, kp.getPrivateKeyD());
        // 无效公钥点编码（不是曲线上的点）
        byte[] invalidQ = new byte[65];
        invalidQ[0] = 0x04;
        assertThatThrownBy(() -> sm2.verify(data, sig, invalidQ))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt — 非法密文抛 CryptoException")
    void decrypt_invalidCipher_shouldThrow() {
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        // 太短的密文不是有效 SM2 密文
        byte[] invalidCipher = new byte[10];
        assertThatThrownBy(() -> sm2.decrypt(invalidCipher, kp.getPrivateKeyD()))
                .isInstanceOf(CryptoException.class);
    }
}