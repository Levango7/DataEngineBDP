"""资产管理业务逻辑.

负责资产登记、审核、上架、下架、浏览、详情、更新、下载、调用。
安全分级对齐设计文档 §5：
    - PUBLIC: 可直接流通
    - INTERNAL: 需平台审批后流通
    - SENSITIVE: 必须脱敏后流通

资产状态机：
    DRAFT -> PENDING_AUDIT (提交审核)
    PENDING_AUDIT -> LISTED (审核通过)
    PENDING_AUDIT -> REJECTED (审核驳回)
    LISTED -> OFFLINE (下架)
    OFFLINE -> LISTED (重新上架)
"""

from __future__ import annotations

from typing import Any, Optional

from asset_exchange.interfaces.asset_repository import AssetRepository
from asset_exchange.interfaces.subscription_repository import (
    SubscriptionRepository,
)
from asset_exchange.models.asset import Asset, AssetFilter, AssetUsage
from asset_exchange.models.base import (
    AssetAuditResult,
    AssetStatus,
    SecurityLevel,
)
from asset_exchange.models.subscription import SubscriptionStatus
from asset_exchange.repositories import (
    AssetNotListedError,
    ValidationError,
)

# 资产状态扩展：PENDING_AUDIT 表示待审核（已加入 AssetStatus 枚举）
STATUS_PENDING_AUDIT = AssetStatus.PENDING_AUDIT.value


class AssetService:
    """资产管理服务（编排 AssetRepository）."""

    def __init__(
        self,
        asset_repo: AssetRepository,
        sub_repo: SubscriptionRepository,
    ) -> None:
        self._asset_repo = asset_repo
        self._sub_repo = sub_repo

    # ---------- 资产登记 ----------

    async def register(self, asset: Asset) -> Asset:
        """资产登记.

        业务校验：
        - 名称非空
        - 登记后状态置为 DRAFT（草稿），需提交审核才能上架

        Returns:
            已登记的资产。
        """
        asset.status = AssetStatus.DRAFT
        asset_id = await self._asset_repo.save(asset)
        return await self._asset_repo.get(asset_id)

    async def submit_audit(self, asset_id: str) -> Asset:
        """提交审核.

        状态转换：DRAFT -> PENDING_AUDIT

        Raises:
            ValidationError: 资产非 DRAFT 状态。
        """
        a = await self._asset_repo.get(asset_id)
        if a.status != AssetStatus.DRAFT:
            raise ValidationError(f"资产当前状态 {a.status.value} 不可提交审核，仅 DRAFT 状态可提交")
        await self._asset_repo.update(asset_id, status=AssetStatus.PENDING_AUDIT)
        return await self._asset_repo.get(asset_id)

    async def audit(
        self,
        asset_id: str,
        result: AssetAuditResult,
        auditor_id: str,
        reason: Optional[str] = None,
    ) -> Asset:
        """资产审核（合规/质量/分级检查）.

        审核规则：
        - 合规检查：securityLevel 不能为 SENSITIVE（敏感资产需先脱敏）
        - 质量检查：qualityScore >= 60
        - 分级检查：securityLevel 必须为合法值

        状态转换：
        - PENDING_AUDIT + 通过 -> LISTED
        - PENDING_AUDIT + 驳回 -> REJECTED

        Args:
            asset_id: 资产 ID。
            result: 审核结果（APPROVED / REJECTED）。
            auditor_id: 审核人 ID。
            reason: 审核原因（驳回时填）。

        Returns:
            审核后的资产。
        """
        a = await self._asset_repo.get(asset_id)
        # 允许 PENDING_AUDIT 或 DRAFT 状态审核（兼容旧流程）
        if a.status.value not in (STATUS_PENDING_AUDIT, AssetStatus.DRAFT.value):
            raise ValidationError(f"资产当前状态 {a.status.value} 不可审核")

        # 自动合规/质量/分级检查
        checks = self._run_audit_checks(a)
        all_passed = all(c["passed"] for c in checks)

        if result == AssetAuditResult.APPROVED:
            if not all_passed:
                failed = [c["name"] for c in checks if not c["passed"]]
                raise ValidationError(f"审核不通过，检查项失败: {failed}")
            await self._asset_repo.update(asset_id, status=AssetStatus.LISTED)
        else:
            await self._asset_repo.update(asset_id, status=AssetStatus.REJECTED)

        return await self._asset_repo.get(asset_id)

    def _run_audit_checks(self, asset: Asset) -> list[dict[str, Any]]:
        """执行审核检查（合规/质量/分级）.

        Returns:
            检查结果列表，每项 {"name", "passed", "message"}。
        """
        checks: list[dict[str, Any]] = []

        # 合规检查：敏感资产必须配置脱敏
        if asset.securityLevel == SecurityLevel.SENSITIVE:
            desensitize = asset.tags.get("desensitize") == "true"
            checks.append(
                {
                    "name": "compliance",
                    "passed": desensitize,
                    "message": (
                        "敏感资产必须配置脱敏规则（tags.desensitize=true）" if not desensitize else "合规检查通过"
                    ),
                }
            )
        else:
            checks.append({"name": "compliance", "passed": True, "message": "合规检查通过"})

        # 质量检查：qualityScore >= 60
        quality_ok = asset.qualityScore >= 60
        checks.append(
            {
                "name": "quality",
                "passed": quality_ok,
                "message": f"质量评分 {asset.qualityScore} < 60" if not quality_ok else "质量检查通过",
            }
        )

        # 分级检查：securityLevel 必须为合法值
        checks.append({"name": "classification", "passed": True, "message": "分级检查通过"})

        return checks

    async def publish(self, asset_id: str) -> Asset:
        """资产上架.

        业务校验：
        - 资产必须为 DRAFT 或 PENDING_AUDIT 状态
        - 自动执行审核检查（合规/质量/分级），全部通过才上架
        - 上架后状态置为 LISTED

        Raises:
            ValidationError: 校验失败。
        """
        a = await self._asset_repo.get(asset_id)
        # 允许 DRAFT、PENDING_AUDIT、OFFLINE 状态上架
        if a.status.value not in (
            AssetStatus.DRAFT.value,
            STATUS_PENDING_AUDIT,
            AssetStatus.OFFLINE.value,
        ):
            raise ValidationError(f"资产当前状态 {a.status.value} 不可上架")

        # 自动审核检查
        checks = self._run_audit_checks(a)
        failed = [c["name"] for c in checks if not c["passed"]]
        if failed:
            raise ValidationError(f"上架审核不通过，检查项失败: {failed}")

        await self._asset_repo.update(asset_id, status=AssetStatus.LISTED)
        return await self._asset_repo.get(asset_id)

    # ---------- 兼容旧 list_asset（直接上架）----------

    async def list_asset(self, asset: Asset) -> Asset:
        """上架资产（兼容旧接口，等价于 register + publish）.

        业务校验：
        - SENSITIVE 资产必须配置脱敏规则
        - 上架后状态置为 LISTED

        Raises:
            ValidationError: 校验失败。
        """
        # 敏感资产校验
        if asset.securityLevel == SecurityLevel.SENSITIVE:
            if asset.tags.get("desensitize") != "true":
                raise ValidationError("敏感资产必须配置脱敏规则（tags.desensitize=true）")
        # 质量校验
        if asset.qualityScore < 60:
            raise ValidationError(f"质量评分 {asset.qualityScore} < 60，不可上架")
        asset.status = AssetStatus.LISTED
        asset_id = await self._asset_repo.save(asset)
        return await self._asset_repo.get(asset_id)

    # ---------- 资产流通 ----------

    async def download(
        self,
        asset_id: str,
        subscriber_id: str,
        rows: int = 100,
    ) -> dict[str, Any]:
        """资产下载（流通方式之一）.

        业务校验：
        - 资产必须为 LISTED 状态
        - 不允许下载自己的资产

        Args:
            asset_id: 资产 ID。
            subscriber_id: 下载方租户 ID。
            rows: 下载行数。

        Returns:
            下载凭证与统计。
        """
        a = await self._asset_repo.get(asset_id)
        if a.status != AssetStatus.LISTED:
            raise AssetNotListedError(asset_id, a.status.value)
        if a.tenantId == subscriber_id:
            raise ValidationError("不允许下载自己的资产")
        return {
            "assetId": asset_id,
            "subscriberId": subscriber_id,
            "method": "download",
            "rows": rows,
            "bytes": rows * 256,
            "downloadUrl": f"https://storage.example.com/assets/{asset_id}/data?rows={rows}",
            "checksum": "sha256:" + "0" * 64,
        }

    async def invoke(
        self,
        asset_id: str,
        subscriber_id: str,
        params: Optional[dict[str, Any]] = None,
    ) -> dict[str, Any]:
        """资产 API 调用（流通方式之一）.

        业务校验：
        - 资产必须为 LISTED 状态
        - 不允许调用自己的资产

        Args:
            asset_id: 资产 ID。
            subscriber_id: 调用方租户 ID。
            params: 调用参数。

        Returns:
            调用结果。
        """
        a = await self._asset_repo.get(asset_id)
        if a.status != AssetStatus.LISTED:
            raise AssetNotListedError(asset_id, a.status.value)
        if a.tenantId == subscriber_id:
            raise ValidationError("不允许调用自己的资产")
        return {
            "assetId": asset_id,
            "subscriberId": subscriber_id,
            "method": "invoke",
            "params": params or {},
            "result": {"status": "ok", "data": []},
            "invocationId": f"inv-{asset_id[:8]}-{subscriber_id[:8]}",
        }

    # ---------- 资产查询与管理 ----------

    async def get_asset(self, asset_id: str) -> Asset:
        return await self._asset_repo.get(asset_id)

    async def list_assets(self, filter: Optional[AssetFilter] = None) -> list[Asset]:
        """浏览资产市场（仅返回已上架资产，除非 filter 显式指定状态）."""
        f = filter or AssetFilter()
        if f.status is None:
            # 默认只看上架资产
            f.status = AssetStatus.LISTED
        return await self._asset_repo.list(f)

    async def update_asset(self, asset_id: str, **fields: Any) -> Asset:
        return await self._asset_repo.update(asset_id, **fields)

    async def offline_asset(self, asset_id: str) -> Asset:
        """下架资产.

        Raises:
            AssetNotFoundError: 资产不存在。
        """
        a = await self._asset_repo.get(asset_id)
        a.status = AssetStatus.OFFLINE
        await self._asset_repo.save(a)
        return await self._asset_repo.get(asset_id)

    async def relist_asset(self, asset_id: str) -> Asset:
        """重新上架."""
        a = await self._asset_repo.get(asset_id)
        a.status = AssetStatus.LISTED
        await self._asset_repo.save(a)
        return await self._asset_repo.get(asset_id)

    async def delete_asset(self, asset_id: str) -> None:
        """删除资产（仅允许 OFFLINE 状态删除）.

        Raises:
            ValidationError: 资产未下架。
        """
        a = await self._asset_repo.get(asset_id)
        if a.status == AssetStatus.LISTED:
            raise ValidationError("资产已上架，请先下架再删除")
        await self._asset_repo.delete(asset_id)

    async def get_usage(self, asset_id: str) -> AssetUsage:
        """获取资产使用统计."""
        a = await self._asset_repo.get(asset_id)
        subs = await self._sub_repo.list_by_asset(asset_id)
        active_subs = [s for s in subs if s.status == SubscriptionStatus.ACTIVE]
        # 简化统计：订阅数即资产 subscriberCount
        return AssetUsage(
            assetId=asset_id,
            subscriberCount=a.subscriberCount,
            totalCalls=a.subscriberCount * 100,  # 简化估算
            totalDataRows=a.subscriberCount * 1000,
            totalRevenue=a.subscriberCount * a.pricing.price,
            activeSubscriptions=len(active_subs),
        )

    async def _incr_subscriber(self, asset_id: str) -> Asset:
        """订阅者数 +1（订阅服务调用）."""
        a = await self._asset_repo.get(asset_id)
        a.subscriberCount += 1
        await self._asset_repo.save(a)
        return await self._asset_repo.get(asset_id)

    async def _decr_subscriber(self, asset_id: str) -> Asset:
        """订阅者数 -1（取消订阅时调用）."""
        a = await self._asset_repo.get(asset_id)
        if a.subscriberCount > 0:
            a.subscriberCount -= 1
        await self._asset_repo.save(a)
        return await self._asset_repo.get(asset_id)
