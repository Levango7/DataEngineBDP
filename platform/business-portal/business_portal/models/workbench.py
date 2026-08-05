"""工作台模型（待办/常用工具/最近任务）."""
from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

from business_portal.models.base import utc_now


class Task(BaseModel):
    """待办任务."""

    id: str
    type: str = Field(..., description="任务类型: approval/apply/share/alert")
    title: str
    applicant: str = Field(default="", description="申请人")
    status: str = Field(default="pending", description="pending/approved/rejected")
    priority: str = Field(default="normal", description="normal/high/urgent")
    createdAt: datetime = Field(default_factory=utc_now)
    extra: dict[str, Any] = Field(default_factory=dict)


class Tool(BaseModel):
    """常用工具入口."""

    key: str
    label: str
    icon: str = Field(default="", description="图标名")
    url: str = Field(default="", description="跳转 URL")
    description: str | None = None


class RecentTask(BaseModel):
    """最近任务（作业/训练/部署等）."""

    id: str
    name: str
    kind: str = Field(..., description="job/training/deployment/share")
    status: str
    updatedAt: datetime = Field(default_factory=utc_now)


class Workbench(BaseModel):
    """业务线工作台."""

    blId: str
    todos: list[Task] = Field(default_factory=list, description="待办列表")
    tools: list[Tool] = Field(default_factory=list, description="常用工具")
    recentTasks: list[RecentTask] = Field(
        default_factory=list, description="最近任务"
    )
    updatedAt: datetime = Field(default_factory=utc_now)
    extra: dict[str, Any] = Field(default_factory=dict)