package com.shuqing.bigdata.encaps.crypto;

import com.shuqing.bigdata.encaps.crypto.gm.DefaultGmProvider;
import com.shuqing.bigdata.encaps.crypto.intl.DefaultIntlProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CryptoSpiFactory} 单元测试。
 *
 * <p>覆盖 SPI 加载、Profile 路由、运行时切换、Provider 缓存、异常分支等。</p>
 *
 * <p>测试依赖 classpath 上通过 META-INF/services 注册的
 * {@link DefaultGmProvider} 与 {@link DefaultIntlProvider}。</p>
 */
class CryptoSpiFactoryTest {

    private CryptoConfig config;

    @BeforeEach
    void setUp() {
        config = new CryptoConfig();
    }

    // ===== 构造 =====

    @Test
    @DisplayName("构造 — cryptoConfig为null抛CryptoException")
    void constructor_nullConfig_shouldThrow() {
        assertThatThrownBy(() -> new CryptoSpiFactory(null))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("CryptoConfig must not be null");
    }

    @Test
    @DisplayName("构造 — 仅config无Environment，可正常使用")
    void constructor_configOnly_shouldWork() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);
        assertThat(factory.getAvailableProviders()).hasSize(2);
    }

    // ===== SPI 加载 =====

    @Test
    @DisplayName("SPI加载 — 默认加载GM-Provider与INTL-Provider两个实现")
    void spiLoad_shouldLoadBothDefaultProviders() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        List<CryptoProvider> providers = factory.getAvailableProviders();
        assertThat(providers).hasSize(2);

        List<String> names = providers.stream().map(CryptoProvider::getProviderName).toList();
        assertThat(names).containsExactlyInAnyOrder("GM-Provider", "INTL-Provider");
    }

    @Test
    @DisplayName("getAvailableProviders — 返回不可变列表")
    void getAvailableProviders_shouldReturnImmutableList() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);
        List<CryptoProvider> providers = factory.getAvailableProviders();

        assertThatThrownBy(() -> providers.add(new DefaultGmProvider()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("getAvailableProviders(profile) — xinchang仅返回GM-Provider")
    void getAvailableProvidersByProfile_xinchang_shouldReturnOnlyGm() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        List<CryptoProvider> providers = factory.getAvailableProviders("xinchang");
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).getProviderName()).isEqualTo("GM-Provider");
        assertThat(providers.get(0).getSupportedProfile()).isEqualTo(CryptoProfile.XINCHANG);
    }

    @Test
    @DisplayName("getAvailableProviders(profile) — international仅返回INTL-Provider")
    void getAvailableProvidersByProfile_international_shouldReturnOnlyIntl() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        List<CryptoProvider> providers = factory.getAvailableProviders("international");
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).getProviderName()).isEqualTo("INTL-Provider");
        assertThat(providers.get(0).getSupportedProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
    }

    @Test
    @DisplayName("getAvailableProviders(profile) — 非法profile抛CryptoException")
    void getAvailableProvidersByProfile_invalid_shouldThrow() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        assertThatThrownBy(() -> factory.getAvailableProviders("unknown"))
                .isInstanceOf(CryptoException.class);
    }

    // ===== getProvider() — Profile 解析 =====

    @Test
    @DisplayName("getProvider — 无任何Profile配置，fail-safe回退XINCHANG返回GM-Provider")
    void getProvider_noProfile_shouldFallbackToXinchang() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider provider = factory.getProvider();
        assertThat(provider.getProviderName()).isEqualTo("GM-Provider");
        assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
    }

    @Test
    @DisplayName("getProvider — CryptoConfig.activeProfile=xinchang，返回GM-Provider")
    void getProvider_configProfileXinchang_shouldReturnGm() {
        config.setActiveProfile("xinchang");
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider provider = factory.getProvider();
        assertThat(provider.getProviderName()).isEqualTo("GM-Provider");
        assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
    }

    @Test
    @DisplayName("getProvider — CryptoConfig.activeProfile=international，返回INTL-Provider")
    void getProvider_configProfileInternational_shouldReturnIntl() {
        config.setActiveProfile("international");
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider provider = factory.getProvider();
        assertThat(provider.getProviderName()).isEqualTo("INTL-Provider");
        assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
    }

    @Test
    @DisplayName("getProvider — Spring Profile=xinchang，返回GM-Provider")
    void getProvider_springProfileXinchang_shouldReturnGm() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", "xinchang");
        CryptoSpiFactory factory = new CryptoSpiFactory(config, env);

        CryptoProvider provider = factory.getProvider();
        assertThat(provider.getProviderName()).isEqualTo("GM-Provider");
    }

    @Test
    @DisplayName("getProvider — Spring Profile=international，返回INTL-Provider")
    void getProvider_springProfileInternational_shouldReturnIntl() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", "international");
        CryptoSpiFactory factory = new CryptoSpiFactory(config, env);

        CryptoProvider provider = factory.getProvider();
        assertThat(provider.getProviderName()).isEqualTo("INTL-Provider");
    }

    @Test
    @DisplayName("getProvider — Spring Profile含其他值，取匹配的crypto profile")
    void getProvider_springProfileWithOthers_shouldPickCryptoProfile() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", "dev,international");
        CryptoSpiFactory factory = new CryptoSpiFactory(config, env);

        CryptoProvider provider = factory.getProvider();
        assertThat(provider.getProviderName()).isEqualTo("INTL-Provider");
    }

    @Test
    @DisplayName("getProvider — 运行时切换Profile优先级最高")
    void getProvider_overrideProfile_shouldTakePrecedence() {
        config.setActiveProfile("xinchang");
        MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", "xinchang");
        CryptoSpiFactory factory = new CryptoSpiFactory(config, env);

        factory.setCurrentProfile("international");
        CryptoProvider provider = factory.getProvider();
        assertThat(provider.getProviderName()).isEqualTo("INTL-Provider");
        assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);
    }

    @Test
    @DisplayName("setCurrentProfile(null) — 清除覆盖，回退到config")
    void setCurrentProfile_null_shouldClearOverride() {
        config.setActiveProfile("international");
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        factory.setCurrentProfile("xinchang");
        assertThat(factory.getProvider().getProviderName()).isEqualTo("GM-Provider");

        factory.setCurrentProfile(null);
        assertThat(factory.getProvider().getProviderName()).isEqualTo("INTL-Provider");
    }

    @Test
    @DisplayName("setCurrentProfile — 非法值抛CryptoException")
    void setCurrentProfile_invalid_shouldThrow() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        assertThatThrownBy(() -> factory.setCurrentProfile("unknown"))
                .isInstanceOf(CryptoException.class);
    }

    // ===== getProvider(String profile) =====

    @Test
    @DisplayName("getProvider(profile) — xinchang返回GM-Provider")
    void getProviderByProfile_xinchang_shouldReturnGm() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider provider = factory.getProvider("xinchang");
        assertThat(provider.getProviderName()).isEqualTo("GM-Provider");
    }

    @Test
    @DisplayName("getProvider(profile) — international返回INTL-Provider")
    void getProviderByProfile_international_shouldReturnIntl() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider provider = factory.getProvider("international");
        assertThat(provider.getProviderName()).isEqualTo("INTL-Provider");
    }

    @Test
    @DisplayName("getProvider(profile) — 非法profile抛CryptoException")
    void getProviderByProfile_invalid_shouldThrow() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        assertThatThrownBy(() -> factory.getProvider("unknown"))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("Unknown CryptoProfile");
    }

    @Test
    @DisplayName("getProvider(profile) — 大小写不敏感")
    void getProviderByProfile_caseInsensitive_shouldWork() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        assertThat(factory.getProvider("XINCHANG").getProviderName()).isEqualTo("GM-Provider");
        assertThat(factory.getProvider("INTERNATIONAL").getProviderName()).isEqualTo("INTL-Provider");
    }

    // ===== getProvider(profile, providerName) =====

    @Test
    @DisplayName("getProvider(profile, name) — 显式指定Provider名")
    void getProviderByProfileAndName_shouldReturnSpecified() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider provider = factory.getProvider("xinchang", "INTL-Provider");
        assertThat(provider.getProviderName()).isEqualTo("INTL-Provider");
    }

    @Test
    @DisplayName("getProvider(profile, name) — 不存在Provider名抛CryptoException")
    void getProviderByProfileAndName_notFound_shouldThrow() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        assertThatThrownBy(() -> factory.getProvider("xinchang", "Not-Exist"))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("CryptoProvider not found");
    }

    @Test
    @DisplayName("getProvider(profile, name) — 空白name抛CryptoException")
    void getProviderByProfileAndName_blankName_shouldThrow() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        assertThatThrownBy(() -> factory.getProvider("xinchang", ""))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> factory.getProvider("xinchang", null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("getProvider(profile, name) — 非法profile抛CryptoException")
    void getProviderByProfileAndName_invalidProfile_shouldThrow() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        assertThatThrownBy(() -> factory.getProvider("unknown", "GM-Provider"))
                .isInstanceOf(CryptoException.class);
    }

    // ===== getProviderByName =====

    @Test
    @DisplayName("getProviderByName — 直接按名获取")
    void getProviderByName_shouldReturnProvider() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        assertThat(factory.getProviderByName("GM-Provider").getProviderName()).isEqualTo("GM-Provider");
        assertThat(factory.getProviderByName("INTL-Provider").getProviderName()).isEqualTo("INTL-Provider");
    }

    @Test
    @DisplayName("getProviderByName — 不存在抛CryptoException")
    void getProviderByName_notFound_shouldThrow() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        assertThatThrownBy(() -> factory.getProviderByName("Not-Exist"))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("getProviderByName — 空白抛CryptoException")
    void getProviderByName_blank_shouldThrow() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        assertThatThrownBy(() -> factory.getProviderByName(""))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> factory.getProviderByName(null))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 运行时切换 Provider 名 =====

    @Test
    @DisplayName("setCurrentProviderName — 覆盖默认Provider选择")
    void setCurrentProviderName_shouldOverrideDefault() {
        config.setActiveProfile("xinchang");
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        // 默认 GM
        assertThat(factory.getProvider().getProviderName()).isEqualTo("GM-Provider");

        // 切换到 INTL
        factory.setCurrentProviderName("INTL-Provider");
        assertThat(factory.getProvider().getProviderName()).isEqualTo("INTL-Provider");

        // getProvider(profile) 也使用 override
        assertThat(factory.getProvider("xinchang").getProviderName()).isEqualTo("INTL-Provider");
    }

    @Test
    @DisplayName("setCurrentProviderName(null) — 清除覆盖，回退到默认")
    void setCurrentProviderName_null_shouldClearOverride() {
        config.setActiveProfile("xinchang");
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        factory.setCurrentProviderName("INTL-Provider");
        assertThat(factory.getProvider().getProviderName()).isEqualTo("INTL-Provider");

        factory.setCurrentProviderName(null);
        assertThat(factory.getProvider().getProviderName()).isEqualTo("GM-Provider");
    }

    @Test
    @DisplayName("setCurrentProviderName — 切换到不存在的名，调用时才报错（懒校验）")
    void setCurrentProviderName_nonExist_shouldErrorOnGet() {
        config.setActiveProfile("xinchang");
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        factory.setCurrentProviderName("Not-Exist");
        assertThatThrownBy(() -> factory.getProvider())
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("not found");
    }

    // ===== 自定义默认 Provider 名 =====

    @Test
    @DisplayName("自定义默认Provider名 — 信创指向INTL也能正常获取")
    void customDefaultProviderName_shouldWork() {
        config.setActiveProfile("xinchang");
        config.setDefaultProviderXinchang("INTL-Provider");
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider provider = factory.getProvider();
        assertThat(provider.getProviderName()).isEqualTo("INTL-Provider");
    }

    @Test
    @DisplayName("自定义默认Provider名 — 不存在的名抛CryptoException")
    void customDefaultProviderName_notExist_shouldThrow() {
        config.setActiveProfile("xinchang");
        config.setDefaultProviderXinchang("Not-Exist");
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        assertThatThrownBy(() -> factory.getProvider())
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("not found");
    }

    // ===== reload =====

    @Test
    @DisplayName("reload — 重新加载后Provider仍可用")
    void reload_shouldRepopulateCache() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        // 触发首次加载
        assertThat(factory.getAvailableProviders()).hasSize(2);

        // reload
        factory.reload();
        assertThat(factory.getAvailableProviders()).hasSize(2);
        assertThat(factory.getProviderByName("GM-Provider").getProviderName()).isEqualTo("GM-Provider");
    }

    @Test
    @DisplayName("reload — 多次调用幂等")
    void reload_multipleTimes_shouldBeIdempotent() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        factory.reload();
        factory.reload();
        factory.reload();

        assertThat(factory.getAvailableProviders()).hasSize(2);
    }

    // ===== getCurrentProfile =====

    @Test
    @DisplayName("getCurrentProfile — 反映override优先级")
    void getCurrentProfile_shouldReflectOverride() {
        config.setActiveProfile("international");
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.INTERNATIONAL);

        factory.setCurrentProfile("xinchang");
        assertThat(factory.getCurrentProfile()).isEqualTo(CryptoProfile.XINCHANG);
    }

    // ===== 缓存验证 =====

    @Test
    @DisplayName("缓存 — 同一Provider名返回同一实例")
    void cache_shouldReturnSameInstance() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider p1 = factory.getProviderByName("GM-Provider");
        CryptoProvider p2 = factory.getProviderByName("GM-Provider");
        assertThat(p1).isSameAs(p2);
    }

    @Test
    @DisplayName("缓存 — reload后实例更新（重新ServiceLoader迭代）")
    void cache_afterReload_shouldBeNewInstance() {
        CryptoSpiFactory factory = new CryptoSpiFactory(config);

        CryptoProvider p1 = factory.getProviderByName("GM-Provider");
        factory.reload();
        CryptoProvider p2 = factory.getProviderByName("GM-Provider");

        // reload 后应重新加载，可能是新实例
        assertThat(p2.getProviderName()).isEqualTo("GM-Provider");
    }

    // ===== Environment null 场景 =====

    @Test
    @DisplayName("Environment=null — 仅依赖config.activeProfile")
    void environmentNull_shouldUseConfigProfile() {
        config.setActiveProfile("international");
        CryptoSpiFactory factory = new CryptoSpiFactory(config, null);

        assertThat(factory.getProvider().getProviderName()).isEqualTo("INTL-Provider");
    }

    @Test
    @DisplayName("Environment无profile属性 — 回退到config或默认")
    void environmentWithoutProfile_shouldFallback() {
        MockEnvironment env = new MockEnvironment();
        CryptoSpiFactory factory = new CryptoSpiFactory(config, env);

        // 无 config.activeProfile，无 spring profile，回退 XINCHANG
        assertThat(factory.getProvider().getProviderName()).isEqualTo("GM-Provider");
    }
}