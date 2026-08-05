# Business Portal (L5.4)

数擎大数据平台 · L5.4 对内业务线门户。

## 定位

以"业务线-团队-项目"组织视图复用平台全部能力，免计费或走内部结算，不承诺 SLA，资源受部门预算软约束。仅适用于**标准版+旗舰版**。

## 关键特性

- **多业务线隔离**：数据隔离 + 权限隔离，跨业务线默认不可见
- **业务线管理 API**：CRUD 全套
- **业务线仪表盘**：KPI 卡片 + 趋势图 + 实时监控 + TopN 项目
- **工作台**：待办 + 常用工具 + 最近任务
- **数据目录**：树形结构，业务线隔离
- **BI 报表**：CRUD + 业务线隔离
- **内部结算**：成本 × 0.3 推财务（§11.5 定价模型）

## 项目结构

```text
platform/business-portal/
├── business_portal/
│   ├── api/
│   │   ├── app.py                # FastAPI 应用工厂
│   │   └── routers/
│   │       ├── business_lines.py # 业务线 CRUD
│   │       ├── dashboard.py      # 数据概览
│   │       ├── workbench.py      # 工作台
│   │       ├── catalog.py        # 数据目录
│   │       ├── reports.py        # BI 报表
│   │       ├── health.py         # 健康检查
│   │       └── deps.py           # 通用依赖
│   ├── config/
│   │   └── settings.py           # 环境变量配置
│   ├── models/                   # 数据模型
│   ├── interfaces/               # 仓储接口抽象
│   ├── repositories/             # Mock 仓储实现
│   └── services/                 # 服务层 + 注册表
├── tests/                        # 单元测试
├── main.py                       # 入口
├── pyproject.toml
└── requirements.txt
```

## API 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /health | 健康检查 |
| POST | /api/v1/business-lines | 创建业务线 |
| GET | /api/v1/business-lines | 列出业务线 |
| GET | /api/v1/business-lines/{id} | 业务线详情 |
| PUT | /api/v1/business-lines/{id} | 更新业务线 |
| DELETE | /api/v1/business-lines/{id} | 删除业务线 |
| GET | /api/v1/business-lines/{id}/dashboard | 数据概览 |
| GET | /api/v1/business-lines/{id}/workbench | 工作台 |
| GET | /api/v1/business-lines/{id}/catalog | 数据目录 |
| POST | /api/v1/business-lines/{id}/catalog | 添加目录节点 |
| DELETE | /api/v1/business-lines/{id}/catalog/{node_id} | 删除目录节点 |
| GET | /api/v1/business-lines/{id}/reports | BI 报表列表 |
| POST | /api/v1/business-lines/{id}/reports | 创建报表 |
| GET | /api/v1/business-lines/{id}/reports/{report_id} | 报表详情 |
| PUT | /api/v1/business-lines/{id}/reports/{report_id} | 更新报表 |
| DELETE | /api/v1/business-lines/{id}/reports/{report_id} | 删除报表 |

## 启动

```bash
# 安装依赖
pip install -e ".[test]"

# 启动服务（默认 Mock 模式，监听 0.0.0.0:8084）
python main.py

# 运行测试
python -m pytest tests/
```

## 多业务线隔离设计

### 数据隔离

- 所有数据按 `blId` 分桶存储
- 跨业务线访问默认拒绝
- 数据目录节点 `blId` 必须与目标业务线一致
- BI 报表 `blId` 决定归属，跨业务线查询返回 403

### 权限隔离

- 业务线详情查询校验 `X-User-Id` 是否在 `memberIds` / `ownerIds` 中
- 业务线更新/删除仅 `ownerIds` 中的用户可操作
- 列出业务线支持 `memberId` 过滤，仅返回该用户可见的业务线
- 跨业务线访问抛 `PermissionDeniedError` → HTTP 403

## 参考

- [详细设计](../../design/详细设计/多平台多租户大数据平台_对内业务线门户详细设计_v0.1.md)
- [工程交付计划](../../design/工程交付计划_缺口补全_v1.0.md) — Phase 4 任务 P4-T2