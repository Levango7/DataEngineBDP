"""API 注册服务 - 负责 API 的注册、查询、更新、注销与状态转换.

对应详细设计 §3 API 发布流程状态机：
    [定义] → [安全审核] → [审批] → [发布到网关] → [运行中] → [下线] → [归档]
"""
from __future__ import annotations

import uuid
from datetime import datetime

from openapi_catalog.models import (
    APIDefinition,
    APIFilter,
    APIStatus,
    APIUpdateRequest,
)
from openapi_catalog.repositories import (
    APIAlreadyExistsError,
    APINotFoundError,
    APIStatusTransitionError,
)
from openapi_catalog.repositories.mock import MockCatalogStore


# 合法状态转换
_VALID_TRANSITIONS: dict[APIStatus, set[APIStatus]] = {
    APIStatus.DRAFT: {APIStatus.REVIEWING, APIStatus.OFFLINE},
    APIStatus.REVIEWING: {
        APIStatus.APPROVED,
        APIStatus.REJECTED,
        APIStatus.OFFLINE,
    },
    APIStatus.APPROVED: {APIStatus.PUBLISHED, APIStatus.OFFLINE},
    APIStatus.REJECTED: {APIStatus.DRAFT, APIStatus.OFFLINE},
    APIStatus.PUBLISHED: {APIStatus.RUNNING, APIStatus.OFFLINE},
    APIStatus.RUNNING: {
        APIStatus.DEPRECATED,
        APIStatus.OFFLINE,
        APIStatus.RUNNING,  # 灰度/蓝绿
    },
    APIStatus.DEPRECATED: {APIStatus.ARCHIVED, APIStatus.OFFLINE},
    APIStatus.ARCHIVED: set(),
    APIStatus.OFFLINE: {APIStatus.ARCHIVED},
}


class APIRegistryService:
    """API 注册服务."""

    def __init__(self, store: MockCatalogStore) -> None:
        self.store = store

    async def register_api(self, api: APIDefinition) -> APIDefinition:
        """注册新 API.

        Args:
            api: API 定义（id 可空，自动生成）.

        Returns:
            保存后的 API 定义.
        """
        if not api.id:
            api.id = str(uuid.uuid4())
        api.status = api.status or APIStatus.DRAFT
        api.updatedAt = datetime.now()
        return await self.store.save_api(api)

    async def get_api(self, api_id: str) -> APIDefinition:
        """获取 API 详情."""
        return await self.store.get_api(api_id)

    async def list_apis(self, filter_: APIFilter) -> list[APIDefinition]:
        """列出 API."""
        return await self.store.list_apis(filter_)

    async def update_api(
        self, api_id: str, update: APIUpdateRequest
    ) -> APIDefinition:
        """更新 API（部分字段）."""
        api = await self.store.get_api(api_id)

        # 仅 DRAFT/REJECTED 状态可编辑契约字段
        editable = api.status in (APIStatus.DRAFT, APIStatus.REJECTED)
        if not editable:
            # 仅允许更新 description/tags/category 等元数据
            if update.params is not None or update.responses is not None or update.upstream is not None:
                raise APIStatusTransitionError(
                    api_id, api.status.value, "update_contract"
                )

        if update.description is not None:
            api.description = update.description
        if update.category is not None:
            api.category = update.category
        if update.tags is not None:
            api.tags = update.tags
        if update.params is not None and editable:
            api.params = update.params
        if update.responses is not None and editable:
            api.responses = update.responses
        if update.upstream is not None and editable:
            api.upstream = update.upstream
        if update.sla is not None:
            api.sla = update.sla
        if update.costStrategy is not None:
            api.costStrategy = update.costStrategy
        if update.costUnitPrice is not None:
            api.costUnitPrice = update.costUnitPrice
        if update.monthlyQuota is not None:
            api.monthlyQuota = update.monthlyQuota

        api.updatedAt = datetime.now()
        return await self.store.save_api(api)

    async def delete_api(self, api_id: str) -> None:
        """注销 API（仅允许在 DRAFT/REJECTED/ARCHIVED 状态）."""
        api = await self.store.get_api(api_id)
        if api.status not in (APIStatus.DRAFT, APIStatus.REJECTED, APIStatus.ARCHIVED):
            raise APIStatusTransitionError(
                api_id, api.status.value, "delete"
            )
        await self.store.delete_api(api_id)

    async def transition_status(
        self, api_id: str, target: APIStatus
    ) -> APIDefinition:
        """状态转换（校验合法性）."""
        api = await self.store.get_api(api_id)
        current = api.status
        if target not in _VALID_TRANSITIONS.get(current, set()):
            raise APIStatusTransitionError(
                api_id, current.value, target.value
            )
        api.status = target
        api.updatedAt = datetime.now()
        return await self.store.save_api(api)

    async def submit_for_review(self, api_id: str) -> APIDefinition:
        """提交安全审核."""
        return await self.transition_status(api_id, APIStatus.REVIEWING)

    async def approve(self, api_id: str) -> APIDefinition:
        """审核通过."""
        return await self.transition_status(api_id, APIStatus.APPROVED)

    async def reject(self, api_id: str) -> APIDefinition:
        """审核驳回."""
        return await self.transition_status(api_id, APIStatus.REJECTED)

    async def publish(self, api_id: str) -> APIDefinition:
        """发布到网关."""
        api = await self.transition_status(api_id, APIStatus.PUBLISHED)
        # 立即转为 RUNNING
        api.status = APIStatus.RUNNING
        api.updatedAt = datetime.now()
        return await self.store.save_api(api)

    async def deprecate(self, api_id: str) -> APIDefinition:
        """废弃（进入宽限期）."""
        return await self.transition_status(api_id, APIStatus.DEPRECATED)

    async def archive(self, api_id: str) -> APIDefinition:
        """归档下线."""
        return await self.transition_status(api_id, APIStatus.ARCHIVED)