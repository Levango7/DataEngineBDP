# minio Helm Chart

> RustFS 对象存储 - S3 兼容私有存储 - 数据引擎大数据平台

## 1. 概述

`minio` 是数据引擎大数据平台（DataEngineBDP）的 Helm Chart，用于在 Kubernetes 集群上一键部署和管理对象存储组件。

- **Chart 版本**：`2.0.0`
- **App 版本**：`2.0.0`
- **默认命名空间**：`sq-engine`
- **实现说明（2026-11-22，台账 #17）**：本 chart 部署的实现已由 MinIO 社区版换成
  **RustFS 1.0.6**。MinIO 社区版镜像已于 2026-09-11 被上游整体删除（runner 实测
  `docker manifest inspect minio/minio:latest` 返回 rc=1 denied），上游仓
  2026-04-25 归档只读 ⇒ 原坐标在生产已不可拉取。chart 名、服务名（`minio`）与
  endpoint 保持不变（umbrella 以 `file://../minio` 依赖本 chart，业务侧多处引用
  该 Service 名），**只换实现与凭据口径**；服务名重命名另立工作项。
- **凭据口径**：容器内用 RustFS 规范名 `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY`，
  值从 Secret 注入（台账 #12：敏感值不进 ConfigMap）。

## 2. 前置条件

- Kubernetes 集群已就绪（版本 >= 1.28）
- Helm 3.14+ 已安装
- 目标命名空间已创建（或使用 `--create-namespace`）
- 所需 StorageClass / Secret 已按环境准备就绪

## 3. 快速开始

### 3.1 安装 Chart

```bash
# 安装到默认命名空间
helm install minio design/deploy/charts/minio -n sq-engine --create-namespace

# 使用自定义 values 覆盖
helm install minio design/deploy/charts/minio -n sq-engine \
  -f design/deploy/values/minio-values.yaml
```

### 3.2 升级

```bash
helm upgrade minio design/deploy/charts/minio -n sq-engine \
  -f design/deploy/values/minio-values.yaml
```

### 3.3 卸载

```bash
helm uninstall minio -n sq-engine
```

### 3.4 渲染模板（Dry Run）

```bash
helm template minio design/deploy/charts/minio -n sq-engine
```

## 4. 配置参数

下表列出 `minio` Chart 的主要配置参数，完整配置参见 `values.yaml`。

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `image.repository` | 镜像仓库地址（直连 Docker Hub，不加加速器前缀） | `rustfs/rustfs` |
| `image.tag` | 镜像标签 | `1.0.6` |
| `image.pullPolicy` | 镜像拉取策略 | `IfNotPresent` |
| `credentials.existingSecret` | 凭据 Secret 名（留空则不注入凭据，Pod 会用上游内置默认凭据启动，**生产必须设置**） | `""` |
| `credentials.accessKeyKey` | Secret 内 access key 的键名 | `access-key` |
| `credentials.secretKeyKey` | Secret 内 secret key 的键名 | `secret-key` |
| `persistence.enabled` | 是否挂 PVC（对象存储数据面；关闭时数据只落在容器可写层，重启即丢） | `false` |
| `persistence.size` | PVC 容量 | `100Gi` |
| `replicaCount` | 副本数 | `4` |
| `service.type` | Service 类型 | `ClusterIP` |
| `service.port` | Service 端口 | `9000` |
| `service.containerPort` | 容器端口 | `9000` |
| `resources.requests.cpu` | CPU 请求 | `1` |
| `resources.requests.memory` | 内存请求 | `2Gi` |
| `resources.limits.cpu` | CPU 上限 | `2` |
| `resources.limits.memory` | 内存上限 | `4Gi` |
| `securityContext.enabled` | 安全上下文开关 | `true` |
| `securityContext.runAsNonRoot` | 非 root 运行 | `true` |
| `securityContext.runAsUser` | 运行用户 UID（**必须 10001**：上游镜像用 uid/gid 10001 建 rustfs 用户并把 `/data` 置为 0750 rustfs:rustfs，换 uid 会失去写权限） | `10001` |
| `probes.liveness.enabled` | 存活探针开关 | `true` |
| `probes.readiness.enabled` | 就绪探针开关 | `true` |
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
# minio 自定义配置示例
image:
  repository: "rustfs/rustfs"
  tag: "1.0.6"
  pullPolicy: IfNotPresent

# 凭据从 Secret 注入（值不进 values 文件）
credentials:
  existingSecret: rustfs-creds
  accessKeyKey: "access-key"
  secretKeyKey: "secret-key"

# 生产必须开持久化，否则对象数据重启即丢
persistence:
  enabled: true
  size: 500Gi

replicaCount: 2

service:
  type: ClusterIP
  port: 9000
  containerPort: 9000

resources:
  requests:
    cpu: "1"
    memory: "2Gi"
  limits:
    cpu: "2"
    memory: "4Gi"

securityContext:
  enabled: true
  runAsNonRoot: true
  runAsUser: 10001

autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 5

ingress:
  enabled: true
  className: apisix
  hosts:
    - host: minio.shuqing.local
      paths:
        - path: /
          pathType: Prefix
```

## 6. 安全加固

本 Chart 默认启用以下安全配置（符合等保三级要求）：

- **非 root 运行**：`runAsNonRoot: true`，`runAsUser: 10001`（RustFS 上游镜像的
  `rustfs` 用户 uid/gid 即 10001，`/data` 与 `/logs` 目录属主也是 `rustfs:rustfs`
  且权限 0750 ⇒ 换成别的 uid 会失去数据目录写权限）
- **凭据走 Secret**：`RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY` 从
  `credentials.existingSecret` 注入，不进 ConfigMap 与 Pod spec 字面量
- **权限降级**：`allowPrivilegeEscalation: false`
- **能力裁剪**：`capabilities.drop: [ALL]`
- **只读根文件系统**：按组件特性配置

## 7. 运维操作

### 7.1 查看 Pod 状态

```bash
kubectl get pods -l "app.kubernetes.io/instance=minio" -n sq-engine
```

### 7.2 查看日志

```bash
kubectl logs -l "app.kubernetes.io/instance=minio" -n sq-engine -f
```

### 7.3 端口转发（本地调试）

```bash
kubectl port-forward svc/minio 9000:9000 -n sq-engine
```

### 7.4 获取当前配置

```bash
helm get values minio -n sq-engine
```

## 8. 升级与回滚

```bash
# 查看历史版本
helm history minio -n sq-engine

# 回滚到上一版本
helm rollback minio -n sq-engine
```

## 9. 维护信息

- **维护方**：DataEngineBDP Team
- **联系邮箱**：platform@shuqing.example.com
- **项目主页**：https://github.com/DataEngineBDP
- **Chart 版本**：2.0.0
- **App 版本**：2.0.0
- **关联配置**：`design/deploy/values/minio-values.yaml`
