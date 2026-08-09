# Model Finetuning Engine · 微调任务引擎

> 数据引擎大数据平台 · T032 LoRA/QLoRA/全参微调

## 1. 概述

微调任务引擎是数据引擎大数据平台 LLM 微调能力的核心组件，提供统一的 REST API 管理大模型微调任务的全生命周期。引擎接入三种主流微调框架，支持三种微调方式，并通过 Volcano 调度 GPU 节点池。

### 1.1 核心能力

| 能力 | 说明 |
|------|------|
| 微调方式 | LoRA（rank 8/16/32）、QLoRA（4bit/8bit 量化）、全参微调 |
| 微调框架 | LLaMA-Factory（CLI）、HuggingFace PEFT（Python 集成）、DeepSpeed（多卡并行） |
| 并行策略 | DeepSpeed ZeRO-2/3 数据并行 + 张量并行（TP） |
| GPU 调度 | K8s Volcano Gang Scheduling + 多卡亲和性 + 节点池 |
| 任务管理 | 提交 / 查询 / 列表 / 日志 / 终止 |
| 实时日志 | loss / lr / epoch / GPU 利用率 / 显存占用 |
| Mock 模式 | 无需 GPU 即可验证 API 正确性 |

### 1.2 技术栈

- **Web 框架**：FastAPI 0.110 + Uvicorn
- **数据校验**：Pydantic v2
- **微调框架**：PEFT / DeepSpeed / LLaMA-Factory
- **GPU 调度**：K8s Volcano
- **日志**：loguru
- **Python**：3.10+

## 2. 目录结构

```
platform/model-finetuning/
├── main.py                          # FastAPI 主入口（应用工厂）
├── requirements.txt                 # Python 依赖
├── Dockerfile                       # Docker 镜像构建
├── README.md                        # 本文档
├── app/
│   ├── api/
│   │   └── tasks.py                 # 任务 API 路由
│   ├── services/
│   │   ├── finetune_service.py      # 微调任务管理服务
│   │   └── job_scheduler.py         # GPU 节点池调度
│   ├── adapters/
│   │   ├── base.py                  # 适配器抽象基类
│   │   ├── factory.py               # 适配器工厂
│   │   ├── llama_factory_adapter.py # LLaMA-Factory 适配器
│   │   ├── peft_adapter.py          # PEFT 适配器
│   │   └── deepspeed_adapter.py     # DeepSpeed 适配器
│   └── models/
│       ├── finetune_task.py         # 微调任务模型
│       └── finetune_config.py       # 微调配置模型
└── config/
    ├── deepspeed_zero2.json         # DeepSpeed ZeRO-2 配置
    ├── deepspeed_zero3.json         # DeepSpeed ZeRO-3 配置
    ├── volcano-scheduler.yaml       # Volcano GPU 调度配置
    └── volcano-podgroup-example.yaml # PodGroup 示例
```

## 3. API 端点

### 3.1 健康检查

```
GET /api/v1/health
```

### 3.2 任务管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/finetune/tasks` | 提交微调任务 |
| GET | `/api/v1/finetune/tasks` | 查询任务列表（支持 status/tenantId/limit/offset 过滤） |
| GET | `/api/v1/finetune/tasks/{id}` | 查询任务详情 |
| DELETE | `/api/v1/finetune/tasks/{id}` | 终止任务 |
| GET | `/api/v1/finetune/tasks/{id}/logs` | 查询任务日志（支持 tail/parse 参数） |

### 3.3 系统信息

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/finetune/adapters` | 列出微调框架适配器 |
| GET | `/api/v1/finetune/nodes` | 列出 GPU 节点池状态 |
| GET | `/api/v1/finetune/stats` | 服务统计信息 |

### 3.4 提交任务示例

```json
{
  "taskName": "lora-llama2-7b-test",
  "baseModel": "meta-llama/Llama-2-7b-hf",
  "dataset": {
    "name": "alpaca-zh",
    "path": "/data/datasets/alpaca-zh.json",
    "format": "alpaca"
  },
  "config": {
    "method": "lora",
    "framework": "peft",
    "lora": {
      "rank": 16,
      "alpha": 32,
      "dropout": 0.05,
      "targetModules": ["q_proj", "k_proj", "v_proj", "o_proj"]
    },
    "hyperparams": {
      "epochs": 3,
      "batchSize": 8,
      "learningRate": 0.0002,
      "maxSeqLength": 2048
    }
  },
  "gpu": {
    "count": 1,
    "type": "A100-40G",
    "memoryGB": 40
  },
  "outputDir": "/data/finetune/output",
  "tenantId": "tenant-001"
}
```

## 4. 微调方式说明

### 4.1 LoRA

低秩矩阵分解，仅训练少量适配器参数，基座模型冻结。

- **rank**：秩，常用 8/16/32，越大表达能力越强但显存占用越高
- **alpha**：缩放系数，通常设为 rank 的 2 倍
- **适用场景**：单卡或少量卡微调，资源受限场景

### 4.2 QLoRA

先对基座模型做 4bit/8bit 量化，再在其上做 LoRA 微调，大幅降低显存。

- **4bit**：NF4 量化，7B 模型单卡约 6GB 显存
- **8bit**：INT8 量化，精度更高但显存占用翻倍
- **适用场景**：单卡微调大模型（如 33B/65B）

### 4.3 全参微调

全量参数微调，显存占用最大，通常配合 DeepSpeed ZeRO-3 卸载。

- **gradientCheckpointing**：梯度检查点，以计算换显存
- **适用场景**：对模型质量要求最高的生产场景

## 5. 框架适配器

### 5.1 LLaMA-Factory

通过 `subprocess` 调用 `llamafactory-cli train` CLI，YAML 配置驱动。

- 优点：低代码，配置简单，支持多种数据格式
- 缺点：灵活性不如直接 PEFT

### 5.2 HuggingFace PEFT

直接 Python 集成 PEFT 库，与 transformers Trainer 深度集成。

- 优点：灵活性高，可定制训练逻辑
- 缺点：需要编写更多代码

### 5.3 DeepSpeed

多卡并行训练，支持 ZeRO-2/3 数据并行与张量并行。

- ZeRO-2：切分优化器状态 + 梯度
- ZeRO-3：额外切分模型参数，支持最大规模模型
- TP：张量并行，跨卡切分矩阵运算

## 6. GPU 调度

### 6.1 Volcano 配置

`config/volcano-scheduler.yaml` 配置了：

- **Gang Scheduling**：微调任务所有 Pod 必须同时调度成功
- **GPU 节点池**：按 GPU 型号（A100/V100/T4）划分
- **多卡亲和性**：要求多卡调度到同一节点（避免跨节点 NVLink 缺失）
- **队列优先级**：finetune-high / normal / low 三级队列

### 6.2 调度策略

调度器采用 best-fit 算法：

1. 筛选能满足 GPU 需求（卡数/型号/显存）的节点
2. 优先选择空闲 GPU 数最少但够用的节点（减少碎片）
3. 多卡任务要求同节点亲和

## 7. 本地运行

### 7.1 Mock 模式（无需 GPU）

```bash
# 安装依赖（仅核心依赖，无需 torch/deepspeed）
pip install fastapi uvicorn pydantic loguru pyyaml

# 启动服务
cd platform/model-finetuning
FINETUNE_MOCK_MODE=true FINETUNE_WORK_DIR=/tmp/finetune \
  python -m uvicorn main:app --host 0.0.0.0 --port 8095
```

### 7.2 Docker 运行

```bash
# 构建镜像
docker build -t sq/model-finetuning:0.1.0 .

# Mock 模式运行
docker run -p 8095:8095 -e FINETUNE_MOCK_MODE=true sq/model-finetuning:0.1.0

# GPU 模式运行
docker run --gpus all -p 8095:8095 -e FINETUNE_MOCK_MODE=false \
  -v /data/finetune:/data/finetune sq/model-finetuning:0.1.0
```

### 7.3 验证

```bash
# 健康检查
curl http://localhost:8095/api/v1/health

# 提交 LoRA 任务
curl -X POST http://localhost:8095/api/v1/finetune/tasks \
  -H "Content-Type: application/json" \
  -d @examples/lora_request.json

# 查看任务日志
curl http://localhost:8095/api/v1/finetune/tasks/{taskId}/logs?tail=50
```

## 8. 测试

集成测试位于 `tests/integration/docker/test_finetuning.py`，覆盖：

- 任务提交 / 查询 / 列表 / 终止 / 日志 API
- 三种微调方式（LoRA/QLoRA/全参）配置验证
- 三个框架适配器（LLaMA-Factory/PEFT/DeepSpeed）
- GPU 节点池调度

```bash
# 运行测试（需先启动服务）
pytest tests/integration/docker/test_finetuning.py -v
```

## 9. 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `FINETUNE_HOST` | `0.0.0.0` | 监听地址 |
| `FINETUNE_PORT` | `8095` | 监听端口 |
| `FINETUNE_WORK_DIR` | `/tmp/finetune` | 工作目录（日志/配置） |
| `FINETUNE_MOCK_MODE` | `true` | Mock 模式（不调用 GPU/框架） |
| `FINETUNE_SCHEDULER_BACKEND` | `volcano` | 调度后端 |
| `FINETUNE_LOG_LEVEL` | `info` | 日志级别 |

## 10. 与其他组件的关系

- **llm-gateway**：Phase 1 已完成，微调产出的模型可注册到 llm-gateway 提供推理服务
- **ml-platform**：微调任务引擎作为 ml-platform 的子模块，复用其资源管理能力
- **Volcano**：GPU 调度依赖集群已部署 Volcano