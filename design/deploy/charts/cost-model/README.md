# cost-model Chart

FinOps 成本模型（`platform/finops/cost-model`：`/api/v1/finops/metering` 计量采集 + `/api/v1/pricing` 定价与成本测算）的部署单元。

- **台账背景**：#2 部署单元缺口之一。本服务是 finops 面的成本/计量数据源（前端 finops 页面对接其端点）。
- **端口**：8084；探针 `/api/v1/health`（自带 HealthController）；**startup 探针默认开**（Java 服务冷启动，
  同族实测 40–80s，liveness 先探会掐死容器）。
- **Secret（两路，`valueFrom.secretKeyRef`）**：

  | values 键 | 注入环境变量 | 说明 |
  |---|---|---|
  | `auth.jwtSecretName` / `auth.jwtSecretKey` | `JWT_SECRET` | **必填**：application.yml 里 `${JWT_SECRET}` 无默认值，须与签发端（encaps-layer / Keycloak）一致 |
  | `db.existingSecret` / `db.passwordKey` | `DB_PASSWORD` | 可选（默认 H2 不需要） |

- **不设 runAsUser/runAsGroup**：本镜像 Dockerfile 用 `useradd --system`（uid 由系统分配、非固定），
  强制 runAsUser 会造成 /app 属主不匹配；chart 只保留 `runAsNonRoot` 兜底 + `fsGroup`
  （kubelet 会把 fsGroup 加进容器补充组，emptyDir 挂载卷仍可写）。这是"镜像 uid 非固定"时 chart 的正确姿势。
- **数据卷默认开**：默认库为 H2 文件（`./data/cost-model-db`，WORKDIR=/app ⇒ `/app/data`），只读根下必须可写。
- **采集开关**（非敏感，进 ConfigMap）：`COLLECTOR_INTERVAL`（默认 60s）与 `COLLECT_CPU/GPU/MEMORY/NETWORK/STORAGE`
  （默认全 true）——按应用默认暴露，可按集群实际能力关闭（例如无 GPU 集群设 `COLLECT_GPU=false`）。
- **生产建议**：改 PostgreSQL（`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`）+ 启用 Flyway（本模块已接入迁移，
  见 `docs/db-migration-backlog.yaml` 已接入清单）+ `DDL_AUTO=validate`。

```bash
helm template cost-model design/deploy/charts/cost-model
helm upgrade --install cost-model design/deploy/charts/cost-model   --set auth.jwtSecretName=sq-jwt --set env.DB_URL='jdbc:postgresql://postgresql:5432/shuqing?currentSchema=cost_model'   --set env.DB_USERNAME=shuqing --set db.existingSecret=sq-cost-model-db   -n sq-finops
```
