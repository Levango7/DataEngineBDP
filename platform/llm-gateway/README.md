# llm-gateway · 大模型网关

> 数擎大数据平台 · L4.5.6 领域大模型接口（含大模型网关）
> 对标：星环 SoLar / 通用 LLM Gateway

统一 API 入口，路由多模型、负载均衡、Token 计量、安全审计，屏蔽底层部署差异。
OpenAI 兼容协议，便于存量应用接入。**网关只做治理，不直接持有模型**。

## 技术栈

- Go 1.23
- Gin Web 框架
- 多模型适配器（OpenAI / 文心一言 / 通义千问 / 智谱 GLM / Mock）
- 接口抽象 + Mock 实现策略：真实大模型 API 通过配置注入

## 快速开始

```bash
# Mock 模式（开发环境，无需真实 API Key）
LLM_GATEWAY_MOCK_MODE=true JWT_DEV_MODE=true go run .

# 默认端口 8084，健康检查
curl http://127.0.0.1:8084/health
```

## API 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/chat/completions` | 对话补全（OpenAI 兼容） |
| POST | `/api/v1/embeddings` | 向量嵌入 |
| GET | `/api/v1/models` | 可用模型列表 |
| GET | `/api/v1/providers` | Provider 列表 |
| POST | `/api/v1/providers` | 注册 Provider |
| DELETE | `/api/v1/providers/:name` | 注销 Provider |
| GET | `/api/v1/metrics/tokens` | Token 使用统计 |
| GET | `/api/v1/metrics/latency` | 延迟统计 |
| GET | `/health` | 健康检查 |

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
请求 → Auth → 路由(Router) → 负载均衡(LoadBalancer) → Provider适配器
                ↓                                          ↓
            审计前置(敏感词) ──────────────────→ 计量(TokenMeter) + 审计后置(AuditRecord)
```

- **Router**：根据模型名 / 租户 / 优先级路由到对应 Provider。租户级规则优先于全局规则。
- **LoadBalancer**：同一模型的多个实例间按权重轮询。
- **TokenMeter**：按租户 / 模型统计 Token 用量与调用延迟，线程安全。
- **Auditor**：敏感词过滤、请求日志记录、安全审计。

## 配置

支持 YAML 文件 / JSON / 环境变量三种配置方式。真实 API 凭据通过配置注入，不硬编码。

```yaml
server:
  port: "8084"
  version: "0.1.0"
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

## 开发

```bash
go build ./...      # 编译
go test ./...       # 测试
go vet ./...        # 静态检查
```