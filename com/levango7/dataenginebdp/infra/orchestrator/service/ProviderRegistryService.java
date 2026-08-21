package com.shuqing.bigdata.infra.orchestrator.service;

import com.shuqing.bigdata.infra.orchestrator.model.EnvironmentType;
import com.shuqing.bigdata.infra.orchestrator.registry.EnvironmentProfile;
import com.shuqing.bigdata.infra.orchestrator.registry.ProviderDescriptor;
import com.shuqing.bigdata.infra.orchestrator.registry.ProviderRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider 注册与发现服务。
 *
 * <p>在应用启动时（{@link PostConstruct}）根据 {@code application.yml} 配置
 * 自动注册 7 种环境的 Provider 描述符到 {@link ProviderRegistry}。
 * 支持通过环境变量覆盖默认 baseUrl，便于在不同部署环境（开发/测试/生产）切换 Provider 地址。</p>
 *
 * <p>配置项（{@code app.orchestrator.providers} 前缀）：</p>
 * <pre>{@code
 * app:
 *   orchestrator:
 *     providers:
 *       xinchang:
 *         base-url: http://infra-provider-xinchang:8090
 *         enabled: true
 *       baremetal:
 *         base-url: http://infra-provider-baremetal:8091
 * }</pre>
 */
@Service
public class ProviderRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistryService.class);

    private final ProviderRegistry registry;
    private final EnvironmentProfile environmentProfile;

    /** 信创 Provider baseUrl。 */
    @Value("${app.orchestrator.providers.xinchang.base-url:http://infra-provider-xinchang:8090}")
    private String xinchangBaseUrl;
    @Value("${app.orchestrator.providers.xinchang.enabled:true}")
    private boolean xinchangEnabled;

    /** 裸金属 Provider baseUrl。 */
    @Value("${app.orchestrator.providers.baremetal.base-url:http://infra-provider-baremetal:8091}")
    private String baremetalBaseUrl;
    @Value("${app.orchestrator.providers.baremetal.enabled:true}")
    private boolean baremetalEnabled;

    /** 公有云 Provider baseUrl（华为/阿里/腾讯共用）。 */
    @Value("${app.orchestrator.providers.cloud.base-url:http://infra-provider-cloud:8092}")
    private String cloudBaseUrl;
    @Value("${app.orchestrator.providers.cloud.enabled:true}")
    private boolean cloudEnabled;

    /** 私有云 Provider baseUrl（vSphere/OpenStack 共用）。 */
    @Value("${app.orchestrator.providers.private.base-url:http://infra-provider-private:8093}")
    private String privateBaseUrl;
    @Value("${app.orchestrator.providers.private.enabled:true}")
    private boolean privateEnabled;

    /**
     * 构造服务。
     *
     * @param registry           Provider 注册表
     * @param environmentProfile 环境配置 Profile
     */
    public ProviderRegistryService(ProviderRegistry registry, EnvironmentProfile environmentProfile) {
        this.registry = registry;
        this.environmentProfile = environmentProfile;
    }

    /**
     * 启动时自动注册全部 Provider。
     */
    @PostConstruct
    public void autoRegisterProviders() {
        log.info("Auto-registering providers from configuration...");

        List<ProviderDescriptor> descriptors = buildDescriptors();
        for (ProviderDescriptor descriptor : descriptors) {
            registry.register(descriptor);
        }

        List<EnvironmentType> missing = registry.missingEnvironments();
        if (missing.isEmpty()) {
            log.info("All 7 environment providers registered successfully");
        } else {
            log.warn("Missing providers for environments: {}", missing);
        }
    }

    /**
     * 根据配置构造 7 种环境的 Provider 描述符。
     *
     * @return 描述符列表
     */
    private List<ProviderDescriptor> buildDescriptors() {
        List<ProviderDescriptor> descriptors = new ArrayList<>();

        // 信创
        descriptors.add(ProviderDescriptor.builder()
                .environmentType(EnvironmentType.XINCHANG)
                .name("infra-provider-xinchang")
                .baseUrl(xinchangBaseUrl)
                .enabled(xinchangEnabled)
                .implementationLanguage("java")
                .build());

        // 裸金属
        descriptors.add(ProviderDescriptor.builder()
                .environmentType(EnvironmentType.BAREMETAL)
                .name("infra-provider-baremetal")
                .baseUrl(baremetalBaseUrl)
                .enabled(baremetalEnabled)
                .implementationLanguage("go")
                .build());

        // 公有云 - 华为
        descriptors.add(ProviderDescriptor.builder()
                .environmentType(EnvironmentType.CLOUD_HUAWEI)
                .name("infra-provider-cloud-huawei")
                .baseUrl(cloudBaseUrl)
                .enabled(cloudEnabled)
                .implementationLanguage("java")
                .build());

        // 公有云 - 阿里
        descriptors.add(ProviderDescriptor.builder()
                .environmentType(EnvironmentType.CLOUD_ALI)
                .name("infra-provider-cloud-ali")
                .baseUrl(cloudBaseUrl)
                .enabled(cloudEnabled)
                .implementationLanguage("java")
                .build());

        // 公有云 - 腾讯
        descriptors.add(ProviderDescriptor.builder()
                .environmentType(EnvironmentType.CLOUD_TENCENT)
                .name("infra-provider-cloud-tencent")
                .baseUrl(cloudBaseUrl)
                .enabled(cloudEnabled)
                .implementationLanguage("java")
                .build());

        // 私有云 - vSphere
        descriptors.add(ProviderDescriptor.builder()
                .environmentType(EnvironmentType.PRIVATE_VSPHERE)
                .name("infra-provider-private-vsphere")
                .baseUrl(privateBaseUrl)
                .enabled(privateEnabled)
                .implementationLanguage("java")
                .build());

        // 私有云 - OpenStack
        descriptors.add(ProviderDescriptor.builder()
                .environmentType(EnvironmentType.PRIVATE_OPENSTACK)
                .name("infra-provider-private-openstack")
                .baseUrl(privateBaseUrl)
                .enabled(privateEnabled)
                .implementationLanguage("java")
                .build());

        return descriptors;
    }

    /**
     * 列出全部已注册 Provider。
     *
     * @return Provider 描述符列表
     */
    public List<ProviderDescriptor> listProviders() {
        return registry.listProviders();
    }

    /**
     * 列出全部已启用 Provider。
     *
     * @return Provider 描述符列表
     */
    public List<ProviderDescriptor> listEnabledProviders() {
        return registry.listEnabledProviders();
    }

    /**
     * 获取环境配置 Profile。
     *
     * @return EnvironmentProfile
     */
    public EnvironmentProfile getEnvironmentProfile() {
        return environmentProfile;
    }
}