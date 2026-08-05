"""仓储层异常定义."""
from __future__ import annotations


class PortalError(Exception):
    """业务线门户基础异常."""


class BusinessLineNotFoundError(PortalError):
    """业务线不存在."""

    def __init__(self, bl_id: str):
        self.blId = bl_id
        super().__init__(f"业务线不存在: {bl_id}")


class BusinessLineAlreadyExistsError(PortalError):
    """业务线已存在（同名或同 ID）."""

    def __init__(self, name: str):
        self.name = name
        super().__init__(f"业务线已存在: {name}")


class ReportNotFoundError(PortalError):
    """报表不存在."""

    def __init__(self, report_id: str):
        self.reportId = report_id
        super().__init__(f"报表不存在: {report_id}")


class CatalogNodeNotFoundError(PortalError):
    """数据目录节点不存在."""

    def __init__(self, node_id: str):
        self.nodeId = node_id
        super().__init__(f"数据目录节点不存在: {node_id}")


class ValidationError(PortalError):
    """业务校验失败."""


class PermissionDeniedError(PortalError):
    """跨业务线访问被拒绝（权限隔离）."""

    def __init__(self, bl_id: str, user: str):
        self.blId = bl_id
        self.user = user
        super().__init__(f"用户 {user} 无权访问业务线 {bl_id}")


class BudgetExceededError(PortalError):
    """超出业务线预算（软限制，告警不阻断）."""

    def __init__(self, bl_id: str, used: float, budget: float):
        self.blId = bl_id
        self.used = used
        self.budget = budget
        super().__init__(f"业务线 {bl_id} 预算超限: 已用 {used} > 预算 {budget}")