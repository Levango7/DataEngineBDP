# DataEngineBDP Umbrella Chart

> DataEngineBDP 大数据平台统一部署 Chart（Helm Umbrella），一键编排全部自研组件与第三方大数据引擎。

## 目录

- [设计目标](#设计目标)
- [Chart 结构](#chart-结构)
- [子 Chart 清单](#子-chart-清单)
- [前置条件](#前置条件)
- [快速开始](#快速开始)
- [多环境部署](#多环境部署)
- [按需启用子组件](#按需启用子组件)
- [依赖更新](#依赖更新)
- [渲染校验](#渲染校验)
- [卸载](#卸载)
- [常见问题](#常见问题)

---

## 设计目标

DataEngineBDP 平台包含 80+ 组件，涵盖自研核心服务、治理套件、AI/智能组件、大数据引擎、调度编排、可观测性、基础设施与开发工具。本 Umbrella Chart 提供：

1. **统一入口**：一条 `helm install` 命令编排全部组件，免去逐个部署。
2. **按需启用**：每个子 Chart 通过 `<name>.enabled` 开关控制是否渲染，默认仅启用自研核心与治理组件。
3. **环境隔离**：通过 `-f design/deploy/values/env/<env>/<name>-values.yaml` 覆盖各环境配置（dev/staging/prod）。
4. **配置分层**：Chart 骨架默认值 → 环境覆盖值 → 命令行 `--set`，逐层覆盖。

## Chart 结构

```
design/deploy/charts/dataenginebdp-umbrella/
├── Chart.yaml            # Chart 元信息 + dependencies 依赖声明（Helm v3）
├── requirements.yaml     # 依赖清单（Helm v2 兼容，与 Chart.yaml.dependencies 一致）
├── values.yaml           # 全局配置 + 各子 Chart enabled 开关
├── README.md             # 本文档
└── templates/            # Umbrella 自身模板（当前为空，资源由子 Chart 渲染）
```

各子 Chart 位于同级目录：

```
design/deploy/charts/
├── dataenginebdp-umbrella/   # 本 Umbrella Chart
├── encaps-layer/             # 自研核心组件
├── sql-gateway/
├── catalog/
├── rule-engine/
├── spark/                    # 第三方大数据引擎
├── flink/
└── ...                       # 其余 80+ 组件
```

## 子 Chart 清单

本 Umbrella 编排 83 个子 Chart，按类别分组如下：

| 类别 | 默认状态 | 组件 |
|------|----------|------|
| 自研核心组件 | **启用** | encaps-layer, sql-gateway, catalog, rule-engine |
| 自研治理组件 | **启用** | governance, metadata-collector, lineage-analyzer, data-quality, asset-catalog |
| 自研智能/AI组件 | 禁用 | ai-assistant, knowledge-engine, llm-gateway, llmops, ml-platform, nl2sql, tag-engine, vector-engine |
| 大数据引擎 | 禁用 | spark, flink, trino, doris, kafka, zookeeper, hive-metastore, iceberg-rest, seatunnel, flink-cdc, iotdb, elasticsearch, redis, postgresql, minio, nebula-graph, milvus |
| 调度编排 | 禁用 | airflow, dolphinscheduler, stream-batch-scheduler |
| 可视化与监控 | 禁用 | superset, grafana, prometheus, loki, tempo, observability |
| 基础设施 | 禁用 | ske-infra, cni-cilium, csi-juicefs, csi-ceph, metallb, ingress-nginx, apisix, cert-manager, external-secrets, cluster-autoscaler, keda, descheduler, node-problem-detector, metrics-server, velero, karmada, knative-serving, argo-rollouts, reloader |
| 开发工具 | 禁用 | jupyter, vscode-server, theia, mlflow, model-finetuning, chunker |
| 业务与平台组件 | 禁用 | business-portal, asset-exchange, finance-template, industry-templates, finops, open-api-catalog, registry, storage-io, infra-orchestrator, infra-provider-baremetal, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, dqctl, keycloak |

> **说明**：默认仅启用自研核心 + 治理组件（9 个），保证最小可用部署。大数据引擎、基础设施等按需启用。

## 前置条件

1. **Helm** >= 3.8（推荐 3.12+）
2. **Kubernetes** >= 1.24
3. 子 Chart 已就位于 `design/deploy/charts/` 同级目录
4. 目标集群已就绪，且已创建所需 namespace（如 `sq-system`、`sq-engine`、`sq-governance`）

## 快速开始

### 1. 更新依赖

将所有子 Chart 拉取到 `charts/` 子目录（本地 `file://` 依赖会建立符号链接或拷贝）：

```bash
helm dependency update design/deploy/charts/dataenginebdp-umbrella
```

### 2. 渲染预览（不实际部署）

```bash
helm template dataenginebdp design/deploy/charts/dataenginebdp-umbrella \
  --namespace sq-system
```

### 3. 安装（默认仅启用自研核心+治理组件）

```bash
helm install dataenginebdp design/deploy/charts/dataenginebdp-umbrella \
  --namespace sq-system \
  --create-namespace
```

### 4. 查看部署状态

```bash
helm list -n sq-system
kubectl get all -n sq-system
```

## 多环境部署

各环境配置文件位于 `design/deploy/values/env/<env>/`，通过 `-f` 多次覆盖：

### dev 环境（最小资源、单副本、H2 内存库）

```bash
helm install dataenginebdp design/deploy/charts/dataenginebdp-umbrella \
  --namespace sq-system \
  --create-namespace \
  -f design/deploy/charts/dataenginebdp-umbrella/values.yaml \
  -f design/deploy/values/env/dev/encaps-layer-values.yaml \
  -f design/deploy/values/env/dev/sql-gateway-values.yaml \
  -f design/deploy/values/env/dev/catalog-values.yaml \
  -f design/deploy/values/env/dev/rule-engine-values.yaml
```

### staging 环境（中等资源、双副本、PostgreSQL、PDB）

```bash
helm upgrade dataenginebdp design/deploy/charts/dataenginebdp-umbrella \
  --namespace sq-system \
  -f design/deploy/charts/dataenginebdp-umbrella/values.yaml \
  -f design/deploy/values/env/staging/encaps-layer-values.yaml \
  -f design/deploy/values/env/staging/sql-gateway-values.yaml \
  -f design/deploy/values/env/staging/catalog-values.yaml \
  -f design/deploy/values/env/staging/rule-engine-values.yaml
```

### prod 环境（充足资源、3副本、PostgreSQL+PVC+HPA+PDB+TLS）

```bash
helm upgrade dataenginebdp design/deploy/charts/dataenginebdp-umbrella \
  --namespace sq-system \
  -f design/deploy/charts/dataenginebdp-umbrella/values.yaml \
  -f design/deploy/values/env/prod/encaps-layer-values.yaml \
  -f design/deploy/values/env/prod/sql-gateway-values.yaml \
  -f design/deploy/values/env/prod/catalog-values.yaml \
  -f design/deploy/values/env/prod/rule-engine-values.yaml
```

> **提示**：可将多个 `-f` 合并为一个环境聚合 values 文件，或编写部署脚本批量加载。

## 按需启用子组件

### 启用大数据引擎（Spark + Flink + Trino + Doris）

```bash
helm upgrade dataenginebdp design/deploy/charts/dataenginebdp-umbrella \
  --namespace sq-system \
  --set spark.enabled=true \
  --set flink.enabled=true \
  --set trino.enabled=true \
  --set doris.enabled=true
```

### 启用可观测性套件（Prometheus + Grafana + Loki + Tempo）

```bash
helm upgrade dataenginebdp design/deploy/charts/dataenginebdp-umbrella \
  --namespace sq-system \
  --set prometheus.enabled=true \
  --set grafana.enabled=true \
  --set loki.enabled=true \
  --set tempo.enabled=true
```

### 仅禁用某个自研核心组件

```bash
helm upgrade dataenginebdp design/deploy/charts/dataenginebdp-umbrella \
  --namespace sq-system \
  --set rule-engine.enabled=false
```

### 通过 values 文件批量启用

创建 `my-overrides.yaml`：

```yaml
spark:
  enabled: true
flink:
  enabled: true
trino:
  enabled: true
kafka:
  enabled: true
zookeeper:
  enabled: true  # Kafka 依赖
```

然后：

```bash
helm upgrade dataenginebdp design/deploy/charts/dataenginebdp-umbrella \
  --namespace sq-system \
  -f my-overrides.yaml
```

## 依赖更新

当子 Chart 列表或版本变化时，重新更新依赖：

```bash
helm dependency update design/deploy/charts/dataenginebdp-umbrella
```

该命令会读取 `Chart.yaml.dependencies`，将 `file://../<name>` 指向的子 Chart 打包到 `charts/` 子目录。

> **注意**：Helm v3 使用 `Chart.yaml.dependencies`，不再读取 `requirements.yaml`。本 Chart 两者保持一致以兼容 Helm v2。

## 渲染校验

部署前建议先渲染校验，确认输出符合预期：

```bash
# 渲染全部已启用组件
helm template dataenginebdp design/deploy/charts/dataenginebdp-umbrella \
  --namespace sq-system \
  > /tmp/dataenginebdp-rendered.yaml

# 校验 YAML 语法
kubectl apply --dry-run=client -f /tmp/dataenginebdp-rendered.yaml

# 检查特定资源
grep -A 20 "kind: HorizontalPodAutoscaler" /tmp/dataenginebdp-rendered.yaml
```

## 卸载

```bash
# 卸载 release（保留 PVC）
helm uninstall dataenginebdp -n sq-system

# 如需彻底清理 PVC（谨慎！数据将丢失）
kubectl delete pvc -n sq-system --all
```

## 常见问题

### Q1: `helm dependency update` 报错 "found in Chart.yaml, but missing in charts/"

子 Chart 路径不正确。确认 `repository: "file://../<name>"` 指向的目录存在且包含有效 `Chart.yaml`。

### Q2: 某子 Chart 未渲染

检查该子 Chart 的 `enabled` 开关：

```bash
helm get values dataenginebdp -n sq-system | grep -A1 "<name>"
```

确认 `enabled: true`。默认禁用的组件需通过 `--set <name>.enabled=true` 或 values 文件启用。

### Q3: HPA/PDB/Ingress 未生成

各组件的 `hpa.enabled`、`pdb.enabled`、`ingress.enabled` 默认为 `false`。在环境 values 文件中设置为 `true` 才会渲染。例如 prod 环境已启用 HPA+PDB+TLS。

### Q4: 如何只部署单个组件？

直接使用子 Chart，无需 Umbrella：

```bash
helm install encaps-layer design/deploy/charts/encaps-layer \
  --namespace sq-system \
  -f design/deploy/values/env/prod/encaps-layer-values.yaml
```

### Q5: 字段名 hpa/pdb 与 autoscaling/podDisruptionBudget 的关系？

自研组件（encaps-layer, sql-gateway, catalog, rule-engine）的 HPA/PDB 模板使用 `values.hpa` / `values.pdb` 字段名，对应 `templates/hpa.yaml` / `templates/pdb.yaml`。环境 values 文件已统一使用 `hpa` / `pdb`。

---

## 维护信息

- **维护方**：DataEngineBDP Team
- **关联设计**：`design/详细设计/多平台多租户大数据平台_部署清单详细设计_v0.1.md`
- **版本**：Chart `0.1.0` / App `2.0.0`