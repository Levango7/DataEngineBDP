package com.levango7.dataenginebdp.infra.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterEntity;
import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterInfo;
import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterRequest;
import com.levango7.dataenginebdp.infra.cloud.provider.CloudProvider;
import com.levango7.dataenginebdp.infra.cloud.repository.CloudClusterRepository;
import com.levango7.dataenginebdp.infra.cloud.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 云 Provider 路由服务。
 *
 * <p>统一管理三朵云的 {@link CloudProvider} 实例，按 {@code provider} 名称路由分发。
 * 协调 VM 创建 → 元数据持久化 → K8s 引导的完整流程。</p>
 *
 * <p>核心职责：</p>
 * <ol>
 *   <li>路由：根据 {@code provider} 参数选择对应的 {@link CloudProvider}</li>
 *   <li>持久化：将集群元数据写入 {@link CloudClusterRepository}（H2/PostgreSQL）</li>
 *   <li>编排：调用 {@link K8sBootstrapService} 在 VM 创建完成后执行 K8s 引导</li>
 *   <li>租户隔离：从 {@link TenantContext} 获取租户 ID，注入 workspaceId</li>
 * </ol>
 */
@Service
public class CloudProviderService {

    private static final Logger log = LoggerFactory.getLogger(CloudProviderService.class);

    private final Map<String, CloudProvider> providers;
    private final CloudClusterRepository repository;
    private final K8sBootstrapService k8sBootstrapService;
    private final ObjectMapper objectMapper;

    /**
     * 构造服务。
     *
     * <p>Spring 自动注入所有 {@link CloudProvider} 实现，按 {@link CloudProvider#name()} 建立 provider 路由表。</p>
     *
     * @param providerList       所有 CloudProvider 实现（华为云/阿里云/腾讯云）
     * @param repository         集群元数据 Repository
     * @param k8sBootstrapService K8s 引导服务
     * @param objectMapper       JSON 序列化器
     */
    public CloudProviderService(List<CloudProvider> providerList,
                                CloudClusterRepository repository,
                                K8sBootstrapService k8sBootstrapService,
                                ObjectMapper objectMapper) {
        this.providers = providerList.stream()
                .collect(Collectors.toUnmodifiableMap(CloudProvider::name, p -> p));
        this.repository = repository;
        this.k8sBootstrapService = k8sBootstrapService;
        this.objectMapper = objectMapper;
        log.info("CloudProviderService initialized with providers: {}", providers.keySet());
    }

    /**
     * 创建云集群。
     *
     * <p>流程：生成集群 ID → 调用 Provider 创建 VM → 持久化元数据 → 触发 K8s 引导。</p>
     *
     * @param providerName 云 provider 标识（huawei / ali / tencent）
     * @param request      集群创建请求
     * @return 集群信息
     * @throws IllegalArgumentException 不支持的 provider
     * @throws CloudProvider.CloudProviderException 云 API 调用失败
     */
    @Transactional
    public CloudClusterInfo createCluster(String providerName, CloudClusterRequest request) {
        CloudProvider provider = resolveProvider(providerName);
        String clusterId = UUID.randomUUID().toString();
        log.info("Creating cloud cluster: provider={}, clusterId={}, name={}, nodeCount={}",
                providerName, clusterId, request.getClusterName(), request.getNodeCount());

        // 1. 调用 Provider 创建 VM
        CloudClusterInfo info = provider.createVMs(clusterId, request);

        // 2. 持久化元数据
        CloudClusterEntity entity = CloudClusterEntity.builder()
                .id(clusterId)
                .clusterName(request.getClusterName())
                .provider(providerName)
                .workspaceId(request.getWorkspaceId())
                .status(info.getStatus())
                .nodeCount(request.getNodeCount())
                .k8sBootstrapStatus(info.getK8sBootstrapStatus())
                .nodesJson(serializeNodes(info.getNodes()))
                .build();
        repository.save(entity);

        // 3. 触发 K8s 引导（异步）
        if (request.isAutoBootstrapK8s()) {
            try {
                k8sBootstrapService.bootstrapAsync(clusterId, providerName, info);
            } catch (Exception e) {
                log.warn("K8s bootstrap trigger failed for cluster {} (will retry): {}", clusterId, e.getMessage());
            }
        }
        return info;
    }

    /**
     * 销毁云集群。
     *
     * @param providerName 云 provider 标识
     * @param clusterId    集群 ID
     * @return 销毁后的集群信息
     */
    @Transactional
    public CloudClusterInfo destroyCluster(String providerName, String clusterId) {
        CloudProvider provider = resolveProvider(providerName);
        log.info("Destroying cloud cluster: provider={}, clusterId={}", providerName, clusterId);

        CloudClusterInfo info = provider.destroyVMs(clusterId);

        // 更新元数据
        repository.findById(clusterId).ifPresent(entity -> {
            entity.setStatus("DELETED");
            entity.setNodesJson(serializeNodes(info.getNodes()));
            repository.save(entity);
        });
        return info;
    }

    /**
     * 启动云集群。
     */
    @Transactional
    public CloudClusterInfo startCluster(String providerName, String clusterId) {
        CloudProvider provider = resolveProvider(providerName);
        log.info("Starting cloud cluster: provider={}, clusterId={}", providerName, clusterId);
        CloudClusterInfo info = provider.startVMs(clusterId);
        repository.findById(clusterId).ifPresent(entity -> {
            entity.setStatus("RUNNING");
            repository.save(entity);
        });
        return info;
    }

    /**
     * 停止云集群。
     */
    @Transactional
    public CloudClusterInfo stopCluster(String providerName, String clusterId) {
        CloudProvider provider = resolveProvider(providerName);
        log.info("Stopping cloud cluster: provider={}, clusterId={}", providerName, clusterId);
        CloudClusterInfo info = provider.stopVMs(clusterId);
        repository.findById(clusterId).ifPresent(entity -> {
            entity.setStatus("STOPPED");
            repository.save(entity);
        });
        return info;
    }

    /**
     * 查询云集群。
     */
    public CloudClusterInfo getCluster(String providerName, String clusterId) {
        CloudProvider provider = resolveProvider(providerName);
        return provider.getVMInfo(clusterId);
    }

    /**
     * 扩缩容云集群。
     */
    @Transactional
    public CloudClusterInfo scaleCluster(String providerName, String clusterId, int targetNodeCount) {
        CloudProvider provider = resolveProvider(providerName);
        log.info("Scaling cloud cluster: provider={}, clusterId={}, target={}",
                providerName, clusterId, targetNodeCount);
        CloudClusterInfo info = provider.scaleVMs(clusterId, targetNodeCount);
        repository.findById(clusterId).ifPresent(entity -> {
            entity.setNodeCount(targetNodeCount);
            repository.save(entity);
        });
        return info;
    }

    /**
     * 列出指定 provider 的所有集群。
     */
    public List<CloudClusterInfo> listClusters(String providerName) {
        resolveProvider(providerName);
        return repository.findByProvider(providerName).stream()
                .map(this::entityToInfo)
                .toList();
    }

    /**
     * 列出指定 provider 与 workspace 的集群。
     */
    public List<CloudClusterInfo> listClustersByWorkspace(String providerName, String workspaceId) {
        resolveProvider(providerName);
        return repository.findByProviderAndWorkspaceId(providerName, workspaceId).stream()
                .map(this::entityToInfo)
                .toList();
    }

    /**
     * 列出所有支持的 provider。
     */
    public List<String> listSupportedProviders() {
        return List.copyOf(providers.keySet());
    }

    /**
     * 路由解析 provider。
     *
     * @throws IllegalArgumentException 不支持的 provider
     */
    private CloudProvider resolveProvider(String providerName) {
        CloudProvider provider = providers.get(providerName);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Unsupported cloud provider: " + providerName + ", supported: " + providers.keySet());
        }
        return provider;
    }

    /**
     * 序列化节点列表为 JSON。
     */
    private String serializeNodes(List<CloudClusterInfo.VMInfo> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(nodes);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize nodes: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 实体转 DTO。
     */
    private CloudClusterInfo entityToInfo(CloudClusterEntity entity) {
        List<CloudClusterInfo.VMInfo> nodes = new java.util.ArrayList<>();
        if (entity.getNodesJson() != null && !entity.getNodesJson().isEmpty()) {
            try {
                nodes = objectMapper.readValue(entity.getNodesJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, CloudClusterInfo.VMInfo.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize nodes for cluster {}: {}", entity.getId(), e.getMessage());
            }
        }
        return CloudClusterInfo.builder()
                .clusterId(entity.getId())
                .clusterName(entity.getClusterName())
                .provider(entity.getProvider())
                .workspaceId(entity.getWorkspaceId())
                .status(entity.getStatus())
                .nodes(nodes)
                .k8sApiServerEndpoint(entity.getK8sApiServerEndpoint())
                .k8sBootstrapStatus(entity.getK8sBootstrapStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .errorMessage(entity.getErrorMessage())
                .build();
    }
}