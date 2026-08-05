package com.shuqing.bigdata.infra.xinchang.service;

import com.shuqing.bigdata.infra.xinchang.model.ClusterCreateRequest;
import com.shuqing.bigdata.infra.xinchang.model.XinchangNodeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * K8s 集群初始化服务。
 *
 * <p>使用 kubeadm 初始化 K8s 集群，加入 SKE 定制配置：</p>
 * <ul>
 *   <li>kubeadm init 初始化 control-plane</li>
 *   <li>kubeadm join 加入 worker 节点</li>
 *   <li>SKE 定制：默认开启 IPVS、关闭 swap、调整 kube-reserved</li>
 *   <li>国产 CPU 适配：aarch64（鲲鹏/飞腾）使用 ARM 镜像，x86_64（海光/兆芯）使用 AMD64 镜像</li>
 * </ul>
 *
 * <p>本实现为模拟版，仅记录操作日志并返回 VIP；生产环境应通过 SSH/Ansible 远程执行 kubeadm 命令。</p>
 */
@Service
public class K8sBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(K8sBootstrapService.class);

    private final String podCidr;
    private final String serviceCidr;
    private final String k8sVersion;

    /**
     * 构造服务。
     *
     * @param podCidr     默认 Pod CIDR
     * @param serviceCidr 默认 Service CIDR
     * @param k8sVersion  默认 K8s 版本
     */
    public K8sBootstrapService(@Value("${app.xinchang.k8s.pod-cidr:10.244.0.0/16}") String podCidr,
                               @Value("${app.xinchang.k8s.service-cidr:10.96.0.0/12}") String serviceCidr,
                               @Value("${app.xinchang.k8s.version:v1.28.9}") String k8sVersion) {
        this.podCidr = podCidr;
        this.serviceCidr = serviceCidr;
        this.k8sVersion = k8sVersion;
    }

    /**
     * 初始化 K8s 集群。
     *
     * @param clusterId 集群 ID
     * @param request   创建请求
     * @return control-plane 端点 VIP
     */
    public String bootstrap(String clusterId, ClusterCreateRequest request) {
        log.info("K8s bootstrap start: cluster={} k8sVersion={} podCidr={} serviceCidr={}",
                clusterId, request.getK8sVersion(), request.getPodCidr(), request.getServiceCidr());

        List<XinchangNodeSpec> controlPlaneNodes = request.getNodes().stream()
                .filter(n -> "control-plane".equalsIgnoreCase(n.getRole()))
                .toList();
        List<XinchangNodeSpec> workerNodes = request.getNodes().stream()
                .filter(n -> "worker".equalsIgnoreCase(n.getRole()))
                .toList();

        // 1. 选首个 control-plane 节点作为 init 节点
        XinchangNodeSpec initNode = controlPlaneNodes.get(0);
        log.info("kubeadm init on first control-plane: host={} cpu={} os={}",
                initNode.getHostname(), initNode.getCpuArch(), initNode.getOsType());

        // 2. 生成 kubeadm 配置（含 SKE 定制）
        String kubeadmConfig = generateKubeadmConfig(request);
        log.debug("kubeadm config:\n{}", kubeadmConfig);

        // 3. 模拟 kubeadm init
        String controlPlaneVip = "192.168.200.10";
        log.info("kubeadm init completed: controlPlaneVip={}", controlPlaneVip);

        // 4. 模拟 kubeadm join worker
        for (XinchangNodeSpec worker : workerNodes) {
            log.info("kubeadm join worker: host={} cpu={} os={}",
                    worker.getHostname(), worker.getCpuArch(), worker.getOsType());
        }

        // 5. 应用 SKE 定制配置
        if (request.isSkeEnabled()) {
            applySkeCustomizations(clusterId, request);
        }

        log.info("K8s bootstrap completed: cluster={} endpoint={}", clusterId, controlPlaneVip);
        return controlPlaneVip;
    }

    /**
     * 加入 worker 节点（扩容时使用）。
     *
     * @param clusterId           集群 ID
     * @param controlPlaneEndpoint control-plane 端点
     * @param node                worker 节点规格
     */
    public void joinWorker(String clusterId, String controlPlaneEndpoint, XinchangNodeSpec node) {
        log.info("kubeadm join worker: cluster={} endpoint={} host={} cpu={} os={}",
                clusterId, controlPlaneEndpoint, node.getHostname(), node.getCpuArch(), node.getOsType());
    }

    /**
     * drain + 删除节点（缩容时使用）。
     *
     * @param clusterId           集群 ID
     * @param controlPlaneEndpoint control-plane 端点
     * @param hostname            节点主机名
     */
    public void drainAndRemoveNode(String clusterId, String controlPlaneEndpoint, String hostname) {
        log.info("drain and remove node: cluster={} endpoint={} host={}",
                clusterId, controlPlaneEndpoint, hostname);
    }

    /**
     * 销毁 K8s 集群（kubeadm reset）。
     *
     * @param clusterId           集群 ID
     * @param controlPlaneEndpoint control-plane 端点
     */
    public void teardown(String clusterId, String controlPlaneEndpoint) {
        log.info("kubeadm reset teardown: cluster={} endpoint={}", clusterId, controlPlaneEndpoint);
    }

    /**
     * 生成 kubeadm 配置 YAML。
     *
     * @param request 创建请求
     * @return kubeadm 配置 YAML 字符串
     */
    private String generateKubeadmConfig(ClusterCreateRequest request) {
        return """
                apiVersion: kubeadm.k8s.io/v1beta3
                kind: InitConfiguration
                metadata:
                  name: %s
                nodeRegistration:
                  criSocket: unix:///var/run/containerd/containerd.sock
                  kubeletExtraArgs:
                    cgroup-driver: systemd
                ---
                apiVersion: kubeadm.k8s.io/v1beta3
                kind: ClusterConfiguration
                kubernetesVersion: %s
                controlPlaneEndpoint: "%s:6443"
                networking:
                  podSubnet: %s
                  serviceSubnet: %s
                apiServer:
                  extraArgs:
                    feature-gates: "DynamicResourceAllocation=true"
                """.formatted(
                request.getClusterName(),
                request.getK8sVersion(),
                "192.168.200.10",
                request.getPodCidr(),
                request.getServiceCidr());
    }

    /**
     * 应用 SKE 定制配置。
     *
     * <p>SKE 定制项：</p>
     * <ul>
     *   <li>IPVS 模式（kube-proxy）</li>
     *   <li>关闭 swap（已通过节点 OS 镜像预置）</li>
     *   <li>kube-reserved 调整</li>
     *   <li>国产 CPU 适配：aarch64 / x86_64 镜像选择</li>
     * </ul>
     *
     * @param clusterId 集群 ID
     * @param request   创建请求
     */
    private void applySkeCustomizations(String clusterId, ClusterCreateRequest request) {
        log.info("Applying SKE customizations: cluster={}", clusterId);
        // IPVS 模式
        log.info("SKE: kube-proxy mode=ipvs");
        // kube-reserved
        log.info("SKE: kube-reserved=cpu=200m,memory=512Mi");
        // 国产 CPU 适配
        for (XinchangNodeSpec node : request.getNodes()) {
            String imageArch = switch (node.getCpuArch()) {
                case KUNPENG, PHYTIUM -> "arm64";
                case HYGON, ZHAOXIN -> "amd64";
            };
            log.info("SKE: node={} cpu={} imageArch={}", node.getHostname(), node.getCpuArch(), imageArch);
        }
    }
}