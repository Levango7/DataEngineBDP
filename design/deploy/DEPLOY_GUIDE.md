# 部署指南

## 1. 前置条件

### 1.1 CLI 工具

| 工具 | 最低版本 | 用途 | 安装验证 |
|------|---------|------|---------|
| helm | v3.12+ | Helm Chart 渲染与部署 | `helm version` |
| kubectl | v1.28+ | K8s 集群操作 | `kubectl version --client` |
| argocd | v2.7+ | ArgoCD GitOps 管理 | `argocd version` |
| git | v2.40+ | 仓库克隆 | `git --version` |

### 1.2 集群要求

- Kubernetes v1.28+
- 已安装 ArgoCD（`argocd` namespace）
- StorageClass `sq-fast-ssd`（prod PVC 依赖）
- IngressClass `apisix`（Ingress 依赖）
- 集群可达镜像仓库（`docker.m.daocloud.io` 或自建 Harbor）

### 1.3 环境变量

```bash
export GIT_REPO="https://github.com/Levango7/DataEngineBDP"
export GIT_BRANCH="main"
export ARGOCD_SERVER="argocd.example.com"
export ARGOCD_USER="admin"
```

## 2. 项目结构

```
design/deploy/
├── charts/                          # 83 个 Helm Chart
│   ├── encaps-layer/
│   │   ├── Chart.yaml
│   │   ├── values.yaml              # Chart 默认 values（骨架级）
│   │   └── templates/
│   ├── sql-gateway/
│   └── ...                          # 其余 81 个 Chart
├── values/                          # 深度调优 values（单环境）
│   ├── spark-values.yaml
│   └── ...
├── values/env/                      # 多环境 values（本次新增）
│   ├── dev/                         # 10 个组件的 dev values
│   ├── staging/                     # 10 个组件的 staging values
│   └── prod/                        # 10 个组件的 prod values
├── argocd/                          # ArgoCD GitOps 配置
│   ├── applications.yaml            # 30 个 Application（10 组件 × 3 环境）
│   ├── applicationset-all-components.yaml  # 全量 83 组件 ApplicationSet
│   ├── kustomization.yaml           # 总入口
│   ├── projects/                    # 3 个 AppProject（dev/staging/prod）
│   ├── sync-policies/               # 同步策略 + syncWave 定义
│   └── kustomize/                   # Kustomize base + overlays
├── HEML_LINT_REPORT.md              # Chart 完整性验证报告
└── DEPLOY_GUIDE.md                  # 本文档
```

## 3. dev 环境部署

### 3.1 方式一：Helm 直接部署（手动）

```bash
# 克隆仓库
git clone $GIT_REPO && cd DataEngineBDP

# 部署 encaps-layer 到 dev
helm upgrade --install encaps-layer \
  design/deploy/charts/encaps-layer \
  -n dev-system \
  --create-namespace \
  -f design/deploy/values/env/dev/encaps-layer-values.yaml

# 部署 sql-gateway 到 dev
helm upgrade --install sql-gateway \
  design/deploy/charts/sql-gateway \
  -n dev-engine \
  --create-namespace \
  -f design/deploy/values/env/dev/sql-gateway-values.yaml

# 批量部署 10 个关键组件
for comp in encaps-layer sql-gateway catalog rule-engine spark flink trino doris kafka superset; do
  ns="dev-engine"
  [[ "$comp" == "encaps-layer" ]] && ns="dev-system"
  [[ "$comp" == "superset" ]] && ns="dev-app"
  helm upgrade --install "$comp" \
    "design/deploy/charts/$comp" \
    -n "$ns" --create-namespace \
    -f "design/deploy/values/env/dev/$comp-values.yaml"
done

# 验证
kubectl get pods -n dev-system
kubectl get pods -n dev-engine
kubectl get pods -n dev-app
```

### 3.2 方式二：ArgoCD GitOps（推荐）

```bash
# 登录 ArgoCD
argocd login $ARGOCD_SERVER --username $ARGOCD_USER --grpc-web

# 方式 A：部署全部 ArgoCD 资源（AppProject + Application）
kubectl apply -k design/deploy/argocd/

# 方式 B：仅部署 dev 环境的 AppProject 和 Application
kubectl apply -f design/deploy/argocd/projects/platform-dev.yaml
kubectl apply -f design/deploy/argocd/applications.yaml

# 查看 Application 状态
argocd app list | grep dev-

# 查看同步详情
argocd app get dev-encaps-layer

# 手动触发同步（若未开启 auto-sync）
argocd app sync dev-encaps-layer
```

### 3.3 dev 环境特征

| 维度 | 值 |
|------|-----|
| namespace | dev-system / dev-engine / dev-app |
| 副本数 | 1（单副本） |
| 资源 | 100m~500m CPU, 256Mi~1Gi 内存 |
| 日志 | DEBUG |
| 数据库 | H2 内存 / SQLite |
| PVC | 关闭 |
| HPA | 关闭 |
| Ingress | 开启（无 TLS） |

## 4. staging 环境部署

### 4.1 Helm 直接部署

```bash
for comp in encaps-layer sql-gateway catalog rule-engine spark flink trino doris kafka superset; do
  ns="staging-engine"
  [[ "$comp" == "encaps-layer" ]] && ns="staging-system"
  [[ "$comp" == "superset" ]] && ns="staging-app"
  helm upgrade --install "$comp" \
    "design/deploy/charts/$comp" \
    -n "$ns" --create-namespace \
    -f "design/deploy/values/env/staging/$comp-values.yaml"
done
```

### 4.2 ArgoCD GitOps

```bash
kubectl apply -f design/deploy/argocd/projects/platform-staging.yaml
kubectl apply -f design/deploy/argocd/applications.yaml

argocd app list | grep staging-
```

### 4.3 staging 环境特征

| 维度 | 值 |
|------|-----|
| namespace | staging-system / staging-engine / staging-app |
| 副本数 | 2~3（多副本） |
| 资源 | 500m~1 CPU, 1~2Gi 内存 |
| 日志 | INFO |
| 数据库 | PostgreSQL |
| PVC | 关闭 |
| HPA | 关闭 |
| PDB | 开启（minAvailable=1） |
| Ingress | 开启（无 TLS） |

## 5. prod 环境部署

### 5.1 前置检查

```bash
# 确认 StorageClass 存在
kubectl get storageclass sq-fast-ssd

# 确认 TLS Secret 存在（或由 cert-manager 自动签发）
kubectl get secret encaps-layer-tls -n prod-system

# 确认同步窗口（prod 仅工作日 09:00-18:00 自动同步）
argocd proj get platform-prod | grep syncWindows
```

### 5.2 Helm 直接部署

```bash
for comp in encaps-layer sql-gateway catalog rule-engine spark flink trino doris kafka superset; do
  ns="prod-engine"
  [[ "$comp" == "encaps-layer" ]] && ns="prod-system"
  [[ "$comp" == "superset" ]] && ns="prod-app"
  helm upgrade --install "$comp" \
    "design/deploy/charts/$comp" \
    -n "$ns" --create-namespace \
    -f "design/deploy/values/env/prod/$comp-values.yaml"
done
```

### 5.3 ArgoCD GitOps

```bash
# 部署 prod AppProject（含同步窗口限制）
kubectl apply -f design/deploy/argocd/projects/platform-prod.yaml

# 部署 Application
kubectl apply -f design/deploy/argocd/applications.yaml

# prod 同步需在窗口内（工作日 09:00-18:00），窗口外需手动同步
argocd app sync prod-encaps-layer  # 手动同步（窗口外）

# 验证全部 prod Application 健康
argocd app list | grep prod- | grep -v Healthy
```

### 5.4 prod 环境特征

| 维度 | 值 |
|------|-----|
| namespace | prod-system / prod-engine / prod-app |
| 副本数 | 2~3（高可用） |
| 资源 | 1~2 CPU, 2~4Gi 内存 |
| 日志 | WARN |
| 数据库 | PostgreSQL + 连接池调优 |
| PVC | 开启（sq-fast-ssd, 50~200Gi） |
| HPA | 开启（CPU 75%, 3~10 副本） |
| PDB | 开启（minAvailable=2） |
| Ingress | 开启（TLS） |
| 同步窗口 | 工作日 09:00-18:00 |

## 6. 全量部署（83 个组件）

### 6.1 ApplicationSet 自动发现

```bash
# 部署 ApplicationSet（自动为 83 个 Chart × 3 环境生成 Application）
kubectl apply -f design/deploy/argocd/applicationset-all-components.yaml

# 查看生成的 Application
argocd app list | wc -l  # 预期 249+（83×3）
```

### 6.2 Kustomize 一键部署

```bash
# 一键部署所有 ArgoCD 资源
kubectl apply -k design/deploy/argocd/
```

## 7. 同步波次

ArgoCD 通过 `argocd.argoproj.io/sync-wave` 注解控制同步顺序：

| wave | 层级 | 组件 |
|------|------|------|
| -10 | 基础设施 | cert-manager, cni-cilium, csi-ceph, metallb, metrics-server |
| -5 | 存储消息 | postgresql, redis, minio, kafka, zookeeper, elasticsearch |
| -3 | 数据存储 | kafka, doris, iotdb, nebula-graph, milvus |
| -2 | 计算引擎 | spark, flink, trino, seatunnel, dolphinscheduler, airflow |
| -1 | 网关安全 | keycloak, apisix, ingress-nginx, external-secrets |
| 0 | 业务服务 | encaps-layer, sql-gateway, catalog, rule-engine, governance |
| 1 | 应用可视化 | superset, jupyter, mlflow, ai-assistant, theia |
| 2 | 模板可观测 | finance-template, grafana, prometheus, loki, tempo |
| 3 | 基础 Provider | infra-orchestrator, infra-provider-* |

## 8. 故障排查

### 8.1 Application 不同步

```bash
# 查看 Application 状态
argocd app get <app-name>

# 常见原因：
# 1. 同步窗口限制（prod 仅工作日 9-18 点）
#    解决：argocd app sync <app-name>  # 手动同步
# 2. AppProject 限制（namespace 不在 destinations 中）
#    解决：检查 projects/platform-<env>.yaml 的 destinations
# 3. Chart 不存在或路径错误
#    解决：确认 design/deploy/charts/<component>/ 存在
```

### 8.2 Pod 启动失败

```bash
# 查看 Pod 事件
kubectl describe pod <pod-name> -n <namespace>

# 查看 Pod 日志
kubectl logs <pod-name> -n <namespace>

# 常见原因：
# 1. 镜像拉取失败 → 检查 image.repository 和集群镜像仓库可达性
# 2. 资源不足 → kubectl describe node 查看 allocatable
# 3. StorageClass 不存在 → kubectl get storageclass
# 4. ConfigMap 缺失 → 检查 Chart templates/configmap.yaml
```

### 8.3 Helm 渲染错误

```bash
# 本地渲染验证（无需集群）
helm template <release-name> design/deploy/charts/<component> \
  -f design/deploy/values/env/<env>/<component>-values.yaml

# lint 检查
helm lint design/deploy/charts/<component> \
  -f design/deploy/values/env/<env>/<component>-values.yaml
```

### 8.4 ArgoCD 漂移检测

```bash
# 查看漂移状态
argocd app get <app-name> --refresh

# 启用 selfHeal 后漂移自动回滚
# 若需暂时禁用自愈：
argocd app set <app-name> --auto-prune=false --self-heal=false
```

### 8.5 values 覆盖未生效

```bash
# 确认 values 文件路径正确
# ArgoCD Application 中 valueFiles 路径相对于 Chart path
# 例：path: design/deploy/charts/encaps-layer
#     valueFiles: ../../values/env/dev/encaps-layer-values.yaml
#     实际解析为：design/deploy/values/env/dev/encaps-layer-values.yaml

# 渲染确认
helm template encaps-layer design/deploy/charts/encaps-layer \
  -f design/deploy/values/env/dev/encaps-layer-values.yaml | grep -A5 "image:"
```

## 9. 回滚

```bash
# ArgoCD 回滚到历史版本
argocd app history <app-name>
argocd app rollback <app-name> <revision-id>

# Helm 回滚
helm rollback <release-name> <revision> -n <namespace>
```

## 10. 清理

```bash
# 删除单个 Application（级联清理资源）
argocd app delete <app-name>

# 删除某环境全部 Application
argocd app list | grep dev- | awk '{print $1}' | xargs argocd app delete

# 删除 AppProject（需先删除其下所有 Application）
argocd proj delete platform-dev

# Helm 卸载
helm uninstall encaps-layer -n dev-system
```