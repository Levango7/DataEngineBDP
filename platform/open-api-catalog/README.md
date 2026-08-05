# Open API Service Catalog (L5.5)

数擎大数据平台 · 开放 API 服务目录

## 定位

将平台数据能力封装为 REST/gRPC API，经 APISIX 网关对外暴露；配套服务目录支持浏览、搜索、订阅、评分，形成"数据即 API、API 即资产"的开放生态。

## 功能

- **API 注册**：定义 API 契约（OpenAPI 3.0 Spec），含参数、响应、鉴权方式
- **发布流程**：定义 → 安全审核 → 审批 → 发布到网关 → 运行 → 废弃 → 归档
- **服务目录**：分类、标签、全文搜索、SLA 等级、计费策略
- **订阅管理**：消费者申请 → 提供方审批 → 发放 AK/SK → 调用
- **鉴权**：API Key / JWT / OAuth2 Client Credentials
- **限流**：令牌桶（API 级）+ 滑动窗口（订阅级配额）
- **计量计费**：按次 / 按量 / 按月包，自动汇聚到账单
- **APISIX 路由自动配置**：生成 APISIX Route + 插件链（认证/限流/熔断/计量/日志）
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
| GET | /api/v1/subscriptions | 列出订阅 |
| GET | /api/v1/subscriptions/{id} | 订阅详情 |
| POST | /api/v1/subscriptions/{id}/approve | 审批订阅 |
| POST | /api/v1/subscriptions/{id}/suspend | 暂停订阅 |
| POST | /api/v1/subscriptions/{id}/resume | 恢复订阅 |
| POST | /api/v1/subscriptions/{id}/revoke | 吊销订阅 |
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