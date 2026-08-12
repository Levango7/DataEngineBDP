# Karmada 控制面与成员集群纳管

## 概述

本目录包含 Karmada 多集群控制面的部署配置、控制台 API、Docker 模拟环境与纳管验证文档。

Karmada 是 CNCF 孵化的 Kubernetes 多集群管理方案，本方案使用 Karmada 1.10+ 实现：
- 控制面部署到主集群（通过 Helm Chart + ArgoCD GitOps）
- 纳管 3 个成员集群：信创集群（麒麟 OS + 鲲鹏）、本地集群（标准 K8s）、公有云集群（华为云 CCE）
- PropagationPolicy CRD 封装多集群调度语义，暴露给租户通过控制台 API 管理

## 目录结构

```
platform/karmada/
├── karmada-values.yaml            # Karmada Helm Chart values（控制面配置）
├── karmadactl-join-config.yaml    # 成员集群纳管配置（3 集群）
├── propagation-policy-crd.yaml    # PropagationPolicy CRD 定义
├── argocd-application.yaml        # ArgoCD Application 清单（GitOps 集成）
├── api/                           # PropagationPolicy 控制台 API（Go/Gin）
│   ├── main.go
│   ├── go.mod
│   ├── Dockerfile
│   ├── README.md
│   └── internal/
│       ├── handler/               # HTTP handler
│       ├── model/                 # 数据模型
│       ├── store/                 # 持久化存储
│       └── middleware/            # 中间件（JWT/CORS/日志）
├── docker/                        # Docker 多集群模拟环境
│   ├── docker-compose.yml
│   ├── README.md
│   └── mock-cluster/              # Mock 成员集群服务
│       ├── server.py
│       └── Dockerfile
└── README.md                      # 本文档
```

## 架构

图：Karmada 多集群架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    主集群（Host Cluster）                     │
│  ┌─────────────────┐  ┌──────────────────────────────────┐  │
│  │   ArgoCD (T003)  │  │      Karmada 控制面 (1.10+)      │  │
│  │   GitOps 同步    │──│  apiserver / etcd / scheduler    │  │
│  │                  │  │  controller-manager / webhook    │  │
│  └─────────────────┘  └──────────────┬───────────────────┘  │
│                                       │ push 模式            │
└───────────────────────────────────────┼─────────────────────┘
                                        │
                    ┌───────────────────┼───────────────────┐
                    │                   │                   │
            ┌───────▼───────┐   ┌───────▼───────┐   ┌───────▼───────┐
            │  信创集群      │   │  本地集群      │   │  公有云集群    │
            │  麲鹏/麒麟     │   │  标准 K8s     │   │  华为云 CCE   │
            │  arm64        │   │  amd64        │   │  amd64        │
            │  权重=3       │   │  权重=2       │   │  权重=1       │
            └───────────────┘   └───────────────┘   └───────────────┘
```

## 成员集群纳管

### 集群清单

表：成员集群参数说明表

| 集群名            | 类型      | OS/发行版    | 架构  | 区域       | 环境      | 权重 | maxReplicas |
|-------------------|-----------|--------------|-------|------------|-----------|------|-------------|
| xinchang-cluster  | xinchang  | 麒麟 V10     | arm64 | on-premise | production| 3    | 100         |
| local-cluster     | local     | 标准 K8s     | amd64 | on-premise | staging   | 2    | 50          |
| cce-cluster       | cloud     | 华为云 CCE   | amd64 | cn-north-4 | production| 1    | 200         |

### 纳管步骤

命令示例：成员集群纳管

```bash
# 1. 获取 Karmada 控制面 kubeconfig
kubectl get secret karmada-kubeconfig -n karmada-system \
  -o jsonpath='{.data.value}' | base64 -d > karmada-apiserver.config

# 2. 纳管信创集群
karmadactl join xinchang-cluster \
  --cluster-kubeconfig=xinchang.kubeconfig \
  --karmada-kubeconfig=karmada-apiserver.config \
  --cluster-namespace=karmada-cluster

# 3. 纳管本地集群
karmadactl join local-cluster \
  --cluster-kubeconfig=local.kubeconfig \
  --karmada-kubeconfig=karmada-apiserver.config \
  --cluster-namespace=karmada-cluster

# 4. 纳管公有云集群
karmadactl join cce-cluster \
  --cluster-kubeconfig=cce.kubeconfig \
  --karmada-kubeconfig=karmada-apiserver.config \
  --cluster-namespace=karmada-cluster

# 5. 验证纳管状态
kubectl get clusters -n karmada-cluster
# 期望输出：
# NAME               READY   SYNCED   AGE
# xinchang-cluster   True    True     5m
# local-cluster      True    True     3m
# cce-cluster        True    True     1m
```

### 纳管验证

纳管完成后，执行以下验证：

1. **集群状态**：所有集群 `Ready=True` 且 `Syncable=True`
2. **标签传播**：集群标签（type/vendor/arch/region/env）正确设置
3. **CRD 安装**：成员集群已安装 Karmada 执行 CRD（ClusterResourceBinding 等）
4. **RBAC**：控制面 ServiceAccount 在成员集群有足够权限

## 鲲鹏镜像兼容性说明

信创集群基于鲲鹏 ARM 架构，镜像兼容性需特别注意：

### 多架构镜像要求

表：鲲鹏镜像兼容性对照表

| 组件                | amd64 镜像                          | arm64 镜像                          | 兼容状态 |
|---------------------|-------------------------------------|-------------------------------------|----------|
| Karmada apiserver   | registry.k8s.io/karmada/...         | 同上（多架构清单）                   | ✅ 原生支持 |
| Karmada scheduler   | registry.k8s.io/karmada/...         | 同上（多架构清单）                   | ✅ 原生支持 |
| etcd                | registry.k8s.io/etcd:3.5.16         | 同上（多架构清单）                   | ✅ 原生支持 |
| 业务应用            | 需提供 arm64 清单                   | harbor.xinchang.example.com/...     | ⚠️ 需验证 |

### 验证步骤

命令示例：鲲鹏镜像兼容性验证

```bash
# 1. 检查镜像是否支持 arm64
docker manifest inspect registry.k8s.io/karmada/karmada-apiserver:v1.10.0 \
  | grep -A2 '"architecture": "arm64"'

# 2. 在鲲鹏节点拉取镜像验证
ssh kunpeng-node "crictl pull harbor.xinchang.example.com/app:v1.0.0"

# 3. 部署测试 Pod 到信创集群
cat <<EOF | kubectl apply --cluster xinchang-cluster -f -
apiVersion: v1
kind: Pod
metadata:
  name: arm64-test
spec:
  nodeSelector:
    kubernetes.io/arch: arm64
  containers:
    - name: test
      image: harbor.xinchang.example.com/app:v1.0.0
EOF
```

### 注意事项

- Karmada 官方镜像（registry.k8s.io/karmada/*）提供多架构清单，arm64 原生支持
- 业务应用镜像需在鲲鹏构建节点上构建 arm64 版本，或使用多架构 manifest list
- 信创环境镜像仓库（harbor.xinchang.example.com）需配置鲲鹏构建节点
- 避免使用仅 amd64 的第三方镜像，需寻找替代或自行构建 arm64 版本

## PropagationPolicy 调度语义

### 调度模式

表：调度模式对照表

| 模式       | replicaSchedulingType | 说明                           | 适用场景       |
|------------|----------------------|--------------------------------|----------------|
| 复制模式   | Duplicated           | 每集群部署全副本               | 配置类、守护类 |
| 切分模式   | Divided              | 副本总数按策略分配到多集群     | 无状态服务     |

### 副本分配策略

表：副本分配策略对照表

| 策略       | replicaDivisionPreference | 说明                         |
|------------|---------------------------|------------------------------|
| 聚合       | Aggregated                | 尽量集中到少数集群           |
| 加权       | Weighted                  | 按静态权重分配               |

### 示例：按权重 3:2:1 分配

代码示例：PropagationPolicy 加权分配（YAML）

```yaml
apiVersion: policy.karmada.io/v1alpha1
kind: PropagationPolicy
metadata:
  name: weighted-spread
  namespace: default
spec:
  resourceSelectors:
    - apiVersion: apps/v1
      kind: Deployment
  placement:
    clusterAffinity:
      labelSelector:
        matchExpressions:
          - key: cluster.karmada.io/type
            operator: In
            values: [xinchang, local, cloud]
    replicaScheduling:
      replicaSchedulingType: Divided
      replicaDivisionPreference: Weighted
      weightPreference:
        staticWeightList:
          - targetCluster: { clusterNames: [xinchang-cluster] }
            weight: 3
          - targetCluster: { clusterNames: [local-cluster] }
            weight: 2
          - targetCluster: { clusterNames: [cce-cluster] }
            weight: 1
    spreadConstraints:
      - spreadByField: cluster
        minGroups: 2
        maxGroups: 3
```

部署 6 副本的 Deployment 时，Karmada 调度器将按 3:2:1 分配：
- 信创集群：3 副本
- 本地集群：2 副本
- 公有云集群：1 副本

## ArgoCD GitOps 集成

与 Phase 1 ArgoCD（T003）集成，Karmada Helm Chart 纳入 GitOps：

1. `argocd-application.yaml` 定义 ArgoCD Application，自动同步 Karmada Helm Chart
2. `propagation-policy-crd.yaml` 通过 ArgoCD 同步 CRD 定义
3. 同步策略：自动同步 + 自愈 + 修剪（CRD 不修剪，避免误删）
4. 通知：同步失败/健康降级时发送 Slack 告警

## Docker 模拟环境

用于集成测试和本地开发，详见 [docker/README.md](docker/README.md)。

命令示例：启动 Docker 模拟环境

```bash
# 启动
docker compose -f platform/karmada/docker/docker-compose.yml up -d --build

# 运行测试
pytest tests/integration/docker/test_karmada.py -v

# 停止
docker compose -f platform/karmada/docker/docker-compose.yml down -v
```

## 集成测试

测试文件：`tests/integration/docker/test_karmada.py`

测试覆盖：

表：集成测试覆盖说明表

| 测试类                             | 覆盖内容                                   |
|------------------------------------|--------------------------------------------|
| TestClusterEnrollment              | 3 集群纳管状态、元数据、Ready/Syncable    |
| TestPropagationPolicyCRUD          | 控制台 API CRUD、认证、404 处理            |
| TestReplicaWeightedDistribution    | 副本按权重 3:2:1 分配、Duplicated 模式    |
| TestPolicySyncToClusters           | 策略同步到成员集群                         |

## 验收标准对照

表：验收标准对照表

| 验收标准                          | 交付物                                       | 状态 |
|-----------------------------------|----------------------------------------------|------|
| Karmada ≥ 1.10 Helm Chart 配置    | karmada-values.yaml                          | ✅   |
| 3 个成员集群纳管配置              | karmadactl-join-config.yaml                  | ✅   |
| PropagationPolicy CRD 定义        | propagation-policy-crd.yaml                  | ✅   |
| 控制台 API 骨架                   | api/ (Go/Gin REST API)                       | ✅   |
| Docker 多集群模拟配置             | docker/docker-compose.yml + mock-cluster     | ✅   |
| pytest 测试套件                   | tests/integration/docker/test_karmada.py     | ✅   |
| ArgoCD GitOps 集成                | argocd-application.yaml                      | ✅   |
| 鲲鹏镜像兼容性说明                | 本文档「鲲鹏镜像兼容性说明」章节            | ✅   |

## 相关任务

- **T003**：ArgoCD GitOps 部署（Phase 1 已完成，本任务与其集成）
- **T026**：Karmada 控制面与成员集群纳管（本任务）