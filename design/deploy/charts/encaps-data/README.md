# encaps-data Chart

封装层数据域（`platform/encaps-data`：数据源 CRUD、引擎监控 Doris/Kafka/Flink/IoTDB、全文检索）的部署单元。

- **台账背景**：#2 部署单元缺口清单中的核心服务之一；其 prod profile 的**建表缺口**（#63）已于
  2026-10-11 用 `V3__baseline_shared_model.sql`（20 表，补齐其复用 encaps-layer 的共享实体）结清，
  本地 PG16 + `SPRING_PROFILES_ACTIVE=prod` 实测启动通过 ⇒ 本 chart 的 prod 路径可用。
- **端口**：8083；探针 `/actuator/health`（compose 健康检查同路径）；**startup 探针默认开**（本服务实测启动约 78s：
  K8s client 初始化 22s + JVM；liveness 先探会掐死容器——encaps-layer 在 kind-smoke 的实证教训）。（原行尾：
  Hibernate 初始化实测启动 40–60s，liveness 先探会掐死容器——kind-smoke 实证教训）。
- **Secret 注入（三项，均 `valueFrom.secretKeyRef`，留空则不注入）**：
  | values 键 | 注入的环境变量 | 说明 |
  |---|---|---|
  | `auth.jwtSecretName` / `auth.jwtSecretKey` | `JWT_SECRET` | 须与签发端一致，否则 401 |
  | `db.existingSecret` / `db.passwordKey` | `DB_PASSWORD` | prod profile **必需**（`application-prod.yml` 无默认值） |
  | `crypto.existingSecret` / `crypto.encryptKeyKey` | `ENCRYPT_KEY` | 凭据加密，可与 encaps-layer 复用同一 Secret |
- **只读根配套**：`/tmp`（Tomcat 部署临时目录）与 `/app/logs` 默认挂 emptyDir；H2 文件库场景另开
  `data.enabled`（写 `/app/data`），走 PG 时可关。
- **必填/建议**：`CREDENTIAL_ENCRYPTION_KEY` 与 `ENCRYPT_KEY` **至少其一**（条件装配，否则上下文起不来）；
  生产改 PG：`DB_URL`（`…?currentSchema=encaps_data`）+ `DB_USERNAME` + `DB_PASSWORD`（Secret）+ `CORS_ORIGINS`；
  `DDL_AUTO` 生产建议 `validate`（本模块暂无迁移，见 `docs/db-migration-backlog.yaml`）。
- **注意**：本模块暂无生产迁移，默认 `ddl-auto=update` + H2 文件库；切 PG 时请按 `docs/db-migration-backlog.yaml`
  的待补清单先接迁移再设 `validate`。

```bash
helm template encaps-data design/deploy/charts/encaps-data
helm upgrade --install encaps-data design/deploy/charts/encaps-data \
  --set auth.jwtSecretName=sq-jwt --set db.existingSecret=sq-encaps-data-db \
  --set crypto.existingSecret=sq-crypto \
  --set env.SPRING_PROFILES_ACTIVE=prod \
  --set env.DB_URL='jdbc:postgresql://postgresql:5432/shuqing?currentSchema=encaps_data' \
  --set env.DB_USERNAME=shuqing --set env.CORS_ORIGINS=https://console.example.com \
  -n sq-encaps
```
