package com.shuqing.bigdata.infra.privatecloud.provider.openstack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.infra.privatecloud.model.PrivateClusterInfo;
import com.shuqing.bigdata.infra.privatecloud.model.PrivateClusterRequest;
import com.shuqing.bigdata.infra.privatecloud.model.VMSpec;
import com.shuqing.bigdata.infra.privatecloud.provider.PrivateCloudProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenStack 私有云 Provider 实现。
 *
 * <p>基于 OpenStack Nova API（v2.1）+ Keystone V3 实现 {@link PrivateCloudProvider}：
 * 通过创建实例 → 分配浮动 IP → 等待 ACTIVE 状态的流程创建 K8s 节点 VM。</p>
 *
 * <p>流程：</p>
 * <ol>
 *   <li>Keystone V3 认证获取 token；</li>
 *   <li>对每个 {@link VMSpec}，调用 {@link OpenStackClient#createServer} 创建实例；</li>
 *   <li>调用 {@link OpenStackClient#allocateFloatingIp} 分配浮动 IP（best-effort）；</li>
 *   <li>查询实例信息获取状态与 IP。</li>
 * </ol>
 *
 * @author shuqing-bigdata
 */
@Component("openstackProvider")
public class OpenStackProvider implements PrivateCloudProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenStackProvider.class);

    private static final String TYPE = "openstack";
    private static final String ROLE_CONTROL_PLANE = "control-plane";
    private static final String ROLE_WORKER = "worker";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final OpenStackClient openStackClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造 OpenStack Provider。
     *
     * @param openStackClient OpenStack REST API 客户端
     */
    public OpenStackProvider(OpenStackClient openStackClient) {
        this.openStackClient = openStackClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public List<PrivateClusterInfo.VMInfo> createVMs(PrivateClusterRequest request) {
        log.info("OpenStack 开始创建实例: clusterName={}", request.getClusterName());
        List<PrivateClusterInfo.VMInfo> vms = new ArrayList<>();

        // 1. 创建控制面实例
        VMSpec cpSpec = request.getControlPlane();
        String cpName = request.getClusterName() + "-cp-1";
        PrivateClusterInfo.VMInfo cpVm = createSingleServer(cpName, cpSpec, ROLE_CONTROL_PLANE);
        if (cpVm != null) {
            vms.add(cpVm);
        }

        // 2. 创建工作节点实例
        List<VMSpec> workers = request.getWorkers();
        for (int i = 0; i < workers.size(); i++) {
            VMSpec workerSpec = workers.get(i);
            String workerName = request.getClusterName() + "-worker-" + (i + 1);
            PrivateClusterInfo.VMInfo workerVm = createSingleServer(workerName, workerSpec, ROLE_WORKER);
            if (workerVm != null) {
                vms.add(workerVm);
            }
        }

        log.info("OpenStack 实例创建完成: clusterName={} count={}", request.getClusterName(), vms.size());
        return vms;
    }

    /**
     * 创建单个实例：创建 → 分配浮动 IP → 查询信息。
     *
     * @param name 实例名称
     * @param spec VM 规格
     * @param role 角色
     * @return VM 信息；失败返回 null
     */
    private PrivateClusterInfo.VMInfo createSingleServer(String name, VMSpec spec, String role) {
        try {
            String imageId = spec.getImageRef() != null
                    ? spec.getImageRef()
                    : openStackClient.getConfig().getImageId();
            String flavorId = spec.getFlavorId() != null
                    ? spec.getFlavorId()
                    : openStackClient.getConfig().getFlavorId();

            log.info("创建实例: name={} imageId={} flavorId={} role={}", name, imageId, flavorId, role);
            String serverId = openStackClient.createServer(name, imageId, flavorId);

            // 分配浮动 IP（best-effort，失败不阻塞）
            String floatingIp = null;
            try {
                floatingIp = openStackClient.allocateFloatingIp(
                        openStackClient.getConfig().getExternalNetwork());
                log.info("浮动 IP 已分配: serverId={} floatingIp={}", serverId, floatingIp);
            } catch (Exception e) {
                log.warn("分配浮动 IP 失败（不阻塞流程）: serverId={} err={}", serverId, e.getMessage());
            }

            PrivateClusterInfo.VMInfo vmInfo = queryServerInfo(serverId, name, role);
            if (floatingIp != null) {
                vmInfo.setFloatingIp(floatingIp);
            }
            log.info("实例创建成功: name={} serverId={} ip={}", name, serverId, vmInfo.getIpAddress());
            return vmInfo;
        } catch (Exception e) {
            log.error("实例创建失败: name={} role={} err={}", name, role, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 查询实例信息，解析状态与 IP。
     *
     * @param serverId 实例 ID
     * @param name     实例名称
     * @param role     角色
     * @return VM 信息
     */
    private PrivateClusterInfo.VMInfo queryServerInfo(String serverId, String name, String role) {
        PrivateClusterInfo.VMInfo vmInfo = PrivateClusterInfo.VMInfo.builder()
                .vmId(serverId)
                .name(name)
                .role(role)
                .powerState(STATUS_ACTIVE)
                .ipAddress(null)
                .build();

        try {
            String json = openStackClient.getServer(serverId);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                JsonNode server = root.path("server");
                JsonNode status = server.path("status");
                if (!status.isMissingNode()) {
                    vmInfo.setPowerState(status.asText());
                }
                // 解析固定 IP（accessIPv4 或 addresses.network.addr）
                JsonNode accessIp = server.path("accessIPv4");
                if (!accessIp.isMissingNode() && !accessIp.isNull() && !accessIp.asText().isEmpty()) {
                    vmInfo.setIpAddress(accessIp.asText());
                } else {
                    JsonNode addresses = server.path("addresses");
                    if (addresses.isObject()) {
                        String firstIp = addresses.fields().next()
                                .getValue().path(0).path("addr").asText(null);
                        if (firstIp != null) {
                            vmInfo.setIpAddress(firstIp);
                        }
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("解析实例信息失败: serverId={} err={}", serverId, e.getMessage());
        } catch (Exception e) {
            log.warn("查询实例信息失败: serverId={} err={}", serverId, e.getMessage());
        }
        return vmInfo;
    }

    @Override
    public boolean destroyVMs(PrivateClusterInfo cluster) {
        List<PrivateClusterInfo.VMInfo> vms = cluster.getVms();
        if (vms == null || vms.isEmpty()) {
            log.warn("销毁实例列表为空: clusterId={}", cluster.getId());
            return true;
        }

        log.info("OpenStack 开始销毁实例: clusterId={} count={}", cluster.getId(), vms.size());
        boolean allSuccess = true;
        for (PrivateClusterInfo.VMInfo vm : vms) {
            try {
                openStackClient.deleteServer(vm.getVmId());
                log.info("实例销毁成功: serverId={} name={}", vm.getVmId(), vm.getName());
            } catch (Exception e) {
                log.error("实例销毁失败: serverId={} name={} err={}",
                        vm.getVmId(), vm.getName(), e.getMessage(), e);
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    @Override
    public List<PrivateClusterInfo.VMInfo> getVMInfo(PrivateClusterInfo cluster) {
        List<PrivateClusterInfo.VMInfo> vms = cluster.getVms();
        if (vms == null || vms.isEmpty()) {
            return List.of();
        }

        List<PrivateClusterInfo.VMInfo> result = new ArrayList<>();
        for (PrivateClusterInfo.VMInfo vm : vms) {
            PrivateClusterInfo.VMInfo refreshed = queryServerInfo(vm.getVmId(), vm.getName(), vm.getRole());
            if (vm.getFloatingIp() != null) {
                refreshed.setFloatingIp(vm.getFloatingIp());
            }
            result.add(refreshed);
        }
        return result;
    }

    @Override
    public List<PrivateClusterInfo.VMInfo> scaleVMs(PrivateClusterInfo cluster,
                                                    int targetWorkerCount,
                                                    VMSpec workerSpec) {
        List<PrivateClusterInfo.VMInfo> vms = new ArrayList<>(
                cluster.getVms() != null ? cluster.getVms() : List.of());

        long currentWorkers = vms.stream()
                .filter(vm -> ROLE_WORKER.equals(vm.getRole()))
                .count();

        log.info("OpenStack 扩缩容: clusterId={} currentWorkers={} target={}",
                cluster.getId(), currentWorkers, targetWorkerCount);

        if (targetWorkerCount > currentWorkers) {
            int toAdd = (int) (targetWorkerCount - currentWorkers);
            for (int i = 0; i < toAdd; i++) {
                int index = (int) currentWorkers + i + 1;
                String name = cluster.getClusterName() + "-worker-" + index;
                PrivateClusterInfo.VMInfo newVm = createSingleServer(name, workerSpec, ROLE_WORKER);
                if (newVm != null) {
                    vms.add(newVm);
                }
            }
        } else if (targetWorkerCount < currentWorkers) {
            int toRemove = (int) (currentWorkers - targetWorkerCount);
            List<PrivateClusterInfo.VMInfo> workers = new ArrayList<>();
            List<PrivateClusterInfo.VMInfo> controlPlanes = new ArrayList<>();
            for (PrivateClusterInfo.VMInfo vm : vms) {
                if (ROLE_WORKER.equals(vm.getRole())) {
                    workers.add(vm);
                } else {
                    controlPlanes.add(vm);
                }
            }

            for (int i = 0; i < toRemove && !workers.isEmpty(); i++) {
                PrivateClusterInfo.VMInfo toDelete = workers.remove(workers.size() - 1);
                try {
                    openStackClient.deleteServer(toDelete.getVmId());
                } catch (Exception e) {
                    log.error("缩容销毁失败: serverId={} err={}", toDelete.getVmId(), e.getMessage());
                }
            }

            vms = new ArrayList<>();
            vms.addAll(controlPlanes);
            vms.addAll(workers);
        }

        return vms;
    }
}