"""数据引擎大数据平台 · 智能数据层 · NL2SQL 核心引擎 (L4.5.4).

将自然语言查询转换为 SQL，对接 Catalog 元数据与 SQL 网关，
支持意图识别、Schema 上下文构建、语法校验、多轮澄清与槽位填充。

模块组成：
    - schema_context      : Schema 上下文构建器（对接 Catalog :8082）
    - intent_recognition  : 意图识别（聚合/过滤/Join/排序）
    - sql_generator       : 基于 LangChain 的 SQL 生成
    - sql_validator       : SQL 语法校验（sqlparse）
    - dialogue_clarifier  : 多轮对话澄清
    - slot_filler         : 槽位填充
    - gateway_client      : SQL 网关对接（:8081）
    - app                 : FastAPI 服务入口（port 8093）
"""

__version__ = "0.1.0"
