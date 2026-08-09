"""Python 函数运行时 · FastAPI 入口 · 数据引擎大数据平台 T025。

本模块是 Serverless Python 函数运行时的 FastAPI 入口，封装为 Knative Service。
设计要点：
    1. 冷启动优化：启动时预编译依赖、预热连接池，目标冷启动 ≤ 3s；
    2. invocation 计量：每次调用按 tenant 标签写入 Prometheus 指标 + Loki 日志；
    3. 函数加载：从 /functions 目录动态加载用户函数 handler；
    4. 健康检查：/health 供 Knative readinessProbe 使用。

运行时环境变量：
    FUNCTION_NAME：默认函数名（默认 default）
    TENANT_ID：默认租户 ID（默认 default-tenant）
    PROMETHEUS_PUSHGATEWAY：Pushgateway 地址（可选，留空则用 exposition）
    LOG_LEVEL：日志级别（默认 INFO）
"""

from __future__ import annotations

import importlib
import logging
import os
import sys
import time
from pathlib import Path
from typing import Any, Callable, Dict, Optional

from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse

from app.metrics import InvocationRecorder, init_recorder

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
FUNCTION_NAME = os.environ.get("FUNCTION_NAME", "default")
DEFAULT_TENANT_ID = os.environ.get("TENANT_ID", "default-tenant")
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO")
# 函数代码目录（init container 预加载依赖后挂载）
FUNCTIONS_DIR = Path(os.environ.get("FUNCTIONS_DIR", "/functions"))

logging.basicConfig(
    level=getattr(logging, LOG_LEVEL.upper(), logging.INFO),
    format="%(asctime)s [%(levelname)s] tenant=%(tenant_id)s %(message)s",
)
logger = logging.getLogger("function-runtime")


# ---------------------------------------------------------------------------
# 应用启动：冷启动优化
# ---------------------------------------------------------------------------
app = FastAPI(
    title="Shuqing Function Runtime (Python)",
    version="0.1.0",
    docs_url="/docs",
)

# invocation 计量记录器（Prometheus 指标 + Loki 日志）
recorder: InvocationRecorder = init_recorder(
    runtime="python",
    pushgateway_url=os.environ.get("PROMETHEUS_PUSHGATEWAY", ""),
)

# 已加载的函数 handler 缓存：function_name -> callable
_loadedHandlers: Dict[str, Callable[..., Any]] = {}


def loadFunctionHandler(functionName: str) -> Callable[..., Any]:
    """动态加载用户函数 handler。

    约定：函数代码位于 ``/functions/<function_name>/handler.py``，需暴露 ``handle(event)`` 函数。
    加载后缓存到 ``_loadedHandlers``，避免重复 import 开销（冷启动后热路径加速）。

    Args:
        functionName: 函数名。

    Returns:
        可调用对象 ``handle(event: dict) -> dict``。

    Raises:
        ImportError: 函数模块不存在或未定义 handle。
    """
    if functionName in _loadedHandlers:
        return _loadedHandlers[functionName]

    modulePath = FUNCTIONS_DIR / functionName / "handler.py"
    if not modulePath.exists():
        raise ImportError(f"函数 handler 不存在: {modulePath}")

    # 动态导入：以 app.functions.<name>.handler 为模块名
    moduleName = f"app.functions.{functionName}.handler"
    spec = importlib.util.spec_from_file_location(moduleName, modulePath)
    if spec is None or spec.loader is None:
        raise ImportError(f"无法加载函数模块: {modulePath}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[moduleName] = module
    spec.loader.exec_module(module)

    handler = getattr(module, "handle", None)
    if handler is None or not callable(handler):
        raise ImportError(f"函数 {functionName} 未定义 handle(event) 入口")

    _loadedHandlers[functionName] = handler
    logger.info("函数 handler 加载完成: %s", functionName, extra={"tenant_id": "-"})
    return handler


@app.on_event("startup")
async def _onStartup() -> None:
    """启动钩子：预热函数 handler，降低首次请求延迟。

    冷启动优化策略：
        1. 预加载默认函数 handler（避免首次请求触发 import）；
        2. 预热 metrics 记录器（初始化 Prometheus 客户端）；
        3. 记录启动耗时供监控。
    """
    start = time.monotonic()
    try:
        loadFunctionHandler(FUNCTION_NAME)
    except ImportError as exc:
        logger.warning("启动预热失败（不影响运行，首次请求时再加载）: %s", exc,
                       extra={"tenant_id": "-"})
    recorder.warmup()
    elapsed = time.monotonic() - start
    logger.info("Python 运行时启动完成，预热耗时 %.3fs", elapsed,
                extra={"tenant_id": "-"})


# ---------------------------------------------------------------------------
# 路由
# ---------------------------------------------------------------------------
@app.get("/health")
async def health() -> JSONResponse:
    """健康检查端点（Knative readinessProbe / livenessProbe 使用）。"""
    return JSONResponse({"status": "UP", "runtime": "python", "function": FUNCTION_NAME})


@app.post("/invoke")
async def invoke(request: Request) -> Response:
    """函数调用入口。

    请求头：
        X-Tenant-Id：租户 ID（用于计量隔离）
        X-Function-Name：函数名（覆盖默认）

    请求体：任意 JSON，作为 event 传入用户 handler。

    响应：用户 handler 返回的 JSON。

    计量：
        每次调用记录 invocation_count{tenant, runtime, function} 指标，
        并将调用日志写入 Loki（通过 stdout JSON 日志被 Promtail 采集）。
    """
    startTime = time.monotonic()
    tenantId = request.headers.get("X-Tenant-Id", DEFAULT_TENANT_ID)
    functionName = request.headers.get("X-Function-Name", FUNCTION_NAME)

    try:
        event = await request.json()
    except Exception:
        event = {}

    status = "success"
    statusCode = 200
    result: Any
    try:
        handler = loadFunctionHandler(functionName)
        result = handler(event)
        if result is None:
            result = {}
    except Exception as exc:
        status = "error"
        statusCode = 500
        result = {"error": str(exc), "function": functionName}
        logger.exception("函数调用失败: %s", functionName,
                         extra={"tenant_id": tenantId})

    duration = time.monotonic() - startTime

    # invocation 计量：Prometheus 指标 + Loki 日志
    recorder.record(
        tenantId=tenantId,
        functionName=functionName,
        status=status,
        duration=duration,
    )

    return JSONResponse(result, status_code=statusCode)


@app.get("/metrics")
async def metrics() -> Response:
    """Prometheus 指标暴露端点。"""
    body, contentType = recorder.expose()
    return Response(content=body, media_type=contentType)