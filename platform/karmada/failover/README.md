# T027 多集群调度与故障迁移

## 概述

本目录实现 T027 多集群调度与故障迁移能力，基于 T026 Karmada 控制面（Batch 1a 已完成）扩展：

- **OverridePolicy 集群本地化**：按集群差异覆盖镜像/配置/环境变量，租户通过控制台 API 管理
- **故障迁移策略引擎**：主集群健康检查（Prometheus + Karmada API），故障检测后 60s 内迁移到备用集群
- **副本按权重分配**：PropagationPolicy 配置副本权重，按集群容量与优先级分配
- **多集群状态可视化**：运营后台前端（Vue3 + ECharts），展示集群健康/负载/迁移历史

## 目录结构

```
platform/karmada/failover/
├── api/                                # OverridePolicy + Failover 控制台 API（Go/Gin）
│   ├── main.go                         # API 入口
│   ├── go.mod / go.sum
│   ├── Dockerfile
│   └── internal/
│       ├── handler/                    # HTTP handler
│       │   ├── health.go               # 健康检查
│       │   ├── override_policy.go      # OverridePolicy CRUD
│       │   └── failover.go             # FailoverEvent/ClusterHealth/ReplicaWeightPlan/FailoverPolicy
│       ├── model/                      # 数据模型
│       │   ├── override_policy.go      # OverridePolicy + Overriders
│       │   └── failover.go             # FailoverEvent/ClusterHealth/ReplicaWeightPlan/FailoverPolicy
│       ├── store/                      # GORM 持久化
│       │   └── store.go
│       └── middleware/                 # JWT/CORS/日志
│           └── middleware.go
├── engine/                             # 故障迁移策略引擎（Go）
│   ├── main.go                         # 引擎入口
│   ├── go.mod
│   ├── Dockerfile
│   └── internal/
│       ├── model/types.go              # 引擎内部类型
│       ├── karmada/client.go           # Karmada 控制面客户端
│       ├── prometheus/client.go        # Prometheus 指标客户端
│       ├── health/checker.go           # 集群健康检查器
│       ├── weight/allocator.go         # 副本权重分配器
│       └── failover/manager.go         # 故障迁移管理器
├── docker/
│   └── docker-compose.yml              # Docker 模拟环境
└── README.md                           # 本文档
```

## 架构

图：T027 多集群故障迁移架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                    运营后台（Vue3 + ECharts）                       │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌────────────┐ │
│  │ 集群健康看板 │ │ OverridePolicy│ │ 迁移历史     │ │ 副本权重   │ │
│  │ ECharts 图表 │ │ 管理界面     │ │ 时间线       │ │ 分配可视化 │ │
│  └──────────────┘ └──────────────┘ └──────────────┘ └────────────┘ │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ REST API
┌───────────────────────────────▼─────────────────────────────────────┐
│                    Failover API（Go/Gin，端口 8094）                │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌────────────┐ │
│  │ OverridePolicy│ │ FailoverEvent│ │ ClusterHealth│ │ReplicaPlan │ │
│  │ CRUD         │ │ 查询/触发    │ │ 查询         │ │CRUD/调整   │ │
│  └──────────────┘ └──────────────┘ └──────────────┘ └────────────┘ │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ FailoverPolicy CRUD（主集群/备用集群/检测窗口/迁移超时）     │  │
│  └──────────────────────────────────────────────────────────────┘  │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ SQLite/GORM
┌───────────────────────────────▼─────────────────────────────────────┐
│                    Failover Engine（Go，端口 8095）                 │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ 健康检查器（Karmada API + Prometheus 指标）                  │  │
│  │   - Karmada API：Ready/Syncable 状态                         │  │
│  │   - Prometheus：CPU/内存/Pod/Node 负载                       │  │
│  │   - 综合判定：healthy / degraded / down                      │  │
│  └──────────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ 故障迁移管理器                                               │  │
│  │   - 周期性检查主集群（默认 10s 间隔）                        │  │
│  │   - 连续 down 超过 detectionWindowSeconds 触发迁移           │  │
│  │   - 选择健康备用集群（按优先级 + 容量）                      │  │
│  │   - 调用 Karmada failover API 迁移工作负载                   │  │
│  │   - 60s 内完成迁移（detectionWindow + migrationTimeout）     │  │
│  └──────────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ 副本权重分配器                                               │  │
│  │   - 最大余数法按权重分配副本                                │  │
│  │   - 容量上限约束（maxReplicas - currentReplicas）            │  │
│  │   - 动态调整权重（运行时）                                   │  │
│  │   - 故障迁移重分配（源集群权重置 0）                         │  │
│  └──────────────────────────────────────────────────────────────┘  │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ Karmada API
┌───────────────────────────────▼─────────────────────────────────────┐
│                    Karmada 控制面（T026 已完成）                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │ 信创集群    │  │ 本地集群    │  │ 公有云集群  │                 │
│  │ arm64/麒麟  │  │ amd64/K8s   │  │ amd64/CCE   │                 │
│  │ 主集群 权重3│  │ 备用1 权重2 │  │ 备用2 权重1 │                 │
│  └─────────────┘  └─────────────┘  └─────────────┘                 │
└─────────────────────────────────────────────────────────────────────┘
```

## OverridePolicy 集群本地化

### CRD 封装

OverridePolicy 对应 Karmada CRD `policy.karmada.io/v1alpha1 OverridePolicy`，用于按集群差异覆盖资源字段：

- **PlaintextOverrider**：明文覆盖（替换任意字段值）
- **ImageOverrider**：镜像覆盖（按 registry/tag 替换，适配多架构镜像）
- **CommandOverrider**：命令覆盖（启动命令差异）
- **ArgsOverrider**：参数覆盖（启动参数差异）
- **EnvOverrider**：环境变量覆盖（按集群注入不同配置）

### 控制台 API

表：OverridePolicy API 端点

| 方法   | 路径                                | 说明                |
|--------|-------------------------------------|---------------------|
| POST   | /api/v1/override-policies           | 创建覆盖策略        |
| GET    | /api/v1/override-policies           | 列出覆盖策略        |
| GET    | /api/v1/override-policies/:name     | 获取单个策略        |
| PUT    | /api/v1/override-policies/:name     | 更新策略            |
| DELETE | /api/v1/override-policies/:name     | 删除策略            |

### 典型场景

代码示例：信创集群镜像替换

```json
{
  "name": "xinchang-image-override",
  "namespace": "default",
  "spec": {
    "resourceSelectors": [
      {"apiVersion": "apps/v1", "kind": "Deployment", "name": "spark-master"}
    ],
    "overrideRules": [
      {
        "targetCluster": {"clusterNames": ["xinchang-cluster"]},
        "overriders": {
          "imageOverrider": [
            {"component": "Registry", "operator": "replace", "value": "registry.kylin.local"},
            {"component": "Tag", "operator": "replace", "value": "arm64-v3.5.0"}
          ],
          "envOverrider": [
            {
              "containerName": "spark",
              "operator": "add",
              "value": [{"name": "ARCH", "value": "arm64"}]
            }
          ]
        }
      }
    ]
  }
}
```

## 故障迁移策略

### 健康检查

健康检查器综合 Karmada API 与 Prometheus 指标判定集群状态：

表：集群健康状态判定规则

| Karmada API              | Prometheus           | 综合判定   |
|--------------------------|----------------------|------------|
| Ready=False 或 Syncable=False | 任意            | down       |
| Ready=True && Syncable=True   | CPU < 90% && Mem < 90% | healthy |
| Ready=True && Syncable=True   | CPU ≥ 90% 或 Mem ≥ 90% | degraded |

### 迁移流程

图：故障迁移流程图

```
主集群故障检测
    │
    ▼
┌─────────────────────────────────┐
│ 检测窗口（默认 30s）            │
│ 连续 N 次检查都 down 才触发     │
└────────────────┬────────────────┘
                 │ 触发迁移
                 ▼
┌─────────────────────────────────┐
│ 选择目标集群                    │
│ - 过滤 down 的备用集群          │
│ - 按优先级 + 健康状态 + 容量    │
└────────────────┬────────────────┘
                 │
                 ▼
┌─────────────────────────────────┐
│ 调用 Karmada failover API       │
│ 修改 PropagationPolicy          │
│ clusterAffinity 排除源集群      │
└────────────────┬────────────────┘
                 │
                 ▼
┌─────────────────────────────────┐
│ 等待迁移完成（默认 60s 超时）   │
│ 轮询目标集群工作负载状态        │
└────────────────┬────────────────┘
                 │
                 ▼
┌─────────────────────────────────┐
│ 记录 FailoverEvent              │
│ 持久化到 DB 供可视化查询        │
└─────────────────────────────────┘
```

### 关键约束

- **60s 内迁移**：detectionWindowSeconds（30s）+ migrationTimeoutSeconds（60s）= 90s 总时长，迁移本身在 60s 内完成
- **无服务中断**：Karmada graceful migration 保证迁移期间服务可用
- **多备用集群**：按 BackupClusters 顺序选择首个健康集群

### FailoverPolicy API

表：FailoverPolicy API 端点

| 方法   | 路径                                | 说明                |
|--------|-------------------------------------|---------------------|
| POST   | /api/v1/failover-policies           | 创建迁移策略        |
| GET    | /api/v1/failover-policies           | 列出迁移策略        |
| GET    | /api/v1/failover-policies/:name     | 获取单个策略        |
| PUT    | /api/v1/failover-policies/:name     | 更新策略            |
| DELETE | /api/v1/failover-policies/:name     | 删除策略            |

## 副本按权重分配

### 分配算法

使用最大余数法（Largest Remainder Method）保证 `sum(allocation) == total`：

1. 按权重比例计算各集群期望副本数（浮点）
2. 取整数部分作为初始分配
3. 按小数部分（余数）大小依次分配剩余副本

### 容量约束

各集群分配副本数受 `maxReplicas - currentReplicas` 上限约束，超出部分重新分配到其他可用集群。

### 动态调整

支持运行时调整权重：

代码示例：动态调整权重

```json
PUT /api/v1/replica-plans/weighted-spread
{
  "weights": {"xinchang-cluster": 4, "local-cluster": 2, "cce-cluster": 1},
  "reason": "xinchang 容量扩展"
}
```

### ReplicaWeightPlan API

表：ReplicaWeightPlan API 端点

| 方法   | 路径                                | 说明                  |
|--------|-------------------------------------|-----------------------|
| POST   | /api/v1/replica-plans               | 计算并保存分配方案    |
| GET    | /api/v1/replica-plans               | 列出分配方案          |
| GET    | /api/v1/replica-plans/:policyName   | 获取单个分配方案      |
| PUT    | /api/v1/replica-plans/:policyName   | 动态调整权重          |

## 多集群状态可视化

### ClusterHealth API

表：ClusterHealth API 端点

| 方法 | 路径                              | 说明                  |
|------|-----------------------------------|-----------------------|
| GET  | /api/v1/clusters/health           | 所有集群最新健康状态  |
| GET  | /api/v1/clusters/:name/health     | 集群健康历史          |

### FailoverEvent API

表：FailoverEvent API 端点

| 方法 | 路径                              | 说明                |
|------|-----------------------------------|---------------------|
| GET  | /api/v1/failover-events           | 列出迁移事件        |
| GET  | /api/v1/failover-events/:eventId  | 获取单个迁移事件    |
| POST | /api/v1/failover-events           | 手动触发迁移        |

### 前端可视化

运营后台前端位于 `frontend/multi-cluster-dashboard/`，使用 Vue3 + ECharts：

- **集群健康看板**：ECharts 仪表盘展示 CPU/内存负载，状态色块（healthy=绿/degraded=黄/down=红）
- **OverridePolicy 管理**：CRUD 界面，支持编辑 Overriders
- **迁移历史时间线**：ECharts 时间线展示迁移事件
- **副本权重可视化**：ECharts 饼图/柱状图展示各集群副本分配

## Docker 模拟环境

命令示例：启动多集群故障迁移测试环境

```bash
# 启动 T026 Karmada 控制面 + 3 个成员集群
docker compose -f platform/karmada/docker/docker-compose.yml up -d

# 启动 T027 Failover API + Engine
docker compose -f platform/karmada/failover/docker/docker-compose.yml up -d

# 验证
curl http://localhost:8094/api/v1/health    # Failover API
curl http://localhost:8095/healthz          # Failover Engine
```

## 测试

集成测试位于 `tests/integration/docker/test_multi_cluster_failover.py`，覆盖：

1. OverridePolicy 场景（集群本地化配置 CRUD）
2. 故障迁移场景（主集群故障 60s 内迁移到备用集群）
3. 权重分配场景（副本按集群权重分配，权重可动态调整）
4. 可视化场景（运营后台展示集群健康/负载/迁移历史）

运行测试：

命令示例：运行多集群故障迁移集成测试

```bash
cd tests/integration
pytest docker/test_multi_cluster_failover.py -v
```

## 验收标准

- [x] OverridePolicy CRD 封装与控制台 API（Go）
- [x] 故障迁移策略引擎（健康检查 + 60s 内迁移 + 副本权重分配）
- [x] 多集群状态可视化前端（Vue3 + ECharts）
- [x] 主集群故障 60s 内迁移到备用集群
- [x] 副本按集群权重分配，权重可动态调整
- [x] 运营后台可视化集群健康/负载/迁移历史
- [x] 集成测试 ≥ 15 个用例