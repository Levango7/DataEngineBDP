package com.shuqing.bigdata.encaps.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加密 SPI 工厂。
 *
 * <p>通过 Java SPI（{@link ServiceLoader}）加载 {@link CryptoProvider} 实现，
 * 按 {@link CryptoProfile} 路由到具体 Provider，并支持运行时切换。</p>
 *
 * <h3>加载机制</h3>
 * <ul>
 *   <li>首次调用时通过 {@link ServiceLoader#load(Class)} 枚举 classpath 上
 *       {@code META-INF/services/com.shuqing.bigdata.encaps.crypto.CryptoProvider}
 *       中注册的所有实现</li>
 *   <li>实例按 {@link CryptoProvider#getProviderName()} 去重缓存到
 *       {@link ConcurrentHashMap}，避免重复实例化</li>
 *   <li>支持 {@link #reload()} 主动刷新（如热部署）</li>
 * </ul>
 *
 * <h3>Profile 解析优先级</h3>
 * <ol>
 *   <li>运行时显式切换值（{@link #setCurrentProfile(String)}）</li>
 *   <li>{@link CryptoConfig#getActiveProfile()} 显式配置</li>
 *   <li>Spring {@code spring.profiles.active}（通过 {@link Environment}）</li>
 *   <li>默认 {@link CryptoProfile#XINCHANG}（fail-safe，但会记录 WARN）</li>
 * </ol>
 *
 * <h3>Provider 选择策略</h3>
 * <p>在指定 Profile 下：</p>
 * <ol>
 *   <li>若运行时显式切换过 Provider 名（{@link #setCurrentProviderName(String)}），使用该名</li>
 *   <li>否则使用 {@link CryptoConfig#getDefaultProviderName(CryptoProfile)} 返回的默认名</li>
 *   <li>若该名不存在于已加载 Provider，抛出 {@link CryptoException}</li>
 * </ol>
 */
public class CryptoSpiFactory {

    private static final Logger log = LoggerFactory.getLogger(CryptoSpiFactory.class);

    /** Spring Profile 配置键 */
    private static final String SPRING_PROFILE_KEY = "spring.profiles.active";

    /** Provider 缓存：providerName -> CryptoProvider 实例 */
    private final ConcurrentHashMap<String, CryptoProvider> providerCache = new ConcurrentHashMap<>();

    /** 已加载标志，避免重复 ServiceLoader 迭代 */
    private volatile boolean loaded = false;

    private final CryptoConfig cryptoConfig;
    private final Environment environment;

    /** 运行时切换的 Profile（优先级最高） */
    private volatile String overrideProfile;

    /** 运行时切换的 Provider 名（优先级最高） */
    private volatile String overrideProviderName;

    /**
     * 构造工厂。
     *
     * @param cryptoConfig 加密配置，不可为 null
     * @param environment  Spring Environment，可为 null（无 Spring 容器场景）
     * @throws CryptoException 当 cryptoConfig 为 null
     */
    public CryptoSpiFactory(CryptoConfig cryptoConfig, Environment environment) {
        if (cryptoConfig == null) {
            throw new CryptoException("CryptoConfig must not be null");
        }
        this.cryptoConfig = cryptoConfig;
        this.environment = environment;
    }

    /**
     * 便捷构造（无 Spring Environment）。
     *
     * @param cryptoConfig 加密配置
     */
    public CryptoSpiFactory(CryptoConfig cryptoConfig) {
        this(cryptoConfig, null);
    }

    /**
     * 根据当前 Profile 获取默认 Provider。
     *
     * <p>Profile 解析见类注释；Provider 名取该 Profile 的默认名。</p>
     *
     * @return 当前 Profile 下的默认 Provider
     * @throws CryptoException 无任何 Provider 或指定 Provider 不存在
     */
    public CryptoProvider getProvider() {
        CryptoProfile profile = resolveCurrentProfile();
        return getProvider(profile.getProfileName());
    }

    /**
     * 指定 Profile 获取默认 Provider。
     *
     * @param profile Profile 字符串，如 {@code xinchang} / {@code international}
     * @return 该 Profile 下的默认 Provider
     * @throws CryptoException Profile 不合法或 Provider 不存在
     */
    public CryptoProvider getProvider(String profile) {
        CryptoProfile cryptoProfile = CryptoProfile.fromString(profile);
        String providerName = resolveProviderName(cryptoProfile);
        return getProviderByName(providerName);
    }

    /**
     * 指定 Profile 与 Provider 名获取 Provider。
     *
     * @param profile      Profile 字符串
     * @param providerName Provider 名
     * @return 对应 Provider
     * @throws CryptoException 参数不合法或 Provider 不存在
     */
    public CryptoProvider getProvider(String profile, String providerName) {
        // 校验 Profile 合法性（fail-closed）
        CryptoProfile.fromString(profile);
        if (providerName == null || providerName.isBlank()) {
            throw new CryptoException("providerName must not be blank");
        }
        return getProviderByName(providerName);
    }

    /**
     * 按 Provider 名直接获取（跨 Profile）。
     *
     * @param providerName Provider 名
     * @return 对应 Provider
     * @throws CryptoException Provider 不存在
     */
    public CryptoProvider getProviderByName(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            throw new CryptoException("providerName must not be blank");
        }
        ensureLoaded();
        CryptoProvider provider = providerCache.get(providerName);
        if (provider == null) {
            throw new CryptoException("CryptoProvider not found: " + providerName
                    + ", available: " + providerCache.keySet());
        }
        return provider;
    }

    /**
     * 列出所有已加载 Provider。
     *
     * @return 不可变列表
     */
    public List<CryptoProvider> getAvailableProviders() {
        ensureLoaded();
        return Collections.unmodifiableList(new ArrayList<>(providerCache.values()));
    }

    /**
     * 列出指定 Profile 下所有可用 Provider。
     *
     * @param profile Profile 字符串
     * @return 该 Profile 下的 Provider 列表
     */
    public List<CryptoProvider> getAvailableProviders(String profile) {
        CryptoProfile cryptoProfile = CryptoProfile.fromString(profile);
        ensureLoaded();
        List<CryptoProvider> result = new ArrayList<>();
        for (CryptoProvider p : providerCache.values()) {
            if (p.getSupportedProfile() == cryptoProfile) {
                result.add(p);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 运行时切换当前 Profile。
     *
     * <p>切换后 {@link #getProvider()} 将使用该 Profile。传 null 清除覆盖，回退到配置。</p>
     *
     * @param profile Profile 字符串；null 清除覆盖
     * @throws CryptoException Profile 不合法
     */
    public void setCurrentProfile(String profile) {
        if (profile != null) {
            // 校验合法性
            CryptoProfile.fromString(profile);
        }
        this.overrideProfile = profile;
        log.info("CryptoSpiFactory current profile switched to: {}", profile);
    }

    /**
     * 运行时切换当前 Provider 名。
     *
     * <p>切换后 {@link #getProvider()} / {@link #getProvider(String)} 将优先使用该名。
     * 传 null 清除覆盖，回退到各 Profile 默认。</p>
     *
     * @param providerName Provider 名；null 清除覆盖
     */
    public void setCurrentProviderName(String providerName) {
        this.overrideProviderName = providerName;
        log.info("CryptoSpiFactory current provider name switched to: {}", providerName);
    }

    /**
     * 获取当前生效 Profile。
     *
     * @return 当前 Profile 枚举
     */
    public CryptoProfile getCurrentProfile() {
        return resolveCurrentProfile();
    }

    /**
     * 重新加载 SPI。
     *
     * <p>清空缓存并重新迭代 ServiceLoader。用于热部署或测试场景。</p>
     */
    public synchronized void reload() {
        providerCache.clear();
        loaded = false;
        ensureLoaded();
    }

    // ---------------- 内部方法 ----------------

    /**
     * 懒加载所有 SPI 实现。
     */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            ServiceLoader<CryptoProvider> loader = ServiceLoader.load(CryptoProvider.class);
            int count = 0;
            for (CryptoProvider provider : loader) {
                String name = provider.getProviderName();
                if (name == null || name.isBlank()) {
                    log.warn("Skipping CryptoProvider with blank name: {}", provider.getClass());
                    continue;
                }
                if (providerCache.putIfAbsent(name, provider) != null) {
                    log.warn("Duplicate CryptoProvider name '{}', keeping first", name);
                } else {
                    count++;
                    log.debug("Loaded CryptoProvider: name={}, class={}, profile={}, algorithm={}",
                            name, provider.getClass().getName(),
                            provider.getSupportedProfile(), provider.getAlgorithmType());
                }
            }
            loaded = true;
            log.info("CryptoSpiFactory loaded {} CryptoProvider(s): {}", count, providerCache.keySet());
            if (count == 0 && cryptoConfig.isEagerLoad()) {
                log.warn("No CryptoProvider loaded via SPI; check META-INF/services registration");
            }
        }
    }

    /**
     * 解析当前 Profile。
     */
    private CryptoProfile resolveCurrentProfile() {
        // 1. 运行时覆盖
        if (overrideProfile != null && !overrideProfile.isBlank()) {
            return CryptoProfile.fromString(overrideProfile);
        }
        // 2. CryptoConfig 显式配置
        if (cryptoConfig.getActiveProfile() != null && !cryptoConfig.getActiveProfile().isBlank()) {
            return CryptoProfile.fromString(cryptoConfig.getActiveProfile());
        }
        // 3. Spring Environment
        if (environment != null) {
            String springProfile = environment.getProperty(SPRING_PROFILE_KEY);
            if (springProfile != null && !springProfile.isBlank()) {
                // spring.profiles.active 可能逗号分隔多个，取第一个匹配的
                for (String p : springProfile.split(",")) {
                    String trimmed = p.trim();
                    if (!trimmed.isEmpty()) {
                        try {
                            return CryptoProfile.fromString(trimmed);
                        } catch (CryptoException ignored) {
                            // 不是 crypto profile，继续尝试下一个
                        }
                    }
                }
            }
        }
        // 4. fail-safe 默认
        log.warn("No active CryptoProfile resolved, falling back to XINCHANG");
        return CryptoProfile.XINCHANG;
    }

    /**
     * 解析 Provider 名。
     */
    private String resolveProviderName(CryptoProfile profile) {
        if (overrideProviderName != null && !overrideProviderName.isBlank()) {
            return overrideProviderName;
        }
        return cryptoConfig.getDefaultProviderName(profile);
    }
}