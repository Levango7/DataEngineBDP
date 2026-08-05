package com.shuqing.bigdata.infra.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.infra.orchestrator.model.ClusterCreateRequest;
import com.shuqing.bigdata.infra.orchestrator.model.ClusterInfo;
import com.shuqing.bigdata.infra.orchestrator.model.ClusterScaleRequest;
import com.shuqing.bigdata.infra.orchestrator.model.EnvironmentType;
import com.shuqing.bigdata.infra.orchestrator.model.SupplyResult;
import com.shuqing.bigdata.infra.orchestrator.registry.EnvironmentProfile;
import com.shuqing.bigdata.infra.orchestrator.registry.ProviderDescriptor;
import com.shuqing.bigdata.infra.orchestrator.registry.ProviderRegistry;
import com.shuqing.bigdata.infra.orchestrator.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应流程编排核心 - L0.5 跨环境统一供给抽象的业务大脑。
 *
 * <p>对上接收统一的 {@link ClusterCreateRequest}（含 {@code environment} 字段），
 * 对下通过 {@link WebClient} 调用对应环境的 Provider REST API，并将异构响应归一化为
 * {@link ClusterInfo}。供应流程包含：</p>
 *
 * <ol>
 *   <li>Provider 路由：根据 {@code request.environment} 从 {@link ProviderRegistry} 查找 Provider</li>
 *   <li>请求转换：将统一请求转为下游 Provider 期望的 JSON（透传 + 环境特定参数）</li>
 *   <li>创建集群：POST 到下游 Provider</li>
 *   <li>轮询状态：GET 集群状态直到 ACTIVE 或超时</li>
 *   <li>响应归一化：将下游异构响应转为统一 {@link ClusterInfo}</li>
 *   <li>返回 {@link SupplyResult}：包含集群信息、耗时、事件</li>
 * </ol>
 *
 * <p>下游 Provider REST API 契约（编排层透传，不做语义转换）：</p>
 * <ul>
 *   <li>信创：{@code POST /api/v1/clusters/xinchang}</li>
 *   <li>裸金属：{@code POST /api/v1/clusters/baremetal}</li>
 *   <li>公有云：{@code POST /api/v1/clusters/cloud/{provider}}</li>
 *   <li>私有云：{@code POST /api/v1/clusters/private/{provider}}</li>
 * </ul>
 */
@Service
public class SupplyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SupplyOrchestrator.class);

    private final ProviderRegistry registry;
    private final EnvironmentProfile environmentProfile;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /** 集群状态轮询最大次数。 */
    @Value("${app.orchestrator.poll.max-attempts:60}")
    private int maxPollAttempts;

    /** 集群状态轮询间隔（毫秒）。 */
    @Value("${app.orchestrator.poll.interval-ms:2000}")
    private long pollIntervalMs;

    /** 是否启用轮询等待集群 ACTIVE（false 则创建后立即返回）。 */
    @Value("${app.orchestrator.poll.enabled:true}")
    private boolean pollEnabled;

    /**
     * 构造编排器。
     *
     * @param registry           Provider 注册表
     * @param environmentProfile 环境配置 Profile
     * @param webClient          WebClient（共享实例）
     * @param objectMapper       JSON 序列化器
     */
    public SupplyOrchestrator(ProviderRegistry registry,
                              EnvironmentProfile environmentProfile,
                              WebClient webClient,
                              ObjectMapper objectMapper) {
        this.registry = registry;
        this.environmentProfile = environmentProfile;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 统一创建集群入口。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>查找 Provider</li>
     *   <li>构造下游请求体</li>
     *   <li>POST 创建</li>
     *   <li>（可选）轮询直到 ACTIVE</li>
     *   <li>归一化响应</li>
     * </ol>
     *
     * @param request 统一创建请求
     * @return 供应结果
     */
    public SupplyResult createCluster(ClusterCreateRequest request) {
        Instant startedAt = Instant.now();
        List<String> events = new ArrayList<>();
        EnvironmentType env = request.getEnvironment();

        // 1. 查找 Provider
        ProviderDescriptor descriptor;
        try {
            descriptor = registry.lookup(env);
        } catch (IllegalArgumentException e) {
            events.add("provider-lookup-failed");
            return SupplyResult.failure(env, startedAt, events, null, null, e.getMessage());
        }
        events.add("provider-selected:" + descriptor.getName());
        log.info("createCluster env={} clusterName={} provider={} url={}",
                env, request.getClusterName(), descriptor.getName(), descriptor.getBaseUrl());

        // 注入 JWT 上下文的 tenantId
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            request.setTenantId(tenantId);
        }

        // 2. 构造下游请求体
        Map<String, Object> providerRequest = buildProviderRequest(request, env);
        events.add("request-transformed");

        // 3. POST 创建
        String createUrl = descriptor.getRestBaseUrl();
        JsonNode createResponse;
        try {
            createResponse = webClient.post()
                    .uri(createUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(providerRequest)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException e) {
            events.add("provider-create-failed:" + e.getStatusCode());
            log.error("Provider create failed: env={} status={} body={}",
                    env, e.getStatusCode(), e.getResponseBodyAsString());
            return SupplyResult.failure(env, startedAt, events,
                    descriptor.getName(), descriptor.getBaseUrl(),
                    "provider returned " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            events.add("provider-create-error");
            log.error("Provider create error: env={}", env, e);
            return SupplyResult.failure(env, startedAt, events,
                    descriptor.getName(), descriptor.getBaseUrl(), e.getMessage());
        }
        events.add("provider-create-accepted");

        // 4. 解析集群 ID
        String clusterId = extractClusterId(createResponse);
        if (clusterId == null) {
            events.add("cluster-id-missing");
            return SupplyResult.failure(env, startedAt, events,
                    descriptor.getName(), descriptor.getBaseUrl(),
                    "provider response missing clusterId: " + createResponse);
        }
        events.add("cluster-id:" + clusterId);

        // 5. 轮询直到 ACTIVE
        ClusterInfo clusterInfo = normalizeClusterInfo(createResponse, env, request);
        if (pollEnabled && clusterInfo.getStatus() != ClusterInfo.Status.ACTIVE) {
            events.add("poll-start");
            clusterInfo = pollUntilActive(descriptor, clusterId, env, request, events);
            events.add("poll-end:status=" + clusterInfo.getStatus());
        }

        // 6. 构造结果
        if (clusterInfo.getStatus() == ClusterInfo.Status.ACTIVE
                || clusterInfo.getStatus() == ClusterInfo.Status.CREATING) {
            events.add("supply-completed:status=" + clusterInfo.getStatus());
            return SupplyResult.success(clusterInfo, startedAt, events,
                    descriptor.getName(), descriptor.getBaseUrl());
        } else {
            events.add("supply-failed:status=" + clusterInfo.getStatus());
            return SupplyResult.failure(env, startedAt, events,
                    descriptor.getName(), descriptor.getBaseUrl(),
                    "cluster ended in status " + clusterInfo.getStatus());
        }
    }

    /**
     * 销毁集群。
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 集群信息（DESTROYED）
     */
    public ClusterInfo destroyCluster(EnvironmentType environment, String clusterId) {
        ProviderDescriptor descriptor = registry.lookup(environment);
        log.info("destroyCluster env={} clusterId={} provider={}", environment, clusterId, descriptor.getName());

        String url = descriptor.getClusterUrl(clusterId);
        JsonNode response = webClient.delete()
                .uri(url)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        ClusterCreateRequest placeholder = ClusterCreateRequest.builder()
                .environment(environment).clusterName("").tenantId("").nodes(List.of()).build();
        return normalizeClusterInfo(response, environment, placeholder);
    }

    /**
     * 查询集群信息。
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 集群信息
     */
    public ClusterInfo getClusterInfo(EnvironmentType environment, String clusterId) {
        ProviderDescriptor descriptor = registry.lookup(environment);
        log.debug("getClusterInfo env={} clusterId={} provider={}", environment, clusterId, descriptor.getName());

        String url = descriptor.getClusterUrl(clusterId);
        JsonNode response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        ClusterCreateRequest placeholder = ClusterCreateRequest.builder()
                .environment(environment).clusterName("").tenantId("").nodes(List.of()).build();
        return normalizeClusterInfo(response, environment, placeholder);
    }

    /**
     * 扩缩容集群。
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @param scaleReq    扩缩容请求
     * @return 集群信息
     */
    public ClusterInfo scaleCluster(EnvironmentType environment, String clusterId, ClusterScaleRequest scaleReq) {
        ProviderDescriptor descriptor = registry.lookup(environment);
        log.info("scaleCluster env={} clusterId={} target={} provider={}",
                environment, clusterId, scaleReq.getTargetNodeCount(), descriptor.getName());

        Map<String, Object> body = new HashMap<>();
        body.put("targetNodeCount", scaleReq.getTargetNodeCount());
        if (scaleReq.getNodeSpec() != null) {
            body.put("nodeSpec", scaleReq.getNodeSpec());
        }

        String url = descriptor.getScaleUrl(clusterId);
        JsonNode response = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        ClusterCreateRequest placeholder = ClusterCreateRequest.builder()
                .environment(environment).clusterName("").tenantId("").nodes(List.of()).build();
        return normalizeClusterInfo(response, environment, placeholder);
    }

    /**
     * 列出指定环境的全部集群。
     *
     * @param environment 环境类型
     * @return 集群列表
     */
    public List<ClusterInfo> listClusters(EnvironmentType environment) {
        ProviderDescriptor descriptor = registry.lookup(environment);
        log.debug("listClusters env={} provider={}", environment, descriptor.getName());

        String url = descriptor.getRestBaseUrl();
        JsonNode response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        List<ClusterInfo> result = new ArrayList<>();
        if (response != null && response.isArray()) {
            ClusterCreateRequest placeholder = ClusterCreateRequest.builder()
                    .environment(environment).clusterName("").tenantId("").nodes(List.of()).build();
            for (JsonNode item : response) {
                result.add(normalizeClusterInfo(item, environment, placeholder));
            }
        }
        return result;
    }

    /**
     * 列出全部环境的全部集群（跨环境聚合）。
     *
     * @return 集群列表
     */
    public List<ClusterInfo> listAllClusters() {
        List<ClusterInfo> all = new ArrayList<>();
        for (EnvironmentType env : registry.registeredEnvironments()) {
            if (!registry.isAvailable(env)) {
                continue;
            }
            try {
                all.addAll(listClusters(env));
            } catch (Exception e) {
                log.warn("listClusters failed for env={}: {}", env, e.getMessage());
            }
        }
        return all;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构造下游 Provider 期望的请求体。
     *
     * <p>策略：将统一请求字段透传，并合并 {@code providerParams} 中的环境特定参数。
     * 各 Provider 的请求体字段名大致对齐（clusterName/tenantId/k8sVersion/podCidr/serviceCidr/nodes），
     * 编排层仅做透传，不做语义转换。</p>
     *
     * @param request 统一请求
     * @param env     环境类型
     * @return 下游请求体 Map
     */
    private Map<String, Object> buildProviderRequest(ClusterCreateRequest request, EnvironmentType env) {
        Map<String, Object> body = new HashMap<>();
        body.put("clusterName", request.getClusterName());
        body.put("tenantId", request.getTenantId());
        body.put("k8sVersion", request.getK8sVersion());
        body.put("podCidr", request.getPodCidr());
        body.put("serviceCidr", request.getServiceCidr());
        body.put("description", request.getDescription());

        // 节点规格
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (ClusterCreateRequest.NodeSpec node : request.getNodes()) {
            Map<String, Object> nodeMap = new HashMap<>();
            nodeMap.put("role", node.getRole());
            nodeMap.put("count", node.getCount());
            nodeMap.put("cpuCores", node.getCpuCores());
            nodeMap.put("memoryGb", node.getMemoryGb());
            nodeMap.put("diskGb", node.getDiskGb());
            if (node.getCpuArch() != null) {
                nodeMap.put("cpuArch", node.getCpuArch());
            }
            if (node.getOsType() != null) {
                nodeMap.put("osType", node.getOsType());
            }
            if (node.getBmcIp() != null) {
                nodeMap.put("bmcIp", node.getBmcIp());
            }
            if (node.getPxeMac() != null) {
                nodeMap.put("pxeMac", node.getPxeMac());
            }
            if (node.getInstanceType() != null) {
                nodeMap.put("instanceType", node.getInstanceType());
            }
            if (node.getZone() != null) {
                nodeMap.put("zone", node.getZone());
            }
            nodes.add(nodeMap);
        }
        body.put("nodes", nodes);

        // 信创环境透传 skeEnabled
        if (env == EnvironmentType.XINCHANG) {
            body.put("skeEnabled", request.isSkeEnabled());
        }

        // 合并 providerParams（环境特定参数）
        if (request.getProviderParams() != null) {
            body.putAll(request.getProviderParams());
        }

        return body;
    }

    /**
     * 从下游响应中提取集群 ID。
     *
     * <p>尝试多种常见字段名：{@code clusterId} / {@code id} / {@code cluster_id}。</p>
     *
     * @param response 下游响应
     * @return 集群 ID；若不存在返回 null
     */
    private String extractClusterId(JsonNode response) {
        if (response == null) {
            return null;
        }
        for (String field : List.of("clusterId", "id", "cluster_id")) {
            JsonNode node = response.get(field);
            if (node != null && !node.isNull()) {
                return node.asText();
            }
        }
        return null;
    }

    /**
     * 将下游异构响应归一化为统一 {@link ClusterInfo}。
     *
     * @param response 下游响应
     * @param env      环境类型
     * @param request  原始请求（用于补充 clusterName/tenantId）
     * @return 统一集群信息
     */
    private ClusterInfo normalizeClusterInfo(JsonNode response, EnvironmentType env,
                                             ClusterCreateRequest request) {
        if (response == null) {
            return ClusterInfo.builder()
                    .environment(env)
                    .clusterName(request.getClusterName())
                    .tenantId(request.getTenantId())
                    .status(ClusterInfo.Status.UNKNOWN)
                    .build();
        }

        String clusterId = extractClusterId(response);
        String clusterName = textField(response, "clusterName", "name", "cluster_name");
        String tenantId = textField(response, "tenantId", "tenant_id");
        String k8sVersion = textField(response, "k8sVersion", "k8s_version", "kubernetesVersion");
        String statusRaw = textField(response, "status", "state", "clusterStatus");
        String controlPlaneEndpoint = textField(response, "controlPlaneEndpoint", "controlPlaneEndpoint",
                "apiServerEndpoint", "endpoint");
        String errorMessage = textField(response, "errorMessage", "error", "errorMsg");

        // 节点列表
        List<String> nodes = new ArrayList<>();
        JsonNode nodesNode = response.get("nodes");
        if (nodesNode != null && nodesNode.isArray()) {
            for (JsonNode n : nodesNode) {
                nodes.add(n.toString());
            }
        }

        // 元数据
        Map<String, String> metadata = new HashMap<>();
        JsonNode metaNode = response.get("metadata");
        if (metaNode != null && metaNode.isObject()) {
            metaNode.fields().forEachRemaining(entry ->
                    metadata.put(entry.getKey(), entry.getValue().asText()));
        }

        return ClusterInfo.builder()
                .clusterId(clusterId)
                .clusterName(clusterName != null ? clusterName : request.getClusterName())
                .tenantId(tenantId != null ? tenantId : request.getTenantId())
                .environment(env)
                .k8sVersion(k8sVersion)
                .status(ClusterInfo.normalizeStatus(statusRaw))
                .controlPlaneEndpoint(controlPlaneEndpoint)
                .nodes(nodes)
                .metadata(metadata)
                .errorMessage(errorMessage)
                .createdAt(instantField(response, "createdAt", "created_at", "createTime"))
                .updatedAt(instantField(response, "updatedAt", "updated_at", "updateTime"))
                .build();
    }

    /**
     * 轮询集群状态直到 ACTIVE 或超时。
     *
     * @param descriptor Provider 描述符
     * @param clusterId  集群 ID
     * @param env        环境类型
     * @param request    原始请求
     * @param events     事件列表
     * @return 最终集群信息
     */
    private ClusterInfo pollUntilActive(ProviderDescriptor descriptor, String clusterId,
                                        EnvironmentType env, ClusterCreateRequest request,
                                        List<String> events) {
        String url = descriptor.getClusterUrl(clusterId);
        ClusterInfo current = null;

        for (int i = 0; i < maxPollAttempts; i++) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                events.add("poll-interrupted");
                break;
            }

            try {
                JsonNode response = webClient.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();
                current = normalizeClusterInfo(response, env, request);

                if (current.getStatus() == ClusterInfo.Status.ACTIVE) {
                    events.add("poll-active:attempt=" + (i + 1));
                    return current;
                }
                if (current.getStatus() == ClusterInfo.Status.FAILED
                        || current.getStatus() == ClusterInfo.Status.DESTROYED) {
                    events.add("poll-terminal:attempt=" + (i + 1) + ",status=" + current.getStatus());
                    return current;
                }
            } catch (Exception e) {
                log.warn("poll attempt {} failed for cluster {}: {}", i + 1, clusterId, e.getMessage());
                events.add("poll-error:attempt=" + (i + 1));
            }
        }

        events.add("poll-timeout:attempts=" + maxPollAttempts);
        return current != null ? current : ClusterInfo.builder()
                .clusterId(clusterId)
                .environment(env)
                .status(ClusterInfo.Status.UNKNOWN)
                .build();
    }

    /**
     * 从 JsonNode 中按候选字段名顺序提取文本值。
     */
    private String textField(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child != null && !child.isNull()) {
                return child.asText();
            }
        }
        return null;
    }

    /**
     * 从 JsonNode 中按候选字段名顺序提取时间戳。
     */
    private Instant instantField(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child != null && !child.isNull()) {
                try {
                    return Instant.parse(child.asText());
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}