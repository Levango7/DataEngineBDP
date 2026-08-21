# 多集群灾备方案 - 基于 Velero + Karmada

## 架构概览

图：灾备架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Karmada 控制面                                │
│                   (karmada-host context)                            │
│  ┌─────────────────┐  ┌──────────────────────┐                     │
│  │ PropagationPolicy│  │ ClusterFailoverConfig│                     │
│  │ (应用分发策略)    │  │ (故障切换配置)        │                     │
│  └─────────────────┘  └──────────────────────┘                     │
│  ┌─────────────────────────────────────┐                           │
│  │ FederatedBackupPolicy (联邦备份策略)  │                           │
│  └─────────────────────────────────────┘                           │
└──────────┬──────────────────┬──────────────────┬──────────────────┘
           │                  │                  │
    ┌──────▼──────┐   ┌──────▼──────┐   ┌──────▼──────┐
    │  主集群      │   │  备集群      │   │  演练集群    │
    │ cluster-bj  │   │ cluster-sh  │   │ cluster-sz  │
    │             │   │             │   │             │
    │  Velero     │   │  Velero     │   │  Velero     │
    │  (备份/恢复) │   │  (备份/恢复) │   │  (备份/恢复) │
    └──────┬──────┘   └──────┬──────┘   └──────┬──────┘
           │                  │                  │
           └──────────┬───────┴──────────────────┘
                      │
              ┌───────▼───────┐
              │  备份存储      │
              │  S3 / MinIO   │
              │  (主 region)  │
              └───────┬───────┘
                      │
              ┌───────▼───────┐
              │  异地存储      │
              │  S3 (跨region) │
              │  (容灾归档)    │
              └───────────────┘
```

### 核心组件

| 组件 | 作用 | 部署位置 |
|------|------|----------|
| Velero | Kubernetes 集群备份与恢复 | 每个成员集群 |
| Karmada | 多集群应用分发与故障转移 | 控制面集群 |
| S3/MinIO | 备份对象存储 | 主 region + 异地 region |
| FederatedBackupPolicy CRD | 跨集群备份策略 | Karmada 控制面 |
| ClusterFailoverConfig CRD | 故障切换配置 | Karmada 控制面 |

## 前置条件

### 1. Velero 安装

每个 Karmada 成员集群均需安装 Velero：

```bash
# 安装 Velero（以 S3 为存储后端）
velero install \
  --provider aws \
  --bucket velero-backups \
  --backup-location-config region=us-east-1,s3ForcePathStyle=true,s3Url=https://s3.amazonaws.com \
  --snapshot-location-config region=us-east-1 \
  --use-restic \
  --namespace velero
```

### 2. S3/MinIO 配置

- 主 region S3 bucket：`velero-backups`
- 异地 region S3 bucket：`velero-backups-archive`（启用跨 region 复制）
- IAM 凭证：通过 Secret `velero-cloud-credentials` 提供

### 3. Karmada 接入

- Karmada 控制面已部署
- 成员集群已注册（`kubectl --context=karmada-host get clusters`）
- `kubectl-karmada` 插件已安装

### 4. Helm Chart 部署

```bash
# 部署 Velero Chart（启用灾备）
helm upgrade --install velero design/deploy/charts/velero \
  --namespace velero \
  --set disasterRecovery.enabled=true \
  --set disasterRecovery.rto=30 \
  --set disasterRecovery.rpo=60

# 部署 Karmada Chart（启用灾备）
helm upgrade --install karmada design/deploy/charts/karmada \
  --namespace karmada-system \
  --set disasterRecovery.enabled=true
```

## 使用方法

### 1. 跨集群备份

```bash
# 全量备份所有集群
./scripts/disaster-recovery/backup-all-clusters.sh --type full

# 增量备份指定 namespace
./scripts/disaster-recovery/backup-all-clusters.sh \
  --type incremental \
  --namespaces production,staging
```

### 2. 集群恢复

```bash
# 恢复指定集群
./scripts/disaster-recovery/restore-cluster.sh \
  --cluster cluster-shanghai \
  --backup full-cluster-beijing-20260101-020000

# 核心业务恢复
./scripts/disaster-recovery/restore-cluster.sh \
  --cluster cluster-shanghai \
  --backup backup-xxx \
  --policy business-critical
```

### 3. 故障切换

```bash
# 故障切换（自动查找最新备份）
./scripts/disaster-recovery/failover.sh \
  --from cluster-beijing \
  --to cluster-shanghai

# 指定备份切换
./scripts/disaster-recovery/failover.sh \
  --from cluster-beijing \
  --to cluster-shanghai \
  --backup full-cluster-beijing-20260101-020000
```

### 4. 灾备演练

```bash
# 执行灾备演练
./scripts/disaster-recovery/disaster-recovery-drill.sh \
  --from cluster-beijing \
  --to cluster-shanghai \
  --rto-target 30 \
  --rpo-target 60

# 仅恢复演练环境
./scripts/disaster-recovery/disaster-recovery-drill.sh \
  --cleanup-only \
  --from cluster-beijing \
  --to cluster-shanghai
```

### 5. 备份验证

```bash
# 基本验证
./scripts/disaster-recovery/verify-backup.sh \
  --backup full-cluster-beijing-20260101-020000

# 深度验证（含可恢复性测试）
./scripts/disaster-recovery/verify-backup.sh \
  --backup full-cluster-beijing-20260101-020000 \
  --deep
```

## RTO/RPO 配置说明

### RTO（Recovery Time Objective）

- **定义**：故障发生后系统恢复运行所需的最大时间
- **配置项**：`disasterRecovery.rto`（分钟）
- **业务影响**：RTO 越小，业务中断时间越短，但要求更高的自动化程度
- **推荐值**：核心业务 15-30 分钟，重要业务 30-60 分钟，一般业务 60-120 分钟

### RPO（Recovery Point Objective）

- **定义**：故障发生时允许丢失的最大数据量（时间维度）
- **配置项**：`disasterRecovery.rpo`（分钟）
- **业务影响**：RPO 越小，数据丢失越少，但备份频率越高、存储成本越大
- **推荐值**：核心业务 15 分钟，重要业务 60 分钟，一般业务 360 分钟

### 配置示例

```yaml
# values.yaml
disasterRecovery:
  enabled: true
  rto: 30              # RTO 30 分钟
  rpo: 60              # RPO 60 分钟
  backupSchedule:
    daily: "0 2 * * *"     # 每日凌晨全量
    hourly: "0 * * * *"    # 每小时增量
  retentionDays: 30        # 保留 30 天
```

详细配置指南参见 [RTO-RPO-CONFIG.md](./RTO-RPO-CONFIG.md)。

## 最佳实践

### 1. 备份策略

- **分层备份**：每日全量 + 每小时增量 + 每周归档
- **保留策略**：全量 30 天，增量 24 小时，归档 90 天
- **异地容灾**：启用跨 region S3 复制，防范 region 级故障
- **存储加密**：使用 SSE-KMS 加密备份数据

### 2. 恢复策略

- **分级恢复**：核心业务优先恢复（`--policy business-critical`）
- **恢复验证**：恢复后自动检查 Deployment 就绪与 Pod 状态
- **试运行**：使用 `--dry-run` 验证恢复计划
- **逐步恢复**：先恢复核心 namespace，再恢复其他

### 3. 故障切换

- **人工确认**：生产环境 `autoFailover: false`，由人工触发
- **流量切换**：通过 Karmada PropagationPolicy 调整集群亲和性
- **数据同步**：切换前确保最新备份已恢复
- **回切准备**：故障修复后需手动清除 taint 并回切

### 4. 灾备演练

- **定期演练**：建议每月执行一次完整演练
- **真实模拟**：演练应模拟真实故障场景（集群不可达）
- **指标验收**：RTO/RPO 必须达标，业务连续性必须通过
- **环境恢复**：演练后必须清理环境，避免影响生产

### 5. 监控告警

- **备份监控**：监控 Schedule 执行状态，失败时告警
- **存储监控**：监控 S3 bucket 用量与跨 region 复制延迟
- **集群健康**：监控成员集群可达性，达到故障阈值时告警
- **演练报告**：归档每次演练报告，跟踪 RTO/RPO 趋势

## 文件清单

| 文件 | 说明 |
|------|------|
| `backup-all-clusters.sh` | 跨集群备份脚本 |
| `restore-cluster.sh` | 一键恢复脚本 |
| `failover.sh` | 故障切换脚本 |
| `disaster-recovery-drill.sh` | 灾备演练脚本 |
| `verify-backup.sh` | 备份验证脚本 |
| `README.md` | 灾备使用说明（本文件） |
| `RTO-RPO-CONFIG.md` | RTO/RPO 配置指南 |
| `design/deploy/charts/velero/templates/backup-schedule.yaml` | 备份调度模板 |
| `design/deploy/charts/velero/templates/restore-policy.yaml` | 恢复策略模板 |
| `design/deploy/charts/velero/templates/backup-storage-location.yaml` | 备份存储位置模板 |
| `design/deploy/charts/karmada/templates/federated-backup-policy.yaml` | 联邦备份策略 CRD |
| `design/deploy/charts/karmada/templates/cluster-failover-config.yaml` | 故障切换配置 CRD |