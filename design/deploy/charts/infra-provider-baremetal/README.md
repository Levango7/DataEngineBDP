# infra-provider-baremetal Helm Chart

> 本地 DC 供应 - 裸金属资源供应 - 数据引擎大数据平台

## 1. 概述

`infra-provider-baremetal` 是数据引擎大数据平台（DataEngineBDP）的 Helm Chart，用于在 Kubernetes 集群上一键部署和管理 `infra-provider-baremetal` 组件。

- **Chart 版本**：`2.0.0`
- **App 版本**：`2.0.0`
- **默认命名空间**：`sq-system`

## 2. 前置条件

- Kubernetes 集群已就绪（版本 >= 1.28）
- Helm 3.14+ 已安装
- 目标命名空间已创建（或使用 `--create-namespace`）
- 所需 StorageClass / Secret 已按环境准备就绪

## 3. 快速开始

### 3.1 安装 Chart

```bash
# 安装到默认命名空间
helm install infra-provider-baremetal design/deploy/charts/infra-provider-baremetal -n sq-system --create-namespace

# 使用自定义 values 覆盖
helm install infra-provider-baremetal design/deploy/charts/infra-provider-baremetal -n sq-system \
  -f design/deploy/values/infra-provider-baremetal-values.yaml
```

### 3.2 升级

```bash
helm upgrade infra-provider-baremetal design/deploy/charts/infra-provider-baremetal -n sq-system \
  -f design/deploy/values/infra-provider-baremetal-values.yaml
```

### 3.3 卸载

```bash
helm uninstall infra-provider-baremetal -n sq-system
```

### 3.4 渲染模板（Dry Run）

```bash
helm template infra-provider-baremetal design/deploy/charts/infra-provider-baremetal -n sq-system
```

## 4. 配置参数

下表列出 `infra-provider-baremetal` Chart 的主要配置参数，完整配置参见 `values.yaml`。

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `image.repository` | 镜像仓库地址 | `docker.m.daocloud.io/sq-infra-provider-baremetal` |
| `image.tag` | 镜像标签 | `0.1.0` |
| `image.pullPolicy` | 镜像拉取策略 | `IfNotPresent` |
| `replicaCount` | 副本数 | `1` |
| `service.type` | Service 类型 | `ClusterIP` |
| `service.port` | Service 端口 | `8080` |
| `service.containerPort` | 容器端口 | `8080` |
| `resources.requests.cpu` | CPU 请求 | `500m` |
| `resources.requests.memory` | 内存请求 | `512Mi` |
| `resources.limits.cpu` | CPU 上限 | `1000m` |
| `resources.limits.memory` | 内存上限 | `1Gi` |
| `securityContext.enabled` | 安全上下文开关 | `true` |
| `securityContext.runAsNonRoot` | 非 root 运行 | `true` |
| `securityContext.runAsUser` | 运行用户 UID | `1000` |
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
# infra-provider-baremetal 自定义配置示例
image:
  repository: "docker.m.daocloud.io/sq-infra-provider-baremetal"
  tag: "0.1.0"
  pullPolicy: IfNotPresent

replicaCount: 2

service:
  type: ClusterIP
  port: 8080
  containerPort: 8080

resources:
  requests:
    cpu: "500m"
    memory: "512Mi"
  limits:
    cpu: "1000m"
    memory: "1Gi"

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
    - host: infra-provider-baremetal.shuqing.local
      paths:
        - path: /
          pathType: Prefix
```

## 6. 安全加固

本 Chart 默认启用以下安全配置（符合等保三级要求）：

- **非 root 运行**：`runAsNonRoot: true`，`runAsUser: 1000`
- **权限降级**：`allowPrivilegeEscalation: false`
- **能力裁剪**：`capabilities.drop: [ALL]`
- **只读根文件系统**：按组件特性配置

## 7. 运维操作

### 7.1 查看 Pod 状态

```bash
kubectl get pods -l "app.kubernetes.io/instance=infra-provider-baremetal" -n sq-system
```

### 7.2 查看日志

```bash
kubectl logs -l "app.kubernetes.io/instance=infra-provider-baremetal" -n sq-system -f
```

### 7.3 端口转发（本地调试）

```bash
kubectl port-forward svc/infra-provider-baremetal 8080:8080 -n sq-system
```

### 7.4 获取当前配置

```bash
helm get values infra-provider-baremetal -n sq-system
```

## 8. 升级与回滚

```bash
# 查看历史版本
helm history infra-provider-baremetal -n sq-system

# 回滚到上一版本
helm rollback infra-provider-baremetal -n sq-system
```

## 9. 维护信息

- **维护方**：DataEngineBDP Team
- **联系邮箱**：platform@shuqing.example.com
- **项目主页**：https://github.com/DataEngineBDP
- **Chart 版本**：2.0.0
- **App 版本**：2.0.0
- **关联配置**：`design/deploy/values/infra-provider-baremetal-values.yaml`
