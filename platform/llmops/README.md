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
| LLMOPS_FRONTEND_STORE | memory | 前端视图存储: memory / postgresql |
| LLMOPS_FRONTEND_DATABASE_URL | (空) | PostgreSQL DSN（postgresql 模式必填；生产经 Secret 注入） |
| LLMOPS_ENV | development | 运行环境: development / test / production（production 禁止内存回退） |
| LLMOPS_INFERENCE_ORCHESTRATOR | contract | 推理编排: contract / kubernetes（production 必须 kubernetes） |
| LLMOPS_INFERENCE_NAMESPACE | llmops-inference | 推理服务命名空间 |
| LLMOPS_INFERENCE_IMAGE | (空) | 推理运行时镜像（kubernetes 必填；运行时契约=llama.cpp server） |
| LLMOPS_INFERENCE_INIT_IMAGE | curlimages/curl:8.11.1 | initContainer 镜像（下载模型） |
| LLMOPS_INFERENCE_MODEL_URL | (空) | 模型 artifact URL（kubernetes 必填；仅 http/https，经 Secret 注入） |
| LLMOPS_INFERENCE_KUBECONFIG | (空) | kubeconfig 路径（空则 in-cluster ServiceAccount） |
| LLMOPS_INFERENCE_SYNC_INTERVAL_SEC | 15 | 集群实况对账周期秒 |

## 生产持久层（PostgreSQL）

默认 `memory` 为进程内存实现（重启即丢，仅开发/测试）。生产必须配：

```bash
LLMOPS_ENV=production \
LLMOPS_FRONTEND_STORE=postgresql \
LLMOPS_FRONTEND_DATABASE_URL="postgresql://user:pass@pg-host:5432/llmops" \
python main.py
```

- `LLMOPS_ENV=production` 且 `LLMOPS_FRONTEND_STORE != postgresql` 时**启动即失败**
  （Settings 校验 fail-fast，禁止静默回退内存）；
- DSN 只从环境变量/K8s Secret 读取，源码、示例、测试不写可用凭据；
- 表 `llmops_frontend_metrics/finetunes/inference` 以 JSONB 存储、
  `(tenant_id, entity_id|task_id)` 主键租户隔离，schema 幂等自举（咨询锁 + IF NOT EXISTS）；
- Helm Chart 生产默认已按此注入（`design/deploy/charts/llmops`）。

## 真推理编排（Kubernetes）

默认 `contract` 为契约级状态机（deploy 即 RUNNING、invoke 诚实 501，仅开发/测试）。
生产必须真集群编排：

```bash
LLMOPS_ENV=production \
LLMOPS_FRONTEND_STORE=postgresql \
LLMOPS_FRONTEND_DATABASE_URL="postgresql://user:pass@pg-host:5432/llmops" \
LLMOPS_INFERENCE_ORCHESTRATOR=kubernetes \
LLMOPS_INFERENCE_IMAGE="ghcr.io/ggml-org/llama.cpp:server" \
LLMOPS_INFERENCE_MODEL_URL="https://<模型仓>/model.gguf" \
python main.py
```

- `LLMOPS_ENV=production` 且 `LLMOPS_INFERENCE_ORCHESTRATOR != kubernetes` 时**启动即失败**
  （禁止契约级假编排）；`kubernetes` 模式缺 `INFERENCE_IMAGE` / `INFERENCE_MODEL_URL` 同样 fail-fast；
- **deploy**：在 `LLMOPS_INFERENCE_NAMESPACE` 创建 Deployment（initContainer 下载模型
  + 推理运行时主容器）与 Service，视图先落 `DEPLOYING`；创建失败落 `FAILED + errorMessage`
  并 best-effort 清理残留；
- **scale / stop**：走 scale subresource；`desiredReplicas` 立即写、`replicas` 与 `status`
  由 reconcile 按集群实况对账改写（STOPPED / DEPLOYING / RUNNING）；
- **invoke**：仅 `RUNNING` 可调用，转发到集群内 Service 的 llama.cpp `/completion`
  （请求 `{prompt, maxTokens}` → 响应 `{text, raw}`）；非 RUNNING 返回 409；
- **reconcile**：每 `SYNC_INTERVAL_SEC` 秒对账；store 有而集群没有的非终态记录 ⇒ `FAILED`
  （不掩盖集群外删除漂移）；
- 运行时契约＝llama.cpp server（`/completion` 推理、`/health` 探针）；生产可换任何满足
  该契约的镜像；多模型与模型仓发布策略属后续边界；
- Helm Chart 生产默认创建 ServiceAccount + 目标命名空间最小权限 Role（`templates/rbac.yaml`），
  模型 URL 与 DSN 同一 Secret（`frontend.existingSecretName` 的 `model-url` 键）。
  Secret 需含 `database-url` 与 `model-url` 两键。