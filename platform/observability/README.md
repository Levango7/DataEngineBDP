# 统一运维观测 — Grafana 双视图与告警分级

## 概述

本目录实现数据引擎大数据平台 V2.0 Phase 2 的 T041 交付物：
Grafana 双视图（平台方/客户方按 Organization 隔离）、Alertmanager 告警分级路由
（P0 电话/短信、P1 邮件/IM、P2 钉钉/飞书）、统一查询 API（按租户隔离）、
告警规则模板库。

## 目录结构

```
platform/observability/
├── grafana/                          # Grafana 双视图配置
│   ├── platform-org/                 # 平台方 Organization（orgId=1）
│   │   ├── datasources/datasources.yaml
│   │   └── dashboards/
│   │       ├── dashboards.yaml
│   │       └── platform-overview.json
│   ├── tenant-org/                   # 客户方 Organization（orgId>=2）
│   │   ├── datasources/datasources.yaml
│   │   └── dashboards/
│   │       ├── dashboards.yaml
│   │       └── tenant-overview.json
│   └── provisioning/
│       ├── grafana.ini               # Grafana 主配置（多 Organization + Keycloak OAuth）
│       └── init-orgs.sh              # Organization 初始化脚本
├── alertmanager/                     # Alertmanager 分级路由
│   ├── alertmanager.yml              # 主配置（P0/P1/P2 三级路由 + 抑制规则）
│   ├── routes/
│   │   ├── p0-route.yml              # P0 路由（电话/短信）
│   │   ├── p1-route.yml              # P1 路由（邮件/IM）
│   │   └── p2-route.yml              # P2 路由（钉钉/飞书）
│   └── templates/
│       ├── default.tmpl              # 通用模板
│       ├── p0.tmpl                   # P0 电话/短信模板
│       ├── p1.tmpl                   # P1 邮件/企业微信模板
│       └── p2.tmpl                   # P2 钉钉/飞书模板
├── query-api/                        # 统一查询 API（Go/Gin）
│   ├── main.go
│   ├── go.mod
│   ├── Dockerfile
│   ├── README.md
│   └── internal/
│       ├── middleware/auth.go        # JWT 认证 + 租户隔离中间件
│       ├── handler/
│       │   ├── health.go
│       │   └── query.go              # Prometheus 代理（平台方/客户方双视图）
│       └── service/
│           ├── prometheus.go         # Prometheus HTTP 客户端
│           └── tenant_filter.go      # 租户隔离 PromQL 注入
├── rules/                            # 告警规则模板库
│   ├── p0-rules.yaml                 # P0 严重告警规则
│   ├── p1-rules.yaml                 # P1 重要告警规则
│   ├── p2-rules.yaml                 # P2 一般告警规则
│   └── README.md
└── README.md                         # 本文件
```

## 架构总览

```
                          ┌─────────────────────────────┐
                          │       Keycloak (OIDC)        │
                          │  realm: shuqing             │
                          │  groups: platform-ops,      │
                          │          tenant-*           │
                          └──────────┬──────────────────┘
                                     │ OAuth
              ┌──────────────────────┼──────────────────────┐
              ↓                                             ↓
   ┌──────────────────┐                          ┌──────────────────┐
   │  Grafana Org 1   │                          │  Grafana Org N   │
   │  (Platform)      │                          │  (Tenant-N)      │
   │  平台运维视图    │                          │  租户视图        │
   └────────┬─────────┘                          └────────┬─────────┘
            │ datasource                                  │ datasource
            │ url=query-api:8090/platform                 │ url=query-api:8090/tenant
            ↓                                             ↓
   ┌────────────────────────────────────────────────────────────────┐
   │                    query-api (Go/Gin :8090)                    │
   │  /platform/** → 无过滤（需 role=platform-ops）                │
   │  /tenant/**   → 注入 tenant_id 过滤（由 JWT tenantId claim）   │
   └────────────────────────┬───────────────────────────────────────┘
                            │
                            ↓
   ┌────────────────────────────────────────────────────────────────┐
   │                   Prometheus (:9090)                           │
   │  指标携带 tenant_id 标签，由 query-api 强制过滤                │
   │  rule_files: rules/p0-rules.yaml, p1-rules.yaml, p2-rules.yaml │
   └────────────────────────┬───────────────────────────────────────┘
                            │ alert
                            ↓
   ┌────────────────────────────────────────────────────────────────┐
   │                Alertmanager (:9093)                            │
   │  route by severity label:                                      │
   │    P0 → p0-pager        → 电话网关 + 短信网关                  │
   │    P1 → p1-email-im     → SMTP 邮件 + 企业微信                │
   │    P2 → p2-dingtalk-feishu → 钉钉 + 飞书                      │
   └────────────────────────────────────────────────────────────────┘
```

## 双视图隔离

### 平台方视图（Organization 1）

- **数据源**：`Prometheus-Platform`，URL 指向 `query-api:8090/platform`
- **可见范围**：全平台所有租户的指标（不做 tenant 过滤）
- **访问控制**：仅 `platform-ops` 角色可访问（由 `PlatformRoleMiddleware` 校验）
- **仪表板**：`platform-overview.json`（活跃租户数、告警分级计数、组件 CPU、租户 QPS）

### 客户方视图（Organization N，每租户一个）

- **数据源**：`Prometheus-Tenant`，URL 指向 `query-api:8090/tenant`
- **可见范围**：仅本租户指标（由 `TenantIsolationMiddleware` 强制注入 `tenant_id` 过滤）
- **访问控制**：JWT 中 `tenantId` claim 决定可见范围，租户间互不可见
- **仪表板**：`tenant-overview.json`（查询 QPS、运行中作业、告警分级、查询延迟 P95）

### 隔离保证

1. **Organization 隔离**：Grafana 按 `orgId` 隔离数据源/仪表板/用户，租户无法切换到其他 Organization。
2. **API 隔离**：`query-api` 对 `/tenant/**` 请求强制注入 `tenant_id` 标签过滤，即使伪造 PromQL 也无法跨租户查询。
3. **JWT 隔离**：`tenantId` claim 由 Keycloak 签发，租户无法篡改。
4. **PromQL 注入防护**：`tenant_id` 必须匹配 `^[a-zA-Z0-9_-]{1,64}$`。

## 告警分级路由

| 级别 | severity label | 通知渠道           | receiver              | 重复间隔 |
|------|----------------|--------------------|-----------------------|----------|
| P0   | `P0`           | 电话 + 短信        | `p0-pager`            | 5m       |
| P1   | `P1`           | 邮件 + 企业微信    | `p1-email-im`         | 30m      |
| P2   | `P2`           | 钉钉 + 飞书        | `p2-dingtalk-feishu`  | 4h       |

### 抑制规则

- P0 触发时抑制同租户同组件的 P1/P2，避免告警风暴。
- P1 触发时抑制同租户同组件的 P2。

## 告警规则模板

详见 `rules/README.md`。

平台方直接加载模板；租户复制模板后替换 `{{tenant_id}}` 与阈值变量即可自定义告警。

## 部署

### Docker Compose

```yaml
services:
  query-api:
    build: ./platform/observability/query-api
    ports: ["8090:8090"]
    environment:
      - PROMETHEUS_URL=http://prometheus:9090
      - JWT_SECRET=${JWT_SECRET}

  grafana:
    image: grafana/grafana:10.4.1
    volumes:
      - ./platform/observability/grafana/provisioning:/etc/grafana/provisioning
      - ./platform/observability/grafana/platform-org:/etc/grafana/provisioning/dashboards/platform-org
      - ./platform/observability/grafana/tenant-org:/etc/grafana/provisioning/dashboards/tenant-org
    ports: ["3000:3000"]

  alertmanager:
    image: prom/alertmanager:v0.27.0
    volumes:
      - ./platform/observability/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml
      - ./platform/observability/alertmanager/templates:/etc/alertmanager/templates
    ports: ["9093:9093"]

  prometheus:
    image: prom/prometheus:v2.52.0
    volumes:
      - ./platform/observability/rules:/etc/prometheus/rules
    ports: ["9090:9090"]
```

### 环境变量

| 变量                    | 说明                         | 示例                             |
|-------------------------|------------------------------|----------------------------------|
| `JWT_SIGNING_KEY`       | JWT 签名密钥（≥32 字节，必需，fail-fast） | `<强随机密钥>`           |
| `PROMETHEUS_URL`        | Prometheus 地址              | `http://prometheus:9090`         |
| `PHONE_GATEWAY_URL`     | 电话网关地址                 | `http://phone-gateway:8088`      |
| `SMS_GATEWAY_URL`       | 短信网关地址                 | `http://sms-gateway:8089`        |
| `SMTP_HOST` / `SMTP_PORT` | SMTP 服务器                | `smtp.example.com` / `587`       |
| `WECOM_WEBHOOK_URL`     | 企业微信群机器人 webhook     | `https://qyapi.weixin.qq.com/...`|
| `DINGTALK_WEBHOOK_URL`  | 钉钉群机器人 webhook         | `https://oapi.dingtalk.com/...`  |
| `FEISHU_WEBHOOK_URL`    | 飞书群机器人 webhook         | `https://open.feishu.cn/...`     |

## 测试

集成测试位于 `tests/integration/docker/test_grafana_dual_view.py`，覆盖：

1. **双视图隔离**：平台方与客户方 Organization 数据互不可见
2. **告警分级路由**：P0/P1/P2 三级路由触发正确通知渠道
3. **查询隔离**：统一查询 API 按租户隔离，租户间指标互不可见
4. **模板复用**：告警规则模板可复用，租户自定义阈值生效

运行：

```bash
pytest tests/integration/docker/test_grafana_dual_view.py -v
```