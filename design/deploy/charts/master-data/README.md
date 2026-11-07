# master-data Helm Chart

> 主数据管理服务 (L3.6) - 数据引擎大数据平台

## 1. 概述

`master-data` 是数据引擎大数据平台（DataEngineBDP）的 Helm Chart，用于在 Kubernetes 集群上一键部署和管理 `master-data` 组件。

- **Chart 版本**：`2.0.0`
- **App 版本**：`2.0.0`
- **默认命名空间**：`sq-governance`
- **组件形态**：Java Spring Boot（`platform/master-data/Dockerfile`），端口 `8088`，H2 文件库 + Flyway
- **安装方式**：**独立 `helm install`**（未纳入 `dataenginebdp-umbrella`，umbrella 的 enabled 列表未裁决前不入，见第 8 节）

## 2. 前置条件

- Kubernetes 集群已就绪（版本 >= 1.28）
- Helm 3.14+ 已安装
- 目标命名空间已创建（或使用 `--create-namespace`）
- **JWT 签名 Secret 已创建**（必须）：默认引用 `shuqing-jwt-secret` / 键 `jwt-secret`，创建方法见 `design/deploy/JWT鉴权配置指南.md` 第 3 节。缺失时本服务启动即失败（见第 6 节）

## 3. 快速开始

### 3.1 安装 Chart

```bash
# 1) 先创建 JWT Secret（缺失则 Pod 起不来）
kubectl create secret generic shuqing-jwt-secret \
  --namespace sq-governance \
  --from-literal=jwt-secret="$(openssl rand -base64 48)"

# 2) 独立安装
helm install master-data design/deploy/charts/master-data -n sq-governance --create-namespace
```

### 3.2 升级 / 卸载

```bash
helm upgrade master-data design/deploy/charts/master-data -n sq-governance
helm uninstall master-data -n sq-governance
```

### 3.3 渲染模板（Dry Run）

```bash
helm template master-data design/deploy/charts/master-data -n sq-governance
```

## 4. 配置参数

下表列出主要配置参数（完整配置参见 `values.yaml`）：

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `image.repository` | 镜像仓库地址（build.yml 产出） | `ghcr.io/levango7/sq-master-data` |
| `image.tag` | 镜像标签 | `latest` |
| `image.pullPolicy` | 镜像拉取策略 | `Always` |
| `replicaCount` | 副本数（H2 单写者，默认 1） | `1` |
| `service.type` | Service 类型 | `ClusterIP` |
| `service.port` | Service 端口 | `8088` |
| `service.containerPort` | 容器端口 | `8088` |
| `resources.requests.cpu` | CPU 请求 | `0.5` |
| `resources.requests.memory` | 内存请求 | `1Gi` |
| `resources.limits.cpu` | CPU 上限 | `1` |
| `resources.limits.memory` | 内存上限 | `2Gi` |
| `securityContext.enabled` | 安全上下文开关 | `true` |
| `securityContext.runAsNonRoot` | 非 root 运行 | `true` |
| `securityContext.runAsUser` | 运行用户 UID（与镜像 appuser 对齐） | `1001` |
| `securityContext.runAsGroup` | 运行用户组 GID | `1001` |
| `securityContext.fsGroup` | 卷属组 | `1001` |
| `securityContext.readOnlyRootFilesystem` | 只读根文件系统 | `true` |
| `probes.liveness.httpGet.path` | 存活探针端点 | `/actuator/health` |
| `probes.readiness.httpGet.path` | 就绪探针端点 | `/actuator/health` |
| `data.enabled` | `/app/data`（H2 文件库）挂载开关 | `true` |
| `data.storageClassName` | 持久化 StorageClass（留空=emptyDir，非空则渲染 PVC） | `""` |
| `data.storageSize` | PVC 容量（storageClassName 非空时生效） | `10Gi` |
| `tmp.enabled` | `/tmp` 挂 emptyDir（Tomcat 临时目录） | `true` |
| `logs.enabled` | `/app/logs` 挂载（本服务无文件日志） | `false` |
| `auth.existingSecretName` | JWT Secret 名称 | `shuqing-jwt-secret` |
| `auth.jwtSecretKey` | JWT Secret 键名 | `jwt-secret` |
| `dbUrl` | 覆盖 DB_URL（切外部 PostgreSQL 用） | `""` |
| `hpa.enabled` | HPA 开关 | `false` |
| `pdb.enabled` | PDB 开关 | `false` |
| `ingress.enabled` | Ingress 开关 | `false` |
| `networkPolicy.enabled` | NetworkPolicy 开关 | `false` |
| `serviceMonitor.enabled` | ServiceMonitor 开关 | `false` |

## 5. 示例 values.yaml

```yaml
image:
  repository: "ghcr.io/levango7/sq-master-data"
  tag: "<40 位 sha>"
  pullPolicy: IfNotPresent

replicaCount: 1

service:
  type: ClusterIP
  port: 8088
  containerPort: 8088

# 生产持久化：H2 换 PostgreSQL 时同时覆盖 dbUrl
dbUrl: "jdbc:<SECRET_69cefb2b>"

data:
  enabled: true
  storageClassName: csi-ceph-ssd

auth:
  existingSecretName: shuqing-jwt-secret
  jwtSecretKey: jwt-secret
```

## 6. 关键部署说明（务必阅读）

### 6.1 JWT_SECRET 是启动必填项

本模块自带 `security/SecurityConfig`，其 `securityFilterChain` 直接注入 `JwtAuthFilter` bean；该 bean 仅当 `app.security.jwt.secret`（=环境变量 `JWT_SECRET`，`application.yml` 中无默认值）可解析且 ≥32 字节时才装配。缺失 ⇒ `UnsatisfiedDependencyException` ⇒ 容器启动即退出。

因此本 Chart 对 `JWT_SECRET` 采取**无条件 secretKeyRef 注入**：引用的 Secret 缺失时 Pod 停在 `CreateContainerConfigError`，把失败暴露在部署期。密钥值**不落任何 Chart 文件**（secret 卫生）。

- **与签发端一致性**：密钥必须与 JWT 签发端（catalog / encaps-layer / Keycloak）一致，否则接口全部 401。
- **本地/CI**：可 `--set auth.existingSecretName=<release>-catalog-auth --set auth.jwtSecretKey=jwt-signing-key` 复用 catalog chart 生成的 Secret。

### 6.2 只读根 + 必需可写点

`readOnlyRootFilesystem: true` 下，`/app/data`（H2 文件库）与 `/tmp`（嵌入式 Tomcat 临时目录）都不可写。本 Chart 默认把两者挂成 emptyDir（`data.enabled` / `tmp.enabled`），保留只读根加固。生产持久化把 `data.storageClassName` 指向 PVC 即可复用同一挂载点。

### 6.3 uid/gid 对齐

镜像内 `appuser`/`appgroup` 固化为 uid/gid 1001。`/app/data` 是镜像内目录（非卷），K8s 的 fsGroup 对它不生效，只有 `runAsUser`/`runAsGroup` 与镜像一致，H2 建库才有写权（否则 `AccessDeniedException: /app/data/master-data-db.mv.db`）。

### 6.4 单副本约束

H2 文件库是单文件单写者存储：多副本各自挂独立卷 ⇒ 数据分裂，同文件并发写会抛约束冲突。默认 `replicaCount: 1`；横向扩容前请先按 5 节示例切 PostgreSQL。

### 6.5 服务链接注入

本服务端口配置项 `server.port=${MASTER_DATA_PORT:8088}` 与 K8s 服务链接注入变量同名，独立安装时（Service 名=chart 名）会被注入 `tcp://ip:8088` 畸形值，故 Chart 显式 `enableServiceLinks: false`（同 catalog / asset-exchange / rule-engine 口径）。

## 7. 运维操作

```bash
# 查看 Pod 状态
kubectl get pods -l "app.kubernetes.io/instance=master-data" -n sq-governance

# 查看日志
kubectl logs -l "app.kubernetes.io/instance=master-data" -n sq-governance -f

# 端口转发（本地调试）
kubectl port-forward svc/master-data 8088:8088 -n sq-governance

# 回滚
helm rollback master-data -n sq-governance
```

## 8. 与 umbrella 的关系

本 Chart **未纳入** `charts/dataenginebdp-umbrella` 的 enabled 列表（组件去留/拓扑编排未裁决前不入 umbrella）。请按第 3 节**独立 `helm install`** 使用；后续若纳入 umbrella，只需在 umbrella values 中启用对应条目。

## 9. 维护信息

- **维护方**：DataEngineBDP Team
- **联系邮箱**：platform@shuqing.example.com
- **项目主页**：https://github.com/DataEngineBDP
- **Chart 版本**：2.0.0 / App 版本：2.0.0
- **关联源码**：`platform/master-data/Dockerfile`（部署关键约定见其头注）
