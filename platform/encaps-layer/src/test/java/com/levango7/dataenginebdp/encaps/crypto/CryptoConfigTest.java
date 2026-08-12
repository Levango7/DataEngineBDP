package com.levango7.dataenginebdp.encaps.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CryptoConfig} 单元测试。
 *
 * <p>覆盖默认值、setter/getter、{@link #getDefaultProviderName(CryptoProfile)} 路由
 * 与异常分支。</p>
 */
class CryptoConfigTest {

    private CryptoConfig config;

    @BeforeEach
    void setUp() {
        config = new CryptoConfig();
    }

    @Test
    @DisplayName("默认值 — 信创默认GM-Provider，国际默认INTL-Provider，eagerLoad=true")
    void defaults_shouldMatchExpectedValues() {
        assertThat(config.getDefaultProviderXinchang()).isEqualTo("GM-Provider");
        assertThat(config.getDefaultProviderInternational()).isEqualTo("INTL-Provider");
        assertThat(config.isEagerLoad()).isTrue();
        assertThat(config.getActiveProfile()).isNull();
    }

    @Test
    @DisplayName("setter/getter — activeProfile")
    void activeProfile_shouldRoundTrip() {
        config.setActiveProfile("xinchang");
        assertThat(config.getActiveProfile()).isEqualTo("xinchang");

        config.setActiveProfile("international");
        assertThat(config.getActiveProfile()).isEqualTo("international");
    }

    @Test
    @DisplayName("setter/getter — defaultProviderXinchang")
    void defaultProviderXinchang_shouldRoundTrip() {
        config.setDefaultProviderXinchang("Custom-GM");
        assertThat(config.getDefaultProviderXinchang()).isEqualTo("Custom-GM");
    }

    @Test
    @DisplayName("setter/getter — defaultProviderInternational")
    void defaultProviderInternational_shouldRoundTrip() {
        config.setDefaultProviderInternational("Custom-INTL");
        assertThat(config.getDefaultProviderInternational()).isEqualTo("Custom-INTL");
    }

    @Test
    @DisplayName("setter/getter — eagerLoad")
    void eagerLoad_shouldRoundTrip() {
        config.setEagerLoad(false);
        assertThat(config.isEagerLoad()).isFalse();

        config.setEagerLoad(true);
        assertThat(config.isEagerLoad()).isTrue();
    }

    @Test
    @DisplayName("getDefaultProviderName — XINCHANG返回信创默认Provider名")
    void getDefaultProviderName_xinchang_shouldReturnGmProvider() {
        assertThat(config.getDefaultProviderName(CryptoProfile.XINCHANG))
                .isEqualTo("GM-Provider");
    }

    @Test
    @DisplayName("getDefaultProviderName — INTERNATIONAL返回国际默认Provider名")
    void getDefaultProviderName_international_shouldReturnIntlProvider() {
        assertThat(config.getDefaultProviderName(CryptoProfile.INTERNATIONAL))
                .isEqualTo("INTL-Provider");
    }

    @Test
    @DisplayName("getDefaultProviderName — 自定义后返回自定义名")
    void getDefaultProviderName_afterCustomization_shouldReturnCustomName() {
        config.setDefaultProviderXinchang("My-GM");
        config.setDefaultProviderInternational("My-INTL");

        assertThat(config.getDefaultProviderName(CryptoProfile.XINCHANG)).isEqualTo("My-GM");
        assertThat(config.getDefaultProviderName(CryptoProfile.INTERNATIONAL)).isEqualTo("My-INTL");
    }

    @Test
    @DisplayName("getDefaultProviderName — null Profile 抛 CryptoException")
    void getDefaultProviderName_nullProfile_shouldThrow() {
        assertThatThrownBy(() -> config.getDefaultProviderName(null))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("must not be null");
    }
}