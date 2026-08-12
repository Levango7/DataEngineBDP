# Open API Service Catalog (L5.5)

数据引擎大数据平台 · 开放 API 服务目录

## 定位

将平台数据能力封装为 REST/gRPC API，经 APISIX 网关对外暴露；配套服务目录支持浏览、搜索、订阅、评分，形成"数据即 API、API 即资产"的开放生态。

## 功能

- **API 注册**：定义 API 契约（OpenAPI 3.0 Spec），含参数、响应、鉴权方式
- **一键生成 API**：从 SQL / 模型 / 函数三种来源自动生成 RESTful API 契约
  - SQL → API：解析参数（`:param` / `${param}` / `@param`）、自动推断字段类型、禁止 DDL/DML
  - 模型 → API：支持推理 / 嵌入 / 微调三种调用模式，映射运行时（vLLM / TGI / Triton）
  - 函数 → API：映射 UDF/UDAF 为 HTTP 端点，自动生成入参 Schema
- **发布流程**：定义 → 安全审核 → 审批 → 发布到网关 → 运行 → 废弃 → 归档
- **服务目录**：分类、标签、全文搜索、SLA 等级、计费策略
- **订阅管理**：消费者申请 → 提供方审批 → 发放 AK/SK → 调用
- **鉴权**：API Key / JWT / OAuth2 Client Credentials
- **限流**：令牌桶（API 级）+ 滑动窗口（订阅级配额），支持 QPS / 并发 / 突发三级配置
- **计量计费**：按次（by_call）/ 按量（by_bytes）/ 按月包（monthly_package），自动汇聚到账单
- **APISIX 路由自动配置**：生成 APISIX Route + 插件链（认证→限流→熔断→重写→计量→日志）
- **API 文档自动生成**：OpenAPI 3.0 Spec + Markdown

## API 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/v1/apis | 注册 API |
| GET | /api/v1/apis | 列出 API |
| GET | /api/v1/apis/{id} | API 详情 |
| PUT | /api/v1/apis/{id} | 更新 API |
| DELETE | /api/v1/apis/{id} | 注销 API |
| POST | /api/v1/apis/{id}/submit-review | 提交安全审核 |
| POST | /api/v1/apis/{id}/approve | 审核通过 |
| POST | /api/v1/apis/{id}/reject | 审核驳回 |
| POST | /api/v1/apis/{id}/publish | 发布到网关 |
| POST | /api/v1/apis/{id}/deprecate | 废弃 |
| POST | /api/v1/apis/{id}/archive | 归档 |
| POST | /api/v1/apis/{id}/subscribe | 申请订阅 |
| GET | /api/v1/apis/{id}/subscribers | 订阅者列表 |
| POST | /api/v1/apis/{id}/call | 调用 API |
| GET | /api/v1/apis/{id}/metrics | 调用计量 |
| GET | /api/v1/apis/{id}/docs | API 文档 |
| GET | /api/v1/apis/{id}/apisix-config | APISIX 路由配置 |
| POST | /api/v1/apis/{id}/apisix-deploy | 部署 APISIX 路由 |
| POST | /api/v1/apis/generate/sql | 从 SQL 一键生成 API |
| POST | /api/v1/apis/generate/model | 从模型一键生成 API |
| POST | /api/v1/apis/generate/function | 从函数一键生成 API |
| GET | /api/v1/apis/generate/options | 获取生成选项（数据源/模型类型/运行时） |
| GET | /api/v1/subscriptions | 列出订阅 |
| GET | /api/v1/subscriptions/{id} | 订阅详情 |
| POST | /api/v1/subscriptions/{id}/approve | 审批订阅 |
| POST | /api/v1/subscriptions/{id}/suspend | 暂停订阅 |
| POST | /api/v1/subscriptions/{id}/resume | 恢复订阅 |
| POST | /api/v1/subscriptions/{id}/revoke | 吊销订阅 |
| POST | /api/v1/subscriptions/{id}/keys | 重新颁发 AK/SK |
| GET | /api/v1/subscriptions/{id}/keys | 查询 AK/SK |
| PUT | /api/v1/subscriptions/{id}/rate-limit | 配置限流（QPS/并发/突发） |
| GET | /api/v1/subscriptions/{id}/rate-limit | 查询限流配置 |
| PUT | /api/v1/apis/{id}/billing | 配置计费策略（按次/按量/月包） |
| GET | /api/v1/apis/{id}/billing | 查询计费策略 |
| GET | /health | 健康检查 |

## 快速开始

```bash
# 安装依赖
pip install -e ".[test]"

# 启动服务
python main.py

# 运行测试
python -m pytest tests/ -v
```

## 配置

环境变量（前缀 `OPENAPI_CATALOG_`）：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| OPENAPI_CATALOG_HOST | 0.0.0.0 | 监听地址 |
| OPENAPI_CATALOG_PORT | 8090 | 监听端口 |
| OPENAPI_CATALOG_API_PREFIX | /api/v1 | API 路由前缀 |
| OPENAPI_CATALOG_APISIX_ADMIN_URL | http://apisix-admin:9180/apisix/admin | APISIX Admin API |
| OPENAPI_CATALOG_KEYCLOAK_URL | http://keycloak:8080/realms/shuqing | Keycloak Realm |
| OPENAPI_CATALOG_DEFAULT_QUOTA | 100 | 默认订阅配额（次/分钟） |
| OPENAPI_CATALOG_DEFAULT_RATE_LIMIT | 100 | 默认限流（次/秒） |

## 架构

```text
┌──────── 消费者（同平台租户 / 外部系统） ────────┐
│   浏览目录 → 申请订阅 → 持 AK/SK 调用           │
└──────────────────────┬─────────────────────────┘
                       ▼
┌──────── X4 APISIX 网关（认证/限流/计量/路由） ────────┐
│  Keycloak 校验 JWT/API Key → 插件链(限流/熔断/计量)    │
└──────┬──────────────┬──────────────┬──────────────┬──┘
       ▼              ▼              ▼              ▼
   L2.4 Trino     L2.5 Doris     L4.5.6 大模型   自定义函数
                       ▲
                       │ 注册/路由下发
┌──────── API 发布管理 + 服务目录 ────────┐
│  定义→安全审核→审批→发布→版本→监控→下线  │
│  目录：分类/标签/全文+语义搜索/评分       │
└──────────────────────────────────────────┘
```

## 技术栈

- Python 3.10+
- FastAPI 0.110
- Pydantic 2.6
- pytest 8.1

## APISIX 插件链

`apisix/` 目录包含网关插件链配置，插件执行顺序：认证 → 限流 → 熔断 → 重写 → 计量 → 日志。

| 文件 | 说明 |
| --- | --- |
| `apisix/apisix-config.yaml` | APISIX 全局配置，插件启用列表与执行顺序 |
| `apisix/routes.yaml` | 路由模板（SQL/模型/函数三种 upstream + 健康检查） |
| `apisix/plugins/key-auth.yaml` | API Key 认证插件，消费方 AK/SK 校验 |
| `apisix/plugins/limit-req.yaml` | 限流插件，按 SLA 等级（Platinum/Gold/Silver）分级限流 |
| `apisix/plugins/metering.yaml` | 计量计费插件，支持 by_call / by_bytes / monthly_package 三种策略 |

## 前端

`frontend/open-api-dashboard/` 提供 Vue3 服务目录管理界面：

- **服务目录**（CatalogView）：分类筛选、标签搜索、SLA 等级、计费策略展示
- **一键生成**（GenerateView）：SQL / 模型 / 函数三种 Tab，表单驱动生成 API
- **订阅管理**（SubscriptionsView）：申请订阅、审批、AK/SK 颁发、限流配置
- **用量看板**（DashboardView）：ECharts 调用量趋势、计费汇总、Top API 排行
- **API 详情**（ApiDetailView）：契约预览、文档、APISIX 配置、调用示例

技术栈：Vue 3 + Vite + Pinia + Vue Router + Element Plus + ECharts + Axios。

## 集成测试

`tests/integration/docker/test_open_api_catalog.py` 包含 36 个集成测试用例，覆盖：

- API 一键生成（SQL/模型/函数三种来源）
- 服务目录 CRUD 与状态流转
- 订阅审批与 AK/SK 颁发
- 限流触发（429 Too Many Requests）
- 三种计费策略计量准确性
- APISIX 插件链配置下发

测试支持 TestClient 模式（无需 Docker）和 HTTP 模式（对接真实容器）两种运行方式。