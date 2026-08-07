# NL2SQL 核心引擎 · 数擎大数据平台 L4.5.4

> 将自然语言查询转换为 SQL，对接 Catalog 元数据与 SQL 网关，支持意图识别、Schema 上下文构建、语法校验、多轮澄清与槽位填充。

## 项目用途

`nl2sql` 是数擎大数据平台（ShuqingBigDataPlatform）的 NL2SQL 核心引擎，提供：

- **Schema 上下文构建**：从 Catalog（Go :8082）拉取表结构，裁剪为 LLM prompt 上下文。
- **意图识别**：识别聚合 / 过滤 / Join / 排序 / 分组等查询意图。
- **SQL 生成**：基于 LangChain + LLM（经 llm-gateway :8084）生成 SQL，含 Mock 降级。
- **语法校验**：sqlparse 静态校验 + SELECT-only 安全护栏。
- **多轮对话澄清**：模糊查询多轮交互澄清。
- **槽位填充**：缺失参数交互补全。
- **SQL 网关对接**：生成 SQL 发送到 sql-gateway（Java :8081）校验执行。

## 技术栈

| 维度       | 选型                                   |
|------------|----------------------------------------|
| 语言       | Python 3.10+                           |
| Web 框架   | FastAPI 0.110 + Uvicorn                |
| 数据模型   | Pydantic v2 + pydantic-settings        |
| SQL 解析   | sqlparse 0.5                           |
| LLM 编排   | LangChain 0.1（可选，Mock 模式无需）   |
| HTTP 客户端 | httpx（异步）                          |
| 日志       | loguru                                 |
| 测试       | pytest + pytest-asyncio                |

## 目录结构

```
platform/nl2sql/
├── app.py                    # FastAPI 服务入口（port 8093）
├── models.py                 # 核心数据模型（Intent/Slot/DialogueState 等）
├── schema_context.py         # Schema 上下文构建器（对接 Catalog :8082）
├── intent_recognition.py     # 意图识别（聚合/过滤/Join/排序）
├── sql_generator.py          # SQL 生成（LangChain + Mock 降级）
├── sql_validator.py          # SQL 语法校验（sqlparse）
├── dialogue_clarifier.py     # 多轮对话澄清
├── slot_filler.py            # 槽位填充
├── gateway_client.py         # SQL 网关对接（:8081）
├── requirements.txt          # 依赖
├── pyproject.toml            # 项目配置
├── README.md
├── config/
│   ├── __init__.py
│   └── settings.py           # 配置中心（环境变量前缀 NL2SQL_）
└── tests/                    # 单元测试（101 用例）
    ├── conftest.py
    ├── test_settings.py
    ├── test_schema_context.py
    ├── test_intent_recognition.py
    ├── test_sql_validator.py
    ├── test_slot_filler.py
    ├── test_dialogue_clarifier.py
    ├── test_sql_generator.py
    ├── test_gateway_client.py
    └── test_api.py
```

## 快速开始

### Mock 模式（零外部依赖）

```bash
cd platform/nl2sql
python app.py
# 或
uvicorn app:create_app --factory --port 8093
```

默认 `NL2SQL_LLM_MODE=mock`，使用内置 Mock schema 与规则化 SQL 生成，无需 Catalog / SQL Gateway / LLM。

### LangChain 模式（生产）

```bash
pip install -r requirements.txt
NL2SQL_LLM_MODE=langchain \
NL2SQL_LLM_GATEWAY_URL=http://llm-gateway:8084 \
NL2SQL_LLM_API_KEY=sk-xxx \
NL2SQL_CATALOG_URL=http://catalog:8082 \
NL2SQL_SQL_GATEWAY_URL=http://sql-gateway:8081 \
python app.py
```

## API 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET  | `/api/v1/health` | 健康检查 |
| POST | `/api/v1/nl2sql/generate` | 单轮 NL → SQL（不执行） |
| POST | `/api/v1/nl2sql/execute` | NL → SQL → 网关执行 |
| POST | `/api/v1/nl2sql/dialogue/start` | 开启多轮对话 |
| POST | `/api/v1/nl2sql/dialogue/answer` | 提交澄清回答 |
| POST | `/api/v1/nl2sql/validate` | 校验 SQL 语法 |
| GET  | `/api/v1/nl2sql/schema` | 获取 schema 上下文（调试用） |

### 生成 SQL 示例

```bash
curl -X POST http://localhost:8093/api/v1/nl2sql/generate \
  -H "Content-Type: application/json" \
  -d '{"query": "统计 orders 表昨天的订单数量", "useMockSchema": true}'
```

响应：

```json
{
  "sql": "SELECT COUNT(*) AS cnt FROM default.orders WHERE dt = date_sub(current_date, 1) LIMIT 100;",
  "intent": {"primaryType": "aggregation", "aggFunc": "COUNT", "confidence": 0.9},
  "validation": {"valid": true, "issues": []},
  "llmUsed": false,
  "elapsedMs": 1.2
}
```

### 多轮对话示例

```bash
# 1. 开启对话
curl -X POST http://localhost:8093/api/v1/nl2sql/dialogue/start \
  -H "Content-Type: application/json" \
  -d '{"query": "关联 orders 和 users 统计", "useMockSchema": true}'

# 2. 提交澄清回答
curl -X POST http://localhost:8093/api/v1/nl2sql/dialogue/answer \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "<上一步返回>", "answer": "昨天", "useMockSchema": true}'
```

## 配置项

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `NL2SQL_HOST` | 0.0.0.0 | 监听地址 |
| `NL2SQL_PORT` | 8093 | 监听端口 |
| `NL2SQL_API_PREFIX` | /api/v1 | API 路由前缀 |
| `NL2SQL_CATALOG_URL` | http://localhost:8082 | Catalog 地址 |
| `NL2SQL_SQL_GATEWAY_URL` | http://localhost:8081 | SQL 网关地址 |
| `NL2SQL_DEFAULT_ENGINE` | trino | 默认引擎 trino/doris |
| `NL2SQL_DEFAULT_LIMIT` | 100 | 默认行数上限 |
| `NL2SQL_LLM_MODE` | mock | LLM 模式 mock/langchain |
| `NL2SQL_LLM_GATEWAY_URL` | http://localhost:8084 | LLM 网关地址 |
| `NL2SQL_LLM_MODEL` | qwen2.5-7b-instruct | 模型名 |
| `NL2SQL_LLM_API_KEY` | | LLM 网关 API Key |
| `NL2SQL_LLM_TEMPERATURE` | 0.0 | 采样温度 |
| `NL2SQL_SELECT_ONLY` | true | 仅允许 SELECT |
| `NL2SQL_MAX_TABLES` | 20 | 上下文最大表数 |
| `NL2SQL_MAX_DIALOGUE_TURNS` | 5 | 对话最大轮次 |

> 注：pydantic-settings v2 对 camelCase 字段（如 `llmMode`）的环境变量映射为 `NL2SQL_llmMode`，与 `NL2SQL_LLM_MODE` 不匹配。多 word 配置通过构造参数或 `.env` 文件设置；单 word（如 `NL2SQL_PORT`）可环境变量覆盖。

## 与平台其他组件的关系

| 组件 | 关系 |
| --- | --- |
| `catalog`（Go :8082） | 拉取数据库 / 表 / 列元数据，构建 schema 上下文 |
| `sql-gateway`（Java :8081） | 生成的 SQL 发送到网关校验执行（鉴权 / 路由 / 限流） |
| `llm-gateway`（Go :8084） | LangChain 模式下经网关调用大模型（OpenAI 兼容协议） |

## 测试

```bash
cd platform/nl2sql
python -m pytest tests/ -v
# 101 passed
```

## 验证

```bash
python -c "import app"  # 无错误
python -m pytest tests/  # 全部通过
```

## 后续规划

- [ ] 接入评测集（Spider / CSpider / 业务自定义）做 NL2SQL 准确率评测
- [ ] LangChain 模式接入 few-shot examples 与 self-correction
- [ ] 槽位填充引入 LLM 抽取（当前为关键词规则）
- [ ] 对话状态持久化至 Redis（当前内存）
- [ ] 接入平台统一鉴权与链路追踪