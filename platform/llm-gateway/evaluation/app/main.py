"""FastAPI 应用入口。

数据引擎大数据平台 · T031 模型评测平台

启动：
    uvicorn app.main:app --host 0.0.0.0 --port 8086

默认端口 8086，通过环境变量 EVAL_PORT 覆盖。
依赖 T030 LLM 网关（默认 http://localhost:18085），通过 LLM_GATEWAY_URL 覆盖。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
import logging

from app.api.routes import create_router
from app.config import get_settings
from app.core.executor import EvalExecutor
from app.core.job_manager import JobManager
from app.core.llm_client import LLMGatewayClient
from app.report.generator import ABReportGenerator
from fastapi import FastAPI

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# 全局组件（在 lifespan 中初始化）
# ---------------------------------------------------------------------------
class AppState:
    """应用状态容器。"""

    job_manager: JobManager
    executor: EvalExecutor
    llm_client: LLMGatewayClient
    report_generator: ABReportGenerator


_state = AppState()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理。

    启动时初始化组件，关闭时释放资源。
    """
    settings = get_settings()
    logger.info(
        "启动评测平台: port=%s, llm_gateway=%s",
        settings.port,
        settings.llm_gateway_url,
    )

    # 初始化组件
    _state.job_manager = JobManager()
    _state.llm_client = LLMGatewayClient(
        base_url=settings.llm_gateway_url,
        api_key=settings.llm_gateway_api_key,
        timeout=settings.llm_gateway_timeout,
        enable_mock_fallback=settings.enable_mock_fallback,
    )
    _state.executor = EvalExecutor(
        job_manager=_state.job_manager,
        llm_client=_state.llm_client,
        token_price_per_1k=settings.token_price_per_1k,
    )
    _state.report_generator = ABReportGenerator(
        job_manager=_state.job_manager,
    )

    # 注册路由
    router = create_router(
        job_manager=_state.job_manager,
        executor=_state.executor,
        llm_client=_state.llm_client,
        report_generator=_state.report_generator,
    )
    app.include_router(router)

    yield

    # 关闭资源
    _state.llm_client.close()
    logger.info("评测平台已关闭")


def create_app() -> FastAPI:
    """创建 FastAPI 应用。"""
    app = FastAPI(
        title="数据引擎大数据平台 · 模型评测平台",
        description="T031 模型评测平台与 A/B 对比：评测任务引擎 + 标准集 + 六指标 + 三模式 + A/B 报告",
        version="0.1.0",
        lifespan=lifespan,
    )

    @app.get("/health", tags=["health"])
    async def health() -> dict:
        """健康检查（供 Docker HEALTHCHECK / K8s 探针）。"""
        return {"status": "UP", "service": "llm-evaluation", "version": "0.1.0"}

    return app


# 模块级应用实例（uvicorn 入口）
app = create_app()


if __name__ == "__main__":
    import uvicorn

    settings = get_settings()
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=settings.port,
        log_level="info",
    )
