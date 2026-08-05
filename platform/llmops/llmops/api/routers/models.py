"""模型管理路由."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field, ValidationError, model_validator

from llmops.api.routers.deps import get_registry, status_for_error
from llmops.models.base import ModelStatus, ModelType
from llmops.models.model import ModelFilter, ModelInfo, ModelParams
from llmops.repositories import LlmopsError
from llmops.services.registry import ServiceRegistry

router = APIRouter(prefix="/models", tags=["models"])


# ---------- 请求模型 ----------

class RegisterModelRequest(BaseModel):
    """注册模型请求."""

    name: str = Field(..., min_length=1, max_length=128)
    type: ModelType = Field(..., description="模型类型: base/ft")
    baseModelId: str | None = Field(default=None, description="基座模型 ID（ft 必填）")
    params: ModelParams = Field(default_factory=ModelParams)
    description: str | None = None
    tags: dict[str, str] = Field(default_factory=dict)

    @model_validator(mode="after")
    def _validate_base(self) -> "RegisterModelRequest":
        """微调模型必须指定基座模型 ID."""
        if self.type == ModelType.FT and not self.baseModelId:
            raise ValueError("微调模型(type=ft)必须指定 baseModelId")
        if self.type == ModelType.BASE and self.baseModelId is not None:
            raise ValueError("基座模型(type=base)不应指定 baseModelId")
        return self


class UpdateModelRequest(BaseModel):
    """更新模型请求."""

    description: str | None = None
    tags: dict[str, str] | None = None


class SetProductionVersionRequest(BaseModel):
    """设置生产版本请求."""

    version: int = Field(..., ge=1)


# ---------- 路由 ----------

@router.post(
    "",
    response_model=ModelInfo,
    status_code=status.HTTP_201_CREATED,
    summary="注册模型",
)
async def register_model(
    req: RegisterModelRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> ModelInfo:
    """注册一个新模型（基座或微调）."""
    import uuid

    model_info = ModelInfo(
        id=str(uuid.uuid4()),
        name=req.name,
        type=req.type,
        baseModelId=req.baseModelId,
        params=req.params,
        description=req.description,
        tags=req.tags,
    )
    try:
        return await registry.modelService.register_model(model_info)
    except ValidationError as exc:
        raise HTTPException(status_code=422, detail=exc.errors())
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "",
    response_model=list[ModelInfo],
    summary="列出模型",
)
async def list_models(
    name: str | None = Query(default=None, description="名称模糊匹配"),
    type: ModelType | None = Query(default=None, description="按类型过滤"),
    status_: ModelStatus | None = Query(default=None, alias="status", description="按状态过滤"),
    tag: str | None = Query(default=None, description="标签 key=value"),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[ModelInfo]:
    """按条件列出模型."""
    filter_ = ModelFilter(
        name=name, type=type, status=status_, tag=tag, limit=limit, offset=offset
    )
    return await registry.modelService.list_models(filter_)


@router.get(
    "/{model_id}",
    response_model=ModelInfo,
    summary="获取模型详情",
)
async def get_model(
    model_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> ModelInfo:
    """根据 ID 获取模型详情."""
    try:
        return await registry.modelService.get_model(model_id)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.delete(
    "/{model_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="删除模型",
)
async def delete_model(
    model_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> None:
    """删除模型（已部署的模型不允许删除）."""
    try:
        await registry.modelService.delete_model(model_id)
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc))


@router.get(
    "/{model_id}/versions",
    response_model=list,
    summary="模型版本列表",
)
async def get_model_versions(
    model_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> list:
    """获取模型的所有版本."""
    from llmops.models.model import ModelVersion

    try:
        versions = await registry.modelService.get_model_versions(model_id)
        return [v.model_dump() for v in versions]
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.put(
    "/{model_id}/production-version",
    response_model=ModelInfo,
    summary="设置生产版本",
)
async def set_production_version(
    model_id: str,
    req: SetProductionVersionRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> ModelInfo:
    """设置模型的生产版本."""
    try:
        return await registry.modelService.set_production_version(
            model_id, req.version
        )
    except LlmopsError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))