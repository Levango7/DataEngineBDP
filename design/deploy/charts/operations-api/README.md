# operations-api Helm Chart

> 数擎运营后台 · 合同管理服务 (P-04) - 数据引擎大数据平台

## 1. 概述

`operations-api` 是数据引擎大数据平台（DataEngineBDP）的 Helm Chart，用于在 Kubernetes 集群上一键部署和管理 `operations-api` 组件（Python FastAPI，`platform/operations-api/Dockerfile`）。

- **Chart 版本**：`2.0.0`
- **App 版本**：`2.0.0`
- **默认命名空间**：`platform-ops`（与既有 `design/deploy/services/operations/k8s.yaml` 一致）
- **组件形态**：端口 `8081`（uvicorn），健康端点 `/healthz`（存活，公开）、`/readyz`（就绪，公开）
- **安装方式**：**独立 `helm install`**（未纳入 `dataenginebdp-umbrella`，见第 8 节）

## 2. 前置条件

- Kubernetes 集群已就绪（版本 >= 1.28）
- Helm 3.14+ 已安装
- 目标命名空间已创建（或使用 `--create-namespace`）
- **JWT 签名 Secret 已创建**（必须）：默认引用 `shuqing-jwt-secret` / 键 `jwt-secret`，创建方法见 `design/deploy/JWT鉴权配置指南.md` 第 3 节。缺失时本服务拒绝启动（见第 6 节）

## 3. 快速开始

### 3.1 安装 Chart

```bash
# 1) 先创建 JWT Secret（缺失则 Pod 起不来）
kubectl create secret generic shuqing-jwt-secret \
  --namespace platform-ops \
  --from-literal=jwt-secret="$(openssl rand -base64 48)"

# 2) 独立安装
helm install operations-api design/deploy/charts/operations-api -n platform-ops --create-namespace
```

### 3.2 升级 / 卸载

```bash
helm upgrade operations-api design/deploy/charts/operations-api -n platform-ops
helm uninstall operations-api -n platform-ops
```

### 3.3 渲染模板（Dry Run）

```bash
helm template operations-api design/deploy/charts/operations-api -n platform-ops
```

## 4. 配置参数

下表列出主要配置参数（完整配置参见 `values.yaml`）：

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `image.repository` | 镜像仓库地址（build.yml 产出） | `ghcr.io/levango7/sq-operations-api` |
| `image.tag` | 镜像标签 | `latest` |
| `image.pullPolicy` | 镜像拉取策略 | `Always` |
| `replicaCount` | 副本数（内存仓储，默认 1） | `1` |
| `service.type` | Service 类型 | `ClusterIP` |
| `service.port` | Service 端口 | `8081` |
| `service.containerPort` | 容器端口 | `8081` |
| `resources.requests.cpu` | CPU 请求 | `500m` |
| `resources.requests.memory` | 内存请求 | `512Mi` |
| `resources.limits.cpu` | CPU 上限 | `1000m` |
| `resources.limits.memory` | 内存上限 | `1Gi` |
| `securityContext.enabled` | 安全上下文开关 | `true` |
| `securityContext.runAsNonRoot` | 非 root 运行 | `true` |
| `securityContext.runAsUser` | 运行用户 UID（与镜像 app 对齐） | `999` |
| `securityContext.runAsGroup` | 运行用户组 GID | `999` |
| `securityContext.fsGroup` | 卷属组 | `999` |
| `securityContext.readOnlyRootFilesystem` | 只读根文件系统 | `false` |
| `probes.liveness.httpGet.path` | 存活探针端点 | `/healthz` |
| `probes.readiness.httpGet.path` | 就绪探针端点 | `/readyz` |
| `auth.authMode` | 鉴权模式（K8s 必须显式） | `jwt` |
| `auth.existingSecretName` | JWT Secret 名称 | `shuqing-jwt-secret` |
| `auth.jwtSecretKey` | JWT Secret 键名 | `jwt-secret` |
| `hpa.enabled` | HPA 开关 | `false` |
| `pdb.enabled` | PDB 开关 | `false` |
| `ingress.enabled` | Ingress 开关 | `false` |
| `networkPolicy.enabled` | NetworkPolicy 开关 | `false` |
| `serviceMonitor.enabled` | ServiceMonitor 开关 | `false` |

## 5. 示例 values.yaml

```yaml
image:
  repository: "ghcr.io/levango7/sq-operations-api"
  tag: "<40 位 sha>"
  pullPolicy: IfNotPresent

replicaCount: 1

service:
  type: ClusterIP
  port: 8081
  containerPort: 8081

auth:
  authMode: jwt
  existingSecretName: shuqing-jwt-secret
  jwtSecretKey: jwt-secret
```

## 6. 关键部署说明（务必阅读）

### 6.1 AUTH_MODE / JWT_SECRET 启动依赖

`jwt_auth.py` 导入即校验（台账 #117 P1）：K8s 环境（`KUBERNETES_SERVICE_HOST` 存在）**未显式 `AUTH_MODE`**，或 jwt 模式**缺 `JWT_SECRET`**（≥32 字节）⇒ 拒绝启动（"拒绝以匿名 admin 模式启动"）。本 Chart 因此：

1. 显式注入 `AUTH_MODE={{ .Values.auth.authMode | default "jwt" }}`（生产=强制 jwt；`none` 仅本地/测试，且 K8s 下直接被拒）；
2. `JWT_SECRET` 经 secretKeyRef 注入（值不落任何 Chart 文件）。

Secret 缺失时 Pod 停在 `CreateContainerConfigError`，失败暴露在部署期。

### 6.2 uid/gid 对齐

镜像用 `groupadd/useradd --system` 创建 `app` 用户，实测 `python:3.12-slim` 基座分配 uid=gid=999（2026-11-07 `docker run` 验证），Chart 的 `runAsUser`/`runAsGroup`/`fsGroup` 与之对齐。

### 6.3 单副本约束

合同仓储目前为内存仓库（`operations_api/repositories/__init__.py`），多副本各自持有独立内存态 ⇒ 数据分裂；持久化属产品范围，落地前保持 `replicaCount: 1`。

### 6.4 只读根文件系统

默认 `readOnlyRootFilesystem: false`，与同族 Python 服务 `asset-exchange` 同口径：本服务无 H2/日志等落盘目录（`PYTHONDONTWRITEBYTECODE=1`、仓储在内存），保留非 root + 能力全裁 + 禁提权即可，不追加只读根收益。

## 7. 运维操作

```bash
# 查看 Pod 状态
kubectl get pods -l "app.kubernetes.io/instance=operations-api" -n platform-ops

# 查看日志
kubectl logs -l "app.kubernetes.io/instance=operations-api" -n platform-ops -f

# 端口转发（本地调试）
kubectl port-forward svc/operations-api 8081:8081 -n platform-ops

# 回滚
helm rollback operations-api -n platform-ops
```

## 8. 双实现裁决与 umbrella 关系

- **双实现**（2026-11-06 审计 #117 登记，**2026-11-10 裁决 #119⑥**）：`design/deploy/services/operations/k8s.yaml` 引用的是内存演示态副本；本 Chart 部署的是 `platform/operations-api` 正版镜像。**裁决结果：本 Chart 为 umbrella 唯一部署单元**；演示态目录存档保留不删（CI 仍编译其 `main.py`、`CONVENTIONS.md` 仍有引用）。两边代码未合并、路由不共享。
- **umbrella**：本 Chart **已纳入** `charts/dataenginebdp-umbrella`（2026-11-10 批五处同步：requirements.yaml / Chart.yaml dependencies / values.yaml enabled / Chart.lock / vendored `charts/operations-api-2.0.0.tgz`），`enabled: true` 默认启用；`/api/v1/contracts/**` 路由已随 APISIX 路由生成物重生成（99 条）。如需独立使用，仍按第 3 节**独立 `helm install`**。

## 9. 维护信息

- **维护方**：DataEngineBDP Team
- **联系邮箱**：platform@shuqing.example.com
- **项目主页**：https://github.com/DataEngineBDP
- **Chart 版本**：2.0.0 / App 版本：2.0.0
- **关联源码**：`platform/operations-api/Dockerfile`（部署关键约定见其头注）
