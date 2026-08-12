package com.levango7.dataenginebdp.encaps.crypto.jwt.profile;

import com.levango7.dataenginebdp.encaps.crypto.CryptoConfig;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM2Provider;
import com.levango7.dataenginebdp.encaps.crypto.jwt.storage.GmStorageCipher;
import com.levango7.dataenginebdp.encaps.crypto.jwt.storage.IntlStorageCipher;
import com.levango7.dataenginebdp.encaps.crypto.jwt.storage.StorageCipher;
import com.levango7.dataenginebdp.encaps.crypto.jwt.transport.GmTransportCipher;
import com.levango7.dataenginebdp.encaps.crypto.jwt.transport.TransportCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link XinchangProfileAdapter} 单元测试。
 *
 * <p>覆盖 Profile 适配、能力描述、自检、加密器创建等。</p>
 */
class XinchangProfileAdapterTest {

    private byte[] newKey(int len) {
        byte[] k = new byte[len];
        new SecureRandom().nextBytes(k);
        return k;
    }

    // ===== Profile 判断 =====

    @Test
    @DisplayName("信创 Profile — isXinchang=true")
    void xinchangProfile_isXinchangTrue() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        assertThat(adapter.isXinchang()).isTrue();
        assertThat(adapter.isInternational()).isFalse();
        assertThat(adapter.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
        assertThat(adapter.getCurrentProfileName()).isEqualTo("xinchang");
    }

    @Test
    @DisplayName("国际 Profile — isInternational=true")
    void internationalProfile_isInternationalTrue() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("international");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        assertThat(adapter.isInternational()).isTrue();
        assertThat(adapter.isXinchang()).isFalse();
        assertThat(adapter.getCurrentProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
    }

    // ===== 能力描述 =====

    @Test
    @DisplayName("信创 Profile — 能力描述")
    void xinchangProfile_capabilities() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        Map<String, String> caps = adapter.getCapabilities();
        assertThat(caps.get("jwt")).isEqualTo("SM3withSM2");
        assertThat(caps.get("storage")).isEqualTo("SM4-CBC");
        assertThat(caps.get("transport")).isEqualTo("SM2+SM4-CBC");
        assertThat(caps.get("digest")).isEqualTo("SM3");
    }

    @Test
    @DisplayName("国际 Profile — 能力描述")
    void internationalProfile_capabilities() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("international");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        Map<String, String> caps = adapter.getCapabilities();
        assertThat(caps.get("jwt")).isEqualTo("RS256");
        assertThat(caps.get("storage")).isEqualTo("AES-GCM");
        assertThat(caps.get("transport")).isEqualTo("TLS");
    }

    // ===== 支持判断 =====

    @Test
    @DisplayName("信创 Profile — 支持 JWT 与传输加密")
    void xinchangProfile_supportsJwtAndTransport() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        assertThat(adapter.isGmJwtSupported()).isTrue();
        assertThat(adapter.isTransportEncryptionSupported()).isTrue();
    }

    @Test
    @DisplayName("国际 Profile — 不支持 JWT 与传输加密")
    void internationalProfile_doesNotSupportJwtAndTransport() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("international");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        assertThat(adapter.isGmJwtSupported()).isFalse();
        assertThat(adapter.isTransportEncryptionSupported()).isFalse();
    }

    // ===== 加密器创建 =====

    @Test
    @DisplayName("信创 Profile — 创建 GmStorageCipher")
    void xinchangProfile_createGmStorageCipher() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        StorageCipher cipher = adapter.createStorageCipher(newKey(16), newKey(32));
        assertThat(cipher).isInstanceOf(GmStorageCipher.class);
    }

    @Test
    @DisplayName("国际 Profile — 创建 IntlStorageCipher")
    void internationalProfile_createIntlStorageCipher() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("international");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        StorageCipher cipher = adapter.createStorageCipher(newKey(16), newKey(32));
        assertThat(cipher).isInstanceOf(IntlStorageCipher.class);
    }

    @Test
    @DisplayName("信创 Profile — 创建 GmTransportCipher")
    void xinchangProfile_createGmTransportCipher() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        SM2Provider sm2 = new SM2Provider();
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        TransportCipher cipher = adapter.createTransportCipher(kp.getPublicKeyQ(), kp.getPrivateKeyD());
        assertThat(cipher).isInstanceOf(GmTransportCipher.class);
    }

    @Test
    @DisplayName("国际 Profile — 创建传输加密器抛异常")
    void internationalProfile_createTransportCipher_throwsException() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("international");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        SM2Provider sm2 = new SM2Provider();
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        assertThatThrownBy(() -> adapter.createTransportCipher(kp.getPublicKeyQ(), kp.getPrivateKeyD()))
                .isInstanceOf(CryptoException.class);
    }

    // ===== Profile 切换 =====

    @Test
    @DisplayName("运行时切换 Profile")
    void switchProfile_shouldChange() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        assertThat(adapter.isXinchang()).isTrue();
        adapter.switchProfile("international");
        assertThat(adapter.isInternational()).isTrue();
    }

    // ===== 自检 =====

    @Test
    @DisplayName("selfCheck — 信创 Profile")
    void selfCheck_xinchangProfile() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        Map<String, String> result = adapter.selfCheck();
        assertThat(result.get("profile")).isEqualTo("xinchang");
        assertThat(result.get("jwtSupported")).isEqualTo("true");
        assertThat(result.get("transportSupported")).isEqualTo("true");
        assertThat(result).containsKey("providersStatus");
        assertThat(result).containsKey("capabilities");
    }

    @Test
    @DisplayName("selfCheck — 国际 Profile")
    void selfCheck_internationalProfile() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("international");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        Map<String, String> result = adapter.selfCheck();
        assertThat(result.get("profile")).isEqualTo("international");
        assertThat(result.get("jwtSupported")).isEqualTo("false");
        assertThat(result.get("transportSupported")).isEqualTo("false");
    }

    // ===== 构造异常 =====

    @Test
    @DisplayName("构造 — null config 抛异常")
    void constructor_nullConfig_throwsException() {
        assertThatThrownBy(() -> new XinchangProfileAdapter(null))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 单密钥创建 =====

    @Test
    @DisplayName("单密钥创建 — 信创 Profile")
    void createStorageCipher_singleKey_xinchang() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);

        StorageCipher cipher = adapter.createStorageCipher(newKey(16));
        assertThat(cipher).isInstanceOf(GmStorageCipher.class);
    }
}
