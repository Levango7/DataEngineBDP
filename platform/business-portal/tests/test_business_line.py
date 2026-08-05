"""业务线 CRUD 测试."""
from __future__ import annotations

import uuid

import pytest

from business_portal.models.base import BusinessLineStatus
from business_portal.models.business_line import (
    BusinessLine,
    BusinessLineConfig,
    BusinessLineFilter,
    Budget,
)
from business_portal.repositories import (
    BusinessLineAlreadyExistsError,
    BusinessLineNotFoundError,
    PermissionDeniedError,
)
from business_portal.services.business_line_service import BusinessLineService


def _make_bl(
    name: str = "风控线",
    tenant_id: str = "tenant-1",
    owner_ids: list[str] | None = None,
    member_ids: list[str] | None = None,
) -> BusinessLine:
    return BusinessLine(
        id=str(uuid.uuid4()),
        name=name,
        tenantId=tenant_id,
        description="测试业务线",
        budget=Budget(total=100000.0, used=30000.0),
        config=BusinessLineConfig(dataIsolation="strict", permissionScope="bl"),
        ownerIds=owner_ids or ["admin-1"],
        teamIds=["team-1"],
        memberIds=member_ids or ["admin-1", "user-1", "user-2"],
    )


class TestBusinessLineCreate:
    """创建业务线测试."""

    @pytest.mark.asyncio
    async def test_create_success(self, mock_bl_store):
        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl()
        result = await svc.create_business_line(bl)
        assert result.id == bl.id
        assert result.name == "风控线"
        assert result.status == BusinessLineStatus.ACTIVE

    @pytest.mark.asyncio
    async def test_create_duplicate_name_raises(
        self, mock_bl_store
    ):
        """同租户下业务线名称唯一."""
        svc = BusinessLineService(mock_bl_store)
        bl1 = _make_bl(name="风控线", tenant_id="t-1")
        bl2 = _make_bl(name="风控线", tenant_id="t-1")
        await svc.create_business_line(bl1)
        with pytest.raises(BusinessLineAlreadyExistsError):
            await svc.create_business_line(bl2)

    @pytest.mark.asyncio
    async def test_create_same_name_different_tenant_ok(
        self, mock_bl_store
    ):
        """不同租户下业务线名称可重复."""
        svc = BusinessLineService(mock_bl_store)
        bl1 = _make_bl(name="风控线", tenant_id="t-1")
        bl2 = _make_bl(name="风控线", tenant_id="t-2")
        await svc.create_business_line(bl1)
        await svc.create_business_line(bl2)  # 不应抛异常


class TestBusinessLineRead:
    """读取业务线测试."""

    @pytest.mark.asyncio
    async def test_get_success(self, mock_bl_store):
        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl()
        await svc.create_business_line(bl)
        result = await svc.get_business_line(bl.id)
        assert result.id == bl.id

    @pytest.mark.asyncio
    async def test_get_not_found_raises(self, mock_bl_store):
        svc = BusinessLineService(mock_bl_store)
        with pytest.raises(BusinessLineNotFoundError):
            await svc.get_business_line("nonexistent-id")

    @pytest.mark.asyncio
    async def test_get_with_permission_check_member_ok(
        self, mock_bl_store
    ):
        """成员可访问业务线."""
        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl(member_ids=["user-1"])
        await svc.create_business_line(bl)
        result = await svc.get_business_line(bl.id, user_id="user-1")
        assert result.id == bl.id

    @pytest.mark.asyncio
    async def test_get_with_permission_check_non_member_denied(
        self, mock_bl_store
    ):
        """非成员不可访问业务线（权限隔离）."""
        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl(member_ids=["user-1"])
        await svc.create_business_line(bl)
        with pytest.raises(PermissionDeniedError):
            await svc.get_business_line(bl.id, user_id="intruder")

    @pytest.mark.asyncio
    async def test_list_by_tenant(self, mock_bl_store):
        svc = BusinessLineService(mock_bl_store)
        await svc.create_business_line(_make_bl(name="bl-1", tenant_id="t-1"))
        await svc.create_business_line(_make_bl(name="bl-2", tenant_id="t-1"))
        await svc.create_business_line(_make_bl(name="bl-3", tenant_id="t-2"))
        result = await svc.list_business_lines(BusinessLineFilter(tenantId="t-1"))
        assert len(result) == 2

    @pytest.mark.asyncio
    async def test_list_by_member(self, mock_bl_store):
        """按成员过滤：仅返回该成员可见的业务线（权限隔离）."""
        svc = BusinessLineService(mock_bl_store)
        await svc.create_business_line(
            _make_bl(name="bl-1", tenant_id="t-1", member_ids=["u-1", "u-2"])
        )
        await svc.create_business_line(
            _make_bl(name="bl-2", tenant_id="t-1", member_ids=["u-2"])
        )
        result = await svc.list_business_lines(BusinessLineFilter(memberId="u-1"))
        assert len(result) == 1
        assert result[0].name == "bl-1"


class TestBusinessLineUpdate:
    """更新业务线测试."""

    @pytest.mark.asyncio
    async def test_update_name(self, mock_bl_store):
        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl()
        await svc.create_business_line(bl)
        result = await svc.update_business_line(bl.id, {"name": "风控线-v2"})
        assert result.name == "风控线-v2"

    @pytest.mark.asyncio
    async def test_update_status(self, mock_bl_store):
        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl()
        await svc.create_business_line(bl)
        result = await svc.update_business_line(
            bl.id, {"status": BusinessLineStatus.SUSPENDED}
        )
        assert result.status == BusinessLineStatus.SUSPENDED

    @pytest.mark.asyncio
    async def test_update_by_non_owner_denied(self, mock_bl_store):
        """非业务线管理员不可更新（权限隔离）."""
        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl(owner_ids=["admin-1"], member_ids=["admin-1", "user-1"])
        await svc.create_business_line(bl)
        with pytest.raises(PermissionDeniedError):
            await svc.update_business_line(
                bl.id, {"name": "x"}, user_id="user-1"
            )

    @pytest.mark.asyncio
    async def test_update_by_owner_ok(self, mock_bl_store):
        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl(owner_ids=["admin-1"], member_ids=["admin-1"])
        await svc.create_business_line(bl)
        result = await svc.update_business_line(
            bl.id, {"name": "x"}, user_id="admin-1"
        )
        assert result.name == "x"

    @pytest.mark.asyncio
    async def test_update_duplicate_name_raises(self, mock_bl_store):
        svc = BusinessLineService(mock_bl_store)
        bl1 = _make_bl(name="bl-1", tenant_id="t-1")
        bl2 = _make_bl(name="bl-2", tenant_id="t-1")
        await svc.create_business_line(bl1)
        await svc.create_business_line(bl2)
        with pytest.raises(BusinessLineAlreadyExistsError):
            await svc.update_business_line(bl2.id, {"name": "bl-1"})


class TestBusinessLineDelete:
    """删除业务线测试."""

    @pytest.mark.asyncio
    async def test_delete_success(self, mock_bl_store):
        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl()
        await svc.create_business_line(bl)
        await svc.delete_business_line(bl.id)
        with pytest.raises(BusinessLineNotFoundError):
            await svc.get_business_line(bl.id)

    @pytest.mark.asyncio
    async def test_delete_not_found_raises(self, mock_bl_store):
        svc = BusinessLineService(mock_bl_store)
        with pytest.raises(BusinessLineNotFoundError):
            await svc.delete_business_line("nonexistent-id")

    @pytest.mark.asyncio
    async def test_delete_by_non_owner_denied(self, mock_bl_store):
        """非业务线管理员不可删除（权限隔离）."""
        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl(owner_ids=["admin-1"], member_ids=["admin-1", "user-1"])
        await svc.create_business_line(bl)
        with pytest.raises(PermissionDeniedError):
            await svc.delete_business_line(bl.id, user_id="user-1")


class TestBusinessLineUsage:
    """用量概览测试."""

    @pytest.mark.asyncio
    async def test_get_usage(self, mock_bl_store):
        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl()
        await svc.create_business_line(bl)
        usage = await svc.get_usage(bl.id)
        assert usage.blId == bl.id
        assert usage.memberCount == 3
        assert usage.teamCount == 1

    @pytest.mark.asyncio
    async def test_get_usage_not_found_raises(self, mock_bl_store):
        svc = BusinessLineService(mock_bl_store)
        with pytest.raises(BusinessLineNotFoundError):
            await svc.get_usage("nonexistent-id")


class TestBudget:
    """预算模型测试."""

    def test_budget_remaining(self):
        b = Budget(total=100.0, used=30.0)
        assert b.remaining == 70.0

    def test_budget_usage_ratio(self):
        b = Budget(total=100.0, used=30.0)
        assert b.usageRatio == 0.3

    def test_budget_exceeded(self):
        b = Budget(total=100.0, used=120.0)
        assert b.isExceeded is True

    def test_budget_not_exceeded(self):
        b = Budget(total=100.0, used=50.0)
        assert b.isExceeded is False