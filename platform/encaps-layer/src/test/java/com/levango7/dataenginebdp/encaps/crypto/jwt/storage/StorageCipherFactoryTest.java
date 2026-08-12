package com.levango7.dataenginebdp.encaps.crypto.jwt.storage;

import com.levango7.dataenginebdp.encaps.crypto.CryptoConfig;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StorageCipherFactory} 单元测试。
 *
 * <p>覆盖按 Profile 路由、密钥校验、Profile 切换等。</p>
 */
class StorageCipherFactoryTest {

    private byte[] newKey(int len) {
        byte[] k = new byte[len];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    @DisplayName("信创 Profile — 创建 GmStorageCipher")
    void create_xinchangProfile_returnsGmCipher() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        StorageCipherFactory factory = new StorageCipherFactory(config);

        byte[] gmKey = newKey(16);
        byte[] intlKey = newKey(32);
        StorageCipher cipher = factory.create(gmKey, intlKey);

        assertThat(cipher).isInstanceOf(GmStorageCipher.class);
        assertThat(cipher.isGm()).isTrue();
        assertThat(cipher.getAlgorithm()).isEqualTo("SM4-CBC");
    }

    @Test
    @DisplayName("国际 Profile — 创建 IntlStorageCipher")
    void create_internationalProfile_returnsIntlCipher() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("international");
        StorageCipherFactory factory = new StorageCipherFactory(config);

        byte[] gmKey = newKey(16);
        byte[] intlKey = newKey(32);
        StorageCipher cipher = factory.create(gmKey, intlKey);

        assertThat(cipher).isInstanceOf(IntlStorageCipher.class);
        assertThat(cipher.isGm()).isFalse();
        assertThat(cipher.getAlgorithm()).isEqualTo("AES-GCM");
    }

    @Test
    @DisplayName("信创 Profile — 缺少 GM 密钥抛异常")
    void create_xinchangProfile_missingGmKey_throwsException() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        StorageCipherFactory factory = new StorageCipherFactory(config);

        assertThatThrownBy(() -> factory.create(null, newKey(32)))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("国际 Profile — 缺少 Intl 密钥抛异常")
    void create_internationalProfile_missingIntlKey_throwsException() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("international");
        StorageCipherFactory factory = new StorageCipherFactory(config);

        assertThatThrownBy(() -> factory.create(newKey(16), null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("指定 Profile — 显式创建")
    void create_specifiedProfile_returnsCorrectCipher() {
        CryptoConfig config = new CryptoConfig();
        StorageCipherFactory factory = new StorageCipherFactory(config);

        StorageCipher gmCipher = factory.create(CryptoProfile.XINCHANG, newKey(16), null);
        assertThat(gmCipher).isInstanceOf(GmStorageCipher.class);

        StorageCipher intlCipher = factory.create(CryptoProfile.INTERNATIONAL, null, newKey(32));
        assertThat(intlCipher).isInstanceOf(IntlStorageCipher.class);
    }

    @Test
    @DisplayName("单密钥创建 — 按当前 Profile")
    void createSingleKey_currentProfile() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        StorageCipherFactory factory = new StorageCipherFactory(config);

        StorageCipher cipher = factory.create(newKey(16));
        assertThat(cipher).isInstanceOf(GmStorageCipher.class);
    }

    @Test
    @DisplayName("单密钥创建 — null 抛异常")
    void createSingleKey_nullKey_throwsException() {
        CryptoConfig config = new CryptoConfig();
        StorageCipherFactory factory = new StorageCipherFactory(config);
        assertThatThrownBy(() -> factory.create(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("运行时切换 Profile")
    void switchProfile_shouldChangeCipherType() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        StorageCipherFactory factory = new StorageCipherFactory(config);

        assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);

        factory.switchProfile("international");
        assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
    }

    @Test
    @DisplayName("构造 — null config 抛异常")
    void constructor_nullConfig_throwsException() {
        assertThatThrownBy(() -> new StorageCipherFactory(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("加密往返 — 通过工厂创建的加密器")
    void encryptDecrypt_viaFactoryCipher() {
        CryptoConfig config = new CryptoConfig();
        config.setActiveProfile("xinchang");
        StorageCipherFactory factory = new StorageCipherFactory(config);

        StorageCipher cipher = factory.create(newKey(16));
        String plain = "test-data";
        String encrypted = cipher.encryptString(plain);
        assertThat(cipher.decryptString(encrypted)).isEqualTo(plain);
    }
}
