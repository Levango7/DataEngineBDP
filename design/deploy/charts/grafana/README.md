# grafana Helm Chart

> Grafana 面板 - 可视化监控仪表盘 - 数据引擎大数据平台

## 1. 概述

`grafana` 是数据引擎大数据平台（DataEngineBDP）的 Helm Chart，用于在 Kubernetes 集群上一键部署和管理 `grafana` 组件。

- **Chart 版本**：`2.0.0`
- **App 版本**：`2.0.0`
- **默认命名空间**：`sq-monitor`

## 2. 前置条件

- Kubernetes 集群已就绪（版本 >= 1.28）
- Helm 3.14+ 已安装
- 目标命名空间已创建（或使用 `--create-namespace`）
- 所需 StorageClass / Secret 已按环境准备就绪

## 3. 快速开始

### 3.1 安装 Chart

```bash
# 安装到默认命名空间
helm install grafana design/deploy/charts/grafana -n sq-monitor --create-namespace

# 使用自定义 values 覆盖（仓库内无 grafana-values.yaml；用你的环境 values 文件）
helm install grafana design/deploy/charts/grafana -n sq-monitor \
  -f <your-env-values>.yaml
```

### 3.2 升级

```bash
helm upgrade grafana design/deploy/charts/grafana -n sq-monitor \
  -f <your-env-values>.yaml
```

### 3.3 卸载

```bash
helm uninstall grafana -n sq-monitor
```

### 3.4 渲染模板（Dry Run）

```bash
helm template grafana design/deploy/charts/grafana -n sq-monitor
```

## 4. 配置参数

下表列出 `grafana` Chart 的主要配置参数，完整配置参见 `values.yaml`。

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `image.repository` | 镜像仓库地址 | `docker.m.daocloud.io/grafana/grafana` |
| `image.tag` | 镜像标签 | `11.2.0` |
| `image.pullPolicy` | 镜像拉取策略 | `IfNotPresent` |
| `replicaCount` | 副本数 | `1` |
| `service.type` | Service 类型 | `ClusterIP` |
| `service.port` | Service 端口 | `3000` |
| `service.containerPort` | 容器端口 | `3000` |
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
# grafana 自定义配置示例
image:
  repository: "docker.m.daocloud.io/grafana/grafana"
  tag: "11.2.0"
  pullPolicy: IfNotPresent

replicaCount: 2

service:
  type: ClusterIP
  port: 3000
  containerPort: 3000

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
    - host: grafana.shuqing.local
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
kubectl get pods -l "app.kubernetes.io/instance=grafana" -n sq-monitor
```

### 7.2 查看日志

```bash
kubectl logs -l "app.kubernetes.io/instance=grafana" -n sq-monitor -f
```

### 7.3 端口转发（本地调试）

```bash
kubectl port-forward svc/grafana 3000:3000 -n sq-monitor
```

### 7.4 获取当前配置

```bash
helm get values grafana -n sq-monitor
```

## 8. 升级与回滚

```bash
# 查看历史版本
helm history grafana -n sq-monitor

# 回滚到上一版本
helm rollback grafana -n sq-monitor
```

## 9. 维护信息

- **维护方**：DataEngineBDP Team
- **联系邮箱**：platform@shuqing.example.com
- **项目主页**：https://github.com/DataEngineBDP
- **Chart 版本**：2.0.0
- **App 版本**：2.0.0
- **关联配置**：本 chart 的 `values.yaml`（2026-11-13 订正：原引用的 `design/deploy/values/grafana-values.yaml` 在仓库中不存在，属悬空引用）

## 10. Dashboards as Code（大盘即代码，2026-11-13 新增）

**机制**：大盘以 JSON 文件随 chart 入库（`dashboards/*.json`），由 `templates/dashboards-configmap.yaml`
打包为 ConfigMap（带 `grafana_dashboard: "1"` 标签）并生成 provisioning provider；`templates/datasources-configmap.yaml`
提供 Prometheus 数据源 provisioning；Deployment 将三处卷挂载进容器：

- 大盘目录 → `/var/lib/grafana/dashboards`（provider 的 `path`，见 `dashboards.path`）
- provider 配置 → `/etc/grafana/provisioning/dashboards`
- 数据源配置 → `/etc/grafana/provisioning/datasources`（`datasource.*`，默认 `http://prometheus:9090`）

**随 chart 交付的大盘**：

| 大盘 | uid | 内容 |
| --- | --- | --- |
| 平台 API 总览 | `sq-api-overview` | 在线目标（`up`）、JVM/Go 请求率、P95 延迟、5xx 占比、up 明细表 |
| JVM 服务明细 | `sq-jvm-services` | 堆/非堆内存、进程 CPU、线程数、GC 暂停率、Go 在途请求 |

**指标口径**：Java 服务取 Actuator/Prometheus 族（`http_server_requests_seconds_*`、`jvm_*`、`process_*`）；
Go 服务（catalog 等）取 `http_requests_total` / `http_request_duration_seconds` / `http_in_flight_requests`。

**操作**：加盘 = 往 `dashboards/` 丢 JSON 后 `helm upgrade`（ConfigMap checksum 触发滚动重启）；关盘 = `dashboards.enabled=false`。

**验证**：`helm template grafana design/deploy/charts/grafana` 渲染含 3 个相关 ConfigMap 与 3 个挂载点；
CI 的「全量 Chart 矩阵」腿（Profile Render Check）覆盖本 chart。**边界**：面板查询按通用指标名书写，
各环境 `instance` 标签与 Prometheus scrape 配置相关；若面板 No data，先核对 Prometheus 抓取目标与 `datasource.url`。
