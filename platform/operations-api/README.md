# 合同管理服务 · operations-api

> P-04 合同交付实体（代码骨架）

> **双实现并存（2026-11-06 审计标注）：本目录之外另有一份同域实现**
> `design/deploy/services/operations/main.py`（239 行，FastAPI 单文件，路由
> `/api/v1/admin/*`：租户/套餐/计量/账单/看板，内存态 TENANTS + 硬编码 PACKAGES），
> 且被同目录 `k8s.yaml:32` 引用为部署镜像入口；本目录是合同 CRUD 正版骨架
> （路由 `/api/v1/contracts/*`，带 app.py/api/jwt_auth/repositories 结构），
> 但**无 Dockerfile、不被 compose/chart/k8s 引用**。两边路由不相交、各自演进，
> 谁为正版、是否合并属产品裁决，见 `docs/KNOWN-FAILURES.md` #117 待裁项。
> 在裁决前请勿只改一边而不同步另一边。反向标注见
> `design/deploy/services/operations/README.md`。

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