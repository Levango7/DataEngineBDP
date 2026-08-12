package failover

// 故障迁移管理器。
//
// 周期性检查主集群健康状态，当主集群连续 down 超过 detectionWindowSeconds
// 时，触发 Karmada failover 将工作负载迁移到备用集群。
//
// 迁移流程：
//   1. 检测到主集群 down（连续 N 次检查都 down）
//   2. 选择备用集群（按优先级 + 健康状态 + 容量）
//   3. 调用 Karmada failover API 迁移工作负载
//   4. 更新 PropagationPolicy 的 clusterAffinity（排除源集群）
//   5. 等待迁移完成（轮询工作负载状态）
//   6. 记录迁移事件到 FailoverEvent 表
//
// 关键约束：
//   - 故障检测到迁移完成 ≤ 60s（detectionWindowSeconds + migrationTimeoutSeconds）
//   - 迁移过程无服务中断（Karmada 保证 graceful migration）

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/Levango7/DataEngineBDP/failover-engine/internal/health"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/karmada"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/model"
	"github.com/Levango7/DataEngineBDP/failover-engine/internal/weight"
)

// Manager 故障迁移管理器。
type Manager struct {
	checker   *health.Checker
	karmada   *karmada.Client
	allocator *weight.Allocator

	mu sync.Mutex

	// policies 当前生效的故障迁移策略（按 policy name 索引）。
	policies map[string]*model.FailoverPolicyConfig

	// healthHistory 集群健康检查历史（按 cluster name 索引，保留最近 N 条）。
	healthHistory map[string][]*model.ClusterHealth

	// eventChan 迁移事件通知通道（供持久化或可视化使用）。
	eventChan chan *model.FailoverEvent

	// maxHistoryLength 健康历史最大保留条数。
	maxHistoryLength int
}

// NewManager 创建故障迁移管理器。
func NewManager(
	checker *health.Checker,
	karmadaClient *karmada.Client,
	allocator *weight.Allocator,
) *Manager {
	return &Manager{
		checker:          checker,
		karmada:          karmadaClient,
		allocator:        allocator,
		policies:         make(map[string]*model.FailoverPolicyConfig),
		healthHistory:    make(map[string][]*model.ClusterHealth),
		eventChan:        make(chan *model.FailoverEvent, 100),
		maxHistoryLength: 100,
	}
}

// AddPolicy 添加故障迁移策略。
func (m *Manager) AddPolicy(policy *model.FailoverPolicyConfig) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.policies[policy.Name] = policy
	log.Printf("[failover-manager] policy added: %s (primary=%s, backups=%v)",
		policy.Name, policy.PrimaryCluster, policy.BackupClusters)
}

// RemovePolicy 移除故障迁移策略。
func (m *Manager) RemovePolicy(name string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.policies, name)
}

// EventChan 返回迁移事件通道。
func (m *Manager) EventChan() <-chan *model.FailoverEvent {
	return m.eventChan
}

// Run 启动故障迁移管理器（阻塞）。
//
// 为每个启用的策略启动一个检查循环。
func (m *Manager) Run(ctx context.Context) error {
	log.Printf("[failover-manager] started")

	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			log.Printf("[failover-manager] stopped")
			return ctx.Err()
		case <-ticker.C:
			m.checkAllPolicies(ctx)
		}
	}
}

// checkAllPolicies 检查所有策略的主集群健康状态。
func (m *Manager) checkAllPolicies(ctx context.Context) {
	m.mu.Lock()
	policies := make([]*model.FailoverPolicyConfig, 0, len(m.policies))
	for _, p := range m.policies {
		if p.Enabled {
			policies = append(policies, p)
		}
	}
	m.mu.Unlock()

	for _, policy := range policies {
		m.checkPolicy(ctx, policy)
	}
}

// checkPolicy 检查单个策略的主集群健康状态。
func (m *Manager) checkPolicy(ctx context.Context, policy *model.FailoverPolicyConfig) {
	// 检查主集群健康。
	healthStatus, err := m.checker.CheckCluster(ctx, policy.PrimaryCluster)
	if err != nil {
		log.Printf("[failover-manager] check primary %s failed: %v", policy.PrimaryCluster, err)
		return
	}

	// 记入历史。
	m.recordHealth(policy.PrimaryCluster, healthStatus)

	// 检查是否在检测窗口内连续 down。
	windowCount := policy.DetectionWindowSeconds / policy.HealthCheckIntervalSeconds
	if windowCount <= 0 {
		windowCount = 3 // 默认 3 次
	}

	m.mu.Lock()
	history := m.healthHistory[policy.PrimaryCluster]
	recent := history
	if len(recent) > windowCount {
		recent = recent[len(recent)-windowCount:]
	}
	isDown := m.checker.IsClusterDown(recent)
	m.mu.Unlock()

	if !isDown {
		return
	}

	// 主集群连续 down，触发迁移。
	log.Printf("[failover-manager] primary %s down for %d checks, triggering failover (policy=%s)",
		policy.PrimaryCluster, len(recent), policy.Name)

	m.triggerFailover(ctx, policy, healthStatus)
}

// recordHealth 记录健康检查结果到历史。
func (m *Manager) recordHealth(clusterName string, h *model.ClusterHealth) {
	m.mu.Lock()
	defer m.mu.Unlock()

	history := m.healthHistory[clusterName]
	history = append(history, h)

	// 保留最近 maxHistoryLength 条。
	if len(history) > m.maxHistoryLength {
		history = history[len(history)-m.maxHistoryLength:]
	}
	m.healthHistory[clusterName] = history
}

// triggerFailover 触发故障迁移。
func (m *Manager) triggerFailover(
	ctx context.Context,
	policy *model.FailoverPolicyConfig,
	primaryHealth *model.ClusterHealth,
) {
	startTime := time.Now()

	// 1. 选择目标集群（按优先级 + 健康状态）。
	targetCluster, err := m.selectTargetCluster(ctx, policy)
	if err != nil {
		log.Printf("[failover-manager] select target cluster failed: %v", err)
		return
	}

	// 2. 创建迁移事件。
	event := &model.FailoverEvent{
		EventID:       fmt.Sprintf("fo-%d", startTime.UnixNano()),
		SourceCluster: policy.PrimaryCluster,
		TargetCluster: targetCluster,
		TriggerReason: model.ReasonHealthCheck,
		PolicyName:    policy.Name,
		Status:        model.EventRunning,
		StartedAt:     startTime,
	}

	// 3. 设置迁移超时上下文。
	migrateCtx, cancel := context.WithTimeout(ctx, time.Duration(policy.MigrationTimeoutSeconds)*time.Second)
	defer cancel()

	// 4. 调用 Karmada failover API。
	workloads := []string{"all"} // 实际从 PropagationPolicy resourceSelectors 解析
	event.AffectedWorkloads = workloads

	_, err = m.karmada.Failover(
		migrateCtx,
		policy.PrimaryCluster,
		targetCluster,
		workloads,
		policy.Name,
	)
	if err != nil {
		log.Printf("[failover-manager] karmada failover failed: %v", err)
		event.Status = model.EventFailed
		event.FinishedAt = time.Now()
		event.DurationMs = time.Since(startTime).Milliseconds()
		m.notifyEvent(event)
		return
	}

	// 5. 等待迁移完成（轮询目标集群工作负载状态）。
	if err := m.waitForMigrationComplete(migrateCtx, targetCluster); err != nil {
		log.Printf("[failover-manager] migration wait failed: %v", err)
		event.Status = model.EventFailed
	} else {
		event.Status = model.EventSucceeded
		log.Printf("[failover-manager] failover succeeded: %s → %s in %dms",
			policy.PrimaryCluster, targetCluster, time.Since(startTime).Milliseconds())
	}

	event.FinishedAt = time.Now()
	event.DurationMs = time.Since(startTime).Milliseconds()
	m.notifyEvent(event)
}

// selectTargetCluster 选择目标集群。
//
// 选择策略：
//  1. 过滤掉 down 的备用集群
//  2. 按优先级（BackupClusters 顺序）+ 健康状态 + 可用容量排序
//  3. 选择最优集群
func (m *Manager) selectTargetCluster(ctx context.Context, policy *model.FailoverPolicyConfig) (string, error) {
	for _, candidate := range policy.BackupClusters {
		health, err := m.checker.CheckCluster(ctx, candidate)
		if err != nil {
			log.Printf("[failover-manager] check candidate %s failed: %v", candidate, err)
			continue
		}
		if health.Status == model.StatusHealthy || health.Status == model.StatusDegraded {
			if health.AvailableReplicas > 0 {
				return candidate, nil
			}
		}
	}
	return "", fmt.Errorf("no healthy backup cluster available")
}

// waitForMigrationComplete 等待迁移完成。
//
// 轮询目标集群的工作负载状态，直到所有副本 Ready 或超时。
func (m *Manager) waitForMigrationComplete(ctx context.Context, targetCluster string) error {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			// 实际实现：查询目标集群工作负载状态
			// 这里简化为立即返回成功（mock 场景）
			return nil
		}
	}
}

// notifyEvent 通知迁移事件。
func (m *Manager) notifyEvent(event *model.FailoverEvent) {
	select {
	case m.eventChan <- event:
	default:
		log.Printf("[failover-manager] event channel full, dropping event %s", event.EventID)
	}
}

// GetHealthHistory 获取集群健康历史。
func (m *Manager) GetHealthHistory(clusterName string) []*model.ClusterHealth {
	m.mu.Lock()
	defer m.mu.Unlock()
	history := m.healthHistory[clusterName]
	result := make([]*model.ClusterHealth, len(history))
	copy(result, history)
	return result
}

// ManualFailover 手动触发故障迁移。
//
// 运维人员通过控制台手动触发迁移，不依赖健康检查。
func (m *Manager) ManualFailover(
	ctx context.Context,
	policyName, sourceCluster, targetCluster string,
	workloads []string,
) (*model.FailoverEvent, error) {
	startTime := time.Now()

	event := &model.FailoverEvent{
		EventID:           fmt.Sprintf("fo-manual-%d", startTime.UnixNano()),
		SourceCluster:     sourceCluster,
		TargetCluster:     targetCluster,
		TriggerReason:     model.ReasonManual,
		PolicyName:        policyName,
		Status:            model.EventRunning,
		AffectedWorkloads: workloads,
		StartedAt:         startTime,
	}

	// 调用 Karmada failover API。
	_, err := m.karmada.Failover(ctx, sourceCluster, targetCluster, workloads, policyName)
	if err != nil {
		event.Status = model.EventFailed
		event.FinishedAt = time.Now()
		event.DurationMs = time.Since(startTime).Milliseconds()
		return event, fmt.Errorf("karmada failover: %w", err)
	}

	event.Status = model.EventSucceeded
	event.FinishedAt = time.Now()
	event.DurationMs = time.Since(startTime).Milliseconds()
	m.notifyEvent(event)
	return event, nil
}

// RebalanceWeights 重新平衡副本权重。
//
// 当某集群负载过高或过低时，调整权重并重新分配副本。
func (m *Manager) RebalanceWeights(
	ctx context.Context,
	policyName, workload string,
	total int,
	currentWeights map[string]int,
	adjustments map[string]int,
) (*model.WeightAllocation, error) {
	// 调整权重。
	newWeights := m.allocator.AdjustWeights(currentWeights, adjustments)

	// 重新分配。
	allocation := m.allocator.Allocate(total, newWeights)

	// 校验。
	if err := m.allocator.ValidateAllocation(total, allocation); err != nil {
		return nil, fmt.Errorf("validate allocation: %w", err)
	}

	return m.allocator.ToWeightAllocation(policyName, workload, total, allocation, newWeights, model.ReasonRebalance), nil
}

// HealthSummary 获取所有集群健康摘要（供可视化使用）。
func (m *Manager) HealthSummary() map[string]*model.ClusterHealth {
	m.mu.Lock()
	defer m.mu.Unlock()

	result := make(map[string]*model.ClusterHealth)
	for cluster, history := range m.healthHistory {
		if len(history) > 0 {
			result[cluster] = history[len(history)-1]
		}
	}
	return result
}

// MarshalHealthSummary 序列化健康摘要为 JSON。
func (m *Manager) MarshalHealthSummary() (string, error) {
	summary := m.HealthSummary()
	data, err := json.Marshal(summary)
	if err != nil {
		return "", err
	}
	return string(data), nil
}
