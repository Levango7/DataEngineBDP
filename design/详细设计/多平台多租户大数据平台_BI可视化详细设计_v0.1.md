﻿# L4.4 BI 可视化（Superset + ECharts）· 详细设计（v0.1）

> 归属：多平台多租户大数据平台 · L4 数据开发与分析层
> 套餐：标准版起含（§11.5.1），基础版不含
> 对标：Apache Superset（BI 引擎）/ Apache ECharts（图表库）/ Tableau（商业 BI）
> 关联：L2.7 统一 SQL 网关（查询经网关）；L2.4 Trino（即席数据源）；L2.5 Doris（OLAP 数据源）；L3.7 多租户权限（行级继承）；L0.11 封装层（REST 收口）；L3.5 资产目录（建数据集时调 `/api/catalog/v1/assets/{id}` 获取元模型与敏感级别）

## 1. 定位与价值

BI 可视化是数据消费层的**最后一公里**：以拖拽式仪表盘承载业务自助分析，以自研大屏承载运维/经营监控场景，统一经封装层 REST 收口、查询统一经 L2.7 SQL 网关，数据源插拔、租户隔离。

客户价值：
- "自助分析"——业务人员拖拽建图表，不写 SQL 即可洞察；筛选器联动、跨仪表盘复用。
- "经营大屏"——自研 ECharts 大屏满足国产化/信创渲染、低延迟轮询、定制动效。
- "可嵌入"——仪表盘/单图表可 iframe 嵌入业务系统，带 token 鉴权。

平台价值：
- 引擎选型 Superset（成熟开源、Python 镜像信创可用）+ ECharts（国产开源、渲染可控）。
- 部署 Superset on K8s（Helm），与平台同底座；自研大屏为独立前端 + 转查询服务。
- 查询不直连引擎，统一走 L2.7 网关，配额/审计/行级权限一处收敛。
- 两套入口互补：Superset 偏自助分析（交互式、可钻取），大屏偏展示（高定制、低延迟、投屏）。

## 2. 总体架构

```text
┌──────── 用户入口（控制台 / 业务系统嵌入） ────────┐
│  拖拽式仪表盘(Superset)     经营大屏(自研 ECharts)    │
└──────────────┬──────────────────────┬───────────────┘
               ▼                      ▼
┌───── Superset on K8s（Helm） ─────┐  ┌── 自研大屏服务 ──┐
│  Dashboard/Chart/Filter · 元数据  │  │  轮询/WebSocket   │
│  SQLAlchemy 数据源 → L2.7 网关    │  │  查询 → L2.7 网关  │
└────────────────────┬───────────────┘  └──────────┬────────┘
                     └──────────┬───────────────────┘
                                ▼
               ┌──── L2.7 统一 SQL 网关（路由/限流/审计） ────┐
               │        Trino(即席)      Doris(OLAP/物化)      │
               └────────────────────┬─────────────────────────┘
                                    ▼
                        L2.1 统一存储（Iceberg / Doris 在线表）
```

## 3. Superset 集成

- **部署**：Helm Chart 部署至 SKE K8s，含 webserver、worker（Celery）、beat、Redis（信创用国产镜像）、PostgreSQL 元数据库（多租户共享 schema，按 `tenant_id` 隔离）。
- **数据源配置**：Superset SQLAlchemy 数据源不直连 Trino/Doris，而是配置为指向 **L2.7 网关 JDBC/HTTP 端点**，由网关注入租户、行级权限、限流；连接串模板 `jdbc:trino://gateway/{catalog}?tenant={t}`。对应 `superset-values.yaml` 中 `datasources.trino.host`/`datasources.doris.host`/`datasources.iceberg.host` 均应配置为 `sql-gateway:8080`（L2.7 网关地址），而非直连引擎。
- **数据集**：在 Superset 中建 Dataset，对应网关中已发布视图；建数据集时调 `GET /api/catalog/v1/assets/{id}`（L3.5 资产目录）获取字段元模型与敏感级别，敏感字段自动应用脱敏策略，避免绕过 L3.7；字段维度/度量由业务标注，缓存策略按数据集分级（实时/5min/1h）。
- **图表与筛选器**：支持 30+ 内置图表（表格/透视/折线/柱/饼/漏斗/桑基/地图）；筛选器绑定数据集字段，支持联动、原生时间粒度切换（日/周/月/季/年）；自定义插件图表挂载 ECharts 渲染，弥补 Superset 国产图表短板。
- **缓存**：查询结果缓存至 Redis，key 含 `tenant+dataset+sql_hash`，TTL 按数据集配置；缓存失效由网关在数据写入时主动通知（CDC 事件）。
- **告警与报表**：Superset Alerts & Reports（Celery beat）定时触发查询，命中阈值发邮件/IM；定时导出 PDF/PNG 推送订阅人。

## 4. 自研大屏（ECharts）

面向经营/运维大屏场景，Superset 仪表盘不满足定制动效与低延迟要求时启用：
- **前端**：Vue3 + ECharts，大屏布局编辑器（栅格拖拽、自适应缩放、定时轮询配置）。
- **数据通道**：
  - 轮询模式：前端按图表配置 `interval`（默认 30s）调封装层 `/api/bi/v1/chart/query`。
  - WebSocket 模式：大屏建立 WS 长连接，后端按数据源变更/定时推送增量，适用于实时指标。
- **查询执行**：大屏服务收到查询请求 → 调 L2.7 网关 → 路由 Doris 物化表（毫秒级）或 Trino（秒级）；查询结果可在大屏服务侧短缓存（5-10s）减少网关压力。
- **渲染**：ECharts 国产开源，信创浏览器（国产内核）兼容；动效/主题统一配置，导出 PNG/截图归档。
- **大屏编排**：支持多图表栅格、自由布局、组件分组、全局主题切换（浅色/深色）、自适应分辨率（1920/2K/4K）；预览与发布分离，发布版本只读可回滚。

## 5. 接口契约（经封装层 REST）

```
POST   /api/bi/v1/dashboards                { name, projectId, layout } → 建仪表盘
GET    /api/bi/v1/dashboards/{id}           → { meta, charts[], filters[] }
POST   /api/bi/v1/charts                    { datasetId, vizType, params } → 建图表
POST   /api/bi/v1/chart/query              { chartId, filters, timeRange } → { rows, cacheHit }
POST   /api/bi/v1/dashboards/{id}/publish   → 发布(只读快照)
POST   /api/bi/v1/dashboards/{id}/embed     { ttl } → { embedUrl, token }   // 嵌入令牌
GET    /api/bi/v1/dashboards/{id}/export    { format:png|pdf } → 二进制
POST   /api/bi/v1/screens                   { name, layout, charts[] } → 建大屏
WS     /api/bi/v1/screens/{id}/stream       → 实时推送图表增量
GET    /api/bi/v1/datasets/from-catalog     { assetId } → { dataset, fields, sensitiveLevels }  // 从 L3.5 资产目录拉取数据集元模型
```
> 所有接口经封装层鉴权（租户/项目/角色），查询体经网关下发引擎；嵌入令牌限时且仅读；大屏 WS 鉴权同 REST，断线自动重连。

## 6. 关键流程

```text
建数据集(选网关视图，调 L3.5 获取元模型与敏感级别) → 建图表(选类型/维度/度量) → 组装仪表盘(拖拽+筛选器)
        → 发布(只读快照) → 分享/嵌入(发令牌) → 业务系统 iframe 嵌入
```
1. **建数据集**：业务在控制台选已发布视图，调 `GET /api/bi/v1/datasets/from-catalog`（转 L3.5 `GET /api/catalog/v1/assets/{id}`）获取字段元模型与敏感级别；敏感字段自动应用脱敏策略；标注维度/度量、缓存策略；网关校验权限。
2. **建图表**：选图表类型，拖字段到行列/筛选区，预览走网关实时查询，命中缓存秒返。
3. **组装仪表盘**：拖图表入栅格，配筛选器联动、全局时间范围、布局自适应。
4. **发布**：生成只读快照版本，可回滚；分享链接或嵌入令牌；订阅人可设定时推送。
5. **嵌入**：业务系统 iframe 加载 `embedUrl?token=xxx`，令牌限时、只读、行级权限继承。
6. **大屏流程**：新建大屏 → 拖 ECharts 组件 → 绑定数据集+轮询/WS → 预览 → 发布到投屏终端。

## 7. 多租户与权限

- 仪表盘/图表/数据集按 `tenant_id + project_id` 隔离；Superset 元数据表加 `tenant_id` 列，查询强制带条件。
- 角色映射：平台角色 → Superset Role（Gamma/Alpha/Admin），封装层登录时下发。
- **行级权限继承 L3.7**：网关在 SQL 下发前注入行级过滤（如 `WHERE region IN (...)`），Superset/大屏无感。
- 嵌入令牌：绑定租户+用户+仪表盘+过期时间，禁止跨租户访问。
- 配额：每租户仪表盘/大屏数量、查询 QPS、导出次数受 L0.11 ResourceQuota 约束，超额拒绝并提示。

## 8. 多环境适配

| 环境 | Superset 镜像 | 元数据库 | Redis | 大屏浏览器 |
| --- | --- | --- | --- | --- |
| 信创 | 国产 Python 基础镜像 | PostgreSQL（信创版） | 国产 Redis | 国产内核浏览器 |
| 本地 | 官方镜像 | PostgreSQL | Redis | Chrome/Edge |
| 公有云 VM | 官方镜像 | 客户自提供 PG | 客户自提供 Redis | 标准 |
| 私有云 | 官方/客户镜像 | 客户 PG | 客户 Redis | 标准 |

> 关键约束：云环境不使用云托管 BI/缓存服务，自建 on K8s，保持可迁移、不锁云。

## 9. 部署配置

部署参数落地于 `design/deploy/values/superset-values.yaml`，由封装层 Helm Operator 渲染。关键配置块：

```yaml
# superset-values.yaml（节选）
superset:
  image: sq-superset:4.0-0.1.0
  version: "4.0.2"
  webserver: { replicas: 2 }
  celery:
    worker: { replicas: 2 }
    beat:   { replicas: 1 }
  cache: { type: "redis" }
  database: { type: "postgresql" }
  datasources:
    trino:   { host: "sql-gateway:8080" }   # L2.7 网关，不直连 trino-coord
    doris:   { host: "sql-gateway:8080" }   # L2.7 网关，不直连 doris-fe
    iceberg: { host: "sql-gateway:8080" }   # L2.7 网关
  visualization:
    customPlugins: ["echarts-line", "echarts-bar", "echarts-map", "echarts-funnel"]
  tierProfiles:
    base:      { enabled: false }   # 基础版不含 L4.4
    standard:  { enabled: true,  webserver.replicas: 2, resources: "medium" }
    flagship:  { enabled: true,  webserver.replicas: 4, resources: "large" }
```

> 注：`superset-values.yaml` 顶部注释统一为"§8.3 BI 可视化"（对齐模块名，原"数据可视化"已纠正）。
> 资源配额说明：`tierProfiles.*.resources` 中 quota 为单组件上限，平台总额度见 §11.5.2，由封装层 ResourceQuota 在租户级聚合约束（非所有组件同时满载，故单组件配额之和可略超平台总额度）。

## 10. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 大查询拖垮引擎 | 慢查询/超时 | 网关限流 + 查询超时 + Superset 异步执行 + 结果缓存 |
| 信创浏览器渲染兼容 | 大屏图表异常 | ECharts 国产内核适配测试，降级静态图 |
| 元数据库单点 | BI 不可用 | PG 主备 + 定期备份；元数据轻量，可快速恢复 |
| 嵌入令牌泄露 | 越权访问 | 令牌短 TTL + 绑定租户/用户 + 单次签发审计 |
| Superset 升级风险 | 元数据不兼容 | 升级前元数据快照；版本锁定，灰度滚动 |
| 大屏高频轮询压网关 | 网关过载 | 轮询间隔下限 5s + 大屏服务短缓存 + WS 优先 |
| 图表渲染大数据量 | 浏览器卡顿 | 后端分页/聚合下推；前端虚拟滚动 + 采样 |

## 11. 与 UI 的对应

控制台 v0.3「数据分析」页（仪表盘列表、新建拖拽编辑器、筛选器面板）、「经营大屏」页（大屏编辑/预览/轮询配置）、业务系统嵌入视图均基于此模块。本文件是其接口与权限契约依据。

> 版本：v0.1（初稿）；后续 v0.2 补充图表插件市场、AI 取数建议、移动端适配。