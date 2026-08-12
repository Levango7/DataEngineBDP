# LLMOps Platform (L4.5.3)

> 数据引擎大数据平台 · 智能数据层 · LLMOps 运营平台
> 对齐 `design/详细设计/多平台多租户大数据平台_智能数据层详细设计_v0.1.md` L4.5.5

## 定位

从微调、评估到部署的一体化大模型运营；基座模型与领域模型统一纳管。
复用 L4.5.2 机器学习 MLflow Tracking/Registry 底座，在其之上扩展大模型特有
的评估指标（幻觉率、对比基座提升）与端点生命周期管理。

## 架构

采用 **Mock + 接口抽象** 策略：

- `interfaces/`：ModelStore / ModelTrainer / ModelDeployer / ModelMonitor 四大抽象接口
- `repositories/mock/`：内存态 Mock 实现（默认，用于测试与无 MLflow 环境）
- `repositories/mlflow/`：真实 MLflow SDK 实现（通过配置开关注入）
- `services/`：业务编排层
- `api/`：FastAPI 路由层

## 目录结构

```
platform/llmops/
├── main.py                      # FastAPI 启动入口
├── pyproject.toml               # 包定义与依赖
├── requirements.txt             # 依赖清单
├── Dockerfile                   # 容器镜像
├── llmops/
│   ├── __init__.py
│   ├── models/                  # Pydantic 数据模型
│   ├── config/                  # 配置（环境变量）
│   ├── interfaces/              # 抽象接口
│   ├── repositories/
│   │   ├── mock/                # Mock 实现
│   │   └── mlflow/              # MLflow 实现
│   ├── services/                # 业务服务层
│   └── api/                     # FastAPI 路由
└── tests/                       # pytest 单元测试
```

## 快速开始

```bash
# 安装依赖
pip install -e ".[test]"

# 启动服务（默认 Mock 模式）
python main.py

# 切换到 MLflow 模式
LLMOPS_STORE_TYPE=mlflow LLMOPS_MLFLOW_URI=http://mlflow:5000 python main.py

# 运行测试
python -m pytest tests/ -v
```

## API 端点

启动后访问 `http://localhost:8080/docs` 查看自动生成的 OpenAPI 文档。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/v1/models | 注册模型 |
| GET | /api/v1/models | 列出模型 |
| GET | /api/v1/models/{id} | 获取模型详情 |
| DELETE | /api/v1/models/{id} | 删除模型 |
| GET | /api/v1/models/{id}/versions | 模型版本列表 |
| POST | /api/v1/training/jobs | 创建训练任务 |
| GET | /api/v1/training/jobs | 列出训练任务 |
| GET | /api/v1/training/jobs/{id} | 训练状态 |
| DELETE | /api/v1/training/jobs/{id} | 取消训练 |
| POST | /api/v1/deployments | 部署模型 |
| GET | /api/v1/deployments | 列出部署 |
| GET | /api/v1/deployments/{id} | 部署状态 |
| DELETE | /api/v1/deployments/{id} | 卸载部署 |
| GET | /api/v1/deployments/{id}/metrics | 模型指标 |
| GET | /api/v1/deployments/{id}/latency | 延迟统计 |
| GET | /health | 健康检查 |

## 配置

通过环境变量配置（前缀 `LLMOPS_`）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| LLMOPS_HOST | 0.0.0.0 | 监听地址 |
| LLMOPS_PORT | 8080 | 监听端口 |
| LLMOPS_LOG_LEVEL | info | 日志级别 |
| LLMOPS_STORE_TYPE | mock | 存储类型: mock / mlflow |
| LLMOPS_MLFLOW_URI | http://localhost:5000 | MLflow Tracking URI |
| LLMOPS_MLFLOW_REGISTRY_URI | (同 MLFLOW_URI) | MLflow Registry URI |