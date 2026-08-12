package com.levango7.dataenginebdp.encaps.security.facade.crypto;

import com.levango7.dataenginebdp.encaps.crypto.CryptoConfig;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import com.levango7.dataenginebdp.encaps.crypto.CryptoSpiFactory;
import com.levango7.dataenginebdp.encaps.security.facade.config.SecurityFacadeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CryptoFacade} 单元测试。
 *
 * <p>验证 CryptoFacade 正确委托 CryptoSpiFactory，加解密/签名/验签/摘要闭环。</p>
 *
 * <p>依赖 classpath 上通过 SPI 注册的 GM-Provider 与 INTL-Provider。</p>
 */
class CryptoFacadeTest {

    private CryptoSpiFactory spiFactory;
    private SecurityFacadeConfig config;
    private CryptoFacade cryptoFacade;

    @BeforeEach
    void setUp() {
        CryptoConfig cryptoConfig = new CryptoConfig();
        spiFactory = new CryptoSpiFactory(cryptoConfig);
        config = new SecurityFacadeConfig();
        cryptoFacade = new CryptoFacade(spiFactory, config);
    }

    @Test
    @DisplayName("currentProfile — 默认回退 XINCHANG")
    void currentProfile_default_shouldBeXinchang() {
        assertThat(cryptoFacade.currentProfile()).isEqualTo(CryptoProfile.XINCHANG);
    }

    @Test
    @DisplayName("currentProviderName — 默认 GM-Provider")
    void currentProviderName_default_shouldBeGm() {
        assertThat(cryptoFacade.currentProviderName()).isEqualTo("GM-Provider");
    }

    @Test
    @DisplayName("availableProviderNames — 包含 GM-Provider 与 INTL-Provider")
    void availableProviderNames_shouldContainBoth() {
        assertThat(cryptoFacade.availableProviderNames())
                .containsExactlyInAnyOrder("GM-Provider", "INTL-Provider");
    }

    @Test
    @DisplayName("digest — 相同输入产生相同摘要")
    void digest_sameInput_shouldProduceSameOutput() {
        byte[] data = "test data".getBytes();

        String hash1 = cryptoFacade.digest(data);
        String hash2 = cryptoFacade.digest(data);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isNotBlank();
        // Base64 解码应成功
        assertThat(Base64.getDecoder().decode(hash1)).isNotEmpty();
    }

    @Test
    @DisplayName("digest — 不同输入产生不同摘要")
    void digest_differentInput_shouldProduceDifferentOutput() {
        String hash1 = cryptoFacade.digest("data1".getBytes());
        String hash2 = cryptoFacade.digest("data2".getBytes());

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("sign + verify — 签名验签闭环")
    void signAndVerify_shouldRoundTrip() {
        byte[] data = "important data".getBytes();
        KeyPair keyPair = cryptoFacade.getKeyPair();

        String signature = cryptoFacade.sign(data, keyPair.getPrivate());
        boolean verified = cryptoFacade.verify(data, signature, keyPair.getPublic());

        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("verify — 篡改数据后验签失败")
    void verify_tamperedData_shouldFail() {
        byte[] data = "original".getBytes();
        KeyPair keyPair = cryptoFacade.getKeyPair();

        String signature = cryptoFacade.sign(data, keyPair.getPrivate());
        boolean verified = cryptoFacade.verify("tampered".getBytes(), signature, keyPair.getPublic());

        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("encrypt + decrypt — 加解密闭环")
    void encryptAndDecrypt_shouldRoundTrip() {
        // 使用对称密钥（SM4/AES）
        KeyPair keyPair = cryptoFacade.getKeyPair();
        byte[] plaintext = "secret message".getBytes();

        // 非对称加密（SM2/RSA）
        String cipher = cryptoFacade.encrypt(plaintext, keyPair.getPublic());
        byte[] decrypted = cryptoFacade.decrypt(cipher, keyPair.getPrivate());

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("禁用后调用抛 CryptoException")
    void disabled_shouldThrow() {
        config.setEnabled(false);

        assertThatThrownBy(() -> cryptoFacade.digest("test".getBytes()))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("仅加解密禁用 — 抛 CryptoException")
    void cryptoDisabled_shouldThrow() {
        config.getCrypto().setEnabled(false);

        assertThatThrownBy(() -> cryptoFacade.digest("test".getBytes()))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("defaultProfile=international — 使用 INTL-Provider")
    void defaultProfileInternational_shouldUseIntlProvider() {
        config.getCrypto().setDefaultProfile("international");
        // 需要重新创建 spiFactory 以使 profile 生效
        CryptoConfig cryptoConfig = new CryptoConfig();
        cryptoConfig.setActiveProfile("international");
        spiFactory = new CryptoSpiFactory(cryptoConfig);
        cryptoFacade = new CryptoFacade(spiFactory, config);

        assertThat(cryptoFacade.currentProviderName()).isEqualTo("INTL-Provider");
        assertThat(cryptoFacade.currentProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
    }
}