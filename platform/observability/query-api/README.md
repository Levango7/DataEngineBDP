# 统一查询 API（query-api）

## 概述

统一查询 API 是数据引擎大数据平台 Grafana 双视图与租户隔离的核心组件。
它封装后端 Prometheus 查询 API，按租户强制注入 `tenant_id` 标签过滤，
确保租户间指标互不可见。

## 架构

```
Grafana 平台方 Org ──┐
                      ├──→ query-api:8090 ──→ Prometheus:9090
Grafana 客户方 Org ──┘
        │                       │
        │  Bearer JWT            │ 注入 tenant_id 过滤
        │  (tenantId claim)      │ (PromQL AND {tenant_id="xxx"})
        ↓                       ↓
    Keycloak               Prometheus (多租户标签)
```

## 双视图端点

| 路径前缀      | 视图     | 隔离策略                        | 鉴权              |
|---------------|----------|---------------------------------|-------------------|
| `/platform/**` | 平台方  | 无过滤（全平台指标可见）        | JWT role=platform-ops |
| `/tenant/**`   | 客户方  | 强制注入 `tenant_id` 标签过滤   | JWT tenantId claim   |

## 代理的 Prometheus API

| 端点                                  | 说明           |
|---------------------------------------|----------------|
| `/{view}/api/v1/query`                | 瞬时查询       |
| `/{view}/api/v1/query_range`          | 范围查询       |
| `/{view}/api/v1/labels`               | 标签名列表     |
| `/{view}/api/v1/label/:name/values`   | 标签值列表     |
| `/{view}/api/v1/series`               | 序列查找       |

其中 `{view}` 为 `platform` 或 `tenant`。

## 租户隔离策略

### PromQL 注入

对客户方查询的 PromQL，注入最外层 `AND` 过滤：

```
原始: sum(rate(shuqing_query_total[5m])) by (component)
注入: (sum(rate(shuqing_query_total[5m])) by (component)) AND {tenant_id="tenant-demo"}
```

### 标签/序列过滤

对 `labels` / `label/:name/values` / `series` 请求，追加 `match[]={tenant_id="xxx"}` 参数。

### PromQL 注入防护

`tenant_id` 必须匹配 `^[a-zA-Z0-9_-]{1,64}$`，防止 PromQL 注入攻击。

## 配置

| 环境变量                  | 默认值                              | 说明                         |
|---------------------------|-------------------------------------|------------------------------|
| `QUERY_API_PORT`          | `8090`                              | 监听端口                     |
| `PROMETHEUS_URL`          | `http://prometheus:9090`            | 后端 Prometheus 地址         |
| `JWT_SECRET`              | `dev-secret-key-...`                | HMAC-SHA 签名密钥（≥32字节） |
| `JWT_ISSUER`              | `shuqing-bigdata`                   | JWT issuer                   |
| `QUERY_API_PLATFORM_ROLE` | `platform-ops`                      | 平台方角色名                 |

## 本地运行

```bash
# 编译
go build -o query-api .

# 运行（需先启动 Prometheus）
export PROMETHEUS_URL=http://localhost:9090
export JWT_SECRET=dev-secret-key-change-in-production-at-least-256-bits
./query-api

# 测试客户方查询（需有效 JWT）
curl -H "Authorization: Bearer <jwt>" \
  "http://localhost:8090/tenant/api/v1/query?query=up&time=$(date +%s)"
```

## 测试

```bash
go test ./...
```