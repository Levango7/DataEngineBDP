package com.shuqing.bigdata.infra.orchestrator.registry;

import com.shuqing.bigdata.infra.orchestrator.model.EnvironmentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 环境配置 Profile。
 *
 * <p>为每种 {@link EnvironmentType} 维护一组默认供应参数（CPU/内存/网络/存储/K8s 版本），
 * 供前端集群创建向导预填、供应编排层在请求缺省时补全。所有默认值可通过
 * {@code application.yml} 的 {@code app.orchestrator.profiles} 前缀覆盖。</p>
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * app:
 *   orchestrator:
 *     profiles:
 *       XINCHANG:
 *         default-cpu-cores: 16
 *         default-memory-gb: 64
 *         default-pod-cidr: 10.244.0.0/16
 * }</pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.orchestrator")
public class EnvironmentProfile {

    /**
     * 各环境默认配置，key 为 {@link EnvironmentType#name()}。
     * Spring Boot 自动绑定 yml 中 {@code profiles.XINCHANG} 等键。
     */
    private Map<String, ProfileDefaults> profiles = new java.util.HashMap<>();

    /**
     * 获取指定环境的默认配置；若未配置则返回该环境的内置默认值。
     *
     * @param type 环境类型
     * @return 默认配置（永不为 null）
     */
    public ProfileDefaults getOrDefault(EnvironmentType type) {
        ProfileDefaults configured = profiles.get(type.name());
        if (configured != null) {
            return configured;
        }
        return builtinDefaults(type);
    }

    /**
     * 列出全部环境类型的默认配置（合并 yml 覆盖与内置默认）。
     *
     * @return 环境 → 默认配置
     */
    public Map<EnvironmentType, ProfileDefaults> allProfiles() {
        Map<EnvironmentType, ProfileDefaults> result = new EnumMap<>(EnvironmentType.class);
        for (EnvironmentType type : EnvironmentType.values()) {
            result.put(type, getOrDefault(type));
        }
        return result;
    }

    /**
     * 内置的每种环境默认配置。
     *
     * <p>原则：</p>
     * <ul>
     *   <li>物理机环境（信创/裸金属）默认较大规格，因物理机资源充足</li>
     *   <li>公有云默认中等规格，平衡成本</li>
     *   <li>私有云默认中等规格</li>
     * </ul>
     *
     * @param type 环境类型
     * @return 内置默认配置
     */
    private ProfileDefaults builtinDefaults(EnvironmentType type) {
        return switch (type) {
            case XINCHANG -> ProfileDefaults.builder()
                    .defaultCpuCores(16)
                    .defaultMemoryGb(64)
                    .defaultDiskGb(500)
                    .defaultPodCidr("10.244.0.0/16")
                    .defaultServiceCidr("10.96.0.0/12")
                    .defaultK8sVersion("v1.28.9")
                    .defaultNetworkType("vlan")
                    .build();
            case BAREMETAL -> ProfileDefaults.builder()
                    .defaultCpuCores(32)
                    .defaultMemoryGb(128)
                    .defaultDiskGb(1000)
                    .defaultPodCidr("10.244.0.0/16")
                    .defaultServiceCidr("10.96.0.0/12")
                    .defaultK8sVersion("v1.29.2")
                    .defaultNetworkType("vlan")
                    .build();
            case CLOUD_HUAWEI, CLOUD_ALI, CLOUD_TENCENT -> ProfileDefaults.builder()
                    .defaultCpuCores(8)
                    .defaultMemoryGb(32)
                    .defaultDiskGb(200)
                    .defaultPodCidr("10.244.0.0/16")
                    .defaultServiceCidr("10.96.0.0/12")
                    .defaultK8sVersion("v1.28.9")
                    .defaultNetworkType("vpc")
                    .build();
            case PRIVATE_VSPHERE, PRIVATE_OPENSTACK -> ProfileDefaults.builder()
                    .defaultCpuCores(8)
                    .defaultMemoryGb(16)
                    .defaultDiskGb(200)
                    .defaultPodCidr("10.244.0.0/16")
                    .defaultServiceCidr("10.96.0.0/12")
                    .defaultK8sVersion("v1.28.9")
                    .defaultNetworkType("vlan")
                    .build();
        };
    }

    /**
     * 单个环境的默认供应参数。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProfileDefaults {

        /** 默认 CPU 核数。 */
        @Builder.Default
        private int defaultCpuCores = 8;

        /** 默认内存（GB）。 */
        @Builder.Default
        private int defaultMemoryGb = 32;

        /** 默认系统盘（GB）。 */
        @Builder.Default
        private int defaultDiskGb = 200;

        /** 默认 Pod CIDR。 */
        @Builder.Default
        private String defaultPodCidr = "10.244.0.0/16";

        /** 默认 Service CIDR。 */
        @Builder.Default
        private String defaultServiceCidr = "10.96.0.0/12";

        /** 默认 K8s 版本。 */
        @Builder.Default
        private String defaultK8sVersion = "v1.28.9";

        /** 默认网络类型：{@code vlan} / {@code vpc}。 */
        @Builder.Default
        private String defaultNetworkType = "vpc";

        /** 默认节点数。 */
        @Builder.Default
        private int defaultNodeCount = 3;
    }
}