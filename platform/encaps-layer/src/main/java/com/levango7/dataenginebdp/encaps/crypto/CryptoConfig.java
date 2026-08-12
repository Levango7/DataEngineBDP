package com.levango7.dataenginebdp.encaps.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 加密配置。
 *
 * <p>绑定 {@code app.crypto.*} 配置项，控制 SPI 工厂的 Profile 与默认 Provider 选择。</p>
 *
 * <p>典型 YAML：</p>
 * <pre>{@code
 * app:
 *   crypto:
 *     # 显式覆盖当前 Profile；为空时回退到 spring.profiles.active
 *     active-profile: xinchang
 *     # 信创 Profile 下默认 Provider 名（默认 GM-Provider）
 *     default-provider-xinchang: GM-Provider
 *     # 国际 Profile 下默认 Provider 名（默认 INTL-Provider）
 *     default-provider-international: INTL-Provider
 *     # 是否在启动期预加载所有 Provider（默认 true）
 *     eager-load: true
 * }</pre>
 */
@Configuration
@ConfigurationProperties(prefix = "app.crypto")
public class CryptoConfig {

    /** 默认 Profile：与 Spring Profile 同步，未显式设置时为 null，回退到 spring.profiles.active */
    private String activeProfile;

    /** 信创 Profile 下默认 Provider 名 */
    private String defaultProviderXinchang = "GM-Provider";

    /** 国际 Profile 下默认 Provider 名 */
    private String defaultProviderInternational = "INTL-Provider";

    /** 是否在启动期预加载所有 Provider */
    private boolean eagerLoad = true;

    /**
     * 当前显式配置的 Profile 字符串。
     *
     * @return Profile 字符串；未配置返回 null
     */
    public String getActiveProfile() {
        return activeProfile;
    }

    /**
     * 设置当前 Profile 字符串。
     *
     * @param activeProfile Profile 字符串，如 {@code xinchang} / {@code international}
     */
    public void setActiveProfile(String activeProfile) {
        this.activeProfile = activeProfile;
    }

    /**
     * 信创 Profile 下默认 Provider 名。
     *
     * @return Provider 名
     */
    public String getDefaultProviderXinchang() {
        return defaultProviderXinchang;
    }

    /**
     * 设置信创 Profile 下默认 Provider 名。
     *
     * @param defaultProviderXinchang Provider 名
     */
    public void setDefaultProviderXinchang(String defaultProviderXinchang) {
        this.defaultProviderXinchang = defaultProviderXinchang;
    }

    /**
     * 国际 Profile 下默认 Provider 名。
     *
     * @return Provider 名
     */
    public String getDefaultProviderInternational() {
        return defaultProviderInternational;
    }

    /**
     * 设置国际 Profile 下默认 Provider 名。
     *
     * @param defaultProviderInternational Provider 名
     */
    public void setDefaultProviderInternational(String defaultProviderInternational) {
        this.defaultProviderInternational = defaultProviderInternational;
    }

    /**
     * 是否启动期预加载所有 Provider。
     *
     * @return true 表示预加载
     */
    public boolean isEagerLoad() {
        return eagerLoad;
    }

    /**
     * 设置是否预加载。
     *
     * @param eagerLoad 预加载标志
     */
    public void setEagerLoad(boolean eagerLoad) {
        this.eagerLoad = eagerLoad;
    }

    /**
     * 由 Profile 枚举获取对应默认 Provider 名。
     *
     * @param profile Profile 枚举
     * @return 该 Profile 下默认 Provider 名
     */
    public String getDefaultProviderName(CryptoProfile profile) {
        if (profile == null) {
            throw new CryptoException("CryptoProfile must not be null");
        }
        return switch (profile) {
            case XINCHANG -> defaultProviderXinchang;
            case INTERNATIONAL -> defaultProviderInternational;
        };
    }
}