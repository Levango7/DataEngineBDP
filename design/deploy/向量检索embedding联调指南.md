# 向量检索 · Embedding 服务联调指南

> 归属：多平台多租户大数据平台 · 向量检索（vector-engine）
> 版本：v1.0 ｜ 日期：2026-08-29 ｜ 状态：生效
> 关联：`platform/vector-engine/internal/embedding/embedder.go`；`design/deploy/charts/vector-engine`；`docs/user-guide/api-reference.md 第15章`

---

## 1. 背景

自 **2026-08-29** 起，vector-engine 的全局检索（`POST /api/v1/vector/search`）支持**可插拔向量化**：

| 模式 | 触发条件 | 检索能力 | 响应 `mode` 字段 |
| --- | --- | --- | --- |
| semantic（语义检索） | 配置 `VECTOR_EMBEDDING_API` | 真实语义相似度 | `semantic` |
| hash-fallback（降级） | 未配置 API | n-gram 词元特征近似 | `hash-fallback` |

> 未配置时**不会报错**，自动降级为确定性特征向量（结果稳定可复现），并在响应中标注 `mode`，调用方应据此提示"语义检索已启用/未启用"。

## 2. 快速启用（3 步）

### 2.1 配置 Chart（推荐 K8s 部署）

```bash
helm upgrade --install vector-engine design/deploy/charts/vector-engine \
  --namespace <namespace> \
  --set embedding.api=https://<your-embedding-endpoint>/v1/embeddings \
  --set embedding.model=text-embedding-3-small \
  --set embedding.apiKeySecretName=embedding-key   # 可选；API Key 所在 Secret
  # API Key 键名默认 api-key，可用 embedding.apiKeySecretKey 覆盖
```

### 2.2 创建 API Key Secret（若使用密钥鉴权）

```bash
kubectl create secret generic embedding-key \
  --namespace <namespace> \
  --from-literal=api-key='<your-api-key>' \
  --dry-run=client -o yaml | kubectl apply -f -
```

### 2.3 验证

```bash
curl -s -X POST https://<platform-domain>/api/v1/vector/search \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"query":"数据引擎血缘分析","topK":5}' | jq '.mode'
# 期望输出: "semantic"
```

## 3. 端点兼容要求

服务端实现为 **OpenAI 兼容 `/embeddings` 协议**：

**请求**（`POST {api}`）：
```json
{"input": ["查询文本"], "model": "text-embedding-3-small"}
```
**响应**：
```json
{"data": [{"embedding": [0.0123, -0.0456, ...]}], "model": "..."}
```

| 要求 | 说明 |
| --- | --- |
| 鉴权 | 支持 `Authorization: Bearer <key>`（配置 apiKey 时自动附加） |
| 超时 | 单次调用 15 秒，超时返回 `embedding_failed` |
| 响应体 | 最大读取 4MB；`data` 长度必须等于 `input` 长度 |
| 错误 | 非 2xx 或 `{"error": {...}}` 均显式报错，不会静默降级 |

## 4. 常见服务对接示例

### 4.1 OpenAI / 兼容网关

```bash
--set embedding.api=https://api.openai.com/v1/embeddings \
--set embedding.model=text-embedding-3-small
```

### 4.2 阿里云百炼（DashScope，国内推荐）

```bash
--set embedding.api=https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding-v3 \
--set embedding.model=text-embedding-v3 \
--set embedding.apiKeySecretName=dashscope-key
```

### 4.3 腾讯云混元 embedding

```bash
--set embedding.api=https://api.hunyuan.cloud.tencent.com/v1/embeddings \
--set embedding.model=hunyuan-embedding
```

### 4.4 本地 / 内网（Ollama + BGE，信创/私有云推荐）

```bash
--set embedding.api=http://ollama.<namespace>.svc:11434/v1/embeddings \
--set embedding.model=bge-m3
```

> 兼容性以"OpenAI /embeddings 协议"为判据：不支持该协议的服务（如原生 Milvus embedding 插件）需加一层 OpenAI 兼容网关（如 LiteLLM / one-api）后再接入。

## 5. 维度一致性（易踩坑）

向量集合创建时指定 `dimension`（`POST /api/v1/collections`）。**插入向量与查询向量的维度必须等于集合维度**：

| 场景 | 要求 |
| --- | --- |
| 创建集合 | `dimension` 应与所选 embedding 模型的输出维度一致（如 text-embedding-3-small=1536、bge-m3=1024、text-embedding-v3=1024） |
| 插入向量 | 必须按模型输出维度插入（官方 SDK 通常自动处理） |
| 全局检索 | 查询文本经同一模型向量化，维度自动一致 |
| 降级模式 | hash-fallback 固定 256 维——**仅适用于 256 维集合**；若集合维度 ≠ 256 且未配置 API，检索结果为空或维度报错，属预期行为 |

> 建议：切换 embedding 模型时新建集合（或重建索引），避免维度不一致导致检索异常。

## 6. 验证与排障

### 6.1 响应示例（semantic）

```json
{
  "mode": "semantic",
  "results": [
    {"id": "doc-1", "score": 0.87, "payload": {"title": "数据引擎"}, "collection": "docs"}
  ]
}
```

### 6.2 常见问题

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| 响应 `mode: hash-fallback` | 未配置 `VECTOR_EMBEDDING_API` | 按 §2 配置后滚动重启 |
| 返回 502 `embedding_failed` | 端点不可达/超时/鉴权失败/响应格式不符 | 检查 `embedding.api` 可达性与协议兼容（§3） |
| 检索结果为空或维度报错 | 集合维度与模型输出维度不一致 | 按 §5 核对维度 |
| 401 未授权 | API Key 缺失或 Secret 未创建 | 检查 `embedding.apiKeySecretName` 对应 Secret |
| 语义结果不理想 | 模型与业务语料不匹配 | 更换 embedding 模型；中文场景优先 bge-m3 / text-embedding-v3 |

### 6.3 日志定位

```bash
kubectl logs deploy/vector-engine -n <namespace> | grep -iE "embedding|VECTOR_EMBEDDING"
```

## 7. 相关链接

- 实现：`platform/vector-engine/internal/embedding/embedder.go`（HTTPEmbedder / HashEmbedder）
- Chart：`design/deploy/charts/vector-engine`（`embedding` 段）
- API：`docs/user-guide/api-reference.md 第15章`（`mode` 字段说明）
- 鉴权配套：`design/deploy/JWT鉴权配置指南.md`
