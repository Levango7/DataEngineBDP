# 漂移检测策略

> 版本：v0.1 ｜ 日期：2026-08-07
> 依赖：T003 ArgoCD 已部署，`argocd` CLI 可用

---

## 1. 检测目标

实时识别集群实际状态与 Git 声明之间的差异，输出结构化漂移事件，供修复 / 告警 / 审计消费。

## 2. 检测数据源

| 数据源 | 用途 | 采集方式 |
| --- | --- | --- |
| ArgoCD Application status | 判断 OutOfSync / Degraded | `kubectl get application -n argocd` |
| ArgoCD diff | 字段级差异 | `argocd app diff <app> --server-side-generate` |
| ArgoCD Diff Server | 增强字段级 diff（支持 ignoreDifferences） | ArgoCD v2.6+ 内置 |
| kubectl dry-run | 独立校验（不依赖 ArgoCD） | `kubectl apply --dry-run=server -f` |
| Kubernetes Audit Log | 变更来源追溯 | kube-apiserver audit log |
| Prometheus 指标 | 历史趋势 + 告警 | `argocd_app_sync_status` 等指标 |

## 3. 检测流程

```text
┌─────────────────────────────────────────────────────────┐
│  Step 1: 枚举 Application                               │
│  argocd app list -o json                                │
├─────────────────────────────────────────────────────────┤
│  Step 2: 对每个 App 执行 diff                           │
│  argocd app diff <app> --server-side-generate           │
├─────────────────────────────────────────────────────────┤
│  Step 3: 加载 ignoreDifferences 过滤良性差异            │
│  - Istio sidecar 注入字段                               │
│  - kubectl.kubernetes.io/last-applied-configuration     │
│  - managedFields / resourceVersion / uid                │
│  - HPA 注入的 replicas                                  │
├─────────────────────────────────────────────────────────┤
│  Step 4: 按 severity-rules 分级                         │
│  - 镜像 tag 不一致 → Critical                           │
│  - ConfigMap/Secret 内容变更 → High                     │
│  - replicas 变更 → Medium                               │
│  - label/annotation 变更 → Low                          │
├─────────────────────────────────────────────────────────┤
│  Step 5: 输出漂移事件                                   │
│  - 写入 ConfigMap drift-event-<app>-<ts>                │
│  - 暴露 Prometheus 指标                                 │
│  - 触发 ArgoCD notification                             │
├─────────────────────────────────────────────────────────┤
│  Step 6: 记录审计日志                                   │
│  - 漂移事件全量写入 audit log                           │
│  - 关联 Kubernetes audit log（变更来源）                │
└─────────────────────────────────────────────────────────┘
```

## 4. 检测频率

| 环境 | 频率 | 触发方式 |
| --- | --- | --- |
| dev | 每 5 分钟 | CronJob |
| staging | 每 3 分钟 | CronJob |
| prod | 每 1 分钟 | CronJob + ArgoCD 实时 sync status |

## 5. ignoreDifferences 规则

以下差异视为**良性差异**，不计入漂移：

1. **系统注入字段**：
   - `metadata.managedFields`
   - `metadata.resourceVersion`
   - `metadata.uid`
   - `metadata.creationTimestamp`
   - `metadata.generation`
   - `status`（所有资源的 status 子资源）

2. **kubectl 注解**：
   - `kubectl.kubernetes.io/last-applied-configuration`

3. **Istio sidecar 注入**：
   - `spec.template.spec.containers[name=istio-proxy]`
   - `spec.template.spec.initContainers[name=istio-init]`
   - `spec.template.metadata.annotations.proxy.istio.io/config`

4. **HPA 管理字段**：
   - `spec.replicas`（当资源被 HPA 管理时）

5. **ArgoCD 管理注解**：
   - `argocd.argoproj.io/sync-wave`

## 6. 检测输出格式

漂移事件以 ConfigMap 形式存储，便于查询与保留：

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: drift-event-root-prod-20260807-093000
  namespace: argocd
  labels:
    drift-event: "true"
    app: root-prod
    environment: prod
    severity: high
  annotations:
    detected-at: "2026-08-07T09:30:00Z"
    detected-by: "drift-detection-cronjob"
data:
  app: root-prod
  environment: prod
  severity: high
  drift-type: config-drift
  resource: prod-platform/prod-data-engineer/ConfigMap/engine-config
  diff-summary: |
    --- git
    +++ cluster
    - data.logLevel: "info"
    + data.logLevel: "debug"
  remediation-action: "auto-heal"
  remediation-status: "pending"
```

## 7. 与 ArgoCD 原生检测的关系

ArgoCD 自身已具备漂移检测能力（Application status `syncStatus: OutOfSync`），本模块在此基础上增强：

| 能力 | ArgoCD 原生 | 本模块增强 |
| --- | --- | --- |
| OutOfSync 检测 | ✅ | ✅ |
| 字段级 diff | ✅（通过 `argocd app diff`） | ✅ + 按严重程度分级 |
| ignoreDifferences | ✅（Application spec） | ✅ + 集中化管理（policies/） |
| 漂移事件持久化 | ❌（仅 status 字段） | ✅ ConfigMap 持久化 |
| 漂移历史趋势 | ❌ | ✅ Prometheus 指标 |
| 漂移来源追溯 | ❌ | ✅ 关联 K8s audit log |
| 跨 App 聚合视图 | ❌ | ✅ Grafana 面板 |