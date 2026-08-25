"""微调任务引擎 FastAPI 主入口.

端口：8095
路由前缀：/api/v1/finetune

端点：
    GET  /api/v1/health                       健康检查
    GET  /api/v1/finetune/adapters            列出适配器
    GET  /api/v1/finetune/nodes               列出 GPU 节点池
    GET  /api/v1/finetune/stats               服务统计
    POST /api/v1/finetune/tasks               提交微调任务
    GET  /api/v1/finetune/tasks               查询任务列表
    GET  /api/v1/finetune/tasks/{id}          查询任务详情
    DELETE /api/v1/finetune/tasks/{id}        终止任务
    GET  /api/v1/finetune/tasks/{id}/logs     查询任务日志

设计要点：
    - 应用工厂 create_app，便于测试注入。
    - Mock 模式（FINETUNE_MOCK_MODE=true）零外部依赖即可运行，
      不需要 GPU / torch / LLaMA-Factory，仅验证 API 正确性。
    - 所有组件通过 app.state 共享。
"""
from __future__ import annotations

import os
from typing import Any

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from loguru import logger

from app.api.tasks import create_router
from app.services.finetune_service import FinetuneService
from app.services.job_scheduler import JobScheduler


# ============================================================
# 配置
# ============================================================
def _env_bool(name: str, default: bool = False) -> bool:
    """从环境变量读取布尔值."""
    val = os.environ.get(name)
    if val is None:
        return default
    return val.lower() in ("true", "1", "yes", "on")


def _corsOrigins() -> list[str]:
    raw = os.environ.get("CORS_ORIGINS", "http://localhost:5173")
    return [o.strip() for o in raw.split(",") if o.strip()]


# ============================================================
# 应用工厂
# ============================================================
def create_app() -> FastAPI:
    """创建 FastAPI 应用实例.

    通过环境变量配置：
        FINETUNE_PORT: 端口，默认 8095
        FINETUNE_WORK_DIR: 工作目录，默认 /tmp/finetune
        FINETUNE_MOCK_MODE: Mock 模式，默认 true（本地验证）
        FINETUNE_SCHEDULER_BACKEND: 调度后端，默认 volcano
    """
    workDir = os.environ.get("FINETUNE_WORK_DIR", "/tmp/finetune")
    mockMode = _env_bool("FINETUNE_MOCK_MODE", default=True)
    schedulerBackend = os.environ.get("FINETUNE_SCHEDULER_BACKEND", "volcano")

    if mockMode:
        logger.warning(
            "演示模式: FINETUNE_MOCK_MODE=true,微调任务在本地模拟执行,不占用 GPU 资源"
        )

    app = FastAPI(
        title="Model Finetuning Engine",
        description=(
            "数据引擎大数据平台 · T032 LoRA/QLoRA/全参微调引擎<br/>"
            "接入 LLaMA-Factory / PEFT / DeepSpeed 三框架，"
            "支持 LoRA rank 8/16/32、QLoRA 4bit/8bit、全参微调，"
            "GPU 节点池调度与多卡并行。"
        ),
        version="0.1.0",
        docs_url="/docs",
        redoc_url="/redoc",
    )

    # CORS（前端联调）
    app.add_middleware(
        CORSMiddleware,
        allow_origins=_corsOrigins(),
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # 初始化服务
    scheduler = JobScheduler(
        backend=schedulerBackend, mockMode=mockMode
    )
    service = FinetuneService(
        workDir=workDir, mockMode=mockMode, scheduler=scheduler
    )
    app.state.service = service
    app.state.mockMode = mockMode

    # 注册路由
    app.include_router(create_router(service))

    # ============================================================
    # 健康检查
    # ============================================================
    @app.get("/api/v1/health", tags=["health"])
    async def health():
        """健康检查端点."""
        return {
            "status": "UP",
            "service": "model-finetuning",
            "version": "0.1.0",
            "mockMode": mockMode,
            "timestamp": service.stats()["timestamp"],
        }

    @app.get("/", include_in_schema=False)
    async def root():
        """根路径重定向到文档."""
        return JSONResponse(
            {
                "service": "model-finetuning",
                "version": "0.1.0",
                "docs": "/docs",
                "health": "/api/v1/health",
            }
        )

    @app.on_event("startup")
    async def on_startup():
        logger.info(
            f"微调引擎启动完成，mockMode={mockMode}, "
            f"workDir={workDir}, scheduler={schedulerBackend}"
        )

    @app.on_event("shutdown")
    async def on_shutdown():
        logger.info("微调引擎关闭")

    return app


# ============================================================
# 模块级应用实例（uvicorn 直接引用）
# ============================================================
app = create_app()


if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("FINETUNE_PORT", "8095"))
    uvicorn.run(
        "main:app",
        host=os.environ.get("FINETUNE_HOST", "0.0.0.0"),
        port=port,
        reload=False,
        log_level=os.environ.get("FINETUNE_LOG_LEVEL", "info"),
    )