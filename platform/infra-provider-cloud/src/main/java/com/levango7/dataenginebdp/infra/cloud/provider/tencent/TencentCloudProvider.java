package com.levango7.dataenginebdp.infra.cloud.provider.tencent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterEntity;
import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterInfo;
import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterRequest;
import com.levango7.dataenginebdp.infra.cloud.model.VMSpec;
import com.levango7.dataenginebdp.infra.cloud.provider.CloudProvider;
import com.levango7.dataenginebdp.infra.cloud.repository.CloudClusterRepository;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.cvm.v20170312.CvmClient;
import com.tencentcloudapi.cvm.v20170312.models.DescribeInstancesRequest;
import com.tencentcloudapi.cvm.v20170312.models.DescribeInstancesResponse;
import com.tencentcloudapi.cvm.v20170312.models.Instance;
import com.tencentcloudapi.cvm.v20170312.models.RunInstancesRequest;
import com.tencentcloudapi.cvm.v20170312.models.RunInstancesResponse;
import com.tencentcloudapi.cvm.v20170312.models.TerminateInstancesRequest;
import com.tencentcloudapi.cvm.v20170312.models.TerminateInstancesResponse;
import com.tencentcloudapi.cvm.v20170312.models.StartInstancesRequest;
import com.tencentcloudapi.cvm.v20170312.models.StartInstancesResponse;
import com.tencentcloudapi.cvm.v20170312.models.StopInstancesRequest;
import com.tencentcloudapi.cvm.v20170312.models.StopInstancesResponse;
import com.tencentcloudapi.cvm.v20170312.models.SystemDisk;
import com.tencentcloudapi.cvm.v20170312.models.DataDisk;
import com.tencentcloudapi.cvm.v20170312.models.VirtualPrivateCloud;
import com.tencentcloudapi.cvm.v20170312.models.InternetAccessible;
import com.tencentcloudapi.cvm.v20170312.models.Tag;
import com.tencentcloudapi.cvm.v20170312.models.Placement;
import com.tencentcloudapi.cvm.v20170312.models.TagSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯云 CVM Provider 实现。
 *
 * <p>使用腾讯云 Java SDK（{@code com.tencentcloudapi.cvm.v20170312}）封装 VM 生命周期：
 * 创建 CVM 实例 → 配置安全组 → 分配公网 IP → 返回 VM 信息。</p>
 *
 * <p>SDK 文档：https://github.com/TencentCloud/tencentcloud-sdk-java</p>
 */
@Component
public class TencentCloudProvider implements CloudProvider {

    private static final Logger log = LoggerFactory.getLogger(TencentCloudProvider.class);

    private static final String PROVIDER_NAME = "tencent";

    private final CloudClusterRepository repository;
    private final ObjectMapper objectMapper;
    private final CvmClient cvmClient;
    private final String region;

    /**
     * 构造腾讯云 Provider。
     *
     * <p>从 application.yml 读取 secretId/secretKey/region，构建 CVM Client。
     * 凭证通过环境变量 {@code TENCENT_SECRET_ID} / {@code TENCENT_SECRET_KEY} 注入，禁止明文提交。</p>
     *
     * @param repository 集群元数据 Repository
     * @param objectMapper JSON 序列化器
     * @param secretId  腾讯云 SecretId
     * @param secretKey 腾讯云 SecretKey
     * @param region    腾讯云 region（如 ap-guangzhou）
     */
    public TencentCloudProvider(CloudClusterRepository repository,
                                ObjectMapper objectMapper,
                                @Value("${app.cloud.tencent.secret-id:}") String secretId,
                                @Value("${app.cloud.tencent.secret-key:}") String secretKey,
                                @Value("${app.cloud.tencent.region:ap-guangzhou}") String region) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.region = region;
        CvmClient client = null;
        if (secretId != null && !secretId.isEmpty() && secretKey != null && !secretKey.isEmpty()) {
            try {
                Credential cred = new Credential(secretId, secretKey);
                HttpProfile httpProfile = new HttpProfile();
                httpProfile.setEndpoint("cvm.tencentcloudapi.com");
                ClientProfile clientProfile = new ClientProfile();
                clientProfile.setHttpProfile(httpProfile);
                client = new CvmClient(cred, region, clientProfile);
            } catch (Exception e) {
                log.error("Failed to build Tencent CVM client", e);
            }
        } else {
            log.warn("Tencent credentials not configured (TENCENT_SECRET_ID/TENCENT_SECRET_KEY empty); CVM calls will fail at runtime");
        }
        this.cvmClient = client;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public CloudClusterInfo createVMs(String clusterId, CloudClusterRequest request) {
        log.info("Creating {} CVM instances for cluster {} on Tencent Cloud", request.getNodeCount(), clusterId);
        ensureClient();

        VMSpec spec = request.getVmSpec();
        List<CloudClusterInfo.VMInfo> nodes = new ArrayList<>();

        try {
            // 腾讯云 RunInstances 支持一次创建多台相同规格的实例
            RunInstancesRequest runRequest = buildRunInstancesRequest(clusterId, request, spec);
            RunInstancesResponse response = cvmClient.RunInstances(runRequest);

            if (response.getInstanceIdSet() != null) {
                for (int i = 0; i < response.getInstanceIdSet().length; i++) {
                    nodes.add(CloudClusterInfo.VMInfo.builder()
                            .instanceId(response.getInstanceIdSet()[i])
                            .instanceName(request.getClusterName() + "-node-" + i)
                            .status("PENDING")
                            .controlPlane(i == 0)
                            .availabilityZone(request.getAvailabilityZone())
                            .build());
                }
            }
            log.info("Tencent CVM created: cluster={}, nodeCount={}, requestId={}",
                    clusterId, nodes.size(), response.getRequestId());
            return CloudClusterInfo.builder()
                    .clusterId(clusterId)
                    .clusterName(request.getClusterName())
                    .provider(PROVIDER_NAME)
                    .workspaceId(request.getWorkspaceId())
                    .status("CREATING")
                    .nodes(nodes)
                    .k8sBootstrapStatus(request.isAutoBootstrapK8s() ? "PENDING" : "DISABLED")
                    .build();
        } catch (TencentCloudSDKException e) {
            log.error("Failed to create CVM on Tencent Cloud for cluster {}", clusterId, e);
            throw new CloudProviderException("Tencent CVM create failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CloudClusterInfo destroyVMs(String clusterId) {
        log.info("Destroying CVM instances for cluster {} on Tencent Cloud", clusterId);
        ensureClient();

        CloudClusterEntity entity = repository.findById(clusterId)
                .orElseThrow(() -> new CloudProviderException("Cluster not found: " + clusterId));
        List<CloudClusterInfo.VMInfo> nodes = deserializeNodes(entity.getNodesJson());
        String[] instanceIds = nodes.stream()
                .map(CloudClusterInfo.VMInfo::getInstanceId)
                .toArray(String[]::new);

        try {
            TerminateInstancesRequest terminateRequest = new TerminateInstancesRequest();
            terminateRequest.setInstanceIds(instanceIds);
            TerminateInstancesResponse response = cvmClient.TerminateInstances(terminateRequest);
            log.info("Tencent CVM terminate: cluster={}, requestId={}", clusterId, response.getRequestId());
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
        } catch (TencentCloudSDKException e) {
            log.error("Failed to destroy CVM on Tencent Cloud for cluster {}", clusterId, e);
            throw new CloudProviderException("Tencent CVM destroy failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CloudClusterInfo startVMs(String clusterId) {
        log.info("Starting CVM instances for cluster {} on Tencent Cloud", clusterId);
        ensureClient();

        CloudClusterEntity entity = repository.findById(clusterId)
                .orElseThrow(() -> new CloudProviderException("Cluster not found: " + clusterId));
        List<CloudClusterInfo.VMInfo> nodes = deserializeNodes(entity.getNodesJson());
        String[] instanceIds = nodes.stream()
                .map(CloudClusterInfo.VMInfo::getInstanceId)
                .toArray(String[]::new);

        try {
            StartInstancesRequest startRequest = new StartInstancesRequest();
            startRequest.setInstanceIds(instanceIds);
            StartInstancesResponse response = cvmClient.StartInstances(startRequest);
            log.info("Tencent CVM start: cluster={}, requestId={}", clusterId, response.getRequestId());
            return getVMInfo(clusterId);
        } catch (TencentCloudSDKException e) {
            log.error("Failed to start CVM on Tencent Cloud for cluster {}", clusterId, e);
            throw new CloudProviderException("Tencent CVM start failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CloudClusterInfo stopVMs(String clusterId) {
        log.info("Stopping CVM instances for cluster {} on Tencent Cloud", clusterId);
        ensureClient();

        CloudClusterEntity entity = repository.findById(clusterId)
                .orElseThrow(() -> new CloudProviderException("Cluster not found: " + clusterId));
        List<CloudClusterInfo.VMInfo> nodes = deserializeNodes(entity.getNodesJson());
        String[] instanceIds = nodes.stream()
                .map(CloudClusterInfo.VMInfo::getInstanceId)
                .toArray(String[]::new);

        try {
            StopInstancesRequest stopRequest = new StopInstancesRequest();
            stopRequest.setInstanceIds(instanceIds);
            stopRequest.setForceStop(false);
            StopInstancesResponse response = cvmClient.StopInstances(stopRequest);
            log.info("Tencent CVM stop: cluster={}, requestId={}", clusterId, response.getRequestId());
            return getVMInfo(clusterId);
        } catch (TencentCloudSDKException e) {
            log.error("Failed to stop CVM on Tencent Cloud for cluster {}", clusterId, e);
            throw new CloudProviderException("Tencent CVM stop failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CloudClusterInfo getVMInfo(String clusterId) {
        CloudClusterEntity entity = repository.findById(clusterId).orElse(null);
        if (entity == null) {
            return null;
        }
        List<CloudClusterInfo.VMInfo> nodes = deserializeNodes(entity.getNodesJson());

        if (cvmClient != null) {
            try {
                DescribeInstancesRequest req = new DescribeInstancesRequest();
                String[] instanceIds = nodes.stream()
                        .map(CloudClusterInfo.VMInfo::getInstanceId)
                        .toArray(String[]::new);
                req.setInstanceIds(instanceIds);
                DescribeInstancesResponse resp = cvmClient.DescribeInstances(req);
                if (resp.getInstanceSet() != null) {
                    for (Instance inst : resp.getInstanceSet()) {
                        for (CloudClusterInfo.VMInfo vm : nodes) {
                            if (vm.getInstanceId().equals(inst.getInstanceId())) {
                                vm.setStatus(inst.getInstanceState());
                                vm.setPrivateIp(inst.getPrivateIpAddresses() != null && inst.getPrivateIpAddresses().length > 0
                                        ? inst.getPrivateIpAddresses()[0] : null);
                                vm.setPublicIp(inst.getPublicIpAddresses() != null && inst.getPublicIpAddresses().length > 0
                                        ? inst.getPublicIpAddresses()[0] : null);
                            }
                        }
                    }
                }
            } catch (TencentCloudSDKException e) {
                log.debug("Failed to query Tencent CVM status: {}", e.getMessage());
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
        log.info("Scaling cluster {} to {} nodes on Tencent Cloud", clusterId, targetNodeCount);
        CloudClusterEntity entity = repository.findById(clusterId)
                .orElseThrow(() -> new CloudProviderException("Cluster not found: " + clusterId));
        int current = entity.getNodeCount();
        if (targetNodeCount == current) {
            return getVMInfo(clusterId);
        }
        log.warn("Tencent scale: current={}, target={}, full implementation requires original vmSpec",
                current, targetNodeCount);
        return getVMInfo(clusterId);
    }

    /**
     * 构建 RunInstances 请求。
     */
    private RunInstancesRequest buildRunInstancesRequest(String clusterId,
                                                         CloudClusterRequest request, VMSpec spec) {
        RunInstancesRequest runRequest = new RunInstancesRequest();
        runRequest.setInstanceCount((long) request.getNodeCount());
        runRequest.setImageId(spec.getImageId());
        runRequest.setInstanceType(spec.getInstanceType());

        // 实例名称前缀（腾讯云会自动加后缀）
        runRequest.setInstanceName(request.getClusterName() + "-node");

        // 系统盘：DiskSize 为 Long 类型
        SystemDisk systemDisk = new SystemDisk();
        systemDisk.setDiskType("CLOUD_SSD");
        systemDisk.setDiskSize((long) spec.getSystemDiskGb());
        runRequest.setSystemDisk(systemDisk);

        // 数据盘
        if (spec.getDataDiskGb() != null && spec.getDataDiskGb() > 0) {
            DataDisk dataDisk = new DataDisk();
            dataDisk.setDiskType("CLOUD_SSD");
            dataDisk.setDiskSize((long) spec.getDataDiskGb());
            runRequest.setDataDisks(new DataDisk[]{dataDisk});
        }

        // 公网带宽
        InternetAccessible internetAccessible = new InternetAccessible();
        internetAccessible.setInternetMaxBandwidthOut((long) spec.getBandwidthMbps());
        internetAccessible.setPublicIpAssigned(spec.isAllocatePublicIp());
        internetAccessible.setInternetChargeType("TRAFFIC_POSTPAID_BY_HOUR");
        runRequest.setInternetAccessible(internetAccessible);

        // VPC / 子网
        if (request.getSubnetId() != null || request.getVpcId() != null) {
            VirtualPrivateCloud vpc = new VirtualPrivateCloud();
            if (request.getVpcId() != null) {
                vpc.setVpcId(request.getVpcId());
            }
            if (request.getSubnetId() != null) {
                vpc.setSubnetId(request.getSubnetId());
            }
            runRequest.setVirtualPrivateCloud(vpc);
        }

        // 安全组
        if (request.getSecurityGroupIds() != null && !request.getSecurityGroupIds().isEmpty()) {
            runRequest.setSecurityGroupIds(request.getSecurityGroupIds().toArray(new String[0]));
        }

        // 可用区：通过 Placement 对象设置 zone
        if (request.getAvailabilityZone() != null) {
            Placement placement = new Placement();
            placement.setZone(request.getAvailabilityZone());
            runRequest.setPlacement(placement);
        }

        // 标签：通过 TagSpecification 包装，ResourceType="instance" 表示实例标签
        List<Tag> tags = new ArrayList<>();
        Tag clusterTag = new Tag();
        clusterTag.setKey("cluster_id");
        clusterTag.setValue(clusterId);
        tags.add(clusterTag);
        Tag workspaceTag = new Tag();
        workspaceTag.setKey("workspace_id");
        workspaceTag.setValue(request.getWorkspaceId());
        tags.add(workspaceTag);
        TagSpecification tagSpec = new TagSpecification();
        tagSpec.setResourceType("instance");
        tagSpec.setTags(tags.toArray(new Tag[0]));
        runRequest.setTagSpecification(new TagSpecification[]{tagSpec});

        return runRequest;
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
     * 确保 CVM Client 已初始化。
     */
    private void ensureClient() {
        if (cvmClient == null) {
            throw new CloudProviderException("Tencent CVM client not configured: set TENCENT_SECRET_ID/TENCENT_SECRET_KEY env vars");
        }
    }
}