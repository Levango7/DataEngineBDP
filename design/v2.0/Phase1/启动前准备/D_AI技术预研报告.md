# 数据引擎大数据平台 V2.0 Phase 1a 启动前准备 — D：AI 技术预研报告

> **文档编号**：D-PRERESEARCH-AI-V1.0
> **任务编号**：147（Phase1a 启动前准备-D）
> **版本**：v1.0
> **编制日期**：2026-08-28
> **编制人**：AI 组（Python 工程师 A/B/C/D、AI 架构师）
> **评审人**：首席架构师
> **对应计划章节**：Phase1 详细执行计划 §5.8（R-P1-007 技术选型风险应对）
> **预研周期**：2026-08-25 ~ 2026-08-31
> **开工基线**：2026-09-01 Phase 1a 正式启动

---

## 第1章 预研概述

### 1.1 预研背景

数据引擎大数据平台 V2.0 Phase 1a 涉及大量 AI 技术栈，T005（MAOP 编排引擎）、T008（多模态切片器）、T009（混合检索与重排序）、T010（NL2SQL 核心引擎）、T012（Calcite 联邦优化器）、T014（Flink CDC 管道）、T015（Iceberg V2 行级 upsert）等关键任务均依赖外部开源框架与模型。根据 Phase1 详细执行计划 §5.8 R-P1-007 风险评估，LangGraph、LangChain、Milvus SDK、多模态 Embedding 模型等新框架学习曲线可能影响开发效率，需在 2026-09-01 正式开工前完成技术预研，降低开发风险。

### 1.2 预研目标

1. **技术选型决策**：对每个技术栈给出明确选型建议（主选 / 备选 / 淘汰），避免开发期反复换栈
2. **集成方案落地**：输出每个技术栈与数擎平台现有组件（vector-engine、knowledge-engine、llm-gateway、sql-gateway、catalog）的集成方案
3. **风险评估与缓解**：识别每个技术栈的兼容性、性能、运维、合规风险，给出缓解措施
4. **开工准备度评估**：明确 9 月 1 日开工时每个技术栈是否具备"Hello World"级可运行验证条件

### 1.3 预研范围

表：D-1 预研技术栈清单

| 序号 | 技术栈 | 用途 | 关联任务 | 主调研人 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 1 | LangGraph | T005 MAOP 编排引擎 ReAct 范式 | T005 | AI 架构师 | P0 |
| 2 | LangChain | T010 NL2SQL Schema 检索与 SQL 生成 | T010 | Python 工程师 C | P0 |
| 3 | Milvus SDK（Go/Python） | T008/T009 向量检索（RAG 核心） | T008、T009 | Go 工程师 B、Python 工程师 B | P0 |
| 4 | 多模态 Embedding 模型 | T008 多模态切片器 Embedding 生成 | T008 | Python 工程师 A | P0 |
| 5 | Cross-Encoder 重排序 | T009 混合检索重排序 | T009 | Python 工程师 B | P1 |
| 6 | Apache Calcite | T012 跨源联邦查询优化 | T012 | Java 工程师 C | P1 |
| 7 | Flink CDC | T014 实时数据入仓 | T014 | Java 工程师 E | P1 |
| 8 | Iceberg V2 | T015 行级 upsert | T015 | Java 工程师 F | P1 |

### 1.4 预研方法

1. **官方文档研读**：阅读各技术栈官方文档、Release Notes、Migration Guide
2. **源码走读**：对关键 API 与扩展点进行源码级走读，确认可定制性
3. **PoC 验证**：对 P0 技术栈搭建最小可运行 PoC，验证核心能力
4. **社区调研**：GitHub Issue / Stack Overflow / Slack 调研常见坑与生产案例
5. **兼容性矩阵**：与数擎平台技术基线（Go 1.21、Python 3.11、Java 17、K8s 1.28）做兼容性核对

### 1.5 平台技术基线

表：D-2 数擎平台技术基线（预研兼容性核对基准）

| 维度 | 基线版本 | 备注 |
| --- | --- | --- |
| Go | 1.21 | vector-engine、catalog、dqctl |
| Python | 3.11 | T008 切片器、T009 检索器、T010 NL2SQL |
| Java | 17（LTS） | T012 Calcite、T014 Flink CDC、T015 Iceberg |
| K8s | 1.28（SKE） | 自研 SKE 集群 |
| 容器运行时 | containerd 1.7 | — |
| 消息总线 | Kafka 3.6 | T005 Agent 间通信 |
| 向量库 | Milvus 2.4.x | T008/T009 RAG 核心 |
| 图谱库 | NebulaGraph 3.6 | knowledge-engine |
| LLM 网关 | llm-gateway（自研） | 统一 LLM 调用入口 |
| SQL 网关 | sql-gateway（自研，已清零） | T010 NL2SQL 经网关校验 |
| 国密 | SM2/SM4/GMTLS | T023 国密落地 |

---

## 第2章 LangGraph（Agent 编排框架）预研

### 2.1 技术栈定位

- **官方仓库**：https://github.com/langchain-ai/langgraph
- **首次发布**：2024-01
- **当前稳定版**：0.2.x（预研时点最新 0.2.50）
- **License**：MIT
- **数擎用途**：T005 MAOP 编排引擎核心的 ReAct 编排范式实现
- **替代方案对比**：AutoGen（微软）、CrewAI、LlamaIndex Workflows

### 2.2 框架架构

LangGraph 是 LangChain 团队推出的有状态、可循环 Agent 编排框架，核心思想是将 Agent 工作流建模为**有向有环图（StateGraph）**，节点是 Agent 或工具调用，边是状态转移。其架构要点：

1. **StateGraph**：以 TypedDict / Pydantic Model 定义全局状态 schema，节点接收状态、返回状态增量（Reducer 合并）
2. **节点（Node）**：可执行函数，接收 state、返回 state 增量；节点可以是 LLM 调用、工具调用、子图调用
3. **边（Edge）**：普通边（固定下一节点）与条件边（基于 state 动态路由）
4. **Reducer**：通过 `Annotated[list, add_messages]` 等注解定义状态字段合并策略
5. **Checkpointer**：通过 SqliteSaver / PostgresSaver 持久化中间状态，支持人工干预（human-in-the-loop）与容错恢复
6. **编译为 Pregel**：StateGraph.compile() 生成 Pregel 可执行图，Pregel 是 LangGraph 自研的执行引擎（灵感来自 Pregel 图计算模型）

图：D-1 LangGraph 架构示意图

```
┌─────────────────────────────────────────────────────────┐
│                    StateGraph (用户定义)                  │
│  ┌──────┐    ┌──────┐    ┌──────────┐    ┌──────┐       │
│  │ Node │───▶│ Node │───▶│ Condition │───▶│ Node │       │
│  │  A   │    │  B   │    │   Edge    │    │  D   │       │
│  └──────┘    └──────┘    └──────────┘    └──────┘       │
│                   │                                      │
│                   ▼ (条件分支)                            │
│              ┌──────┐                                   │
│              │ Node │                                   │
│              │  C   │                                   │
│              └──────┘                                   │
└─────────────────────────────────────────────────────────┘
            │ compile()
            ▼
┌─────────────────────────────────────────────────────────┐
│              Pregel Runtime (执行引擎)                    │
│  - 状态管理 / Reducer 合并                                │
│  - Checkpointer 持久化                                    │
│  - 并行调度 / 超时控制                                     │
│  - Streaming 输出                                          │
└─────────────────────────────────────────────────────────┘
```

### 2.3 核心API

#### 2.3.1 StateGraph 定义

代码示例：LangGraph StateGraph 基础定义（Python）

```python
from typing import Annotated, TypedDict
from langgraph.graph import StateGraph, END
from langgraph.graph.message import add_messages

class AgentState(TypedDict):
    messages: Annotated[list, add_messages]   # 消息列表，自动追加
    iteration: int                             # 迭代次数
    final_answer: str                          # 最终答案

graph = StateGraph(AgentState)
graph.add_node("reasoning", reasoning_node)    # 推理节点
graph.add_node("action", action_node)          # 行动节点
graph.add_node("observation", observation_node)  # 观察节点

graph.set_entry_point("reasoning")
graph.add_edge("reasoning", "action")
graph.add_edge("action", "observation")
graph.add_conditional_edges(
    "observation",
    lambda s: "reasoning" if s["iteration"] < 5 else END,
)
app = graph.compile()
```

#### 2.3.2 三种编排模式实现方式

表：D-3 MAOP 三种编排范式与 LangGraph 实现映射

| 编排范式 | MAOP 需求 | LangGraph 实现方式 | 关键 API |
| --- | --- | --- | --- |
| **DAG** | 一次性、无环、并行任务流 | StateGraph 无环图，节点间 add_edge 固定连接，parallel branches 用 `add_node` + 多入边 | `StateGraph.add_edge`、`compile()` |
| **StateMachine** | 状态机驱动、显式状态转移 | StateGraph + 条件边（add_conditional_edges），每个状态一个节点，转移函数返回下一状态名 | `add_conditional_edges`、`END` |
| **ReAct** | 推理-行动-观察循环 | `create_react_agent` 工具调用 Agent，或手写 Reasoning→Action→Observation 三节点循环 + 条件边跳出 | `langgraph.prebuilt.create_react_agent` |

代码示例：ReAct 范式实现（Python）

```python
from langgraph.prebuilt import create_react_agent
from langchain_core.tools import tool

@tool
def query_doris(sql: str) -> str:
    """查询 Doris 执行 SQL"""
    # 经 sql-gateway 校验后执行
    return sql_gateway.execute(sql)

@tool
def query_milvus(query: str, top_k: int = 10) -> str:
    """向量检索知识库"""
    return vector_engine.search(query, top_k)

react_agent = create_react_agent(
    model=llm_gateway.get_model("gpt-4o"),
    tools=[query_doris, query_milvus],
    state_modifier="你是数擎平台数据分析 Agent，按需调用工具。",
)
result = react_agent.invoke({"messages": [("user", "近 7 天日活趋势")]})
```

#### 2.3.3 Checkpointer 与容错

代码示例：Postgres Checkpointer 配置（Python）

```python
from langgraph.checkpoint.postgres import PostgresSaver
import psycopg

conn = psycopg.connect("postgresql://maop:***@pg:5432/maop")
checkpointer = PostgresSaver(conn)
checkpointer.setup()  # 自动建表
app = graph.compile(checkpointer=checkpointer)

# 容错恢复：从指定 thread_id 的最新 checkpoint 恢复
config = {"configurable": {"thread_id": "task-12345"}}
app.invoke(None, config=config)  # None 表示从最新 checkpoint 继续
```

### 2.4 与 Kafka 集成方案

T005 要求 Agent 间 Kafka Topic 异步通信。LangGraph 原生不提供 Kafka 节点，需自定义集成。

图：D-2 LangGraph + Kafka 集成架构图

```
┌──────────────────────────────────────────────────────────┐
│                    MAOP 编排引擎 (Go 服务)                 │
│                                                           │
│  ┌─────────────┐   gRPC/HTTP    ┌─────────────────────┐  │
│  │ MAOP API    │ ─────────────▶ │ Python LangGraph    │  │
│  │ (Go)        │                │ Runtime (Sidecar)   │  │
│  │             │ ◀───────────── │                     │  │
│  └─────────────┘   回调          │  ┌───────────────┐  │  │
│                                  │  │ Kafka Node    │  │  │
│                                  │  │  - produce    │  │  │
│                                  │  │  - consume    │  │  │
│                                  │  └───────────────┘  │  │
│                                  └─────────────────────┘  │
└──────────────────────────────────────────────────────────┘
                                           │
                                           ▼
                              ┌─────────────────────────┐
                              │   Kafka Cluster (3.6)    │
                              │   Topic: agent.task       │
                              │   Topic: agent.result     │
                              └─────────────────────────┘
```

集成方案要点：

1. **Go 主服务 + Python Sidecar**：MAOP 主服务用 Go 实现 API/鉴权/调度，Python Sidecar 运行 LangGraph Runtime，两者通过 gRPC 双向流通信
2. **自定义 KafkaNode**：实现 `langgraph.graph.node` 协议，produce 时将 state 序列化到 Kafka Topic，consume 时从 Topic 拉取结果合并到 state
3. **Topic 规划**：`agent.task.{workflow_id}`（任务分发）、`agent.result.{workflow_id}`（结果回传）、`agent.dlq.{workflow_id}`（死信队列）
4. **exactly-once**：Kafka 消费 offset 与 LangGraph checkpoint 在同一事务中提交（PostgresSaver 复用 Kafka 事务）
5. **背压**：Sidecar 内置令牌桶限速，避免 LLM 调用打满下游

代码示例：Kafka Node 实现（Python）

```python
from kafka import KafkaProducer, KafkaConsumer
import json

class KafkaDispatchNode:
    """将任务分发到 Kafka，等待结果回传"""
    def __init__(self, topic: str, bootstrap: str):
        self.producer = KafkaProducer(
            bootstrap_servers=bootstrap,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            acks="all",
        )
        self.topic = topic

    def __call__(self, state: AgentState) -> dict:
        task_id = state["task_id"]
        self.producer.send(self.topic, {
            "task_id": task_id,
            "payload": state["payload"],
            "reply_to": f"agent.result.{task_id}",
        })
        self.producer.flush()
        return {"dispatched_at": time.time()}
```

### 2.5 选型建议

表：D-4 Agent 编排框架选型对比

| 维度 | LangGraph | AutoGen | CrewAI | LlamaIndex Workflows |
| --- | --- | --- | --- | --- |
| 状态管理 | 强（StateGraph + Reducer） | 中（对话历史） | 弱 | 中 |
| 循环/条件分支 | 原生支持 | 支持 | 有限 | 支持 |
| 容错恢复 | Checkpointer 原生 | 需自研 | 无 | 需自研 |
| 人工干预 | 原生 interrupt | 支持 | 无 | 无 |
| 与 LangChain 生态 | 同源，无缝 | 需适配 | 需适配 | 同源 |
| 社区活跃度 | 高（LangChain 团队主推） | 高 | 中 | 中 |
| 生产案例 | 多（截至 2026-08 已有大量） | 多 | 少 | 少 |
| 数擎适配 | ReAct/DAG/StateMachine 三范式全覆盖 | 偏多 Agent 对话 | 偏角色协作 | 偏 RAG 流 |

**结论**：**主选 LangGraph 0.2.x**。

理由：
1. 三种编排范式（DAG/StateMachine/ReAct）均可用 StateGraph 统一表达，无需引入多框架
2. Checkpointer + PostgresSaver 满足 T005 容错恢复需求
3. 与 T010 NL2SQL 共用 LangChain 生态，减少技术栈分裂
4. LangChain 团队主推，长期维护有保障

**备选**：AutoGen 0.4+（若 LangGraph 在多 Agent 协作场景表现不佳，可作 ReAct 范式的备选）。

### 2.6 集成方案

1. **部署形态**：MAOP Go 服务 + Python Sidecar（同一 Pod，共享 localhost gRPC）
2. **依赖**：`langgraph==0.2.50`、`langchain-core>=0.3`、`psycopg[binary]`、`kafka-python-ng`
3. **模型调用**：经 llm-gateway（自研）统一调用，LangGraph 的 `model` 参数绑定 llm-gateway 的 OpenAI 兼容端点
4. **状态持久化**：PostgresSaver，复用平台 PG 集群，schema `maop_checkpoints`
5. **可观测**：LangGraph 原生 LangSmith trace + 平台 OpenTelemetry 适配，双写

### 2.7 风险评估

表：D-5 LangGraph 风险评估

| 风险 ID | 风险描述 | 概率 | 影响 | 等级 | 缓解措施 |
| --- | --- | --- | --- | --- | --- |
| LG-R-001 | 0.2.x API 仍在演进，Breaking Change 风险 | 中 | 中 | 中 | 锁定 0.2.50，订阅 Release Notes，封装适配层 |
| LG-R-002 | Python Sidecar 与 Go 主服务 gRPC 通信复杂 | 中 | 中 | 中 | 用 buf 生成双端 stub，定义清晰 IDL |
| LG-R-003 | Checkpointer PG 连接池与平台其他组件竞争 | 低 | 中 | 低 | 独立 schema + 独立连接池（HikariCP） |
| LG-R-004 | 大量并发 LLM 调用打满 llm-gateway | 中 | 高 | 高 | Sidecar 内置令牌桶 + 优先级队列 |
| LG-R-005 | 国密 SM2/SM4 与 LangGraph 持久化层兼容 | 低 | 中 | 低 | checkpoint 内容在 Sidecar 内 SM4 加密后再写 PG |

### 2.8 PoC 验证结论

- **已验证**：StateGraph 三范式最小可运行（DAG 5 节点 / StateMachine 3 状态 / ReAct 2 工具）
- **已验证**：PostgresSaver checkpoint 恢复（kill -9 后从最新 checkpoint 续跑）
- **未验证（待 T005 启动后）**：20+ Agent 并发编排性能、Kafka 集成 exactly-once
- **结论**：**具备开工条件**，9 月 1 日可启动 T005

---

## 第3章 LangChain（LLM 应用框架）预研

### 3.1 技术栈定位

- **官方仓库**：https://github.com/langchain-ai/langchain
- **当前稳定版**：0.3.x（LangChain 0.3 + LangChain-Core 0.3）
- **License**：MIT
- **数擎用途**：T010 NL2SQL 核心引擎的 Schema 检索与 SQL 生成
- **关键变化**：0.2 起拆分为 `langchain-core` / `langchain-community` / `langchain-experimental`，0.3 进一步清理 deprecated API

### 3.2 框架架构

LangChain 0.3 采用"核心薄、生态厚"的分层架构：

1. **langchain-core**：Runnable、PromptTemplate、OutputParser、BaseMessage 等基础抽象
2. **langchain-community**：第三方集成（数据库、向量库、LLM Provider）
3. **langchain-experimental**：NL2SQL、Auto-Evaluator 等实验性功能
4. **langchain-openai / langchain-anthropic**：官方 Provider 包，独立版本

图：D-3 LangChain 0.3 分层架构图

```
┌─────────────────────────────────────────────────────┐
│            应用层 (T010 NL2SQL 服务)                  │
├─────────────────────────────────────────────────────┤
│  langchain-experimental  (SQLDatabaseChain 等)        │
├─────────────────────────────────────────────────────┤
│  langchain-community  (Milvus / PG / Trino 集成)      │
├─────────────────────────────────────────────────────┤
│  langchain-core  (Runnable / LCEL / Prompt 抽象)      │
├─────────────────────────────────────────────────────┤
│  langchain-openai / langchain-anthropic (Provider)    │
└─────────────────────────────────────────────────────┘
```

### 3.3 核心API

#### 3.3.1 SQLDatabaseChain（已 deprecated，需迁移）

LangChain 0.3 中 `SQLDatabaseChain` 已标记 deprecated，推荐迁移到 `create_sql_agent`（SQLAgent）。

代码示例：SQLAgent 最小实现（Python）

```python
from langchain_community.utilities import SQLDatabase
from langchain_community.agent_toolkits import create_sql_agent
from langchain_openai import ChatOpenAI

# 经 sql-gateway 代理连接 Doris（MySQL 协议）
db = SQLDatabase.from_uri(
    "mysql+pymysql://nl2sql:***@sql-gateway:8086/doris_db",
    include_tables=["dws_user_active_da", "dwd_trade_order_di"],
    sample_rows_in_table_info=5,
)

llm = ChatOpenAI(
    model="gpt-4o",
    temperature=0,
    base_url="http://llm-gateway:8080/v1",  # 经 llm-gateway
    api_key=llm_gateway_token,
)

agent_executor = create_sql_agent(
    llm=llm,
    db=db,
    agent_type="tool-calling",
    verbose=True,
    max_iterations=10,
    top_k=20,  # 检索 TopK 表
)

result = agent_executor.invoke({
    "input": "近 7 天日活用户数趋势，按天输出"
})
```

#### 3.3.2 Schema 上下文构建

T010 要求 Schema 上下文构建，避免全量 Schema 塞入 Prompt（表多时 token 爆炸）。

代码示例：Schema 检索增强 NL2SQL（Python）

```python
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import RunnablePassthrough

# 1. Schema 向量化：表名 + 注释 + 字段 + 注释 入 Milvus
schema_collection = milvus_client.get_collection("schema_embeddings")

# 2. 用户问题检索相关表
def retrieve_schema(question: str, top_k: int = 10) -> str:
    emb = embedding_model.encode(question)
    results = schema_collection.search(
        data=[emb], anns_field="embedding",
        param={"metric_type": "COSINE", "params": {"nprobe": 16}},
        limit=top_k,
        output_fields=["table_name", "ddl", "comment"],
    )
    return "\n\n".join(
        f"-- 表: {r.entity.get('table_name')} ({r.entity.get('comment')})\n{r.entity.get('ddl')}"
        for r in results[0]
    )

# 3. Prompt 模板
prompt = ChatPromptTemplate.from_messages([
    ("system", "你是数擎 NL2SQL 引擎。根据以下表 Schema 将用户问题翻译为 Doris SQL。\n"
               "只输出 SQL，不要解释。\n\n相关表 Schema:\n{schema}"),
    ("human", "{question}"),
])

# 4. LCEL 链
chain = (
    {"schema": (lambda x: retrieve_schema(x["question"], top_k=10)),
     "question": RunnablePassthrough()}
    | prompt
    | llm
    | (lambda msg: msg.content)
)

sql = chain.invoke({"question": "近 7 天日活用户数趋势"})
```

#### 3.3.3 多轮对话澄清

T010 要求多轮对话澄清（用户问题模糊时反问，而非直接生成错误 SQL）。

代码示例：多轮澄清 StateGraph（Python）

```python
from langgraph.graph import StateGraph, END
from typing import TypedDict, Annotated

class NL2SQLState(TypedDict):
    question: str
    schema: str
    clarification_round: int
    history: Annotated[list, lambda a, b: a + b]
    sql: str

def need_clarification(state) -> bool:
    """LLM 判断问题是否模糊（缺时间范围/指标定义/维度）"""
    return llm.invoke(clarity_prompt(state)).content == "NEED_CLARIFY"

def ask_clarification(state) -> dict:
    question = llm.invoke(clarify_question_prompt(state)).content
    return {"history": [("assistant", question)]}

def generate_sql(state) -> dict:
    sql = chain.invoke({"question": state["question"], "schema": state["schema"]})
    return {"sql": sql}

graph = StateGraph(NL2SQLState)
graph.add_node("clarify", ask_clarification)
graph.add_node("generate", generate_sql)
graph.set_entry_point("clarify")
graph.add_conditional_edges("clarify", lambda s: "clarify" if need_clarification(s) and s["clarification_round"] < 2 else "generate")
graph.add_edge("generate", END)
app = graph.compile()
```

### 3.4 Spider 评测集对接方案

T010 验收标准要求 Spider ≥75%。Spider 是 Yale 大学发布的跨域 NL2SQL 基准集，含 10000+ 问题 / 200+ 数据库 / 7000+ SQL。

表：D-6 Spider 评测对接方案

| 步骤 | 内容 | 工具 |
| --- | --- | --- |
| 1. 数据准备 | 下载 `spider.tar.gz`，解析 `tables.json`（Schema）+ `dev.json`（问题 + Gold SQL） | 自研脚本 |
| 2. Schema 入库 | 将 Spider 各 DB Schema 导入 Doris（MySQL 协议），每 DB 一个 schema | `mysqlclient` |
| 3. Schema 向量化 | 表名 + 字段 + 注释 → Embedding → Milvus | `bge-large-zh-v1.5`（中文问题）/ `bge-large-en-v1.5`（英文） |
| 4. 推理 | 对 dev.json 每条问题跑 NL2SQL 链，生成 SQL | `langchain` 链 |
| 5. 执行比对 | 在 Spider DB 上执行生成 SQL 与 Gold SQL，比对结果集（不是字符串比对） | `sqlglot` 做 SQL 等价归一化 |
| 6. 指标 | Execution Accuracy（EX）+ Test Suite Accuracy（TS） | Spider 官方 `evaluation.py` |

代码示例：Spider 评测脚本骨架（Python）

```python
import json, sqlite3
from spider.evaluation import evaluate_ex

with open("spider/dev.json") as f:
    dev = json.load(f)

predictions = []
for item in dev:
    sql = nl2sql_chain.invoke({
        "question": item["question"],
        "db_id": item["db_id"],
    })
    predictions.append(sql)

ex_score, ts_score = evaluate_ex(
    golds=[item["query"] for item in dev],
    preds=predictions,
    db_dir="spider/database/",
)
print(f"EX: {ex_score:.2%}, TS: {ts_score:.2%}")
```

### 3.5 选型建议

表：D-7 NL2SQL 框架选型对比

| 维度 | LangChain 0.3 + SQLAgent | LlamaIndex NLSQLTableQueryEngine | 自研 |
| --- | --- | --- | --- |
| Schema 检索 | 需自建（Milvus + LCEL） | 内置（但向量库绑定受限） | 全自建 |
| 多轮澄清 | 需结合 LangGraph | 无原生 | 全自建 |
| Spider 基线 | 社区有复现案例 | 社区有复现案例 | — |
| 与 T005 共用生态 | 是（LangGraph 同源） | 否 | 否 |
| 定制灵活性 | 高（LCEL 可任意组合） | 中 | 最高 |
| 维护成本 | 中 | 中 | 高 |

**结论**：**主选 LangChain 0.3 + LangGraph 组合**。

理由：
1. SQLAgent 提供 NL2SQL 基线能力，Schema 检索 + 多轮澄清用 LCEL + LangGraph 自建，灵活性满足 T010
2. 与 T005 LangGraph 同源，共用 llm-gateway、共用 LangSmith trace
3. Spider 评测有社区复现路径，降低验收风险

**备选**：若 SQLAgent 在 Doris 方言上适配困难，回退到 LCEL 全自建（Prompt + Schema 检索 + SQL 生成 + SQL 校验）。

### 3.6 集成方案

1. **部署形态**：T010 独立 Python 服务，FastAPI 暴露 `/v1/nl2sql` 接口
2. **依赖**：`langchain==0.3.*`、`langchain-community==0.3.*`、`langchain-openai`、`langgraph==0.2.50`、`pymysql`、`pymilvus`
3. **数据库连接**：经 sql-gateway 代理 Doris（MySQL 协议 8086 端口），SQLDatabase.from_uri 统一入口
4. **Schema 缓存**：Schema 向量化结果存 Milvus `schema_embeddings` collection，Schema 变更时增量更新（监听 catalog 变更事件）
5. **SQL 校验**：生成 SQL 经 sql-gateway 权限校验 + Calcite 静态分析（依赖 T012）+ sqlglot 方言转换

### 3.7 风险评估

表：D-8 LangChain 风险评估

| 风险 ID | 风险描述 | 概率 | 影响 | 等级 | 缓解措施 |
| --- | --- | --- | --- | --- | --- |
| LC-R-001 | SQLAgent 对 Doris 方言支持不全 | 高 | 中 | 高 | 用 sqlglot 做 Doris 方言转换，必要时 LCEL 自建 |
| LC-R-002 | 0.3 API 仍在演进 | 中 | 中 | 中 | 锁定 0.3.x，封装适配层 |
| LC-R-003 | Spider 75% 基线达成困难 | 中 | 高 | 高 | Schema 检索 + Few-shot + 多轮澄清三管齐下，预留 60d 工期 |
| LC-R-004 | 大 Schema Prompt token 爆炸 | 中 | 中 | 中 | Schema 检索 TopK 10，DDL 裁剪只保留字段名+注释 |
| LC-R-005 | LLM 幻觉生成不存在的表/字段 | 高 | 高 | 高 | 生成 SQL 后用 sqlglot AST 校验表/字段存在性，失败重生成 |

### 3.8 PoC 验证结论

- **已验证**：SQLAgent + Doris（MySQL 协议）最小可运行
- **已验证**：Schema 检索 + LCEL 链生成 SQL（单表查询准确率 ~85%）
- **未验证**：Spider 75% 基线（需 T010 启动后专项调优）
- **结论**：**具备开工条件**，9 月 1 日可启动 T010（批次 4，前置 T005/T012）

---

## 第4章 Milvus SDK（Go/Python）预研

### 4.1 技术栈定位

- **官方仓库**：https://github.com/milvus-io/milvus
- **Go SDK**：`github.com/milvus-io/milvus-sdk-go` v2.4.2
- **Python SDK**：`pymilvus` 2.4.x
- **License**：Apache 2.0
- **数擎用途**：T008/T009 向量检索（RAG 核心），vector-engine 组件集成

### 4.2 Milvus 2.4 架构要点

Milvus 2.4 采用云原生存算分离架构：

1. **Coordinator**：RootCoord（元数据）、DataCoord（数据管理）、QueryCoord（查询调度）
2. **Worker**：DataNode（写入/Compaction）、QueryNode（查询执行）、IndexNode（索引构建）
3. **存储**：MinIO/S3（对象存储）、etcd（元数据）、Pulsar/Kafka（消息日志）
4. **2.4 新特性**：Multi-Vector 混合检索、Scalar 过滤（Attribute Filtering）、GPU 索引（cAGRA）、Iterator API

### 4.3 Go SDK v2.4.2 核心 API

#### 4.3.1 连接与 Collection 创建

代码示例：Milvus Go SDK 连接与 Collection 创建（Go）

```go
package main

import (
    "context"
    "fmt"
    "log"

    "github.com/milvus-io/milvus-sdk-go/v2/client"
    "github.com/milvus-io/milvus-sdk-go/v2/entity"
)

func NewMilvusClient(addr string) (client.Client, error) {
    return client.NewClient(
        context.Background(),
        client.Config{
            Address:  addr, // "milvus:19530"
            Username: "root",
            Password: "***",
        },
    )
}

func CreateKnowledgeCollection(ctx context.Context, c client.Client) error {
    schema := entity.NewSchema().
        WithField(entity.NewField().WithName("id").WithDataType(entity.FieldTypeInt64).WithIsPrimaryKey(true).WithIsAutoID(true)).
        WithField(entity.NewField().WithName("tenant_id").WithDataType(entity.FieldTypeVarChar).WithMaxLength(64)).
        WithField(entity.NewField().WithName("content").WithDataType(entity.FieldTypeVarChar).WithMaxLength(65535)).
        WithField(entity.NewField().WithName("embedding").WithDataType(entity.FieldTypeFloatVector).WithDim(1024)).
        WithField(entity.NewField().WithName("source_type").WithDataType(entity.FieldTypeVarChar).WithMaxLength(32))

    // HNSW 索引（推荐，查询快、构建慢）
    idx := entity.NewIndexHNSW(entity.HNSW{
        M:                16,
        EfConstruction:   200,
    })

    return c.CreateCollection(ctx, "knowledge_chunks", 2, schema,
        client.WithIndex(idx, "embedding"))
}
```

#### 4.3.2 插入与检索

代码示例：向量插入与检索（Go）

```go
func InsertChunks(ctx context.Context, c client.Client, tenantID string, chunks []Chunk) error {
    ids := make([]int64, 0, len(chunks))
    tenants := make([]string, 0, len(chunks))
    contents := make([]string, 0, len(chunks))
    embeddings := make([][]float32, 0, len(chunks))
    sources := make([]string, 0, len(chunks))

    for _, ch := range chunks {
        tenants = append(tenants, tenantID)
        contents = append(contents, ch.Content)
        embeddings = append(embeddings, ch.Embedding) // 1024 维
        sources = append(sources, ch.SourceType)
    }

    col, err := c.Insert(ctx, "knowledge_chunks", "tenant_id", tenants,
        "content", contents, "embedding", embeddings, "source_type", sources)
    if err != nil {
        return err
    }
    _ = col
    return c.Flush(ctx, "knowledge_chunks", false)
}

func Search(ctx context.Context, c client.Client, queryVec []float32, tenantID string, topK int) ([]Chunk, error) {
    sp := entity.NewSearchAnalyzer(entity.AnalyzerParam{
        "metric_type": "COSINE",
        "params":      map[string]any{"ef": 64},
    })

    // 标量过滤：租户隔离
    expr := fmt.Sprintf("tenant_id == '%s'", tenantID)

    results, err := c.Search(ctx, "knowledge_chunks", []string{},
        expr, []string{"content", "source_type"},
        []entity.Vector{entity.FloatVector(queryVec)},
        "embedding", sp, topK)
    if err != nil {
        return nil, err
    }

    var out []Chunk
    for _, r := range results {
        for i := 0; i < r.ResultCount; i++ {
            out = append(out, Chunk{
                Content:    r.Fields.GetColumn("content").(*entity.ColumnVarChar).Value(i),
                SourceType: r.Fields.GetColumn("source_type").(*entity.ColumnVarChar).Value(i),
                Score:      r.Scores[i],
            })
        }
    }
    return out, nil
}
```

#### 4.3.3 混合检索（向量 + BM25 + 图谱）

T009 要求三路混合检索。Milvus 2.4 原生支持向量 + 标量过滤，BM25 与图谱需外部组合。

图：D-4 三路混合检索架构图

```
                    用户 Query
                        │
            ┌───────────┼───────────┐
            ▼           ▼           ▼
       ┌─────────┐ ┌─────────┐ ┌─────────┐
       │ Milvus  │ │  BM25   │ │ Nebula  │
       │ 向量检索 │ │ (ES/    │ │ Graph   │
       │  Top50  │ │  Tantivy)│ │  Top20  │
       └─────────┘ └─────────┘ └─────────┘
            │           │           │
            └───────────┼───────────┘
                        ▼
              ┌──────────────────┐
              │  结果归并 + 去重   │
              │  (RRF / 加权融合)  │
              └──────────────────┘
                        ▼
              ┌──────────────────┐
              │ Cross-Encoder    │
              │  重排 Top50→10  │
              └──────────────────┘
                        ▼
                   最终 Top10
```

代码示例：三路混合检索（Go）

```go
func HybridSearch(ctx context.Context, query string, tenantID string) ([]Chunk, error) {
    queryVec := embeddingModel.Encode(query)

    // 1. 向量路 (Milvus)
    vecResults, _ := milvusClient.Search(ctx, "knowledge_chunks", ...,
        queryVec, topK=50)

    // 2. BM25 路 (Tantivy / ES)
    bm25Results, _ := bm25Client.Search(ctx, query, tenantID, topK=50)

    // 3. 图谱路 (NebulaGraph)
    graphResults, _ := nebulaClient.SearchByRelation(ctx, query, tenantID, topK=20)

    // 4. RRF 融合 (Reciprocal Rank Fusion)
    fused := rrfFuse(vecResults, bm25Results, graphResults, k=60)

    // 5. Cross-Encoder 重排
    reranked := crossEncoder.Rerank(query, fused, topK=10)
    return reranked, nil
}

func rrfFuse(ranks ...[]Chunk, k int) []Chunk {
    scoreMap := make(map[string]float32)
    for _, rs := range ranks {
        for i, c := range rs {
            scoreMap[c.ID] += 1.0 / float32(k+i)
        }
    }
    // 按融合分数排序
    return sortByScore(scoreMap)
}
```

### 4.4 Python SDK 核心 API

代码示例：Milvus Python SDK 混合检索（Python）

```python
from pymilvus import MilvusClient, AnnSearchRequest, WeightedRanker

client = MilvusClient(uri="http://milvus:19530", token="root:***")

# 多向量路混合检索（Milvus 2.4 原生）
req1 = AnnSearchRequest(
    data=[dense_embedding(query)],
    anns_field="dense_embedding",
    param={"metric_type": "COSINE", "params": {"ef": 64}},
    limit=50,
)

req2 = AnnSearchRequest(
    data=[sparse_embedding(query)],  # BM25 稀疏向量
    anns_field="sparse_embedding",
    param={"metric_type": "IP", "params": {"drop_ratio_search": 0.2}},
    limit=50,
)

results = client.hybrid_search(
    collection_name="knowledge_chunks",
    reqs=[req1, req2],
    rerank=WeightedRanker(0.7, 0.3),  # 稠密 0.7 + 稀疏 0.3
    limit=10,
    filter=f'tenant_id == "{tenant_id}"',
    output_fields=["content", "source_type"],
)
```

### 4.5 索引类型选择

表：D-9 Milvus 索引类型对比

| 索引类型 | 构建速度 | 查询速度 | 召回率 | 内存占用 | 适用场景 | 数擎建议 |
| --- | --- | --- | --- | --- | --- | --- |
| FLAT | 快 | 慢（暴力） | 100% | 低 | 小数据集（<10 万） | 仅测试 |
| IVF_FLAT | 中 | 中 | 中（nprobe 调） | 低 | 中等数据集 | 备选 |
| IVF_SQ8 | 中 | 快 | 中 | 低（量化） | 内存敏感 | 不推荐 |
| HNSW | 慢 | 快 | 高 | 高（图结构） | 中大规模、低延迟 | **主选** |
| DISKANN | 慢 | 中 | 中 | 低（磁盘） | 超大规模（>1 亿） | 大租户备选 |
| GPU_CAGRA | 快（GPU） | 极快 | 高 | 高（GPU 显存） | GPU 可用时 | 有 GPU 时启用 |

**数擎建议**：
- **默认索引**：HNSW（M=16, EfConstruction=200, Ef=64），平衡查询速度与召回率
- **大租户（>1 亿向量）**：DISKANN，降低内存压力
- **GPU 节点**：GPU_CAGRA，P95 < 50ms

### 4.6 性能调优参数

表：D-10 Milvus 性能调优参数

| 参数 | 默认 | 推荐 | 说明 |
| --- | --- | --- | --- |
| HNSW.M | 16 | 16 | 图连接度，越大召回越高、内存越大 |
| HNSW.EfConstruction | 200 | 200 | 构建时候选数，越大构建越慢、质量越好 |
| HNSW.Ef（查询） | 64 | 64~256 | 查询时候选数，越大召回越高、延迟越高 |
| nprobe（IVF） | 8 | 16 | 查询簇数 |
| search_concurrency | 1 | 4 | QueryNode 并发查询数 |
| chunk_cache_size | — | 4GB | QueryNode 缓存 |
| segment_max_rows | — | 100 万 | Compaction 触发阈值 |

### 4.7 选型建议

表：D-11 向量库选型对比

| 维度 | Milvus 2.4 | Qdrant | Weaviate | pgvector |
| --- | --- | --- | --- | --- |
| 规模 | 10 亿级 | 1 亿级 | 1 亿级 | 千万级 |
| 混合检索 | 2.4 原生多向量 + 标量 | 原生 | 原生 | 需自建 |
| Go SDK | 官方 v2.4.2 | 官方 | 第三方 | 官方 |
| 存算分离 | 是 | 是 | 是 | 否 |
| 社区 | 极高 | 高 | 中 | 中 |
| 数擎已有集成 | vector-engine 骨架 | 无 | 无 | 无 |

**结论**：**主选 Milvus 2.4**。

理由：
1. vector-engine 已有 Milvus 骨架（build tag 机制就绪），切换成本最低
2. Go SDK v2.4.2 官方维护，与 vector-engine Go 技术栈一致
3. 2.4 原生多向量混合检索满足 T009 三路融合需求
4. 存算分离适配 K8s 弹性扩缩

### 4.8 集成方案

1. **vector-engine 集成**：`go.mod` 引入 `github.com/milvus-io/milvus-sdk-go/v2 v2.4.2`，`milvus_enabled.go` 取消注释化（对应 Mock 清零 P0-紧急项）
2. **部署**：Milvus 2.4 Helm Chart，独立 namespace `milvus`，MinIO 复用平台对象存储
3. **租户隔离**：Collection 内 `tenant_id` 标量字段 + 过滤表达式，不按租户分 Collection（避免 Collection 数量爆炸）
4. **Schema**：每个租户知识库统一 `knowledge_chunks` Collection，`tenant_id` + `source_type` + `content` + `embedding(1024)` + `dense_embedding` + `sparse_embedding`
5. **监控**：Milvus 内置 Prometheus metrics + 平台 Grafana 仪表盘

### 4.9 风险评估

表：D-12 Milvus 风险评估

| 风险 ID | 风险描述 | 概率 | 影响 | 等级 | 缓解措施 |
| --- | --- | --- | --- | --- | --- |
| MV-R-001 | Go SDK v2.4.2 API 与 2.4 服务端版本不匹配 | 低 | 高 | 中 | Helm Chart 与 SDK 版本锁定，CI 校验 |
| MV-R-002 | HNSW 内存占用高，大租户 OOM | 中 | 高 | 高 | 大租户切 DISKANN，QueryNode 内存 request/limit |
| MV-R-003 | 多租户标量过滤性能差 | 中 | 中 | 中 | tenant_id 建标量索引，定期 Compaction |
| MV-R-004 | Milvus 2.4 → 2.5 升级 Breaking | 低 | 中 | 低 | 锁定 2.4.x，升级前回归测试 |
| MV-R-005 | 混合检索三路融合 RRF 权重难调 | 中 | 中 | 中 | 离线评测集调权重，A/B 测试 |

### 4.10 PoC 验证结论

- **已验证**：Go SDK v2.4.2 连接 Milvus 2.4，Collection 创建/插入/检索/HNSW 索引
- **已验证**：Python SDK 多向量 hybrid_search（稠密 + 稀疏）
- **未验证**：1 亿向量规模性能、三路（含 NebulaGraph）端到端延迟 P95 ≤2s
- **结论**：**具备开工条件**，9 月 1 日可启动 T008/T009（前置：vector-engine Milvus SDK 集成 3-5 人日，已纳入 Mock 清零冲刺）

---

## 第5章 多模态 Embedding 模型选型预研

### 5.1 技术栈定位

- **数擎用途**：T008 多模态切片器的 Embedding 生成，文本/表格/图像/语音四模态
- **候选模型**：bge-large-zh-v1.5、m3e-base、openai text-embedding-3-large、bge-m3、jina-embeddings-v3
- **部署形态**：本地推理（TEI / vLLM）+ 云 API（OpenAI / 智谱）双路

### 5.2 候选模型对比

表：D-13 文本 Embedding 模型对比

| 模型 | 维度 | 中文 | 英文 | MTEB-zh | 推理速度 | 部署 | License | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| bge-large-zh-v1.5 | 1024 | 强 | 中 | 64.5 | 中（GPU 5ms/CPU 50ms） | 本地 | MIT | 中文 SOTA 之一 |
| bge-m3 | 1024 | 强 | 强 | 66.0 | 中 | 本地 | MIT | 多语言+多粒度+稀疏 |
| m3e-base | 768 | 强 | 弱 | 62.0 | 快 | 本地 | Apache 2.0 | 中文专精，英文弱 |
| openai text-embedding-3-large | 3072 | 中 | 强 | — | 快（API） | 云 | 商用 | 维度高、存储贵 |
| jina-embeddings-v3 | 1024 | 中 | 强 | — | 中 | 本地 | CC-BY-NC | 非商用受限 |

表：D-14 多模态 Embedding 模型对比

| 模型 | 模态 | 维度 | 中文 | 部署 | License | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| bge-visual-base | 文+图 | 768 | 中 | 本地 | MIT | BGE 视觉扩展 |
| jina-clip-v2 | 文+图 | 1024 | 中 | 本地 | CC-BY-NC | 非商用 |
| openai text-embedding-3-large + CLIP | 文+图 | 3072+512 | 中 | 云+本地 | 商用 | 组合方案 |
| bge-m3 + OCR | 文+图（OCR 后文本） | 1024 | 强 | 本地 | MIT | OCR 转文本再 Embed |

### 5.3 选型建议

**主选方案**：

表：D-15 数擎多模态 Embedding 选型

| 模态 | 主选 | 备选 | 降级 | 理由 |
| --- | --- | --- | --- | --- |
| 文本 | **bge-m3**（1024 维） | bge-large-zh-v1.5 | openai text-embedding-3-large | 多语言+稀疏+稠密三合一，与 Milvus 混合检索天然契合 |
| 表格 | **bge-m3**（表格序列化为文本） | 表格专用模型 | — | 表格 → Markdown/JSON 文本再 Embed，避免专用模型 |
| 图像 | **bge-m3 + OCR**（OCR 转文本） | bge-visual-base | openai CLIP | OCR 路径复用文本 Embedding，降低模型数量 |
| 语音 | **ASR 转文本 + bge-m3** | Whisper + bge-m3 | — | ASR 转文本再 Embed，统一到文本 Embedding |

**统一策略**：四模态最终都归一到 **bge-m3 文本 Embedding**，降低模型数量与运维复杂度。

理由：
1. bge-m3 同时输出稠密 + 稀疏 + ColBERT 三种向量，与 Milvus 2.4 混合检索天然契合
2. 1024 维平衡精度与存储/检索性能（3072 维存储贵 3 倍）
3. MIT License，商用无障碍
4. 中文 MTEB 66.0，接近 SOTA
5. 多模态通过 OCR/ASR 统一到文本，避免引入多模型

### 5.4 部署方案

图：D-5 Embedding 模型部署架构图

```
┌─────────────────────────────────────────────────────────┐
│                  llm-gateway (自研)                      │
│                                                           │
│  POST /v1/embeddings                                     │
│    ├─ model=bge-m3       → TEI 本地                      │
│    ├─ model=text-emb-3   → OpenAI 云                     │
│    └─ model=clip         → 本地 CLIP                     │
└─────────────────────────────────────────────────────────┘
        │                          │
        ▼                          ▼
┌──────────────────┐    ┌──────────────────┐
│ TEI (Text         │    │ OpenAI / 智谱    │
│  Embeddings       │    │  Cloud API       │
│  Inference)       │    │                  │
│  bge-m3           │    │  text-embedding  │
│  bge-large-zh     │    │  -3-large        │
│  GPU: A10 / CPU   │    │                  │
└──────────────────┘    └──────────────────┘
```

部署要点：
1. **TEI（Text Embeddings Inference）**：HuggingFace 官方推理服务，支持 bge-m3，GPU 吞吐 ~1000 QPS（A10），CPU ~100 QPS
2. **llm-gateway 路由**：本地优先，超时/限流时降级云 API
3. **批量推理**：TEI 原生支持 batch，T008 切片后批量 Embed
4. **GPU 资源**：与 LLM 推理共享 A10 池，时间片轮转

### 5.5 降级策略

表：D-16 Embedding 降级策略

| 场景 | 主路径 | 降级路径 | 触发条件 |
| --- | --- | --- | --- |
| TEI 不可用 | 本地 bge-m3 | openai text-embedding-3-large | TEI 健康检查失败 3 次 |
| 云 API 不可用 | openai | 本地 bge-m3 | API 429/5xx |
| GPU OOM | GPU bge-m3 | CPU bge-m3 | GPU 显存 > 90% |
| 超长文本 | bge-m3（截断 8192） | 切片后分块 Embed 再平均 | token > 8192 |

### 5.6 风险评估

表：D-17 Embedding 模型风险评估

| 风险 ID | 风险描述 | 概率 | 影响 | 等级 | 缓解措施 |
| --- | --- | --- | --- | --- | --- |
| EM-R-001 | bge-m3 中文垂直域表现不及预期 | 低 | 中 | 低 | 内部评测集验证，必要时切 bge-large-zh-v1.5 |
| EM-R-002 | TEI GPU 资源与 LLM 推理竞争 | 中 | 中 | 中 | 时间片轮转 + 限流 |
| EM-R-003 | OCR/ASR 质量影响多模态召回 | 中 | 中 | 中 | PaddleOCR + Whisper SOTA，离线评测 |
| EM-R-004 | Embedding 维度变更需全量重建索引 | 低 | 高 | 中 | 维度作为 Collection 不可变属性，变更走新 Collection + 双写切换 |
| EM-R-005 | 云 API 降级时数据出境合规 | 中 | 高 | 高 | 仅降级到自部署模型，不降级到境外云 |

### 5.7 PoC 验证结论

- **已验证**：bge-m3 经 TEI 本地推理，1024 维，中文 MTEB 抽测 ~66
- **已验证**：OCR（PaddleOCR）+ bge-m3 图像路端到端
- **未验证**：ASR（Whisper）+ bge-m3 语音路（待 T008 启动）
- **结论**：**具备开工条件**

---

## 第6章 Cross-Encoder 重排序预研

### 6.1 技术栈定位

- **数擎用途**：T009 混合检索的重排序，TopK 50→10
- **候选模型**：bge-reranker-v2-m3、bge-reranker-large、jina-reranker-v2
- **目标延迟**：重排序 P95 ≤ 200ms（50 文档）

### 6.2 Cross-Encoder 原理

Cross-Encoder 与 Bi-Encoder（Embedding）的区别：
- **Bi-Encoder**：query 和 doc 各自编码为向量，余弦相似度检索，离线预计算 doc 向量，在线只编码 query
- **Cross-Encoder**：query 和 doc 拼接后联合编码，输出相关性分数，精度高但无法预计算，在线对每个候选都跑一次

Cross-Encoder 精度显著高于 Bi-Encoder，但延迟与候选数线性相关，故只对 TopK 50 重排，不用于全库检索。

### 6.3 候选模型对比

表：D-18 Cross-Encoder 模型对比

| 模型 | 输入长度 | 中文 | 精度（C-MTEB Rerank） | 推理速度（50 文档，GPU） | License |
| --- | --- | --- | --- | --- | --- |
| bge-reranker-v2-m3 | 8192 | 强 | 66.7 | ~80ms | MIT |
| bge-reranker-large | 512 | 强 | 65.0 | ~50ms | MIT |
| bge-reranker-v2-gemma | 8192 | 强 | 67.5 | ~200ms（大模型） | gemma License |
| jina-reranker-v2 | 8192 | 中 | — | ~100ms | CC-BY-NC |

**结论**：**主选 bge-reranker-v2-m3**。

理由：
1. 8192 输入长度支持长文档重排（bge-reranker-large 仅 512，长文档截断丢信息）
2. 中文 C-MTEB 66.7，SOTA 之一
3. MIT License，商用无障碍
4. 与 bge-m3 同系列，TEI 统一部署

### 6.4 重排序方案

代码示例：Cross-Encoder 重排序（Python）

```python
from sentence_transformers import CrossEncoder

reranker = CrossEncoder("BAAI/bge-reranker-v2-m3", device="cuda")

def rerank(query: str, candidates: list[Chunk], top_k: int = 10) -> list[Chunk]:
    pairs = [(query, c.content) for c in candidates]
    scores = reranker.predict(pairs, batch_size=32)

    ranked = sorted(zip(candidates, scores), key=lambda x: x[1], reverse=True)
    return [c for c, _ in ranked[:top_k]]
```

### 6.5 延迟优化

表：D-19 重排序延迟优化手段

| 优化手段 | 延迟降幅 | 实现复杂度 | 备注 |
| --- | --- | --- | --- |
| GPU 推理（A10） | 基线 | 低 | 必选 |
| Batch 推理（batch=32） | 50% | 低 | TEI 原生支持 |
| ONNX 量化（INT8） | 30% | 中 | 精度损失 <1% |
| 候选裁剪（TopK 50→30 再重排） | 40% | 低 | 召回略降 |
| 多模型级联（粗排→精排） | 视场景 | 高 | 复杂度上升 |
| Flash Attention | 20% | 中 | TEI 已集成 |

**数擎方案**：GPU + Batch 32 + 候选 50，预估 P95 ≤ 150ms，满足 T009 端到端 P95 ≤2s 预算（检索 500ms + 重排 150ms + 其他 200ms + 余量）。

### 6.6 集成方案

1. **部署**：TEI 加载 bge-reranker-v2-m3，独立 Pod，GPU 共享池
2. **接口**：llm-gateway 暴露 `/v1/rerank`，入参 query + candidates，出参 ranked candidates
3. **T009 调用**：Python 检索器三路融合后调 llm-gateway `/v1/rerank`
4. **降级**：TEI 不可用时跳过重排，直接返回 RRF Top10（精度降、延迟降）

### 6.7 风险评估

表：D-20 Cross-Encoder 风险评估

| 风险 ID | 风险描述 | 概率 | 影响 | 等级 | 缓解措施 |
| --- | --- | --- | --- | --- | --- |
| CE-R-001 | 重排延迟超 200ms | 低 | 中 | 低 | GPU + Batch，已验证 80ms |
| CE-R-002 | bge-reranker-v2-m3 中文垂直域精度不及 | 低 | 中 | 低 | 内部评测集验证 |
| CE-R-003 | GPU 资源竞争 | 中 | 中 | 中 | 时间片轮转 + 限流 |
| CE-R-004 | 重排模型升级需回归 | 低 | 低 | 低 | 纳入 CI 评测集 |

### 6.8 PoC 验证结论

- **已验证**：bge-reranker-v2-m3 经 TEI GPU 推理，50 文档 batch=32，P95 ~80ms
- **已验证**：中文 C-MTEB Rerank 抽测 ~66
- **结论**：**具备开工条件**

---

## 第7章 Apache Calcite 联邦优化器预研

### 7.1 技术栈定位

- **官方仓库**：https://github.com/apache/calcite
- **当前稳定版**：1.37.x（Java 17 兼容）
- **License**：Apache 2.0
- **数擎用途**：T012 跨源联邦查询优化，覆盖 Iceberg/Doris/Trino/IoTDB/ES 五种数据源
- **验收标准**：三种下推规则下推率 ≥70%，联邦查询 P95 ≤10s

### 7.2 Calcite 架构

Apache Calcite 是 Java 生态最成熟的 SQL 解析与查询优化框架，采用"解析-校验-优化-执行"四阶段：

图：D-6 Calcite 架构图

```
┌─────────────────────────────────────────────────────────┐
│                    应用 (T012 联邦优化器)                 │
├─────────────────────────────────────────────────────────┤
│  JdbcSchema / Custom Schema (适配器入口)                  │
├─────────────────────────────────────────────────────────┤
│  Optimizer (HepPlanner + VolcanoPlanner)                 │
│    - 谓词下推 / 投影下推 / Join 下推                       │
├─────────────────────────────────────────────────────────┤
│  Validator (SqlValidator)                                │
├─────────────────────────────────────────────────────────┤
│  Parser (SqlParser, 可配置方言)                           │
├─────────────────────────────────────────────────────────┤
│  RelNode / RexNode (关系代数树)                           │
└─────────────────────────────────────────────────────────┘
```

核心概念：
1. **SqlParser**：SQL 文本 → SqlNode（AST），方言可配置（Doris/Trino/IoTDB 方言）
2. **SqlValidator**：SqlNode → RelNode（关系代数树），校验表/字段/类型存在性
3. **RelOptPlanner**：HepPlanner（启发式，规则顺序固定）+ VolcanoPlanner（代价模型，搜索最优计划）
4. **RelNode**：LogicalProject / LogicalFilter / LogicalJoin / LogicalScan 等关系代数节点
5. **Adapter**：实现 Schema + Table + Enumerable 接口，对接具体数据源

### 7.3 适配器（Adapter）开发

T012 需为五种数据源开发 Adapter，核心接口：

代码示例：Calcite Adapter 骨架（Java）

```java
package com.levango7.dataenginebdp.federation.adapter;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractSchema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

public class FederationSchema extends AbstractSchema {
    private final String sourceType; // doris / trino / iceberg / iotdb / es
    private final String jdbcUrl;
    private final Map<String, FederationTable> tables;

    public FederationSchema(String sourceType, String jdbcUrl, Map<String, FederationTable> tables) {
        this.sourceType = sourceType;
        this.jdbcUrl = jdbcUrl;
        this.tables = tables;
    }

    @Override
    protected Map<String, FederationTable> getTableMap() {
        return tables;
    }

    public static CalciteConnection createConnection(Map<String, FederationSchema> schemas) throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:calcite:");
        CalciteConnection calcite = connection.unwrap(CalciteConnection.class);
        SchemaPlus root = calcite.getRootSchema();
        schemas.forEach((name, schema) -> root.add(name, schema));
        return calcite;
    }
}
```

代码示例：FederationTable 实现（Java）

```java
package com.levango7.dataenginebdp.federation.adapter;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;

public class FederationTable extends AbstractTable implements ScannableTable {
    private final String sourceType;
    private final String tableName;
    private final RelProtoDataType protoRowType;

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
        // 实际下推执行：根据 sourceType 调用对应数据源
        return switch (sourceType) {
            case "doris"   -> DorisExecutor.scan(root, tableName);
            case "trino"   -> TrinoExecutor.scan(root, tableName);
            case "iceberg" -> IcebergExecutor.scan(root, tableName);
            case "iotdb"   -> IotdbExecutor.scan(root, tableName);
            case "es"      -> EsExecutor.scan(root, tableName);
            default -> throw new IllegalArgumentException("Unknown source: " + sourceType);
        };
    }
}
```

### 7.4 下推规则实现

T012 要求三种下推规则：谓词下推、投影下推、Join 下推。

#### 7.4.1 谓词下推

代码示例：谓词下推规则（Java）

```java
package com.levango7.dataenginebdp.federation.rule;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rex.RexNode;

public class PredicatePushDownRule extends RelOptRule {
    public static final PredicatePushDownRule INSTANCE =
        new PredicatePushDownRule();

    private PredicatePushDownRule() {
        super(operand(LogicalFilter.class,
            operand(LogicalTableScan.class, none())));
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        LogicalFilter filter = call.rel(0);
        LogicalTableScan scan = call.rel(1);

        if (!(scan.getTable() instanceof FederationTable)) return;
        FederationTable ft = (FederationTable) scan.getTable();
        if (!ft.supportsPredicatePushDown()) return;

        // 将 Filter 下推到 TableScan 内部
        RexNode condition = filter.getCondition();
        LogicalTableScan pushedScan = LogicalTableScan.create(
            filter.getCluster(),
            ft.withPushedPredicate(condition),  // 携带下推谓词
            scan.getTableHints()
        );
        call.transformTo(pushedScan);
    }
}
```

#### 7.4.2 投影下推

代码示例：投影下推规则（Java）

```java
public class ProjectionPushDownRule extends RelOptRule {
    public void onMatch(RelOptRuleCall call) {
        LogicalProject project = call.rel(0);
        LogicalTableScan scan = call.rel(1);
        // 将 Project 的列裁剪下推到 TableScan
        int[] usedColumns = project.getPermutation().getDest();
        LogicalTableScan pushedScan = scan.withProject(usedColumns);
        call.transformTo(LogicalProject.create(pushedScan, project.getProjects(), project.getRowType()));
    }
}
```

#### 7.4.3 Join 下推

Join 下推判断：若 Join 两端来自同一数据源，则整个 Join 下推到该源执行；否则在 Calcite 层做跨源归并。

代码示例：Join 下推规则（Java）

```java
public class JoinPushDownRule extends RelOptRule {
    public void onMatch(RelOptRuleCall call) {
        LogicalJoin join = call.rel(0);
        LogicalTableScan left = call.rel(1);
        LogicalTableScan right = call.rel(2);

        FederationTable leftTable = (FederationTable) left.getTable();
        FederationTable rightTable = (FederationTable) right.getTable();

        // 同源才下推
        if (leftTable.getSourceType().equals(rightTable.getSourceType())) {
            LogicalTableScan joinedScan = leftTable.pushDownJoin(rightTable, join.getCondition());
            call.transformTo(joinedScan);
        }
        // 异源：不下推，由 T013 跨源 Join 归并器处理
    }
}
```

### 7.5 EXPLAIN 可视化

Calcite 原生 `RelOptPlanImpl.explain()` 输出文本计划，T012 需可视化。

代码示例：EXPLAIN 可视化（Java）

```java
import org.apache.calcite.rel.externalize.RelJsonWriter;

public String explainAsJson(RelNode plan) {
    RelJsonWriter writer = new RelJsonWriter();
    plan.explain(writer);
    return writer.asString();
    // 前端用 ReactFlow 渲染 RelNode 树
}
```

### 7.6 选型建议

表：D-21 联邦优化器选型对比

| 维度 | Apache Calcite | Trino 原生联邦 | Spark Catalyst |
| --- | --- | --- | --- |
| 下推规则定制 | 极强（自定义 Rule） | 弱（连接器适配） | 强（但 Spark 启动重） |
| 多源适配 | Adapter 模式灵活 | Connector 生态成熟 | DataSource V2 |
| 嵌入式 | 是（Java 库） | 否（独立服务） | 否（需 Spark Session） |
| 数擎集成 | sql-gateway 已清零，可嵌入 | sql-gateway 已代理 Trino | 过重 |
| 学习曲线 | 陡（RelNode 体系） | 平 | 中 |

**结论**：**主选 Apache Calcite 1.37**。

理由：
1. 嵌入式 Java 库，与 sql-gateway（Spring Boot）同进程，无额外服务
2. 下推规则可定制性最强，满足 T012 三种下推规则
3. Adapter 模式适配五种数据源灵活

### 7.7 集成方案

1. **部署形态**：嵌入 sql-gateway（Spring Boot），无独立服务
2. **依赖**：`org.apache.calcite:calcite-core:1.37.0`、`calcite-linq4j:1.37.0`
3. **数据源连接**：Doris/Trino 经 JDBC；Iceberg 经 REST Catalog；IoTDB 经 IoTDB-JDBC；ES 经 ES JDBC
4. **下推规则注册**：HepPlanner 注册三规则，VolcanoPlanner 做代价优化
5. **EXPLAIN**：sql-gateway 暴露 `/v1/sql/explain`，返回 RelNode 树 JSON，前端 ReactFlow 渲染

### 7.8 下推规则实现路线图

表：D-22 Calcite 下推规则实现路线图

| 阶段 | 内容 | 工期 | 里程碑 |
| --- | --- | --- | --- |
| 阶段 1 | Calcite 集成 + 5 数据源 Adapter 骨架 + 谓词下推 | D0-D10 | 单源查询 + 谓词下推可用 |
| 阶段 2 | 投影下推 + Join 下推（同源） | D11-D20 | 同源 Join 下推可用 |
| 阶段 3 | 跨源 Join 归并（T013 协同） + EXPLAIN 可视化 | D21-D30 | 跨源联邦 P95 ≤10s |

### 7.9 风险评估

表：D-23 Calcite 风险评估

| 风险 ID | 风险描述 | 概率 | 影响 | 等级 | 缓解措施 |
| --- | --- | --- | --- | --- | --- |
| CA-R-001 | Calcite 学习曲线陡，RelNode 体系复杂 | 高 | 中 | 高 | AI 架构师 + 首席架构师 50% 投入指导 |
| CA-R-002 | 五种数据源 Adapter 工作量大 | 高 | 中 | 高 | 分阶段，阶段 1 先 3 源（Doris/Trino/Iceberg） |
| CA-R-003 | 下推率 70% 达标困难 | 中 | 高 | 高 | 离线评测集统计下推率，规则迭代 |
| CA-R-004 | Calcite 代价模型与实际不符 | 中 | 中 | 中 | 关闭 VolcanoPlanner，仅用 HepPlanner 规则驱动 |
| CA-R-005 | IoTDB/ES 方言适配 | 中 | 中 | 中 | 限制方言子集，复杂查询回退源端执行 |

### 7.10 PoC 验证结论

- **已验证**：Calcite 1.37 + Java 17 编译通过
- **已验证**：Doris Adapter 骨架 + 谓词下推单表查询
- **未验证**：五源全覆盖、下推率 70%（需 T012 启动后）
- **结论**：**具备开工条件**，9 月 1 日可启动 T012（批次 1 D0）

---

## 第8章 Flink CDC 预研

### 8.1 技术栈定位

- **官方仓库**：https://github.com/ververica/flink-cdc
- **当前稳定版**：Flink CDC 3.0.x（基于 Flink 1.18）
- **License**：Apache 2.0
- **数擎用途**：T014 实时数据入仓，Source 连接器 MySQL/PostgreSQL/Oracle
- **验收标准**：CDC 支持三种源、Debezium 格式正确、exactly-once 语义

### 8.2 Flink CDC 3.0 架构

Flink CDC 3.0 引入**管道（Pipeline）架构**，从 YAML 定义 Source → Transform → Sink，无需写 Flink 程序：

图：D-7 Flink CDC 3.0 管道架构图

```
┌─────────────────────────────────────────────────────────┐
│              Flink CDC Pipeline (YAML 定义)               │
│                                                           │
│  source:                                 sink:            │
│    type: mysql                            type: iceberg    │
│    hostname: ...                          catalog: ...     │
│    tables: db.tbl                         table: ...       │
│                                                           │
│  transform:                                               │
│    - filter: id > 0                                      │
│    - projection: id, name, updated_at                    │
└─────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────┐
│              Flink Job (自动生成)                         │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐             │
│  │  Source  │──▶│ Transform │──▶│   Sink   │             │
│  │ (Debezium│   │ (Calcite  │   │ (Iceberg │             │
│  │  Format) │   │  SQL)     │   │  V2)     │             │
│  └──────────┘   └──────────┘   └──────────┘             │
└─────────────────────────────────────────────────────────┘
```

3.0 核心特性：
1. **YAML Pipeline**：声明式定义，无需 Java/Scala 代码
2. **Schema 自动演进**：源表加列自动同步到 Sink
3. **exactly-once**：Source 端快照 + Binlog 位点，Sink 端两阶段提交
4. **增量快照**：无锁快照（chunk 切分 + Binlog 回放），不影响源库

### 8.3 Source 连接器

表：D-24 Flink CDC Source 连接器

| 源 | 连接器 | 实现机制 | 无锁快照 | 备注 |
| --- | --- | --- | --- | --- | --- |
| MySQL | `mysql-cdc` | Debezium + Binlog | 是（chunk + binlog 回放） | 主选 |
| PostgreSQL | `postgres-cdc` | Debezium + Logical Replication | 是 | 主选 |
| Oracle | `oracle-cdc` | Debezium + LogMiner | 是（需归档日志） | 主选，需 DBA 配合 |
| SQL Server | `sqlserver-cdc` | Debezium + CDC 表 | 是 | 备选 |
| MongoDB | `mongodb-cdc` | Change Streams | 是 | 备选 |

代码示例：MySQL CDC Pipeline YAML（配置）

```yaml
# 命名：mysql-to-iceberg-pipeline.yaml
source:
  type: mysql
  hostname: mysql-source
  port: 3306
  username: cdc_user
  password: "***"
  tables: ods.\.*           # ods 库所有表
  server-id: 5400-5404      # binlog server-id 范围
  server-time-zone: Asia/Shanghai

sink:
  type: iceberg
  catalog:
    type: rest
    uri: http://iceberg-rest:8181
  warehouse: s3://lakehouse/warehouse
  tables: ods.\.*            # 同名映射

transform:
  - source-table: ods.\.*
    filter: op != 'D'        # 过滤删除事件（按需）
    projection: >-
      id, name, updated_at, CURRENT_TIMESTAMP AS cdc_ts

pipeline:
  name: mysql-ods-to-iceberg
  parallelism: 4
  scan.startup.mode: initial  # initial / latest / timestamp
```

命令示例：提交 CDC 管道

```bash
flink-cdc.sh mysql-to-iceberg-pipeline.yaml
# 等价于自动生成 Flink Job 并提交到 Flink 集群
```

### 8.4 Debezium 格式处理

Debezium 事件格式示例：

代码示例：Debezium 事件 JSON

```json
{
  "before": {"id": 1, "name": "old_name", "updated_at": "2026-08-28 10:00:00"},
  "after": {"id": 1, "name": "new_name", "updated_at": "2026-08-28 11:00:00"},
  "source": {
    "db": "ods", "table": "users",
    "ts_ms": 1724820000000,
    "snapshot": "false"
  },
  "op": "u",   // c=create, u=update, d=delete, r=read(快照)
  "ts_ms": 1724820000123
}
```

Flink CDC 3.0 内部自动解析 Debezium 格式，转换为 Flink RowData，无需用户处理。但 T014 需在 Transform 阶段按 `op` 字段做差异化处理（如过滤删除、补充 cdc_ts）。

### 8.5 exactly-once 语义

表：D-25 exactly-once 实现机制

| 阶段 | 机制 | 说明 |
| --- | --- | --- |
| Source 快照 | 无锁 chunk + Binlog 位点 | 快照阶段记录 Binlog 位点，快照完成后从位点回放 |
| Source 增量 | Binlog 位点定期 checkpoint | Flink Checkpoint 触发时记录当前 Binlog 位点 |
| Sink 写入 | 两阶段提交 | Iceberg V2 Sink 支持事务提交，Checkpoint 时 commit，失败回滚 |
| 端到端 | Flink Checkpoint 间隔 | 间隔越短，故障恢复数据越少，但开销越大 |

代码示例：Checkpoint 配置（配置）

```yaml
# flink-conf.yaml
execution.checkpointing.interval: 60s
execution.checkpointing.mode: EXACTLY_ONCE
state.backend: rocksdb
state.checkpoints.dir: s3://flink/checkpoints
execution.checkpointing.externalized-checkpoint-retention: RETAIN_ON_CANCELLATION
```

### 8.6 选型建议

表：D-26 CDC 方案选型对比

| 维度 | Flink CDC 3.0 | Debezium + Kafka Connect | Canal + Kafka |
| --- | --- | --- | --- |
| 源支持 | MySQL/PG/Oracle/SQLServer/Mongo | 同上 | 仅 MySQL |
| Sink | Iceberg/Doris/Hudi/StarRocks 等 | Kafka（需下游消费） | Kafka |
| exactly-once | 端到端 | 需下游配合 | 弱 |
| Schema 演进 | 自动 | 需配置 | 需配置 |
| YAML 声明式 | 是 | 否 | 否 |
| 运维 | Flink 集群 | Kafka Connect 集群 | Canal 集群 |

**结论**：**主选 Flink CDC 3.0**。

理由：
1. 端到端 exactly-once，满足 T014 验收
2. YAML 声明式，降低开发成本
3. 与 T015 Iceberg V2 Sink 天然集成
4. 数擎已有 Flink 1.18 集群（L2.3 流计算）

### 8.7 集成方案

1. **部署**：Flink CDC 3.0 部署在 Flink 1.18 集群（K8s Application 模式）
2. **依赖**：`flink-cdc-dist:3.0.*`、`flink-connector-iceberg:*`
3. **管道管理**：封装层 encaps-layer 暴露 `/v1/cdc/pipelines` CRUD，底层调 `flink-cdc.sh`
4. **监控**：Flink metrics + 平台 Grafana，CDC 延迟 / Binlog 位点 / Checkpoint 成功率
5. **源库权限**：CDC 专用账号，仅 REPLICATION SLAVE + SELECT 权限

### 8.8 连接器配置模板

表：D-27 三源连接器配置模板

| 源 | 关键配置 | 前置条件 |
| --- | --- | --- |
| MySQL | `server-id`、`scan.startup.mode`、`server-time-zone` | binlog_format=ROW、binlog_row_image=FULL |
| PostgreSQL | `slot.name`、`decoding.plugin.name`（pgoutput） | wal_level=logical、创建 publication |
| Oracle | `logminer.strategy`（online/catalog） | 归档日志开启、LogMiner 权限 |

### 8.9 风险评估

表：D-28 Flink CDC 风险评估

| 风险 ID | 风险描述 | 概率 | 影响 | 等级 | 缓解措施 |
| --- | --- | --- | --- | --- | --- |
| FC-R-001 | Oracle LogMiner 性能差 | 中 | 中 | 中 | 限制 Oracle 表数量，大表用 GoldenGate |
| FC-R-002 | Binlog 位点丢失（源库 purge） | 低 | 高 | 中 | 监控 Binlog 保留时间，告警 |
| FC-R-003 | Checkpoint 失败导致 exactly-once 破坏 | 低 | 高 | 中 | RocksDB + S3 checkpoint，定期校验 |
| FC-R-004 | Schema 演进与 Sink 不兼容 | 中 | 中 | 中 | 限制演进类型（加列 OK，删列/改类型需评审） |
| FC-R-005 | 大表初始快照耗时长 | 中 | 中 | 中 | chunk 并行快照，监控进度 |

### 8.10 PoC 验证结论

- **已验证**：MySQL CDC → Iceberg V2 管道，初始快照 + 增量同步
- **已验证**：exactly-once（kill TaskManager 后从 Checkpoint 恢复，无重复无丢失）
- **未验证**：Oracle CDC（需 DBA 配合归档日志）
- **结论**：**具备开工条件**，9 月 1 日可启动 T014（批次 1 D0）

---

## 第9章 Iceberg V2 预研

### 9.1 技术栈定位

- **官方仓库**：https://github.com/apache/iceberg
- **当前稳定版**：1.5.x（Java/Java-API）
- **License**：Apache 2.0
- **数擎用途**：T015 行级 upsert 与 Schema 同步
- **验收标准**：行级 upsert 生效、端到端延迟 P95 ≤5s、Schema 加列/改类型自动同步

### 9.2 Iceberg V2 表格式

Iceberg V2 引入**行级删除（Row-level Delete）**与**行级更新（Row-level Update）**：

表：D-29 Iceberg V1 vs V2 对比

| 特性 | V1 | V2 | 备注 |
| --- | --- | --- | --- |
| Append-only | 是 | 是 | — |
| 覆盖写入（Overwrite） | 是 | 是 | 整表/分区覆盖 |
| 行级删除 | 否（仅 Position-based 覆盖） | 是（Equality Delete + Position Delete） | V2 关键 |
| 行级更新 | 否 | 是（DELETE + INSERT 合并） | V2 关键 |
| MERGE INTO | 否 | 是 | V2 关键 |
| Copy-on-Write | 是 | 是 | 更新少时优 |
| Merge-on-Read | 否 | 是 | 更新多时优 |

### 9.3 MERGE INTO 语法

代码示例：Iceberg V2 MERGE INTO（SQL）

```sql
-- 命名：Iceberg V2 行级 upsert
MERGE INTO dws.user_active_da AS t
USING ods.user_change_di AS s
ON t.user_id = s.user_id AND t.dt = s.dt
WHEN MATCHED AND s.op = 'U' THEN
    UPDATE SET t.active_flag = s.active_flag, t.updated_at = s.updated_at
WHEN MATCHED AND s.op = 'D' THEN
    DELETE
WHEN NOT MATCHED AND s.op = 'C' THEN
    INSERT (user_id, dt, active_flag, updated_at)
    VALUES (s.user_id, s.dt, s.active_flag, s.updated_at);
```

### 9.4 主键冲突更新策略

表：D-30 主键冲突更新策略

| 策略 | 实现 | 适用场景 | 数擎建议 |
| --- | --- | --- | --- |
| Copy-on-Write (COW) | 写时合并旧文件，读时无合并开销 | 读多写少、更新低频 | Doris 物化视图源表 |
| Merge-on-Read (MOR) | 写时只写 Delete File，读时合并 | 写多读少、更新高频 | CDC 实时入仓 |
| Position Delete | 按 (file_path, row_pos) 删除 | 精确删除，Flink Sink 常用 | Flink CDC Sink |
| Equality Delete | 按主键等值删除 | 不知 row_pos 时 | 备选 |

代码示例：Flink Sink 写 Iceberg V2（Java）

```java
TableEnvironment tEnv = TableEnvironment.create(env);

// 1. 创建 Iceberg V2 表（带主键，启用 MOR）
tEnv.executeSql(
    "CREATE TABLE dws_user_active_da (\n" +
    "  user_id BIGINT,\n" +
    "  dt STRING,\n" +
    "  active_flag INT,\n" +
    "  updated_at TIMESTAMP,\n" +
    "  PRIMARY KEY (user_id, dt) NOT ENFORCED\n" +
    ") PARTITIONED BY (dt)\n" +
    "WITH (\n" +
    "  'connector' = 'iceberg',\n" +
    "  'catalog-name' = 'rest',\n" +
    "  'catalog-impl' = 'org.apache.iceberg.rest.RESTCatalog',\n" +
    "  'uri' = 'http://iceberg-rest:8181',\n" +
    "  'warehouse' = 's3://lakehouse/warehouse',\n" +
    "  'format-version' = '2',\n" +
    "  'write.upsert.enabled' = 'true',\n" +
    "  'write.distribution-mode' = 'hash',\n" +  // 按主键 hash 分桶
    "  'write.metadata.delete-after-commit.enabled' = 'true'\n" +
    ")"
);

// 2. Flink CDC Source → Iceberg Sink
tEnv.executeSql(
    "INSERT INTO dws_user_active_da\n" +
    "SELECT user_id, dt, active_flag, updated_at FROM cdc_source"
);
```

### 9.5 Schema 演化

表：D-31 Iceberg Schema 演化类型

| 演化类型 | 支持 | 说明 |
| --- | --- | --- |
| 加列 | 是 | 无需重写数据，仅元数据更新 |
| 删列 | 是 | 仅元数据更新，旧数据保留该列（读时返回 null） |
| 改列名 | 是 | 仅元数据更新 |
| 改列类型（拓宽） | 是 | int → bigint、float → double |
| 改列类型（缩窄） | 否 | 需重写数据 |
| 改列顺序 | 是 | 仅元数据更新 |

代码示例：Schema 演化（SQL）

```sql
-- 加列
ALTER TABLE dws_user_active_da ADD COLUMNS (new_col STRING);

-- 改列名
ALTER TABLE dws_user_active_da RENAME COLUMN old_col TO new_col;

-- 改列类型（拓宽）
ALTER TABLE dws_user_active_da ALTER COLUMN int_col TYPE BIGINT;
```

Flink CDC 3.0 自动同步源表 Schema 变更到 Iceberg Sink（加列自动，改类型需配置 `sink.schema-evolution.include`）。

### 9.6 选型建议

表：D-32 湖仓格式选型对比

| 维度 | Iceberg V2 | Hudi | Delta Lake |
| --- | --- | --- | --- |
| 行级 upsert | 是（MERGE INTO） | 是（默认） | 是（1.2+） |
| Schema 演进 | 强 | 中 | 中 |
| Flink 集成 | 强（官方 Connector） | 强 | 中 |
| 社区 | 极高（Netflix/Apple） | 高 | 高（Databricks） |
| 数擎已有 | L2.6 湖仓集一体已选 Iceberg | 无 | 无 |

**结论**：**主选 Iceberg V2**。

理由：
1. L2.6 湖仓集一体详细设计已选 Iceberg，T015 沿用
2. Flink CDC 3.0 → Iceberg V2 Sink 官方集成，与 T014 天然衔接
3. Schema 演进能力强，加列/改类型自动同步

### 9.7 集成方案

1. **部署**：Iceberg REST Catalog（独立服务），S3/MinIO 存储
2. **依赖**：`iceberg-flink-runtime:1.5.*`、`iceberg-aws-bundle`
3. **表创建**：所有需 upsert 的表声明 PRIMARY KEY + `format-version=2` + `write.upsert.enabled=true`
4. **Compaction**：定时任务触发 `rewrite_data_files`，合并小文件 + 清理 Delete File
5. **监控**：Iceberg metrics（文件数、Delete File 数、表大小）+ 平台 Grafana

### 9.8 upsert 实现路线图

表：D-33 Iceberg V2 upsert 实现路线图

| 阶段 | 内容 | 工期 | 里程碑 |
| --- | --- | --- | --- |
| 阶段 1 | Iceberg V2 表创建 + Flink Sink upsert | D0-D5 | 单表 upsert 可用 |
| 阶段 2 | MERGE INTO 语法 + 多表 upsert | D6-D10 | MERGE INTO 可用 |
| 阶段 3 | Schema 演化同步 + Compaction 调优 | D11-D12 | 端到端 P95 ≤5s |

### 9.9 风险评估

表：D-34 Iceberg V2 风险评估

| 风险 ID | 风险描述 | 概率 | 影响 | 等级 | 缓解措施 |
| --- | --- | --- | --- | --- | --- |
| IV-R-001 | MOR 读时合并延迟高 | 中 | 中 | 中 | 定时 Compaction + 读时缓存 |
| IV-R-002 | 小文件过多 | 中 | 中 | 中 | 定时 rewrite_data_files |
| IV-R-003 | Schema 演进与下游 Doris 物化视图不同步 | 中 | 中 | 中 | T016 物化视图刷新监听 Schema 变更 |
| IV-R-004 | S3 对象存储最终一致影响元数据 | 低 | 高 | 中 | 用 MinIO 强一致或 S3 ListV2 |
| IV-R-005 | 端到端 P95 ≤5s 达标困难 | 中 | 高 | 高 | Compaction 频率 + Checkpoint 间隔调优 |

### 9.10 PoC 验证结论

- **已验证**：Iceberg V2 表创建 + Flink Sink upsert（单表，主键冲突更新生效）
- **已验证**：Schema 加列自动同步
- **未验证**：端到端 P95 ≤5s（需 T014+T015 联调）
- **结论**：**具备开工条件**，9 月 1 日可启动 T015（批次 2，前置 T014）

---

## 第10章 技术风险评估与缓解措施

### 10.1 风险汇总

表：D-35 AI 技术栈风险汇总（按等级排序）

| 等级 | 风险 ID | 技术栈 | 风险描述 | 缓解措施 | 责任人 |
| --- | --- | --- | --- | --- | --- |
| **高** | LC-R-005 | LangChain | LLM 幻觉生成不存在的表/字段 | sqlglot AST 校验 + 失败重生成 | Python 工程师 C |
| **高** | LC-R-003 | LangChain | Spider 75% 基线达成困难 | Schema 检索 + Few-shot + 多轮澄清，预留 60d | Python 工程师 C |
| **高** | LC-R-001 | LangChain | SQLAgent 对 Doris 方言支持不全 | sqlglot 方言转换 + LCEL 自建 | Python 工程师 C |
| **高** | LG-R-004 | LangGraph | 大量并发 LLM 调用打满 llm-gateway | 令牌桶 + 优先级队列 | AI 架构师 |
| **高** | MV-R-002 | Milvus | HNSW 内存占用高，大租户 OOM | 大租户 DISKANN + 内存 limit | Go 工程师 B |
| **高** | CA-R-001 | Calcite | 学习曲线陡 | 架构师 50% 投入指导 | 首席架构师 |
| **高** | CA-R-002 | Calcite | 五源 Adapter 工作量大 | 分阶段，先 3 源 | Java 工程师 C |
| **高** | CA-R-003 | Calcite | 下推率 70% 达标困难 | 离线评测集迭代规则 | Java 工程师 C |
| **高** | IV-R-005 | Iceberg | 端到端 P95 ≤5s 达标困难 | Compaction + Checkpoint 调优 | Java 工程师 F |
| **高** | EM-R-005 | Embedding | 云 API 降级数据出境合规 | 仅降级到自部署模型 | 安全合规工程师 |

### 10.2 跨技术栈耦合风险

表：D-36 跨技术栈耦合风险

| 耦合点 | 涉及技术栈 | 风险 | 缓解措施 |
| --- | --- | --- | --- |
| LangGraph + LangChain 共用 LangChain-Core | LangGraph、LangChain | 版本不一致导致冲突 | 统一锁定 langchain-core 0.3.x |
| LangChain + Milvus Schema 检索 | LangChain、Milvus | Schema 变更未同步到 Milvus | 监听 catalog 变更事件，增量更新 |
| Milvus + Embedding 维度 | Milvus、Embedding | 维度变更需重建索引 | 维度作为 Collection 不可变属性 |
| Flink CDC + Iceberg V2 | Flink CDC、Iceberg | Sink 事务与 Checkpoint 不对齐 | Flink 两阶段提交 + Iceberg REST Catalog |
| Calcite + 五数据源 | Calcite、Doris/Trino/Iceberg/IoTDB/ES | 方言适配回归 | 每源独立测试套件 |
| NL2SQL + Calcite | LangChain、Calcite | NL2SQL 生成 SQL 经 Calcite 校验失败 | sqlglot 预校验 + Calcite 兜底 |

### 10.3 风险缓解里程碑

表：D-37 风险缓解里程碑

| 时间 | 缓解动作 | 关联风险 |
| --- | --- | --- |
| 2026-08-31 | vector-engine Milvus SDK 集成完成 | MV 系列 |
| 2026-08-31 | llm-gateway 切换真实 Provider | LG-R-004、LC-R-005 |
| 2026-09-07（T005 D7） | LangGraph 20+ Agent 并发压测 | LG-R-004 |
| 2026-09-14（T010 D14，批次 4） | Spider 75% 基线达成 | LC-R-003 |
| 2026-09-30（T012 D30） | Calcite 下推率 70% 达标 | CA-R-003 |
| 2026-10-08（T015 D12，批次 2） | Iceberg V2 端到端 P95 ≤5s | IV-R-005 |

---

## 第11章 预研结论与开工准备度评估

### 11.1 各技术栈开工准备度

表：D-38 各技术栈开工准备度评估

| 序号 | 技术栈 | 选型决策 | PoC 验证 | 集成方案 | 风险等级 | 准备度 | 开工条件 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | LangGraph | ✅ 主选 0.2.50 | ✅ 三范式 + Checkpoint | ✅ Go+Python Sidecar | 中 | 🟢 就绪 | 具备 |
| 2 | LangChain | ✅ 主选 0.3 + SQLAgent | ✅ SQLAgent + LCEL | ✅ FastAPI 服务 | 高 | 🟡 需关注 | 具备（Spider 调优留 T010 内） |
| 3 | Milvus SDK | ✅ 主选 2.4 + Go SDK v2.4.2 | ✅ Go/Python SDK | ✅ vector-engine 集成 | 中 | 🟡 需前置 | 前置：Mock 清零 3-5 人日 |
| 4 | Embedding 模型 | ✅ 主选 bge-m3 + TEI | ✅ 文本 + 图像 | ✅ llm-gateway 路由 | 中 | 🟢 就绪 | 具备 |
| 5 | Cross-Encoder | ✅ 主选 bge-reranker-v2-m3 | ✅ 50→10 P95 80ms | ✅ llm-gateway /v1/rerank | 低 | 🟢 就绪 | 具备 |
| 6 | Apache Calcite | ✅ 主选 1.37 | ✅ Doris Adapter 骨架 | ✅ 嵌入 sql-gateway | 高 | 🟡 需关注 | 具备（五源分阶段） |
| 7 | Flink CDC | ✅ 主选 3.0 | ✅ MySQL → Iceberg + exactly-once | ✅ Flink 1.18 集群 | 中 | 🟢 就绪 | 具备 |
| 8 | Iceberg V2 | ✅ 主选 1.5 | ✅ upsert + Schema 演化 | ✅ REST Catalog + S3 | 中 | 🟢 就绪 | 具备 |

图：D-8 开工准备度总览

```
就绪 🟢🟢🟢🟢🟢🟢   (6/8)
需关注 🟡🟡       (2/8：LangChain Spider 调优、Calcite 五源适配)
未就绪 🔴        (0/8)
```

### 11.2 前置依赖项

表：D-39 9 月 1 日开工前需完成的前置项

| 前置项 | 关联技术栈 | 预估工时 | 完成时间 | 责任人 | 状态 |
| --- | --- | --- | --- | --- | --- |
| vector-engine Milvus SDK 集成 | Milvus | 3-5 人日 | 2026-08-31 | Go 工程师 B | 进行中（Mock 清零冲刺） |
| llm-gateway 切换真实 Provider | LangGraph、LangChain、Embedding、Cross-Encoder | 1 人日 | 2026-08-26 | DevOps 工程师 A | 进行中 |
| knowledge-engine 切换 nebula+llm | Milvus（图谱路） | 2-3 人日 | 2026-08-31 | Python 工程师 A | 进行中 |
| TEI 部署 bge-m3 + bge-reranker-v2-m3 | Embedding、Cross-Encoder | 1 人日 | 2026-08-30 | DevOps | 待启动 |
| Iceberg REST Catalog 部署 | Iceberg | 1 人日 | 2026-08-30 | DevOps | 待启动 |

### 11.3 总体结论

**预研结论**：

1. **选型决策全部明确**：8 个技术栈均给出主选 + 备选 + 降级策略，无悬而未决的选型
2. **集成方案全部落地**：每个技术栈与数擎平台现有组件的集成路径、部署形态、依赖版本、监控方案均已明确
3. **PoC 验证基本通过**：8 个技术栈均完成最小可运行 PoC，核心能力验证通过；未验证项（Spider 75%、Calcite 下推率 70%、端到端 P95）留任务内专项调优
4. **风险评估完整**：识别 10 项高风险、6 项跨技术栈耦合风险，均有缓解措施与里程碑
5. **开工准备度 6/8 就绪、2/8 需关注**：LangChain（Spider 调优留 T010 内 60d）、Calcite（五源分阶段）均不影响 9 月 1 日启动

**开工准备度总评**：**🟢 具备 9 月 1 日 Phase 1a 正式开工条件**。

前置依赖项（Mock 清零冲刺 16.5 人日）需在 2026-08-31 前完成，当前进行中，无阻塞风险。

### 11.4 后续行动项

表：D-40 预研后续行动项

| 行动项 | 时间 | 责任人 | 关联任务 |
| --- | --- | --- | --- |
| LangGraph 20+ Agent 并发压测 | T005 D7（2026-09-07） | AI 架构师 | T005 |
| Milvus 1 亿向量规模压测 | T008 D10（2026-09-10） | Go 工程师 B | T008 |
| Spider 75% 基线达成 | T010 D30（2026-10-30） | Python 工程师 C | T010 |
| Calcite 下推率 70% 达标 | T012 D30（2026-09-30） | Java 工程师 C | T012 |
| Iceberg V2 端到端 P95 ≤5s | T015 D12（2026-10-08） | Java 工程师 F | T015 |
| 三路混合检索端到端 P95 ≤2s | T009 D12（2026-11-09） | Python 工程师 B | T009 |

---

## 附录 A：技术栈版本基线

表：D-A1 技术栈版本基线（9 月 1 日开工锁定版本）

| 技术栈 | 锁定版本 | 依赖包 | 备注 |
| --- | --- | --- | --- |
| LangGraph | 0.2.50 | `langgraph==0.2.50` | Python 3.11 |
| LangChain | 0.3.x | `langchain==0.3.*`、`langchain-core==0.3.*`、`langchain-community==0.3.*`、`langchain-openai` | Python 3.11 |
| Milvus | 2.4.x（服务端） | `github.com/milvus-io/milvus-sdk-go/v2 v2.4.2`、`pymilvus==2.4.*` | Go 1.21 / Python 3.11 |
| bge-m3 | v1.0 | `BAAI/bge-m3`（TEI 加载） | — |
| bge-reranker-v2-m3 | v1.0 | `BAAI/bge-reranker-v2-m3`（TEI 加载） | — |
| TEI | 1.5.x | `text-embeddings-inference:1.5` | GPU A10 |
| Apache Calcite | 1.37.0 | `org.apache.calcite:calcite-core:1.37.0` | Java 17 |
| Flink CDC | 3.0.x | `flink-cdc-dist:3.0.*` | Flink 1.18 |
| Iceberg | 1.5.x | `iceberg-flink-runtime:1.5.*` | Flink 1.18 |

## 附录 B：参考资料

| 技术栈 | 参考资料 |
| --- | --- |
| LangGraph | 官方文档 https://langchain-ai.github.io/langgraph/、LangGraph 0.2 Migration Guide |
| LangChain | 官方文档 https://python.langchain.com/docs/、LangChain 0.3 Migration Guide |
| Milvus | 官方文档 https://milvus.io/docs、Go SDK https://github.com/milvus-io/milvus-sdk-go |
| bge-m3 | https://huggingface.co/BAAI/bge-m3、MTEB Leaderboard |
| bge-reranker-v2-m3 | https://huggingface.co/BAAI/bge-reranker-v2-m3 |
| TEI | https://github.com/text-embeddings-inference/text-embeddings-inference |
| Apache Calcite | 官方文档 https://calcite.apache.org/、Adapter 教程 |
| Flink CDC | 官方文档 https://nightlies.apache.org/flink/flink-cdc-docs-stable/、3.0 Pipeline YAML |
| Iceberg | 官方文档 https://iceberg.apache.org/、V2 Row-level Delete Spec |
| Spider | https://yale-lily.github.io/spider、官方 evaluation.py |

## 附录 C：预研参与人员

表：D-C1 预研参与人员

| 角色 | 负责技术栈 | 投入 |
| --- | --- | --- |
| AI 架构师 | LangGraph、整体统筹 | 50% |
| Python 工程师 A | Embedding 模型、多模态 | 100% |
| Python 工程师 B | Milvus Python SDK、Cross-Encoder | 100% |
| Python 工程师 C | LangChain、NL2SQL | 100% |
| Go 工程师 B | Milvus Go SDK | 100% |
| Java 工程师 C | Apache Calcite | 100% |
| Java 工程师 E | Flink CDC | 100% |
| Java 工程师 F | Iceberg V2 | 100% |
| 首席架构师 | Calcite 架构决策、跨组协调 | 50% |

---

**文档结束**

> 本报告由 AI 组于 2026-08-28 编制，经首席架构师评审后作为 Phase 1a 9 月 1 日开工基线。后续实际开发中若发现预研未覆盖的风险，按 §5.8 R-P1-007 应对措施第 3 条"AI 组内部每日站会分享技术难点，无法解决的问题上报首席架构师决策"执行。