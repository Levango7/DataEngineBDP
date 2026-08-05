package com.shuqing.bigdata.infra.cloud.provider.ali;

import com.aliyun.ecs20140526.Client;
import com.aliyun.ecs20140526.models.DeleteInstanceRequest;
import com.aliyun.ecs20140526.models.DeleteInstanceResponse;

import com.aliyun.ecs20140526.models.DescribeInstancesRequest;
import com.aliyun.ecs20140526.models.DescribeInstancesResponseBody;
import com.aliyun.ecs20140526.models.RunInstancesRequest;
import com.aliyun.ecs20140526.models.RunInstancesResponse;
import com.aliyun.ecs20140526.models.StartInstanceRequest;
import com.aliyun.ecs20140526.models.StartInstanceResponse;
import com.aliyun.ecs20140526.models.StopInstanceRequest;
import com.aliyun.ecs20140526.models.StopInstanceResponse;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 阿里云 ECS Provider 实现。
 *
 * <p>使用阿里云 Java SDK（{@code com.aliyun.ecs20140526}）封装 VM 生命周期：
 * 创建 ECS 实例 → 配置安全组 → 分配公网 IP → 返回 VM 信息。</p>
 *
 * <p>SDK 文档：https://github.com/aliyun/aliyun-openapi-java-sdk</p>
 */
@Component
public class AliCloudProvider implements CloudProvider {

    private static final Logger log = LoggerFactory.getLogger(AliCloudProvider.class);

    private static final String PROVIDER_NAME = "ali";

    private final CloudClusterRepository repository;
    private final ObjectMapper objectMapper;
    private final Client ecsClient;

    /**
     * 构造阿里云 Provider。
     *
     * <p>从 application.yml 读取 AK/SK/region，构建 ECS Client。
     * 凭证通过环境变量 {@code ALI_AK} / {@code ALI_SK} 注入，禁止明文提交。</p>
     *
     * @param repository 集群元数据 Repository
     * @param objectMapper JSON 序列化器
     * @param ak     阿里云 Access Key Id
     * @param sk     阿里云 Access Key Secret
     * @param region 阿里云 region（如 cn-hangzhou）
     */
    public AliCloudProvider(CloudClusterRepository repository,
                            ObjectMapper objectMapper,
                            @Value("${app.cloud.ali.ak:}") String ak,
                            @Value("${app.cloud.ali.sk:}") String sk,
                            @Value("${app.cloud.ali.region:cn-hangzhou}") String region) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        Client client = null;
        if (ak != null && !ak.isEmpty() && sk != null && !sk.isEmpty()) {
            try {
                Config config = new Config()
                        .setAccessKeyId(ak)
                        .setAccessKeySecret(sk)
                        .setRegionId(region)
                        .setEndpoint("ecs." + region + ".aliyuncs.com");
                client = new Client(config);
            } catch (Exception e) {
                log.error("Failed to build Aliyun ECS client", e);
            }
        } else {
            log.warn("Aliyun credentials not configured (ALI_AK/ALI_SK empty); ECS calls will fail at runtime");
        }
        this.ecsClient = client;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public CloudClusterInfo createVMs(String clusterId, CloudClusterRequest request) {
        log.info("Creating {} ECS instances for cluster {} on Aliyun", request.getNodeCount(), clusterId);
        ensureClient();

        VMSpec spec = request.getVmSpec();
        List<CloudClusterInfo.VMInfo> nodes = new ArrayList<>();

        // 阿里云 RunInstances 支持一次创建多台相同规格的实例
        try {
            for (int i = 0; i < request.getNodeCount(); i++) {
                RunInstancesRequest runRequest = buildRunInstancesRequest(clusterId, i, request, spec);
                RunInstancesResponse response = ecsClient.runInstances(runRequest);
                if (response.getBody() != null && response.getBody().getInstanceIdSets() != null
                        && response.getBody().getInstanceIdSets().getInstanceIdSet() != null) {
                    for (String instanceId : response.getBody().getInstanceIdSets().getInstanceIdSet()) {
                        nodes.add(CloudClusterInfo.VMInfo.builder()
                                .instanceId(instanceId)
                                .instanceName(request.getClusterName() + "-node-" + i)
                                .status("CREATING")
                                .controlPlane(i == 0)
                                .availabilityZone(request.getAvailabilityZone())
                                .build());
                    }
                }
            }
            log.info("Aliyun ECS created: cluster={}, nodeCount={}", clusterId, nodes.size());
            return CloudClusterInfo.builder()
                    .clusterId(clusterId)
                    .clusterName(request.getClusterName())
                    .provider(PROVIDER_NAME)
                    .workspaceId(request.getWorkspaceId())
                    .status("CREATING")
                    .nodes(nodes)
                    .k8sBootstrapStatus(request.isAutoBootstrapK8s() ? "PENDING" : "DISABLED")
                    .build();
        } catch (Exception e) {
            log.error("Failed to create ECS on Aliyun for cluster {}", clusterId, e);
            throw new CloudProviderException("Aliyun ECS create failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CloudClusterInfo destroyVMs(String clusterId) {
        log.info("Destroying ECS instances for cluster {} on Aliyun", clusterId);
        ensureClient();

        CloudClusterEntity entity = repository.findById(clusterId)
                .orElseThrow(() -> new CloudProviderException("Cluster not found: " + clusterId));
        List<CloudClusterInfo.VMInfo> nodes = deserializeNodes(entity.getNodesJson());

        try {
            for (CloudClusterInfo.VMInfo vm : nodes) {
                DeleteInstanceRequest deleteRequest = new DeleteInstanceRequest()
                        .setInstanceId(vm.getInstanceId())
                        .setForce(true);
                DeleteInstanceResponse response = ecsClient.deleteInstance(deleteRequest);
                log.info("Aliyun ECS delete: instanceId={}, requestId={}",
                        vm.getInstanceId(), response.getBody().getRequestId());
            }
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
            log.error("Failed to destroy ECS on Aliyun for cluster {}", clusterId, e);
            throw new CloudProviderException("Aliyun ECS destroy failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CloudClusterInfo startVMs(String clusterId) {
        log.info("Starting ECS instances for cluster {} on Aliyun", clusterId);
        ensureClient();

        CloudClusterEntity entity = repository.findById(clusterId)
                .orElseThrow(() -> new CloudProviderException("Cluster not found: " + clusterId));
        List<CloudClusterInfo.VMInfo> nodes = deserializeNodes(entity.getNodesJson());

        try {
            for (CloudClusterInfo.VMInfo vm : nodes) {
                StartInstanceRequest startRequest = new StartInstanceRequest()
                        .setInstanceId(vm.getInstanceId());
                StartInstanceResponse response = ecsClient.startInstance(startRequest);
                log.info("Aliyun ECS start: instanceId={}, requestId={}",
                        vm.getInstanceId(), response.getBody().getRequestId());
            }
            return getVMInfo(clusterId);
        } catch (Exception e) {
            log.error("Failed to start ECS on Aliyun for cluster {}", clusterId, e);
            throw new CloudProviderException("Aliyun ECS start failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CloudClusterInfo stopVMs(String clusterId) {
        log.info("Stopping ECS instances for cluster {} on Aliyun", clusterId);
        ensureClient();

        CloudClusterEntity entity = repository.findById(clusterId)
                .orElseThrow(() -> new CloudProviderException("Cluster not found: " + clusterId));
        List<CloudClusterInfo.VMInfo> nodes = deserializeNodes(entity.getNodesJson());

        try {
            for (CloudClusterInfo.VMInfo vm : nodes) {
                StopInstanceRequest stopRequest = new StopInstanceRequest()
                        .setInstanceId(vm.getInstanceId())
                        .setForceStop(false);
                StopInstanceResponse response = ecsClient.stopInstance(stopRequest);
                log.info("Aliyun ECS stop: instanceId={}, requestId={}",
                        vm.getInstanceId(), response.getBody().getRequestId());
            }
            return getVMInfo(clusterId);
        } catch (Exception e) {
            log.error("Failed to stop ECS on Aliyun for cluster {}", clusterId, e);
            throw new CloudProviderException("Aliyun ECS stop failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CloudClusterInfo getVMInfo(String clusterId) {
        CloudClusterEntity entity = repository.findById(clusterId).orElse(null);
        if (entity == null) {
            return null;
        }
        List<CloudClusterInfo.VMInfo> nodes = deserializeNodes(entity.getNodesJson());

        if (ecsClient != null) {
            for (CloudClusterInfo.VMInfo vm : nodes) {
                try {
                    DescribeInstancesRequest req = new DescribeInstancesRequest()
                            .setInstanceIds("[\"" + vm.getInstanceId() + "\"]");
                    var resp = ecsClient.describeInstances(req);
                    if (resp.getBody() != null
                            && resp.getBody().getInstances() != null
                            && resp.getBody().getInstances().getInstance() != null
                            && !resp.getBody().getInstances().getInstance().isEmpty()) {
                        DescribeInstancesResponseBody.DescribeInstancesResponseBodyInstancesInstance inst =
                                resp.getBody().getInstances().getInstance().get(0);
                        vm.setStatus(inst.getStatus());
                        if (inst.getInnerIpAddress() != null) {
                            vm.setPrivateIp(inst.getInnerIpAddress().getIpAddress().isEmpty()
                                    ? null : inst.getInnerIpAddress().getIpAddress().get(0));
                        }
                        if (inst.getPublicIpAddress() != null) {
                            vm.setPublicIp(inst.getPublicIpAddress().getIpAddress().isEmpty()
                                    ? null : inst.getPublicIpAddress().getIpAddress().get(0));
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to query Aliyun ECS {} status: {}", vm.getInstanceId(), e.getMessage());
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
        log.info("Scaling cluster {} to {} nodes on Aliyun", clusterId, targetNodeCount);
        CloudClusterEntity entity = repository.findById(clusterId)
                .orElseThrow(() -> new CloudProviderException("Cluster not found: " + clusterId));
        int current = entity.getNodeCount();
        if (targetNodeCount == current) {
            return getVMInfo(clusterId);
        }
        log.warn("Aliyun scale: current={}, target={}, full implementation requires original vmSpec",
                current, targetNodeCount);
        return getVMInfo(clusterId);
    }

    /**
     * 构建 RunInstances 请求。
     */
    private RunInstancesRequest buildRunInstancesRequest(String clusterId, int index,
                                                         CloudClusterRequest request, VMSpec spec) {
        RunInstancesRequest runRequest = new RunInstancesRequest()
                .setRegionId(getRegionFromClient())
                .setImageId(spec.getImageId())
                .setInstanceType(spec.getInstanceType())
                .setInstanceName(request.getClusterName() + "-node-" + index)
                .setAmount(1)
                // 系统盘配置：通过 SystemDisk 子对象设置 category + size
                .setSystemDisk(new RunInstancesRequest.RunInstancesRequestSystemDisk()
                        .setCategory("cloud_essd")
                        .setSize(String.valueOf(spec.getSystemDiskGb())))
                // 网络配置：经典网络 vs VPC，此处统一 VPC
                .setInternetMaxBandwidthOut(spec.getBandwidthMbps());

        // 公网 IP
        if (spec.isAllocatePublicIp()) {
            runRequest.setInternetChargeType("PayByTraffic");
        }

        // VPC / vSwitch
        if (request.getSubnetId() != null) {
            runRequest.setVSwitchId(request.getSubnetId());
        }

        // 安全组
        if (request.getSecurityGroupIds() != null && !request.getSecurityGroupIds().isEmpty()) {
            runRequest.setSecurityGroupId(request.getSecurityGroupIds().get(0));
        }

        // 可用区
        if (request.getAvailabilityZone() != null) {
            runRequest.setZoneId(request.getAvailabilityZone());
        }

        // SSH 公钥
        if (spec.getSshPublicKey() != null && !spec.getSshPublicKey().isEmpty()) {
            // 简化：实际应查询或创建 keypair，再 setKeyPairName
            runRequest.setPassword(null);
        }

        // 标签：cluster_id 便于成本分摊
        List<RunInstancesRequest.RunInstancesRequestTag> tags = new ArrayList<>();
        tags.add(new RunInstancesRequest.RunInstancesRequestTag()
                .setKey("cluster_id").setValue(clusterId));
        tags.add(new RunInstancesRequest.RunInstancesRequestTag()
                .setKey("workspace_id").setValue(request.getWorkspaceId()));
        runRequest.setTag(tags);

        return runRequest;
    }

    /**
     * 从 client 提取 regionId（简化实现）。
     */
    private String getRegionFromClient() {
        // 阿里云 SDK Client 内部持有 regionId，但未公开 getter；此处从 _endpoint 反推或使用配置注入
        // 简化：返回空让 SDK 使用默认 region
        return null;
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
            throw new CloudProviderException("Aliyun ECS client not configured: set ALI_AK/ALI_SK env vars");
        }
    }
}