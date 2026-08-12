# ArgoCD 回滚策略与操作手册

> 版本：v0.1 ｜ 日期：2026-08-07
> 适用：数据引擎大数据平台 dev/staging/prod 三环境

---

## 1. 回滚原则

| 原则 | 说明 |
| --- | --- |
| **Git 即真理** | 所有回滚最终都体现为 Git commit 的回退，ArgoCD 自动同步到目标版本 |
| **优先 Git revert** | 优先用 `git revert`（生成新 commit），保留历史可追溯，不用 `git reset`（改写历史） |
| **环境分级** | dev 可快速回滚；staging 需通知；prod 需审批 + 通知 |
| **回滚前快照** | prod 回滚前先 `argocd app manifest` 保存当前状态 |

---

## 2. 回滚场景与操作

### 2.1 场景一：应用层回滚（Git revert）

**适用**：代码/配置变更导致故障，需回退到上一个稳定版本。

```bash
# Step 1: 查看最近 commit
git log --oneline -10

# Step 2: revert 故障 commit（生成新 commit，不改写历史）
git revert <faulty-commit-sha>
git push origin main

# Step 3: ArgoCD 自动检测 Git 变更，自动同步到回滚后版本
# 观察 ArgoCD 同步状态
argocd app get root-prod --refresh

# Step 4: 验证
argocd app wait root-prod --sync --health
```

**优点**：历史可追溯，无需 ArgoCD 特权操作。
**缺点**：需等 ArgoCD 同步（约 30s-3min）。

### 2.2 场景二：ArgoCD 原生回滚（快速回退到历史 revision）

**适用**：需立即回退，不等 Git 操作。

```bash
# Step 1: 查看 Application 同步历史
argocd app history root-prod

# 输出示例：
# ID  DATE           REVISION
# 20  2026-08-07...  abc1234 (当前)
# 19  2026-08-06...  def5678 (上一个稳定)
# 18  2026-08-05...  ghi9012

# Step 2: 回滚到指定 revision（如 ID=19）
argocd app rollback root-prod 19

# Step 3: 验证
argocd app get root-prod
```

**优点**：立即生效，无需 Git 操作。
**缺点**：若 selfHeal=true，下次 Git 同步会覆盖回滚（需配合暂停自动同步）。

### 2.3 场景三：紧急回滚（暂停自动同步 + 手动 apply）

**适用**：prod 严重故障，需立即止血。

```bash
# 用 rollback.sh 脚本（见同目录 rollback.sh）
bash design/deploy/argocd/rollback/rollback.sh root-prod

# 脚本执行流程：
# 1. 暂停自动同步（避免 selfHeal 覆盖回滚）
# 2. 列出最近 10 个同步历史
# 3. 交互选择回滚目标 revision
# 4. 执行 argocd app rollback
# 5. 验证回滚成功
# 6. 提示：修复 Git 后恢复自动同步
```

---

## 3. 回滚后恢复

回滚后需在 Git 中修复故障根因，再恢复自动同步：

```bash
# Step 1: 在 Git 中修复（revert 故障 commit 或提交修复 commit）
git revert <faulty-commit>
git push origin main

# Step 2: 等待 ArgoCD 同步到修复版本
argocd app wait root-prod --sync --health

# Step 3: 恢复自动同步（若场景三暂停了）
argocd app set root-prod --sync-policy automated

# Step 4: 验证 selfHeal 恢复
argocd app get root-prod | grep -E 'Auto-Prune|Self-Heal'
```

---

## 4. 各环境回滚策略

| 环境 | 策略 | 审批 | 通知 | RTO |
| --- | --- | --- | --- | --- |
| dev | Git revert（自动同步） | 无 | 无 | < 5min |
| staging | Git revert + 通知 | 无 | Slack #staging | < 10min |
| prod | ArgoCD rollback + 暂停同步 + 审批 | SRE 审批 | Slack #prod-alerts + 邮件 | < 15min |

---

## 5. 回滚验证清单

回滚后必须验证：

| 检查项 | 命令 | 期望 |
| --- | --- | --- |
| Application 同步状态 | `argocd app get <app>` | Synced + Healthy |
| Pod 副本就绪 | `kubectl get deploy -n <ns>` | READY=3/3 |
| 服务健康 | `kubectl get pods -n <ns>` | 全 Running |
| 端到端冒烟 | `bash scripts/poc/run-poc.sh` | 通过 |
| 监控指标恢复 | Grafana / Prometheus | 错误率回归基线 |

---

## 6. 注意事项

1. **selfHeal 与回滚冲突**：selfHeal=true 时，ArgoCD 原生回滚会被下次同步覆盖。回滚期间需暂停自动同步（`argocd app set <app> --sync-policy none`）。
2. **数据库变更不可回滚**：涉及 schema migration 的回滚需先手动回滚数据库，否则应用回滚后启动失败。
3. **回滚不等于修复**：回滚是止血，根因修复仍需在 Git 中提交修复 commit。
4. **revisionHistoryLimit**：prod 保留 50 个历史版本（见 root-prod.yaml），足够覆盖多次回滚。