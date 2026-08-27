# namespace-security Helm Chart

命名空间级安全策略 Chart，统一管理 Kubernetes 命名空间的 **ResourceQuota** 与 **LimitRange**，满足等保三级资源管控要求。

## 用途

- **ResourceQuota**：在命名空间维度限制 CPU/内存/Pod/PVC/Service 等资源总量上限，防止单租户挤占集群资源。
- **LimitRange**：为未显式声明资源请求/限制的容器自动注入默认值，并约束容器资源上下限。

## 部署命令

```bash
# 安装到目标命名空间（命名空间需预先存在）
helm install namespace-security design/deploy/charts/namespace-security -n <target-namespace>

# 渲染预览
helm template ns-security design/deploy/charts/namespace-security

# 禁用 LimitRange 仅启用 ResourceQuota
helm install namespace-security design/deploy/charts/namespace-security \
  -n <target-namespace> \
  --set limitRange.enabled=false
```

## 参数说明

### ResourceQuota

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `resourceQuota.enabled` | 是否启用 ResourceQuota | `true` |
| `resourceQuota.hard.requests.cpu` | 命名空间 CPU 请求总量上限 | `"10"` |
| `resourceQuota.hard.requests.memory` | 命名空间内存请求总量上限 | `"20Gi"` |
| `resourceQuota.hard.limits.cpu` | 命名空间 CPU 限制总量上限 | `"20"` |
| `resourceQuota.hard.limits.memory` | 命名空间内存限制总量上限 | `"40Gi"` |
| `resourceQuota.hard.pods` | 命名空间可运行 Pod 总数上限 | `"100"` |
| `resourceQuota.hard.persistentvolumeclaims` | 命名空间 PVC 数量上限 | `"20"` |
| `resourceQuota.hard.services` | 命名空间 Service 数量上限 | `"50"` |

### LimitRange

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `limitRange.enabled` | 是否启用 LimitRange | `true` |
| `limitRange.limits[0].default.cpu` | 容器默认 CPU 限制 | `"1"` |
| `limitRange.limits[0].default.memory` | 容器默认内存限制 | `"2Gi"` |
| `limitRange.limits[0].defaultRequest.cpu` | 容器默认 CPU 请求 | `"0.1"` |
| `limitRange.limits[0].defaultRequest.memory` | 容器默认内存请求 | `"256Mi"` |
| `limitRange.limits[0].max.cpu` | 容器 CPU 上限 | `"4"` |
| `limitRange.limits[0].max.memory` | 容器内存上限 | `"8Gi"` |
| `limitRange.limits[0].min.cpu` | 容器 CPU 下限 | `"0.01"` |
| `limitRange.limits[0].min.memory` | 容器内存下限 | `"16Mi"` |

## 卸载

```bash
helm uninstall namespace-security -n <target-namespace>
```