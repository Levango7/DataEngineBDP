# SQ Asset Exchange (L5.6 数据资产流通)

数擎大数据平台 · L5 多租户产品层 · 数据资产流通平台（L5.6）。

将平台内**数据集 / 数据服务 / 数据模型 / 大模型**四类资产统一登记、上架、流通、变现，
构建"提供方—平台—消费方"三方市场。

## 功能

- **资产管理**：上架 / 下架 / 浏览 / 详情 / 更新
- **订阅审批**：订阅 / 审批 / 订阅列表
- **数据交付**：API 交付 / 文件交付 / 数据库直连交付
- **计量计费**：按调用量 / 数据量 / 时间计费
- **使用统计**：订阅量、调用量、收益看板

## 目录结构

```text
platform/asset-exchange/
├── asset_exchange/
│   ├── api/                  # FastAPI 路由层
│   │   ├── app.py            # 应用工厂
│   │   └── routers/          # 路由模块（assets/subscriptions/health）
│   ├── config/               # 配置（环境变量驱动）
│   ├── interfaces/           # 仓储抽象接口
│   ├── models/               # Pydantic 数据模型
│   ├── repositories/         # 仓储实现
│   │   └── mock/             # 内存 Mock 实现
│   └── services/             # 业务编排层
├── tests/                    # pytest 单元测试
├── main.py                   # 入口
├── pyproject.toml
└── requirements.txt
```

## 快速开始

```bash
# 安装
pip install -e ".[test]"

# 启动（默认 Mock 模式，端口 8086）
python main.py

# 测试
python -m pytest tests/
```

## API 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/v1/assets | 上架资产 |
| GET  | /api/v1/assets | 浏览资产市场 |
| GET  | /api/v1/assets/{id} | 资产详情 |
| PUT  | /api/v1/assets/{id} | 更新资产 |
| DELETE | /api/v1/assets/{id} | 下架资产 |
| POST | /api/v1/assets/{id}/subscribe | 订阅资产 |
| GET  | /api/v1/assets/{id}/subscriptions | 资产订阅列表 |
| POST | /api/v1/subscriptions/{id}/approve | 审批订阅 |
| POST | /api/v1/subscriptions/{id}/deliver | 交付数据 |
| GET  | /api/v1/subscriptions/{id}/delivery-status | 交付状态 |
| GET  | /api/v1/assets/{id}/billing | 计费记录 |
| GET  | /api/v1/assets/{id}/usage | 使用统计 |
| GET  | /health | 健康检查 |

## 设计依据

- `design/详细设计/多平台多租户大数据平台_数据资产流通详细设计_v0.1.md`
- `design/工程交付计划_缺口补全_v1.0.md` — Phase 4 任务 P4-T4