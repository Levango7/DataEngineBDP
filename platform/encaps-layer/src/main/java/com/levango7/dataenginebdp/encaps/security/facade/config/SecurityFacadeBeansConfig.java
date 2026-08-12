package com.levango7.dataenginebdp.encaps.security.facade.config;

import com.levango7.dataenginebdp.encaps.crypto.CryptoConfig;
import com.levango7.dataenginebdp.encaps.crypto.CryptoSpiFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * SecurityFacade Spring 装配配置。
 *
 * <p>注册 SecurityFacade 依赖的底层 Bean（{@link CryptoSpiFactory}），
 * 确保 {@link com.levango7.dataenginebdp.encaps.security.facade.SecurityFacade} 及其子 Facade
 * 可通过构造注入完成装配。</p>
 *
 * <h3>注册的 Bean</h3>
 * <ul>
 *   <li>{@link CryptoSpiFactory} — 加密 SPI 工厂（T022 已实现，但未注册为 Bean）</li>
 * </ul>
 *
 * <p>设计说明：{@link CryptoSpiFactory} 在 T022 中作为纯 POJO 实现，
 * 由各 Factory（TransportCipherFactory 等）按需 new 出实例；
 * 但 SecurityFacade 体系需要通过 Spring 容器统一管理生命周期，故在此注册为单例 Bean。
 * {@link ObjectMapper} 由 Spring Boot Web 自动配置提供，无需在此注册。</p>
 */
@Configuration
public class SecurityFacadeBeansConfig {

    /**
     * 注册 CryptoSpiFactory 单例。
     *
     * <p>使用容器中的 {@link CryptoConfig} 与 {@link Environment} 构造，
     * 保证 Profile 路由与 T022 配置一致。</p>
     *
     * @param cryptoConfig 加密配置
     * @param environment  Spring Environment
     * @return CryptoSpiFactory 单例
     */
    @Bean
    public CryptoSpiFactory cryptoSpiFactory(CryptoConfig cryptoConfig, Environment environment) {
        return new CryptoSpiFactory(cryptoConfig, environment);
    }
}