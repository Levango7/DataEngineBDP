# encaps-tenant Chart

封装层租户域（`platform/encaps-tenant`：Tenant / Project / Account / Workspace / Quota）的部署单元。

- **台账背景**：#2 部署单元缺口清单中的核心服务之一；其 prod profile 的**建表缺口**（#63）已于
  2026-10-11 用 `V3__baseline_shared_model.sql`（20 表，补齐其复用 encaps-layer 的共享实体）结清，
  本地 PG16 + `SPRING_PROFILES_ACTIVE=prod` 实测启动通过 ⇒ 本 chart 的 prod 路径可用。
- **端口**：8081；探针 `/actuator/health`；**startup 探针默认开**（本服务 20 个 JPA 仓库 +
  Hibernate 初始化实测启动 40–60s，liveness 先探会掐死容器——kind-smoke 实证教训）。
- **Secret 注入（三项，均 `valueFrom.secretKeyRef`，留空则不注入）**：
  | values 键 | 注入的环境变量 | 说明 |
  |---|---|---|
  | `auth.jwtSecretName` / `auth.jwtSecretKey` | `JWT_SECRET` | 须与签发端一致，否则 401 |
  | `db.existingSecret` / `db.passwordKey` | `DB_PASSWORD` | prod profile **必需**（`application-prod.yml` 无默认值） |
  | `crypto.existingSecret` / `crypto.encryptKeyKey` | `ENCRYPT_KEY` | 凭据加密，可与 encaps-layer 复用同一 Secret |
- **只读根配套**：`/tmp`（Tomcat 部署临时目录）与 `/app/logs` 默认挂 emptyDir；H2 文件库场景另开
  `data.enabled`（写 `/app/data`），走 PG 时可关。
- **prod 必填清单**：`env.SPRING_PROFILES_ACTIVE=prod`、`DB_URL`（`…?currentSchema=encaps_tenant`）、
  `DB_USERNAME`、`FLYWAY_SCHEMA=encaps_tenant`、`CORS_ORIGINS`（prod **无默认值**，缺失即启动失败）。
- **注意**：与 encaps-layer 有同名表（租户/工作空间），二者共用 PG 实例时**必须独立 schema**
  （`encaps_tenant`）隔离，禁止落 `public`（application-prod.yml 已按此配置）。

```bash
helm template encaps-tenant design/deploy/charts/encaps-tenant
helm upgrade --install encaps-tenant design/deploy/charts/encaps-tenant \
  --set auth.jwtSecretName=sq-jwt --set db.existingSecret=sq-encaps-tenant-db \
  --set crypto.existingSecret=sq-crypto \
  --set env.SPRING_PROFILES_ACTIVE=prod \
  --set env.DB_URL='jdbc:postgresql://postgresql:5432/shuqing?currentSchema=encaps_tenant' \
  --set env.DB_USERNAME=shuqing --set env.CORS_ORIGINS=https://console.example.com \
  -n sq-encaps
```
