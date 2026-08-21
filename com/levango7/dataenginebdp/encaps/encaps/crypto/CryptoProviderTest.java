package com.shuqing.bigdata.encaps.crypto;

import com.shuqing.bigdata.encaps.crypto.gm.DefaultGmProvider;
import com.shuqing.bigdata.encaps.crypto.intl.DefaultIntlProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import javax.crypto.KeyGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CryptoProvider} 接口契约测试。
 *
 * <p>对默认实现 {@link DefaultGmProvider} 与 {@link DefaultIntlProvider} 进行契约校验，
 * 验证签名/验签、加密/解密、摘要、密钥对生成等核心行为符合接口语义。</p>
 */
class CryptoProviderTest {

    private DefaultGmProvider gmProvider;
    private DefaultIntlProvider intlProvider;

    private static final byte[] DATA = "shuqing-bigdata-crypto-test".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        gmProvider = new DefaultGmProvider();
        intlProvider = new DefaultIntlProvider();
    }

    // ===== 元信息 =====

    @Test
    @DisplayName("GM — providerName=GM-Provider, profile=XINCHANG, algorithm=SIGN")
    void gm_metadata_shouldMatchXinchangGm() {
        assertThat(gmProvider.getProviderName()).isEqualTo("GM-Provider");
        assertThat(gmProvider.getSupportedProfile()).isEqualTo(CryptoProfile.XINCHANG);
        assertThat(gmProvider.getAlgorithmType()).isEqualTo(AlgorithmType.SIGN);
    }

    @Test
    @DisplayName("INTL — providerName=INTL-Provider, profile=INTERNATIONAL, algorithm=SIGN")
    void intl_metadata_shouldMatchInternationalIntl() {
        assertThat(intlProvider.getProviderName()).isEqualTo("INTL-Provider");
        assertThat(intlProvider.getSupportedProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
        assertThat(intlProvider.getAlgorithmType()).isEqualTo(AlgorithmType.SIGN);
    }

    // ===== 摘要 =====

    @Test
    @DisplayName("GM digest — 相同输入产生相同摘要，长度=32字节(SHA-256)")
    void gm_digest_shouldBeDeterministicAnd32Bytes() {
        byte[] d1 = gmProvider.digest(DATA);
        byte[] d2 = gmProvider.digest(DATA);

        assertThat(d1).hasSize(32);
        assertThat(d1).isEqualTo(d2);
    }

    @Test
    @DisplayName("INTL digest — 相同输入产生相同摘要，长度=32字节(SHA-256)")
    void intl_digest_shouldBeDeterministicAnd32Bytes() {
        byte[] d1 = intlProvider.digest(DATA);
        byte[] d2 = intlProvider.digest(DATA);

        assertThat(d1).hasSize(32);
        assertThat(d1).isEqualTo(d2);
    }

    @Test
    @DisplayName("digest — 不同输入产生不同摘要")
    void digest_differentInput_shouldProduceDifferentDigest() {
        byte[] other = "different-input".getBytes(StandardCharsets.UTF_8);

        assertThat(gmProvider.digest(DATA)).isNotEqualTo(gmProvider.digest(other));
        assertThat(intlProvider.digest(DATA)).isNotEqualTo(intlProvider.digest(other));
    }

    @Test
    @DisplayName("digest — null入参抛CryptoException")
    void digest_nullInput_shouldThrow() {
        assertThatThrownBy(() -> gmProvider.digest(null))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.digest(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("digestHex — 返回64字符hex串")
    void digestHex_shouldReturn64CharHex() {
        String hex = gmProvider.digestHex(DATA);
        assertThat(hex).hasSize(64);
        assertThat(hex).matches("[0-9a-f]{64}");
    }

    // ===== 签名/验签 =====

    @Test
    @DisplayName("GM sign/verify — 签名后验签通过")
    void gm_signAndVerify_shouldVerifySuccessfully() {
        KeyPair keyPair = gmProvider.getKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        byte[] signature = gmProvider.sign(DATA, privateKey);
        assertThat(signature).isNotEmpty();

        boolean verified = gmProvider.verifySign(DATA, signature, publicKey);
        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("INTL sign/verify — 签名后验签通过")
    void intl_signAndVerify_shouldVerifySuccessfully() {
        KeyPair keyPair = intlProvider.getKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        byte[] signature = intlProvider.sign(DATA, privateKey);
        assertThat(signature).isNotEmpty();

        boolean verified = intlProvider.verifySign(DATA, signature, publicKey);
        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("verifySign — 篡改数据后验签失败")
    void verifySign_tamperedData_shouldReturnFalse() {
        KeyPair keyPair = gmProvider.getKeyPair();
        byte[] signature = gmProvider.sign(DATA, keyPair.getPrivate());

        byte[] tampered = Arrays.copyOf(DATA, DATA.length);
        tampered[0] ^= 0x01;

        boolean verified = gmProvider.verifySign(tampered, signature, keyPair.getPublic());
        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("verifySign — 篡改签名后验签失败")
    void verifySign_tamperedSignature_shouldReturnFalse() {
        KeyPair keyPair = intlProvider.getKeyPair();
        byte[] signature = intlProvider.sign(DATA, keyPair.getPrivate());

        byte[] tamperedSign = Arrays.copyOf(signature, signature.length);
        tamperedSign[0] ^= 0x01;

        boolean verified = intlProvider.verifySign(DATA, tamperedSign, keyPair.getPublic());
        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("sign — null入参抛CryptoException")
    void sign_nullArgs_shouldThrow() {
        KeyPair keyPair = gmProvider.getKeyPair();

        assertThatThrownBy(() -> gmProvider.sign(null, keyPair.getPrivate()))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> gmProvider.sign(DATA, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("verifySign — null入参抛CryptoException")
    void verifySign_nullArgs_shouldThrow() {
        KeyPair keyPair = intlProvider.getKeyPair();
        byte[] signature = intlProvider.sign(DATA, keyPair.getPrivate());

        assertThatThrownBy(() -> intlProvider.verifySign(null, signature, keyPair.getPublic()))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.verifySign(DATA, null, keyPair.getPublic()))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.verifySign(DATA, signature, null))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 对称加密/解密（AES） =====

    @Test
    @DisplayName("GM encrypt/decrypt(AES) — 对称加密后解密还原原文")
    void gm_symmetricEncryptDecrypt_shouldRoundTrip() throws Exception {
        Key aesKey = generateAesKey();
        byte[] plaintext = "symmetric-secret".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = gmProvider.encrypt(plaintext, aesKey);
        assertThat(ciphertext).isNotEmpty();
        assertThat(ciphertext).isNotEqualTo(plaintext);

        byte[] decrypted = gmProvider.decrypt(ciphertext, aesKey);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("INTL encrypt/decrypt(AES) — 对称加密后解密还原原文")
    void intl_symmetricEncryptDecrypt_shouldRoundTrip() throws Exception {
        Key aesKey = generateAesKey();
        byte[] plaintext = "intl-symmetric-secret".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = intlProvider.encrypt(plaintext, aesKey);
        assertThat(ciphertext).isNotEmpty();

        byte[] decrypted = intlProvider.decrypt(ciphertext, aesKey);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("encrypt — null入参抛CryptoException")
    void encrypt_nullArgs_shouldThrow() {
        Key aesKey = generateAesKey();

        assertThatThrownBy(() -> gmProvider.encrypt(null, aesKey))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> gmProvider.encrypt(DATA, null))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.encrypt(null, aesKey))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.encrypt(DATA, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt — null入参抛CryptoException")
    void decrypt_nullArgs_shouldThrow() {
        Key aesKey = generateAesKey();

        assertThatThrownBy(() -> gmProvider.decrypt(null, aesKey))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> gmProvider.decrypt(DATA, null))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.decrypt(null, aesKey))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.decrypt(DATA, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt — 错误密文抛CryptoException")
    void decrypt_invalidCiphertext_shouldThrow() {
        Key aesKey = generateAesKey();
        byte[] badCiphertext = "not-a-valid-ciphertext".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> gmProvider.decrypt(badCiphertext, aesKey))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> intlProvider.decrypt(badCiphertext, aesKey))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("encrypt/decrypt — 不同Provider对同一AES密钥结果一致（均走JDK标准AES）")
    void encryptDecrypt_differentProvidersSameAlgorithm_shouldBehaveConsistently() throws Exception {
        Key aesKey = generateAesKey();
        byte[] plaintext = "cross-provider-test".getBytes(StandardCharsets.UTF_8);

        // GM 加密 → INTL 解密（同算法可互通）
        byte[] ciphertext = gmProvider.encrypt(plaintext, aesKey);
        byte[] decrypted = intlProvider.decrypt(ciphertext, aesKey);
        assertThat(decrypted).isEqualTo(plaintext);

        // INTL 加密 → GM 解密
        byte[] ciphertext2 = intlProvider.encrypt(plaintext, aesKey);
        byte[] decrypted2 = gmProvider.decrypt(ciphertext2, aesKey);
        assertThat(decrypted2).isEqualTo(plaintext);
    }

    // ===== 非对称加密/解密（RSA） =====

    @Test
    @DisplayName("GM encrypt/decrypt(RSA) — 公钥加密私钥解密还原原文")
    void gm_asymmetricEncryptDecrypt_shouldRoundTrip() {
        KeyPair keyPair = gmProvider.getKeyPair();
        // RSA 2048 PKCS1Padding 最多 245 字节，用短明文
        byte[] plaintext = "asym".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = gmProvider.encrypt(plaintext, keyPair.getPublic());
        assertThat(ciphertext).isNotEmpty();
        assertThat(ciphertext).isNotEqualTo(plaintext);

        byte[] decrypted = gmProvider.decrypt(ciphertext, keyPair.getPrivate());
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("INTL encrypt/decrypt(RSA) — 公钥加密私钥解密还原原文")
    void intl_asymmetricEncryptDecrypt_shouldRoundTrip() {
        KeyPair keyPair = intlProvider.getKeyPair();
        byte[] plaintext = "asym-intl".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = intlProvider.encrypt(plaintext, keyPair.getPublic());
        assertThat(ciphertext).isNotEmpty();

        byte[] decrypted = intlProvider.decrypt(ciphertext, keyPair.getPrivate());
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("INTL encrypt(RSA) — 错误密钥/数据抛CryptoException")
    void intl_asymmetricEncrypt_invalid_shouldThrow() {
        KeyPair keyPair = intlProvider.getKeyPair();
        // 用私钥"加密"在 RSA PKCS1 模式下实际可工作，但用损坏的密文解密应失败
        byte[] badCiphertext = new byte[]{1, 2, 3, 4, 5};

        assertThatThrownBy(() -> intlProvider.decrypt(badCiphertext, keyPair.getPrivate()))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("GM encrypt(RSA) — 损坏密文解密抛CryptoException")
    void gm_asymmetricDecrypt_invalidCiphertext_shouldThrow() {
        KeyPair keyPair = gmProvider.getKeyPair();
        byte[] badCiphertext = new byte[]{9, 8, 7, 6};

        assertThatThrownBy(() -> gmProvider.decrypt(badCiphertext, keyPair.getPrivate()))
                .isInstanceOf(CryptoException.class);
    }

    /**
     * 生成 AES-128 测试密钥。
     */
    private Key generateAesKey() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(128);
            return kg.generateKey();
        } catch (Exception e) {
            throw new CryptoException("Failed to generate AES key", e);
        }
    }

    // ===== 密钥对 =====

    @Test
    @DisplayName("getKeyPair — 每次生成不同的密钥对")
    void getKeyPair_shouldGenerateDifferentPairs() {
        KeyPair kp1 = gmProvider.getKeyPair();
        KeyPair kp2 = gmProvider.getKeyPair();

        assertThat(kp1.getPrivate().getEncoded()).isNotEqualTo(kp2.getPrivate().getEncoded());
        assertThat(kp1.getPublic().getEncoded()).isNotEqualTo(kp2.getPublic().getEncoded());
    }

    @Test
    @DisplayName("getKeyPair — 公私钥算法为RSA（占位）")
    void getKeyPair_algorithmShouldBeRsa() {
        KeyPair kp = intlProvider.getKeyPair();
        assertThat(kp.getPrivate().getAlgorithm()).isEqualTo("RSA");
        assertThat(kp.getPublic().getAlgorithm()).isEqualTo("RSA");
    }

    // ===== constantTimeEquals =====

    @Test
    @DisplayName("constantTimeEquals — 相等数组返回true")
    void constantTimeEquals_equalArrays_shouldReturnTrue() {
        byte[] a = {1, 2, 3};
        byte[] b = {1, 2, 3};
        assertThat(gmProvider.constantTimeEquals(a, b)).isTrue();
        assertThat(intlProvider.constantTimeEquals(a, b)).isTrue();
    }

    @Test
    @DisplayName("constantTimeEquals — 不等数组返回false")
    void constantTimeEquals_unequalArrays_shouldReturnFalse() {
        byte[] a = {1, 2, 3};
        byte[] b = {1, 2, 4};
        assertThat(gmProvider.constantTimeEquals(a, b)).isFalse();
        assertThat(intlProvider.constantTimeEquals(a, b)).isFalse();
    }

    @Test
    @DisplayName("constantTimeEquals — 长度不等返回false")
    void constantTimeEquals_differentLength_shouldReturnFalse() {
        byte[] a = {1, 2, 3};
        byte[] b = {1, 2};
        assertThat(gmProvider.constantTimeEquals(a, b)).isFalse();
        assertThat(intlProvider.constantTimeEquals(a, b)).isFalse();
    }

    @Test
    @DisplayName("digestHex(INTL) — 返回64字符hex串")
    void intl_digestHex_shouldReturn64CharHex() {
        String hex = intlProvider.digestHex(DATA);
        assertThat(hex).hasSize(64);
        assertThat(hex).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("constantTimeEquals — null入参返回false")
    void constantTimeEquals_nullArgs_shouldReturnFalse() {
        byte[] a = {1, 2, 3};
        assertThat(gmProvider.constantTimeEquals(null, a)).isFalse();
        assertThat(gmProvider.constantTimeEquals(a, null)).isFalse();
        assertThat(intlProvider.constantTimeEquals(null, null)).isFalse();
    }

    // ===== AlgorithmType 枚举 =====

    @Test
    @DisplayName("AlgorithmType — 包含4个枚举值")
    void algorithmType_shouldContainFourValues() {
        assertThat(AlgorithmType.values())
                .containsExactlyInAnyOrder(
                        AlgorithmType.SIGN,
                        AlgorithmType.ENCRYPT,
                        AlgorithmType.DIGEST,
                        AlgorithmType.SYMMETRIC);
    }

    @Test
    @DisplayName("AlgorithmType.valueOf — 大小写敏感，可正常解析")
    void algorithmType_valueOf_shouldWork() {
        assertThat(AlgorithmType.valueOf("SIGN")).isEqualTo(AlgorithmType.SIGN);
        assertThat(AlgorithmType.valueOf("ENCRYPT")).isEqualTo(AlgorithmType.ENCRYPT);
        assertThat(AlgorithmType.valueOf("DIGEST")).isEqualTo(AlgorithmType.DIGEST);
        assertThat(AlgorithmType.valueOf("SYMMETRIC")).isEqualTo(AlgorithmType.SYMMETRIC);
    }

    // ===== CryptoProfile 枚举 =====

    @Test
    @DisplayName("CryptoProfile — 包含2个枚举值")
    void cryptoProfile_shouldContainTwoValues() {
        assertThat(CryptoProfile.values())
                .containsExactlyInAnyOrder(
                        CryptoProfile.XINCHANG,
                        CryptoProfile.INTERNATIONAL);
    }

    @Test
    @DisplayName("CryptoProfile.fromString — 大小写不敏感")
    void cryptoProfile_fromString_shouldBeCaseInsensitive() {
        assertThat(CryptoProfile.fromString("xinchang")).isEqualTo(CryptoProfile.XINCHANG);
        assertThat(CryptoProfile.fromString("XINCHANG")).isEqualTo(CryptoProfile.XINCHANG);
        assertThat(CryptoProfile.fromString("XiNcHaNg")).isEqualTo(CryptoProfile.XINCHANG);
        assertThat(CryptoProfile.fromString("international")).isEqualTo(CryptoProfile.INTERNATIONAL);
        assertThat(CryptoProfile.fromString("INTERNATIONAL")).isEqualTo(CryptoProfile.INTERNATIONAL);
    }

    @Test
    @DisplayName("CryptoProfile.fromString — 前后空白被trim")
    void cryptoProfile_fromString_shouldTrimWhitespace() {
        assertThat(CryptoProfile.fromString("  xinchang  ")).isEqualTo(CryptoProfile.XINCHANG);
        assertThat(CryptoProfile.fromString(" international ")).isEqualTo(CryptoProfile.INTERNATIONAL);
    }

    @Test
    @DisplayName("CryptoProfile.fromString — null/空白抛CryptoException")
    void cryptoProfile_fromString_blank_shouldThrow() {
        assertThatThrownBy(() -> CryptoProfile.fromString(null))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> CryptoProfile.fromString(""))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> CryptoProfile.fromString("   "))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("CryptoProfile.fromString — 未知值抛CryptoException")
    void cryptoProfile_fromString_unknown_shouldThrow() {
        assertThatThrownBy(() -> CryptoProfile.fromString("unknown"))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("Unknown CryptoProfile");
    }

    @Test
    @DisplayName("CryptoProfile — getProfileName/getDefaultProviderName/getDescription")
    void cryptoProfile_accessors_shouldReturnExpectedValues() {
        assertThat(CryptoProfile.XINCHANG.getProfileName()).isEqualTo("xinchang");
        assertThat(CryptoProfile.XINCHANG.getDefaultProviderName()).isEqualTo("GM-Provider");
        assertThat(CryptoProfile.XINCHANG.getDescription()).contains("国密");

        assertThat(CryptoProfile.INTERNATIONAL.getProfileName()).isEqualTo("international");
        assertThat(CryptoProfile.INTERNATIONAL.getDefaultProviderName()).isEqualTo("INTL-Provider");
        assertThat(CryptoProfile.INTERNATIONAL.getDescription()).contains("RSA");
    }

    // ===== CryptoException =====

    @Test
    @DisplayName("CryptoException — 仅消息构造")
    void cryptoException_messageOnly_shouldWork() {
        CryptoException ex = new CryptoException("test message");
        assertThat(ex.getMessage()).isEqualTo("test message");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("CryptoException — 消息+cause构造")
    void cryptoException_messageAndCause_shouldWork() {
        Throwable cause = new IllegalArgumentException("root");
        CryptoException ex = new CryptoException("wrapper", cause);
        assertThat(ex.getMessage()).isEqualTo("wrapper");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("CryptoException — 仅cause构造")
    void cryptoException_causeOnly_shouldWork() {
        Throwable cause = new IllegalStateException("root");
        CryptoException ex = new CryptoException(cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }
}