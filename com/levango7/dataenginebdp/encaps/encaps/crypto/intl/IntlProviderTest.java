package com.shuqing.bigdata.encaps.crypto.intl;

import com.shuqing.bigdata.encaps.crypto.AlgorithmType;
import com.shuqing.bigdata.encaps.crypto.CryptoConfig;
import com.shuqing.bigdata.encaps.crypto.CryptoException;
import com.shuqing.bigdata.encaps.crypto.CryptoProfile;
import com.shuqing.bigdata.encaps.crypto.CryptoProvider;
import com.shuqing.bigdata.encaps.crypto.CryptoSpiFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IntlProvider} 单元测试。
 *
 * <p>验证国际 Provider 聚合类的 SPI 加载、算法路由、CryptoProvider 接口契约、
 * 以及与 {@link CryptoSpiFactory} 的集成。</p>
 */
class IntlProviderTest {

    private IntlProvider intlProvider;
    private CryptoConfig config;

    private static final byte[] DATA = "intl-provider-test".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        intlProvider = new IntlProvider();
        config = new CryptoConfig();
    }

    // ===== 元信息 =====

    @Test
    @DisplayName("元信息 — providerName=INTL-Provider, profile=INTERNATIONAL, algorithm=SIGN")
    void metadata_shouldMatchExpected() {
        assertThat(intlProvider.getProviderName()).isEqualTo("INTL-Provider");
        assertThat(intlProvider.getSupportedProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
        assertThat(intlProvider.getAlgorithmType()).isEqualTo(AlgorithmType.SIGN);
    }

    @Test
    @DisplayName("内部Provider — getRsaProvider/getShaProvider/getAesProvider 返回非null")
    void internalProviders_shouldBeNonNull() {
        assertThat(intlProvider.getRsaProvider()).isNotNull();
        assertThat(intlProvider.getShaProvider()).isNotNull();
        assertThat(intlProvider.getAesProvider()).isNotNull();
    }

    // ===== SPI 加载集成 =====

    @Test
    @DisplayName("SPI加载 — CryptoSpiFactory 按 Profile=international 获取 IntlProvider")
    void spiLoad_internationalProfile_shouldReturnIntlProvider() {
        config.setActiveProfile("international");
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider provider = factory.getProvider();
        assertThat(provider.getProviderName()).isEqualTo("INTL-Provider");
        assertThat(provider).isInstanceOf(IntlProvider.class);
        assertThat(provider.getSupportedProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
    }

    @Test
    @DisplayName("SPI加载 — getAvailableProviders(international) 返回 IntlProvider")
    void spiLoad_availableProvidersInternational_shouldContainIntl() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        var providers = factory.getAvailableProviders("international");
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).getProviderName()).isEqualTo("INTL-Provider");
        assertThat(providers.get(0)).isInstanceOf(IntlProvider.class);
    }

    @Test
    @DisplayName("SPI加载 — getProviderByName(INTL-Provider) 返回 IntlProvider 实例")
    void spiLoad_getByName_shouldReturnIntlProvider() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider provider = factory.getProviderByName("INTL-Provider");
        assertThat(provider).isInstanceOf(IntlProvider.class);
    }

    // ===== 算法路由 =====

    @Test
    @DisplayName("算法路由 — digest 委托给 SHAProvider (SHA-256)")
    void routing_digest_shouldDelegateToShaProvider() {
        byte[] viaProvider = intlProvider.digest(DATA);
        byte[] viaSha = intlProvider.getShaProvider().digest(DATA, "SHA-256");
        assertThat(viaProvider).isEqualTo(viaSha);
        assertThat(viaProvider).hasSize(32);
    }

    @Test
    @DisplayName("算法路由 — sign/verifySign 委托给 RSAProvider")
    void routing_signVerify_shouldDelegateToRsaProvider() {
        KeyPair kp = intlProvider.getKeyPair();
        PrivateKey privateKey = kp.getPrivate();
        PublicKey publicKey = kp.getPublic();

        byte[] signature = intlProvider.sign(DATA, privateKey);
        assertThat(signature).isNotEmpty();

        boolean verified = intlProvider.verifySign(DATA, signature, publicKey);
        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("算法路由 — RSA encrypt/decrypt 委托给 RSAProvider")
    void routing_rsaEncryptDecrypt_shouldDelegateToRsaProvider() {
        KeyPair kp = intlProvider.getKeyPair();
        byte[] plaintext = "routing-rsa".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = intlProvider.encrypt(plaintext, kp.getPublic());
        byte[] decrypted = intlProvider.decrypt(ciphertext, kp.getPrivate());
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("算法路由 — AES encrypt/decrypt 委托给 AESProvider (GCM)")
    void routing_aesEncryptDecrypt_shouldDelegateToAesProvider() throws Exception {
        SecretKey aesKey = generateAesKey();
        byte[] plaintext = "routing-aes-gcm".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = intlProvider.encrypt(plaintext, aesKey);
        byte[] decrypted = intlProvider.decrypt(ciphertext, aesKey);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    // ===== CryptoProvider 接口契约 =====

    @Test
    @DisplayName("digest — 相同输入相同输出，长度32字节")
    void digest_shouldBeDeterministic() {
        byte[] d1 = intlProvider.digest(DATA);
        byte[] d2 = intlProvider.digest(DATA);
        assertThat(d1).isEqualTo(d2);
        assertThat(d1).hasSize(32);
    }

    @Test
    @DisplayName("digest — null入参抛CryptoException")
    void digest_null_shouldThrow() {
        assertThatThrownBy(() -> intlProvider.digest(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("sign — null入参抛CryptoException")
    void sign_null_shouldThrow() {
        KeyPair kp = intlProvider.getKeyPair();
        assertThatThrownBy(() -> intlProvider.sign(null, kp.getPrivate()))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.sign(DATA, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("verifySign — null入参抛CryptoException")
    void verifySign_null_shouldThrow() {
        KeyPair kp = intlProvider.getKeyPair();
        byte[] signature = intlProvider.sign(DATA, kp.getPrivate());

        assertThatThrownBy(() -> intlProvider.verifySign(null, signature, kp.getPublic()))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.verifySign(DATA, null, kp.getPublic()))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.verifySign(DATA, signature, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("encrypt — null入参抛CryptoException")
    void encrypt_null_shouldThrow() {
        KeyPair kp = intlProvider.getKeyPair();
        assertThatThrownBy(() -> intlProvider.encrypt(null, kp.getPublic()))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.encrypt(DATA, (java.security.Key) null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt — null入参抛CryptoException")
    void decrypt_null_shouldThrow() {
        KeyPair kp = intlProvider.getKeyPair();
        assertThatThrownBy(() -> intlProvider.decrypt(null, kp.getPrivate()))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.decrypt(DATA, (java.security.Key) null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("encrypt — 不支持的密钥算法抛CryptoException")
    void encrypt_unsupportedKeyAlgorithm_shouldThrow() {
        // 使用一个非RSA/非AES的Key实现
        java.security.Key fakeKey = new java.security.Key() {
            @Override
            public String getAlgorithm() {
                return "DES";
            }

            @Override
            public String getFormat() {
                return "RAW";
            }

            @Override
            public byte[] getEncoded() {
                return new byte[8];
            }
        };
        assertThatThrownBy(() -> intlProvider.encrypt(DATA, fakeKey))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt — 不支持的密钥算法抛CryptoException")
    void decrypt_unsupportedKeyAlgorithm_shouldThrow() {
        java.security.Key fakeKey = new java.security.Key() {
            @Override
            public String getAlgorithm() {
                return "DES";
            }

            @Override
            public String getFormat() {
                return "RAW";
            }

            @Override
            public byte[] getEncoded() {
                return new byte[8];
            }
        };
        assertThatThrownBy(() -> intlProvider.decrypt(DATA, fakeKey))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("encrypt(RSA) — 用PrivateKey加密抛CryptoException（需要PublicKey）")
    void encryptRsa_withPrivateKey_shouldThrow() {
        KeyPair kp = intlProvider.getKeyPair();
        assertThatThrownBy(() -> intlProvider.encrypt(DATA, kp.getPrivate()))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt(RSA) — 用PublicKey解密抛CryptoException（需要PrivateKey）")
    void decryptRsa_withPublicKey_shouldThrow() {
        KeyPair kp = intlProvider.getKeyPair();
        byte[] ciphertext = intlProvider.encrypt("test".getBytes(), kp.getPublic());
        assertThatThrownBy(() -> intlProvider.decrypt(ciphertext, kp.getPublic()))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt(AES) — 密文太短抛CryptoException")
    void decryptAes_tooShort_shouldThrow() throws Exception {
        SecretKey aesKey = generateAesKey();
        byte[] shortCiphertext = new byte[5];
        assertThatThrownBy(() -> intlProvider.decrypt(shortCiphertext, aesKey))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 密钥对生成 =====

    @Test
    @DisplayName("getKeyPair — 默认生成 RSA 2048 密钥对")
    void getKeyPair_shouldGenerateRsa2048() {
        KeyPair kp = intlProvider.getKeyPair();
        assertThat(kp.getPrivate().getAlgorithm()).isEqualTo("RSA");
        assertThat(kp.getPublic().getAlgorithm()).isEqualTo("RSA");
        // 2048 位公钥编码约 294 字节
        assertThat(kp.getPublic().getEncoded()).hasSizeGreaterThan(270);
    }

    @Test
    @DisplayName("generateRsaKeyPair(4096) — 生成 RSA 4096 密钥对")
    void generateRsaKeyPair_4096_shouldWork() {
        KeyPair kp = intlProvider.generateRsaKeyPair(4096);
        assertThat(kp.getPrivate().getAlgorithm()).isEqualTo("RSA");
        assertThat(kp.getPrivate().getEncoded()).hasSizeGreaterThan(2000);
    }

    @Test
    @DisplayName("generateAesKey(128) — 生成 16 字节密钥")
    void generateAesKey_128_shouldWork() {
        byte[] key = intlProvider.generateAesKey(128);
        assertThat(key).hasSize(16);
    }

    @Test
    @DisplayName("generateAesKey(256) — 生成 32 字节密钥")
    void generateAesKey_256_shouldWork() {
        byte[] key = intlProvider.generateAesKey(256);
        assertThat(key).hasSize(32);
    }

    // ===== 便捷方法 =====

    @Test
    @DisplayName("digestHex — 返回 64 字符 hex 字符串")
    void digestHex_shouldReturn64CharHex() {
        String hex = intlProvider.digestHex(DATA);
        assertThat(hex).hasSize(64);
        assertThat(hex).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("digest(data, algorithm) — 支持 SHA-384/512")
    void digestWithAlgorithm_shouldWork() {
        byte[] sha256 = intlProvider.digest(DATA, "SHA-256");
        byte[] sha384 = intlProvider.digest(DATA, "SHA-384");
        byte[] sha512 = intlProvider.digest(DATA, "SHA-512");

        assertThat(sha256).hasSize(32);
        assertThat(sha384).hasSize(48);
        assertThat(sha512).hasSize(64);
    }

    @Test
    @DisplayName("constantTimeEquals — 相等返回true")
    void constantTimeEquals_equal_shouldReturnTrue() {
        byte[] a = {1, 2, 3, 4, 5};
        byte[] b = {1, 2, 3, 4, 5};
        assertThat(intlProvider.constantTimeEquals(a, b)).isTrue();
    }

    @Test
    @DisplayName("constantTimeEquals — 不等返回false")
    void constantTimeEquals_unequal_shouldReturnFalse() {
        byte[] a = {1, 2, 3};
        byte[] b = {1, 2, 4};
        assertThat(intlProvider.constantTimeEquals(a, b)).isFalse();
    }

    @Test
    @DisplayName("constantTimeEquals — 长度不等返回false")
    void constantTimeEquals_differentLength_shouldReturnFalse() {
        byte[] a = {1, 2, 3};
        byte[] b = {1, 2};
        assertThat(intlProvider.constantTimeEquals(a, b)).isFalse();
    }

    @Test
    @DisplayName("constantTimeEquals — null入参返回false")
    void constantTimeEquals_null_shouldReturnFalse() {
        byte[] a = {1, 2, 3};
        assertThat(intlProvider.constantTimeEquals(null, a)).isFalse();
        assertThat(intlProvider.constantTimeEquals(a, null)).isFalse();
        assertThat(intlProvider.constantTimeEquals(null, null)).isFalse();
    }

    // ===== 签名/验签往返 =====

    @Test
    @DisplayName("sign/verify — 篡改数据验签失败")
    void signVerify_tamperedData_shouldReturnFalse() {
        KeyPair kp = intlProvider.getKeyPair();
        byte[] signature = intlProvider.sign(DATA, kp.getPrivate());

        byte[] tampered = Arrays.copyOf(DATA, DATA.length);
        tampered[0] ^= 0x01;

        assertThat(intlProvider.verifySign(tampered, signature, kp.getPublic())).isFalse();
    }

    @Test
    @DisplayName("sign/verify — 篡改签名验签失败")
    void signVerify_tamperedSignature_shouldReturnFalse() {
        KeyPair kp = intlProvider.getKeyPair();
        byte[] signature = intlProvider.sign(DATA, kp.getPrivate());

        byte[] tamperedSign = Arrays.copyOf(signature, signature.length);
        tamperedSign[0] ^= 0x01;

        assertThat(intlProvider.verifySign(DATA, tamperedSign, kp.getPublic())).isFalse();
    }

    // ===== 注入构造 =====

    @Test
    @DisplayName("注入构造 — 使用自定义RSA/SHA/AES Provider")
    void injectionConstructor_shouldUseCustomProviders() {
        RSAProvider customRsa = new RSAProvider();
        SHAProvider customSha = new SHAProvider();
        AESProvider customAes = new AESProvider();

        IntlProvider custom = new IntlProvider(customRsa, customSha, customAes);

        assertThat(custom.getRsaProvider()).isSameAs(customRsa);
        assertThat(custom.getShaProvider()).isSameAs(customSha);
        assertThat(custom.getAesProvider()).isSameAs(customAes);
    }

    /**
     * 生成 AES-256 测试密钥。
     */
    private SecretKey generateAesKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return kg.generateKey();
    }
}