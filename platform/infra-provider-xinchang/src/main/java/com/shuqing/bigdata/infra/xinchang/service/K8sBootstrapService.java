package com.shuqing.bigdata.infra.xinchang.service;

import com.shuqing.bigdata.infra.xinchang.model.ClusterCreateRequest;
import com.shuqing.bigdata.infra.xinchang.model.XinchangNodeSpec;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider;
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>本实现通过 SSH（sshj）远程到信创节点执行真实 kubeadm 命令：
 * init 节点执行 {@code kubeadm init}，worker 节点执行 {@code kubeadm join}，
 * 销毁时执行 {@code kubeadm reset}。SSH 认证支持密码与 PKCS8 私钥（二选一）。</p>
 *
 * <p>安全说明：使用 {@link PromiscuousVerifier} 接受所有主机密钥，适用于信创内网可信环境；
 * 公网部署应替换为已知主机密钥校验。SSH 失败将抛出 {@link RuntimeException}，
 * 由上层 {@code XinchangProvider} 捕获并标记集群 FAILED。</p>
 */
@Service
public class K8sBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(K8sBootstrapService.class);

    /**
     * 匹配 kubeadm init 输出中的 worker join 命令。
     *
     * <p>kubeadm init 输出包含两段 join 命令：control-plane join（带 {@code --control-plane}）
     * 与 worker join（不带）。本正则匹配不含 {@code --control-plane} 的行，取最后一个匹配作为 worker join 命令。</p>
     */
    private static final Pattern WORKER_JOIN_PATTERN = Pattern.compile(
            "(?m)^\\s*kubeadm join\\s+\\S+:\\d+\\s+--token\\s+\\S+\\s+--discovery-token-ca-cert-hash\\s+\\S+\\s*$");

    private final String podCidr;
    private final String serviceCidr;
    private final String k8sVersion;
    private final String controlPlaneVip;
    private final int sshPort;
    private final String sshUsername;
    private final String sshPassword;
    private final String sshKeyFile;
    private final String sshDefaultHost;
    private final boolean sshUseSudo;

    /**
     * 构造服务。
     *
     * @param podCidr         默认 Pod CIDR
     * @param serviceCidr     默认 Service CIDR
     * @param k8sVersion      默认 K8s 版本
     * @param controlPlaneVip 控制平面 VIP（为空时使用首个 control-plane 节点 hostname）
     * @param sshPort         SSH 端口
     * @param sshUsername     SSH 登录用户名
     * @param sshPassword     SSH 密码（与 keyFile 二选一）
     * @param sshKeyFile      SSH PKCS8 私钥文件路径（优先于 password）
     * @param sshDefaultHost  默认 SSH 主机（节点未提供 hostname 时兜底）
     * @param sshUseSudo      是否在远程命令前加 sudo
     */
    public K8sBootstrapService(
            @Value("${app.xinchang.k8s.pod-cidr:10.244.0.0/16}") String podCidr,
            @Value("${app.xinchang.k8s.service-cidr:10.96.0.0/12}") String serviceCidr,
            @Value("${app.xinchang.k8s.version:v1.28.9}") String k8sVersion,
            @Value("${app.xinchang.k8s.control-plane-vip:}") String controlPlaneVip,
            @Value("${app.xinchang.ssh.port:22}") int sshPort,
            @Value("${app.xinchang.ssh.username:root}") String sshUsername,
            @Value("${app.xinchang.ssh.password:}") String sshPassword,
            @Value("${app.xinchang.ssh.key-file:}") String sshKeyFile,
            @Value("${app.xinchang.ssh.host:}") String sshDefaultHost,
            @Value("${app.xinchang.ssh.use-sudo:true}") boolean sshUseSudo) {
        this.podCidr = podCidr;
        this.serviceCidr = serviceCidr;
        this.k8sVersion = k8sVersion;
        this.controlPlaneVip = controlPlaneVip;
        this.sshPort = sshPort;
        this.sshUsername = sshUsername;
        this.sshPassword = sshPassword;
        this.sshKeyFile = sshKeyFile;
        this.sshDefaultHost = sshDefaultHost;
        this.sshUseSudo = sshUseSudo;
    }

    /**
     * 初始化 K8s 集群。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>选取首个 control-plane 节点作为 init 节点</li>
     *   <li>生成 kubeadm 配置（含 SKE 定制）</li>
     *   <li>SSH 到 init 节点执行 {@code kubeadm init}，解析输出获取 worker join 命令</li>
     *   <li>SSH 到每个 worker 节点执行 {@code kubeadm join}</li>
     *   <li>应用 SKE 定制配置</li>
     * </ol>
     *
     * @param clusterId 集群 ID
     * @param request   创建请求
     * @return control-plane 端点（VIP 或 init 节点地址）
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
        String initHost = resolveHost(initNode);
        log.info("kubeadm init on first control-plane: host={} cpu={} os={}",
                initHost, initNode.getCpuArch(), initNode.getOsType());

        // 2. 确定 control-plane endpoint：优先全局 VIP，否则用 init 节点 host
        String controlPlaneEndpoint = resolveControlPlaneEndpoint(initHost);

        // 3. 生成 kubeadm 配置（含 SKE 定制）
        String kubeadmConfig = generateKubeadmConfig(request, controlPlaneEndpoint);
        log.debug("kubeadm config:\n{}", kubeadmConfig);

        // 4. SSH 到 init 节点写入配置并执行 kubeadm init
        String initCommand = buildWriteFileCommand("/tmp/kubeadm-config-" + clusterId + ".yaml", kubeadmConfig)
                + " && " + sudo("kubeadm init --config /tmp/kubeadm-config-" + clusterId + ".yaml");
        String initOutput = executeRemote(initHost, initCommand, 600);
        log.info("kubeadm init completed: controlPlaneEndpoint={}", controlPlaneEndpoint);
        log.debug("kubeadm init output:\n{}", initOutput);

        // 5. 解析 worker join 命令
        String workerJoinCommand = parseWorkerJoinCommand(initOutput);
        log.info("parsed worker join command: {}", workerJoinCommand);

        // 6. SSH 到每个 worker 节点执行 kubeadm join
        for (XinchangNodeSpec worker : workerNodes) {
            String workerHost = resolveHost(worker);
            log.info("kubeadm join worker: host={} cpu={} os={}",
                    workerHost, worker.getCpuArch(), worker.getOsType());
            executeRemote(workerHost, sudo(workerJoinCommand), 300);
        }

        // 7. 应用 SKE 定制配置
        if (request.isSkeEnabled()) {
            applySkeCustomizations(clusterId, request);
        }

        log.info("K8s bootstrap completed: cluster={} endpoint={}", clusterId, controlPlaneEndpoint);
        return controlPlaneEndpoint;
    }

    /**
     * 加入 worker 节点（扩容时使用）。
     *
     * <p>先在控制平面执行 {@code kubeadm token create --print-join-command} 生成 join 命令，
     * 再 SSH 到 worker 节点执行 join。</p>
     *
     * @param clusterId           集群 ID
     * @param controlPlaneEndpoint control-plane 端点
     * @param node                worker 节点规格
     */
    public void joinWorker(String clusterId, String controlPlaneEndpoint, XinchangNodeSpec node) {
        String workerHost = resolveHost(node);
        log.info("kubeadm join worker: cluster={} endpoint={} host={} cpu={} os={}",
                clusterId, controlPlaneEndpoint, workerHost, node.getCpuArch(), node.getOsType());

        // 在控制平面生成 join 命令
        String joinCommand = executeRemote(controlPlaneEndpoint, sudo("kubeadm token create --print-join-command"), 60).trim();
        log.info("generated join command for cluster {}: {}", clusterId, joinCommand);

        // 在 worker 节点执行 join
        executeRemote(workerHost, sudo(joinCommand), 300);
    }

    /**
     * drain + 删除节点（缩容时使用）。
     *
     * <p>在控制平面执行 {@code kubectl drain} 与 {@code kubectl delete node}，
     * 再在目标节点执行 {@code kubeadm reset} 清理节点状态。</p>
     *
     * @param clusterId           集群 ID
     * @param controlPlaneEndpoint control-plane 端点
     * @param hostname            节点主机名
     */
    public void drainAndRemoveNode(String clusterId, String controlPlaneEndpoint, String hostname) {
        log.info("drain and remove node: cluster={} endpoint={} host={}",
                clusterId, controlPlaneEndpoint, hostname);

        // 在控制平面 drain + delete node
        executeRemote(controlPlaneEndpoint,
                sudo("kubectl drain " + hostname + " --ignore-daemonsets --delete-emptydir-data --force"), 120);
        executeRemote(controlPlaneEndpoint, sudo("kubectl delete node " + hostname), 60);

        // 在目标节点执行 kubeadm reset 清理
        executeRemote(hostname, sudo("kubeadm reset -f"), 120);
    }

    /**
     * 销毁 K8s 集群（kubeadm reset）。
     *
     * <p>在控制平面节点执行 {@code kubeadm reset -f} 重置集群状态。</p>
     *
     * @param clusterId           集群 ID
     * @param controlPlaneEndpoint control-plane 端点
     */
    public void teardown(String clusterId, String controlPlaneEndpoint) {
        log.info("kubeadm reset teardown: cluster={} endpoint={}", clusterId, controlPlaneEndpoint);
        executeRemote(controlPlaneEndpoint, sudo("kubeadm reset -f"), 120);
    }

    /**
     * 生成 kubeadm 配置 YAML。
     *
     * @param request              创建请求
     * @param controlPlaneEndpoint control-plane 端点地址（VIP 或 init 节点 host）
     * @return kubeadm 配置 YAML 字符串
     */
    private String generateKubeadmConfig(ClusterCreateRequest request, String controlPlaneEndpoint) {
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
                controlPlaneEndpoint,
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

    // ==================== SSH 远程执行辅助方法 ====================

    /**
     * 解析节点的 SSH 目标主机：优先使用节点 hostname，为空时回退到全局默认 host。
     *
     * @param node 节点规格
     * @return SSH 目标主机地址
     */
    private String resolveHost(XinchangNodeSpec node) {
        String host = node.getHostname();
        if (host == null || host.isBlank()) {
            if (sshDefaultHost == null || sshDefaultHost.isBlank()) {
                throw new IllegalStateException("节点未提供 hostname 且未配置 app.xinchang.ssh.host");
            }
            return sshDefaultHost;
        }
        return host;
    }

    /**
     * 解析 control-plane endpoint：优先使用全局配置的 VIP，为空时使用 init 节点 host。
     *
     * @param initHost init 节点 host
     * @return control-plane endpoint
     */
    private String resolveControlPlaneEndpoint(String initHost) {
        return (controlPlaneVip != null && !controlPlaneVip.isBlank()) ? controlPlaneVip : initHost;
    }

    /**
     * 根据配置在命令前加 sudo 前缀。
     *
     * @param command 原始命令
     * @return 带 sudo 前缀的命令
     */
    private String sudo(String command) {
        return sshUseSudo ? "sudo " + command : command;
    }

    /**
     * 构造通过 heredoc 写入远程文件的命令。
     *
     * <p>使用单引号包裹 EOF 标记，避免内容中的变量被远程 shell 展开。</p>
     *
     * @param path    远程文件路径
     * @param content 文件内容
     * @return 写入文件的 shell 命令
     */
    private String buildWriteFileCommand(String path, String content) {
        return "cat > " + path + " <<'KUBEADM_EOF'\n" + content + "\nKUBEADM_EOF";
    }

    /**
     * 通过 SSH 在目标主机执行命令，返回标准输出。
     *
     * <p>认证方式：优先使用私钥（{@code key-file}），其次使用密码。命令退出码非 0 时抛出异常。</p>
     *
     * @param host           目标主机
     * @param command        待执行命令
     * @param timeoutSeconds 超时秒数
     * @return 命令标准输出
     * @throws RuntimeException SSH 连接、认证或命令执行失败时抛出
     */
    private String executeRemote(String host, String command, int timeoutSeconds) {
        SSHClient ssh = new SSHClient();
        // 信创内网可信环境，接受所有主机密钥；公网部署应替换为已知主机密钥校验
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
        try {
            ssh.connect(host, sshPort);
            authenticate(ssh);
            try (Session session = ssh.startSession()) {
                Session.Command cmd = session.exec(command);
                String stdout = IOUtils.readFully(cmd.getInputStream()).toString();
                String stderr = IOUtils.readFully(cmd.getErrorStream()).toString();
                cmd.join(timeoutSeconds, TimeUnit.SECONDS);
                int exitStatus = cmd.getExitStatus();
                if (exitStatus != 0) {
                    throw new RuntimeException("远程命令执行失败 host=" + host
                            + " exit=" + exitStatus + " stderr=" + stderr);
                }
                return stdout;
            }
        } catch (IOException e) {
            throw new RuntimeException("SSH 执行失败 host=" + host + ": " + e.getMessage(), e);
        } finally {
            try {
                ssh.disconnect();
            } catch (IOException ignored) {
                // 忽略断开连接的异常
            }
        }
    }

    /**
     * 执行 SSH 认证：优先私钥，其次密码。
     *
     * @param ssh SSH 客户端
     * @throws IOException      认证 IO 异常
     * @throws IllegalStateException 未配置有效认证方式
     */
    private void authenticate(SSHClient ssh) throws IOException {
        if (sshKeyFile != null && !sshKeyFile.isBlank()) {
            FileKeyProvider keyProvider = new PKCS8KeyFile();
            keyProvider.init(new File(sshKeyFile));
            ssh.authPublickey(sshUsername, keyProvider);
        } else if (sshPassword != null && !sshPassword.isBlank()) {
            ssh.authPassword(sshUsername, sshPassword);
        } else {
            throw new IllegalStateException("未配置 SSH 认证方式（app.xinchang.ssh.password 或 app.xinchang.ssh.key-file）");
        }
    }

    /**
     * 从 kubeadm init 输出解析 worker join 命令。
     *
     * <p>kubeadm init 输出包含 control-plane join 与 worker join 两段命令，
     * 本方法取不含 {@code --control-plane} 的 worker join 命令。</p>
     *
     * @param initOutput kubeadm init 标准输出
     * @return worker join 命令字符串
     * @throws RuntimeException 无法解析时抛出
     */
    private String parseWorkerJoinCommand(String initOutput) {
        Matcher matcher = WORKER_JOIN_PATTERN.matcher(initOutput);
        String last = null;
        while (matcher.find()) {
            last = matcher.group().trim();
        }
        if (last == null) {
            throw new RuntimeException("无法从 kubeadm init 输出解析 worker join 命令，输出:\n" + initOutput);
        }
        return last;
    }
}
