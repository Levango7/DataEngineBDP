"""特征组与特征读写路由."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from ml_platform.api.routers.deps import getRegistry, statusForError
from ml_platform.models import (
    FeatureGroup,
    FeatureGroupConfig,
    FeatureSchema,
)
from ml_platform.repositories import MlPlatformError
from ml_platform.services.registry import ServiceRegistry

router = APIRouter(prefix="/feature-groups", tags=["features"])


class CreateFeatureGroupRequest(BaseModel):
    """创建特征组请求."""

    name: str = Field(..., description="特征组名")
    description: str | None = Field(default=None, description="描述")
    entityKey: str = Field(default="entity_id", description="实体键列名")
    features: list[FeatureSchema] = Field(default_factory=list, description="特征 schema 列表")
    tags: dict[str, str] = Field(default_factory=dict, description="标签")


class PutFeaturesRequest(BaseModel):
    """写入特征请求."""

    features: dict[str, Any] = Field(..., description="特征名 -> 值")


@router.post(
    "",
    response_model=FeatureGroup,
    status_code=status.HTTP_201_CREATED,
    summary="创建特征组",
)
async def createFeatureGroup(
    body: CreateFeatureGroupRequest,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        config = FeatureGroupConfig(
            name=body.name,
            description=body.description,
            entityKey=body.entityKey,
            features=body.features,
            tags=body.tags,
        )
        return await registry.featureService.createFeatureGroup(config)
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))


@router.get(
    "",
    response_model=list[FeatureGroup],
    summary="列出特征组",
)
async def listFeatureGroups(
    registry: ServiceRegistry = Depends(getRegistry),
):
    return await registry.featureService.listFeatureGroups()


@router.get(
    "/{groupName}",
    response_model=FeatureGroup,
    summary="特征组详情",
)
async def getFeatureGroup(
    groupName: str,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        return await registry.featureService.getFeatureGroup(groupName)
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))


@router.get(
    "/{groupName}/features/{entityId}",
    summary="获取特征",
)
async def getFeatures(
    groupName: str,
    entityId: str,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        features = await registry.featureService.getFeatures(groupName, entityId)
        return {
            "groupName": groupName,
            "entityId": entityId,
            "features": features,
        }
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))


@router.put(
    "/{groupName}/features/{entityId}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="写入特征",
)
async def putFeatures(
    groupName: str,
    entityId: str,
    body: PutFeaturesRequest,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        await registry.featureService.putFeatures(groupName, entityId, body.features)
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))


@router.delete(
    "/{groupName}/features/{entityId}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="删除特征",
)
async def deleteFeatures(
    groupName: str,
    entityId: str,
    registry: ServiceRegistry = Depends(getRegistry),
):
    try:
        await registry.featureService.deleteFeatures(groupName, entityId)
    except MlPlatformError as e:
        raise HTTPException(status_code=statusForError(e), detail=str(e))
