# 合同管理服务 · operations-api

> P-04 合同交付实体（代码骨架）

## 概述

合同管理服务是运营后台（operations）的扩展模块，提供合同全生命周期管理 CRUD API 骨架。

## 目录结构

```
platform/operations-api/
├── operations_api/
│   ├── __init__.py
│   ├── app.py                    # FastAPI 应用入口
│   ├── models/
│   │   ├── __init__.py
│   │   └── contract.py           # 合同实体模型
│   ├── api/
│   │   ├── __init__.py
│   │   └── routers/
│   │       ├── __init__.py
│   │       └── contracts.py      # 合同 CRUD API
│   ├── services/
│   │   └── __init__.py           # 合同业务逻辑
│   └── repositories/
│       └── __init__.py           # 合同仓储（内存骨架）
├── tests/
├── pyproject.toml
└── requirements.txt
```

## 合同实体模型

| 字段 | 类型 | 说明 |
|------|------|------|
| id | str (UUID) | 合同唯一标识 |
| tenantId | str | 租户 ID |
| contractNo | str | 合同编号 |
| partyA | str | 甲方 |
| partyB | str | 乙方 |
| startDate | date | 生效日期 |
| endDate | date | 到期日期 |
| status | ContractStatus | 状态（草稿/生效/暂停/终止/到期） |
| amount | float | 合同金额 |
| type | ContractType | 类型（订阅/一次性/溢出/混合） |
| package | str | 套餐档位 |

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/contracts | 创建合同 |
| GET | /api/v1/contracts | 查询合同列表 |
| GET | /api/v1/contracts/{id} | 查询合同详情 |
| PUT | /api/v1/contracts/{id} | 更新合同 |
| DELETE | /api/v1/contracts/{id} | 终止合同 |
| POST | /api/v1/contracts/{id}/sign | 签署合同 |
| POST | /api/v1/contracts/{id}/terminate | 终止合同 |

## 待实现（TODO）

- [ ] Bearer Token 鉴权
- [ ] PostgreSQL 持久化
- [ ] 电子签章集成
- [ ] 合同审批流程
- [ ] 到期自动提醒
- [ ] 合同与租户/套餐联动
- [ ] 合同金额与账单关联