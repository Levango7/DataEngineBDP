# RTO/RPO 配置指南

## 1. RTO/RPO 定义与业务影响

### 1.1 RTO（Recovery Time Objective，恢复时间目标）

图：RTO 示意图

```
时间轴：
  故障发生 ─────────────────── 恢复完成
  │                              │
  │◄────────── RTO ────────────►│
  │                              │
  │   业务中断期间                │  业务恢复运行
  └──────────────────────────────┘
```

- **定义**：从故障发生到系统恢复运行所需的最大允许时间
- **业务影响**：RTO 决定业务中断时长，直接影响用户体验与收入
- **成本关系**：RTO 越小，要求自动化程度越高，成本越高

### 1.2 RPO（Recovery Point Objective，恢复点目标）

图：RPO 示意图

```
时间轴：
  最近备份 ─────── 故障发生
  │                  │
  │◄───── RPO ──────►│
  │                  │
  │   可恢复的数据点  │  数据丢失窗口
  └──────────────────┘
```

- **定义**：故障发生时允许丢失的最大数据量（以时间衡量）
- **业务影响**：RPO 决定数据丢失窗口，直接影响数据完整性
- **成本关系**：RPO 越小，备份频率越高，存储与性能开销越大

### 1.3 RTO 与 RPO 的关系

表：RTO/RPO 关系对照表

| 维度 | RTO | RPO |
|------|-----|-----|
| 关注点 | 恢复速度 | 数据完整 |
| 时间方向 | 故障后（未来） | 故障前（过去） |
| 降低方式 | 自动化恢复 | 提高备份频率 |
| 成本类型 | 计算资源、自动化开发 | 存储资源、备份性能 |
| 配置项 | `disasterRecovery.rto` | `disasterRecovery.rpo` |

## 2. 不同场景的 RTO/RPO 推荐值

### 2.1 按业务等级分类

表：业务等级 RTO/RPO 推荐值

| 业务等级 | RTO（分钟） | RPO（分钟） | 备份频率 | 适用场景 |
|----------|-------------|-------------|----------|----------|
| L0 - 核心 | 15 | 15 | 每 15 分钟 | 支付、交易、实时风控 |
| L1 - 重要 | 30 | 30 | 每 30 分钟 | 用户服务、订单系统 |
| L2 - 一般 | 60 | 60 | 每小时 | 报表、分析、内容管理 |
| L3 - 次要 | 120 | 360 | 每日 | 日志、归档、批处理 |

### 2.2 按故障类型分类

表：故障类型 RTO/RPO 推荐值

| 故障类型 | RTO（分钟） | RPO（分钟） | 恢复策略 |
|----------|-------------|-------------|----------|
| Pod 级故障 | 5 | 0 | K8s 自愈（无需灾备） |
| Node 级故障 | 10 | 0 | K8s 重调度 |
| 集群级故障 | 30 | 60 | Karmada 故障切换 |
| Region 级故障 | 60 | 60 | 跨 region 恢复 |
| 数据中心故障 | 120 | 360 | 异地全量恢复 |

### 2.3 推荐配置示例

代码示例：核心业务配置（YAML）

```yaml
disasterRecovery:
  enabled: true
  rto: 15
  rpo: 15
  backupSchedule:
    daily: "0 2 * * *"
    hourly: "*/15 * * * *"   # 每 15 分钟增量
  retentionDays: 30
```

代码示例：重要业务配置（YAML）

```yaml
disasterRecovery:
  enabled: true
  rto: 30
  rpo: 30
  backupSchedule:
    daily: "0 2 * * *"
    hourly: "*/30 * * * *"   # 每 30 分钟增量
  retentionDays: 30
```

代码示例：一般业务配置（YAML）

```yaml
disasterRecovery:
  enabled: true
  rto: 60
  rpo: 60
  backupSchedule:
    daily: "0 2 * * *"
    hourly: "0 * * * *"      # 每小时增量
  retentionDays: 30
```

## 3. 配置参数与 RTO/RPO 的关系

### 3.1 RTO 影响参数

表：RTO 影响参数说明表

| 参数 | 对 RTO 的影响 | 推荐值 |
|------|---------------|--------|
| `disasterRecovery.rto` | RTO 目标值（分钟） | 按业务等级 |
| `failover.failureThreshold` | 故障检测延迟 = 阈值 × 间隔 | 3 × 30s = 90s |
| `failover.intervalSeconds` | 故障检测间隔 | 30s |
| `failover.timeoutSeconds` | 单次检测超时 | 60s |
| `failover.autoFailover` | 自动切换减少人工延迟 | 生产建议 false |
| 恢复策略 `healthCheckTimeout` | 恢复验证耗时 | 300s |

RTO 实际值计算公式：

```
RTO = 故障检测时间 + 切换决策时间 + 数据恢复时间 + 健康验证时间
    = (failureThreshold × intervalSeconds) + 决策延迟 + restore耗时 + healthCheck
```

### 3.2 RPO 影响参数

表：RPO 影响参数说明表

| 参数 | 对 RPO 的影响 | 推荐值 |
|------|---------------|--------|
| `disasterRecovery.rpo` | RPO 目标值（分钟） | 按业务等级 |
| `backupSchedule.hourly` | 增量备份频率 = RPO | `0 * * * *` |
| `backupSchedule.daily` | 全量备份频率 | `0 2 * * *` |
| `retentionDays` | 备份保留期 | 30 |
| `incrementalRetentionHours` | 增量保留期 | 24 |

RPO 实际值计算公式：

```
RPO = 当前时间 - 最近一次成功备份完成时间
    ≈ 增量备份间隔（最坏情况）
```

### 3.3 参数调优建议

表：参数调优建议表

| 目标 | 调优方向 | 副作用 |
|------|----------|--------|
| 降低 RTO | 提高 `autoFailover`、缩短检测间隔 | 误切换风险增加 |
| 降低 RPO | 缩短增量备份间隔 | 存储成本增加、备份性能开销 |
| 降低成本 | 延长保留期、减少备份频率 | RPO 增大、恢复点变旧 |
| 提高可靠性 | 增加故障阈值、启用异地容灾 | RTO 增大（检测延迟增加） |

## 4. 监控与告警配置

### 4.1 备份监控

代码示例：Prometheus 告警规则（YAML）

```yaml
groups:
  - name: velero-backup
    rules:
      # 备份失败告警
      - alert: VeleroBackupFailed
        expr: velero_backup_last_status{phase="Failed"} == 1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Velero 备份失败: {{ $labels.backup }}"
          description: "集群 {{ $labels.cluster }} 的备份 {{ $labels.backup }} 失败"

      # 备份超时告警（RPO 超标）
      - alert: VeleroRPOExceeded
        expr: time() - velero_backup_last_success_timestamp > 3600
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "RPO 超标: 超过 60 分钟未成功备份"
          description: "集群 {{ $labels.cluster }} 已超过 RPO 目标"

      # Schedule 未执行
      - alert: VeleroScheduleNotRunning
        expr: time() - velero_schedule_last_run_timestamp > 7200
        for: 15m
        labels:
          severity: warning
        annotations:
          summary: "备份调度未执行: {{ $labels.schedule }}"
```

### 4.2 集群健康监控

代码示例：集群健康告警规则（YAML）

```yaml
groups:
  - name: karmada-cluster-health
    rules:
      # 集群不可达
      - alert: KarmadaClusterUnreachable
        expr: karmada_cluster_ready{condition="Ready"} == 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Karmada 集群不可达: {{ $labels.cluster }}"
          description: "集群 {{ $labels.cluster }} 已连续 2 分钟不可达，可能触发故障切换"

      # 集群就绪率低
      - alert: KarmadaClusterReadyRatioLow
        expr: count(karmada_cluster_ready{condition="Ready"} == 1) / count(karmada_cluster_ready{condition="Ready"}) < 0.5
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "超过半数集群不可用"
```

### 4.3 RTO/RPO 监控

代码示例：RTO/RPO 告警规则（YAML）

```yaml
groups:
  - name: disaster-recovery-metrics
    rules:
      # 实际 RTO 超标
      - alert: RTOExceeded
        expr: dr_failover_actual_rto_minutes > 30
        labels:
          severity: critical
        annotations:
          summary: "实际 RTO 超过目标值"
          description: "最近一次故障切换 RTO = {{ $value }} 分钟，超过目标 30 分钟"

      # 实际 RPO 超标
      - alert: RPOExceeded
        expr: dr_backup_actual_rpo_minutes > 60
        labels:
          severity: warning
        annotations:
          summary: "实际 RPO 超过目标值"
          description: "当前 RPO = {{ $value }} 分钟，超过目标 60 分钟"
```

## 5. 灾备演练计划

### 5.1 演练频率

表：演练频率建议表

| 演练类型 | 频率 | 范围 | 参与人员 |
|----------|------|------|----------|
| 桌面推演 | 每周 | 流程梳理 | 运维团队 |
| 备份验证 | 每日 | 备份完整性 | 自动化 |
| 切换演练 | 每月 | 单集群切换 | 运维 + 业务 |
| 全量演练 | 每季度 | 跨 region 恢复 | 全员 |
| 混沌工程 | 每半年 | 随机故障注入 | SRE |

### 5.2 演练范围

表：演练范围说明表

| 范围 | 演练内容 | RTO 目标 | RPO 目标 |
|------|----------|----------|----------|
| 单 Pod 故障 | Pod 重启 | 5 分钟 | 0 |
| 单 Node 故障 | Node 驱逐 | 10 分钟 | 0 |
| 单集群故障 | 集群切换 | 30 分钟 | 60 分钟 |
| 存储故障 | 备份恢复 | 60 分钟 | 60 分钟 |
| Region 故障 | 跨 region 恢复 | 120 分钟 | 60 分钟 |

### 5.3 验收标准

表：演练验收标准表

| 验收项 | 标准 | 权重 |
|--------|------|------|
| RTO 达标 | 实际 RTO ≤ 目标 RTO | 必须 |
| RPO 达标 | 实际 RPO ≤ 目标 RPO | 必须 |
| 业务连续性 | 核心业务可访问 | 必须 |
| 数据完整性 | 无数据丢失 | 必须 |
| 切换自动化 | 切换流程可一键执行 | 推荐 |
| 报告完整性 | 演练报告完整 | 推荐 |
| 环境恢复 | 演练后环境已清理 | 必须 |

### 5.4 演练流程

图：灾备演练流程图

```
1. 准备阶段
   ├── 部署演练应用
   ├── 记录基线指标
   └── 通知相关团队

2. 故障注入
   ├── 标记集群不可用
   ├── 记录故障开始时间
   └── 验证故障注入成功

3. 故障切换
   ├── 执行 failover.sh
   ├── 等待切换完成
   └── 记录切换完成时间

4. 验证阶段
   ├── 验证业务连续性
   ├── 检查数据完整性
   ├── 计算 RTO/RPO
   └── 生成演练报告

5. 恢复阶段
   ├── 恢复演练环境
   ├── 清除故障标记
   └── 验证环境恢复
```

### 5.5 演练命令示例

命令示例：月度灾备演练

```bash
# 1. 执行灾备演练
./scripts/disaster-recovery/disaster-recovery-drill.sh \
  --from cluster-beijing \
  --to cluster-shanghai \
  --rto-target 30 \
  --rpo-target 60 \
  --report-dir /tmp/dr-reports/monthly-$(date +%Y%m)

# 2. 查看演练报告
cat /tmp/dr-reports/monthly-$(date +%Y%m)/drill-report-*.md

# 3. 如演练未通过，恢复环境
./scripts/disaster-recovery/disaster-recovery-drill.sh \
  --cleanup-only \
  --from cluster-beijing \
  --to cluster-shanghai
```

## 6. 常见问题

### 6.1 RTO 过大如何优化

- 启用 `autoFailover: true`（减少人工决策延迟）
- 缩短 `failover.intervalSeconds`（加快故障检测）
- 使用增量备份恢复（减少数据恢复时间）
- 预热目标集群（提前部署应用骨架）

### 6.2 RPO 过大如何优化

- 缩短增量备份间隔（`backupSchedule.hourly`）
- 启用持续数据保护（CDP）方案
- 使用 Velero 文件系统备份（Restic）替代快照
- 对关键数据使用同步复制而非备份

### 6.3 备份存储成本优化

- 使用增量备份减少全量备份频率
- 配置 S3 生命周期策略自动降级存储
- 压缩备份数据（Velero 默认启用）
- 定期清理过期备份