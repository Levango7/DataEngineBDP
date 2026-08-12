package com.levango7.dataenginebdp.encaps.crypto.jwt.transport;

import com.levango7.dataenginebdp.encaps.crypto.CryptoConfig;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM2Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TransportCipherFactory} 单元测试。
 *
 * <p>覆盖按 Profile 路由、密钥校验、Profile 切换、国际辖区不支持等。</p>
 */
class TransportCipherFactoryTest {

    @Test
    @DisplayName("信创 Profile — 创建 GmTransportCipher")
    void create_xinchangProfile_returnsGmCipher() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        TransportCipherFactory factory = new TransportCipherFactory(config);

        SM2Provider sm2 = new SM2Provider();
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        TransportCipher cipher = factory.create(kp.getPublicKeyQ(), kp.getPrivateKeyD());

        assertThat(cipher).isInstanceOf(GmTransportCipher.class);
        assertThat(cipher.isGm()).isTrue();
        assertThat(cipher.getAlgorithm()).isEqualTo("SM2+SM4-CBC");
    }

    @Test
    @DisplayName("国际 Profile — 抛异常（不支持）")
    void create_internationalProfile_throwsException() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("international");
        TransportCipherFactory factory = new TransportCipherFactory(config);

        SM2Provider sm2 = new SM2Provider();
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        assertThatThrownBy(() -> factory.create(kp.getPublicKeyQ(), kp.getPrivateKeyD()))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    @DisplayName("指定 Profile — 显式创建")
    void create_specifiedProfile() {
        CryptoConfig config = new CryptoConfig();
        TransportCipherFactory factory = new TransportCipherFactory(config);

        SM2Provider sm2 = new SM2Provider();
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        TransportCipher cipher = factory.create(CryptoProfile.XINCHANG, kp.getPublicKeyQ(), kp.getPrivateKeyD());
        assertThat(cipher).isInstanceOf(GmTransportCipher.class);
    }

    @Test
    @DisplayName("仅加密模式")
    void createForEncrypt_onlyPublicKey() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        TransportCipherFactory factory = new TransportCipherFactory(config);

        SM2Provider sm2 = new SM2Provider();
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        TransportCipher cipher = factory.createForEncrypt(kp.getPublicKeyQ());
        assertThat(cipher).isInstanceOf(GmTransportCipher.class);
    }

    @Test
    @DisplayName("仅解密模式")
    void createForDecrypt_onlyPrivateKey() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        TransportCipherFactory factory = new TransportCipherFactory(config);

        SM2Provider sm2 = new SM2Provider();
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        TransportCipher cipher = factory.createForDecrypt(kp.getPrivateKeyD());
        assertThat(cipher).isInstanceOf(GmTransportCipher.class);
    }

    @Test
    @DisplayName("createWithNewKeyPair — 信创 Profile")
    void createWithNewKeyPair_xinchangProfile() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        TransportCipherFactory factory = new TransportCipherFactory(config);

        TransportCipher cipher = factory.createWithNewKeyPair();
        assertThat(cipher).isInstanceOf(GmTransportCipher.class);

        // 验证可加解密
        String plain = "test";
        String envelope = cipher.encryptString(plain);
        assertThat(cipher.decryptString(envelope)).isEqualTo(plain);
    }

    @Test
    @DisplayName("createWithNewKeyPair — 国际 Profile 抛异常")
    void createWithNewKeyPair_internationalProfile_throwsException() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("international");
        TransportCipherFactory factory = new TransportCipherFactory(config);
        assertThatThrownBy(() -> factory.createWithNewKeyPair())
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("运行时切换 Profile")
    void switchProfile_shouldChange() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        TransportCipherFactory factory = new TransportCipherFactory(config);

        assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
        factory.switchProfile("international");
        assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
    }

    @Test
    @DisplayName("构造 — null config 抛异常")
    void constructor_nullConfig_throwsException() {
        assertThatThrownBy(() -> new TransportCipherFactory(null))
                .isInstanceOf(CryptoException.class);
    }
}