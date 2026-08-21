package com.levango7.dataenginebdp.federated.cluster;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 联邦集群接入配置属性。
 *
 * <p>对应 application.yml 中 {@code federated.cluster} 前缀的配置段。
 * 用于控制 {@code ClusterMetadataProvider}/{@code ClusterLineageProvider}/
 * {@code ClusterQualityExecutor} 使用 mock 实现还是真实集群实现。
 *
 * <p>配置示例：
 * <pre>
 * federated:
 *   cluster:
 *     mode: real
 *     karmada-api: https://localhost:5443
 *     karmada-kubeconfig: /etc/karmada/kubeconfig
 *     connect-timeout: 5s
 *     response-timeout: 15s
 *     clusters:
 *       - name: cluster-a
 *         catalog-url: http://localhost:8090
 *         lineage-url: http://localhost:8090/api/v1/lineage
 *         quality-url: http://localhost:8090/api/v1/quality
 *       - name: cluster-b
 *         catalog-url: http://localhost:8091
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "federated.cluster")
public class FederatedClusterProperties {

    /** 运行模式：mock 或 real。 */
    private String mode = "mock";

    /** Karmada 控制面 API 端点（如 https://localhost:5443）。 */
    private String karmadaApi;

    /** Karmada kubeconfig 路径（可选，用于 mTLS 认证）。 */
    private String karmadaKubeconfig;

    /** 连接超时。 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 响应超时。 */
    private Duration responseTimeout = Duration.ofSeconds(15);

    /** 集群列表。 */
    private List<ClusterConfig> clusters = new ArrayList<>();

    /**
     * 单个集群配置。
     */
    @Data
    public static class ClusterConfig {
        /** 集群名（与 Karmada member cluster 名一致）。 */
        private String name;
        /** Catalog API 基础 URL（如 http://cluster-a:8090）。 */
        private String catalogUrl;
        /** Lineage API 基础 URL（如 http://cluster-a:8090/api/v1/lineage）。 */
        private String lineageUrl;
        /** Quality API 基础 URL（如 http://cluster-a:8090/api/v1/quality）。 */
        private String qualityUrl;
        /** 是否启用。 */
        private boolean enabled = true;
    }

    /**
     * 查找指定集群配置。
     *
     * @param name 集群名
     * @return 集群配置，不存在返回 null
     */
    public ClusterConfig findCluster(String name) {
        if (name == null || clusters == null) {
            return null;
        }
        for (ClusterConfig c : clusters) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    /**
     * 获取所有启用的集群名列表。
     *
     * @return 启用集群名列表
     */
    public List<String> getEnabledClusterNames() {
        List<String> names = new ArrayList<>();
        if (clusters == null) {
            return names;
        }
        for (ClusterConfig c : clusters) {
            if (c.isEnabled() && c.getName() != null) {
                names.add(c.getName());
            }
        }
        return names;
    }
}