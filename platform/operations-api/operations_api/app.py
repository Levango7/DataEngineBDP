"""数擎运营后台合同管理服务 · FastAPI 应用入口.

P-04 合同交付实体 — API 应用骨架。

启动方式：
    uvicorn operations_api.app:app --host 0.0.0.0 --port 8081

鉴权：所有 /api/v1/* 端点要求 Bearer JWT（HS256），由 operations_api.jwt_auth 模块
基于环境变量 AUTH_MODE / JWT_SECRET / JWT_EXPECTED_ISSUER 控制：
    AUTH_MODE=jwt  生产强制鉴权（缺 JWT_SECRET 时 fail-fast）
    AUTH_MODE=none 本地/测试匿名放行（K8s 环境下拒绝启动）
"""
from __future__ import annotations

import logging

from fastapi import FastAPI

from .api.routers import contracts

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="数擎运营后台 · 合同管理服务",
    description="P-04 合同交付实体 — 合同管理 CRUD API（Bearer JWT 鉴权）",
    version="0.1.0",
)

# 注册路由
app.include_router(contracts.router)


@app.get("/healthz")
async def healthz():
    """健康检查（公开端点，不要求鉴权）."""
    return {"status": "ok", "service": "operations-api", "version": "0.1.0"}


@app.get("/readyz")
async def readyz():
    """就绪检查（公开端点，不要求鉴权）.

    TODO: 检查数据库连接
    TODO: 检查依赖服务连通性
    """
    return {"status": "ok"}
