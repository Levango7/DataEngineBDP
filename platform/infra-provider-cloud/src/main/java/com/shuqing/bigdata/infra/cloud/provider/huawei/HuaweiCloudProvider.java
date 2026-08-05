package com.shuqing.bigdata.infra.cloud.provider.huawei;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.http.HttpConfig;
import com.huaweicloud.sdk.core.region.Region;
import com.huaweicloud.sdk.ecs.v2.EcsClient;
import com.huaweicloud.sdk.ecs.v2.region.EcsRegion;
import com.huaweicloud.sdk.ecs.v2.model.BatchStartServersOption;
import com.huaweicloud.sdk.ecs.v2.model.BatchStartServersRequest;
import com.huaweicloud.sdk.ecs.v2.model.BatchStartServersRequestBody;
import com.huaweicloud.sdk.ecs.v2.model.BatchStartServersResponse;
import com.huaweicloud.sdk.ecs.v2.model.BatchStopServersOption;
import com.huaweicloud.sdk.ecs.v2.model.BatchStopServersRequest;
import com.huaweicloud.sdk.ecs.v2.model.BatchStopServersRequestBody;
import com.huaweicloud.sdk.ecs.v2.model.BatchStopServersResponse;
import com.huaweicloud.sdk.ecs.v2.model.CreatePostPaidServersRequest;
import com.huaweicloud.sdk.ecs.v2.model.CreatePostPaidServersRequestBody;
import com.huaweicloud.sdk.ecs.v2.model.CreatePostPaidServersResponse;
import com.huaweicloud.sdk.ecs.v2.model.DeleteServersRequest;
import com.huaweicloud.sdk.ecs.v2.model.DeleteServersRequestBody;
import com.huaweicloud.sdk.ecs.v2.model.DeleteServersResponse;
import com.huaweicloud.sdk.ecs.v2.model.PostPaidServer;
import com.huaweicloud.sdk.ecs.v2.model.PostPaidServerEip;
import com.huaweicloud.sdk.ecs.v2.model.PostPaidServerEipBandwidth;
import com.huaweicloud.sdk.ecs.v2.model.PostPaidServerNic;
import com.huaweicloud.sdk.ecs.v2.model.PostPaidServerPublicip;
import com.huaweicloud.sdk.ecs.v2.model.PostPaidServerRootVolume;
import com.huaweicloud.sdk.ecs.v2.model.PostPaidServerSecurityGroup;
import com.huaweicloud.sdk.ecs.v2.model.ServerId;
import com.huaweicloud.sdk.ecs.v2.model.ShowServerRequest;
import com.huaweicloud.sdk.ecs.v2.model.ShowServerResponse;
import com.shuqing.bigdata.infra.cloud.model.CloudClusterEntity;
import com.shuqing.bigdata.infra.cloud.model.CloudClusterInfo;
import com.shuqing.bigdata.infra.cloud.model.CloudClusterRequest;
import com.shuqing.bigdata.infra.cloud.model.VMSpec;
import com.shuqing.bigdata.infra.cloud.provider.CloudProvider;
import com.shuqing.bigdata.infra.cloud.repository.CloudClusterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 华为云 ECS Provider 实现。
 *
 * <p>使用华为云 Java SDK（{@code com.huaweicloud.sdk.ecs.v2}）封装 VM 生命周期：
 * 创建 ECS 实例（按量计费 PostPaid）→ 配置安全组 → 分配 EIP → 返回 VM 信息。</p>
 *
 * <p>SDK 文档：https://github.com/huaweicloud/huaweicloud-sdk-java-v3</p>
 *
 * <p>注意：3.1.x 版本 SDK 提供 v2 包（{@code com.huaweicloud.sdk.ecs.v2}），
 * 包含 {@link PostPaidServer}（按量计费）与 PrePaidServer（包年包月）两种创建模式。
 * 本实现选用 PostPaidServer 以适配云原生弹性伸缩场景，通过 {@code count} 字段一次创建多台。</p>
 */
@Component
public class HuaweiCloudProvider implements CloudProvider {

    private static final Logger log = LoggerFactory.getLogger(HuaweiCloudProvider.class);

    private static final String PROVIDER_NAME = "huawei";

    private final CloudClusterRepository repository;
    private final ObjectMapper objectMapper;
    private final EcsClient ecsClient;

    /**
     * 构造华为云 Provider。
     *
     * <p>从 application.yml 读取 AK/SK/region，构建 ECS Client。
     * 凭证通过环境变量 {@code HUAWEI_AK} / {@code HUAWEI_SK} 注入，禁止明文提交。</p>
     *
     * @param repository 集群元数据 Repository
     * @param objectMapper JSON 序列化器
     * @param ak       华为云 Access Key
     * @param sk       华为云 Secret Key
     * @param region   华为云 region（如 cn-north-4）
     */
    public HuaweiCloudProvider(CloudClusterRepository repository,
                               ObjectMapper objectMapper,
                               @Value("${app.cloud.huawei.ak:}") String ak,
                               @Value("${app.cloud.huawei.sk:}") String sk,
                               @Value("${app.cloud.huawei.region:cn-north-4}") String region) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        // 凭证缺失时仍允许 Spring 启动（便于本地开发/单元测试），实际调用时再抛异常
        EcsClient client = null;
        if (ak != null && !ak.isEmpty() && sk != null && !sk.isEmpty()) {
            try {
                BasicCredentials credentials = new BasicCredentials()
                        .withAk(ak)
                        .withSk(sk);
                Region resolvedRegion = EcsRegion.valueOf(region);
                client = EcsClient.newBuilder()
                        .withCredential(credentials)
                        .withRegion(resolvedRegion)
                        .withHttpConfig(HttpConfig.getDefaultHttpConfig())
                        .build();
            } catch (Exception e) {
                log.error("Failed to build Huawei ECS client for region {}: {}", region, e.getMessage());
            }
        } else {
            log.warn("Huawei credentials not configured (HUAWEI_AK/HUAWEI_SK empty); ECS calls will fail at runtime");
        }
        this.ecsClient = client;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public CloudClusterInfo createVMs(String clusterId, CloudClusterRequest request) {
        log.info("Creating {} ECS instances for cluster {} on Huawei Cloud", request.getNodeCount(), clusterId);
        ensureClient();

        VMSpec spec = request.getVmSpec();
        PostPaidServer server = buildPostPaidServer(clusterId, request, spec);

        CreatePostPaidServersRequestBody body = new CreatePostPaidServersRequestBody()
                .withServer(server);
        CreatePostPaidServersRequest createRequest = new CreatePostPaidServersRequest().withBody(body);

        try {
            CreatePostPaidServersResponse response = ecsClient.createPostPaidServers(createRequest);
            int serverCount = response.getServerIds() != null ? response.getServerIds().size() : 0;
            log.info("Huawei ECS create job submitted: jobId={}, serverCount={}",
                    response.getJobId(), serverCount);
            return buildClusterInfoFromCreate(clusterId, request, response);
        } catch (Exception e) {
            log.error("Failed to create ECS on Huawei Cloud for cluster {}", clusterId, e);
            throw new CloudProviderException("Huawei ECS create failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CloudClusterInfo destroyVMs(String clusterId) {
        log.info("Destroying ECS instances for cluster {} on Huawei Cloud", clusterId);
        ensureClient();

        CloudClusterEntity entity = repository.findById(clusterId)
                .orElseThrow(() -> new CloudProviderException("Cluster not found: " + clusterId));
        List<CloudClusterInfo.VMInfo> nodes = deserializeNodes(entity.getNodesJson());

        List<ServerId> serverIds = new ArrayList<>();
        for (CloudClusterInfo.VMInfo vm : nodes) {
            serverIds.add(new ServerId().withId(vm.getInstanceId()));
        }
        DeleteServersRequestBody body = new DeleteServersRequestBody()
                .withServers(serverIds)
                .withDeletePublicip(true)
                .withDeleteVolume(true);
        DeleteServersRequest deleteRequest = new DeleteServersRequest().withBody(body);

        try {
            DeleteServersResponse response = ecsClient.deleteServers(deleteRequest);
            log.info("Huawei ECS delete job submitted: jobId={}", response.getJobId());
            nodes.forEach(n -> n.setStatus("DELETED"));
            return CloudClusterInfo.builder()
                    .clusterId(entity.getId())
                    .clusterName(entity.getClusterName())
                    .provider(PROVIDER_NAME)
                    .workspaceId(entity.getWorkspaceId())
                    .status("DELETED")
                    .nodes(nodes)
                    .k8sApiServerEndpoint(entity.getK8sApiServerEndpoint())
                    .k8sBootstrapStatus(entity.getK8sBootstrapStatus())
                    .createdAt(entity.getCreatedAt())
                    .updatedAt(entity.getUpdatedAt())
                    .build();
        } catch (Exception e) {
            log.error("Failed to destroy ECS on Huawei Cloud for cluster {}", clusterId, e);
            throw new CloudProviderException("Huawei ECS destroy failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CloudClusterInfo startVMs(String clusterId) {
        log.info("Starting ECS instances for cluster {} on Huawei Cloud", clusterId);
        ensureClient();

        CloudClusterEntity entity = repository.findById(clusterId)
                .orElseThrow(() -> new CloudProviderException("Cluster not found: " + clusterId));
        List<CloudClusterInfo.VMInfo> nodes = deserializeNodes(entity.getNodesJson());

        List<ServerId> serverIds = new ArrayList<>();
        for (CloudClusterInfo.VMInfo vm : nodes) {
            serverIds.add(new ServerId().withId(vm.getInstanceId()));
        }
        BatchStartServersOption option = new BatchStartServersOption().withServers(serverIds);
        BatchStartServersRequestBody body = new BatchStartServersRequestBody().withOsStart(option);
        BatchStartServersRequest startRequest = new BatchStartServersRequest().withBody(body);

        try {
            BatchStartServersResponse response = ecsClient.batchStartServers(startRequest);
            log.info("Huawei ECS start job submitted: jobId={}", response.getJobId());
            return getVMInfo(clusterId);
        } catch (Exception e) {
            log.error("Failed to start ECS on Huawei Cloud for cluster {}", clusterId, e);
            throw new CloudProviderException("Huawei ECS start failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CloudClusterInfo stopVMs(String clusterId) {
        log.info("Stopping ECS instances for cluster {} on Huawei Cloud", clusterId);
        ensureClient();

        CloudClusterEntity entity = repository.findById(clusterId)
                .orElseThrow(() -> new CloudProviderException("Cluster not found: " + clusterId));
        List<CloudClusterInfo.VMInfo> nodes = deserializeNodes(entity.getNodesJson());

        List<ServerId> serverIds = new ArrayList<>();
        for (CloudClusterInfo.VMInfo vm : nodes) {
            serverIds.add(new ServerId().withId(vm.getInstanceId()));
        }
        BatchStopServersOption option = new BatchStopServersOption().withServers(serverIds);
        BatchStopServersRequestBody body = new BatchStopServersRequestBody().withOsStop(option);
        BatchStopServersRequest stopRequest = new BatchStopServersRequest().withBody(body);

        try {
            BatchStopServersResponse response = ecsClient.batchStopServers(stopRequest);
            log.info("Huawei ECS stop job submitted: jobId={}", response.getJobId());
            return getVMInfo(clusterId);
        } catch (Exception e) {
            log.error("Failed to stop ECS on Huawei Cloud for cluster {}", clusterId, e);
            throw new CloudProviderException("Huawei ECS stop failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CloudClusterInfo getVMInfo(String clusterId) {
        CloudClusterEntity entity = repository.findById(clusterId).orElse(null);
        if (entity == null) {
            return null;
        }
        List<CloudClusterInfo.VMInfo> nodes = deserializeNodes(entity.getNodesJson());

        // 若 client 可用，逐台查询最新状态
        if (ecsClient != null) {
            for (CloudClusterInfo.VMInfo vm : nodes) {
                try {
                    ShowServerResponse resp = ecsClient.showServer(
                            new ShowServerRequest().withServerId(vm.getInstanceId()));
                    if (resp != null && resp.getServer() != null) {
                        vm.setStatus(resp.getServer().getStatus());
                    }
                } catch (Exception e) {
                    log.debug("Failed to query ECS {} status: {}", vm.getInstanceId(), e.getMessage());
                }
            }
        }
        return CloudClusterInfo.builder()
                .clusterId(entity.getId())
                .clusterName(entity.getClusterName())
                .provider(PROVIDER_NAME)
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

    @Override
    public CloudClusterInfo scaleVMs(String clusterId, int targetNodeCount) {
        log.info("Scaling cluster {} to {} nodes on Huawei Cloud", clusterId, targetNodeCount);
        CloudClusterEntity entity = repository.findById(clusterId)
                .orElseThrow(() -> new CloudProviderException("Cluster not found: " + clusterId));
        int current = entity.getNodeCount();
        if (targetNodeCount == current) {
            return getVMInfo(clusterId);
        }
        log.warn("Huawei scale: current={}, target={}, full implementation requires original vmSpec",
                current, targetNodeCount);
        return getVMInfo(clusterId);
    }

    /**
     * 构建 PostPaidServer（按量计费）请求体，通过 count 字段一次创建多台。
     */
    private PostPaidServer buildPostPaidServer(String clusterId, CloudClusterRequest request, VMSpec spec) {
        PostPaidServer server = new PostPaidServer()
                .withImageRef(spec.getImageId())
                .withFlavorRef(spec.getInstanceType())
                .withName(request.getClusterName() + "-node")
                .withCount(request.getNodeCount());

        // 系统盘：VolumetypeEnum 为强类型枚举（SATA/SAS/SSD/GPSSD/ESSD 等）
        PostPaidServerRootVolume rootVolume = new PostPaidServerRootVolume()
                .withVolumetype(PostPaidServerRootVolume.VolumetypeEnum.SAS)
                .withSize(spec.getSystemDiskGb());
        server.withRootVolume(rootVolume);

        // 公网 EIP：PostPaidServerPublicip 通过 EIP 字段嵌套 Eip + EipBandwidth
        if (spec.isAllocatePublicIp()) {
            PostPaidServerEipBandwidth bandwidth = new PostPaidServerEipBandwidth()
                    .withSize(spec.getBandwidthMbps());
            PostPaidServerEip eip = new PostPaidServerEip()
                    .withIptype("5_bgp")
                    .withBandwidth(bandwidth);
            PostPaidServerPublicip publicip = new PostPaidServerPublicip()
                    .withEip(eip);
            server.withPublicip(publicip);
        }

        // 网络
        if (request.getSubnetId() != null) {
            PostPaidServerNic nic = new PostPaidServerNic().withSubnetId(request.getSubnetId());
            server.withNics(List.of(nic));
        }

        // 安全组
        if (request.getSecurityGroupIds() != null && !request.getSecurityGroupIds().isEmpty()) {
            List<PostPaidServerSecurityGroup> sgs = new ArrayList<>();
            for (String sgId : request.getSecurityGroupIds()) {
                sgs.add(new PostPaidServerSecurityGroup().withId(sgId));
            }
            server.withSecurityGroups(sgs);
        }

        // 可用区
        if (request.getAvailabilityZone() != null) {
            server.withAvailabilityZone(request.getAvailabilityZone());
        }

        return server;
    }

    /**
     * 从创建响应构建集群信息。
     */
    private CloudClusterInfo buildClusterInfoFromCreate(String clusterId,
                                                        CloudClusterRequest request,
                                                        CreatePostPaidServersResponse response) {
        List<CloudClusterInfo.VMInfo> nodes = new ArrayList<>();
        if (response.getServerIds() != null) {
            for (int i = 0; i < response.getServerIds().size(); i++) {
                nodes.add(CloudClusterInfo.VMInfo.builder()
                        .instanceId(response.getServerIds().get(i))
                        .instanceName(request.getClusterName() + "-node-" + i)
                        .status("CREATING")
                        .controlPlane(i == 0)
                        .availabilityZone(request.getAvailabilityZone())
                        .build());
            }
        }
        return CloudClusterInfo.builder()
                .clusterId(clusterId)
                .clusterName(request.getClusterName())
                .provider(PROVIDER_NAME)
                .workspaceId(request.getWorkspaceId())
                .status("CREATING")
                .nodes(nodes)
                .k8sBootstrapStatus(request.isAutoBootstrapK8s() ? "PENDING" : "DISABLED")
                .build();
    }

    /**
     * 反序列化节点 JSON。
     */
    private List<CloudClusterInfo.VMInfo> deserializeNodes(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, CloudClusterInfo.VMInfo.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize nodes json: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 确保 ECS Client 已初始化。
     */
    private void ensureClient() {
        if (ecsClient == null) {
            throw new CloudProviderException("Huawei ECS client not configured: set HUAWEI_AK/HUAWEI_SK env vars");
        }
    }
}
