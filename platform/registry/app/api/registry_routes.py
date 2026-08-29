"""模型仓库 API 路由.

端点：
    POST   /api/v1/registry/models                注册模型
    GET    /api/v1/registry/models                查询模型列表
    GET    /api/v1/registry/models/{name}         查询模型详情
    GET    /api/v1/registry/models/{name}/versions 查询模型版本历史
    POST   /api/v1/registry/deployments           创建部署
    GET    /api/v1/registry/deployments           查询部署列表
    GET    /api/v1/registry/deployments/{id}      查询部署详情
    DELETE /api/v1/registry/deployments/{id}      停止部署
    PUT    /api/v1/registry/deployments/{id}      更新部署
    GET    /api/v1/registry/deployments/{id}/health 健康检查
    GET    /api/v1/registry/stats                 服务统计
"""

from __future__ import annotations

import asyncio
import logging
import threading
from typing import Optional

from app.core.deployment_manager import DeploymentManager
from app.core.health_checker import HealthChecker
from app.jwt_auth import getAuthContext
from app.models import (
    DeploymentListResponse,
    DeploymentRecord,
    DeploymentStatus,
    DeployRequest,
    HealthCheckResult,
    ModelListResponse,
    ModelRecord,
    ModelRegisterRequest,
)
from fastapi import APIRouter, Depends, HTTPException, Query

logger = logging.getLogger(__name__)


# ============================================================
# 模型仓库（简化为内存字典 + 文件持久化）
# ============================================================
class SimpleModelRegistry:
    """简化模型仓库（内存存储）."""

    def __init__(self):
        self._models: dict[str, list[ModelRecord]] = {}
        self._lock = threading.RLock()

    def _key(self, name: str, tenant: str) -> str:
        return f"{tenant}/{name}"

    def register(self, req: ModelRegisterRequest) -> ModelRecord:
        """注册模型."""
        record = ModelRecord(
            modelName=req.modelName,
            version=req.version,
            path=req.path,
            baseModel=req.baseModel,
            framework=req.framework,
            method=req.method,
            tenantId=req.tenantId,
            metadata=req.metadata,
        )
        key = self._key(req.modelName, req.tenantId)
        with self._lock:
            if key not in self._models:
                self._models[key] = []
            self._models[key].append(record)
        return record

    def get_model(
        self,
        name: str,
        version: str = "",
        tenant: str = "default",
    ) -> Optional[ModelRecord]:
        """查询模型."""
        key = self._key(name, tenant)
        with self._lock:
            versions = self._models.get(key, [])
            if not versions:
                return None
            if version:
                for r in versions:
                    if r.version == version and r.isActive:
                        return r
                return None
            active = [r for r in versions if r.isActive]
            return active[-1] if active else None

    def list_models(self, tenant: str = "") -> list[ModelRecord]:
        """列出所有模型."""
        with self._lock:
            result = []
            for key, versions in self._models.items():
                parts = key.split("/", 1)
                if len(parts) != 2:
                    continue
                k_tenant, _ = parts
                if tenant and k_tenant != tenant:
                    continue
                active = [r for r in versions if r.isActive]
                if active:
                    result.append(active[-1])
            return result

    def list_versions(
        self,
        name: str,
        tenant: str = "default",
    ) -> list[ModelRecord]:
        """列出模型所有版本."""
        key = self._key(name, tenant)
        with self._lock:
            return list(self._models.get(key, []))


def create_router(
    deployment_manager: DeploymentManager,
    health_checker: HealthChecker,
    model_registry: SimpleModelRegistry,
) -> APIRouter:
    """创建模型仓库路由器."""

    router = APIRouter(
        prefix="/api/v1/registry",
        tags=["model-registry"],
        # 全部业务端点统一 JWT 鉴权（对齐平台 jwt_auth 镜像模式）
        dependencies=[Depends(getAuthContext)],
    )

    # ============================================================
    # 模型注册
    # ============================================================
    @router.post("/models", status_code=201, response_model=ModelRecord)
    async def register_model(request: ModelRegisterRequest):
        """注册模型到仓库."""
        try:
            return model_registry.register(request)
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"注册失败: {e}") from e

    @router.get("/models", response_model=ModelListResponse)
    async def list_models(
        tenantId: str = Query(default=""),
    ):
        """查询模型列表."""
        models = model_registry.list_models(tenant=tenantId)
        return ModelListResponse(total=len(models), models=models)

    @router.get("/models/{name}", response_model=ModelRecord)
    async def get_model(
        name: str,
        version: str = Query(default=""),
        tenantId: str = Query(default="default"),
    ):
        """查询模型详情."""
        record = model_registry.get_model(name=name, version=version, tenant=tenantId)
        if record is None:
            raise HTTPException(status_code=404, detail=f"模型不存在: {name}")
        return record

    @router.get("/models/{name}/versions")
    async def list_model_versions(
        name: str,
        tenantId: str = Query(default="default"),
    ):
        """查询模型版本历史."""
        versions = model_registry.list_versions(name, tenantId)
        return {"versions": versions}

    # ============================================================
    # 部署管理
    # ============================================================
    @router.post("/deployments", status_code=201, response_model=DeploymentRecord)
    async def create_deployment(request: DeployRequest):
        """创建部署（一键部署到推理服务）."""
        model = model_registry.get_model(
            name=request.modelName,
            version=request.version,
            tenant=request.tenantId,
        )
        if model is None:
            raise HTTPException(
                status_code=404,
                detail=(f"model_not_found: 模型不存在: " f"{request.modelName}"),
            )
        try:
            record = await asyncio.to_thread(deployment_manager.deploy, request)
            return record
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"部署失败: {e}") from e

    @router.get("/deployments", response_model=DeploymentListResponse)
    async def list_deployments(
        status: Optional[DeploymentStatus] = Query(default=None),
        tenantId: Optional[str] = Query(default=None),
    ):
        """查询部署列表."""
        deployments = deployment_manager.list_deployments(status=status, tenantId=tenantId)
        return DeploymentListResponse(total=len(deployments), deployments=deployments)

    @router.get("/deployments/{deploymentId}", response_model=DeploymentRecord)
    async def get_deployment(deploymentId: str):
        """查询部署详情."""
        record = deployment_manager.get_deployment(deploymentId)
        if record is None:
            raise HTTPException(status_code=404, detail=f"部署不存在: {deploymentId}")
        return record

    @router.delete("/deployments/{deploymentId}", response_model=DeploymentRecord)
    async def stop_deployment(deploymentId: str):
        """停止部署."""
        record = await asyncio.to_thread(deployment_manager.stop_deployment, deploymentId)
        if record is None:
            raise HTTPException(status_code=404, detail=f"部署不存在: {deploymentId}")
        return record

    @router.put("/deployments/{deploymentId}", response_model=DeploymentRecord)
    async def update_deployment(
        deploymentId: str,
        replicas: int = Query(default=0, ge=0),
        gpuCount: int = Query(default=0, ge=0),
    ):
        """更新部署（扩缩容）."""
        record = deployment_manager.update_deployment(deploymentId, replicas=replicas, gpu_count=gpuCount)
        if record is None:
            raise HTTPException(status_code=404, detail=f"部署不存在: {deploymentId}")
        return record

    # ============================================================
    # 健康检查
    # ============================================================
    @router.get(
        "/deployments/{deploymentId}/health",
        response_model=HealthCheckResult,
    )
    async def check_health(deploymentId: str):
        """部署健康检查."""
        record = deployment_manager.get_deployment(deploymentId)
        if record is None:
            raise HTTPException(status_code=404, detail=f"部署不存在: {deploymentId}")
        return await health_checker.check(record)

    # ============================================================
    # 服务统计
    # ============================================================
    @router.get("/stats")
    async def stats():
        """服务统计."""
        return {
            "deployment": deployment_manager.stats(),
            "models": {
                "totalModels": len(model_registry._models),
                "totalVersions": sum(len(v) for v in model_registry._models.values()),
            },
        }

    return router
