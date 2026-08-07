# evaluation · 模型评测平台与 A/B 对比

> 数擎大数据平台 · T031 模型评测平台与 A/B 对比
> Phase 2 Batch 2 · 基于 T030 多模态网关的评测任务引擎

提供模型评测任务引擎，支持 MMLU/CMMLU/CEval 标准集，计算六指标（准确率/召回率/F1/延迟/成本/幻觉率），支持规则/模型/人工三模式评测，生成 A/B 对比报告高亮差异指标。

## 技术栈

- Python 3.10+
- FastAPI 0.115（评测任务引擎）
- Pydantic 2.10（数据模型）
- httpx 0.28（LLM 网关客户端）
- HuggingFace datasets 3.2（标准集自动下载，可选）

## 核心能力

### 1. 评测任务引擎（FastAPI）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/eval/jobs` | 提交评测任务（返回 job_id） |
| GET | `/api/v1/eval/jobs` | 任务列表（支持状态过滤） |
| GET | `/api/v1/eval/jobs/{id}` | 任务详情 |
| GET | `/api/v1/eval/jobs/{id}/logs` | 任务日志 |
| DELETE | `/api/v1/eval/jobs/{id}` | 终止任务 |
| POST | `/api/v1/eval/ab-report` | 生成 A/B 对比报告 |
| GET | `/api/v1/eval/datasets` | 支持的标准集列表 |
| GET | `/api/v1/eval/datasets/{name}/stats` | 标准集统计信息 |
| GET | `/health` | 健康检查 |

### 2. 标准集支持

| 标准集 | 语言 | 任务数 | 说明 |
| --- | --- | --- | --- |
| MMLU | 英文 | 57 | Massive Multitask Language Understanding |
| CMMLU | 中文 | 67 | Chinese MMLU |
| CEval | 中文 | 52 | Chinese Evaluation |
| custom | - | - | 自定义数据集 |

- 内置样例数据：每个标准集内置 12 条样例，覆盖多个任务类别，无网络也可运行
- 远程下载：通过 HuggingFace datasets 自动下载完整数据集并缓存
- 格式转换：各标准集原始格式统一转换为 `EvalSample`

### 3. 六指标计算

| 指标 | 说明 | 取值范围 |
| --- | --- | --- |
| accuracy | 准确率 = 正确数 / 总数 | [0, 1] |
| recall | 召回率 = 正确且非幻觉数 / 总数 | [0, 1] |
| f1 | F1 = 2 × precision × recall / (precision + recall) | [0, 1] |
| latency_p95 | P95 延迟（毫秒） | [0, +∞) |
| cost | Token 成本 = 总 Token × 单价 | [0, +∞) |
| hallucination | 幻觉率 = 幻觉数 / 总数 | [0, 1] |

### 4. 三模式评测

| 模式 | 标识 | 说明 |
| --- | --- | --- |
| 规则模式 | `rule` | 正则/关键字匹配判定答案正确性，基于事实核查判定幻觉 |
| 模型模式 | `model` | LLM as Judge，由评判模型判定答案正确性与幻觉 |
| 人工模式 | `human` | 人工标注界面，预置标注或回退到规则判定 |

### 5. A/B 对比报告

- 两模型评测结果对比
- 高亮差异超过阈值的指标（⚠️ 标记）
- 更优判定：accuracy/recall/f1 越大越优，latency/cost/hallucination 越小越优
- 报告格式：Markdown / HTML

## 快速开始

### 本地运行

```bash
# 安装依赖
cd platform/llm-gateway/evaluation
pip install -r requirements.txt

# 启动评测平台（默认端口 8086）
# 需先启动 T030 LLM 网关（默认 http://localhost:18085）
uvicorn app.main:app --host 0.0.0.0 --port 8086

# 健康检查
curl http://localhost:8086/health
```

### Docker 运行

```bash
# 构建
docker build -t shuqing/evaluation:0.1.0 \
    -f platform/llm-gateway/evaluation/Dockerfile \
    platform/llm-gateway/evaluation

# 运行（连接 LLM 网关容器）
docker run -d --name it-evaluation \
    -p 18086:8086 \
    -e LLM_GATEWAY_URL=http://it-llm-gateway:8084 \
    shuqing/evaluation:0.1.0
```

## 使用示例

### 1. 提交评测任务

```python
import requests

# 提交 MMLU 评测，规则模式
resp = requests.post("http://localhost:8086/api/v1/eval/jobs", json={
    "model": "mock-gpt-4",
    "dataset": "mmlu",
    "mode": "rule",
    "limit": 10
})
job = resp.json()
job_id = job["job_id"]
print(f"任务 ID: {job_id}, 状态: {job['status']}")
```

### 2. 查询任务详情与结果

```python
# 查询任务详情
resp = requests.get(f"http://localhost:8086/api/v1/eval/jobs/{job_id}")
job = resp.json()
print(f"状态: {job['status']}")
print(f"指标: {job['results']}")
```

### 3. 查询任务日志

```python
resp = requests.get(f"http://localhost:8086/api/v1/eval/jobs/{job_id}/logs")
print(resp.json()["logs"])
```

### 4. 三模式评测

```python
# 规则模式
requests.post("http://localhost:8086/api/v1/eval/jobs", json={
    "model": "mock-gpt-4", "dataset": "cmmlu", "mode": "rule"
})

# 模型模式（LLM as Judge）
requests.post("http://localhost:8086/api/v1/eval/jobs", json={
    "model": "mock-gpt-4", "dataset": "cmmlu", "mode": "model",
    "judge_model": "mock-gpt-4"
})

# 人工模式（预置标注）
requests.post("http://localhost:8086/api/v1/eval/jobs", json={
    "model": "mock-gpt-4", "dataset": "cmmlu", "mode": "human",
    "human_labels": {"cmmlu-001": True, "cmmlu-002": False}
})
```

### 5. A/B 对比报告

```python
# 提交两个模型的评测
job_a = requests.post("http://localhost:8086/api/v1/eval/jobs", json={
    "model": "mock-gpt-4", "dataset": "mmlu", "mode": "rule"
}).json()
job_b = requests.post("http://localhost:8086/api/v1/eval/jobs", json={
    "model": "mock-claude", "dataset": "mmlu", "mode": "rule"
}).json()

# 生成 A/B 报告
report = requests.post("http://localhost:8086/api/v1/eval/ab-report", json={
    "job_a": job_a["job_id"],
    "job_b": job_b["job_id"],
    "format": "markdown",
    "highlight_threshold": 0.05
}).json()

print(report["content_markdown"])
```

## 架构

```
platform/llm-gateway/evaluation/
├── app/
│   ├── main.py                    # FastAPI 入口
│   ├── config.py                  # 配置加载
│   ├── models.py                  # Pydantic 数据模型
│   ├── api/
│   │   └── routes.py              # API 路由
│   ├── core/
│   │   ├── job_manager.py         # 评测任务管理器
│   │   ├── executor.py            # 评测执行器
│   │   └── llm_client.py          # LLM 网关客户端
│   ├── datasets/
│   │   ├── base.py                # 数据集基类
│   │   ├── mmlu.py                # MMLU 适配器
│   │   ├── cmmlu.py               # CMMLU 适配器
│   │   └── ceval.py               # CEval 适配器
│   ├── metrics/
│   │   ├── base.py                # 指标基类 + 聚合计算
│   │   ├── accuracy.py            # 准确率
│   │   ├── recall.py              # 召回率
│   │   ├── f1.py                  # F1
│   │   ├── latency.py             # P95 延迟
│   │   ├── cost.py                # Token 成本
│   │   └── hallucination.py       # 幻觉率
│   ├── modes/
│   │   ├── base.py                # 评测模式基类
│   │   ├── rule_mode.py           # 规则模式
│   │   ├── model_mode.py          # 模型模式（LLM as Judge）
│   │   └── human_mode.py          # 人工模式
│   └── report/
│       ├── generator.py           # A/B 报告生成器
│       └── templates.py           # Markdown/HTML 模板
├── requirements.txt
├── Dockerfile
├── pyproject.toml
└── README.md
```

### 评测流程

```
提交任务 → JobManager.submit() → 生成 job_id
    ↓
EvalExecutor.execute()
    ↓
DatasetAdapter.load()           # 加载标准集
    ↓
for each sample:
    LLMGatewayClient.chat()     # 调用被评测模型
    EvalMode.judge()            # 评测模式判定
    ↓
compute_all()                   # 计算六指标
    ↓
JobManager.update_status(SUCCEEDED)
```

## 配置

通过环境变量配置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `EVAL_PORT` | 8086 | 服务端口 |
| `LLM_GATEWAY_URL` | http://localhost:18085 | T030 LLM 网关地址 |
| `LLM_GATEWAY_API_KEY` | dummy | LLM 网关 API Key |
| `LLM_GATEWAY_TIMEOUT` | 30 | LLM 网关请求超时（秒） |
| `DATASET_CACHE_DIR` | ./.cache/datasets | 数据集缓存目录 |
| `EVAL_MAX_CONCURRENCY` | 4 | 最大并发评测数 |
| `TOKEN_PRICE_PER_1K` | 0.01 | Token 成本单价（元/1K Token） |
| `EVAL_DEV_MODE` | false | 开发模式（跳过 JWT 校验） |

## 测试

### pytest 集成测试

```bash
# 启动 Docker 容器
docker-compose up -d --build evaluation

# 运行集成测试
pytest tests/integration/docker/test_model_evaluation.py -v
```

测试覆盖：
- 评测任务场景（提交/查询/日志/终止）
- 指标计算场景（六指标计算正确）
- 三模式场景（规则/模型/人工全部可用）
- A/B 报告场景（高亮差异指标，Markdown/HTML 格式）
- 标准集场景（MMLU/CMMLU/CEval 加载成功）

## 依赖关系

- **T030 多模态网关**（Batch 1a）：提供 OpenAI 兼容 API，评测平台通过 LLM 网关调用被评测模型
- 评测平台默认连接 `http://localhost:18085`（T030 主机端口），Docker 内通过 `http://it-llm-gateway:8084` 连接

## 验收标准

- [x] 评测任务引擎（FastAPI）实现完成（提交/查询/日志/终止）
- [x] MMLU/CMMLU/CEval 标准集支持，数据集自动加载
- [x] 六指标（准确率/召回率/F1/延迟/成本/幻觉率）计算正确
- [x] 三模式评测（规则/模型/人工）全部可用
- [x] A/B 报告高亮差异指标，报告格式 Markdown/HTML
- [x] pytest 集成测试编写完成（≥20 用例）