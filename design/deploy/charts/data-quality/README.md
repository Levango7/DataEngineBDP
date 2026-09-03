# data-quality Helm Chart

> 数据质量与批处理服务（batch-pipeline 五阶段流水线提交/查询 API）- 数据引擎大数据平台

## 1. 概述

`data-quality` 是数据引擎大数据平台（DataEngineBDP）的 Helm Chart，用于在 Kubernetes 集群上一键部署和管理 `data-quality` 组件（实体为 `platform/batch-pipeline`：ingest→validate→clean→compute→output 五阶段批处理流水线的 FastAPI 提交/查询壳，多租户按 JWT claim / X-Tenant-Id 隔离）。

- **Chart 版本**：`2.0.0`
- **App 版本**：`2.0.0`
- **默认命名空间**：`sq-governance`

## 2. 前置条件

- Kubernetes 集群已就绪（版本 >= 1.28）
- Helm 3.14+ 已安装
- 目标命名空间已创建（或使用 `--create-namespace`）
- 所需 StorageClass / Secret 已按环境准备就绪

## 3. 快速开始

### 3.1 安装 Chart

```bash
# 安装到默认命名空间
helm install data-quality design/deploy/charts/data-quality -n sq-governance --create-namespace

# 使用自定义 values 覆盖
helm install data-quality design/deploy/charts/data-quality -n sq-governance \
  -f design/deploy/values/data-quality-values.yaml
```

### 3.2 升级

```bash
helm upgrade data-quality design/deploy/charts/data-quality -n sq-governance \
  -f design/deploy/values/data-quality-values.yaml
```

### 3.3 卸载

```bash
helm uninstall data-quality -n sq-governance
```

### 3.4 渲染模板（Dry Run）

```bash
helm template data-quality design/deploy/charts/data-quality -n sq-governance
```

## 4. 配置参数

下表列出 `data-quality` Chart 的主要配置参数，完整配置参见 `values.yaml`。

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `image.repository` | 镜像仓库地址 | `docker.m.daocloud.io/sq-data-quality` |
| `image.tag` | 镜像标签 | `2.0.0` |
| `image.pullPolicy` | 镜像拉取策略 | `IfNotPresent` |
| `command` / `args` | 容器启动命令（覆盖镜像默认批处理 CLI，改为 API 模式） | `["python"]` / `["-m", "batch_pipeline.api.main"]` |
| `replicaCount` | 副本数 | `1` |
| `service.type` | Service 类型 | `ClusterIP` |
| `service.port` | Service 端口 | `8080` |
| `service.containerPort` | 容器端口 | `8080` |
| `env.AUTH_MODE` | 鉴权模式（生产必须 `jwt`，K8s 内缺省会 fail-fast） | `jwt` |
| `env.RUN_ROOT` | 批次运行根目录（租户分区 run/\<tenant\>/\<batch\>/） | `/app/run` |
| `secretEnv.JWT_SECRET` | HS256 鉴权密钥（Secret keyRef） | `data-quality-auth/jwt-secret` |
| `extraVolumes` / `extraVolumeMounts` | 可写卷（readOnlyRootFilesystem 下 run/state/tmp 外挂） | emptyDir × 3 |
| `resources.requests.cpu` | CPU 请求 | `500m` |
| `resources.requests.memory` | 内存请求 | `1Gi` |
| `resources.limits.cpu` | CPU 上限 | `1000m` |
| `resources.limits.memory` | 内存上限 | `2Gi` |
| `securityContext.enabled` | 安全上下文开关 | `true` |
| `securityContext.runAsNonRoot` | 非 root 运行 | `true` |
| `securityContext.runAsUser` | 运行用户 UID | `1000` |
| `probes.liveness.enabled` | 存活探针开关（`/api/v1/healthz`，匿名可达） | `true` |
| `probes.readiness.enabled` | 就绪探针开关（`/api/v1/readyz`） | `true` |
| `autoscaling.enabled` | HPA 开关 | `false` |
| `autoscaling.minReplicas` | HPA 最小副本 | `1` |
| `autoscaling.maxReplicas` | HPA 最大副本 | `3` |
| `podDisruptionBudget.enabled` | PDB 开关 | `false` |
| `ingress.enabled` | Ingress 开关 | `false` |
| `ingress.className` | Ingress 控制器 | `apisix` |
| `nodeSelector` | 节点选择器 | `{}` |
| `tolerations` | 污点容忍 | `[]` |
| `affinity` | 亲和性 | `{}` |

## 5. 示例 values.yaml

```yaml
# data-quality 自定义配置示例
image:
  repository: "docker.m.daocloud.io/sq-data-quality"
  tag: "2.0.0"
  pullPolicy: IfNotPresent

# API 服务模式启动命令
command: ["python"]
args: ["-m", "batch_pipeline.api.main"]

replicaCount: 2

service:
  type: ClusterIP
  port: 8080
  containerPort: 8080

resources:
  requests:
    cpu: "500m"
    memory: "1Gi"
  limits:
    cpu: "1000m"
    memory: "2Gi"

env:
  LOG_LEVEL: info
  AUTH_MODE: jwt
  RUN_ROOT: /app/run
  PIPELINE_CONFIG: /app/config/pipeline.json

secretEnv:
  JWT_SECRET:
    name: data-quality-auth
    key: jwt-secret

securityContext:
  enabled: true
  runAsNonRoot: true
  runAsUser: 1000

autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 5

ingress:
  enabled: true
  className: apisix
  hosts:
    - host: data-quality.shuqing.local
      paths:
        - path: /
          pathType: Prefix
```

## 6. 安全加固

本 Chart 默认启用以下安全配置（符合等保三级要求）：

- **非 root 运行**：`runAsNonRoot: true`，`runAsUser: 1000`
- **权限降级**：`allowPrivilegeEscalation: false`
- **能力裁剪**：`capabilities.drop: [ALL]`
- **只读根文件系统**：`readOnlyRootFilesystem: true`，批次运行目录（/app/run）、增量状态目录（/app/state）与 /tmp 经 `extraVolumes` 外挂 emptyDir 可写
- **强制鉴权**：`AUTH_MODE=jwt` + Secret 注入 `JWT_SECRET`（部署前创建）：

```bash
kubectl create secret generic data-quality-auth \
  --from-literal=jwt-secret=<HS256 密钥> -n sq-governance
```

- **租户隔离**：批次按 JWT claim / X-Tenant-Id 解析租户并强制分区到 `run/<tenant>/<batch>/`；请求体中的 tenant / storage / run_dir / state_dir 覆盖项由服务端剔除，不可逃逸
- **网络微隔离**：`networkPolicy.enabled: true`（仅同命名空间可访问服务端口）

## 7. 运维操作

### 7.1 查看 Pod 状态

```bash
kubectl get pods -l "app.kubernetes.io/instance=data-quality" -n sq-governance
```

### 7.2 查看日志

```bash
kubectl logs -l "app.kubernetes.io/instance=data-quality" -n sq-governance -f
```

### 7.3 端口转发（本地调试）

```bash
kubectl port-forward svc/data-quality 8080:8080 -n sq-governance
```

### 7.4 获取当前配置

```bash
helm get values data-quality -n sq-governance
```

## 8. 升级与回滚

```bash
# 查看历史版本
helm history data-quality -n sq-governance

# 回滚到上一版本
helm rollback data-quality -n sq-governance
```

## 9. 维护信息

- **维护方**：DataEngineBDP Team
- **联系邮箱**：platform@shuqing.example.com
- **项目主页**：https://github.com/DataEngineBDP
- **Chart 版本**：2.0.0
- **App 版本**：2.0.0
- **关联配置**：`design/deploy/values/data-quality-values.yaml`
