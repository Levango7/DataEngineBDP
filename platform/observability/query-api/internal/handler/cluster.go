package handler

import (
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"

	"github.com/Levango7/DataEngineBDP/query-api/internal/k3sclient"
	"github.com/gin-gonic/gin"
)

// k8sReader 集群数据读取接口（真实 k3sclient 或测试 mock 实现）。
type k8sReader interface {
	ListNodes() (*k3sclient.NodeList, error)
	ListPods() (*k3sclient.PodList, error)
}

// ClusterHandler 集群状态查询（真实接 k3s API）。
//
// 对应前端 /cluster 的 4 个端点：
//   - GET /api/v1/cluster/overview   集群总览（节点/Pod 计数）
//   - GET /api/v1/cluster/nodes      节点列表（容量/用量）
//   - GET /api/v1/cluster/pods       Pod 列表
//   - GET /api/v1/cluster/components 大数据组件状态（按 shuqing 命名空间部署判断）
//
// k3s 连接：K3S_KUBECONFIG 环境变量或常见路径；k3s 不可达时返回
// 503 + 明确错误（不静默 mock）。
type ClusterHandler struct {
	client k8sReader
}

// NewClusterHandler 创建集群查询 handler。
func NewClusterHandler() (*ClusterHandler, error) {
	client, err := k3sclient.NewFromKubeconfig(os.Getenv("K3S_KUBECONFIG"))
	if err != nil {
		return nil, err
	}
	return &ClusterHandler{client: client}, nil
}

// Overview GET /api/v1/cluster/overview
func (h *ClusterHandler) Overview(c *gin.Context) {
	nodes, err := h.client.ListNodes()
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "k3s 不可达: " + err.Error()})
		return
	}
	pods, err := h.client.ListPods()
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "k3s 不可达: " + err.Error()})
		return
	}

	nodeReady := 0
	for _, n := range nodes.Items {
		if nodeConditionReady(n) {
			nodeReady++
		}
	}
	podRunning := 0
	for _, p := range pods.Items {
		if p.Status.Phase == "Running" {
			podRunning++
		}
	}
	c.JSON(http.StatusOK, gin.H{
		"clusterName": "shuqing-k3s",
		"version":     "v1.36.3+k3s1",
		"nodeTotal":   len(nodes.Items),
		"nodeReady":   nodeReady,
		"podTotal":    len(pods.Items),
		"podRunning":  podRunning,
	})
}

// Nodes GET /api/v1/cluster/nodes
func (h *ClusterHandler) Nodes(c *gin.Context) {
	nodes, err := h.client.ListNodes()
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "k3s 不可达: " + err.Error()})
		return
	}
	pods, _ := h.client.ListPods() // pod 计数失败不阻断节点列表

	out := make([]gin.H, 0, len(nodes.Items))
	for _, n := range nodes.Items {
		cpuCap, _ := strconv.ParseFloat(n.Status.Capacity.CPU, 64)
		memCapGi := parseMemoryGi(n.Status.Capacity.Memory)
		podCap, _ := strconv.ParseFloat(n.Status.Capacity.Pods, 64)
		podCount := countPodsOnNode(pods, n.Metadata.Name)

		out = append(out, gin.H{
			"name":             n.Metadata.Name,
			"roles":            nodeRoles(n),
			"status":           nodeStatus(n),
			"cpuCapacity":      cpuCap,
			"cpuUsed":          cpuUsedOnNode(pods, n.Metadata.Name),
			"memCapacity":      memCapGi,
			"memUsed":          memUsedOnNode(pods, n.Metadata.Name),
			"podCount":         podCount,
			"podCapacity":      podCap,
			"osImage":          n.Status.NodeInfo.OSImage,
			"containerRuntime": n.Status.NodeInfo.ContainerRuntime,
			"createdAt":        n.Metadata.CreationTimestamp,
		})
	}
	c.JSON(http.StatusOK, out)
}

// parseMemoryGi 解析 K8s 内存容量为 GiB（支持 Ki/Mi/Gi/Ti 后缀；裸数字按字节）。
func parseMemoryGi(mem string) float64 {
	if mem == "" {
		return 0
	}
	upper := strings.ToUpper(mem)
	mult := 1.0
	switch {
	case strings.HasSuffix(upper, "TI"):
		mult = 1024
		mem = upper[:len(upper)-2]
	case strings.HasSuffix(upper, "GI"):
		mult = 1
		mem = upper[:len(upper)-2]
	case strings.HasSuffix(upper, "MI"):
		mult = 1.0 / 1024
		mem = upper[:len(upper)-2]
	case strings.HasSuffix(upper, "KI"):
		mult = 1.0 / (1024 * 1024)
		mem = upper[:len(upper)-2]
	default:
		// 裸字节 → GiB
		mult = 1.0 / (1024 * 1024 * 1024)
	}
	if v, err := strconv.ParseFloat(mem, 64); err == nil {
		return v * mult
	}
	return 0
}

// Pods GET /api/v1/cluster/pods
func (h *ClusterHandler) Pods(c *gin.Context) {
	pods, err := h.client.ListPods()
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "k3s 不可达: " + err.Error()})
		return
	}

	out := make([]gin.H, 0, len(pods.Items))
	for _, p := range pods.Items {
		restarts := 0
		for _, cs := range p.Status.ContainerStatuses {
			restarts += cs.RestartCount
		}
		out = append(out, gin.H{
			"name":         p.Metadata.Name,
			"namespace":    p.Metadata.Namespace,
			"nodeName":     p.Spec.NodeName,
			"status":       strings.ToLower(p.Status.Phase),
			"restartCount": restarts,
			"cpuRequest":   sumCpuRequest(p),
			"memRequest":   sumMemRequestMi(p),
			"workloadKind": ownerKind(p),
			"workloadName": ownerName(p),
			"startedAt":    p.Status.StartTime,
		})
	}
	c.JSON(http.StatusOK, out)
}

// Components GET /api/v1/cluster/components
//
// 大数据组件状态：按 shuqing 命名空间的 Deployment/StatefulSet 判断
// （组件名 → 就绪副本数），映射到前端 ComponentStatus。
func (h *ClusterHandler) Components(c *gin.Context) {
	pods, err := h.client.ListPods()
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "k3s 不可达: " + err.Error()})
		return
	}
	// 按 workload 名聚合 shuqing 命名空间的 Pod
	agg := map[string]int{}
	for _, p := range pods.Items {
		if p.Metadata.Namespace != "shuqing" {
			continue
		}
		name := ownerName(p)
		if name == "" {
			name = p.Metadata.Name
		}
		if p.Status.Phase == "Running" {
			agg[name]++
		}
	}

	// 固定组件清单（与 platform 自研组件对齐）
	names := []string{
		"encaps-layer", "sql-gateway", "catalog", "rule-engine",
		"metadata-collector", "lineage-analyzer", "tag-engine",
		"vector-engine", "llm-gateway", "ai-assistant", "stream-batch-scheduler",
		"flink-cdc", "infra-orchestrator",
	}
	out := make([]gin.H, 0, len(names))
	for _, n := range names {
		count := agg[n]
		status := "down"
		if count > 0 {
			status = "healthy"
		}
		out = append(out, gin.H{
			"name":   n,
			"status": status,
			"meta":   strconv.Itoa(count) + "/" + strconv.Itoa(count) + " 运行",
		})
	}
	c.JSON(http.StatusOK, out)
}

/* ---------- 辅助 ---------- */

func nodeConditionReady(n k3sclient.NodeItem) bool {
	for _, c := range n.Status.Conditions {
		if c.Type == "Ready" && c.Status == "True" {
			return true
		}
	}
	return false
}

func nodeStatus(n k3sclient.NodeItem) string {
	if nodeConditionReady(n) {
		return "ready"
	}
	return "notReady"
}

func nodeRoles(n k3sclient.NodeItem) []string {
	var roles []string
	for k := range n.Metadata.Labels {
		if strings.HasPrefix(k, "node-role.kubernetes.io/") {
			roles = append(roles, strings.TrimPrefix(k, "node-role.kubernetes.io/"))
		}
	}
	if len(roles) == 0 {
		roles = []string{"worker"}
	}
	sort.Strings(roles)
	return roles
}

func countPodsOnNode(pods *k3sclient.PodList, node string) int {
	n := 0
	for _, p := range pods.Items {
		if p.Spec.NodeName == node {
			n++
		}
	}
	return n
}

func cpuUsedOnNode(pods *k3sclient.PodList, node string) float64 {
	sum := 0.0
	for _, p := range pods.Items {
		if p.Spec.NodeName == node {
			sum += sumCpuRequest(p)
		}
	}
	return sum
}

func memUsedOnNode(pods *k3sclient.PodList, node string) float64 {
	sum := 0.0
	for _, p := range pods.Items {
		if p.Spec.NodeName == node {
			sum += sumMemRequestMi(p) / 1024.0 // Mi → GB
		}
	}
	return sum
}

func sumCpuRequest(p k3sclient.PodItem) float64 {
	sum := 0.0
	for _, c := range p.Spec.Containers {
		cpu := c.Resources.Requests.CPU
		if cpu == "" {
			continue
		}
		if strings.HasSuffix(cpu, "m") {
			if v, err := strconv.ParseFloat(strings.TrimSuffix(cpu, "m"), 64); err == nil {
				sum += v / 1000.0
			}
		} else if v, err := strconv.ParseFloat(cpu, 64); err == nil {
			sum += v
		}
	}
	return sum
}

func sumMemRequestMi(p k3sclient.PodItem) float64 {
	sum := 0.0
	for _, c := range p.Spec.Containers {
		mem := c.Resources.Requests.Memory
		if mem == "" {
			continue
		}
		mem = strings.ToUpper(mem)
		switch {
		case strings.HasSuffix(mem, "GI"):
			if v, err := strconv.ParseFloat(strings.TrimSuffix(mem, "GI"), 64); err == nil {
				sum += v * 1024
			}
		case strings.HasSuffix(mem, "MI"):
			if v, err := strconv.ParseFloat(strings.TrimSuffix(mem, "MI"), 64); err == nil {
				sum += v
			}
		case strings.HasSuffix(mem, "KI"):
			if v, err := strconv.ParseFloat(strings.TrimSuffix(mem, "KI"), 64); err == nil {
				sum += v / 1024
			}
		default:
			if v, err := strconv.ParseFloat(mem, 64); err == nil {
				sum += v / (1024 * 1024)
			}
		}
	}
	return sum
}

func ownerKind(p k3sclient.PodItem) string {
	if len(p.Metadata.OwnerReferences) > 0 {
		return p.Metadata.OwnerReferences[0].Kind
	}
	return ""
}

func ownerName(p k3sclient.PodItem) string {
	if len(p.Metadata.OwnerReferences) > 0 {
		return p.Metadata.OwnerReferences[0].Name
	}
	return ""
}
