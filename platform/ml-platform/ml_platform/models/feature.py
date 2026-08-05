"""特征工程数据模型."""
from __future__ import annotations

from typing import Any, Optional

from pydantic import BaseModel, Field

from ml_platform.models.base import TimestampMixin


class FeatureSchema(BaseModel):
    """特征列 schema.

    Attributes:
        name:     特征名
        dtype:    数据类型（int/float/string/bool/datetime）
        nullable: 是否允许空值
        default:  默认值
        description: 描述
    """

    name: str = Field(..., description="特征名")
    dtype: str = Field(default="float", description="数据类型")
    nullable: bool = Field(default=True, description="是否允许空值")
    default: Optional[Any] = Field(default=None, description="默认值")
    description: Optional[str] = Field(default=None, description="描述")


class FeatureGroupConfig(BaseModel):
    """特征组配置.

    Attributes:
        name:        特征组名（唯一）
        description: 描述
        entityKey:   实体键列名（如 user_id）
        features:    特征 schema 列表
        tags:        标签
    """

    name: str = Field(..., description="特征组名")
    description: Optional[str] = Field(default=None, description="描述")
    entityKey: str = Field(default="entity_id", description="实体键列名")
    features: list[FeatureSchema] = Field(
        default_factory=list, description="特征 schema 列表"
    )
    tags: dict[str, str] = Field(
        default_factory=dict, description="标签"
    )


class FeatureGroup(TimestampMixin):
    """特征组.

    Attributes:
        id:   特征组 ID
        name: 特征组名
        config: 完整配置
    """

    id: str = Field(..., description="特征组 ID")
    name: str = Field(..., description="特征组名")
    config: FeatureGroupConfig = Field(..., description="特征组配置")