# 多集群联邦 Karmada 架构与使用指南

> 版本：2026-09-11 ｜ 状态：**骨架已交付，真实多集群验证待执行** ｜ 关联文档：[Karmada 控制面 README](../platform/karmada/README.md)、[故障迁移 README](../platform/karmada/failover/README.md)、[联邦查询 README](../platform/karmada/federated-query/README.md)、[PropagationPolicy API](../platform/karmada/api/README.md)

## 一、文档目的

本文档补全数据引擎大数据平台（DataEngineBDP）多集群联邦 Karmada 架构的完整说明，覆盖架构说明、使用指南、配置参数与验证方案。现有 `platform/karmada/` 下的各子模块 README 已描述组件级实现，本文档在系统层面整合，作为多集群联邦能力的统一入口文档。

## 二、架构说明

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                      主集群（Host Cluster）                          │
│  ┌─────────────────┐  ┌──────────────────────────────────────────┐  │
│  │   ArgoCD        │  │      Karmada 控制面 (1.10+)              │  │
│  │   GitOps 同步   │──│  apiserver / etcd / scheduler            │  │
│  │                 │  │  controller-manager / webhook            │  │
│  └─────────────────┘  └──────────────┬───────────────────────────┘  │
│                                       │ push 模式                    │
│  ┌─────────────────┐  ┌──────────────┴───────────────────────────┐  │
│  │ karmada-api     │  │ failover-engine                           │  │
│  │ (Go/Gin:8090)   │  │ (Go: 健康检查 + 故障迁移)                 │  │
│  │ PropagationPolicy│ │ Prometheus 指标采集 + 迁移决策            │  │
│  └─────────────────┘  └──────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ federated-query (Java/Spring Boot:8094)                      │   │
│  │ 跨集群查询路由 + 表元数据定位 + mTLS 传输 + 结果归并          │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                         │
                     ┌───────────────────┼───────────────────┐
                     │                   │                   │
             ┌───────▼───────┐   ┌───────▼───────┐   ┌───────▼───────┐
             │  信创集群      │   │  本地集群      │   │  公有云集群    │
             │  鲲鹏/麒麟     │   │  标准 K8s     │   │  华为云 CCE   │
             │  arm64        │   │  amd64        │   │  amd64        │
             │  权重=3       │   │  权重=2       │   │  权重=1       │
             └───────────────┘   └───────────────┘   └───────────────┘
```

### 2.2 核心组件

| 组件 | 目录 | 技术栈 | 端口 | 职责 | 成熟度 |
| --- | --- | --- | --- | --- | --- |
| **Karmada 控制面** | `platform/karmada/`（Helm values） | Karmada 1.10+ | — | 多集群管理控制面（apiserver/etcd/scheduler） | 部署配置就绪 |
| **karmada-api** | `platform/karmada/api/` | Go 1.26 / Gin | 8090 | PropagationPolicy CRUD 控制台 API | 骨架（策略 CRUD 未接控制面） |
| **failover-api** | `platform/karmada/failover/api/` | Go 1.26 / Gin | — | OverridePolicy + Failover 控制台 API | 骨架 |
| **failover-engine** | `platform/karmada/failover/engine/` | Go 1.26 | — | 集群健康检查 + 故障迁移决策引擎 | 骨架 |
| **federated-query** | `/platform/karmada/federated-query/` | Java 17 / Spring Boot 4.1.1 | 8094 | 跨集群查询路由 + 表定位 + 结果归并 | 骨架 |

### 2.3 成员集群

| 集群名 | 类型 | OS/发行版 | 架构 | 区域 | 环境 | 权重 | maxReplicas |
| --- | --- | --- | --- | --- | --- | --- | --- |
| xinchang-cluster | 信创 | 麒麟 V10 | arm64 | on-premise | production | 3 | 100 |
| local-cluster | 本地 | 标准 K8s | amd64 | on-premise | staging | 2 | 50 |
| cce-cluster | 公有云 | 华为云 CCE | amd64 | cn-north-4 | production | 1 | 200 |

### 2.4 联邦查询流程

```
用户 SQL 请求 → federated-query(8094)
  → Calcite AST 解析 → 表元数据定位（Catalog API）
  → 路由查询到对应集群（mTLS 传输）
  → 跨集群结果归并（CONCAT/UNION/JOIN/AGG）
  → 返回统一结果
```

### 2.5 故障转移流程

```
failover-engine 定期健康检查（Prometheus + Karmada API）
  → 检测集群故障（连续 N 次健康检查失败）
  → 触发 FailoverEvent
  → 按 FailoverPolicy 调整 PropagationPolicy（排除故障集群）
  → 60s 内完成迁移到备用集群
  → 告警通知 + 迁移历史记录
```

## 三、使用指南

### 3.1 添加成员集群

#### 步骤 1：准备集群 kubeconfig

```bash
# 获取成员集群的 kubeconfig（以信创集群为例）
ssh xinchang-master "cat /etc/kubernetes/admin.conf" > xinchang.kubeconfig
```

#### 步骤 2：通过 karmadactl 纳管

```bash
# 纳管信创集群
karmadactl join xinchang-cluster \
  --cluster-kubeconfig=xinchang.kubeconfig \
  --karmada-context=karmada-apiserver

# 纳管本地集群
karmadactl join local-cluster \
  --cluster-kubeconfig=local.kubeconfig \
  --karmada-context=karmada-apiserver

# 纳管公有云集群
karmadactl join cce-cluster \
  --cluster-kubeconfig=cce.kubeconfig \
  --karmada-context=karmada-apiserver
```

#### 步骤 3：验证纳管

```bash
# 查看已纳管集群
kubectl get clusters --karmada-context=karmada-apiserver

# 预期输出：
# NAME               READY   AGE
# xinchang-cluster   True    5m
## local-cluster      True    3m
# cce-cluster        True    1m
```

### 3.2 创建联邦策略

#### PropagationPolicy（传播策略）

```bash
# 通过 karmada-api 创建传播策略
curl -X POST http://karmada-api:8090/api/v1/propagation-policies \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "weighted-spread",
    "namespace": "default",
    "spec": {
      "resourceSelectors": [
        {"apiVersion": "apps/v1", "kind": "Deployment", "name": "my-app"}
      ],
      "placement": {
        "clusterAffinity": {
          "matchLabels": {"cluster.karmada.io/type": "production"}
        },
        "replicaScheduling": {
          "replicaSchedulingType": "Divided",
          "replicaDivisionPreference": "Weighted",
          "weightPreference": {
            "staticWeightList": [
              {"targetCluster": {"clusterNames": ["xinchang-cluster"]}, "weight": 3},
              {"targetCluster": {"clusterNames": ["local-cluster"]}, "weight": 2},
              {"targetCluster": {"clusterNames": ["cce-cluster"]}, "weight": 1}
            ]
          }
        }
      }
    }
  }'
```

#### OverridePolicy（集群差异化覆盖）

```bash
# 通过 failover-api 创建覆盖策略（信创集群使用 arm64 镜像）
curl -X POST http://failover-api:8080/api/v1/override-policies \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "xinchang-arm64-override",
    "namespace": "default",
    "spec": {
      "resourceSelectors": [
        {"apiVersion": "apps/v1", "kind": "Deployment", "name": "my-app"}
      ],
      "overrideRules": [
        {
          "targetCluster": {"clusterNames": ["xinchang-cluster"]},
          "overriders": {
            "imageOverride": {
              "image": "my-app:latest",
              "newImage": "my-app:latest-arm64"
            }
          }
        }
      ]
    }
  }'
```

### 3.3 查询跨集群数据

```bash
# 通过 federated-query 执行跨集群查询
curl -X POST http://federated-query:8094/api/v1/federated-query \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT cluster_name, count(*) FROM cluster_metrics GROUP BY cluster_name",
    "timeout": 30
  }'
```

**预期结果**：

```json
{
  "status": "success",
  "data": [
    {"cluster_name": "xinchang-cluster", "count": 150},
    {"cluster_name": "local-cluster", "count": 100},
    {"cluster_name": "cce-cluster", "count": 50}
  ],
  "metadata": {
    "clusters_queried": 3,
    "latency_ms": 1250
  }
}
```

### 3.4 故障转移配置

```bash
# 创建 FailoverPolicy
curl -X POST http://failover-api:8080/api/v1/failover-policies \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "auto-failover",
    "namespace": "default",
    "spec": {
      "detection": {
        "healthCheckInterval": "30s",
        "failureThreshold": 3,
        "metrics": ["cluster_ready", "api_server_latency", "pod_success_rate"]
      },
      "failover": {
        "gracePeriod": "60s",
        "targetClusters": ["local-cluster", "cce-cluster"],
        "strategy": "weighted-redistribute"
      }
    }
  }'
```

## 四、配置参数

### 4.1 Karmada 控制面配置

配置文件：`platform/karmada/karmada-values.yaml`

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `karmada.version` | 1.10.0 | Karmada 版本 |
| `karmada.apiserver.replicas` | 1 | apiserver 副本数 |
| `karmada.etcd.replicas` | 1 | etcd 副本数 |
| `karmada.scheduler.replicas` | 1 | scheduler 副本数 |
| `karmada.controllerManager.replicas` | 1 | controller-manager 副本数 |

### 4.2 karmada-api 服务配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `KARMADA_API_VERSION` | 0.1.0 | 服务版本 |
| `KARMADA_API_PORT` | 8090 | 监听端口 |
| `KARMADA_API_DB` | karmada-api.db | SQLite 数据库路径 |
| `JWT_SECRET` | dev-secret-key-... | JWT 签名密钥 |
| `JWT_ISSUER` | shuqing-bigdata | JWT issuer |

### 4.3 federated-query 服务配置

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| 服务端口 | 8094 | REST API 监听端口 |
| 查询超时 | 30s | 跨集群查询超时 |
| mTLS | 启用 | Istio mTLS 双向认证 |
| 降级策略 | 单集群查询 | 网络中断时降级到本地集群查询 |

### 4.4 failover-engine 调度策略

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `healthCheckInterval` | 30s | 健康检查间隔 |
| `failureThreshold` | 3 | 连续失败次数阈值 |
| `gracePeriod` | 60s | 迁移宽限期 |
| `metrics` | cluster_ready, api_server_latency, pod_success_rate | 健康检查指标 |

### 4.5 故障转移阈值

| 指标 | 阈值 | 说明 |
| --- | --- | --- |
| `cluster_ready` | `True` | 集群 Ready 状态 |
| `api_server_latency` | < 5s | API Server P99 延迟 |
| `pod_success_rate` | > 95% | Pod 成功率 |
| 连续失败次数 | ≥ 3 | 触发故障转移 |
| 迁移完成时间 | ≤ 60s | 从检测到迁移完成 |

## 五、验证方案

### 5.1 多集群部署验证

```bash
# 1. 部署 Karmada 控制面
helm install karmada platform/karmada/ -f platform/karmada/karmada-values.yaml

# 2. 等待控制面就绪
kubectl wait --for=condition=ready pod -l app=karmada --timeout=300s

# 3. 纳管 3 个成员集群
karmadactl join xinchang-cluster --cluster-kubeconfig=xinchang.kubeconfig
karmadactl join local-cluster --cluster-kubeconfig=local.kubeconfig
karmadactl join cce-cluster --cluster-kubeconfig=cce.kubeconfig

# 4. 验证集群纳管
kubectl get clusters
# 预期：3 个集群均为 Ready

# 5. 部署 karmada-api / failover / federated-query
helm install karmada-api platform/karmada/api/
helm install failover platform/karmada/failover/
helm install federated-query platform/karmada/federated-query/

# 6. 验证服务就绪
kubectl get pods -l app=karmada-api
kubectl get pods -l app=failover
kubectl get pods -l app=federated-query
```

### 5.2 故障转移验证

```bash
# 1. 部署测试应用（跨 3 集群）
kubectl create deployment test-app --image=nginx --replicas=6
# 应用 PropagationPolicy 使副本分散到 3 集群

# 2. 验证副本分布
kubectl get deployment test-app --karmada-context=karmada-apiserver
# 预期：xinchang=3, local=2, cce=1（按权重 3:2:1）

# 3. 模拟信创集群故障
kubectl cordon xinchang-cluster
kubectl drain xinchang-cluster --ignore-daemonsets

# 4. 等待故障转移
sleep 90  # 等待健康检查 + 迁移

# 5. 验证副本已迁移
kubectl get deployment test-app --karmada-context=karmada-apiserver
# 预期：xinchang=0, local=3, cce=3（权重重分配）

# 6. 恢复信创集群
kubectl uncordon xinchang-cluster

# 7. 验证副本回迁
sleep 90
kubectl get deployment test-app --karmada-context=karmada-apiserver
# 预期：xinchang=3, local=2, cce=1（恢复原始权重）
```

### 5.3 查询一致性验证

```bash
# 1. 在 3 个集群分别创建测试表
for cluster in xinchang-cluster local-cluster cce-cluster; do
  kubectl exec -it mysql-${cluster} -- mysql -e "
    CREATE TABLE IF NOT EXISTS test_data (id INT, cluster_name VARCHAR(50));
    INSERT INTO test_data VALUES (1, '${cluster}');
  "
done

# 2. 通过 federated-query 执行跨集群查询
RESULT=$(curl -s -X POST http://federated-query:8094/api/v1/federated-query \
  -H "Authorization: Bearer <token>" \
  -d '{"sql": "SELECT * FROM test_data ORDER BY id"}')

# 3. 验证结果包含所有集群数据
echo "${RESULT}" | grep -q "xinchang-cluster"
echo "${RESULT}" | grep -q "local-cluster"
echo "${RESULT}" | grep -q "cce-cluster"

# 4. 验证结果一致性（与单集群查询对比）
for cluster in xinchang-cluster local-cluster cce-cluster; do
  SINGLE=$(kubectl exec mysql-${cluster} -- mysql -e "SELECT * FROM test_data")
  echo "Cluster ${cluster}: ${SINGLE}"
done
# 跨集群查询结果应等于各集群结果的并集
```

### 5.4 验证报告模板

```markdown
# Karmada 多集群联邦验证报告

> 验证日期：YYYY-MM-DD ｜ 验证人：___ ｜ Karmada 版本：___

## 一、环境

| 项目 | 值 |
| --- | --- |
| Karmada 版本 | ___ |
| 成员集群数 | ___ |
| 集群列表 | xinchang-cluster / local-cluster / cce-cluster |

## 二、验证结果

| 验证项 | 结果 | 说明 |
| --- | --- | --- |
| 控制面部署 | ✅/❌ | ___ |
| 集群纳管 | ✅/❌ | ___ |
| PropagationPolicy | ✅/❌ | ___ |
| OverridePolicy | ✅/❌ | ___ |
| 故障转移 | ✅/❌ | ___ |
| 联邦查询 | ✅/❌ | ___ |
| 查询一致性 | ✅/❌ | ___ |

## 三、结论

- [ ] 全部验证通过，多集群联邦能力可交付
- [ ] 存在失败项，需修复后重新验证
```

## 六、当前状态与待办

### 6.1 已交付

| 能力 | 状态 | 出处 |
| --- | --- | --- |
| Karmada 控制面部署配置 | ✅ | `karmada-values.yaml` + `argocd-application.yaml` |
| 成员集群纳管配置 | ✅ | `karmadactl-join-config.yaml`（3 集群） |
| PropagationPolicy CRD | ✅ | `propagation-policy-crd.yaml` |
| PropagationPolicy 控制台 API | ✅（骨架） | `karmada/api/`（Go/Gin，CRUD 未接控制面） |
| OverridePolicy + Failover API | ✅（骨架） | `karmada/failover/api/`（Go/Gin） |
| 故障迁移引擎 | ✅（骨架） | `karmada/failover/engine/`（Go，健康检查 + 迁移决策） |
| 跨集群联邦查询 | ✅（骨架） | `karmada/federated-query/`（Java/Spring Boot） |
| Docker 多集群模拟环境 | ✅ | `karmada/docker/`（docker-compose） |

### 6.2 待办（对应 [PROJECT-ROADMAP-DETAILED.md](PROJECT-ROADMAP-DETAILED.md) 长期架构方向）

| 待办 | 优先级 | 说明 |
| --- | --- | --- |
| karmada-api 接入真实 Karmada 控制面 | 高 | PropagationPolicy CRUD 当前仅落本地 SQLite，未对接 Karmada 控制面实际下发。**P-01 已添加 Karmada REST API 客户端骨架**（`internal/karmadaclient/client.go`），支持 `KARMADA_API_SERVER` + `KARMADA_KUBECONFIG` 环境变量配置，**待异地机房真实验证** |
| 联邦集群注册/注销 API | 高 | **P-01 已创建骨架**（`internal/handler/cluster.go`），端点：POST/GET/DELETE `/api/v1/clusters`，**待异地机房真实验证** |
| failover-engine 接入真实 Prometheus | 高 | 健康检查需真实 Prometheus 指标采集 |
| federated-query 接入真实多集群 | 高 | 跨集群查询路由需真实多集群环境验证 |
| 四环境联邦验证 | 中 | 信创/本地/公有云/私有云四环境联邦部署验证 |
| 跨集群事务支持 | 中 | 基于 Iceberg Snapshot Isolation 的跨集群 ACID 事务 |
| 联邦治理统一视图 | 中 | 跨集群元数据 / 血缘 / 质量规则统一治理 |

### 6.3 多集群联邦验证状态（P-01）

> **最后更新**：2026-09-11

| 验证项 | 状态 | 说明 |
|--------|------|------|
| Karmada REST API 客户端骨架 | ✅ 已创建 | `internal/karmadaclient/client.go`，支持环境变量配置 |
| 联邦集群注册/注销 API 骨架 | ✅ 已创建 | `internal/handler/cluster.go`，POST/GET/DELETE `/api/v1/clusters` |
| 环境变量配置 | ✅ 已添加 | `KARMADA_API_SERVER`、`KARMADA_KUBECONFIG`、`KARMADA_API_TIMEOUT` |
| 本地单集群验证 | ⏳ 待验证 | 需本地 Karmada 控制面环境 |
| 异地机房多集群验证 | ⏳ 待验证 | **待异地机房真实验证**（需真实多机房环境） |
| 四环境联邦验证 | ⏳ 待验证 | 信创/本地/公有云/私有云四环境联邦部署 |

## 七、关联文档索引

| 文档 | 路径 | 关联内容 |
| --- | --- | --- |
| Karmada 控制面 README | [platform/karmada/README.md](../platform/karmada/README.md) | 控制面部署 + 成员集群纳管 |
| PropagationPolicy API | [platform/karmada/api/README.md](../platform/karmada/api/README.md) | 传播策略 CRUD API |
| 故障迁移 README | [platform/karmada/failover/README.md](../platform/karmada/failover/README.md) | OverridePolicy + Failover |
| 联邦查询 README | [platform/karmada/federated-query/README.md](../platform/karmada/federated-query/README.md) | 跨集群查询路由与归并 |
| 组件成熟度矩阵 | [component-maturity.md](component-maturity.md) | karmada-api / karmada-federated-query / karmada-failover 成熟度 |
| 项目路线图（详细） | [PROJECT-ROADMAP-DETAILED.md](PROJECT-ROADMAP-DETAILED.md) | 长期架构方向（多集群/边缘调度） |
| 路线图 | [../ROADMAP.md](../ROADMAP.md) | v2.0 多集群联邦 + v2.1 多集群联邦增强 |
| 部署指南 | [deployment-guide.md](deployment-guide.md) | Helm Chart 部署 |

## 八、变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-09-11 | P-01：添加 Karmada REST API 客户端骨架 + 联邦集群注册/注销 API 骨架 + 环境变量配置（KARMADA_API_SERVER/KARMADA_KUBECONFIG）+ 多集群联邦验证状态说明 |
| 2026-09-11 | 首次创建：Karmada 多集群联邦架构说明 + 使用指南 + 配置参数 + 验证方案 |