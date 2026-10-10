# query-api Chart

统一查询 API（`platform/observability/query-api`）的部署单元。

- **台账背景**：#2 部署单元缺口之一。本服务是 **dashboard / cluster-overview 等页面的数据源**
  （对外经 APISIX `/api/v1/ops/**`、`/api/v1/cluster/**`；内部为 `/platform/api/v1/*`（平台视图）
  与 `/tenant/api/v1/*`（强制 `tenant_id` 过滤）双视图）。
- **端口**：8090；探针 `/api/v1/health`（与镜像 HEALTHCHECK 同路径）；Go 服务启动快 ⇒ 不需要 startup 探针。
- **敏感项**：`JWT_SIGNING_KEY` 走 `auth.jwtSigningKeySecret`（`valueFrom.secretKeyRef`），
  **不进 ConfigMap / Pod spec 字面量**（`check-chart-secret-hygiene.py` 门禁保证）。
  该值是**硬要求**：`internal/middleware/auth.go:47` `mustGetenv("JWT_SIGNING_KEY")`，缺失即启动 fatal；
  值需 ≥32 字节且与签发端一致。
- **环境变量**（非敏感，进 ConfigMap）：`PROMETHEUS_URL`（默认 `http://prometheus:9090`）、
  `ALERTMANAGER_URL`、`QUERY_API_PLATFORM_ROLE`（默认 `platform-ops`）、`JWT_ISSUER`（默认 `shuqing-bigdata`）、
  `CORS_ALLOWED_ORIGINS`、`OPS_COMPONENTS`；`QUERY_API_K8S_MOCK` **仅供无 k8s 的测试栈**（生产留空）。
- **只读根**：`/tmp` emptyDir 默认开（保险起见；Go 服务通常不需要额外可写点）。
- **注意**：本服务当前**未暴露 `/metrics`**（可观测性走 OTel），`serviceMonitor` 默认关，启用前需先加指标端点。

```bash
helm template query-api design/deploy/charts/query-api
helm upgrade --install query-api design/deploy/charts/query-api \
  --set auth.jwtSigningKeySecret=sq-jwt --set env.CORS_ALLOWED_ORIGINS=https://console.example.com \
  -n sq-observability
```
