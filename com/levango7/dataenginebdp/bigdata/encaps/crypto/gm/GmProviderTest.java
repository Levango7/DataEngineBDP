package com.shuqing.bigdata.encaps.crypto.gm;

import com.shuqing.bigdata.encaps.crypto.AlgorithmType;
import com.shuqing.bigdata.encaps.crypto.CryptoConfig;
import com.shuqing.bigdata.encaps.crypto.CryptoException;
import com.shuqing.bigdata.encaps.crypto.CryptoProfile;
import com.shuqing.bigdata.encaps.crypto.CryptoProvider;
import com.shuqing.bigdata.encaps.crypto.CryptoSpiFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GmProvider} 国密 Provider 聚合测试。
 *
 * <p>验证：</p>
 * <ul>
 *   <li>通过 {@link CryptoSpiFactory} 按 Profile=xinchang 获取 GmProvider（SPI 集成）</li>
 *   <li>算法路由正确性（SM2→SM2Provider, SM3→SM3Provider, SM4→SM4Provider）</li>
 *   <li>Provider 元信息（name/algorithm/profile）</li>
 *   <li>聚合委托行为（digest/sign/encrypt/decrypt）</li>
 * </ul>
 */
class GmProviderTest {

    private GmProvider gmProvider;

    @BeforeEach
    void setUp() {
        gmProvider = new GmProvider();
    }

    // ===== 元信息 =====

    @Test
    @DisplayName("元信息 — providerName=GM-Provider, profile=XINCHANG, algorithm=SIGN")
    void metadata_shouldMatchXinchangGm() {
        assertThat(gmProvider.getProviderName()).isEqualTo("GM-Provider");
        assertThat(gmProvider.getSupportedProfile()).isEqualTo(CryptoProfile.XINCHANG);
        assertThat(gmProvider.getAlgorithmType()).isEqualTo(AlgorithmType.SIGN);
    }

    @Test
    @DisplayName("子 Provider — getSm2/getSm3/getSm4 返回非 null 实例")
    void subProviders_shouldBeInitialized() {
        assertThat(gmProvider.getSm2()).isNotNull().isInstanceOf(SM2Provider.class);
        assertThat(gmProvider.getSm3()).isNotNull().isInstanceOf(SM3Provider.class);
        assertThat(gmProvider.getSm4()).isNotNull().isInstanceOf(SM4Provider.class);
    }

    // ===== SPI 集成（通过 CryptoSpiFactory 加载） =====

    @Test
    @DisplayName("SPI — CryptoSpiFactory 按 Profile=xinchang 加载 GmProvider")
    void spi_loadByXinchangProfile_shouldReturnGmProvider() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider provider = factory.getProvider();
        assertThat(provider.getProviderName()).isEqualTo("GM-Provider");
        assertThat(provider).isInstanceOf(GmProvider.class);
        assertThat(provider.getSupportedProfile()).isEqualTo(CryptoProfile.XINCHANG);
    }

    @Test
    @DisplayName("SPI — getProviderByName(\"GM-Provider\") 返回 GmProvider")
    void spi_getByName_shouldReturnGmProvider() {
        CryptoConfig config = new CryptoConfig();
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider provider = factory.getProviderByName("GM-Provider");
        assertThat(provider).isInstanceOf(GmProvider.class);
    }

    @Test
    @DisplayName("SPI — getAvailableProviders(xinchang) 包含 GmProvider")
    void spi_availableProviders_xinchang_shouldContainGm() {
        CryptoConfig config = new CryptoConfig();
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        var providers = factory.getAvailableProviders("xinchang");
        assertThat(providers).anyMatch(p -> p instanceof GmProvider);
    }

    // ===== 算法路由：digest → SM3Provider =====

    @Test
    @DisplayName("路由 — digest 委托给 SM3Provider，输出 32 字节")
    void route_digest_shouldDelegateToSm3() {
        byte[] data = "route-digest-test".getBytes(StandardCharsets.UTF_8);
        byte[] viaGm = gmProvider.digest(data);
        byte[] viaSm3 = gmProvider.getSm3().digest(data);

        assertThat(viaGm).hasSize(32);
        assertThat(viaGm).isEqualTo(viaSm3);
    }

    // ===== 算法路由：sign/verify → SM2Provider =====

    @Test
    @DisplayName("路由 — sign/verifySign 委托给 SM2Provider")
    void route_signVerify_shouldDelegateToSm2() {
        SM2Provider.Sm2KeyPair kp = gmProvider.getSm2().generateKeyPair();
        GmProvider.Sm2PrivateKey priv = new GmProvider.Sm2PrivateKey(kp.getPrivateKeyD());
        GmProvider.Sm2PublicKey pub = new GmProvider.Sm2PublicKey(kp.getPublicKeyQ());
        byte[] data = "route-sign-test".getBytes(StandardCharsets.UTF_8);

        byte[] sig = gmProvider.sign(data, priv);
        boolean verified = gmProvider.verifySign(data, sig, pub);

        assertThat(verified).isTrue();
        // 与直接调用 SM2Provider 一致
        boolean directVerified = gmProvider.getSm2().verify(data, sig, kp.getPublicKeyQ());
        assertThat(directVerified).isTrue();
    }

    // ===== 算法路由：encrypt/decrypt → SM2（默认）/SM4 =====

    @Test
    @DisplayName("路由 — encrypt/decrypt 默认走 SM2 非对称加密")
    void route_encryptDecrypt_defaultSm2() {
        SM2Provider.Sm2KeyPair kp = gmProvider.getSm2().generateKeyPair();
        GmProvider.Sm2PublicKey pub = new GmProvider.Sm2PublicKey(kp.getPublicKeyQ());
        GmProvider.Sm2PrivateKey priv = new GmProvider.Sm2PrivateKey(kp.getPrivateKeyD());
        byte[] plain = "route-sm2-encrypt-test".getBytes(StandardCharsets.UTF_8);

        byte[] cipher = gmProvider.encrypt(plain, pub);
        byte[] recovered = gmProvider.decrypt(cipher, priv);

        assertThat(recovered).isEqualTo(plain);
    }

    @Test
    @DisplayName("路由 — encrypt/decrypt 走 SM4 对称加密（Sm4SecretKey）")
    void route_encryptDecrypt_sm4() {
        byte[] key = gmProvider.getSm4().generateKey();
        GmProvider.Sm4SecretKey sm4Key = new GmProvider.Sm4SecretKey(key, "ECB", null);
        byte[] plain = "route-sm4-encrypt-test".getBytes(StandardCharsets.UTF_8);

        byte[] cipher = gmProvider.encrypt(plain, sm4Key);
        byte[] recovered = gmProvider.decrypt(cipher, sm4Key);

        assertThat(recovered).isEqualTo(plain);
    }

    @Test
    @DisplayName("路由 — SM4 CBC 模式通过 GmProvider")
    void route_encryptDecrypt_sm4Cbc() {
        byte[] key = gmProvider.getSm4().generateKey();
        byte[] iv = gmProvider.getSm4().generateIv();
        GmProvider.Sm4SecretKey sm4Key = new GmProvider.Sm4SecretKey(key, "CBC", iv);
        byte[] plain = "route-sm4-cbc-test-data".getBytes(StandardCharsets.UTF_8);

        byte[] cipher = gmProvider.encrypt(plain, sm4Key);
        byte[] recovered = gmProvider.decrypt(cipher, sm4Key);

        assertThat(recovered).isEqualTo(plain);
    }

    // ===== KeyPair 生成 =====

    @Test
    @DisplayName("getKeyPair — 返回 JCA KeyPair（SM2 椭圆曲线）")
    void getKeyPair_shouldReturnJcaSm2KeyPair() {
        var kp = gmProvider.getKeyPair();
        assertThat(kp).isNotNull();
        assertThat(kp.getPublic().getAlgorithm()).isEqualTo("EC");
    }

    // ===== 密钥包装类 =====

    @Test
    @DisplayName("Sm2PrivateKey — getAlgorithm=SM2, getEncoded 返回副本")
    void sm2PrivateKey_wrapper() {
        byte[] d = new byte[32];
        d[0] = 0x01;
        GmProvider.Sm2PrivateKey key = new GmProvider.Sm2PrivateKey(d);

        assertThat(key.getAlgorithm()).isEqualTo("SM2");
        assertThat(key.getFormat()).isEqualTo("RAW");
        assertThat(key.getEncoded()).isEqualTo(d);
        // 修改原数组不影响 key（防御性拷贝）
        d[0] = 0x02;
        assertThat(key.getEncoded()[0]).isEqualTo((byte) 0x01);
    }

    @Test
    @DisplayName("Sm2PublicKey — getAlgorithm=SM2, getEncoded 返回副本")
    void sm2PublicKey_wrapper() {
        byte[] q = new byte[65];
        q[0] = 0x04;
        GmProvider.Sm2PublicKey key = new GmProvider.Sm2PublicKey(q);

        assertThat(key.getAlgorithm()).isEqualTo("SM2");
        assertThat(key.getEncoded()[0]).isEqualTo((byte) 0x04);
    }

    @Test
    @DisplayName("Sm4SecretKey — getAlgorithm=SM4, 默认 ECB 模式")
    void sm4SecretKey_wrapper() {
        byte[] keyBytes = new byte[16];
        keyBytes[0] = 0x01;
        GmProvider.Sm4SecretKey key = new GmProvider.Sm4SecretKey(keyBytes);

        assertThat(key.getAlgorithm()).isEqualTo("SM4");
        assertThat(key.getMode()).isEqualTo("ECB");
        assertThat(key.getIv()).isNull();
        assertThat(key.getKeyBytes()).isEqualTo(keyBytes);
    }

    @Test
    @DisplayName("Sm4SecretKey — CBC 模式带 IV")
    void sm4SecretKey_cbcWithIv() {
        byte[] keyBytes = new byte[16];
        byte[] iv = new byte[16];
        GmProvider.Sm4SecretKey key = new GmProvider.Sm4SecretKey(keyBytes, "cbc", iv);

        assertThat(key.getMode()).isEqualTo("CBC");
        assertThat(key.getIv()).isEqualTo(iv);
    }

    @Test
    @DisplayName("Sm4SecretKey — 非法密钥长度抛 CryptoException")
    void sm4SecretKey_invalidKeyLen_shouldThrow() {
        assertThatThrownBy(() -> new GmProvider.Sm4SecretKey(new byte[15]))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> new GmProvider.Sm4SecretKey(null))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 异常处理 =====

    @Test
    @DisplayName("sign(null) — 抛 CryptoException")
    void sign_null_shouldThrow() {
        SM2Provider.Sm2KeyPair kp = gmProvider.getSm2().generateKeyPair();
        GmProvider.Sm2PrivateKey priv = new GmProvider.Sm2PrivateKey(kp.getPrivateKeyD());
        assertThatThrownBy(() -> gmProvider.sign(null, priv))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("digest(null) — 抛 CryptoException")
    void digest_null_shouldThrow() {
        assertThatThrownBy(() -> gmProvider.digest(null))
                .isInstanceOf(CryptoException.class);
    }

    // ===== JCA 密钥路由分支 =====

    @Test
    @DisplayName("路由 — sign/verifySign 使用 JCA PrivateKey/PublicKey（非 Sm2PrivateKey/Sm2PublicKey）")
    void route_signVerify_jcaKeys_shouldDelegateToSm2() {
        // 生成 JCA 密钥对，通过 extractSm2D/extractSm2Q 的 instanceof PrivateKey/PublicKey 分支
        var jcaKp = gmProvider.getSm2().generateJcaKeyPair();
        byte[] data = "jca-key-route-test".getBytes(StandardCharsets.UTF_8);

        byte[] sig = gmProvider.sign(data, jcaKp.getPrivate());
        boolean verified = gmProvider.verifySign(data, sig, jcaKp.getPublic());
        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("路由 — encrypt/decrypt 使用 JCA PublicKey/PrivateKey（SM2 非对称）")
    void route_encryptDecrypt_jcaKeys_shouldDelegateToSm2() {
        var jcaKp = gmProvider.getSm2().generateJcaKeyPair();
        byte[] plain = "jca-encrypt-route-test".getBytes(StandardCharsets.UTF_8);

        byte[] cipher = gmProvider.encrypt(plain, jcaKp.getPublic());
        byte[] recovered = gmProvider.decrypt(cipher, jcaKp.getPrivate());
        assertThat(recovered).isEqualTo(plain);
    }

    // ===== 异常处理：null 入参 =====

    @Test
    @DisplayName("sign — null key 抛 CryptoException")
    void sign_nullKey_shouldThrow() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> gmProvider.sign(data, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("verifySign — null 入参抛 CryptoException")
    void verifySign_null_shouldThrow() {
        SM2Provider.Sm2KeyPair kp = gmProvider.getSm2().generateKeyPair();
        GmProvider.Sm2PrivateKey priv = new GmProvider.Sm2PrivateKey(kp.getPrivateKeyD());
        GmProvider.Sm2PublicKey pub = new GmProvider.Sm2PublicKey(kp.getPublicKeyQ());
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        byte[] sig = gmProvider.sign(data, priv);

        assertThatThrownBy(() -> gmProvider.verifySign(null, sig, pub))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> gmProvider.verifySign(data, null, pub))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> gmProvider.verifySign(data, sig, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("encrypt — null 入参抛 CryptoException")
    void encrypt_null_shouldThrow() {
        SM2Provider.Sm2KeyPair kp = gmProvider.getSm2().generateKeyPair();
        GmProvider.Sm2PublicKey pub = new GmProvider.Sm2PublicKey(kp.getPublicKeyQ());
        assertThatThrownBy(() -> gmProvider.encrypt(null, pub))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> gmProvider.encrypt("x".getBytes(StandardCharsets.UTF_8), null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("decrypt — null 入参抛 CryptoException")
    void decrypt_null_shouldThrow() {
        SM2Provider.Sm2KeyPair kp = gmProvider.getSm2().generateKeyPair();
        GmProvider.Sm2PrivateKey priv = new GmProvider.Sm2PrivateKey(kp.getPrivateKeyD());
        assertThatThrownBy(() -> gmProvider.decrypt(null, priv))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> gmProvider.decrypt(new byte[16], null))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 密钥包装类边界分支 =====

    @Test
    @DisplayName("Sm2PrivateKey(null) — 抛 CryptoException")
    void sm2PrivateKey_null_shouldThrow() {
        assertThatThrownBy(() -> new GmProvider.Sm2PrivateKey(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("Sm2PublicKey(null) — 抛 CryptoException")
    void sm2PublicKey_null_shouldThrow() {
        assertThatThrownBy(() -> new GmProvider.Sm2PublicKey(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("Sm2PrivateKey — getD 返回副本（防御性拷贝）")
    void sm2PrivateKey_getD_shouldReturnCopy() {
        byte[] d = new byte[32];
        d[0] = 0x01;
        GmProvider.Sm2PrivateKey key = new GmProvider.Sm2PrivateKey(d);
        byte[] d1 = key.getD();
        d1[0] = 0x02;
        // 修改返回的数组不影响内部状态
        assertThat(key.getD()[0]).isEqualTo((byte) 0x01);
    }

    @Test
    @DisplayName("Sm2PublicKey — getQ 返回副本（防御性拷贝）")
    void sm2PublicKey_getQ_shouldReturnCopy() {
        byte[] q = new byte[65];
        q[0] = 0x04;
        GmProvider.Sm2PublicKey key = new GmProvider.Sm2PublicKey(q);
        byte[] q1 = key.getQ();
        q1[0] = 0x03;
        assertThat(key.getQ()[0]).isEqualTo((byte) 0x04);
    }

    @Test
    @DisplayName("Sm2PublicKey — getEncoded 返回副本")
    void sm2PublicKey_getEncoded_shouldReturnCopy() {
        byte[] q = new byte[65];
        q[0] = 0x04;
        GmProvider.Sm2PublicKey key = new GmProvider.Sm2PublicKey(q);
        byte[] enc1 = key.getEncoded();
        enc1[0] = 0x03;
        assertThat(key.getEncoded()[0]).isEqualTo((byte) 0x04);
    }

    @Test
    @DisplayName("Sm4SecretKey — getKeyBytes 返回副本（防御性拷贝）")
    void sm4SecretKey_getKeyBytes_shouldReturnCopy() {
        byte[] keyBytes = new byte[16];
        keyBytes[0] = 0x01;
        GmProvider.Sm4SecretKey key = new GmProvider.Sm4SecretKey(keyBytes);
        byte[] k1 = key.getKeyBytes();
        k1[0] = 0x02;
        assertThat(key.getKeyBytes()[0]).isEqualTo((byte) 0x01);
    }

    @Test
    @DisplayName("Sm4SecretKey — getEncoded 返回副本")
    void sm4SecretKey_getEncoded_shouldReturnCopy() {
        byte[] keyBytes = new byte[16];
        keyBytes[0] = 0x01;
        GmProvider.Sm4SecretKey key = new GmProvider.Sm4SecretKey(keyBytes);
        byte[] enc1 = key.getEncoded();
        enc1[0] = 0x02;
        assertThat(key.getEncoded()[0]).isEqualTo((byte) 0x01);
    }

    @Test
    @DisplayName("Sm4SecretKey — getIv 返回副本（CBC 模式）")
    void sm4SecretKey_getIv_shouldReturnCopy() {
        byte[] keyBytes = new byte[16];
        byte[] iv = new byte[16];
        iv[0] = 0x01;
        GmProvider.Sm4SecretKey key = new GmProvider.Sm4SecretKey(keyBytes, "CBC", iv);
        byte[] iv1 = key.getIv();
        iv1[0] = 0x02;
        assertThat(key.getIv()[0]).isEqualTo((byte) 0x01);
    }

    @Test
    @DisplayName("Sm4SecretKey — 非法密钥长度（CBC 构造器）抛 CryptoException")
    void sm4SecretKey_cbcInvalidKeyLen_shouldThrow() {
        assertThatThrownBy(() -> new GmProvider.Sm4SecretKey(new byte[15], "CBC", null))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> new GmProvider.Sm4SecretKey(null, "CBC", null))
                .isInstanceOf(CryptoException.class);
    }
}