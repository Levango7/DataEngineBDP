# ArgoCD 自动回滚配置说明

> 版本：v1.0 ｜ 日期：2026-08-20 ｜ 状态：生效
> 适用：数据引擎大数据平台 dev / staging / prod 三环境
> 配置目录：`design/deploy/argocd/`
> 关联审计项：L10（缺少自动回滚策略 → ArgoCD autoRollback 配置）
> 详细操作手册：`design/deploy/argocd/rollback/rollback-strategy.md`

---

## 1. 概述

本文档说明平台 ArgoCD GitOps 部署中与自动回滚相关的三项核心配置：

1. `syncPolicy.automated.prune` —— 修剪（Git 删除即集群删除）
2. `syncPolicy.automated.selfHeal` —— 自愈（集群漂移自动回滚到 Git 声明）
3. `revisionHistoryLimit` —— 历史版本保留数（决定可回滚的 revision 数量）

这三项共同构成"Git 即真理"的自动回滚基线：任何偏离 Git 声明的集群状态都会被自动纠正，同时保留足够的历史版本以支持 `argocd app rollback` 快速回退。

---

## 2. 配置项语义

### 2.1 syncPolicy.automated.prune

| 项 | 说明 |
| --- | --- |
| 作用 | 当 Git 中删除某资源后，ArgoCD 自动在集群中删除对应资源 |
| 取值 | `true`（启用修剪）/ `false`（保留孤儿资源） |
| 风险 | 误删 Git 中的资源会级联删除集群资源，需配合 `PrunePropagationPolicy=foreground` + `PruneLast=true` |
| 回滚意义 | 回滚到旧版本时，旧版本中不存在的资源会被自动清理，确保回滚后状态与目标 revision 完全一致 |

### 2.2 syncPolicy.automated.selfHeal

| 项 | 说明 |
| --- | --- |
| 作用 | 当集群状态与 Git 声明不一致（人工 kubectl 改动、手动 scale 等）时，ArgoCD 自动将其纠正回 Git 声明 |
| 取值 | `true`（启用自愈）/ `false`（容忍漂移） |
| 风险 | 紧急手动修复（如 kubectl patch 止血）会被 selfHeal 覆盖；此时需临时关闭 selfHeal |
| 回滚意义 | 这是"自动回滚"的核心机制：Git revert 后，ArgoCD 检测到 Git 变更并自动同步到回滚后版本，无需人工 sync |

### 2.3 revisionHistoryLimit

| 项 | 说明 |
| --- | --- |
| 作用 | 保留最近 N 次同步的 revision 历史，供 `argocd app rollback <app> <id>` 快速回退 |
| 取值 | 正整数；0 会禁用历史记录（无法原生回滚） |
| 选型依据 | dev 频繁发布保留少；prod 谨慎发布保留多，覆盖多次回滚需求 |
| 回滚意义 | 配合 `argocd app history` 查看历史，`argocd app rollback` 立即回退到指定 revision，无需等 Git 操作 |

---

## 3. 各环境配置清单

### 3.1 实际部署的 Application

| 环境 | 配置文件 | prune | selfHeal | revisionHistoryLimit | retry.limit | 回滚 RTO |
| --- | --- | --- | --- | --- | --- | --- |
| dev | `applications/root-dev.yaml` | `true` | `true` | `10` | `5` | < 5min |
| staging | `applications/root-staging.yaml` | `true` | `true` | `20` | `3` | < 10min |
| prod | `applications/root-prod.yaml` | `true` | `true` | `50` | `2` | < 15min |

**配置示例（prod）**：

```yaml
spec:
  syncPolicy:
    automated:
      prune: true              # 修剪：Git 删除的资源在集群中同步删除
      selfHeal: true           # 自愈：集群漂移自动回滚到 Git 声明
    syncOptions:
      - CreateNamespace=false  # 生产 namespace 预创建（避免误删）
      - ApplyOutOfSyncOnly=true
      - PrunePropagationPolicy=foreground
      - PruneLast=true
      - ServerSideApply=true
    retry:
      limit: 2                 # 生产重试次数最少，避免错误变更反复重试
      backoff:
        duration: 30s
        factor: 2
        maxDuration: 10m
  revisionHistoryLimit: 50     # 生产保留 50 个历史版本用于回滚
```

### 3.2 同步策略模板（`sync-policies/` 目录）

| 模板文件 | prune | selfHeal | 适用场景 |
| --- | --- | --- | --- |
| `sync-policy-auto.yaml` | `true` | `true` | dev/staging 默认（Git 即真理） |
| `sync-policy-manual.yaml` | — | — | prod 关键变更（手动 sync，不配置 automated） |
| `sync-policy-no-heal.yaml` | `true` | `false` | prod 紧急手动修复期（容忍漂移，修复后恢复 selfHeal） |

> 模板文件带 `note` 注解，标注"此为模板，不实际部署"，复制 `spec.syncPolicy` 到真实 Application 使用。

---

## 4. 自动回滚工作机制

### 4.1 场景一：Git revert 触发自动回滚（推荐）

```
故障发生 → git revert <commit> && git push
         → ArgoCD 检测 Git 变更（约 30s-3min 轮询/webhook）
         → 自动 sync 到回滚后版本
         → prune 清理新版本独有资源
         → selfHeal 纠正任何漂移
         → 回滚完成
```

**优点**：历史可追溯，无需 ArgoCD 特权操作。
**关键配置**：`selfHeal=true`（确保自动同步）+ `prune=true`（确保清理）。

### 4.2 场景二：ArgoCD 原生 rollback（快速止血）

```
故障发生 → argocd app history root-prod    # 查看历史 revision
         → argocd app rollback root-prod 19  # 回退到 ID=19
         → 立即生效
```

**优点**：立即生效，无需 Git 操作。
**关键配置**：`revisionHistoryLimit`（决定可回退的历史范围）。
**注意**：若 `selfHeal=true`，下次 Git 同步会覆盖此次回滚；回滚期间需暂停自动同步：

```bash
argocd app set root-prod --sync-policy none      # 暂停
argocd app rollback root-prod 19                  # 回滚
# 修复 Git 后恢复
argocd app set root-prod --sync-policy automated
```

### 4.3 场景三：紧急止血（关闭 selfHeal）

当需手动 `kubectl patch` 止血且不希望被 selfHeal 覆盖时，临时切换到 `sync-policy-no-heal` 模板：

```bash
# 临时关闭 selfHeal（应用 sync-policies/sync-policy-no-heal.yaml 的 syncPolicy）
argocd app set root-prod --auto-heal=false

# 手动止血
kubectl patch deploy <dep> -n prod-platform --type=json -p='[...]'

# 修复 Git 后恢复 selfHeal
argocd app set root-prod --auto-heal=true
```

---

## 5. revisionHistoryLimit 选型建议

| 环境 | 推荐值 | 理由 |
| --- | --- | --- |
| dev | 10 | 发布频繁，历史价值低，节省存储 |
| staging | 20 | 预发布，需覆盖最近多次发布以验证回滚 |
| prod | 50 | 谨慎发布，需覆盖多次回滚 + 审计追溯 |

> 当前配置已符合上述建议，无需调整。新增环境时按此表取值。

---

## 6. 验证

确认自动回滚配置已生效：

```bash
# 1. 检查各环境 Application 的 syncPolicy
for env in dev staging prod; do
  echo "=== root-$env ==="
  argocd app get root-$env | grep -E 'Auto-Prune|Self-Heal|Sync Policy'
done

# 期望输出：
# Auto-Prune: Enabled
# Self-Heal: Enabled
# Sync Policy: Automated

# 2. 检查 revisionHistoryLimit
kubectl get application root-prod -n argocd -o jsonpath='{.spec.revisionHistoryLimit}'
# 期望：50

# 3. 检查历史版本可用
argocd app history root-prod
# 应列出不超过 revisionHistoryLimit 条记录
```

---

## 7. 相关文件索引

| 文件 | 作用 |
| --- | --- |
| `design/deploy/argocd/applications/root-dev.yaml` | dev Application（prune+selfHeal+limit=10） |
| `design/deploy/argocd/applications/root-staging.yaml` | staging Application（prune+selfHeal+limit=20） |
| `design/deploy/argocd/applications/root-prod.yaml` | prod Application（prune+selfHeal+limit=50） |
| `design/deploy/argocd/sync-policies/sync-policy-auto.yaml` | 自动同步模板（dev/staging） |
| `design/deploy/argocd/sync-policies/sync-policy-manual.yaml` | 手动同步模板（prod 关键变更） |
| `design/deploy/argocd/sync-policies/sync-policy-no-heal.yaml` | 关闭自愈模板（prod 紧急修复期） |
| `design/deploy/argocd/rollback/rollback-strategy.md` | 回滚操作手册（详细命令与流程） |
| `design/deploy/argocd/rollback/rollback.sh` | 紧急回滚脚本（暂停同步+回滚+验证） |
| `design/deploy/argocd/README.md` | ArgoCD GitOps 部署总览 |

---

## 8. 变更记录

| 日期 | 变更 | 关联 |
| --- | --- | --- |
| 2026-08-20 | 初版，汇总自动回滚配置说明（prune/selfHeal/revisionHistoryLimit） | L10 |