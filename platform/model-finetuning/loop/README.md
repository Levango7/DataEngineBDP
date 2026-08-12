# 微调→评测→部署闭环编排服务

> T033 一键闭环编排：自动执行 **微调 → 评测 → 部署** 三步，
> 集成 T032 微调引擎与 T031 评测平台，支持版本化管理与 WebSocket 实时监控。

## 1. 概述

本服务提供微调→评测→部署一键闭环能力，用户提交一次请求即可完成全流程：

1. **微调**：调用 T032 微调引擎，基于 LoRA/QLoRA/全参微调基座模型
2. **评测**：调用 T031 评测平台，使用 MMLU/CMMLU/CEval 标准集评测
3. **部署**：调用模型仓库服务，部署到推理运行时（vLLM/Triton）

同时提供：
- **版本化管理**：Adapter 权重与评测报告版本化存储、版本对比、回滚
- **过程监控**：WebSocket 实时推送训练指标（loss/lr/GPU 利用率）

## 2. 架构

```
┌─────────────────────────────────────────────────────────┐
│                   闭环编排服务 (18088)                    │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│  │  Orchestrator │  │ StepExecutor │  │  WSManager  │     │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘     │
│         │                │                 │             │
│  ┌──────┴──────┐  ┌──────┴──────┐  ┌──────┴──────┐     │
│  │  AdapterReg │  │  ReportReg  │  │  ModelRepo  │     │
│  └─────────────┘  └─────────────┘  └─────────────┘     │
└─────────────────────────────────────────────────────────┘
          │                    │                │
          ▼                    ▼                ▼
   ┌──────────┐        ┌──────────┐      ┌──────────┐
   │ T032 微调 │        │ T031 评测 │      │ 模型仓库  │
   │ (8095)   │        │ (18086)  │      │ (18089)  │
   └──────────┘        └──────────┘      └──────────┘
```

## 3. API 端点

### 3.1 闭环任务管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/loop/tasks` | 提交闭环任务 |
| GET | `/api/v1/loop/tasks` | 查询任务列表 |
| GET | `/api/v1/loop/tasks/{taskId}` | 查询任务详情 |
| DELETE | `/api/v1/loop/tasks/{taskId}` | 取消任务 |
| GET | `/api/v1/loop/tasks/{taskId}/logs` | 查询任务日志 |
| WS | `/api/v1/loop/tasks/{taskId}/ws` | WebSocket 实时进度 |
| GET | `/api/v1/loop/stats` | 服务统计 |

### 3.2 版本管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/loop/adapters/versions` | Adapter 版本历史 |
| GET | `/api/v1/loop/adapters/compare` | Adapter 版本对比 |
| POST | `/api/v1/loop/adapters/rollback` | Adapter 回滚 |
| GET | `/api/v1/loop/adapters/active` | 获取激活版本 |
| GET | `/api/v1/loop/reports/versions` | 评测报告版本历史 |
| GET | `/api/v1/loop/reports/compare` | 评测报告版本对比 |

### 3.3 提交闭环任务示例

```json
POST /api/v1/loop/tasks
{
  "taskName": "lora-finetune-eval-deploy",
  "baseModel": "meta-llama/Llama-2-7b-hf",
  "trainDataset": {
    "name": "alpaca-zh",
    "path": "/data/datasets/alpaca-zh.json",
    "format": "alpaca"
  },
  "evalDataset": "cmmlu",
  "finetune": {
    "method": "lora",
    "framework": "peft",
    "lora": {"rank": 16, "alpha": 32},
    "hyperparams": {"epochs": 1, "batchSize": 4, "learningRate": 0.0002}
  },
  "eval": {
    "dataset": "cmmlu",
    "mode": "rule",
    "metrics": ["accuracy", "recall", "f1", "latency_p95", "cost", "hallucination"]
  },
  "deploy": {
    "runtime": "vllm",
    "port": 8000,
    "replicas": 1,
    "gpuCount": 1,
    "minAccuracy": 0.7
  },
  "gpu": {"count": 1, "type": "any"},
  "tenantId": "default"
}
```

### 3.4 WebSocket 消息格式

```json
{
  "type": "metrics",
  "taskId": "loop-xxxx",
  "timestamp": "2026-08-08T05:00:00Z",
  "data": {
    "step": "finetune",
    "loss": 1.234,
    "learningRate": 0.0001,
    "gpuUtil": [85.5, 82.3],
    "gpuMemory": [12.5, 11.8]
  }
}
```

消息类型：
- `status`：状态变更
- `metrics`：训练指标
- `log`：日志
- `error`：错误
- `completed`：完成

## 4. 状态机

```
pending → finetuning → evaluating → deploying → completed
                              ↘ failed（任一步失败）
                              ↘ cancelled（用户取消）
```

## 5. 配置

通过环境变量配置：

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `LOOP_PORT` | 18088 | 服务端口 |
| `FINETUNE_URL` | http://localhost:8095 | T032 微调引擎地址 |
| `EVALUATION_URL` | http://localhost:18086 | T031 评测平台地址 |
| `REGISTRY_URL` | http://localhost:18089 | 模型仓库服务地址 |
| `LOOP_WORK_DIR` | /tmp/finetune-loop | 工作目录 |
| `LOOP_MOCK_MODE` | true | Mock 模式（不调用外部服务） |

## 6. 启动

### 本地启动

```bash
cd platform/model-finetuning/loop
pip install -r requirements.txt
LOOP_MOCK_MODE=true uvicorn app.main:app --host 0.0.0.0 --port 18088
```

### Docker 启动

```bash
docker build -t shuqing/finetuning-loop:0.1.0 .
docker run -d -p 18088:18088 -e LOOP_MOCK_MODE=true shuqing/finetuning-loop:0.1.0
```

## 7. 目录结构

```
loop/
├── app/
│   ├── __init__.py
│   ├── main.py              # FastAPI 主入口
│   ├── config.py            # 配置
│   ├── models.py            # 数据模型
│   ├── api/
│   │   ├── __init__.py
│   │   └── loop_routes.py   # API 路由
│   ├── core/
│   │   ├── __init__.py
│   │   ├── orchestrator.py     # 闭环编排器
│   │   ├── step_executor.py    # 步骤执行器
│   │   └── websocket_manager.py # WebSocket 管理
│   └── versioning/
│       ├── __init__.py
│       ├── adapter_registry.py  # Adapter 版本化
│       ├── report_registry.py   # 评测报告版本化
│       └── model_repository.py  # 模型仓库
├── requirements.txt
├── Dockerfile
└── README.md
```