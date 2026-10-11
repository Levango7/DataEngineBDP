package health

// 集群健康检查器。
//
// 综合 Karmada API 与 Prometheus 指标判断集群健康状态：
//   - Karmada API：Ready=True && Syncable=True → 基础健康
//   - Prometheus：CPU < 90% && Memory < 90% → 负载正常
//   - 综合判定：
//       Ready=False 或 Syncable=False → down
//       Ready=True && (CPU >= 90% 或 Memory >= 90%) → degraded
//       其他 → healthy
//
// 检查结果写入 ClusterHealthRecord 表，供可视化查询。

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"time"

	"github.com/Levango7/DataEngineBDP/failover-engine/internal/karmada"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/model"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/prometheus"
)

// Checker 集群健康检查器。
type Checker struct {
	karmadaClient *karmada.Client
	promClient    *prometheus.Client
	// degradedThresholds 负载阈值（百分比）。
	cpuDegradedThreshold    float64
	memoryDegradedThreshold float64
}

// NewChecker 创建健康检查器。
//
// 默认阈值：CPU/内存 >= 90% 视为 degraded。
func NewChecker(karmadaClient *karmada.Client, promClient *prometheus.Client) *Checker {
	return &Checker{
		karmadaClient:           karmadaClient,
		promClient:              promClient,
		cpuDegradedThreshold:    90.0,
		memoryDegradedThreshold: 90.0,
	}
}

// SetThresholds 设置 degraded 阈值。
func (c *Checker) SetThresholds(cpu, memory float64) {
	c.cpuDegradedThreshold = cpu
	c.memoryDegradedThreshold = memory
}

// CheckCluster 检查单个集群健康状态。
//
// 返回综合健康状态（包含 Karmada API 与 Prometheus 指标）。
func (c *Checker) CheckCluster(ctx context.Context, clusterName string) (*model.ClusterHealth, error) {
	health := &model.ClusterHealth{
		ClusterName: clusterName,
		CheckedAt:   time.Now(),
	}

	// 1. Karmada API 查询集群状态。
	cluster, err := c.karmadaClient.GetCluster(ctx, clusterName)
	if err != nil {
		// Karmada API 不可达，标记为 down。
		health.Status = model.StatusDown
		health.Ready = false
		health.Syncable = false
		health.CheckSource = "karmada_api"
		health.Detail = fmt.Sprintf(`{"error":"karmada api: %s"}`, err.Error())
		return health, nil
	}

	health.Ready = cluster.IsReady()
	health.Syncable = cluster.IsSyncable()
	health.MaxReplicas = cluster.MaxReplicas

	// 2. 如果集群不 Ready 或不可 Syncable，直接判定为 down。
	if !health.Ready || !health.Syncable {
		health.Status = model.StatusDown
		health.CheckSource = "karmada_api"
		health.Detail = fmt.Sprintf(
			`{"ready":%t,"syncable":%t}`,
			health.Ready, health.Syncable,
		)
		return health, nil
	}

	// 3. Prometheus 查询负载指标。
	if c.promClient != nil {
		metrics, err := c.promClient.GetClusterMetrics(ctx, clusterName)
		if err == nil {
			health.CPULoad = metrics.CPULoad
			health.MemoryLoad = metrics.MemoryLoad
			health.PodCount = metrics.PodCount
			health.NodeCount = metrics.NodeCount
			health.CheckSource = "prometheus"
		} else {
			log.Printf("[health-checker] prometheus query failed for %s: %v", clusterName, err)
			health.CheckSource = "karmada_api"
		}
	} else {
		health.CheckSource = "karmada_api"
	}

	// 4. 综合判定。
	if health.CPULoad >= c.cpuDegradedThreshold || health.MemoryLoad >= c.memoryDegradedThreshold {
		health.Status = model.StatusDegraded
	} else {
		health.Status = model.StatusHealthy
	}

	// 5. 可用副本数 = maxReplicas - podCount（粗略估计）。
	health.AvailableReplicas = health.MaxReplicas - health.PodCount
	if health.AvailableReplicas < 0 {
		health.AvailableReplicas = 0
	}

	// 6. 详细信息 JSON。
	detail, _ := json.Marshal(map[string]interface{}{
		"ready":             health.Ready,
		"syncable":          health.Syncable,
		"cpuLoad":           health.CPULoad,
		"memoryLoad":        health.MemoryLoad,
		"podCount":          health.PodCount,
		"nodeCount":         health.NodeCount,
		"maxReplicas":       health.MaxReplicas,
		"availableReplicas": health.AvailableReplicas,
	})
	health.Detail = string(detail)

	return health, nil
}

// CheckAllClusters 检查所有集群健康状态。
func (c *Checker) CheckAllClusters(ctx context.Context, clusterNames []string) ([]*model.ClusterHealth, error) {
	results := make([]*model.ClusterHealth, 0, len(clusterNames))
	for _, name := range clusterNames {
		health, err := c.CheckCluster(ctx, name)
		if err != nil {
			log.Printf("[health-checker] check %s failed: %v", name, err)
			continue
		}
		results = append(results, health)
	}
	return results, nil
}

// IsClusterDown 判断集群是否 down（连续 N 次检查都 down）。
//
// 用于故障检测窗口：在 detectionWindowSeconds 内连续检查都 down 才触发迁移。
func (c *Checker) IsClusterDown(history []*model.ClusterHealth) bool {
	if len(history) == 0 {
		return false
	}
	for _, h := range history {
		if h.Status != model.StatusDown {
			return false
		}
	}
	return true
}
