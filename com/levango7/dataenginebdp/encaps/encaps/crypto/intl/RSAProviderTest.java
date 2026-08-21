package com.shuqing.bigdata.encaps.crypto.intl;

import com.shuqing.bigdata.encaps.crypto.CryptoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RSAProvider} 单元测试。
 *
 * <p>覆盖 RSA 签名/验签、加密/解密、密钥对生成、异常分支等。
 * 验证 PKCS#1 v2.1 规范符合性（2048/4096 位）。</p>
 */
class RSAProviderTest {

    private RSAProvider rsaProvider;

    private static final byte[] DATA = "shuqing-bigdata-rsa-test".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SHORT_DATA = "abc".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        rsaProvider = new RSAProvider();
    }

    // ===== 密钥对生成 =====

    @Test
    @DisplayName("generateKeyPair(2048) — 生成有效 RSA 2048 密钥对")
    void generateKeyPair_2048_shouldGenerateValidPair() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);

        assertThat(kp).isNotNull();
        assertThat(kp.getPrivate().getAlgorithm()).isEqualTo("RSA");
        assertThat(kp.getPublic().getAlgorithm()).isEqualTo("RSA");
        assertThat(kp.getPrivate().getEncoded()).isNotEmpty();
        assertThat(kp.getPublic().getEncoded()).isNotEmpty();
        // X.509 公钥编码长度约 294 字节，PKCS#8 私钥编码长度约 1200-1700 字节（可变）
        assertThat(kp.getPublic().getEncoded()).hasSizeGreaterThan(270);
        assertThat(kp.getPrivate().getEncoded()).hasSizeGreaterThan(1000);
    }

    @Test
    @DisplayName("generateKeyPair(4096) — 生成有效 RSA 4096 密钥对")
    void generateKeyPair_4096_shouldGenerateValidPair() {
        KeyPair kp = rsaProvider.generateKeyPair(4096);

        assertThat(kp.getPrivate().getAlgorithm()).isEqualTo("RSA");
        assertThat(kp.getPublic().getAlgorithm()).isEqualTo("RSA");
        // 4096 位私钥编码更长
        assertThat(kp.getPrivate().getEncoded()).hasSizeGreaterThan(2000);
    }

    @Test
    @DisplayName("generateKeyPair(3072) — 生成有效 RSA 3072 密钥对")
    void generateKeyPair_3072_shouldGenerateValidPair() {
        KeyPair kp = rsaProvider.generateKeyPair(3072);
        assertThat(kp.getPrivate().getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    @DisplayName("generateKeyPair — 不支持的密钥长度抛CryptoException")
    void generateKeyPair_unsupportedSize_shouldThrow() {
        assertThatThrownBy(() -> rsaProvider.generateKeyPair(1024))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("Unsupported RSA key size");
        assertThatThrownBy(() -> rsaProvider.generateKeyPair(512))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> rsaProvider.generateKeyPair(2049))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("generateKeyPair() — 默认生成 2048 位")
    void generateKeyPair_default_shouldBe2048() {
        KeyPair kp = rsaProvider.generateKeyPair();
        assertThat(kp.getPublic().getEncoded()).hasSizeGreaterThan(270);
    }

    @Test
    @DisplayName("generateKeyPair — 每次生成不同密钥对")
    void generateKeyPair_shouldGenerateDifferentPairs() {
        KeyPair kp1 = rsaProvider.generateKeyPair();
        KeyPair kp2 = rsaProvider.generateKeyPair();

        assertThat(kp1.getPrivate().getEncoded()).isNotEqualTo(kp2.getPrivate().getEncoded());
        assertThat(kp1.getPublic().getEncoded()).isNotEqualTo(kp2.getPublic().getEncoded());
    }

    // ===== 签名/验签 =====

    @Test
    @DisplayName("sign/verify(2048) — 签名后验签通过")
    void signAndVerify_2048_shouldRoundTrip() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] privateKey = kp.getPrivate().getEncoded();
        byte[] publicKey = kp.getPublic().getEncoded();

        byte[] signature = rsaProvider.sign(DATA, privateKey);
        assertThat(signature).isNotEmpty();
        assertThat(signature).hasSize(256); // 2048/8 = 256

        boolean verified = rsaProvider.verify(DATA, signature, publicKey);
        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("sign/verify(4096) — 签名后验签通过")
    void signAndVerify_4096_shouldRoundTrip() {
        KeyPair kp = rsaProvider.generateKeyPair(4096);
        byte[] privateKey = kp.getPrivate().getEncoded();
        byte[] publicKey = kp.getPublic().getEncoded();

        byte[] signature = rsaProvider.sign(DATA, privateKey);
        assertThat(signature).hasSize(512); // 4096/8 = 512

        boolean verified = rsaProvider.verify(DATA, signature, publicKey);
        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("sign/verify(SHA384withRSA) — 指定算法签名验签通过")
    void signAndVerify_sha384_shouldRoundTrip() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] privateKey = kp.getPrivate().getEncoded();
        byte[] publicKey = kp.getPublic().getEncoded();

        byte[] signature = rsaProvider.sign(DATA, privateKey, "SHA384withRSA");
        boolean verified = rsaProvider.verify(DATA, signature, publicKey, "SHA384withRSA");
        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("sign/verify(SHA512withRSA) — 指定算法签名验签通过")
    void signAndVerify_sha512_shouldRoundTrip() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] privateKey = kp.getPrivate().getEncoded();
        byte[] publicKey = kp.getPublic().getEncoded();

        byte[] signature = rsaProvider.sign(DATA, privateKey, "SHA512withRSA");
        boolean verified = rsaProvider.verify(DATA, signature, publicKey, "SHA512withRSA");
        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("verify — 篡改数据后验签失败")
    void verify_tamperedData_shouldReturnFalse() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] signature = rsaProvider.sign(DATA, kp.getPrivate().getEncoded());

        byte[] tampered = Arrays.copyOf(DATA, DATA.length);
        tampered[0] ^= 0x01;

        boolean verified = rsaProvider.verify(tampered, signature, kp.getPublic().getEncoded());
        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("verify — 篡改签名后验签失败")
    void verify_tamperedSignature_shouldReturnFalse() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] signature = rsaProvider.sign(DATA, kp.getPrivate().getEncoded());

        byte[] tamperedSign = Arrays.copyOf(signature, signature.length);
        tamperedSign[0] ^= 0x01;

        boolean verified = rsaProvider.verify(DATA, tamperedSign, kp.getPublic().getEncoded());
        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("verify — 不同密钥对验签失败")
    void verify_differentKeyPair_shouldReturnFalse() {
        KeyPair kp1 = rsaProvider.generateKeyPair(2048);
        KeyPair kp2 = rsaProvider.generateKeyPair(2048);

        byte[] signature = rsaProvider.sign(DATA, kp1.getPrivate().getEncoded());
        boolean verified = rsaProvider.verify(DATA, signature, kp2.getPublic().getEncoded());
        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("sign — null入参抛CryptoException")
    void sign_nullArgs_shouldThrow() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] privateKey = kp.getPrivate().getEncoded();

        assertThatThrownBy(() -> rsaProvider.sign(null, privateKey))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> rsaProvider.sign(DATA, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("verify — null入参抛CryptoException")
    void verify_nullArgs_shouldThrow() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] signature = rsaProvider.sign(DATA, kp.getPrivate().getEncoded());
        byte[] publicKey = kp.getPublic().getEncoded();

        assertThatThrownBy(() -> rsaProvider.verify(null, signature, publicKey))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> rsaProvider.verify(DATA, null, publicKey))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> rsaProvider.verify(DATA, signature, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("sign — 损坏的私钥抛CryptoException")
    void sign_invalidPrivateKey_shouldThrow() {
        byte[] badKey = "not-a-valid-private-key".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> rsaProvider.sign(DATA, badKey))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("verify — 损坏的公钥抛CryptoException")
    void verify_invalidPublicKey_shouldThrow() {
        byte[] badKey = "not-a-valid-public-key".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> rsaProvider.verify(DATA, SHORT_DATA, badKey))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 加密/解密 =====

    @Test
    @DisplayName("encrypt/decrypt(2048) — 公钥加密私钥解密还原原文")
    void encryptAndDecrypt_2048_shouldRoundTrip() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] publicKey = kp.getPublic().getEncoded();
        byte[] privateKey = kp.getPrivate().getEncoded();

        // RSA 2048 PKCS1Padding 最多 245 字节
        byte[] plaintext = "short-plaintext-for-rsa".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = rsaProvider.encrypt(plaintext, publicKey);
        assertThat(ciphertext).isNotEmpty();
        assertThat(ciphertext).hasSize(256); // 2048/8 = 256
        assertThat(ciphertext).isNotEqualTo(plaintext);

        byte[] decrypted = rsaProvider.decrypt(ciphertext, privateKey);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("encrypt/decrypt(4096) — 公钥加密私钥解密还原原文")
    void encryptAndDecrypt_4096_shouldRoundTrip() {
        KeyPair kp = rsaProvider.generateKeyPair(4096);
        byte[] publicKey = kp.getPublic().getEncoded();
        byte[] privateKey = kp.getPrivate().getEncoded();

        byte[] plaintext = "4096-bit-rsa-plaintext-test".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = rsaProvider.encrypt(plaintext, publicKey);
        assertThat(ciphertext).hasSize(512); // 4096/8 = 512

        byte[] decrypted = rsaProvider.decrypt(ciphertext, privateKey);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("encrypt — 不同密文（每次加密结果不同，因PKCS1随机padding）")
    void encrypt_shouldProduceDifferentCiphertext() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] publicKey = kp.getPublic().getEncoded();

        byte[] plaintext = "same-plaintext".getBytes(StandardCharsets.UTF_8);

        byte[] ct1 = rsaProvider.encrypt(plaintext, publicKey);
        byte[] ct2 = rsaProvider.encrypt(plaintext, publicKey);
        // PKCS1 v1.5 padding 包含随机字节，每次加密结果不同
        assertThat(ct1).isNotEqualTo(ct2);
    }

    @Test
    @DisplayName("encrypt — null入参抛CryptoException")
    void encrypt_nullArgs_shouldThrow() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] publicKey = kp.getPublic().getEncoded();

        assertThatThrownBy(() -> rsaProvider.encrypt(null, publicKey))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> rsaProvider.encrypt(DATA, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt — null入参抛CryptoException")
    void decrypt_nullArgs_shouldThrow() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] privateKey = kp.getPrivate().getEncoded();

        assertThatThrownBy(() -> rsaProvider.decrypt(null, privateKey))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> rsaProvider.decrypt(DATA, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt — 损坏密文抛CryptoException")
    void decrypt_invalidCiphertext_shouldThrow() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] privateKey = kp.getPrivate().getEncoded();
        byte[] badCiphertext = new byte[]{1, 2, 3, 4, 5};

        assertThatThrownBy(() -> rsaProvider.decrypt(badCiphertext, privateKey))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt — 损坏私钥抛CryptoException")
    void decrypt_invalidPrivateKey_shouldThrow() {
        byte[] badKey = "not-a-valid-private-key".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = new byte[256];

        assertThatThrownBy(() -> rsaProvider.decrypt(ciphertext, badKey))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 密钥解码 =====

    @Test
    @DisplayName("decodePublicKey/decodePrivateKey — 正确解码密钥")
    void decodeKeys_shouldWork() {
        KeyPair kp = rsaProvider.generateKeyPair(2048);
        byte[] pubEncoded = kp.getPublic().getEncoded();
        byte[] privEncoded = kp.getPrivate().getEncoded();

        PublicKey pubKey = rsaProvider.decodePublicKey(pubEncoded);
        PrivateKey privKey = rsaProvider.decodePrivateKey(privEncoded);

        assertThat(pubKey.getAlgorithm()).isEqualTo("RSA");
        assertThat(privKey.getAlgorithm()).isEqualTo("RSA");
        assertThat(pubKey.getEncoded()).isEqualTo(pubEncoded);
        assertThat(privKey.getEncoded()).isEqualTo(privEncoded);
    }

    @Test
    @DisplayName("decodePublicKey — 损坏数据抛CryptoException")
    void decodePublicKey_invalid_shouldThrow() {
        byte[] badKey = "invalid".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> rsaProvider.decodePublicKey(badKey))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decodePrivateKey — 损坏数据抛CryptoException")
    void decodePrivateKey_invalid_shouldThrow() {
        byte[] badKey = "invalid".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> rsaProvider.decodePrivateKey(badKey))
                .isInstanceOf(CryptoException.class);
    }

    // ===== PKCS#1 规范符合性 =====

    @Test
    @DisplayName("PKCS#1 — 签名长度等于密钥字节数")
    void pkcs1_signatureLength_shouldMatchKeySize() {
        // 2048 位
        KeyPair kp2048 = rsaProvider.generateKeyPair(2048);
        byte[] sig2048 = rsaProvider.sign(DATA, kp2048.getPrivate().getEncoded());
        assertThat(sig2048).hasSize(256);

        // 4096 位
        KeyPair kp4096 = rsaProvider.generateKeyPair(4096);
        byte[] sig4096 = rsaProvider.sign(DATA, kp4096.getPrivate().getEncoded());
        assertThat(sig4096).hasSize(512);
    }

    @Test
    @DisplayName("PKCS#1 — 密文长度等于密钥字节数")
    void pkcs1_ciphertextLength_shouldMatchKeySize() {
        byte[] plaintext = "test".getBytes(StandardCharsets.UTF_8);

        KeyPair kp2048 = rsaProvider.generateKeyPair(2048);
        byte[] ct2048 = rsaProvider.encrypt(plaintext, kp2048.getPublic().getEncoded());
        assertThat(ct2048).hasSize(256);

        KeyPair kp4096 = rsaProvider.generateKeyPair(4096);
        byte[] ct4096 = rsaProvider.encrypt(plaintext, kp4096.getPublic().getEncoded());
        assertThat(ct4096).hasSize(512);
    }
}