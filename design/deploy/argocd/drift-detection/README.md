# 数据引擎大数据平台 · GitOps 配置漂移检测与自愈

> 版本：v0.1 ｜ 日期：2026-08-07 ｜ 状态：可部署
> 所属：T004 GitOps 漂移检测
> 依赖：T003 ArgoCD GitOps 部署（`design/deploy/argocd/`）已就绪

---

## 1. 目标

在 T003 ArgoCD GitOps 基础上，构建**配置漂移检测 + 自动修复 + 告警通知 + 合规审计**四位一体的漂移治理体系：

1. **漂移检测**：实时比对 Git 声明与集群实际状态，识别 OutOfSync / 资源差异 / 字段级 diff
2. **自动修复**：基于 selfHeal + 修复 Job 的分级自愈策略，区分 dev/staging/prod 修复行为
3. **告警通知**：Slack / 邮件 / Webhook / 钉钉多渠道通知，按严重程度路由
4. **合规审计**：漂移事件全量审计日志，对接合规规则引擎，输出合规报告

---

## 2. 漂移定义与分类

### 2.1 漂移类型

| 类型 | 说明 | 检测方式 | 严重程度 |
| --- | --- | --- | --- |
| **字段级漂移** | 集群中资源某字段与 Git 声明不一致 | ArgoCD diff + resource customizations | Medium |
| **资源级漂移** | 集群中存在 Git 未声明的资源 / Git 声明的资源在集群中缺失 | ArgoCD sync status (OutOfSync) | High |
| **配置漂移** | ConfigMap / Secret 内容被直接修改 | 字段级 diff + checksum 比对 | High |
| **副本漂移** | Deployment / StatefulSet 实际副本数与期望不一致 | `kubectl scale` 检测 | Medium |
| **镜像漂移** | 运行中 Pod 镜像 tag 与声明不一致（如 latest 被替换） | Pod image 字段比对 | Critical |
| **标签漂移** | 资源 label 被修改（影响服务发现 / 路由） | label 字段 diff | Low |
| **注解漂移** | annotation 被修改（影响 ArgoCD 管理标记） | annotation 字段 diff | Low |

### 2.2 漂移来源

```text
┌──────────────────────────────────────────────────────────┐
│  漂移来源                                                │
├──────────────────────────────────────────────────────────┤
│  ① 人工 kubectl apply/edit 直接改集群                    │
│  ② Helm 历史遗留 / 手动升级                              │
│  ③ Operator / Controller 自动注入字段                    │
│  ④ kubectl scale 手动扩缩容                              │
│  ⑤ Sidecar 注入（Istio / ArgoCD Rollouts）              │
│  ⑥ 集群自动伸缩（HPA / VPA / Cluster Autoscaler）        │
│  ⑦ 临时调试改动未回写 Git                                │
└──────────────────────────────────────────────────────────┘
```

---

## 3. 目录结构

```text
design/deploy/argocd/drift-detection/
├── README.md                              # 本文档
├── detection/                             # 漂移检测
│   ├── drift-detection-strategy.md        # 检测策略文档
│   ├── resource-customizations.yaml       # ArgoCD 资源自定义（忽略字段）
│   ├── diff-server-config.yaml            # ArgoCD Diff Server 配置
│   └── detect-drift.sh                    # 漂移检测脚本
├── remediation/                           # 自动修复
│   ├── remediation-strategy.md            # 修复策略文档
│   ├── self-heal-dev.yaml                 # dev 环境自愈配置
│   ├── self-heal-staging.yaml             # staging 环境自愈配置
│   ├── self-heal-prod.yaml                # prod 环境自愈配置
│   ├── remediation-job.yaml               # 修复 CronJob
│   ├── remediation-rbac-sa.yaml           # 修复 ServiceAccount
│   ├── remediation-rbac-role.yaml         # 修复 Role
│   ├── remediation-rbac-binding.yaml      # 修复 RoleBinding
│   └── remediate.sh                       # 修复操作脚本
├── alerting/                              # 告警通知
│   ├── alerting-channels.md               # 告警渠道文档
│   ├── argocd-notifications-cm.yaml       # ArgoCD Notifications ConfigMap
│   ├── alert-rules.yaml                   # 告警规则（触发条件）
│   ├── slack-templates.yaml               # Slack 消息模板
│   ├── webhook-secret.yaml                # Webhook 凭证 Secret
│   └── alert-routing.yaml                 # 告警路由（按环境/严重程度）
├── audit/                                 # 合规审计
│   ├── audit-strategy.md                  # 审计策略文档
│   ├── audit-policy.yaml                  # Kubernetes Audit Policy
│   ├── drift-audit-cm.yaml                # 漂移审计 ConfigMap
│   ├── compliance-rules.yaml              # 合规规则（OPA/Rego）
│   ├── audit-export-job.yaml              # 审计日志导出 CronJob
│   └── audit-export-scripts.yaml          # 审计导出脚本 ConfigMap
├── policies/                              # 漂移策略
│   ├── drift-policy.yaml                  # 漂移策略总定义
│   ├── ignore-differences.yaml            # 字段忽略规则（按资源类型）
│   └── severity-rules.yaml                # 严重程度分级规则
├── dashboards/                            # 监控面板
│   ├── drift-dashboard.json               # Grafana 漂移监控面板
│   └── drift-metrics.yaml                 # Prometheus 指标规则
└── verify/
    └── verify-drift-detection.sh          # 漂移检测体系验证脚本
```

---

## 4. 快速开始

### 4.1 前置

- T003 ArgoCD 已部署且 `root-dev / root-staging / root-prod` 三 Application 处于 Synced
- 集群已安装 ArgoCD Notifications 控制器（v2.5+ 默认随 ArgoCD 安装）
- Slack Webhook URL / 邮件 SMTP / 钉钉 Webhook 已准备

### 4.2 一键部署

```bash
cd /mnt/f/Agent/workbuddy/workspace/DataEngineBDP
DRIFT_DIR=design/deploy/argocd/drift-detection

# Step 1: 部署漂移策略（字段忽略 / 严重程度规则）
kubectl apply -f $DRIFT_DIR/policies/

# Step 2: 部署资源自定义（告诉 ArgoCD 哪些字段忽略 diff）
kubectl apply -f $DRIFT_DIR/detection/resource-customizations.yaml -n argocd
kubectl apply -f $DRIFT_DIR/detection/diff-server-config.yaml -n argocd

# Step 3: 部署自愈配置（分环境）
kubectl apply -f $DRIFT_DIR/remediation/self-heal-dev.yaml -n argocd
kubectl apply -f $DRIFT_DIR/remediation/self-heal-staging.yaml -n argocd
kubectl apply -f $DRIFT_DIR/remediation/self-heal-prod.yaml -n argocd
kubectl apply -f $DRIFT_DIR/remediation/remediation-rbac-sa.yaml -n argocd
kubectl apply -f $DRIFT_DIR/remediation/remediation-rbac-role.yaml -n argocd
kubectl apply -f $DRIFT_DIR/remediation/remediation-rbac-binding.yaml -n argocd
kubectl apply -f $DRIFT_DIR/remediation/remediation-job.yaml -n argocd

# Step 4: 部署告警通知（ConfigMap + Secret + 路由）
kubectl apply -f $DRIFT_DIR/alerting/argocd-notifications-cm.yaml -n argocd
kubectl apply -f $DRIFT_DIR/alerting/alert-rules.yaml -n argocd
kubectl apply -f $DRIFT_DIR/alerting/slack-templates.yaml -n argocd
kubectl apply -f $DRIFT_DIR/alerting/alert-routing.yaml -n argocd
# Webhook 凭证（含敏感信息，按需替换）
kubectl apply -f $DRIFT_DIR/alerting/webhook-secret.yaml -n argocd

# Step 5: 部署合规审计
kubectl apply -f $DRIFT_DIR/audit/audit-policy.yaml
kubectl apply -f $DRIFT_DIR/audit/drift-audit-cm.yaml -n argocd
kubectl apply -f $DRIFT_DIR/audit/compliance-rules.yaml -n argocd
kubectl apply -f $DRIFT_DIR/audit/audit-export-job.yaml -n argocd
kubectl apply -f $DRIFT_DIR/audit/audit-export-scripts.yaml -n argocd

# Step 6: 部署监控面板
kubectl apply -f $DRIFT_DIR/dashboards/drift-metrics.yaml -n monitoring

# Step 7: 验证
bash $DRIFT_DIR/verify/verify-drift-detection.sh
```

### 4.3 触发一次漂移检测

```bash
# 方式一：手动执行检测脚本
bash design/deploy/argocd/drift-detection/detection/detect-drift.sh

# 方式二：通过 argocd CLI 查看某 App 漂移
argocd app diff root-dev --server-side-generate

# 方式三：触发修复 CronJob
kubectl create job --from=cronjob/drift-remediation -n argocd manual-remediation-$(date +%s)
```

---

## 5. 关键设计

### 5.1 漂移检测机制

```text
┌──────────────────────────────────────────────────────────────┐
│  检测数据源                                                  │
├──────────────────────────────────────────────────────────────┤
│  ① ArgoCD Application status（OutOfSync / Health）           │
│  ② ArgoCD diff（Git manifest vs 集群 live manifest）         │
│  ③ ArgoCD Diff Server（字段级 diff，支持 ignoreDifferences） │
│  ④ kubectl dry-run --server-side 比对                        │
│  ⑤ Kubernetes Audit Log（谁在何时改了什么）                  │
└──────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│  检测流程                                                    │
│  1. 列出所有 ArgoCD Application                              │
│  2. 对每个 App 执行 argocd app diff                          │
│  3. 按 ignoreDifferences 过滤已知良性差异                    │
│  4. 按 severity-rules 分级（Critical/High/Medium/Low）       │
│  5. 写入 drift-event CR / ConfigMap / Prometheus 指标        │
│  6. 触发告警通知                                             │
│  7. 按自愈策略决定是否自动修复                               │
│  8. 全量记录审计日志                                         │
└──────────────────────────────────────────────────────────────┘
```

### 5.2 自愈策略矩阵

| 环境 | selfHeal | 自动修复 Job | 修复窗口 | 人工确认 |
| --- | --- | --- | --- | --- |
| dev | true | 启用 | 7×24 | 否 |
| staging | true | 启用 | 7×24 | 否 |
| prod | true（谨慎） | 仅 Critical | 工作日 09:00-18:00 | High/Critical 需确认 |

> 生产环境 selfHeal=true 但通过 `syncWindows` 限制自动同步窗口，非窗口期漂移仅告警不修复。

### 5.3 告警路由

```text
漂移事件
   │
   ├─ Critical（镜像漂移 / 资源缺失）
   │   └─→ Slack #prod-alerts + 电话呼叫 SRE + 钉钉 + 邮件
   ├─ High（配置漂移 / 资源级漂移）
   │   └─→ Slack #prod-alerts + 钉钉 + 邮件
   ├─ Medium（字段级 / 副本漂移）
   │   └─→ Slack #drift-warnings + 邮件
   └─ Low（标签 / 注解漂移）
       └─→ Slack #drift-info（仅记录，不 @ 人）
```

### 5.4 合规审计

- **审计策略**：Kubernetes Audit Policy 记录所有写操作（apply / patch / delete）
- **合规规则**：OPA/Rego 规则校验"非 ArgoCD 来源的变更"
- **审计导出**：CronJob 每日导出漂移事件到对象存储（MinIO/S3），保留 90 天
- **合规报告**：每周生成合规报告，包含漂移次数 / 修复时长 / 违规变更列表

---

## 6. 验证清单

| 检查项 | 命令 | 期望 |
| --- | --- | --- |
| 策略已加载 | `kubectl get configmap drift-policy -n argocd` | 存在 |
| 资源自定义生效 | `argocd app get root-dev --show-params` | ignoreDifferences 已应用 |
| 检测脚本可用 | `bash detection/detect-drift.sh --dry-run` | 输出漂移列表（空或非空） |
| 自愈配置 | `kubectl get application root-dev -o yaml \| grep selfHeal` | true |
| 修复 CronJob | `kubectl get cronjob drift-remediation -n argocd` | SUSPEND=false |
| 通知 ConfigMap | `kubectl get configmap argocd-notifications-cm -n argocd` | 含 drift 模板 |
| 告警路由 | `kubectl get application root-prod -o yaml \| grep notifications` | 已订阅 |
| 审计策略 | `kubectl get configmap audit-policy -n kube-system` | 存在 |
| 合规规则 | `kubectl get configmap compliance-rules -n argocd` | 含 Rego 规则 |
| 审计导出 Job | `kubectl get cronjob drift-audit-export -n argocd` | SUSPEND=false |
| Grafana 面板 | 浏览器访问 Grafana → 漂移面板 | 可见 drift 指标 |

一键验证：`bash design/deploy/argocd/drift-detection/verify/verify-drift-detection.sh`

---

## 7. 与 T003 ArgoCD 的关系

```text
┌──────────────────────────────────────────────────────────┐
│  T004 漂移检测体系（本目录）                             │
│  ├─ detection/   漂移检测（diff + 资源自定义）           │
│  ├─ remediation/ 自动修复（selfHeal + 修复 Job）         │
│  ├─ alerting/    告警通知（Slack/邮件/钉钉/Webhook）     │
│  ├─ audit/       合规审计（Audit Log + OPA + 导出）      │
│  ├─ policies/    漂移策略（忽略字段 + 严重程度）         │
│  └─ dashboards/  监控面板（Grafana + Prometheus）        │
├──────────────────────────────────────────────────────────┤
│  T003 ArgoCD GitOps（design/deploy/argocd/）             │
│  ├─ applications/  root-dev/staging/prod                 │
│  ├─ sync-policies/ auto + selfHeal + prune               │
│  ├─ projects/      AppProject 多环境隔离                 │
│  └─ rollback/      回滚机制                              │
├──────────────────────────────────────────────────────────┤
│  T001 k3s 集群（ske/k3s/）                               │
└──────────────────────────────────────────────────────────┘
```

**关键集成点**：

1. 本目录的 `resource-customizations.yaml` 注入到 ArgoCD `argocd-cm`，扩展 `resource.customizations`
2. `self-heal-config.yaml` 通过 patch 更新 T003 `applications/root-*.yaml` 的 `syncPolicy.automated.selfHeal`
3. `argocd-notifications-cm.yaml` 替换/合并 ArgoCD 默认通知 ConfigMap
4. `audit-policy.yaml` 写入 `kube-system`，配置 kube-apiserver audit log

---

## 8. 演进路线

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| v0.1（本版本） | 基础漂移检测 + selfHeal + Slack 通知 + 审计日志 | 可部署 |
| v0.2 | 接入 OPA Gatekeeper 强制校验 + 漂移预测（基于历史频率） | 规划 |
| v0.3 | 漂移根因分析（自动定位漂移来源人 / 来源操作） | 规划 |
| v0.4 | 跨集群漂移检测（多集群 GitOps 一致性比对） | 规划 |