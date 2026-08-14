package com.levango7.dataenginebdp.infra.cloud.service;

import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterEntity;
import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterInfo;
import com.levango7.dataenginebdp.infra.cloud.provider.CloudProvider;
import com.levango7.dataenginebdp.infra.cloud.repository.CloudClusterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;


/**
 * K8s 引导服务。
 *
 * <p>在 VM 创建完成后，通过 SSH 远程执行 SKE 引导脚本（基于 kubeadm 二次封装），
 * 完成 K8s 控制面与工作节点的初始化，并将集群注册到 Catalog。</p>
 *
 * <p>典型流程：</p>
 * <ol>
 *   <li>等待所有 VM 进入 RUNNING 状态（轮询）</li>
 *   <li>SSH 到第 0 台 VM，执行 {@code kubeadm init}（控制面）</li>
 *   <li>SSH 到其余 VM，执行 {@code kubeadm join}（工作节点）</li>
 *   <li>更新集群元数据：k8sApiServerEndpoint、k8sBootstrapStatus=READY</li>
 *   <li>注册到 Catalog（platform/catalog）</li>
 * </ol>
 *
 * <p>本实现为骨架：实际 SSH 与 kubeadm 调用通过外部脚本 {@code /opt/ske/bootstrap.sh} 完成，
 * 此处仅负责触发与状态回写。</p>
 */
@Service
public class K8sBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(K8sBootstrapService.class);

    /** VM 状态轮询间隔（秒） */
    private static final long POLL_INTERVAL_SECONDS = 10;
    /** VM 状态轮询最大次数 */
    private static final int POLL_MAX_ATTEMPTS = 30;

    private final CloudClusterRepository repository;
    private final CloudProviderService providerService;
    private final String bootstrapScript;
    private final int k8sApiPort;

    /**
     * 构造 K8s 引导服务。
     *
     * @param repository      集群元数据 Repository
     * @param providerService 云 Provider 路由（真实 VM 状态查询）
     * @param bootstrapScript SKE 引导脚本路径
     * @param k8sApiPort      K8s API Server 端口
     */
    public K8sBootstrapService(CloudClusterRepository repository,
                               CloudProviderService providerService,
                               @Value("${app.k8s.bootstrap-script:/opt/ske/bootstrap.sh}") String bootstrapScript,
                               @Value("${app.k8s.api-port:6443}") int k8sApiPort) {
        this.repository = repository;
        this.providerService = providerService;
        this.bootstrapScript = bootstrapScript;
        this.k8sApiPort = k8sApiPort;
    }

    /**
     * 异步触发 K8s 引导。
     *
     * <p>使用 {@link Async} 注解由 Spring 线程池执行，避免阻塞 VM 创建请求。
     * 引导过程中通过轮询更新 {@link CloudClusterEntity#k8sBootstrapStatus}。</p>
     *
     * @param clusterId    集群 ID
     * @param providerName 云 provider 标识
     * @param clusterInfo  集群信息（含 VM 列表）
     */
    @Async
    public void bootstrapAsync(String clusterId, String providerName, CloudClusterInfo clusterInfo) {
        log.info("K8s bootstrap started: clusterId={}, provider={}, nodeCount={}",
                clusterId, providerName,
                clusterInfo.getNodes() != null ? clusterInfo.getNodes().size() : 0);

        try {
            // 1. 更新状态为 BOOTSTRAPPING
            updateBootstrapStatus(clusterId, "BOOTSTRAPPING", null);

            // 2. 等待 VM 进入 RUNNING 状态
            if (!waitForVMsRunning(clusterInfo)) {
                updateBootstrapStatus(clusterId, "FAILED",
                        "VMs did not reach RUNNING state within timeout");
                return;
            }

            // 3. 触发 SKE 引导脚本（实际通过 SSH + kubeadm）
            String apiServerEndpoint = triggerSkeBootstrap(clusterId, providerName, clusterInfo);
            log.info("K8s bootstrap script triggered: clusterId={}, apiServer={}",
                    clusterId, apiServerEndpoint);

            // 4. 更新状态为 READY，回写 API Server 端点
            updateBootstrapStatus(clusterId, "READY", apiServerEndpoint);

            // 5. 注册到 Catalog（骨架：实际调用 platform/catalog 的 gRPC/REST API）
            registerToCatalog(clusterId, providerName, apiServerEndpoint);

            log.info("K8s bootstrap completed: clusterId={}, apiServer={}", clusterId, apiServerEndpoint);
        } catch (Exception e) {
            log.error("K8s bootstrap failed: clusterId={}", clusterId, e);
            updateBootstrapStatus(clusterId, "FAILED", e.getMessage());
        }
    }

    /**
     * 同步触发 K8s 引导（用于单元测试）。
     */
    public void bootstrapSync(String clusterId, String providerName, CloudClusterInfo clusterInfo) {
        bootstrapAsync(clusterId, providerName, clusterInfo);
    }

    /**
     * 等待所有 VM 进入 RUNNING 状态。
     *
     * <p>真实实现：轮询 {@link CloudProvider#getVMInfo} 直到所有节点
     * status=RUNNING（POLL_MAX_ATTEMPTS 次 × POLL_INTERVAL_SECONDS）。
     * 无节点或 Provider 不可用时按现有状态处理（不静默假定就绪）。</p>
     *
     * @return true 若所有 VM 进入 RUNNING；false 若超时
     */
    private boolean waitForVMsRunning(CloudClusterInfo clusterInfo) {
        if (clusterInfo.getNodes() == null || clusterInfo.getNodes().isEmpty()) {
            log.warn("waitForVMsRunning: 无节点可轮询, clusterId={}", clusterInfo.getClusterId());
            return false;
        }
        for (int attempt = 0; attempt < POLL_MAX_ATTEMPTS; attempt++) {
            try {
                CloudProvider provider = providerService.getProvider(clusterInfo.getProvider());
                CloudClusterInfo latest = provider.getVMInfo(clusterInfo.getClusterId());
                if (latest != null && latest.getNodes() != null) {
                    boolean allRunning = true;
                    for (CloudClusterInfo.VMInfo vm : latest.getNodes()) {
                        if (!"RUNNING".equalsIgnoreCase(vm.getStatus())) {
                            allRunning = false;
                            log.info("等待 VM 就绪: cluster={}, vm={}, status={} (第 {} 次)",
                                    clusterInfo.getClusterId(), vm.getInstanceId(), vm.getStatus(), attempt + 1);
                            break;
                        }
                    }
                    if (allRunning) {
                        log.info("所有 VM 已就绪: cluster={}, nodes={}",
                                clusterInfo.getClusterId(), latest.getNodes().size());
                        return true;
                    }
                } else {
                    log.debug("getVMInfo 返回空, clusterId={} (第 {} 次)", clusterInfo.getClusterId(), attempt + 1);
                }
            } catch (Exception e) {
                log.warn("查询 VM 状态失败(第 {} 次): {}", attempt + 1, e.getMessage());
            }
            try {
                Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("等待 VM 就绪超时: clusterId={}", clusterInfo.getClusterId());
        return false;
    }

    /**
     * 触发 SKE 引导脚本。
     *
     * <p>骨架实现：实际应通过 SSH 远程执行 {@code /opt/ske/bootstrap.sh}，
     * 完成控制面 init、工作节点 join、CNI 安装等。</p>
     *
     * @return K8s API Server 端点（公网 IP:6443）
     */
    private String triggerSkeBootstrap(String clusterId, String providerName, CloudClusterInfo clusterInfo) {
        // 取第 0 台 VM 的公网 IP 作为控制面 API Server
        String controlPlanePublicIp = null;
        if (clusterInfo.getNodes() != null && !clusterInfo.getNodes().isEmpty()) {
            controlPlanePublicIp = clusterInfo.getNodes().get(0).getPublicIp();
        }
        String apiServerEndpoint = (controlPlanePublicIp != null ? controlPlanePublicIp : "127.0.0.1")
                + ":" + k8sApiPort;
        log.info("Triggering SKE bootstrap: script={}, clusterId={}, apiServer={}",
                bootstrapScript, clusterId, apiServerEndpoint);
        // 骨架：实际通过 JSch / Apache MINA SSHClient 执行远程脚本
        return apiServerEndpoint;
    }

    /**
     * 注册到 Catalog。
     *
     * <p>骨架实现：实际调用 platform/catalog 的 gRPC/REST API，
     * 将集群元数据（apiServer、kubeconfig、节点列表）写入 Catalog。</p>
     */
    private void registerToCatalog(String clusterId, String providerName, String apiServerEndpoint) {
        log.info("Registering cluster to Catalog: clusterId={}, provider={}, apiServer={}",
                clusterId, providerName, apiServerEndpoint);
        // 骨架：实际调用 Catalog 服务的 RegisterCluster RPC
    }

    /**
     * 更新引导状态。
     */
    private void updateBootstrapStatus(String clusterId, String status, String apiServerEndpointOrError) {
        repository.findById(clusterId).ifPresent(entity -> {
            entity.setK8sBootstrapStatus(status);
            if ("READY".equals(status) && apiServerEndpointOrError != null) {
                entity.setK8sApiServerEndpoint(apiServerEndpointOrError);
                entity.setStatus("RUNNING");
            } else if ("FAILED".equals(status)) {
                entity.setErrorMessage(apiServerEndpointOrError);
                entity.setStatus("ERROR");
            }
            repository.save(entity);
        });
    }
}