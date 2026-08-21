package com.levango7.dataenginebdp.federated.cluster;

import com.levango7.dataenginebdp.federated.governance.FederatedGovernanceView;
import com.levango7.dataenginebdp.federated.governance.FederatedLineageService;
import com.levango7.dataenginebdp.federated.governance.FederatedMetadataService;
import com.levango7.dataenginebdp.federated.governance.FederatedQualityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;


/**
 * 集群 Provider/Executor 自动装配配置。
 *
 * <p>根据 {@code federated.cluster.mode} 选择注入 mock 实现或真实集群实现：
 * <ul>
 *   <li>{@code mode=mock}（默认）：注入返回空数据的 mock 实现，用于本地开发与单元测试</li>
 *   <li>{@code mode=real}：注入 {@link RealClusterMetadataProvider}/
 *       {@link RealClusterLineageProvider}/{@link RealClusterQualityExecutor}，
 *       通过 Karmada REST API 与各集群 Catalog/Lineage/Quality API 交互</li>
 * </ul>
 *
 * <p>注入的 bean 会被 {@link FederatedMetadataService}/
 * {@link FederatedLineageService}/{@link FederatedQualityService} 通过构造函数消费。
 */
@Slf4j
@Configuration
public class ClusterProviderAutoConfiguration {

    /**
     * Mock 模式：元数据提供者（返回空列表）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "federated.cluster", name = "mode", havingValue = "mock", matchIfMissing = true)
    public FederatedMetadataService.ClusterMetadataProvider mockMetadataProvider() {
        log.info("ClusterMetadataProvider: using MOCK implementation");
        return clusterId -> Collections.emptyList();
    }

    /**
     * Mock 模式：血缘提供者（返回空列表）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "federated.cluster", name = "mode", havingValue = "mock", matchIfMissing = true)
    public FederatedLineageService.ClusterLineageProvider mockLineageProvider() {
        log.info("ClusterLineageProvider: using MOCK implementation");
        return clusterId -> Collections.emptyList();
    }

    /**
     * Mock 模式：质量执行器（返回空列表）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "federated.cluster", name = "mode", havingValue = "mock", matchIfMissing = true)
    public FederatedQualityService.ClusterQualityExecutor mockQualityExecutor() {
        log.info("ClusterQualityExecutor: using MOCK implementation");
        return (rule, clusterId) -> Collections.emptyList();
    }

    /**
     * Real 模式：元数据提供者。
     */
    @Bean
    @ConditionalOnProperty(prefix = "federated.cluster", name = "mode", havingValue = "real")
    public FederatedMetadataService.ClusterMetadataProvider realMetadataProvider(
            WebClient clusterWebClient, FederatedClusterProperties props) {
        log.info("ClusterMetadataProvider: using REAL implementation, karmadaApi={}", props.getKarmadaApi());
        return new RealClusterMetadataProvider(clusterWebClient, props);
    }

    /**
     * Real 模式：血缘提供者。
     */
    @Bean
    @ConditionalOnProperty(prefix = "federated.cluster", name = "mode", havingValue = "real")
    public FederatedLineageService.ClusterLineageProvider realLineageProvider(
            WebClient clusterWebClient, FederatedClusterProperties props) {
        log.info("ClusterLineageProvider: using REAL implementation");
        return new RealClusterLineageProvider(clusterWebClient, props);
    }

    /**
     * Real 模式：质量执行器。
     */
    @Bean
    @ConditionalOnProperty(prefix = "federated.cluster", name = "mode", havingValue = "real")
    public FederatedQualityService.ClusterQualityExecutor realQualityExecutor(
            WebClient clusterWebClient, FederatedClusterProperties props) {
        log.info("ClusterQualityExecutor: using REAL implementation");
        return new RealClusterQualityExecutor(clusterWebClient, props);
    }

    /**
     * 兜底元数据提供者：当 mode 既非 mock 也非 real 时使用，返回空列表。
     *
     * <p>此 bean 仅在主备 bean 均未生效时启用，避免容器启动失败。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            FederatedMetadataService.ClusterMetadataProvider.class)
    public FederatedMetadataService.ClusterMetadataProvider fallbackMetadataProvider() {
        log.warn("ClusterMetadataProvider: using FALLBACK (empty) implementation");
        return clusterId -> Collections.emptyList();
    }

    /**
     * 兜底血缘提供者。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            FederatedLineageService.ClusterLineageProvider.class)
    public FederatedLineageService.ClusterLineageProvider fallbackLineageProvider() {
        log.warn("ClusterLineageProvider: using FALLBACK (empty) implementation");
        return clusterId -> Collections.emptyList();
    }

    /**
     * 兜底质量执行器。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            FederatedQualityService.ClusterQualityExecutor.class)
    public FederatedQualityService.ClusterQualityExecutor fallbackQualityExecutor() {
        log.warn("ClusterQualityExecutor: using FALLBACK (empty) implementation");
        return (rule, clusterId) -> Collections.emptyList();
    }
}