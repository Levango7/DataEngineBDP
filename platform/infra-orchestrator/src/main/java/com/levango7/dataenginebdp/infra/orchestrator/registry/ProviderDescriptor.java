package com.levango7.dataenginebdp.infra.orchestrator.registry;

import com.levango7.dataenginebdp.infra.orchestrator.model.EnvironmentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Provider 描述符。
 *
 * <p>描述一个下游基础设施供应 Provider 的运行态信息，由
 * {@link ProviderRegistry} 在启动时加载并维护。每个描述符对应一种
 * {@link EnvironmentType}，并承载调用该 Provider 所需的全部连接信息。</p>
 *
 * <p>典型示例：</p>
 * <pre>{@code
 * ProviderDescriptor.builder()
 *     .environmentType(EnvironmentType.XINCHANG)
 *     .name("infra-provider-xinchang")
 *     .baseUrl("http://infra-provider-xinchang:8090")
 *     .healthEndpoint("/actuator/health")
 *     .enabled(true)
 *     .build();
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderDescriptor {

    /**
     * 环境类型，唯一标识该 Provider 服务的环境。
     */
    private EnvironmentType environmentType;

    /**
     * Provider 服务名称，用于日志、监控指标标签与服务发现。
     * 例如 {@code infra-provider-xinchang}。
     */
    private String name;

    /**
     * Provider 基础 URL，不含路径前缀。
     * 例如 {@code http://infra-provider-xinchang:8090}。
     */
    private String baseUrl;

    /**
     * 健康检查端点（相对路径），用于探活。
     * 默认 {@code /actuator/health}。
     */
    @Builder.Default
    private String healthEndpoint = "/actuator/health";

    /**
     * 是否启用。禁用的 Provider 在 {@link ProviderRegistry#lookup} 中被跳过。
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Provider 实现语言：{@code java} / {@code go}，仅用于监控展示。
     */
    @Builder.Default
    private String implementationLanguage = "java";

    /**
     * 拼接健康检查完整 URL。
     *
     * @return 健康检查 URL，如 {@code http://infra-provider-xinchang:8090/actuator/health}
     */
    public String getHealthUrl() {
        return stripTrailingSlash(baseUrl) + healthEndpoint;
    }

    /**
     * 拼接下游 Provider 的 REST API 基础路径 URL（不含 host:port 之后的部分由 EnvironmentType 决定）。
     *
     * @return REST 路径前缀完整 URL，如 {@code http://infra-provider-cloud:8092/api/v1/clusters/cloud/huawei}
     */
    public String getRestBaseUrl() {
        return stripTrailingSlash(baseUrl) + environmentType.getRestPathPrefix();
    }

    /**
     * 拼接指定集群 ID 的 REST URL。
     *
     * @param clusterId 集群 ID
     * @return 集群 URL，如 {@code http://.../api/v1/clusters/cloud/huawei/abc-123}
     */
    public String getClusterUrl(String clusterId) {
        return getRestBaseUrl() + "/" + clusterId;
    }

    /**
     * 拼接扩缩容 URL。
     *
     * @param clusterId 集群 ID
     * @return scale URL
     */
    public String getScaleUrl(String clusterId) {
        return getClusterUrl(clusterId) + "/scale";
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}