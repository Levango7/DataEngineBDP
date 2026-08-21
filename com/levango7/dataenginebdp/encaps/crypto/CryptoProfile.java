package com.shuqing.bigdata.encaps.crypto;

import java.util.Arrays;
import java.util.Locale;

/**
 * 加密 Profile 枚举。
 *
 * <p>对应 Spring Profile（{@code spring.profiles.active}），用于在多合规辖区下切换
 * 加密 Provider 集合：</p>
 * <ul>
 *   <li>{@link #XINCHANG} — 信创/国密辖区，默认加载国密 Provider（SM2/SM3/SM4）</li>
 *   <li>{@link #INTERNATIONAL} — 国际辖区，默认加载国际 Provider（RSA/SHA-256/AES）</li>
 * </ul>
 *
 * <p>Profile 字符串与 Spring Profile 一致：{@code xinchang} / {@code international}，
 * 大小写不敏感。无法识别的字符串将在 {@link #fromString(String)} 中抛出
 * {@link CryptoException}，fail-closed 防止误用默认 Profile。</p>
 */
public enum CryptoProfile {

    /** 信创 Profile：默认国密 Provider */
    XINCHANG("xinchang", "GM-Provider", "国密SM2/SM3/SM4默认Provider"),

    /** 国际 Profile：默认国际 Provider */
    INTERNATIONAL("international", "INTL-Provider", "RSA/SHA-256/AES默认Provider");

    /** Spring Profile 字符串 */
    private final String profileName;

    /** 该 Profile 下默认 Provider 名称 */
    private final String defaultProviderName;

    /** 描述（便于日志/调试） */
    private final String description;

    CryptoProfile(String profileName, String defaultProviderName, String description) {
        this.profileName = profileName;
        this.defaultProviderName = defaultProviderName;
        this.description = description;
    }

    /**
     * Spring Profile 字符串。
     *
     * @return 与 {@code spring.profiles.active} 一致的小写标识
     */
    public String getProfileName() {
        return profileName;
    }

    /**
     * 该 Profile 下默认 Provider 名称。
     *
     * @return 默认 Provider 名称，供 {@link CryptoSpiFactory} 在未显式指定 Provider 时使用
     */
    public String getDefaultProviderName() {
        return defaultProviderName;
    }

    /**
     * 描述。
     *
     * @return 人类可读描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 由 Spring Profile 字符串解析为枚举。
     *
     * <p>大小写不敏感；空白或无法识别时抛出 {@link CryptoException}，fail-closed。</p>
     *
     * @param profile Spring Profile 字符串，如 {@code xinchang} / {@code international}
     * @return 对应枚举
     * @throws CryptoException 当字符串为空或无法识别
     */
    public static CryptoProfile fromString(String profile) {
        if (profile == null || profile.isBlank()) {
            throw new CryptoException("CryptoProfile must not be blank");
        }
        String normalized = profile.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(p -> p.profileName.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new CryptoException(
                        "Unknown CryptoProfile: " + profile
                                + ", supported: xinchang|international"));
    }
}