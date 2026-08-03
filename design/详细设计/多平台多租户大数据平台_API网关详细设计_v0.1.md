# X4 API 网关（APISIX）· 详细设计（v0.1）

> 归属：多平台多租户大数据平台 · X 横切能力层
> 对标：Apache APISIX（国产开源，信创友好）/ Kong / Higress
> 关联：X1 身份权限（鉴权接 Keycloak）；L5.5 开放API/服务目录（网关即发布入口）；L5.2 运营后台（计量数据来源）；L0.11 封装层（配额/隔离）
> 适用版本：基础版✅ / 标准版✅ / 旗舰版✅（全版本开放，对照 §11.5 套餐模块矩阵）

## 1. 定位与价值

API 网关是平台对外暴露的**唯一流量入口**：所有开放 API、控制台 API、租户自定义 API 均经 APISIX 统一鉴权、限流、灰度、计量、审计。底层 K8s 以 Ingress Controller 模式部署，客户无感知。

客户价值：
- "一次发布，多环境可达"——经 L5.5 服务目录发布 API，网关自动生成路由，四环境同构。
- "按调用量计费"——计量数据推 L5.2 运营后台，账单引擎按 API/租户聚合。
- "灰度安全"——按租户/版本/权重灰度发布，出问题秒级回滚。

平台价值：
- 鉴权统一接 Keycloak（X1），不把租户 Token 逻辑散落到各服务。
- 限流/熔断在网关侧收敛，后端服务不被打穿；信创环境 APISIX 纯 Go，无 JVM 依赖。

## 2. 总体架构

```text
┌──────── 外部调用方 ────────┐
│  租户控制台  开放API客户端  三方集成  CI/CD回调  │
└──────────────┬──────────────┘
               ▼
┌──────── APISIX on K8s（Ingress Controller 模式） ────────┐
│  Data Plane（etcd 配置 → Nginx/OpenResty 转发，纯 Go 插件）  │
│  插件链：jwt-auth → keycloak-auth → limit-req → traffic-split │
│         → prometheus(计量) → kafka-logger(审计) → proxy-rewrite │
└──────────────┬──────────────┬──────────────┐
               ▼              ▼              ▼
        后端服务集群      Keycloak(X1)    L5.2 运营后台
        (K8s Service)    (校验/换租户)   (计量/账单)
               ▲
┌──────── L5.5 服务目录（Admin 控制面） ────────┐
│  发布 API → 写 etcd 路由 → 绑插件 → 灰度规则 → 下线  │
└──────────────────────────────────────────┘
```

## 3. 核心插件

| 插件 | 类型 | 作用 | 来源 |
| --- | --- | --- | --- |
| keycloak-auth | 鉴权 | 校验 JWT/Introspection，注入租户/角色到 Header | 自研，对接 X1 |
| jwt-auth | 鉴权 | 内部服务间 mJWT 校验 | APISIX 内置 |
| limit-req / limit-count | 限流 | 按租户/API 令牌桶 + 计数器，超限 429 | 内置 |
| traffic-split | 灰度 | 按权重/版本/租户分流到不同 upstream | 内置 |
| prometheus | 计量 | 暴露调用次数/延迟/状态码指标，被 L5.2 拉取 | 内置 |
| kafka-logger | 审计 | 调用日志推 Kafka，运营后台消费留痕 | 内置 |
| proxy-rewrite | 重写 | 改写 Host/Path/Header，适配后端差异 | 内置 |
| api-breaker | 熔断 | 后端错误率超阈值自动熔断，保护下游 | 内置 |

> 插件链顺序固定：鉴权 → 限流 → 灰度 → 转发 → 计量 → 审计；任一环节失败即短路返回。

插件配置示例（发布一条租户 API 时绑定的插件集合）：
```yaml
plugins:
  keycloak-auth: { client_id: "shuqing", introspection_endpoint: "http://keycloak/realms/{tenant}/protocol/openid-connect/token/introspect" }
  limit-count:   { count: 1000, time_window: 60, key_type: "var", key: "consumer_name", rejected_code: 429 }
  traffic-split: { rules: [ { match: { vars: [["http_release","==","canary"]] }, upstream_id: "u-canary" } ], upstream_id: "u-stable" }
  prometheus:    { prefer_name: true }
  kafka-logger:  { broker_list: ["kafka:9092"], kafka_topic: "apisix-audit", log_format: { tenant: "$consumer_name", api: "$uri" } }
```

## 4. 服务目录与路由

API 生命周期由 **L5.5 服务目录** 管理，网关只做执行面：

1. **发布**：在服务目录登记 API（路径、后端 Service、版本、所属租户）→ 调网关 Admin API 写 Route + 绑插件。
2. **版本**：同一路径多 upstream（v1/v2），用 `traffic-split` 按权重分流。
3. **灰度**：按租户白名单或 Header 标签路由到金丝雀 upstream，观察指标后全量。
4. **下线**：服务目录置为"下线" → 网关 Route 删除，调用方立即 404；历史路由版本留痕可回滚。

路由命名约定：`route-{tenant}-{api}-{version}`，labels 强制带 `tenant/api/version/env`，便于跨环境同步与审计追溯。灰度规则支持按租户白名单、Header 标签、权重百分比三种策略组合，由服务目录 UI 配置后翻译为 `traffic-split` 规则下发。

## 5. 计量计费

- **计量维度**：租户 × API × 调用次数 × 状态码 × 延迟分桶，由 `prometheus` 插件暴露。
- **采集链路**：APISIX `/apisix/prometheus/metrics` → Prometheus → 远程写 L5.2 运营后台 TSDB → 账单引擎按月聚合。
- **计费口径**：仅计 2xx 成功调用；4xx/5xx 不计费但计入审计与限流统计。
- **租户配额**：限流阈值由封装层（L0.11）按工作空间套餐下发，网关动态生效，超限返回 429 + Retry-After。

计量指标格式（被 L5.2 远程写拉取）：
```text
apisix_http_status{code="200",consumer="tenantA",route="r-tenantA-query-v1"}  98231
apisix_latency_bucket{le="100",consumer="tenantA",route="r-tenantA-query-v1"} 88012
```

## 6. 接口契约（Admin REST，仅平台内部调用）

```
POST /apisix/admin/routes         { uri, upstream, plugins, labels } → 发布路由
PUT  /apisix/admin/routes/{id}    { plugins, upstream }              → 更新/灰度
DELETE /apisix/admin/routes/{id}                                    → 下线
POST /apisix/admin/consumers      { username, plugins: {keycloak-auth} } → 绑定租户凭证
GET  /apisix/admin/metrics                                         → 计量指标(被 L5.2 拉取)
```
> Admin API 经 K8s NetworkPolicy 仅限服务目录与运营后台访问，不对外暴露；Data Plane 流量才对外。

## 7. 多租户隔离

- **路由隔离**：每条 Route 带 `labels.tenant=xxx`，租户只能命中本租户路由；跨租户调用 404。
- **鉴权隔离**：`keycloak-auth` 从 JWT 解析 tenant_id，与 Route label 不一致即 401。
- **限流隔离**：`limit-count` 的 key 含 tenant_id，租户间互不影响；套餐配额由 L0.11 下发。
- **计量隔离**：指标 label 带 tenant，L5.2 按租户分账，杜绝串账。

隔离加固：Data Plane Pod 注入 `tenant` 标签，K8s NetworkPolicy 限制同租户命名空间后端可达；Admin API 经 mTLS 仅服务目录可调，租户控制台只读自身路由。

## 8. 多环境适配

| 环境 | 部署形态 | 配置差异 | 说明 |
| --- | --- | --- | --- |
| 信创 | APISIX on SKE K8s（Helm） | etcd 本地三副本，纯 Go 插件 | 无 JVM，信创 OS/CPU 友好 |
| 本地数据中心 | APISIX on SKE K8s | 同上，裸金属/虚机 | 自建 etcd |
| 公有云 VM | APISIX on SKE K8s | 同上，**不启用云托管网关** | 仅用客户 VM，保持可迁移 |
| 私有云 | APISIX on SKE K8s | 同上，厂商私有云 | 客户私有云 VM |

> 关键约束：四环境 APISIX 配置（路由/插件/限流）完全一致，差异只在 K8s 节点与 etcd 拓扑；绝不引入云厂商托管网关，避免锁云。

> 部署配置：APISIX Helm values 见 `design/deploy/values/apisix-values.yaml`（与 keycloak-values.yaml 同构），包含：etcd 配置（三副本跨节点）、插件链启用（keycloak-auth/jwt-auth/limit-req/traffic-split/prometheus/kafka-logger/proxy-rewrite/api-breaker）、Prometheus ServiceMonitor 暴露、NetworkPolicy（Admin API 仅服务目录/运营后台可调、Data Plane 对外）、套餐分档资源配额（base/standard/flagship 三档）。四环境经 Profile 切换 etcd 拓扑与节点池配置，路由/插件配置完全一致。

## 9. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| etcd 单点故障 | 路由配置不可写，Data Plane 缓存仍可用 | etcd 三副本跨节点；Data Plane 本地缓存降级运行 |
| 插件链顺序错误 | 鉴权未过即计量/限流误判 | 插件顺序平台固化，租户不可改；CI 校验 Route 配置 |
| 限流配置漂移 | 租户超卖或被误限 | 限流阈值只由 L0.11 下发，控制台只读；变更走审批 |
| 计量数据丢失 | 账单不准 | Prometheus 远程写双写 + Kafka 审计兜底对账 |
| 信创 OpenResty 兼容性 | 插件不可用 | 部署前 preflight 探测，缺失能力降级并告警 |

## 10. 与 UI 的对应

控制台 v0.3「开放 API」页（发布/灰度/下线）、工作台"API 调用量 1.2M 次/日 · 限流 429 12 次"、运营后台账单的"按 API 计费"列均经此网关。本文件是其流量侧与计量侧契约依据。