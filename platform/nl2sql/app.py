"""NL2SQL 引擎 FastAPI 服务入口.

端口：8093
路由前缀：/api/v1

端点：
    GET  /api/v1/health                       健康检查
    POST /api/v1/nl2sql/generate              单轮 NL → SQL（不执行）
    POST /api/v1/nl2sql/execute               NL → SQL → 网关执行
    POST /api/v1/nl2sql/dialogue/start        开启多轮对话
    POST /api/v1/nl2sql/dialogue/answer       提交澄清回答
    POST /api/v1/nl2sql/validate              校验 SQL 语法
    GET  /api/v1/nl2sql/schema                获取 schema 上下文（调试用）

设计要点：
    - 应用工厂 create_app，便于测试注入。
    - 所有组件通过 app.state 共享，路由通过依赖获取。
    - Mock 模式（NL2SQL_LLM_MODE=mock）零外部依赖即可运行。
"""

from __future__ import annotations

from collections import OrderedDict
import os
import time
from typing import Any, Optional
import uuid

from config.settings import Settings, get_settings
from dialogue_clarifier import DialogueClarifier
from fastapi import Depends, FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from gateway_client import GatewayClient
from intent_recognition import IntentRecognizer
from jwt_auth import AuthContext, effectiveTenant, getAuthContext, requireAdmin
from loguru import logger
from models import (
    DialogueState,
    Intent,
    SlotFrame,
    SqlGenerationResult,
    ValidationResult,
)
from pydantic import BaseModel, Field
from schema_context import SchemaContextBuilder
from slot_filler import SlotFiller
from sql_generator import (
    BaseSqlGenerator,
    LlmCallError,
    LlmNotConfiguredError,
    createGenerator,
)
from sql_validator import SqlValidator


# ============================================================
# 请求 / 响应模型
# ============================================================
class GenerateRequest(BaseModel):
    """NL → SQL 请求."""

    query: str = Field(description="自然语言查询")
    database: Optional[str] = Field(default=None, description="目标数据库")
    tableHints: Optional[list[str]] = Field(default=None, description="表名提示")
    useMockSchema: bool = Field(default=False, description="强制 Mock schema")
    tenantId: Optional[str] = Field(default=None, description="租户 ID")


class ExecuteRequest(GenerateRequest):
    """NL → SQL → 执行请求."""

    engine: Optional[str] = Field(default=None, description="查询引擎 trino/doris")
    limit: Optional[int] = Field(default=None, ge=1, description="行数限制")


class ExecuteResponse(BaseModel):
    """执行响应."""

    sql: str
    params: list[Any] = Field(default_factory=list, description="参数化查询参数值（与 SQL 中 ? 占位符按序对应）")
    intent: Optional[Intent] = None
    validation: Optional[ValidationResult] = None
    gateway: Optional[dict[str, Any]] = None
    elapsedMs: float = 0.0


class DialogueStartRequest(BaseModel):
    """开启对话请求."""

    query: str
    database: Optional[str] = None
    tableHints: Optional[list[str]] = None
    useMockSchema: bool = False


class DialogueAnswerRequest(BaseModel):
    """对话回答请求."""

    sessionId: str
    answer: str
    useMockSchema: bool = False


class DialogueResponse(BaseModel):
    """对话响应."""

    sessionId: str
    clarified: bool = Field(description="是否已完成澄清")
    nextQuestion: Optional[str] = Field(default=None, description="下一个澄清问题")
    sql: Optional[str] = Field(default=None, description="澄清完成后的 SQL")
    intent: Optional[Intent] = None
    slots: Optional[SlotFrame] = None
    turnCount: int = 0


class ValidateRequest(BaseModel):
    """SQL 校验请求."""

    sql: str
    database: Optional[str] = None
    useMockSchema: bool = False


class ConvertRequest(BaseModel):
    """NL → SQL 精简请求（兼容 ai-assistant Go 代理）."""

    query: str = Field(description="自然语言查询")
    dialect: Optional[str] = Field(default=None, description="目标方言（仅回显）")
    tenantId: Optional[str] = Field(default=None, description="租户 ID")


class ConvertResponse(BaseModel):
    """NL → SQL 精简响应（兼容 ai-assistant Go 代理）."""

    sql: str = Field(description="生成的 SQL")
    params: list[Any] = Field(default_factory=list, description="参数化查询参数值（与 SQL 中 ? 占位符按序对应）")
    dialect: str = Field(default="", description="SQL 方言")
    tables: list[str] = Field(default_factory=list, description="涉及表名")
    confidence: float = Field(default=0.0, ge=0.0, le=1.0, description="置信度")


class HealthResponse(BaseModel):
    """健康检查响应."""

    status: str
    component: str = "nl2sql"
    version: str = "0.1.0"
    llmMode: str
    llmConfigured: bool
    llmModel: str = ""
    catalogUrl: str
    sqlGatewayUrl: str


# ============================================================
# 服务注册表
# ============================================================
class ServiceRegistry:
    """组件注册表，集中持有所有服务实例."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.schemaBuilder = SchemaContextBuilder(settings)
        self.intentRecognizer = IntentRecognizer()
        self.validator = SqlValidator(settings)
        self.generator: BaseSqlGenerator = createGenerator(settings, self.validator)
        self.slotFiller = SlotFiller()
        self.clarifier = DialogueClarifier(slotFiller=self.slotFiller, maxTurns=settings.maxDialogueTurns)
        self.gatewayClient = GatewayClient(settings)
        # 会话存储（内存，生产可换 Redis）：键为 (tenantId, sessionId)，LRU+TTL 双限
        self._sessions: OrderedDict[tuple[str, str], tuple[DialogueState, float]] = OrderedDict()

    def _sweepExpiredSessions(self) -> None:
        now = time.monotonic()
        expired = [key for key, (_, ts) in self._sessions.items() if now - ts > self.settings.sessionTtlSeconds]
        for key in expired:
            del self._sessions[key]

    def getSession(self, tenantId: str, sessionId: str) -> Optional[DialogueState]:
        self._sweepExpiredSessions()
        key = (tenantId, sessionId)
        entry = self._sessions.get(key)
        if entry is None:
            return None
        state, _ = entry
        self._sessions[key] = (state, time.monotonic())
        self._sessions.move_to_end(key)
        return state

    def saveSession(self, state: DialogueState, tenantId: str) -> None:
        self._sweepExpiredSessions()
        key = (tenantId, state.sessionId)
        self._sessions[key] = (state, time.monotonic())
        self._sessions.move_to_end(key)
        while len(self._sessions) > self.settings.maxSessions:
            self._sessions.popitem(last=False)


def build_services(settings: Optional[Settings] = None) -> ServiceRegistry:
    """构建服务注册表."""
    return ServiceRegistry(settings or get_settings())


def _corsOrigins() -> list[str]:
    raw = os.environ.get("CORS_ORIGINS", "http://localhost:5173")
    return [o.strip() for o in raw.split(",") if o.strip()]


# ============================================================
# 应用工厂
# ============================================================
def create_app(
    settings: Optional[Settings] = None,
    registry: Optional[ServiceRegistry] = None,
) -> FastAPI:
    """创建 FastAPI 应用实例.

    Args:
        settings: 配置，不传则使用全局单例。
        registry: 服务注册表，不传则根据 settings 构建（便于测试注入）。

    Returns:
        FastAPI 应用。
    """
    if settings is None:
        settings = get_settings()
    if registry is None:
        registry = build_services(settings)

    app = FastAPI(
        title="NL2SQL Core Engine",
        description=(
            "数据引擎大数据平台 · 智能数据层 · NL2SQL 核心引擎 (L4.5.4)\n\n"
            "将自然语言查询转换为 SQL，对接 Catalog 元数据与 SQL 网关，\n"
            "支持意图识别、Schema 上下文构建、语法校验、多轮澄清与槽位填充。"
        ),
        version="0.1.0",
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
    )

    app.state.settings = settings
    app.state.registry = registry

    app.add_middleware(
        CORSMiddleware,
        allow_origins=_corsOrigins(),
        allow_credentials=True,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type"],
    )

    prefix = settings.apiPrefix
    _registerRoutes(app, registry, prefix)

    @app.on_event("shutdown")
    async def _closeGenerator() -> None:
        """关闭生成器底层资源（http 直连模式的 httpx 连接池）."""
        aclose = getattr(registry.generator, "aclose", None)
        if aclose is not None:
            await aclose()

    return app


# ============================================================
# 路由注册
# ============================================================
def _registerRoutes(app: FastAPI, reg: ServiceRegistry, prefix: str) -> None:
    """注册所有路由."""

    @app.get("/api/v1/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        """健康检查."""
        s = reg.settings
        return HealthResponse(
            status="UP",
            llmMode=s.llmMode,
            llmConfigured=s.llmConfigured,
            llmModel=s.llmModel,
            catalogUrl=s.catalogUrl,
            sqlGatewayUrl=s.sqlGatewayUrl,
        )

    @app.post(f"{prefix}/nl2sql/generate", response_model=SqlGenerationResult)
    async def generate(req: GenerateRequest, ctx: AuthContext = Depends(getAuthContext)) -> SqlGenerationResult:
        """单轮 NL → SQL（不执行）.

        租户裁决：tenantId 一律以 token 声明为准；仅 admin 可通过请求体
        指定他人租户（effectiveTenant）。防止越权探测跨租户 schema。

        生成的 SQL 强制包含 tenant_id 过滤条件，确保租户级数据隔离。
        """
        _tenant = effectiveTenant(ctx, req.tenantId)
        return await _doGenerate(reg, req.query, req.database, req.tableHints, req.useMockSchema, tenantId=_tenant)

    @app.post(f"{prefix}/nl2sql/convert", response_model=ConvertResponse)
    async def convert(req: ConvertRequest, ctx: AuthContext = Depends(getAuthContext)) -> ConvertResponse:
        """NL → SQL 精简端点（B-5 联调兼容）.

        供 ai-assistant Go 代理调用（downstream_proxy.Nl2Sql 契约：
        请求 {query, dialect}，响应 {sql, dialect, tables, confidence}）。
        内部复用完整生成链路（真实 LLM / Mock），异常经 _generateSafe
        转为友好 HTTP 错误。

        生成的 SQL 强制包含 tenant_id 过滤条件，确保租户级数据隔离。
        """
        _tenant = effectiveTenant(ctx, req.tenantId)
        gen = await _doGenerate(reg, req.query, None, None, False, tenantId=_tenant)
        # 涉及表：优先意图 Join 表 + 首表回退；从生成结果 SQL 中无法可靠反解，
        # 这里用 schema 上下文构建时的命中表不可得，取意图信息近似。
        tables: list[str] = []
        if gen.intent is not None:
            tables = list(gen.intent.joinTables)
        # 置信度启发式：校验通过 0.9，有告警 0.7，失败 0.5
        v = gen.validation
        if v is None:
            confidence = 0.5
        elif v.valid:
            confidence = 0.7 if v.hasWarning else 0.9
        else:
            confidence = 0.5
        dialect = (req.dialect or reg.settings.defaultEngine).upper()
        return ConvertResponse(sql=gen.sql, params=gen.params, dialect=dialect, tables=tables, confidence=confidence)

    @app.post(f"{prefix}/nl2sql/execute", response_model=ExecuteResponse)
    async def execute(req: ExecuteRequest, ctx: AuthContext = Depends(getAuthContext)) -> ExecuteResponse:
        """NL → SQL → 网关执行.

        租户裁决：tenantId 一律以 token 声明为准；仅 admin 可通过请求体
        指定他人租户（effectiveTenant）。防止越权触发跨租户查询。

        生成的 SQL 强制包含 tenant_id 过滤条件，确保租户级数据隔离。
        """
        _tenant = effectiveTenant(ctx, req.tenantId)
        gen = await _doGenerate(reg, req.query, req.database, req.tableHints, req.useMockSchema, tenantId=_tenant)
        if gen.validation and not gen.validation.valid:
            return ExecuteResponse(
                sql=gen.sql,
                params=gen.params,
                intent=gen.intent,
                validation=gen.validation,
                gateway=None,
                elapsedMs=gen.elapsedMs,
            )
        gw = await reg.gatewayClient.execute(
            sql=gen.sql,
            engine=req.engine,
            tenantId=_tenant,
            limit=req.limit,
            params=gen.params,
        )
        return ExecuteResponse(
            sql=gen.sql,
            params=gen.params,
            intent=gen.intent,
            validation=gen.validation,
            gateway=gw.model_dump(),
            elapsedMs=gen.elapsedMs + gw.durationMs,
        )

    @app.post(f"{prefix}/nl2sql/dialogue/start", response_model=DialogueResponse)
    async def dialogueStart(req: DialogueStartRequest, auth: AuthContext = Depends(getAuthContext)) -> DialogueResponse:
        """开启多轮对话."""
        sessionId = str(uuid.uuid4())
        ctx = await reg.schemaBuilder.buildContext(
            query=req.query,
            database=req.database,
            tableHints=req.tableHints,
            useMock=req.useMockSchema,
        )
        intent = reg.intentRecognizer.recognize(req.query, ctx)
        frame = reg.slotFiller.buildFrame(intent, req.query, ctx)
        state = reg.clarifier.startDialogue(
            sessionId=sessionId,
            query=req.query,
            intent=intent,
            frame=frame,
            database=req.database,
        )
        # 生成首条澄清问题
        nextQ = reg.clarifier.nextQuestion(state, ctx)
        sql: Optional[str] = None
        if nextQ is None:
            # 无需澄清，直接生成 SQL
            gen = await _generateSafe(reg, req.query, ctx, intent, frame, tenantId=auth.tenantId)
            sql = gen.sql
            state.clarified = True
        reg.saveSession(state, auth.tenantId)
        return DialogueResponse(
            sessionId=sessionId,
            clarified=state.clarified,
            nextQuestion=nextQ,
            sql=sql,
            intent=intent,
            slots=frame,
            turnCount=state.turnCount,
        )

    @app.post(f"{prefix}/nl2sql/dialogue/answer", response_model=DialogueResponse)
    async def dialogueAnswer(
        req: DialogueAnswerRequest, auth: AuthContext = Depends(getAuthContext)
    ) -> DialogueResponse:
        """提交澄清回答."""
        state = reg.getSession(auth.tenantId, req.sessionId)
        if state is None:
            raise HTTPException(status_code=404, detail=f"会话 {req.sessionId} 不存在")
        # 取 schema 上下文（基于首轮查询重建）
        firstUser = next((t for t in state.turns if t.role == "user"), None)
        queryText = firstUser.content if firstUser else ""
        ctx = await reg.schemaBuilder.buildContext(
            query=queryText,
            database=state.database,
            useMock=req.useMockSchema,
        )
        reg.clarifier.absorbAnswer(state, req.answer, ctx)
        nextQ = reg.clarifier.nextQuestion(state, ctx)
        sql: Optional[str] = None
        if nextQ is None and state.currentSlots is not None and state.currentSlots.intent is not None:
            gen = await _generateSafe(
                reg, queryText, ctx, state.currentSlots.intent, state.currentSlots, tenantId=auth.tenantId
            )
            sql = gen.sql
            state.clarified = True
        reg.saveSession(state, auth.tenantId)
        return DialogueResponse(
            sessionId=state.sessionId,
            clarified=state.clarified,
            nextQuestion=nextQ,
            sql=sql,
            intent=state.currentSlots.intent if state.currentSlots else None,
            slots=state.currentSlots,
            turnCount=state.turnCount,
        )

    @app.post(f"{prefix}/nl2sql/validate", response_model=ValidationResult)
    async def validate(req: ValidateRequest, auth: AuthContext = Depends(getAuthContext)) -> ValidationResult:
        """校验 SQL 语法."""
        ctx = None
        if req.database or req.useMockSchema:
            ctx = await reg.schemaBuilder.buildContext(query="", database=req.database, useMock=req.useMockSchema)
        return reg.validator.validate(req.sql, ctx)

    @app.get(f"{prefix}/nl2sql/schema")
    async def schema(
        database: Optional[str] = None,
        useMock: bool = False,
        auth: AuthContext = Depends(getAuthContext),
    ) -> JSONResponse:
        """获取 schema 上下文（调试用）."""
        requireAdmin(auth)
        tables = await reg.schemaBuilder.fetchTables(database=database, useMock=useMock)
        return JSONResponse(
            {
                "database": database,
                "tables": [t.model_dump() for t in tables],
            }
        )


# ============================================================
# 内部：生成流程
# ============================================================
async def _generateSafe(
    reg: ServiceRegistry,
    query: str,
    ctx: Any,
    intent: Intent,
    slots: Optional[SlotFrame] = None,
    tenantId: Optional[str] = None,
) -> SqlGenerationResult:
    """调用 SQL 生成器并把 LLM 配置/调用异常转为友好 HTTP 错误.

    - LlmNotConfiguredError → 503（LLM 未配置，提示设置环境变量）
    - LlmCallError          → 502（LLM 调用失败，透传友好文案）
    服务本身不崩溃：异常在此收口，客户端拿到结构化错误信息。

    Args:
        tenantId: 租户 ID，传给生成器以在 SQL 中注入 tenant_id 过滤条件。
    """
    try:
        return await reg.generator.generate(query, ctx, intent, slots, tenantId=tenantId)
    except LlmNotConfiguredError as e:
        logger.warning("LLM 未配置: {}", e)
        raise HTTPException(status_code=503, detail=str(e)) from e
    except LlmCallError as e:
        logger.warning("LLM 调用失败: {}", e)
        raise HTTPException(status_code=502, detail=str(e)) from e


async def _doGenerate(
    reg: ServiceRegistry,
    query: str,
    database: Optional[str],
    tableHints: Optional[list[str]],
    useMockSchema: bool,
    tenantId: Optional[str] = None,
) -> SqlGenerationResult:
    """完整生成流程：schema → intent → slots → generate → validate.

    Args:
        tenantId: 租户 ID，传给生成器以在 SQL 中注入 tenant_id 过滤条件，
            实现租户级数据隔离。None 时跳过（仅限无鉴权的本地调试场景）。
    """
    logger.info("NL2SQL generate: query={!r} db={} tenant={}", query, database, tenantId)
    ctx = await reg.schemaBuilder.buildContext(
        query=query, database=database, tableHints=tableHints, useMock=useMockSchema
    )
    intent = reg.intentRecognizer.recognize(query, ctx)
    frame = reg.slotFiller.buildFrame(intent, query, ctx)
    result = await _generateSafe(reg, query, ctx, intent, frame, tenantId=tenantId)
    # 若有缺失必需槽位，标记需要澄清
    if not frame.isComplete:
        result.needsClarification = True
        result.clarificationQuestions = reg.clarifier.detectAmbiguity(query, intent, frame, ctx)
    return result


# ============================================================
# 入口
# ============================================================
def main() -> None:
    """启动 NL2SQL FastAPI 服务."""
    import uvicorn

    settings = get_settings()
    uvicorn.run(
        "app:create_app",
        factory=True,
        host=settings.host,
        port=settings.port,
        log_level=settings.logLevel,
        reload=settings.reload,
    )


if __name__ == "__main__":
    main()
