package com.shuqing.bigdata.encaps.crypto.jwt.profile;

import com.shuqing.bigdata.encaps.crypto.CryptoConfig;
import com.shuqing.bigdata.encaps.crypto.CryptoException;
import com.shuqing.bigdata.encaps.crypto.CryptoProfile;
import com.shuqing.bigdata.encaps.crypto.gm.SM2Provider;
import com.shuqing.bigdata.encaps.crypto.jwt.GmJwtProcessor;
import com.shuqing.bigdata.encaps.crypto.jwt.storage.StorageCipher;
import com.shuqing.bigdata.encaps.crypto.jwt.transport.TransportCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CryptoProfileRouter} 单元测试。
 *
 * <p>覆盖统一路由器入口、JWT/存储/传输加密器获取、Profile 切换等。</p>
 */
class CryptoProfileRouterTest {

    private byte[] newKey(int len) {
        byte[] k = new byte[len];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    @DisplayName("信创 Profile — 获取 GmJwtProcessor")
    void xinchangProfile_getJwtProcessor() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        CryptoProfileRouter router = new CryptoProfileRouter(config, null, "shuqing");

        GmJwtProcessor processor = router.getJwtProcessor();
        assertThat(processor).isNotNull();
    }

    @Test
    @DisplayName("国际 Profile — 获取 JWT 处理器抛异常")
    void internationalProfile_getJwtProcessor_throwsException() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("international");
        CryptoProfileRouter router = new CryptoProfileRouter(config);

        assertThatThrownBy(() -> router.getJwtProcessor())
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("信创 Profile — 获取存储加密器")
    void getStorageCipher_xinchang() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        CryptoProfileRouter router = new CryptoProfileRouter(config);

        StorageCipher cipher = router.getStorageCipher(newKey(16), newKey(32));
        assertThat(cipher.isGm()).isTrue();
    }

    @Test
    @DisplayName("国际 Profile — 获取存储加密器")
    void getStorageCipher_international() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("international");
        CryptoProfileRouter router = new CryptoProfileRouter(config);

        StorageCipher cipher = router.getStorageCipher(newKey(16), newKey(32));
        assertThat(cipher.isGm()).isFalse();
    }

    @Test
    @DisplayName("信创 Profile — 获取传输加密器")
    void getTransportCipher_xinchang() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        CryptoProfileRouter router = new CryptoProfileRouter(config);

        SM2Provider sm2 = new SM2Provider();
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        TransportCipher cipher = router.getTransportCipher(kp.getPublicKeyQ(), kp.getPrivateKeyD());
        assertThat(cipher.isGm()).isTrue();
    }

    @Test
    @DisplayName("getCurrentProfile — 返回当前 Profile")
    void getCurrentProfile() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        CryptoProfileRouter router = new CryptoProfileRouter(config);
        assertThat(router.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
    }

    @Test
    @DisplayName("运行时切换 Profile")
    void switchProfile() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        CryptoProfileRouter router = new CryptoProfileRouter(config);

        router.switchProfile("international");
        assertThat(router.getCurrentProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
    }

    @Test
    @DisplayName("getCapabilities — 返回能力描述")
    void getCapabilities() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        CryptoProfileRouter router = new CryptoProfileRouter(config);

        Map<String, String> caps = router.getCapabilities();
        assertThat(caps.get("jwt")).isEqualTo("SM3withSM2");
        assertThat(caps.get("storage")).isEqualTo("SM4-CBC");
    }

    @Test
    @DisplayName("selfCheck — 返回自检结果")
    void selfCheck() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        CryptoProfileRouter router = new CryptoProfileRouter(config);

        Map<String, String> result = router.selfCheck();
        assertThat(result.get("profile")).isEqualTo("xinchang");
    }

    @Test
    @DisplayName("getAdapter — 返回内部适配器")
    void getAdapter() {
        CryptoConfig config = new CryptoConfig();
        CryptoProfileRouter router = new CryptoProfileRouter(config);
        assertThat(router.getAdapter()).isNotNull();
    }

    @Test
    @DisplayName("getSpiFactory — 返回内部 SPI 工厂")
    void getSpiFactory() {
        CryptoConfig config = new CryptoConfig();
        CryptoProfileRouter router = new CryptoProfileRouter(config);
        assertThat(router.getSpiFactory()).isNotNull();
    }
}