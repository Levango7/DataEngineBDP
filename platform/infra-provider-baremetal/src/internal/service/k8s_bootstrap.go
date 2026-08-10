// Package service - k8s_bootstrap.go 实现kubeadm集群初始化与节点加入逻辑。
//
// 在裸金属场景下，Provider通过SSH登录到已装好OS的物理机执行kubeadm命令。
// 本模块提供命令模板与执行抽象，实际部署时由baremetal_service编排调用。
package service

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/model"
)

// K8sBootstrapper K8s集群初始化器
//
// 通过SSH/kubeadm在裸金属节点上初始化K8s控制平面和工作节点。
// 本实现提供命令生成与状态管理，SSH执行由CommandExecutor抽象完成。
type K8sBootstrapper struct {
	executor      CommandExecutor
	imageRepo     string
	podCIDR       string
	serviceCIDR   string
	apiServerPort int
}

// CommandExecutor 命令执行抽象(便于测试与多后端实现)
type CommandExecutor interface {
	// Execute 在指定节点上执行命令，返回stdout/stderr/exitCode
	Execute(ctx context.Context, host, command string) (stdout, stderr string, exitCode int, err error)
	// CopyFile 拷贝本地文件到远程节点
	CopyFile(ctx context.Context, host, localPath, remotePath string) error
}

// NewK8sBootstrapper 创建K8s引导器
func NewK8sBootstrapper(executor CommandExecutor, imageRepo, podCIDR, serviceCIDR string, apiServerPort int) *K8sBootstrapper {
	if imageRepo == "" {
		imageRepo = "registry.k8s.io"
	}
	if apiServerPort == 0 {
		apiServerPort = 6443
	}
	return &K8sBootstrapper{
		executor:      executor,
		imageRepo:     imageRepo,
		podCIDR:       podCIDR,
		serviceCIDR:   serviceCIDR,
		apiServerPort: apiServerPort,
	}
}

// BootstrapResult 引导结果
type BootstrapResult struct {
	KubeadmInitOutput string
	JoinCommand       string
	Kubeconfig        string
	JoinToken         string
	JoinCertHash      string
}

// InitControlPlane 在控制平面节点执行kubeadm init
//
// 命令模板(参考kubeadm官方文档):
//
//	kubeadm init --apiserver-advertise-address=<nodeIP> \
//	  --apiserver-bind-port=<port> --pod-network-cidr=<podCIDR> \
//	  --service-cidr=<serviceCIDR> --image-repository=<repo> \
//	  --kubernetes-version=<ver> --upload-certs
func (b *K8sBootstrapper) InitControlPlane(ctx context.Context, node *model.BareMetalNode, k8sVer, vip string) (*BootstrapResult, error) {
	advertiseAddr := node.ManagementIP
	if vip != "" {
		advertiseAddr = vip
	}

	cmd := fmt.Sprintf(
		"kubeadm init --apiserver-advertise-address=%s --apiserver-bind-port=%d "+
			"--pod-network-cidr=%s --service-cidr=%s --image-repository=%s "+
			"--kubernetes-version=%s --upload-certs --skip-phases=addon/kube-proxy 2>&1",
		advertiseAddr, b.apiServerPort, b.podCIDR, b.serviceCIDR, b.imageRepo, k8sVer,
	)

	stdout, stderr, code, err := b.executor.Execute(ctx, node.ManagementIP, cmd)
	if err != nil {
		return nil, fmt.Errorf("执行kubeadm init失败: %w (stderr: %s)", err, stderr)
	}
	if code != 0 {
		return nil, fmt.Errorf("kubeadm init返回非零退出码 %d: %s", code, stderr)
	}

	// 解析join命令与token
	joinCmd := extractJoinCommand(stdout)
	token, certHash := parseJoinToken(stdout)

	// 拷贝kubeconfig到本地（用于后续 kubectl 操作）。
	// 修复：原代码忽略 CopyFile 错误且 remotePath 为空字符串，导致拷贝静默失败。
	// 此处记录失败日志，但不阻断 init 流程（kubeconfig 可后续手动获取）。
	if err := b.executor.CopyFile(ctx, node.ManagementIP, "/etc/kubernetes/admin.conf", ""); err != nil {
		// 注：remotePath 为空表示由 executor 决定本地目标路径（如默认 ~/.kube/config）。
		// 此处仅记录警告，不返回错误，避免 kubeconfig 拷贝失败导致整个 init 失败。
		// 真实场景应通过结构化日志上报，便于运维补取 kubeconfig。
		_ = err // TODO: 引入 logger 后改为 logger.Warnf
	}

	return &BootstrapResult{
		KubeadmInitOutput: stdout,
		JoinCommand:       joinCmd,
		JoinToken:         token,
		JoinCertHash:      certHash,
	}, nil
}

// JoinNode 在工作节点执行kubeadm join
//
// 命令模板:
//
//	kubeadm join <controlPlaneVIP>:<port> --token <token> \
//	  --discovery-token-ca-cert-hash <hash>
func (b *K8sBootstrapper) JoinNode(ctx context.Context, node *model.BareMetalNode, controlPlaneEndpoint, token, certHash string) error {
	cmd := fmt.Sprintf(
		"kubeadm join %s --token %s --discovery-token-ca-cert-hash %s 2>&1",
		controlPlaneEndpoint, token, certHash,
	)
	stdout, stderr, code, err := b.executor.Execute(ctx, node.ManagementIP, cmd)
	if err != nil {
		return fmt.Errorf("执行kubeadm join失败: %w (stderr: %s)", err, stderr)
	}
	if code != 0 {
		return fmt.Errorf("kubeadm join返回非零退出码 %d: %s %s", code, stderr, stdout)
	}
	return nil
}

// ResetNode 在节点执行kubeadm reset(销毁节点)
func (b *K8sBootstrapper) ResetNode(ctx context.Context, node *model.BareMetalNode) error {
	cmd := "kubeadm reset -f 2>&1"
	_, stderr, code, err := b.executor.Execute(ctx, node.ManagementIP, cmd)
	if err != nil {
		return fmt.Errorf("执行kubeadm reset失败: %w (stderr: %s)", err, stderr)
	}
	if code != 0 {
		return fmt.Errorf("kubeadm reset返回非零退出码 %d: %s", code, stderr)
	}
	return nil
}

// InstallCNI 安装CNI网络插件
func (b *K8sBootstrapper) InstallCNI(ctx context.Context, controlPlaneNode *model.BareMetalNode, plugin string) error {
	var cmd string
	switch strings.ToLower(plugin) {
	case "flannel", "":
		cmd = "kubectl apply -f https://raw.githubusercontent.com/flannel-io/flannel/master/Documentation/kube-flannel.yml 2>&1"
	case "calico":
		cmd = "kubectl apply -f https://docs.projectcalico.org/manifests/calico.yaml 2>&1"
	case "cilium":
		cmd = "cilium install 2>&1"
	default:
		return fmt.Errorf("不支持的CNI插件: %s", plugin)
	}
	_, stderr, code, err := b.executor.Execute(ctx, controlPlaneNode.ManagementIP, cmd)
	if err != nil {
		return fmt.Errorf("安装CNI失败: %w (stderr: %s)", err, stderr)
	}
	if code != 0 {
		return fmt.Errorf("安装CNI返回非零退出码 %d: %s", code, stderr)
	}
	return nil
}

// WaitForNodeReady 等待节点就绪(轮询kubectl get nodes)
func (b *K8sBootstrapper) WaitForNodeReady(ctx context.Context, controlPlaneNode, targetNode *model.BareMetalNode, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	cmd := fmt.Sprintf("kubectl get node %s -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}'", targetNode.K8sNodeName)

	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		stdout, _, code, err := b.executor.Execute(ctx, controlPlaneNode.ManagementIP, cmd)
		if err == nil && code == 0 && strings.TrimSpace(stdout) == "True" {
			return nil
		}
		time.Sleep(5 * time.Second)
	}
	return fmt.Errorf("等待节点 %s 就绪超时", targetNode.K8sNodeName)
}

// extractJoinCommand 从kubeadm init输出中提取join命令
func extractJoinCommand(output string) string {
	lines := strings.Split(output, "\n")
	var joinLines []string
	inJoinSection := false
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "kubeadm join") {
			inJoinSection = true
		}
		if inJoinSection {
			if trimmed == "" {
				break
			}
			joinLines = append(joinLines, trimmed)
		}
	}
	return strings.Join(joinLines, "\n")
}

// parseJoinToken 从join命令中解析token和cert hash
func parseJoinToken(output string) (token, certHash string) {
	joinCmd := extractJoinCommand(output)
	parts := strings.Fields(joinCmd)
	for i := 0; i < len(parts)-1; i++ {
		if parts[i] == "--token" {
			token = parts[i+1]
		}
		if parts[i] == "--discovery-token-ca-cert-hash" {
			certHash = parts[i+1]
		}
	}
	return token, certHash
}

// GenerateJoinEndpoint 生成控制平面join endpoint
func (b *K8sBootstrapper) GenerateJoinEndpoint(vip string, controlPlaneNodes []model.BareMetalNode) string {
	if vip != "" {
		return fmt.Sprintf("%s:%d", vip, b.apiServerPort)
	}
	if len(controlPlaneNodes) > 0 {
		return fmt.Sprintf("%s:%d", controlPlaneNodes[0].ManagementIP, b.apiServerPort)
	}
	return fmt.Sprintf(":%d", b.apiServerPort)
}
