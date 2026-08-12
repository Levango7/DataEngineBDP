# ML Platform (L4.5.6)

数据引擎大数据平台 · 智能数据层 · 机器学习平台

## 定位

模型全生命周期底座：实验追踪 → 模型注册 → 训练/评估 → 在线预测。
对齐 MLflow（开源 MLOps 标准）/ Spark MLlib（分布式训练）/ Scikit-learn（单机训练）。

## 架构

采用 **Mock + 接口抽象** 策略：

- `MLBackend` 抽象接口 + `MockMLBackend` / `SklearnMLBackend` 实现
- `FeatureStore` 抽象接口 + `MockFeatureStore` 实现
- `ExperimentStore` 抽象接口 + `MockExperimentStore` 实现

真实 Spark MLlib / Scikit-learn 通过配置注入，便于离线测试与多环境适配。

## 目录结构

```text
ml-platform/
├── main.py                          # FastAPI 启动入口
├── requirements.txt
├── pyproject.toml
├── Dockerfile
├── ml_platform/
│   ├── __init__.py
│   ├── api/                         # FastAPI 路由层
│   │   ├── app.py                   # 应用工厂
│   │   └── routers/                 # 路由模块
│   ├── config/                      # 配置（环境变量驱动）
│   ├── interfaces/                  # 抽象接口
│   │   ├── backend.py               # MLBackend
│   │   ├── feature_store.py         # FeatureStore
│   │   └── experiment_store.py      # ExperimentStore
│   ├── models/                      # Pydantic 数据模型
│   ├── repositories/                # 接口实现
│   │   ├── mock/                    # Mock 实现（内存）
│   │   └── sklearn/                 # Scikit-learn 实现
│   └── services/                    # 业务编排层
└── tests/                           # pytest 单元测试
```

## 快速开始

```bash
# 安装依赖
pip install -e .[test]

# 启动服务（默认 Mock 模式）
python main.py

# 切换到 Scikit-learn 后端
ML_BACKEND_TYPE=sklearn python main.py

# 运行测试
python -m pytest tests/
```

## API 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /health | 健康检查 |
| POST | /api/v1/experiments | 创建实验 |
| GET | /api/v1/experiments | 列出实验 |
| GET | /api/v1/experiments/{id} | 实验详情 |
| POST | /api/v1/experiments/{id}/metrics | 记录指标 |
| POST | /api/v1/experiments/{id}/params | 记录参数 |
| POST | /api/v1/training/jobs | 创建训练任务 |
| GET | /api/v1/training/jobs/{id} | 训练状态 |
| POST | /api/v1/models/{id}/predict | 模型预测 |
| POST | /api/v1/models/{id}/evaluate | 模型评估 |
| POST | /api/v1/feature-groups | 创建特征组 |
| GET | /api/v1/feature-groups/{name}/features/{entity_id} | 获取特征 |
| PUT | /api/v1/feature-groups/{name}/features/{entity_id} | 写入特征 |

自动文档：`/docs`（Swagger）, `/redoc`（ReDoc）。