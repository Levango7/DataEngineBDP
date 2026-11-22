# iceberg-rest Helm Chart

> Iceberg REST Catalog - 表格式 REST 元服务 - 数据引擎大数据平台

## 1. 概述

`iceberg-rest` 是数据引擎大数据平台（DataEngineBDP）的 Helm Chart，用于在 Kubernetes 集群上一键部署和管理 `iceberg-rest` 组件。

- **Chart 版本**：`2.0.0`
- **App 版本**：`2.0.0`
- **默认命名空间**：`sq-engine`

## 2. 前置条件

- Kubernetes 集群已就绪（版本 >= 1.28）
- Helm 3.14+ 已安装
- 目标命名空间已创建（或使用 `--create-namespace`）
- 所需 StorageClass / Secret 已按环境准备就绪

## 3. 快速开始

### 3.1 安装 Chart

```bash
# 安装到默认命名空间
helm install iceberg-rest design/deploy/charts/iceberg-rest -n sq-engine --create-namespace

# 使用自定义 values 覆盖
helm install iceberg-rest design/deploy/charts/iceberg-rest -n sq-engine \
  -f design/deploy/values/iceberg-rest-values.yaml
```

### 3.2 升级

```bash
helm upgrade iceberg-rest design/deploy/charts/iceberg-rest -n sq-engine \
  -f design/deploy/values/iceberg-rest-values.yaml
```

### 3.3 卸载

```bash
helm uninstall iceberg-rest -n sq-engine
```

### 3.4 渲染模板（Dry Run）

```bash
helm template iceberg-rest design/deploy/charts/iceberg-rest -n sq-engine
```

## 4. 配置参数

下表列出 `iceberg-rest` Chart 的主要配置参数，完整配置参见 `values.yaml`。

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `image.repository` | 镜像仓库地址（直连 Docker Hub） | `tabulario/iceberg-rest` |
| `image.tag` | 镜像标签 | `1.6.0` |
| `image.pullPolicy` | 镜像拉取策略 | `IfNotPresent` |
| `replicaCount` | 副本数（**必须 1**，见下方「副本数约束」） | `1` |
| `credentials.existingSecret` | 凭据 Secret 名（AK/SK 走 Secret，不进 ConfigMap） | `""` |
| `catalog.uri` | catalog 元数据后端 JDBC URL（留空＝镜像内置默认） | `""` |
| `catalogPersistence.enabled` | catalog 元数据持久化开关 | `false` |
| `catalogPersistence.mountPath` | 持久化卷挂载路径 | `/data` |
| `catalogPersistence.size` | 持久化卷容量 | `5Gi` |
| `service.type` | Service 类型 | `ClusterIP` |
| `service.port` | Service 端口 | `8181` |
| `service.containerPort` | 容器端口 | `8181` |
| `resources.requests.cpu` | CPU 请求 | `0.5` |
| `resources.requests.memory` | 内存请求 | `1Gi` |
| `resources.limits.cpu` | CPU 上限 | `1` |
| `resources.limits.memory` | 内存上限 | `2Gi` |
| `securityContext.enabled` | 安全上下文开关 | `true` |
| `securityContext.runAsNonRoot` | 非 root 运行 | `true` |
| `securityContext.runAsUser` | 运行用户 UID | `1000` |
| `securityContext.fsGroup` | 卷属组（catalog 挂载卷可写所需） | `1000` |
| `probes.liveness.enabled` | 存活探针开关 | `true` |
| `probes.liveness.httpGet.path` | 存活探针路径 | `/v1/config` |
| `probes.readiness.enabled` | 就绪探针开关 | `true` |
| `probes.readiness.httpGet.path` | 就绪探针路径 | `/v1/config` |
| `autoscaling.enabled` | HPA 开关 | `false` |
| `autoscaling.minReplicas` | HPA 最小副本 | `1` |
| `autoscaling.maxReplicas` | HPA 最大副本 | `6` |
| `podDisruptionBudget.enabled` | PDB 开关 | `false` |
| `ingress.enabled` | Ingress 开关 | `false` |
| `ingress.className` | Ingress 控制器 | `apisix` |
| `nodeSelector` | 节点选择器 | `{}` |
| `tolerations` | 污点容忍 | `[]` |
| `affinity` | 亲和性 | `{}` |

### 4.1 副本数约束（不可绕过）

该镜像内置的 catalog 后端是 **JdbcCatalog + 容器内 sqlite 文件**：

- 默认 `CATALOG_URI=jdbc:sqlite:file:/tmp/iceberg_rest_mode=memory`；runner 实测该路径是
  **20480 字节的真实 sqlite 文件**（文件头 `SQLite format 3`），**不是**内存库
  ⇒ 容器重启即丢表清单。
- sqlite 是单文件库：多副本各自持有自己的本地文件 ⇒ 表清单分叉，REST 请求落到不同副本
  会看到不同的 catalog ⇒ 语义错误。

⇒ 单机 sqlite 后端必须 `replicaCount: 1`；要多副本必须把 `catalog.uri` 指向外部共享
JDBC 库（PostgreSQL/MySQL），此时才能放开副本数与 HPA。
挂卷持久化（`catalogPersistence.enabled: true`）同样受此约束。

### 4.2 探针路径

该镜像**不实现** `/health`、`/healthz`、`/readyz`——三个路径 runner 实测全部返回 400
（这三个是 K8s 探针惯例路径，该镜像并不实现）⇒ 旧值必然导致 Pod 永远 NotReady。
REST 规范的 `/v1/config` 实测返回 200 ⇒ liveness / readiness 均用它。

### 4.3 版本三轴（不可混引）

| 轴 | 取值 | 出处 |
| --- | --- | --- |
| Java 侧 Iceberg 库 | `1.12.0` | `platform/flink-cdc/pom.xml:41` 等三处 `<iceberg.version>` |
| Spark 运行时 Iceberg JAR | `iceberg-spark-runtime-4.1_2.13-1.11.0.jar` | `platform/batch-pipeline/config/pipeline.json:174` |
| REST catalog 服务端镜像 | `tabulario/iceberg-rest:1.6.0` | 本 chart 的 `image.tag` |

三者是不同的轴（库 / 客户端运行时 / 服务端实现），任何一处引用都不得拿另一个轴的数字对齐。

## 5. 示例 values.yaml

```yaml
# iceberg-rest 自定义配置示例
image:
  repository: "tabulario/iceberg-rest"
  tag: "1.6.0"
  pullPolicy: IfNotPresent

# 必须为 1，除非 catalog.uri 指向外部共享 JDBC 库
replicaCount: 1

credentials:
  existingSecret: "rustfs-creds"
  accessKeyKey: "access-key"
  secretKeyKey: "secret-key"

# 持久化 catalog 元数据（须保持 replicaCount=1）
catalog:
  uri: ""
catalogPersistence:
  enabled: true
  mountPath: /data
  size: 5Gi
  accessModes:
    - ReadWriteOnce

env:
  CATALOG_IO__IMPL: "org.apache.iceberg.aws.s3.S3FileIO"
  CATALOG_WAREHOUSE: "s3://lakehouse/warehouse"
  CATALOG_S3_ENDPOINT: "http://minio:9000"
  CATALOG_S3_PATH__STYLE__ACCESS: "true"
  AWS_REGION: "us-east-1"

service:
  type: ClusterIP
  port: 8181
  containerPort: 8181

resources:
  requests:
    cpu: "0.5"
    memory: "1Gi"
  limits:
    cpu: "1"
    memory: "2Gi"

securityContext:
  enabled: true
  runAsNonRoot: true
  runAsUser: 1000
  fsGroup: 1000

autoscaling:
  enabled: false
  minReplicas: 1
  maxReplicas: 6

ingress:
  enabled: true
  className: apisix
  hosts:
    - host: iceberg-rest.shuqing.local
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
kubectl get pods -l "app.kubernetes.io/instance=iceberg-rest" -n sq-engine
```

### 7.2 查看日志

```bash
kubectl logs -l "app.kubernetes.io/instance=iceberg-rest" -n sq-engine -f
```

### 7.3 端口转发（本地调试）

```bash
kubectl port-forward svc/iceberg-rest 8181:8181 -n sq-engine
```

### 7.4 获取当前配置

```bash
helm get values iceberg-rest -n sq-engine
```

## 8. 升级与回滚

```bash
# 查看历史版本
helm history iceberg-rest -n sq-engine

# 回滚到上一版本
helm rollback iceberg-rest -n sq-engine
```

## 9. 维护信息

- **维护方**：DataEngineBDP Team
- **联系邮箱**：platform@shuqing.example.com
- **项目主页**：https://github.com/DataEngineBDP
- **Chart 版本**：2.0.0
- **App 版本**：2.0.0
- **关联配置**：`design/deploy/values/iceberg-rest-values.yaml`
