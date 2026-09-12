# 模型仓库注册部署服务

> T033 模型仓库：微调后模型注册 + 一键部署 + 部署管理 + 健康检查

## 1. 概述

本服务提供模型仓库管理与一键部署能力：
- **模型注册**：微调后模型注册到仓库（模型名+版本+路径+元数据）
- **一键部署**：部署到推理服务（vLLM/Triton/简化 Docker 容器）
- **部署管理**：查询部署状态、停止部署、更新部署（扩缩容）
- **健康检查**：部署后模型可调用性验证

## 2. API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/registry/models` | 注册模型 |
| GET | `/api/v1/registry/models` | 查询模型列表 |
| GET | `/api/v1/registry/models/{name}` | 查询模型详情 |
| GET | `/api/v1/registry/models/{name}/versions` | 查询模型版本历史 |
| POST | `/api/v1/registry/deployments` | 创建部署 |
| GET | `/api/v1/registry/deployments` | 查询部署列表 |
| GET | `/api/v1/registry/deployments/{id}` | 查询部署详情 |
| DELETE | `/api/v1/registry/deployments/{id}` | 停止部署 |
| PUT | `/api/v1/registry/deployments/{id}` | 更新部署 |
| GET | `/api/v1/registry/deployments/{id}/health` | 健康检查 |
| GET | `/api/v1/registry/stats` | 服务统计 |

## 3. 配置

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `REGISTRY_PORT` | 18089 | 服务端口 |
| `REGISTRY_MOCK_MODE` | true | Mock 模式 |

## 4. 启动

```bash
cd platform/registry
pip install -r requirements.txt
REGISTRY_MOCK_MODE=true uvicorn app.main:app --host 0.0.0.0 --port 18089
```