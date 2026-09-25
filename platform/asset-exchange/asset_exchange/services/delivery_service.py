"""数据交付业务逻辑.

三种交付方式的实现度（详见 docs/资产交付实现状态.md）：
- API 交付：真实实现——产物是 open-api-catalog 发放、由 APISIX key-auth 生效的
  Access Key（订阅模式 ①）
- 文件交付：暂不具备条件（缺数据集物化与对象存储预签名），如实返回失败（交易模式 ④）
- 数据库直连交付：暂不具备条件（缺凭据托管与权限编排），如实返回失败

交付状态机：
    PENDING -> RUNNING -> SUCCEEDED        产物真实可得
    RUNNING -> PENDING                     凭证待提供方审批（不算成功）
    RUNNING -> FAILED                      失败或不具备条件（errorMessage 说明原因）
"""

from __future__ import annotations

from dataclasses import dataclass, field
import logging
from typing import Any, Optional

from asset_exchange.interfaces.delivery_repository import DeliveryRepository
from asset_exchange.interfaces.subscription_repository import (
    SubscriptionRepository,
)
from asset_exchange.models.base import (
    DeliveryMethod,
    DeliveryStatus,
    SubscriptionStatus,
    utc_now,
)
from asset_exchange.models.delivery import (
    Delivery,
    DeliveryRequest,
    DeliveryStatusResponse,
)
from asset_exchange.repositories import (
    DeliveryNotFoundError,
    SubscriptionNotDeliverableError,
)
from asset_exchange.services.openapi_catalog_client import OpenApiCatalogClient

logger = logging.getLogger(__name__)


class DeliveryUnavailableError(Exception):
    """该交付方式当前不具备真实实现条件.

    语义是"拒绝伪造产物"：宁可把交付置为 FAILED 并写清解锁条件，
    也不能返回假 URL / 假 checksum / 假账号 yet 标记 SUCCEEDED——
    消费方会据此集成，最终在真实调用时才暴露问题。
    """


def _mask_key(value: str) -> str:
    """凭证掩码：保留首尾便于识别，不泄露完整凭据。"""
    if len(value) <= 8:
        return value[:2] + "****"
    return f"{value[:4]}****{value[-4:]}"


@dataclass
class _DeliveryOutcome:
    """一次交付执行的结果.

    Attributes:
        artifact_url: 交付产物地址（真实可调用/可下载）。
        artifact_meta: 产物元数据（含凭证引用，不含明文凭据）。
        data_rows: 交付数据行数；无法在交付时刻测量时为 0（不估算）。
        data_bytes: 交付数据字节数；同上。
        pending_reason: 非空表示"产物尚不可用，需等待外部动作"，
                        交付将留在 PENDING 而不是 SUCCEEDED。
    """

    artifact_url: str
    artifact_meta: dict[str, Any] = field(default_factory=dict)
    data_rows: int = 0
    data_bytes: int = 0
    pending_reason: Optional[str] = None


class DeliveryService:
    """数据交付服务."""

    def __init__(
        self,
        delivery_repo: DeliveryRepository,
        sub_repo: SubscriptionRepository,
        catalog_client: Optional[OpenApiCatalogClient] = None,
    ) -> None:
        self._delivery_repo = delivery_repo
        self._sub_repo = sub_repo
        self._catalog = catalog_client

    async def deliver(
        self,
        subscription_id: str,
        req: DeliveryRequest,
        jwt_token: Optional[str] = None,
    ) -> Delivery:
        """发起数据交付.

        业务校验：
        - 订阅必须为 APPROVED 或 ACTIVE 状态

        Args:
            subscription_id: 订阅 ID.
            req: 交付请求.
            jwt_token: 透传给开放 API 目录的 JWT（API 交付需据此取真实凭证）.

        Raises:
            SubscriptionNotFoundError: 订阅不存在。
            SubscriptionNotDeliverableError: 订阅不可交付。
        """
        sub = await self._sub_repo.get(subscription_id)
        if sub.status not in (SubscriptionStatus.APPROVED, SubscriptionStatus.ACTIVE):
            raise SubscriptionNotDeliverableError(subscription_id, sub.status.value)

        # 创建交付记录
        delivery = Delivery(
            subscriptionId=subscription_id,
            method=req.method,
            config=req.config,
            status=DeliveryStatus.PENDING,
        )
        delivery_id = await self._delivery_repo.save(delivery)
        delivery = await self._delivery_repo.get(delivery_id)

        # 执行交付
        delivery = await self._execute_delivery(delivery, sub, jwt_token)
        return delivery

    async def _execute_delivery(
        self,
        delivery: Delivery,
        sub: Any,
        jwt_token: Optional[str] = None,
    ) -> Delivery:
        """执行交付并落状态.

        三种结局，绝不"伪造成功"：
        - 产物真实可得        → SUCCEEDED + 真实产物
        - 凭证待提供方审批    → 留在 PENDING（可复查），记录 credentialRef 与原因
        - 不具备实现条件      → FAILED，errorMessage 写清缺什么、怎么解锁
        """
        delivery = await self._delivery_repo.update(
            delivery.id,
            status=DeliveryStatus.RUNNING,
            startedAt=utc_now(),
        )

        try:
            if delivery.method == DeliveryMethod.API:
                outcome = await self._deliver_via_api(delivery, sub, jwt_token)
            elif delivery.method == DeliveryMethod.FILE:
                raise self._unavailable("FILE", delivery)
            elif delivery.method == DeliveryMethod.DATABASE_DIRECT:
                raise self._unavailable("DATABASE_DIRECT", delivery)
            else:
                raise ValueError(f"不支持的交付方式: {delivery.method}")
        except DeliveryUnavailableError as exc:
            return await self._delivery_repo.update(
                delivery.id,
                status=DeliveryStatus.FAILED,
                errorMessage=str(exc),
                finishedAt=utc_now(),
            )
        except Exception as exc:  # 网络/目录服务异常等：如实失败，便于排障
            logger.warning("交付执行失败: delivery=%s, err=%s", delivery.id, exc)
            return await self._delivery_repo.update(
                delivery.id,
                status=DeliveryStatus.FAILED,
                errorMessage=str(exc),
                finishedAt=utc_now(),
            )

        if outcome.pending_reason:
            # 凭证未发放：不置 SUCCEEDED，也不置 FAILED（等提供方审批后可重跑）
            logger.info("交付等待凭证发放: delivery=%s, sub=%s", delivery.id, outcome.pending_reason)
            return await self._delivery_repo.update(
                delivery.id,
                status=DeliveryStatus.PENDING,
                artifactUrl=None,
                artifactMeta=outcome.artifact_meta,
                errorMessage=outcome.pending_reason,
            )

        return await self._delivery_repo.update(
            delivery.id,
            status=DeliveryStatus.SUCCEEDED,
            artifactUrl=outcome.artifact_url,
            artifactMeta=outcome.artifact_meta,
            dataRows=outcome.data_rows,
            dataBytes=outcome.data_bytes,
            errorMessage=None,
            finishedAt=utc_now(),
        )

    def _unavailable(self, method_name: str, delivery: Delivery) -> DeliveryUnavailableError:
        """构造"该方式暂不具备真实实现条件"的错误（附解锁条件，见交付实现状态文档）."""
        return DeliveryUnavailableError(
            f"{method_name} 交付尚未具备真实实现条件：需要数据集物化落盘与"
            f"对象存储/凭据托管能力（当前平台未提供），"
            f"详见 docs/资产交付实现状态.md；交付 {delivery.id} 已置为 FAILED。"
        )

    async def _deliver_via_api(
        self,
        delivery: Delivery,
        sub: Any,
        jwt_token: Optional[str],
    ) -> _DeliveryOutcome:
        """API 交付：向开放 API 目录取得真实凭证.

        产物里的 Access Key 来自 open-api-catalog（提供方审批后发放、
        由 APISIX key-auth 生效），因此消费方拿到的 key 确实能调通接口。
        完整 AK 不入库（它就是凭据本身），只存掩码 + 凭证引用。
        """
        if self._catalog is None:
            raise DeliveryUnavailableError(
                "未配置开放 API 目录地址（ASSET_EXCHANGE_OPEN_API_CATALOG_URL），" "API 交付无法取得真实凭证"
            )

        api_id = delivery.config.get("apiId") or (sub.pullConfig or {}).get("apiId")
        if not api_id:
            raise DeliveryUnavailableError(
                "API 交付缺少 apiId：需在交付配置 config.apiId（或订阅 pullConfig.apiId）" "指定被订阅的开放 API"
            )

        cred = await self._catalog.obtain_credential(
            api_id=str(api_id),
            subscriber_id=str(getattr(sub, "subscriberId", "") or ""),
            subscriber_tenant_id=delivery.config.get("subscriberTenantId"),
            purpose=str(delivery.config.get("purpose") or f"资产 {sub.assetId} 的 API 交付"),
            quota_expect=int(delivery.config.get("quotaExpect") or 100),
            jwt_token=jwt_token,
        )

        meta: dict[str, Any] = {
            "credentialSource": "open-api-catalog",
            "catalogSubscriptionId": cred.subscriptionId,
            "catalogSubscriptionStatus": cred.status,
            "apiId": str(api_id),
            "grantedQuota": cred.grantedQuota,
            # 调用量由 open-api-catalog 计量，交付时刻不可测：不编造行数/字节数
            "usageMeasuredBy": "open-api-catalog",
        }

        if not cred.issued:
            return _DeliveryOutcome(
                artifact_url="",
                artifact_meta=meta,
                pending_reason=(
                    f"API 目录订阅 {cred.subscriptionId} 当前状态为 {cred.status}，"
                    f"等待提供方审批发放 Access Key 后可重新交付"
                ),
            )

        meta["accessKeyMasked"] = _mask_key(cred.accessKey or "")
        meta["accessKeyNote"] = (
            "完整 Access Key 不入库；请到开放 API 目录订阅详情查看"
            f"（GET {self._catalog._baseUrl}/subscriptions/{cred.subscriptionId}）"
        )
        # dataRows/dataBytes 留空（0）：真实用量来自计量服务，不在交付时刻估算
        return _DeliveryOutcome(
            artifact_url=cred.invokePath or f"/api/v1/apis/{api_id}/invoke",
            artifact_meta=meta,
        )

    async def get_delivery_status(self, subscription_id: str) -> DeliveryStatusResponse:
        """获取交付状态（按订阅 ID 查最新交付）.

        Raises:
            DeliveryNotFoundError: 该订阅无交付记录。
        """
        delivery = await self._delivery_repo.get_by_subscription(subscription_id)
        if delivery is None:
            raise DeliveryNotFoundError(f"订阅 {subscription_id} 无交付记录")

        return DeliveryStatusResponse(
            deliveryId=delivery.id,
            subscriptionId=delivery.subscriptionId,
            method=delivery.method,
            status=delivery.status,
            dataRows=delivery.dataRows,
            dataBytes=delivery.dataBytes,
            artifactUrl=delivery.artifactUrl,
            errorMessage=delivery.errorMessage,
            startedAt=delivery.startedAt,
            finishedAt=delivery.finishedAt,
        )

    async def get_delivery(self, delivery_id: str) -> Delivery:
        return await self._delivery_repo.get(delivery_id)
