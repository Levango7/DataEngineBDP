package com.levango7.dataenginebdp.encaps.crypto;

import com.levango7.dataenginebdp.encaps.crypto.gm.GmProvider;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM2Provider;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM3Provider;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM4Provider;
import com.levango7.dataenginebdp.encaps.crypto.intl.AESProvider;
import com.levango7.dataenginebdp.encaps.crypto.intl.IntlProvider;
import com.levango7.dataenginebdp.encaps.crypto.intl.RSAProvider;
import com.levango7.dataenginebdp.encaps.crypto.intl.SHAProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双栈集成测试（T022-4）。
 *
 * <p>验证国密栈（{@link GmProvider} / SM2/SM3/SM4）与国际栈（{@link IntlProvider} / RSA/SHA/AES）
 * 在同一 JVM 中并存、按 {@link CryptoProfile} 自动路由、并完成各自签名/摘要/加密闭环。</p>
 *
 * <h3>覆盖场景</h3>
 * <ul>
 *   <li><b>双栈切换</b>：Profile=xinchang → GmProvider；Profile=international → IntlProvider；
 *       运行时切换立即生效</li>
 *   <li><b>SM2/RSA 签名互验</b>：国密 SM2 签名/验签闭环、国际 RSA 签名/验签闭环
 *       （不同算法不可直接互验，验证各自闭环正确性）</li>
 *   <li><b>SM3/SHA 摘要互验</b>：同消息分别计算 SM3 与 SHA-256 摘要，验证长度一致、
 *       确定性、雪崩效应，确认双栈摘要各自符合规范</li>
 *   <li><b>SM4/AES 加密互验</b>：国密 SM4 加密/解密闭环、国际 AES-GCM 加密/解密闭环
 *       （不同算法/格式不可直接互解，验证各自闭环正确性）</li>
 *   <li><b>信创默认国密</b>：未指定 Profile 时回退到 XINCHANG，自动使用 GmProvider</li>
 * </ul>
 *
 * <p>本测试不依赖外部密钥，所有密钥对均由各 Provider 测试生成。</p>
 */
class DualStackIntegrationTest {

    private CryptoConfig config;
    private GmProvider gmProvider;
    private IntlProvider intlProvider;

    private static final byte[] MESSAGE = "双栈集成测试消息-dual-stack-message".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        config = new CryptoConfig();
        gmProvider = new GmProvider();
        intlProvider = new IntlProvider();
    }

    // ==================== 双栈切换验证 ====================

    @Nested
    @DisplayName("双栈切换 — Profile 路由到对应 Provider")
    class DualStackSwitch {

        @Test
        @DisplayName("Profile=xinchang → 自动使用 GmProvider（SM2/SM3/SM4）")
        void profileXinchang_shouldUseGmProvider() {
            config.setActiveProfile("xinchang");
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            CryptoProvider provider = factory.getProvider();

            assertThat(provider).isInstanceOf(GmProvider.class);
            assertThat(provider.getProviderName()).isEqualTo("GM-Provider");
            assertThat(provider.getSupportedProfile()).isEqualTo(CryptoProfile.XINCHANG);
            // 验证内部子 Provider 可访问（SM2/SM3/SM4）
            GmProvider gm = (GmProvider) provider;
            assertThat(gm.getSm2()).isInstanceOf(SM2Provider.class);
            assertThat(gm.getSm3()).isInstanceOf(SM3Provider.class);
            assertThat(gm.getSm4()).isInstanceOf(SM4Provider.class);
        }

        @Test
        @DisplayName("Profile=international(standard) → 自动使用 IntlProvider（RSA/SHA/AES）")
        void profileInternational_shouldUseIntlProvider() {
            config.setActiveProfile("international");
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            CryptoProvider provider = factory.getProvider();

            assertThat(provider).isInstanceOf(IntlProvider.class);
            assertThat(provider.getProviderName()).isEqualTo("INTL-Provider");
            assertThat(provider.getSupportedProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
            // 验证内部子 Provider 可访问（RSA/SHA/AES）
            IntlProvider intl = (IntlProvider) provider;
            assertThat(intl.getRsaProvider()).isInstanceOf(RSAProvider.class);
            assertThat(intl.getShaProvider()).isInstanceOf(SHAProvider.class);
            assertThat(intl.getAesProvider()).isInstanceOf(AESProvider.class);
        }

        @Test
        @DisplayName("运行时切换 Profile — xinchang → international 立即生效")
        void runtimeSwitch_xinchangToInternational_shouldTakeEffectImmediately() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            // 切换到信创 → GmProvider
            factory.setCurrentProfile("xinchang");
            CryptoProvider gm = factory.getProvider();
            assertThat(gm).isInstanceOf(GmProvider.class);
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);

            // 切换到国际 → IntlProvider，立即生效
            factory.setCurrentProfile("international");
            CryptoProvider intl = factory.getProvider();
            assertThat(intl).isInstanceOf(IntlProvider.class);
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
        }

        @Test
        @DisplayName("运行时切换 Profile — international → xinchang 立即生效")
        void runtimeSwitch_internationalToXinchang_shouldTakeEffectImmediately() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            factory.setCurrentProfile("international");
            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);

            factory.setCurrentProfile("xinchang");
            assertThat(factory.getProvider()).isInstanceOf(GmProvider.class);
        }

        @Test
        @DisplayName("双栈共存 — 同一工厂可同时获取两个 Provider")
        void dualStackCoexist_sameFactoryCanAccessBoth() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            CryptoProvider gm = factory.getProvider("xinchang");
            CryptoProvider intl = factory.getProvider("international");

            assertThat(gm).isInstanceOf(GmProvider.class);
            assertThat(intl).isInstanceOf(IntlProvider.class);
            assertThat(factory.getAvailableProviders()).hasSize(2);
        }

        @Test
        @DisplayName("信创环境默认国密 — 未指定 Profile 回退 XINCHANG 使用 GmProvider")
        void noProfile_shouldDefaultToGmProvider() {
            // 不设置 activeProfile，也不传 Spring Environment
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            CryptoProvider provider = factory.getProvider();

            assertThat(provider).isInstanceOf(GmProvider.class);
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
        }
    }

    // ==================== SM2 / RSA 签名互验 ====================

    @Nested
    @DisplayName("SM2/RSA 签名互验 — 各自签名/验签闭环")
    class SignatureCrossVerify {

        @Test
        @DisplayName("国密 SM2 签名 → 国密 SM2 验签 闭环通过")
        void sm2Sign_sm2Verify_shouldPass() {
            SM2Provider sm2 = gmProvider.getSm2();
            SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();

            byte[] signature = sm2.sign(MESSAGE, kp.getPrivateKeyD());
            boolean verified = sm2.verify(MESSAGE, signature, kp.getPublicKeyQ());

            assertThat(signature).isNotEmpty();
            assertThat(verified).isTrue();
        }

        @Test
        @DisplayName("国际 RSA 签名 → 国际 RSA 验签 闭环通过")
        void rsaSign_rsaVerify_shouldPass() {
            KeyPair kp = intlProvider.getKeyPair();

            byte[] signature = intlProvider.sign(MESSAGE, kp.getPrivate());
            boolean verified = intlProvider.verifySign(MESSAGE, signature, kp.getPublic());

            assertThat(signature).isNotEmpty();
            assertThat(verified).isTrue();
        }

        @Test
        @DisplayName("SM2 签名 — 篡改数据后验签失败")
        void sm2Sign_tamperedData_shouldFailVerify() {
            SM2Provider sm2 = gmProvider.getSm2();
            SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();

            byte[] signature = sm2.sign(MESSAGE, kp.getPrivateKeyD());
            byte[] tampered = Arrays.copyOf(MESSAGE, MESSAGE.length);
            tampered[0] ^= 0x01;

            assertThat(sm2.verify(tampered, signature, kp.getPublicKeyQ())).isFalse();
        }

        @Test
        @DisplayName("RSA 签名 — 篡改签名后验签失败")
        void rsaSign_tamperedSignature_shouldFailVerify() {
            KeyPair kp = intlProvider.getKeyPair();
            byte[] signature = intlProvider.sign(MESSAGE, kp.getPrivate());

            byte[] tamperedSig = Arrays.copyOf(signature, signature.length);
            tamperedSig[0] ^= 0x01;

            assertThat(intlProvider.verifySign(MESSAGE, tamperedSig, kp.getPublic())).isFalse();
        }

        @Test
        @DisplayName("双栈签名共存 — 同一 JVM 中 SM2 与 RSA 签名互不影响")
        void dualStackSignature_coexist() {
            // 国密 SM2 签名/验签
            SM2Provider sm2 = gmProvider.getSm2();
            SM2Provider.Sm2KeyPair gmKp = sm2.generateKeyPair();
            byte[] gmSig = sm2.sign(MESSAGE, gmKp.getPrivateKeyD());
            boolean gmVerified = sm2.verify(MESSAGE, gmSig, gmKp.getPublicKeyQ());

            // 国际 RSA 签名/验签
            KeyPair intlKp = intlProvider.getKeyPair();
            byte[] intlSig = intlProvider.sign(MESSAGE, intlKp.getPrivate());
            boolean intlVerified = intlProvider.verifySign(MESSAGE, intlSig, intlKp.getPublic());

            assertThat(gmVerified).isTrue();
            assertThat(intlVerified).isTrue();
            // 签名值不同（不同算法）
            assertThat(gmSig).isNotEqualTo(intlSig);
        }

        @Test
        @DisplayName("通过 SPI 工厂分别获取双栈 Provider 完成签名闭环")
        void spiFactory_dualStackSignature() {
            config.setActiveProfile("xinchang");
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            // 国密栈签名
            GmProvider gm = (GmProvider) factory.getProvider("xinchang");
            SM2Provider.Sm2KeyPair gmKp = gm.getSm2().generateKeyPair();
            byte[] gmSig = gm.getSm2().sign(MESSAGE, gmKp.getPrivateKeyD());
            boolean gmOk = gm.getSm2().verify(MESSAGE, gmSig, gmKp.getPublicKeyQ());

            // 国际栈签名
            IntlProvider intl = (IntlProvider) factory.getProvider("international");
            KeyPair intlKp = intl.getKeyPair();
            byte[] intlSig = intl.sign(MESSAGE, intlKp.getPrivate());
            boolean intlOk = intl.verifySign(MESSAGE, intlSig, intlKp.getPublic());

            assertThat(gmOk).isTrue();
            assertThat(intlOk).isTrue();
        }
    }

    // ==================== SM3 / SHA 摘要互验 ====================

    @Nested
    @DisplayName("SM3/SHA 摘要互验 — 同消息双栈摘要各自正确")
    class DigestCrossVerify {

        @Test
        @DisplayName("同消息 — SM3 与 SHA-256 均输出 32 字节摘要")
        void sameMessage_sm3AndSha256_both32Bytes() {
            byte[] sm3Digest = gmProvider.getSm3().digest(MESSAGE);
            byte[] sha256Digest = intlProvider.getShaProvider().digest(MESSAGE, "SHA-256");

            assertThat(sm3Digest).hasSize(32);
            assertThat(sha256Digest).hasSize(32);
        }

        @Test
        @DisplayName("SM3 摘要 — 确定性（同输入同输出）")
        void sm3Digest_deterministic() {
            byte[] d1 = gmProvider.getSm3().digest(MESSAGE);
            byte[] d2 = gmProvider.getSm3().digest(MESSAGE);
            assertThat(d1).isEqualTo(d2);
        }

        @Test
        @DisplayName("SHA-256 摘要 — 确定性（同输入同输出）")
        void sha256Digest_deterministic() {
            byte[] d1 = intlProvider.getShaProvider().digest(MESSAGE, "SHA-256");
            byte[] d2 = intlProvider.getShaProvider().digest(MESSAGE, "SHA-256");
            assertThat(d1).isEqualTo(d2);
        }

        @Test
        @DisplayName("SM3 与 SHA-256 — 不同算法摘要值不同")
        void sm3AndSha256_differentAlgorithms_differentDigest() {
            byte[] sm3Digest = gmProvider.getSm3().digest(MESSAGE);
            byte[] sha256Digest = intlProvider.getShaProvider().digest(MESSAGE, "SHA-256");

            // 同长度但不同算法，摘要值应不同
            assertThat(sm3Digest).isNotEqualTo(sha256Digest);
        }

        @Test
        @DisplayName("SM3 雪崩效应 — 输入微小变化导致摘要显著不同")
        void sm3Digest_avalanche() {
            byte[] d1 = gmProvider.getSm3().digest(MESSAGE);
            byte[] modified = Arrays.copyOf(MESSAGE, MESSAGE.length);
            modified[0] ^= 0x01;
            byte[] d2 = gmProvider.getSm3().digest(modified);

            assertThat(d1).isNotEqualTo(d2);
            // 统计不同字节位数（雪崩效应应使大部分字节不同）
            int diffBytes = 0;
            for (int i = 0; i < d1.length; i++) {
                if (d1[i] != d2[i]) {
                    diffBytes++;
                }
            }
            assertThat(diffBytes).isGreaterThan(10);
        }

        @Test
        @DisplayName("SHA-256 雪崩效应 — 输入微小变化导致摘要显著不同")
        void sha256Digest_avalanche() {
            byte[] d1 = intlProvider.getShaProvider().digest(MESSAGE, "SHA-256");
            byte[] modified = Arrays.copyOf(MESSAGE, MESSAGE.length);
            modified[0] ^= 0x01;
            byte[] d2 = intlProvider.getShaProvider().digest(modified, "SHA-256");

            assertThat(d1).isNotEqualTo(d2);
            int diffBytes = 0;
            for (int i = 0; i < d1.length; i++) {
                if (d1[i] != d2[i]) {
                    diffBytes++;
                }
            }
            assertThat(diffBytes).isGreaterThan(10);
        }

        @Test
        @DisplayName("双栈摘要共存 — 同一 JVM 中 SM3 与 SHA-256 互不影响")
        void dualStackDigest_coexist() {
            byte[] sm3First = gmProvider.getSm3().digest(MESSAGE);
            byte[] shaFirst = intlProvider.getShaProvider().digest(MESSAGE, "SHA-256");

            // 再次计算，验证互不干扰
            byte[] sm3Second = gmProvider.getSm3().digest(MESSAGE);
            byte[] shaSecond = intlProvider.getShaProvider().digest(MESSAGE, "SHA-256");

            assertThat(sm3First).isEqualTo(sm3Second);
            assertThat(shaFirst).isEqualTo(shaSecond);
            assertThat(sm3First).isNotEqualTo(shaFirst);
        }

        @Test
        @DisplayName("通过 SPI 工厂分别获取双栈 Provider 完成摘要计算")
        void spiFactory_dualStackDigest() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            GmProvider gm = (GmProvider) factory.getProvider("xinchang");
            IntlProvider intl = (IntlProvider) factory.getProvider("international");

            byte[] sm3Digest = gm.getSm3().digest(MESSAGE);
            byte[] shaDigest = intl.getShaProvider().digest(MESSAGE, "SHA-256");

            assertThat(sm3Digest).hasSize(32);
            assertThat(shaDigest).hasSize(32);
            assertThat(sm3Digest).isNotEqualTo(shaDigest);
        }

        @Test
        @DisplayName("空消息摘要 — SM3 与 SHA-256 均可处理空输入")
        void emptyMessage_bothCanDigest() {
            byte[] empty = new byte[0];

            byte[] sm3Digest = gmProvider.getSm3().digest(empty);
            byte[] shaDigest = intlProvider.getShaProvider().digest(empty, "SHA-256");

            assertThat(sm3Digest).hasSize(32);
            assertThat(shaDigest).hasSize(32);
            // 空消息的 SM3 与 SHA-256 摘要均为各自标准已知值，且互不相同
            assertThat(sm3Digest).isNotEqualTo(shaDigest);
        }
    }

    // ==================== SM4 / AES 加密互验 ====================

    @Nested
    @DisplayName("SM4/AES 加密互验 — 各自加密/解密闭环")
    class EncryptionCrossVerify {

        @Test
        @DisplayName("国密 SM4-ECB 加密 → 国密 SM4-ECB 解密 闭环通过")
        void sm4Ecb_encryptDecrypt_shouldRecover() {
            SM4Provider sm4 = gmProvider.getSm4();
            byte[] key = sm4.generateKey();
            byte[] plaintext = "sm4-ecb-互验测试".getBytes(StandardCharsets.UTF_8);

            byte[] ciphertext = sm4.encrypt(plaintext, key, "ECB", null);
            byte[] recovered = sm4.decrypt(ciphertext, key, "ECB", null);

            assertThat(recovered).isEqualTo(plaintext);
            assertThat(ciphertext).isNotEqualTo(plaintext);
        }

        @Test
        @DisplayName("国密 SM4-CBC 加密 → 国密 SM4-CBC 解密 闭环通过")
        void sm4Cbc_encryptDecrypt_shouldRecover() {
            SM4Provider sm4 = gmProvider.getSm4();
            byte[] key = sm4.generateKey();
            byte[] iv = sm4.generateIv();
            byte[] plaintext = "sm4-cbc-互验测试数据".getBytes(StandardCharsets.UTF_8);

            byte[] ciphertext = sm4.encrypt(plaintext, key, "CBC", iv);
            byte[] recovered = sm4.decrypt(ciphertext, key, "CBC", iv);

            assertThat(recovered).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("国际 AES-GCM 加密 → 国际 AES-GCM 解密 闭环通过")
        void aesGcm_encryptDecrypt_shouldRecover() throws Exception {
            SecretKey aesKey = generateAesKey(256);
            byte[] plaintext = "aes-gcm-互验测试".getBytes(StandardCharsets.UTF_8);

            byte[] ciphertext = intlProvider.encrypt(plaintext, aesKey);
            byte[] recovered = intlProvider.decrypt(ciphertext, aesKey);

            assertThat(recovered).isEqualTo(plaintext);
            assertThat(ciphertext).isNotEqualTo(plaintext);
        }

        @Test
        @DisplayName("国际 AES-CBC 加密 → 国际 AES-CBC 解密 闭环通过")
        void aesCbc_encryptDecrypt_shouldRecover() {
            AESProvider aes = intlProvider.getAesProvider();
            byte[] key = aes.generateKey(256);
            byte[] iv = aes.generateCbcIv();
            byte[] plaintext = "aes-cbc-互验测试数据".getBytes(StandardCharsets.UTF_8);

            byte[] ciphertext = aes.encrypt(plaintext, key, "CBC", iv);
            byte[] recovered = aes.decrypt(ciphertext, key, "CBC", iv);

            assertThat(recovered).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("SM4 加密 — 不同密钥产生不同密文")
        void sm4Encrypt_differentKeys_differentCiphertext() {
            SM4Provider sm4 = gmProvider.getSm4();
            byte[] key1 = sm4.generateKey();
            byte[] key2 = sm4.generateKey();
            byte[] plaintext = "different-key-test".getBytes(StandardCharsets.UTF_8);

            byte[] cipher1 = sm4.encrypt(plaintext, key1, "ECB", null);
            byte[] cipher2 = sm4.encrypt(plaintext, key2, "ECB", null);

            assertThat(cipher1).isNotEqualTo(cipher2);
        }

        @Test
        @DisplayName("AES-GCM 加密 — 同密钥多次加密产生不同密文（随机 IV）")
        void aesGcmEncrypt_sameKeyMultipleTimes_differentCiphertext() throws Exception {
            SecretKey aesKey = generateAesKey(256);
            byte[] plaintext = "gcm-random-iv-test".getBytes(StandardCharsets.UTF_8);

            byte[] cipher1 = intlProvider.encrypt(plaintext, aesKey);
            byte[] cipher2 = intlProvider.encrypt(plaintext, aesKey);

            // GCM 模式每次自动生成随机 IV，密文应不同
            assertThat(cipher1).isNotEqualTo(cipher2);
            // 但都能正确解密
            assertThat(intlProvider.decrypt(cipher1, aesKey)).isEqualTo(plaintext);
            assertThat(intlProvider.decrypt(cipher2, aesKey)).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("双栈对称加密共存 — 同一 JVM 中 SM4 与 AES 互不影响")
        void dualStackSymmetric_coexist() {
            // 国密 SM4 闭环
            SM4Provider sm4 = gmProvider.getSm4();
            byte[] sm4Key = sm4.generateKey();
            byte[] sm4Plain = "sm4-coexist".getBytes(StandardCharsets.UTF_8);
            byte[] sm4Cipher = sm4.encrypt(sm4Plain, sm4Key, "ECB", null);
            byte[] sm4Recovered = sm4.decrypt(sm4Cipher, sm4Key, "ECB", null);

            // 国际 AES 闭环
            AESProvider aes = intlProvider.getAesProvider();
            byte[] aesKey = aes.generateKey(256);
            byte[] aesIv = aes.generateCbcIv();
            byte[] aesPlain = "aes-coexist".getBytes(StandardCharsets.UTF_8);
            byte[] aesCipher = aes.encrypt(aesPlain, aesKey, "CBC", aesIv);
            byte[] aesRecovered = aes.decrypt(aesCipher, aesKey, "CBC", aesIv);

            assertThat(sm4Recovered).isEqualTo(sm4Plain);
            assertThat(aesRecovered).isEqualTo(aesPlain);
        }

        @Test
        @DisplayName("通过 SPI 工厂分别获取双栈 Provider 完成对称加密闭环")
        void spiFactory_dualStackSymmetric() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            // 国密栈 SM4
            GmProvider gm = (GmProvider) factory.getProvider("xinchang");
            SM4Provider sm4 = gm.getSm4();
            byte[] sm4Key = sm4.generateKey();
            byte[] sm4Plain = "spi-sm4-test".getBytes(StandardCharsets.UTF_8);
            byte[] sm4Cipher = sm4.encrypt(sm4Plain, sm4Key, "ECB", null);
            byte[] sm4Recovered = sm4.decrypt(sm4Cipher, sm4Key, "ECB", null);

            // 国际栈 AES
            IntlProvider intl = (IntlProvider) factory.getProvider("international");
            AESProvider aes = intl.getAesProvider();
            byte[] aesKey = aes.generateKey(256);
            byte[] aesIv = aes.generateCbcIv();
            byte[] aesPlain = "spi-aes-test".getBytes(StandardCharsets.UTF_8);
            byte[] aesCipher = aes.encrypt(aesPlain, aesKey, "CBC", aesIv);
            byte[] aesRecovered = aes.decrypt(aesCipher, aesKey, "CBC", aesIv);

            assertThat(sm4Recovered).isEqualTo(sm4Plain);
            assertThat(aesRecovered).isEqualTo(aesPlain);
        }
    }

    // ==================== 双栈非对称加密互验 ====================

    @Nested
    @DisplayName("SM2/RSA 非对称加密互验 — 各自加密/解密闭环")
    class AsymmetricEncryptionCrossVerify {

        @Test
        @DisplayName("国密 SM2 加密 → 国密 SM2 解密 闭环通过")
        void sm2Encrypt_sm2Decrypt_shouldRecover() {
            SM2Provider sm2 = gmProvider.getSm2();
            SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
            byte[] plaintext = "sm2-非对称加密互验".getBytes(StandardCharsets.UTF_8);

            byte[] ciphertext = sm2.encrypt(plaintext, kp.getPublicKeyQ());
            byte[] recovered = sm2.decrypt(ciphertext, kp.getPrivateKeyD());

            assertThat(recovered).isEqualTo(plaintext);
            assertThat(ciphertext).isNotEqualTo(plaintext);
        }

        @Test
        @DisplayName("国际 RSA 加密 → 国际 RSA 解密 闭环通过")
        void rsaEncrypt_rsaDecrypt_shouldRecover() {
            KeyPair kp = intlProvider.getKeyPair();
            byte[] plaintext = "rsa-非对称加密互验".getBytes(StandardCharsets.UTF_8);

            byte[] ciphertext = intlProvider.encrypt(plaintext, kp.getPublic());
            byte[] recovered = intlProvider.decrypt(ciphertext, kp.getPrivate());

            assertThat(recovered).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("双栈非对称加密共存 — 同一 JVM 中 SM2 与 RSA 互不影响")
        void dualStackAsymmetric_coexist() {
            // 国密 SM2 闭环
            SM2Provider sm2 = gmProvider.getSm2();
            SM2Provider.Sm2KeyPair gmKp = sm2.generateKeyPair();
            byte[] gmPlain = "sm2-coexist-asym".getBytes(StandardCharsets.UTF_8);
            byte[] gmCipher = sm2.encrypt(gmPlain, gmKp.getPublicKeyQ());
            byte[] gmRecovered = sm2.decrypt(gmCipher, gmKp.getPrivateKeyD());

            // 国际 RSA 闭环
            KeyPair intlKp = intlProvider.getKeyPair();
            byte[] intlPlain = "rsa-coexist-asym".getBytes(StandardCharsets.UTF_8);
            byte[] intlCipher = intlProvider.encrypt(intlPlain, intlKp.getPublic());
            byte[] intlRecovered = intlProvider.decrypt(intlCipher, intlKp.getPrivate());

            assertThat(gmRecovered).isEqualTo(gmPlain);
            assertThat(intlRecovered).isEqualTo(intlPlain);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 生成 AES 测试密钥。
     *
     * @param keySize 密钥长度（128/256）
     * @return AES SecretKey
     * @throws Exception 密钥生成失败
     */
    private SecretKey generateAesKey(int keySize) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(keySize);
        return kg.generateKey();
    }
}