package com.shuqing.bigdata.infra.orchestrator.registry;

import com.shuqing.bigdata.infra.orchestrator.model.EnvironmentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider 注册表 - L0.5 跨环境统一供给抽象的核心组件。
 *
 * <p>维护 {@link EnvironmentType} → {@link ProviderDescriptor} 的运行时映射，
 * 提供 Provider 的注册、查找、列出与注销能力。注册表在应用启动时由
 * {@link com.shuqing.bigdata.infra.orchestrator.service.ProviderRegistryService}
 * 根据 {@code application.yml} 配置自动填充，亦支持运行时动态注册（灰度发布、Provider 热替换）。</p>
 *
 * <p>线程安全：内部使用 {@link ConcurrentHashMap} 与 {@link EnumMap} 的同步包装，
 * 允许注册与查找并发进行。同一 {@link EnvironmentType} 仅允许注册一个 Provider，
 * 重复注册将覆盖旧描述符并记录 WARN 日志。</p>
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    /**
     * 环境 → 描述符映射。使用 {@link ConcurrentHashMap} 保证并发读，写时整体替换保证原子性。
     */
    private final Map<EnvironmentType, ProviderDescriptor> registry = new ConcurrentHashMap<>();

    /**
     * 注册一个 Provider 描述符。
     *
     * <p>若同一 {@link EnvironmentType} 已存在描述符，将覆盖并记录 WARN 日志，
     * 用于支持 Provider 灰度切换、地址变更等运维场景。</p>
     *
     * @param descriptor Provider 描述符，不能为 null，{@code environmentType} 不能为 null
     * @throws IllegalArgumentException 若 descriptor 或其 environmentType 为 null
     */
    public synchronized void register(ProviderDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(descriptor.getEnvironmentType(),
                "descriptor.environmentType must not be null");

        ProviderDescriptor previous = registry.put(descriptor.getEnvironmentType(), descriptor);
        if (previous == null) {
            log.info("Provider registered: env={} name={} baseUrl={} enabled={}",
                    descriptor.getEnvironmentType(), descriptor.getName(),
                    descriptor.getBaseUrl(), descriptor.isEnabled());
        } else {
            log.warn("Provider re-registered: env={} oldName={} newName={} oldUrl={} newUrl={}",
                    descriptor.getEnvironmentType(), previous.getName(), descriptor.getName(),
                    previous.getBaseUrl(), descriptor.getBaseUrl());
        }
    }

    /**
     * 注销指定环境的 Provider。
     *
     * @param environmentType 环境类型
     * @return 被移除的描述符；若不存在返回 {@link Optional#empty()}
     */
    public synchronized Optional<ProviderDescriptor> unregister(EnvironmentType environmentType) {
        ProviderDescriptor removed = registry.remove(environmentType);
        if (removed != null) {
            log.info("Provider unregistered: env={} name={}", environmentType, removed.getName());
        }
        return Optional.ofNullable(removed);
    }

    /**
     * 查找指定环境的已启用 Provider。
     *
     * <p>禁用的 Provider 不会被返回，调用方应捕获
     * {@link IllegalArgumentException} 处理"未找到"场景。</p>
     *
     * @param environmentType 环境类型
     * @return 已启用的 Provider 描述符
     * @throws IllegalArgumentException 若环境未注册或 Provider 被禁用
     */
    public ProviderDescriptor lookup(EnvironmentType environmentType) {
        ProviderDescriptor descriptor = registry.get(environmentType);
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "no provider registered for environment: " + environmentType
                            + ", registered: " + registeredEnvironments());
        }
        if (!descriptor.isEnabled()) {
            throw new IllegalArgumentException(
                    "provider disabled for environment: " + environmentType
                            + " (name=" + descriptor.getName() + ")");
        }
        return descriptor;
    }

    /**
     * 查找指定环境的 Provider（不校验 enabled 状态）。
     *
     * @param environmentType 环境类型
     * @return 描述符；若未注册返回 {@link Optional#empty()}
     */
    public Optional<ProviderDescriptor> find(EnvironmentType environmentType) {
        return Optional.ofNullable(registry.get(environmentType));
    }

    /**
     * 列出全部已注册 Provider（含禁用）。
     *
     * @return 不可变描述符列表
     */
    public List<ProviderDescriptor> listProviders() {
        return List.copyOf(registry.values());
    }

    /**
     * 列出全部已启用的 Provider。
     *
     * @return 不可变描述符列表
     */
    public List<ProviderDescriptor> listEnabledProviders() {
        return registry.values().stream()
                .filter(ProviderDescriptor::isEnabled)
                .toList();
    }

    /**
     * 列出全部已注册的环境类型。
     *
     * @return 环境类型集合
     */
    public Set<EnvironmentType> registeredEnvironments() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    /**
     * 判断指定环境是否已注册且启用。
     *
     * @param environmentType 环境类型
     * @return true 表示已注册且 enabled=true
     */
    public boolean isAvailable(EnvironmentType environmentType) {
        ProviderDescriptor descriptor = registry.get(environmentType);
        return descriptor != null && descriptor.isEnabled();
    }

    /**
     * 注册表大小（含禁用）。
     *
     * @return 已注册 Provider 数量
     */
    public int size() {
        return registry.size();
    }

    /**
     * 校验全部 7 种环境均已注册，用于启动时健康检查。
     *
     * @return 缺失的环境类型列表；若全部注册则返回空列表
     */
    public List<EnvironmentType> missingEnvironments() {
        List<EnvironmentType> missing = new ArrayList<>();
        for (EnvironmentType type : EnvironmentType.values()) {
            if (!registry.containsKey(type)) {
                missing.add(type);
            }
        }
        return missing;
    }
}