"""数擎运营后台合同管理服务 · FastAPI 应用入口.

P-04 合同交付实体 — API 应用骨架。

启动方式：
    uvicorn operations_api.app:app --host 0.0.0.0 --port 8081

TODO: 添加 Bearer Token 鉴权中间件
TODO: 添加请求日志中间件
TODO: 添加 CORS 配置
TODO: 添加 Prometheus 指标暴露
"""
from __future__ import annotations

import logging

from fastapi import FastAPI

from .api.routers import contracts

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="数擎运营后台 · 合同管理服务",
    description="P-04 合同交付实体 — 合同管理 CRUD API",
    version="0.1.0",
)

# 注册路由
app.include_router(contracts.router)


@app.get("/healthz")
async def healthz():
    """健康检查."""
    return {"status": "ok", "service": "operations-api", "version": "0.1.0"}


@app.get("/readyz")
async def readyz():
    """就绪检查.

    TODO: 检查数据库连接
    TODO: 检查依赖服务连通性
    """
    return {"status": "ok"}