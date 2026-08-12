package com.levango7.dataenginebdp.infra.xinchang.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.infra.xinchang.model.ClusterCreateRequest;
import com.levango7.dataenginebdp.infra.xinchang.model.ClusterEntity;
import com.levango7.dataenginebdp.infra.xinchang.model.ClusterInfo;
import com.levango7.dataenginebdp.infra.xinchang.model.ClusterRepository;
import com.levango7.dataenginebdp.infra.xinchang.model.ClusterScaleRequest;
import com.levango7.dataenginebdp.infra.xinchang.model.XinchangNodeSpec;
import com.levango7.dataenginebdp.infra.xinchang.service.K8sBootstrapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 信创集群供应 Provider 实现。
 *
 * <p>支持：</p>
 * <ul>
 *   <li>CPU 架构：鲲鹏 920（aarch64）/ 海光 C86（x86_64）/ 飞腾 / 兆芯</li>
 *   <li>操作系统：麒麟 V10 / 统信 UOS</li>
 *   <li>带外管理：IPMI Redfish API（开机/关机/PXE 引导）</li>
 *   <li>自动化装机：PXE 网络启动</li>
 *   <li>K8s 初始化：kubeadm + SKE 定制配置</li>
 * </ul>
 *
 * <p>本实现为单进程同步流程的简化版，生产环境应改为异步任务 + 状态机驱动。</p>
 */
@Component
public class XinchangProvider implements ClusterProvider {

    private static final Logger log = LoggerFactory.getLogger(XinchangProvider.class);

    /** Provider 类型标识 */
    public static final String PROVIDER_TYPE = "xinchang";

    private final ClusterRepository clusterRepository;
    private final IpmiRedfishClient ipmiClient;
    private final K8sBootstrapService k8sBootstrapService;
    private final ObjectMapper objectMapper;

    /**
     * 构造 Provider。
     *
     * @param clusterRepository  集群元数据 Repository
     * @param ipmiClient         IPMI Redfish 客户端
     * @param k8sBootstrapService K8s 初始化服务
     * @param objectMapper       JSON 序列化器
     */
    public XinchangProvider(ClusterRepository clusterRepository,
                            IpmiRedfishClient ipmiClient,
                            K8sBootstrapService k8sBootstrapService,
                            ObjectMapper objectMapper) {
        this.clusterRepository = clusterRepository;
        this.ipmiClient = ipmiClient;
        this.k8sBootstrapService = k8sBootstrapService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public ClusterInfo createCluster(ClusterCreateRequest request) {
        String clusterId = UUID.randomUUID().toString();
        log.info("Creating xinchang cluster: id={} name={} tenant={} nodes={}",
                clusterId, request.getClusterName(), request.getTenantId(), request.getNodes().size());

        // 1. 持久化初始状态
        ClusterEntity entity = ClusterEntity.builder()
                .clusterId(clusterId)
                .clusterName(request.getClusterName())
                .tenantId(request.getTenantId())
                .k8sVersion(request.getK8sVersion())
                .status(ClusterInfo.Status.CREATING)
                .nodesJson(toJson(request.getNodes()))
                .metadataJson(toJson(Collections.emptyMap()))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        clusterRepository.save(entity);

        try {
            // 2. 校验节点规格：至少 1 个 control-plane
            validateNodes(request.getNodes());

            // 3. IPMI 开机 + PXE 引导（每台物理机）
            for (XinchangNodeSpec node : request.getNodes()) {
                log.info("Provisioning node: host={} cpu={} os={} bmc={} pxeMac={}",
                        node.getHostname(), node.getCpuArch(), node.getOsType(),
                        node.getBmcIp(), node.getPxeMac());
                ipmiClient.powerOnWithPxe(node);
            }

            // 4. K8s 集群初始化（kubeadm init + join）
            String controlPlaneEndpoint = k8sBootstrapService.bootstrap(clusterId, request);

            // 5. 更新状态为 RUNNING
            Map<String, String> metadata = new HashMap<>();
            metadata.put("provider", PROVIDER_TYPE);
            metadata.put("k8sVersion", request.getK8sVersion());
            metadata.put("podCidr", request.getPodCidr());
            metadata.put("serviceCidr", request.getServiceCidr());
            metadata.put("skeEnabled", String.valueOf(request.isSkeEnabled()));
            metadata.put("createdAt", Instant.now().toString());

            entity.setStatus(ClusterInfo.Status.RUNNING);
            entity.setControlPlaneEndpoint(controlPlaneEndpoint);
            entity.setMetadataJson(toJson(metadata));
            entity.setUpdatedAt(Instant.now());
            clusterRepository.save(entity);

            log.info("Xinchang cluster created: id={} endpoint={}", clusterId, controlPlaneEndpoint);
            return toClusterInfo(entity);
        } catch (Exception e) {
            log.error("Failed to create xinchang cluster id={}: {}", clusterId, e.getMessage(), e);
            entity.setStatus(ClusterInfo.Status.FAILED);
            entity.setErrorMessage(truncate(e.getMessage(), 4096));
            entity.setUpdatedAt(Instant.now());
            clusterRepository.save(entity);
            return toClusterInfo(entity);
        }
    }

    @Override
    public ClusterInfo destroyCluster(String clusterId) {
        log.info("Destroying xinchang cluster: id={}", clusterId);
        ClusterEntity entity = clusterRepository.findById(clusterId).orElse(null);
        if (entity == null) {
            log.warn("Cluster not found: id={}", clusterId);
            return null;
        }

        entity.setStatus(ClusterInfo.Status.DESTROYING);
        entity.setUpdatedAt(Instant.now());
        clusterRepository.save(entity);

        try {
            List<XinchangNodeSpec> nodes = fromJsonNodes(entity.getNodesJson());
            // 1. K8s 重置（kubeadm reset）
            k8sBootstrapService.teardown(clusterId, entity.getControlPlaneEndpoint());
            // 2. IPMI 关机
            for (XinchangNodeSpec node : nodes) {
                ipmiClient.powerOff(node);
            }
            entity.setStatus(ClusterInfo.Status.DESTROYED);
            entity.setUpdatedAt(Instant.now());
            clusterRepository.save(entity);
            log.info("Xinchang cluster destroyed: id={}", clusterId);
            return toClusterInfo(entity);
        } catch (Exception e) {
            log.error("Failed to destroy cluster id={}: {}", clusterId, e.getMessage(), e);
            entity.setStatus(ClusterInfo.Status.FAILED);
            entity.setErrorMessage(truncate(e.getMessage(), 4096));
            entity.setUpdatedAt(Instant.now());
            clusterRepository.save(entity);
            return toClusterInfo(entity);
        }
    }

    @Override
    public ClusterInfo scaleCluster(String clusterId, ClusterScaleRequest request) {
        log.info("Scaling xinchang cluster: id={} add={} remove={}",
                clusterId,
                request.getAddNodes() != null ? request.getAddNodes().size() : 0,
                request.getRemoveHostnames() != null ? request.getRemoveHostnames().size() : 0);
        ClusterEntity entity = clusterRepository.findById(clusterId).orElse(null);
        if (entity == null) {
            return null;
        }

        entity.setStatus(ClusterInfo.Status.SCALING);
        entity.setUpdatedAt(Instant.now());
        clusterRepository.save(entity);

        try {
            List<XinchangNodeSpec> nodes = new ArrayList<>(fromJsonNodes(entity.getNodesJson()));

            // 缩容
            if (request.getRemoveHostnames() != null && !request.getRemoveHostnames().isEmpty()) {
                for (String hostname : request.getRemoveHostnames()) {
                    k8sBootstrapService.drainAndRemoveNode(clusterId, entity.getControlPlaneEndpoint(), hostname);
                    nodes.stream()
                            .filter(n -> n.getHostname().equals(hostname))
                            .findFirst()
                            .ifPresent(node -> {
                                ipmiClient.powerOff(node);
                                nodes.remove(node);
                            });
                }
            }

            // 扩容
            if (request.getAddNodes() != null && !request.getAddNodes().isEmpty()) {
                for (XinchangNodeSpec node : request.getAddNodes()) {
                    ipmiClient.powerOnWithPxe(node);
                    k8sBootstrapService.joinWorker(clusterId, entity.getControlPlaneEndpoint(), node);
                    nodes.add(node);
                }
            }

            entity.setNodesJson(toJson(nodes));
            entity.setStatus(ClusterInfo.Status.RUNNING);
            entity.setUpdatedAt(Instant.now());
            clusterRepository.save(entity);
            return toClusterInfo(entity);
        } catch (Exception e) {
            log.error("Failed to scale cluster id={}: {}", clusterId, e.getMessage(), e);
            entity.setStatus(ClusterInfo.Status.FAILED);
            entity.setErrorMessage(truncate(e.getMessage(), 4096));
            entity.setUpdatedAt(Instant.now());
            clusterRepository.save(entity);
            return toClusterInfo(entity);
        }
    }

    @Override
    public ClusterInfo getClusterInfo(String clusterId) {
        return clusterRepository.findById(clusterId)
                .map(this::toClusterInfo)
                .orElse(null);
    }

    @Override
    public List<ClusterInfo> listClusters(String tenantId) {
        return clusterRepository.findByTenantId(tenantId).stream()
                .map(this::toClusterInfo)
                .toList();
    }

    private void validateNodes(List<XinchangNodeSpec> nodes) {
        boolean hasControlPlane = nodes.stream()
                .anyMatch(n -> "control-plane".equalsIgnoreCase(n.getRole()));
        if (!hasControlPlane) {
            throw new IllegalArgumentException("At least one control-plane node is required");
        }
    }

    private ClusterInfo toClusterInfo(ClusterEntity entity) {
        List<String> nodeStrings = fromJsonNodes(entity.getNodesJson()).stream()
                .map(n -> String.join("|",
                        n.getHostname(),
                        n.getRole(),
                        n.getCpuArch().name(),
                        n.getOsType().name(),
                        n.getBmcIp(),
                        n.getPxeMac(),
                        ipmiClient.getPowerState(n)))
                .toList();
        return ClusterInfo.builder()
                .clusterId(entity.getClusterId())
                .clusterName(entity.getClusterName())
                .tenantId(entity.getTenantId())
                .k8sVersion(entity.getK8sVersion())
                .status(entity.getStatus())
                .controlPlaneEndpoint(entity.getControlPlaneEndpoint())
                .nodes(nodeStrings)
                .metadata(fromJsonMap(entity.getMetadataJson()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .errorMessage(entity.getErrorMessage())
                .build();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    private List<XinchangNodeSpec> fromJsonNodes(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<XinchangNodeSpec>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize nodes JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<String, String> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize metadata JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}