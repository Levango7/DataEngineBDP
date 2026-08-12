"""微调→评测→部署闭环编排服务 FastAPI 主入口.

端口：18088
路由前缀：/api/v1/loop

端点：
    GET  /health                              健康检查
    POST /api/v1/loop/tasks                   提交闭环任务
    GET  /api/v1/loop/tasks                   查询任务列表
    GET  /api/v1/loop/tasks/{id}              查询任务详情
    DELETE /api/v1/loop/tasks/{id}            取消任务
    GET  /api/v1/loop/tasks/{id}/logs         查询任务日志
    WS   /api/v1/loop/tasks/{id}/ws           WebSocket 实时进度
    GET  /api/v1/loop/stats                   服务统计
    GET  /api/v1/loop/adapters/versions       Adapter 版本历史
    GET  /api/v1/loop/adapters/compare        Adapter 版本对比
    POST /api/v1/loop/adapters/rollback       Adapter 回滚
    GET  /api/v1/loop/reports/versions        评测报告版本历史
    GET  /api/v1/loop/reports/compare         评测报告版本对比

设计要点：
    - 应用工厂 create_app，便于测试注入。
    - Mock 模式（LOOP_MOCK_MODE=true）零外部依赖即可运行。
    - 集成 T032 微调引擎 + T031 评测平台 + 模型仓库服务。
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.loop_routes import create_router
from app.config import get_settings
from app.core.orchestrator import LoopOrchestrator
from app.core.step_executor import StepExecutor
from app.core.websocket_manager import WebSocketManager
from app.versioning.adapter_registry import AdapterRegistry
from app.versioning.model_repository import ModelRepository
from app.versioning.report_registry import ReportRegistry

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger(__name__)


# ============================================================
# 应用状态容器
# ============================================================
class AppState:
    """应用状态容器."""

    executor: StepExecutor
    ws_manager: WebSocketManager
    adapter_registry: AdapterRegistry
    report_registry: ReportRegistry
    model_repository: ModelRepository
    orchestrator: LoopOrchestrator


_state = AppState()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理."""
    settings = get_settings()
    logger.info(
        "启动闭环编排服务: port=%s, mock=%s, "
        "finetune=%s, evaluation=%s, registry=%s",
        settings.port, settings.mock_mode,
        settings.finetune_url, settings.evaluation_url,
        settings.registry_url,
    )

    # 初始化组件
    _state.ws_manager = WebSocketManager()
    _state.executor = StepExecutor(
        finetune_url=settings.finetune_url,
        evaluation_url=settings.evaluation_url,
        registry_url=settings.registry_url,
        mock_mode=settings.mock_mode,
        timeout=settings.http_timeout,
        max_retries=settings.http_max_retries,
    )
    await _state.executor.start()

    registry_dir = f"{settings.work_dir}/registry"
    repo_dir = f"{settings.work_dir}/repository"
    _state.adapter_registry = AdapterRegistry(storage_dir=registry_dir)
    _state.report_registry = ReportRegistry(storage_dir=registry_dir)
    _state.model_repository = ModelRepository(storage_dir=repo_dir)

    _state.orchestrator = LoopOrchestrator(
        executor=_state.executor,
        ws_manager=_state.ws_manager,
        adapter_registry=_state.adapter_registry,
        report_registry=_state.report_registry,
    )

    # 注册路由
    router = create_router(
        orchestrator=_state.orchestrator,
        ws_manager=_state.ws_manager,
        adapter_registry=_state.adapter_registry,
        report_registry=_state.report_registry,
    )
    app.include_router(router)

    yield

    # 关闭资源
    await _state.executor.close()
    logger.info("闭环编排服务已关闭")


def create_app() -> FastAPI:
    """创建 FastAPI 应用."""
    settings = get_settings()
    app = FastAPI(
        title="数据引擎大数据平台 · 微调→评测→部署闭环编排",
        description=(
            "T033 一键闭环编排服务<br/>"
            "集成 T032 微调引擎 + T031 评测平台 + 模型仓库部署，"
            "支持 LoRA/QLoRA 微调、六指标评测、一键部署，"
            "版本化管理 + WebSocket 实时监控。"
        ),
        version="0.1.0",
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url="/redoc",
    )

    # CORS
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # ============================================================
    # 健康检查
    # ============================================================
    @app.get("/health", tags=["health"])
    async def health():
        """健康检查."""
        settings = get_settings()
        reachable = {"finetune": False, "evaluation": False, "registry": False}
        try:
            reachable = await _state.executor.health_check()
        except Exception as e:  # noqa: BLE001
            logger.warning("健康检查异常: %s", e)
        return {
            "status": "UP",
            "service": "finetuning-loop",
            "version": "0.1.0",
            "mockMode": settings.mock_mode,
            "finetuneReachable": reachable.get("finetune", False),
            "evaluationReachable": reachable.get("evaluation", False),
            "registryReachable": reachable.get("registry", False),
        }

    @app.get("/", include_in_schema=False)
    async def root():
        """根路径."""
        return {
            "service": "finetuning-loop",
            "version": "0.1.0",
            "docs": "/docs",
            "health": "/health",
        }

    return app


# 模块级应用实例
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