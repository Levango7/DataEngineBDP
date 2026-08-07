# SQ Asset Exchange (L5.6 数据资产流通)

数擎大数据平台 · L5 多租户产品层 · 数据资产流通平台（L5.6 / T039）。

将平台内**数据集 / 数据服务 / 数据模型 / 大模型**四类资产统一登记、上架、流通、变现，
构建"提供方—平台—消费方"三方市场，支持**定价（按次/按量/订阅）**与**自动结算分账**，
全过程**审计留痕**，提供**流通看板**。

## 功能

- **资产登记**：数据资产元数据登记（名称/描述/Schema/质量评分/分级），纳入资产目录
- **资产上架**：上架审核（合规/质量/分级检查），上架后可在资产市场检索
- **资产流通**：订阅 / 下载 / API 调用，支持三种定价（按次/按量/订阅），流通记录留痕
- **资产变现**：自动结算（订阅费/按次费/按量费），分账到数据提供方与平台
- **流通看板**：资产 Top N / 流通趋势 / 收益明细 / 分账明细（前端 Vue3 + ECharts）
- **审计留痕**：登记/上架/流通/变现/分账全过程审计日志，不可篡改（哈希链）

## 目录结构

```text
platform/asset-exchange/
├── asset_exchange/
│   ├── api/                  # FastAPI 路由层
│   │   ├── app.py            # 应用工厂
│   │   └── routers/          # 路由模块（assets/subscriptions/audit/health）
│   ├── config/               # 配置（环境变量驱动）
│   ├── interfaces/           # 仓储抽象接口
│   ├── models/               # Pydantic 数据模型
│   │   ├── asset.py          # 资产模型
│   │   ├── subscription.py   # 订阅模型
│   │   ├── delivery.py       # 交付模型
│   │   ├── billing.py        # 计费模型
│   │   ├── settlement.py     # 结算与分账模型
│   │   └── audit.py          # 审计日志模型
│   ├── repositories/         # 仓储实现
│   │   ├── mock/             # 内存 Mock 实现
│   │   └── sqlite/           # SQLite 持久化实现
│   └── services/             # 业务编排层
│       ├── asset_service.py       # 资产管理（登记/审核/上架/下载/调用）
│       ├── subscription_service.py # 订阅管理
│       ├── delivery_service.py    # 交付管理
│       ├── billing_service.py     # 计费服务（三种定价）
│       ├── settlement_service.py  # 结算服务（自动结算）
│       ├── allocation_service.py  # 分账服务
│       └── audit_service.py       # 审计留痕服务
├── tests/                    # pytest 单元测试
├── main.py                   # 入口
├── pyproject.toml
└── requirements.txt

frontend/asset-exchange-dashboard/   # 流通看板前端（Vue3 + ECharts）
├── src/
│   ├── views/               # 页面：Dashboard/AssetList/RegisterForm/SettlementList
│   ├── components/          # ECharts 图表组件
│   ├── api/                 # API 客户端
│   └── router/              # 路由
├── package.json
└── vite.config.ts

tests/integration/docker/test_asset_exchange.py   # 集成测试（≥20 用例）
```

## 快速开始

```bash
# 安装
pip install -e ".[test]"

# 启动（默认 SQLite 模式，端口 8087）
python main.py

# 启动（Mock 模式，便于开发）
ASSET_EXCHANGE_STORE_TYPE=mock python main.py

# 测试
python -m pytest tests/
```

## API 端点

### 资产管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/v1/assets/register | 资产登记（元数据登记，状态置为 DRAFT） |
| POST | /api/v1/assets/{id}/audit | 资产审核（合规/质量/分级检查） |
| POST | /api/v1/assets/{id}/publish | 资产上架（自动审核检查） |
| POST | /api/v1/assets | 上架资产（兼容旧接口，等价于 register + publish） |
| GET  | /api/v1/assets | 浏览资产市场 |
| GET  | /api/v1/assets/{id} | 资产详情 |
| PUT  | /api/v1/assets/{id} | 更新资产 |
| DELETE | /api/v1/assets/{id} | 下架资产 |
| GET  | /api/v1/assets/{id}/usage | 使用统计 |

### 资产流通

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/v1/assets/{id}/subscribe | 订阅资产 |
| POST | /api/v1/assets/{id}/download | 下载资产 |
| POST | /api/v1/assets/{id}/invoke | API 调用资产 |
| GET  | /api/v1/assets/{id}/subscriptions | 资产订阅列表 |
| POST | /api/v1/subscriptions/{id}/approve | 审批订阅 |
| POST | /api/v1/subscriptions/{id}/deliver | 交付数据 |
| GET  | /api/v1/subscriptions/{id}/delivery-status | 交付状态 |
| POST | /api/v1/subscriptions/{id}/charge | 计费（辅助端点） |

### 资产变现与分账

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET  | /api/v1/assets/{id}/billing | 计费记录汇总 |
| POST | /api/v1/assets/{id}/settle | 资产结算（自动汇总计费，计算分成） |
| GET  | /api/v1/assets/{id}/settlements | 结算列表 |
| POST | /api/v1/assets/{id}/allocate | 资产分账（分账到提供方与平台） |
| GET  | /api/v1/assets/{id}/allocations | 分账列表 |

### 审计留痕

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET  | /api/v1/assets/{id}/audit-logs | 资产审计日志 |
| GET  | /api/v1/audit-logs | 列出审计日志 |
| GET  | /api/v1/audit-logs/{id} | 审计日志详情 |
| GET  | /api/v1/audit-logs/integrity | 审计日志完整性校验（哈希链） |

### 健康检查

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /health | 健康检查 |

## 定价方式

| 模式 | 枚举值 | 说明 | 计算公式 |
| --- | --- | --- | --- |
| 按次 | `by_call` | 按调用量计费 | amount = unit_price × usage |
| 按量 | `by_data` | 按数据量计费（千行） | amount = unit_price × usage / 1000 |
| 订阅 | `subscription` | 周期订阅固定费用 | amount = unit_price × usage（期数） |
| 按时间 | `by_time` | 按时间计费（月） | amount = unit_price × usage |
| 一次性 | `one_time` | 一次性买断 | amount = unit_price |

## 分账规则

- 默认分成：提供方 80% / 平台 20%
- 内部租户间流通：成本系数 0.3，仅记成本不真扣费
- 分成比例可通过环境变量配置：
  - `ASSET_EXCHANGE_PROVIDER_SHARE=0.8`
  - `ASSET_EXCHANGE_PLATFORM_SHARE=0.2`
- 结算时也可通过 `SettleRequest` 临时指定分成比例

## 审计留痕

- 全过程审计日志：登记/上架/流通/变现/分账
- 不可篡改：基于哈希链（每条日志包含前一条的哈希）
- 与 Phase 1 SecurityFacade T021 集成（通过 `ASSET_EXCHANGE_AUDIT_FACADE_URL` 配置）
- 完整性校验：`GET /api/v1/audit-logs/integrity`

## 配置项

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| ASSET_EXCHANGE_HOST | 0.0.0.0 | 监听地址 |
| ASSET_EXCHANGE_PORT | 8087 | 监听端口 |
| ASSET_EXCHANGE_STORE_TYPE | sqlite | 存储类型: mock / sqlite |
| ASSET_EXCHANGE_DB_PATH | data/asset_exchange.db | SQLite 数据库路径 |
| ASSET_EXCHANGE_PROVIDER_SHARE | 0.8 | 提供方收益分成 |
| ASSET_EXCHANGE_PLATFORM_SHARE | 0.2 | 平台抽成 |
| ASSET_EXCHANGE_INTERNAL_FACTOR | 0.3 | 内部结算成本系数 |
| ASSET_EXCHANGE_AUDIT_FACADE_URL | (无) | Phase 1 SecurityFacade T021 URL |
| ASSET_EXCHANGE_PLATFORM_ACCOUNT_ID | platform-main | 平台分账账户 ID |

## 设计依据

- `design/详细设计/多平台多租户大数据平台_数据资产流通详细设计_v0.1.md`
- `design/工程交付计划_缺口补全_v1.0.md` — Phase 4 任务 P4-T4
- Phase 2 Batch 1b 任务 T039
