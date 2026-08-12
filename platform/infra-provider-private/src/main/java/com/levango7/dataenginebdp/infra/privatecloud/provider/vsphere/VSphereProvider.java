package com.levango7.dataenginebdp.infra.privatecloud.provider.vsphere;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.infra.privatecloud.model.PrivateClusterInfo;
import com.levango7.dataenginebdp.infra.privatecloud.model.PrivateClusterRequest;
import com.levango7.dataenginebdp.infra.privatecloud.model.VMSpec;
import com.levango7.dataenginebdp.infra.privatecloud.provider.PrivateCloudProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * VMware vSphere 私有云 Provider 实现。
 *
 * <p>基于 vCenter REST API（vCenter 7.0+）实现 {@link PrivateCloudProvider}：
 * 通过克隆模板 VM → 自定义配置 → 开机 → 等待 IP 就绪的流程创建 K8s 节点 VM。</p>
 *
 * <p>流程：</p>
 * <ol>
 *   <li>登录 vCenter 获取 session；</li>
 *   <li>对每个 {@link VMSpec}，调用 {@link VSphereClient#cloneVm} 从模板克隆；</li>
 *   <li>调用 {@link VSphereClient#powerOn} 开机；</li>
 *   <li>查询 VM 信息获取 IP 地址（best-effort，失败不阻塞流程）。</li>
 * </ol>
 *
 * @author shuqing-bigdata
 */
@Component("vsphereProvider")
public class VSphereProvider implements PrivateCloudProvider {

    private static final Logger log = LoggerFactory.getLogger(VSphereProvider.class);

    private static final String TYPE = "vsphere";
    private static final String ROLE_CONTROL_PLANE = "control-plane";
    private static final String ROLE_WORKER = "worker";
    private static final String POWER_ON = "POWERED_ON";

    private final VSphereClient vSphereClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造 vSphere Provider。
     *
     * @param vSphereClient vSphere REST API 客户端
     */
    public VSphereProvider(VSphereClient vSphereClient) {
        this.vSphereClient = vSphereClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public List<PrivateClusterInfo.VMInfo> createVMs(PrivateClusterRequest request) {
        log.info("vSphere 开始创建 VM: clusterName={}", request.getClusterName());
        List<PrivateClusterInfo.VMInfo> vms = new ArrayList<>();

        // 1. 创建控制面 VM
        VMSpec cpSpec = request.getControlPlane();
        String cpName = request.getClusterName() + "-cp-1";
        PrivateClusterInfo.VMInfo cpVm = createSingleVm(cpName, cpSpec, ROLE_CONTROL_PLANE);
        if (cpVm != null) {
            vms.add(cpVm);
        }

        // 2. 创建工作节点 VM
        List<VMSpec> workers = request.getWorkers();
        for (int i = 0; i < workers.size(); i++) {
            VMSpec workerSpec = workers.get(i);
            String workerName = request.getClusterName() + "-worker-" + (i + 1);
            PrivateClusterInfo.VMInfo workerVm = createSingleVm(workerName, workerSpec, ROLE_WORKER);
            if (workerVm != null) {
                vms.add(workerVm);
            }
        }

        log.info("vSphere VM 创建完成: clusterName={} count={}", request.getClusterName(), vms.size());
        return vms;
    }

    /**
     * 创建单个 VM：克隆 → 开机 → 查询信息。
     *
     * @param vmName VM 名称
     * @param spec   VM 规格
     * @param role   角色
     * @return VM 信息；失败返回 null
     */
    private PrivateClusterInfo.VMInfo createSingleVm(String vmName, VMSpec spec, String role) {
        try {
            String template = spec.getImageRef() != null
                    ? spec.getImageRef()
                    : vSphereClient.getConfig().getTemplateVm();

            log.info("克隆 VM: name={} template={} role={}", vmName, template, role);
            String vmId = vSphereClient.cloneVm(vmName, template);

            log.info("开机 VM: name={} vmId={}", vmName, vmId);
            vSphereClient.powerOn(vmId);

            // 查询 VM 信息（best-effort 获取 IP）
            PrivateClusterInfo.VMInfo vmInfo = queryVmInfo(vmId, vmName, role);
            log.info("VM 创建成功: name={} vmId={} ip={}", vmName, vmId, vmInfo.getIpAddress());
            return vmInfo;
        } catch (Exception e) {
            log.error("VM 创建失败: name={} role={} err={}", vmName, role, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 查询 VM 信息，解析电源状态与 IP。
     *
     * @param vmId   VM ID
     * @param vmName VM 名称
     * @param role   角色
     * @return VM 信息
     */
    private PrivateClusterInfo.VMInfo queryVmInfo(String vmId, String vmName, String role) {
        PrivateClusterInfo.VMInfo vmInfo = PrivateClusterInfo.VMInfo.builder()
                .vmId(vmId)
                .name(vmName)
                .role(role)
                .powerState(POWER_ON)
                .ipAddress(null)
                .build();

        try {
            String json = vSphereClient.getVm(vmId);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                JsonNode value = root.get("value");
                if (value != null) {
                    JsonNode powerState = value.get("power_state");
                    if (powerState != null) {
                        vmInfo.setPowerState(powerState.asText());
                    }
                    JsonNode ip = value.path("ip_address");
                    if (!ip.isMissingNode() && !ip.isNull()) {
                        vmInfo.setIpAddress(ip.asText());
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("解析 VM 信息失败: vmId={} err={}", vmId, e.getMessage());
        } catch (Exception e) {
            log.warn("查询 VM 信息失败: vmId={} err={}", vmId, e.getMessage());
        }
        return vmInfo;
    }

    @Override
    public boolean destroyVMs(PrivateClusterInfo cluster) {
        List<PrivateClusterInfo.VMInfo> vms = cluster.getVms();
        if (vms == null || vms.isEmpty()) {
            log.warn("销毁 VM 列表为空: clusterId={}", cluster.getId());
            return true;
        }

        log.info("vSphere 开始销毁 VM: clusterId={} count={}", cluster.getId(), vms.size());
        boolean allSuccess = true;
        for (PrivateClusterInfo.VMInfo vm : vms) {
            try {
                vSphereClient.deleteVm(vm.getVmId());
                log.info("VM 销毁成功: vmId={} name={}", vm.getVmId(), vm.getName());
            } catch (Exception e) {
                log.error("VM 销毁失败: vmId={} name={} err={}", vm.getVmId(), vm.getName(), e.getMessage(), e);
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
            PrivateClusterInfo.VMInfo refreshed = queryVmInfo(vm.getVmId(), vm.getName(), vm.getRole());
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

        log.info("vSphere 扩缩容: clusterId={} currentWorkers={} target={}",
                cluster.getId(), currentWorkers, targetWorkerCount);

        if (targetWorkerCount > currentWorkers) {
            // 扩容
            int toAdd = (int) (targetWorkerCount - currentWorkers);
            for (int i = 0; i < toAdd; i++) {
                int index = (int) currentWorkers + i + 1;
                String vmName = cluster.getClusterName() + "-worker-" + index;
                PrivateClusterInfo.VMInfo newVm = createSingleVm(vmName, workerSpec, ROLE_WORKER);
                if (newVm != null) {
                    vms.add(newVm);
                }
            }
        } else if (targetWorkerCount < currentWorkers) {
            // 缩容：按 LRU 顺序销毁多余 worker
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
                    vSphereClient.deleteVm(toDelete.getVmId());
                } catch (Exception e) {
                    log.error("缩容销毁失败: vmId={} err={}", toDelete.getVmId(), e.getMessage());
                }
            }

            vms = new ArrayList<>();
            vms.addAll(controlPlanes);
            vms.addAll(workers);
        }

        return vms;
    }
}