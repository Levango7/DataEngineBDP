package com.shuqing.bigdata.encaps.crypto;

import com.shuqing.bigdata.encaps.crypto.gm.GmProvider;
import com.shuqing.bigdata.encaps.crypto.gm.SM2Provider;
import com.shuqing.bigdata.encaps.crypto.gm.SM4Provider;
import com.shuqing.bigdata.encaps.crypto.intl.IntlProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Profile 切换测试（T022-4）。
 *
 * <p>验证 {@link CryptoSpiFactory} 的 Profile 切换机制：</p>
 * <ul>
 *   <li><b>切换后立即生效</b>：{@link CryptoSpiFactory#setCurrentProfile(String)} 后
 *       下一次 {@link CryptoSpiFactory#getProvider()} 即返回新 Profile 的 Provider</li>
 *   <li><b>信创默认国密</b>：未指定任何 Profile 时回退到 {@link CryptoProfile#XINCHANG}</li>
 *   <li><b>切换不影响已生成密钥</b>：Profile 切换不会使已生成的密钥对失效，
 *       原密钥仍可完成签名/验签、加密/解密闭环</li>
 *   <li><b>切换优先级</b>：运行时切换 > CryptoConfig > Spring Environment > 默认</li>
 *   <li><b>切换异常处理</b>：非法 Profile 字符串 fail-closed 抛 {@link CryptoException}</li>
 * </ul>
 */
class ProfileSwitchTest {

    private CryptoConfig config;

    @BeforeEach
    void setUp() {
        config = new CryptoConfig();
    }

    // ==================== 切换后立即生效 ====================

    @Nested
    @DisplayName("切换后立即生效 — setCurrentProfile 后 getProvider 立即返回新 Provider")
    class SwitchImmediatelyEffective {

        @Test
        @DisplayName("切换到 xinchang — 立即返回 GmProvider")
        void switchToXinchang_shouldReturnGmImmediately() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);
            // 初始默认也是 xinchang，先切到 international 再切回验证
            factory.setCurrentProfile("international");
            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);

            factory.setCurrentProfile("xinchang");
            assertThat(factory.getProvider()).isInstanceOf(GmProvider.class);
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
        }

        @Test
        @DisplayName("切换到 international — 立即返回 IntlProvider")
        void switchToInternational_shouldReturnIntlImmediately() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            factory.setCurrentProfile("international");
            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
        }

        @Test
        @DisplayName("多次切换 — 每次都立即生效")
        void multipleSwitches_eachTakesEffectImmediately() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            factory.setCurrentProfile("xinchang");
            assertThat(factory.getProvider()).isInstanceOf(GmProvider.class);

            factory.setCurrentProfile("international");
            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);

            factory.setCurrentProfile("xinchang");
            assertThat(factory.getProvider()).isInstanceOf(GmProvider.class);

            factory.setCurrentProfile("international");
            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);
        }

        @Test
        @DisplayName("切换后 getCurrentProfile 立即反映新值")
        void getCurrentProfile_reflectsSwitchImmediately() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            factory.setCurrentProfile("international");
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);

            factory.setCurrentProfile("xinchang");
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
        }
    }

    // ==================== 信创默认国密 ====================

    @Nested
    @DisplayName("信创默认国密 — 未指定 Profile 时回退 XINCHANG")
    class XinchangDefault {

        @Test
        @DisplayName("无 config.activeProfile、无 Spring Environment → 默认 GmProvider")
        void noProfileAtAll_shouldDefaultToGm() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            CryptoProvider provider = factory.getProvider();

            assertThat(provider).isInstanceOf(GmProvider.class);
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
        }

        @Test
        @DisplayName("config.activeProfile 为空字符串 → 默认 GmProvider")
        void emptyActiveProfile_shouldDefaultToGm() {
            config.setActiveProfile("");
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            CryptoProvider provider = factory.getProvider();

            assertThat(provider).isInstanceOf(GmProvider.class);
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
        }

        @Test
        @DisplayName("Spring Environment 无 crypto profile → 默认 GmProvider")
        void springEnvWithoutCryptoProfile_shouldDefaultToGm() {
            MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", "dev,prod");
            CryptoSpiFactory factory = new CryptoSpiFactory(config, env);

            CryptoProvider provider = factory.getProvider();

            assertThat(provider).isInstanceOf(GmProvider.class);
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
        }

        @Test
        @DisplayName("显式 config.activeProfile=xinchang → GmProvider（与默认一致）")
        void explicitXinchang_shouldReturnGm() {
            config.setActiveProfile("xinchang");
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            assertThat(factory.getProvider()).isInstanceOf(GmProvider.class);
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
        }
    }

    // ==================== 切换不影响已生成密钥 ====================

    @Nested
    @DisplayName("切换不影响已生成密钥 — Profile 切换后原密钥仍可用")
    class SwitchDoesNotAffectExistingKeys {

        @Test
        @DisplayName("SM2 密钥 — 切换 Profile 后原 SM2 签名/验签仍通过")
        void sm2Key_survivesProfileSwitch() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            // 在 xinchang 下生成 SM2 密钥并签名
            factory.setCurrentProfile("xinchang");
            GmProvider gm = (GmProvider) factory.getProvider();
            SM2Provider.Sm2KeyPair kp = gm.getSm2().generateKeyPair();
            byte[] data = "switch-key-test".getBytes(StandardCharsets.UTF_8);
            byte[] signature = gm.getSm2().sign(data, kp.getPrivateKeyD());

            // 切换到 international
            factory.setCurrentProfile("international");
            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);

            // 切回 xinchang，原密钥仍可验签
            factory.setCurrentProfile("xinchang");
            GmProvider gmAgain = (GmProvider) factory.getProvider();
            boolean verified = gmAgain.getSm2().verify(data, signature, kp.getPublicKeyQ());
            assertThat(verified).isTrue();
        }

        @Test
        @DisplayName("RSA 密钥 — 切换 Profile 后原 RSA 签名/验签仍通过")
        void rsaKey_survivesProfileSwitch() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            // 在 international 下生成 RSA 密钥并签名
            factory.setCurrentProfile("international");
            IntlProvider intl = (IntlProvider) factory.getProvider();
            KeyPair kp = intl.getKeyPair();
            byte[] data = "rsa-switch-key-test".getBytes(StandardCharsets.UTF_8);
            byte[] signature = intl.sign(data, kp.getPrivate());

            // 切换到 xinchang
            factory.setCurrentProfile("xinchang");
            assertThat(factory.getProvider()).isInstanceOf(GmProvider.class);

            // 切回 international，原密钥仍可验签
            factory.setCurrentProfile("international");
            IntlProvider intlAgain = (IntlProvider) factory.getProvider();
            boolean verified = intlAgain.verifySign(data, signature, kp.getPublic());
            assertThat(verified).isTrue();
        }

        @Test
        @DisplayName("SM4 密钥 — 切换 Profile 后原 SM4 加密/解密仍通过")
        void sm4Key_survivesProfileSwitch() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            factory.setCurrentProfile("xinchang");
            GmProvider gm = (GmProvider) factory.getProvider();
            SM4Provider sm4 = gm.getSm4();
            byte[] key = sm4.generateKey();
            byte[] plaintext = "sm4-key-survive".getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = sm4.encrypt(plaintext, key, "ECB", null);

            // 切换到 international 再切回
            factory.setCurrentProfile("international");
            factory.setCurrentProfile("xinchang");

            GmProvider gmAgain = (GmProvider) factory.getProvider();
            byte[] recovered = gmAgain.getSm4().decrypt(ciphertext, key, "ECB", null);
            assertThat(recovered).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("密钥独立性 — 不同 Profile 下生成的密钥互不影响")
        void keysFromDifferentProfiles_independent() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            // 国密 SM2 密钥
            factory.setCurrentProfile("xinchang");
            GmProvider gm = (GmProvider) factory.getProvider();
            SM2Provider.Sm2KeyPair gmKp = gm.getSm2().generateKeyPair();

            // 国际 RSA 密钥
            factory.setCurrentProfile("international");
            IntlProvider intl = (IntlProvider) factory.getProvider();
            KeyPair intlKp = intl.getKeyPair();

            // 两个密钥独立存在，互不影响
            assertThat(gmKp.getPrivateKeyD()).hasSize(32);
            assertThat(intlKp.getPrivate().getEncoded()).isNotEmpty();

            // 各自完成签名闭环
            byte[] data = "independence-test".getBytes(StandardCharsets.UTF_8);
            byte[] gmSig = gm.getSm2().sign(data, gmKp.getPrivateKeyD());
            byte[] intlSig = intl.sign(data, intlKp.getPrivate());

            assertThat(gm.getSm2().verify(data, gmSig, gmKp.getPublicKeyQ())).isTrue();
            assertThat(intl.verifySign(data, intlSig, intlKp.getPublic())).isTrue();
        }
    }

    // ==================== 切换优先级 ====================

    @Nested
    @DisplayName("切换优先级 — 运行时切换 > config > Spring Environment > 默认")
    class SwitchPrecedence {

        @Test
        @DisplayName("运行时切换 优先于 config.activeProfile")
        void runtimeOverride_precedesConfig() {
            config.setActiveProfile("xinchang");
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            factory.setCurrentProfile("international");
            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);
        }

        @Test
        @DisplayName("config.activeProfile 优先于 Spring Environment")
        void config_precedesSpringEnv() {
            MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", "xinchang");
            config.setActiveProfile("international");
            CryptoSpiFactory factory = new CryptoSpiFactory(config, env);

            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);
        }

        @Test
        @DisplayName("Spring Environment 优先于 默认 XINCHANG")
        void springEnv_precedesDefault() {
            MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", "international");
            CryptoSpiFactory factory = new CryptoSpiFactory(config, env);

            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);
        }

        @Test
        @DisplayName("清除运行时覆盖 — 回退到 config")
        void clearRuntimeOverride_fallsBackToConfig() {
            config.setActiveProfile("international");
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            factory.setCurrentProfile("xinchang");
            assertThat(factory.getProvider()).isInstanceOf(GmProvider.class);

            factory.setCurrentProfile(null);
            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);
        }

        @Test
        @DisplayName("清除运行时覆盖 — 回退到 Spring Environment")
        void clearRuntimeOverride_fallsBackToSpringEnv() {
            MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", "international");
            CryptoSpiFactory factory = new CryptoSpiFactory(config, env);

            factory.setCurrentProfile("xinchang");
            assertThat(factory.getProvider()).isInstanceOf(GmProvider.class);

            factory.setCurrentProfile(null);
            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);
        }
    }

    // ==================== 切换异常处理 ====================

    @Nested
    @DisplayName("切换异常处理 — 非法 Profile fail-closed")
    class SwitchExceptionHandling {

        @Test
        @DisplayName("setCurrentProfile(null) — 合法，清除覆盖")
        void setNull_shouldClearOverride() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);
            factory.setCurrentProfile("international");
            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);

            // 不应抛异常
            factory.setCurrentProfile(null);
            assertThat(factory.getProvider()).isInstanceOf(GmProvider.class);
        }

        @Test
        @DisplayName("setCurrentProfile(空字符串) — 抛 CryptoException")
        void setEmptyString_shouldThrow() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);
            assertThatThrownBy(() -> factory.setCurrentProfile(""))
                    .isInstanceOf(CryptoException.class);
        }

        @Test
        @DisplayName("setCurrentProfile(空白字符串) — 抛 CryptoException")
        void setBlankString_shouldThrow() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);
            assertThatThrownBy(() -> factory.setCurrentProfile("   "))
                    .isInstanceOf(CryptoException.class);
        }

        @Test
        @DisplayName("setCurrentProfile(未知值) — 抛 CryptoException")
        void setUnknownValue_shouldThrow() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);
            assertThatThrownBy(() -> factory.setCurrentProfile("unknown"))
                    .isInstanceOf(CryptoException.class)
                    .hasMessageContaining("Unknown CryptoProfile");
        }

        @Test
        @DisplayName("setCurrentProfile(standard) — 抛 CryptoException（应为 international）")
        void setStandard_shouldThrow() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);
            // 项目使用 international 而非 standard，fail-closed
            assertThatThrownBy(() -> factory.setCurrentProfile("standard"))
                    .isInstanceOf(CryptoException.class);
        }

        @Test
        @DisplayName("大小写不敏感 — XINCHANG / INTERNATIONAL 均可切换")
        void caseInsensitive_shouldWork() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            factory.setCurrentProfile("XINCHANG");
            assertThat(factory.getProvider()).isInstanceOf(GmProvider.class);

            factory.setCurrentProfile("INTERNATIONAL");
            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);

            factory.setCurrentProfile("Xinchang");
            assertThat(factory.getProvider()).isInstanceOf(GmProvider.class);

            factory.setCurrentProfile("International");
            assertThat(factory.getProvider()).isInstanceOf(IntlProvider.class);
        }

        @Test
        @DisplayName("非法切换不改变当前 Profile — fail-closed 保持原状态")
        void invalidSwitch_doesNotChangeCurrentProfile() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);
            factory.setCurrentProfile("xinchang");
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);

            // 非法切换抛异常
            assertThatThrownBy(() -> factory.setCurrentProfile("invalid")).isInstanceOf(CryptoException.class);

            // 当前 Profile 仍为 xinchang（fail-closed，未污染状态）
            assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
            assertThat(factory.getProvider()).isInstanceOf(GmProvider.class);
        }
    }

    // ==================== 切换后 Provider 缓存一致性 ====================

    @Nested
    @DisplayName("切换后 Provider 缓存一致性")
    class SwitchCacheConsistency {

        @Test
        @DisplayName("切换后 getProviderByName 仍可获取任一 Provider")
        void afterSwitch_getByNameStillWorks() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            factory.setCurrentProfile("xinchang");
            assertThat(factory.getProviderByName("INTL-Provider")).isInstanceOf(IntlProvider.class);
            assertThat(factory.getProviderByName("GM-Provider")).isInstanceOf(GmProvider.class);

            factory.setCurrentProfile("international");
            assertThat(factory.getProviderByName("INTL-Provider")).isInstanceOf(IntlProvider.class);
            assertThat(factory.getProviderByName("GM-Provider")).isInstanceOf(GmProvider.class);
        }

        @Test
        @DisplayName("切换后 getAvailableProviders 仍返回两个 Provider")
        void afterSwitch_availableProvidersUnchanged() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            factory.setCurrentProfile("xinchang");
            assertThat(factory.getAvailableProviders()).hasSize(2);

            factory.setCurrentProfile("international");
            assertThat(factory.getAvailableProviders()).hasSize(2);
        }

        @Test
        @DisplayName("切换后 getAvailableProviders(profile) 仍按 Profile 过滤")
        void afterSwitch_availableProvidersByProfileStillFilters() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            factory.setCurrentProfile("xinchang");
            assertThat(factory.getAvailableProviders("xinchang"))
                    .allMatch(p -> p.getSupportedProfile() == CryptoProfile.XINCHANG);
            assertThat(factory.getAvailableProviders("international"))
                    .allMatch(p -> p.getSupportedProfile() == CryptoProfile.INTERNATIONAL);

            factory.setCurrentProfile("international");
            assertThat(factory.getAvailableProviders("xinchang"))
                    .allMatch(p -> p.getSupportedProfile() == CryptoProfile.XINCHANG);
            assertThat(factory.getAvailableProviders("international"))
                    .allMatch(p -> p.getSupportedProfile() == CryptoProfile.INTERNATIONAL);
        }

        @Test
        @DisplayName("多次切换 — Provider 实例缓存复用（同一 name 返回同一实例）")
        void multipleSwitches_providerInstanceReused() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);

            factory.setCurrentProfile("xinchang");
            CryptoProvider gm1 = factory.getProviderByName("GM-Provider");

            factory.setCurrentProfile("international");
            CryptoProvider gm2 = factory.getProviderByName("GM-Provider");

            factory.setCurrentProfile("xinchang");
            CryptoProvider gm3 = factory.getProviderByName("GM-Provider");

            // 缓存复用，同一实例
            assertThat(gm1).isSameAs(gm2).isSameAs(gm3);
        }
    }

    // ==================== 切换后功能完整性 ====================

    @Nested
    @DisplayName("切换后功能完整性 — 切换后 Provider 功能仍正常")
    class SwitchFunctionalityIntegrity {

        @Test
        @DisplayName("切换到 xinchang 后 — SM3 摘要功能正常")
        void afterSwitchToXinchang_sm3DigestWorks() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);
            factory.setCurrentProfile("international");
            factory.setCurrentProfile("xinchang");

            GmProvider gm = (GmProvider) factory.getProvider();
            byte[] data = "after-switch-sm3".getBytes(StandardCharsets.UTF_8);
            byte[] digest = gm.getSm3().digest(data);

            assertThat(digest).hasSize(32);
            // 确定性
            assertThat(gm.getSm3().digest(data)).isEqualTo(digest);
        }

        @Test
        @DisplayName("切换到 international 后 — SHA-256 摘要功能正常")
        void afterSwitchToInternational_sha256DigestWorks() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);
            factory.setCurrentProfile("xinchang");
            factory.setCurrentProfile("international");

            IntlProvider intl = (IntlProvider) factory.getProvider();
            byte[] data = "after-switch-sha".getBytes(StandardCharsets.UTF_8);
            byte[] digest = intl.getShaProvider().digest(data, "SHA-256");

            assertThat(digest).hasSize(32);
            assertThat(intl.getShaProvider().digest(data, "SHA-256")).isEqualTo(digest);
        }

        @Test
        @DisplayName("切换到 xinchang 后 — SM2 签名/验签功能正常")
        void afterSwitchToXinchang_sm2SignVerifyWorks() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);
            factory.setCurrentProfile("international");
            factory.setCurrentProfile("xinchang");

            GmProvider gm = (GmProvider) factory.getProvider();
            SM2Provider.Sm2KeyPair kp = gm.getSm2().generateKeyPair();
            byte[] data = "after-switch-sm2".getBytes(StandardCharsets.UTF_8);
            byte[] sig = gm.getSm2().sign(data, kp.getPrivateKeyD());

            assertThat(gm.getSm2().verify(data, sig, kp.getPublicKeyQ())).isTrue();
        }

        @Test
        @DisplayName("切换到 international 后 — RSA 签名/验签功能正常")
        void afterSwitchToInternational_rsaSignVerifyWorks() {
            CryptoSpiFactory factory = new CryptoSpiFactory(config);
            factory.setCurrentProfile("xinchang");
            factory.setCurrentProfile("international");

            IntlProvider intl = (IntlProvider) factory.getProvider();
            KeyPair kp = intl.getKeyPair();
            byte[] data = "after-switch-rsa".getBytes(StandardCharsets.UTF_8);
            byte[] sig = intl.sign(data, kp.getPrivate());

            assertThat(intl.verifySign(data, sig, kp.getPublic())).isTrue();
        }
    }
}