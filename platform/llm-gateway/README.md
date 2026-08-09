# llm-gateway · 大模型多模态网关

> 数据引擎大数据平台 · T030 大模型多模态网关统一 API 与路由
> Phase 2 增强：OpenAI 兼容 API + 四维度路由 + 多模态 Token 计量 + SSE 流式 + 异步批处理

统一 API 入口，路由多模型、负载均衡、Token 计量、安全审计，屏蔽底层部署差异。
OpenAI 兼容协议，便于存量应用接入。**网关只做治理，不直接持有模型**。

## 技术栈

- Go 1.25
- Gin Web 框架
- 多模型适配器（OpenAI / 文心一言 / 通义千问 / 智谱 GLM / Mock）
- 接口抽象 + Mock 实现策略：真实大模型 API 通过配置注入

## Phase 2 新增能力

### 1. OpenAI 兼容 API（/v1/chat/completions）

实现 OpenAI Chat Completions 标准端点，可被标准 OpenAI SDK 直接调用。

**多模态扩展**：
- 输入：`messages[].content` 可为字符串（纯文本）或数组（多模态片段）
- 输出：`choices[].message.content` 同上
- 自研扩展字段：`scene`（路由场景）、`modality_out`（期望输出模态）

**支持的输入模态**：

| 模态 | type 字段 | 说明 |
| --- | --- | --- |
| 文本 | `text` | OpenAI 标准 |
| 图像 | `image_url` | OpenAI 标准，支持 URL 与 base64 data URI |
| 语音 | `input_audio` | OpenAI 标准，base64 编码 |
| 视频 | `video_url` | 自研扩展，支持 URL 与 base64 data URI |

**支持的输出模态**：

| 模态 | type 字段 | 说明 |
| --- | --- | --- |
| 文本 | `text` | OpenAI 标准 |
| 图像 | `output_image` | 自研扩展，生成图像 |
| 语音 | `output_audio` | 自研扩展，TTS 合成 |

### 2. 四维度路由引擎

按四个维度综合路由到最优 Provider：

| 维度 | 说明 | 示例 |
| --- | --- | --- |
| 模型 | 按逻辑模型名路由 | gpt-4 / claude / 通义 / 文心 / 自研 |
| 租户 | 按租户优先级与配额路由 | VIP 租户优先级更高 |
| 场景 | 按调用场景路由 | chat / finetune / eval |
| 成本 | 按单价与延迟权衡路由 | 低价 + 低延迟优先 |

**路由决策流程**：

1. 从规则库筛选出匹配（模型, 租户, 场景）的候选规则
2. 按优先级降序排序，取最高优先级的一组候选
3. 在候选组内按成本维度（单价 + 延迟）选最优 Provider
4. 若无匹配规则，回退到默认 Provider

**租户配额**：支持 TPM（每分钟 Token）/ RPM（每分钟请求）/ Daily（每日 Token）三级配额。

### 3. 多模态 Token 计量

各模态独立计量并折算为统一 Token 单位：

| 模态 | 计量规则 | 说明 |
| --- | --- | --- |
| 文本 | 4 字符 ≈ 1 token | 可替换为 tiktoken 等精确分词器 |
| 图像 | 按分辨率折算 | OpenAI 规则：低精度 85 token，高精度按 tile 数折算 |
| 语音 | 每分钟 ≈ 1500 token | 对应 Whisper 计费规则 |
| 视频 | 每分钟 ≈ 6000 token | 按帧采样估算 |

**Usage 响应结构**：

```json
{
  "usage": {
    "prompt_tokens": 10,
    "completion_tokens": 20,
    "image_tokens": 85,
    "audio_tokens": 750,
    "video_tokens": 6000,
    "total_tokens": 6865
  }
}
```

### 4. SSE 流式响应

遵循 OpenAI Chat Completions 流式协议：

- 响应格式：`data: {chunk}\n\n`
- 结束标记：`data: [DONE]\n\n`
- 首 Token 延迟目标 ≤1s（通过立即发送 role chunk 实现）
- 触发方式：请求体 `"stream": true`

### 5. 异步批处理

支持高并发批处理任务：

- 提交任务返回 `job_id`：`POST /v1/batch/jobs`
- 轮询查询结果：`GET /v1/batch/jobs/:id`
- 列出所有任务：`GET /v1/batch/jobs`
- 任务状态：`queued` / `running` / `succeeded` / `failed`
- 并发支持：≥100（通过 worker pool + 队列实现）

## 快速开始

```bash
# Mock 模式（开发环境，无需真实 API Key）
LLM_GATEWAY_MOCK_MODE=true JWT_DEV_MODE=true go run .

# 默认端口 8084，健康检查
curl http://127.0.0.1:8084/health
```

## API 端点

### OpenAI 兼容端点（Phase 2 新增）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/chat/completions` | 多模态对话补全（OpenAI 兼容） |
| POST | `/v1/batch/jobs` | 提交异步批处理任务 |
| GET | `/v1/batch/jobs` | 列出所有批处理任务 |
| GET | `/v1/batch/jobs/:id` | 查询批处理任务状态/结果 |
| GET | `/v1/routing/rules` | 查询路由规则 |
| POST | `/v1/routing/rules` | 添加路由规则 |
| GET | `/v1/routing/decision` | 查询路由决策（不实际调用） |
| POST | `/v1/token/estimate` | 估算多模态 Token 用量 |

### 现有端点（Phase 1，向后兼容）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/chat/completions` | 对话补全（纯文本） |
| POST | `/api/v1/embeddings` | 向量嵌入 |
| GET | `/api/v1/models` | 可用模型列表 |
| GET | `/api/v1/providers` | Provider 列表 |
| POST | `/api/v1/providers` | 注册 Provider |
| DELETE | `/api/v1/providers/:name` | 注销 Provider |
| GET | `/api/v1/metrics/tokens` | Token 使用统计 |
| GET | `/api/v1/metrics/latency` | 延迟统计 |
| GET | `/health` | 健康检查 |

## 使用示例

### 1. 纯文本对话（OpenAI SDK 兼容）

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8084/v1",
    api_key="dummy"  # Mock 模式无需真实 key
)

resp = client.chat.completions.create(
    model="mock-gpt-4",
    messages=[{"role": "user", "content": "你好"}]
)
print(resp.choices[0].message.content)
```

### 2. 多模态对话（图像 + 文本）

```python
resp = client.chat.completions.create(
    model="mock-gpt-4",
    messages=[{
        "role": "user",
        "content": [
            {"type": "text", "text": "请描述这张图片"},
            {"type": "image_url", "image_url": {"url": "https://example.com/img.png", "detail": "low"}}
        ]
    }]
)
print(resp.usage.image_tokens)  # 图像 Token 计量
```

### 3. SSE 流式响应

```python
stream = client.chat.completions.create(
    model="mock-gpt-4",
    messages=[{"role": "user", "content": "写一首短诗"}],
    stream=True
)
for chunk in stream:
    if chunk.choices[0].delta.content:
        print(chunk.choices[0].delta.content, end="", flush=True)
```

### 4. 异步批处理

```python
import requests

# 提交任务
resp = requests.post("http://localhost:8084/v1/batch/jobs", json={
    "model": "mock-gpt-4",
    "messages": [{"role": "user", "content": "批处理任务"}]
})
job_id = resp.json()["id"]

# 轮询结果
import time
while True:
    resp = requests.get(f"http://localhost:8084/v1/batch/jobs/{job_id}")
    job = resp.json()
    if job["status"] in ("succeeded", "failed"):
        break
    time.sleep(0.1)
print(job["response"]["choices"][0]["message"]["content"])
```

### 5. 四维度路由

```python
# 添加路由规则
requests.post("http://localhost:8084/v1/routing/rules", json={
    "id": "vip-chat-rule",
    "model": "gpt-4",
    "tenantId": "vip-tenant",
    "scene": "chat",
    "provider": "openai-premium",
    "priority": 10
})

# 查询路由决策
resp = requests.get("http://localhost:8084/v1/routing/decision", params={
    "model": "gpt-4", "tenant": "vip-tenant", "scene": "chat"
})
print(resp.json())  # {"provider": "openai-premium", "reason": "..."}
```

## 支持的 Provider

| Provider | 类型标识 | 默认 Endpoint |
| --- | --- | --- |
| OpenAI GPT | `openai` | `https://api.openai.com` |
| 百度文心一言 | `wenxin` | `https://qianfan.baidubce.com/v2` |
| 阿里通义千问 | `qianwen` | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 智谱 GLM | `zhipu` | `https://open.bigmodel.cn/api/paas/v4` |
| Mock（测试） | `mock` | — |

## 架构

```
请求 → Auth → 四维度路由(RoutingEngine) → 负载均衡(LoadBalancer) → Provider适配器
                 ↓                                                    ↓
             审计前置(敏感词) ─────────────────────→ 多模态Token计量(TokenCounter) + 审计后置
                 ↓
         流式? → SSE流式响应(Streamer)
         批处理? → 异步任务队列(BatchJobManager) → worker pool(≥100)
```

### 模块结构

```
platform/llm-gateway/
├── main.go                          # 服务入口
├── internal/
│   ├── api/                         # API 控制器
│   │   ├── handler.go               # Phase 1 现有 API
│   │   └── multimodal_handler.go    # Phase 2 多模态 OpenAI 兼容 API
│   ├── gateway/                     # 网关核心
│   │   ├── gateway.go               # Phase 1 网关
│   │   ├── router.go                # Phase 1 路由
│   │   ├── balancer.go              # 负载均衡
│   │   ├── meter.go                 # Phase 1 Token 计量
│   │   ├── auditor.go               # 审计
│   │   └── multimodal.go            # Phase 2 多模态网关扩展
│   ├── routing/                     # Phase 2 四维度路由引擎
│   │   └── engine.go
│   ├── token/                       # Phase 2 多模态 Token 计量
│   │   └── counter.go
│   ├── streaming/                   # Phase 2 SSE 流式 + 异步批处理
│   │   └── streaming.go
│   ├── provider/                    # Provider 适配器
│   │   ├── provider.go              # 接口定义
│   │   ├── multimodal.go            # Phase 2 多模态消息类型
│   │   ├── mock.go                  # Mock 适配器
│   │   ├── openai.go                # OpenAI 适配器
│   │   ├── wenxin.go                # 文心一言适配器
│   │   ├── qianwen.go               # 通义千问适配器
│   │   └── zhipu.go                 # 智谱 GLM 适配器
│   ├── config/                      # 配置加载
│   └── middleware/                  # 中间件（Auth/CORS/Logging）
└── Dockerfile
```

## 配置

支持 YAML 文件 / JSON / 环境变量三种配置方式。真实 API 凭据通过配置注入，不硬编码。

```yaml
server:
  port: "8084"
  version: "0.2.0"
providers:
  - name: openai
    type: openai
    endpoint: https://api.openai.com
    apiKey: sk-xxx
    weight: 3
  - name: mock
    type: mock
    weight: 1
routes:
  - model: gpt-4
    provider: openai
    priority: 10
audit:
  enabled: true
  sensitiveWords: ["password", "secret"]
```

## 测试

### Go 单元测试

```bash
go test ./...                    # 全部测试
go test ./internal/routing/...   # 四维度路由测试
go test ./internal/token/...     # 多模态 Token 计量测试
go test ./internal/streaming/... # SSE + 批处理测试
```

### pytest 集成测试

```bash
# 启动 Docker 容器
docker-compose up -d --build llm-gateway

# 运行集成测试
pytest tests/integration/docker/test_multimodal_gateway.py -v
```

**测试覆盖**：

- OpenAI 兼容 API（/v1/chat/completions）
- 四维度路由决策（模型/租户/场景/成本）
- 多模态 Token 计量（文本/图像/语音/视频分别计量）
- SSE 流式响应（首 Token 延迟 ≤1s）
- 异步批处理（job_id 提交/轮询/结果，≥100 并发）
- 向后兼容（/api/v1/* 现有端点）

## 性能指标

| 指标 | 目标 | 说明 |
| --- | --- | --- |
| SSE 首 Token 延迟 | ≤1s | 通过立即发送 role chunk 实现 |
| 异步批处理并发 | ≥100 | 通过 worker pool 实现 |
| 路由决策延迟 | <1ms | 内存规则匹配 |
| Token 计量延迟 | <1ms | 纯计算无 IO |

## 开发

```bash
go build ./...      # 编译
go test ./...       # 测试
go vet ./...        # 静态检查
```

## 验收标准

- [x] OpenAI 兼容 API（/v1/chat/completions）实现完成
- [x] 四维度路由引擎实现完成（模型/租户/场景/成本）
- [x] 多模态 Token 计量实现完成（文本/图像/语音/视频分别计量）
- [x] SSE 流式响应实现完成
- [x] 异步批处理实现完成（job_id 提交/轮询）
- [x] pytest 测试套件编写完成
- [x] Go 单元测试编写完成
- [x] 向后兼容现有 /api/v1/* 端点
