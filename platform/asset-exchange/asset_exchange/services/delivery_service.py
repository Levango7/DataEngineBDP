"""数据交付业务逻辑.

支持三种交付方式（对齐设计文档 §3）：
- API 交付：消费方通过 API 拉取（订阅模式 ①）
- 文件交付：生成数据文件供下载（交易模式 ④）
- 数据库直连交付：授权访问源库（高价值数据集）

交付状态机：
    PENDING -> RUNNING -> SUCCEEDED
    PENDING/RUNNING -> FAILED
"""
from __future__ import annotations

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
    SubscriptionNotFoundError,
    SubscriptionNotDeliverableError,
)


class DeliveryService:
    """数据交付服务."""

    def __init__(
        self,
        delivery_repo: DeliveryRepository,
        sub_repo: SubscriptionRepository,
    ) -> None:
        self._delivery_repo = delivery_repo
        self._sub_repo = sub_repo

    async def deliver(
        self, subscription_id: str, req: DeliveryRequest
    ) -> Delivery:
        """发起数据交付.

        业务校验：
        - 订阅必须为 APPROVED 或 ACTIVE 状态

        Raises:
            SubscriptionNotFoundError: 订阅不存在。
            SubscriptionNotDeliverableError: 订阅不可交付。
        """
        sub = await self._sub_repo.get(subscription_id)
        if sub.status not in (SubscriptionStatus.APPROVED, SubscriptionStatus.ACTIVE):
            raise SubscriptionNotDeliverableError(
                subscription_id, sub.status.value
            )

        # 创建交付记录
        delivery = Delivery(
            subscriptionId=subscription_id,
            method=req.method,
            config=req.config,
            status=DeliveryStatus.PENDING,
        )
        delivery_id = await self._delivery_repo.save(delivery)
        delivery = await self._delivery_repo.get(delivery_id)

        # 执行交付（Mock 实现：同步置为 RUNNING -> SUCCEEDED）
        delivery = await self._execute_delivery(delivery)
        return delivery

    async def _execute_delivery(self, delivery: Delivery) -> Delivery:
        """执行交付（Mock 实现）.

        根据交付方式生成不同的交付产物：
        - API:             生成 endpoint + apiKey
        - FILE:            生成临时文件 URL
        - DATABASE_DIRECT: 生成授权访问凭证
        """
        now = utc_now()
        # 更新为 RUNNING
        delivery = await self._delivery_repo.update(
            delivery.id,
            status=DeliveryStatus.RUNNING,
            startedAt=now,
        )

        try:
            if delivery.method == DeliveryMethod.API:
                artifact_url, meta, rows, bytes_ = self._deliver_via_api(
                    delivery.config
                )
            elif delivery.method == DeliveryMethod.FILE:
                artifact_url, meta, rows, bytes_ = self._deliver_via_file(
                    delivery.config
                )
            elif delivery.method == DeliveryMethod.DATABASE_DIRECT:
                artifact_url, meta, rows, bytes_ = (
                    self._deliver_via_database_direct(delivery.config)
                )
            else:
                raise ValueError(f"不支持的交付方式: {delivery.method}")

            # 更新为 SUCCEEDED
            delivery = await self._delivery_repo.update(
                delivery.id,
                status=DeliveryStatus.SUCCEEDED,
                artifactUrl=artifact_url,
                artifactMeta=meta,
                dataRows=rows,
                dataBytes=bytes_,
                finishedAt=utc_now(),
            )
            return delivery
        except Exception as exc:
            # 更新为 FAILED
            delivery = await self._delivery_repo.update(
                delivery.id,
                status=DeliveryStatus.FAILED,
                errorMessage=str(exc),
                finishedAt=utc_now(),
            )
            return delivery

    def _deliver_via_api(
        self, config: dict[str, Any]
    ) -> tuple[str, dict[str, Any], int, int]:
        """API 交付：生成 API 端点与凭证.

        Returns:
            (artifact_url, meta, data_rows, data_bytes)
        """
        endpoint = config.get("endpoint", "/api/v1/data/query")
        # Mock: 生成 API key
        api_key = "ak-" + "x" * 32
        artifact_url = endpoint
        meta = {
            "apiKey": api_key,
            "headers": config.get("headers", {}),
            "rateLimit": config.get("rateLimit", "100/s"),
        }
        # Mock 数据量
        rows = config.get("sampleRows", 100)
        bytes_ = rows * 256  # 假设每行 256 字节
        return artifact_url, meta, rows, bytes_

    def _deliver_via_file(
        self, config: dict[str, Any]
    ) -> tuple[str, dict[str, Any], int, int]:
        """文件交付：生成数据文件 URL.

        Returns:
            (artifact_url, meta, data_rows, data_bytes)
        """
        fmt = config.get("format", "csv")
        # Mock: 生成临时文件 URL
        artifact_url = f"https://storage.example.com/delivery/{fmt}/data.{fmt}"
        meta = {
            "format": fmt,
            "encoding": config.get("encoding", "utf-8"),
            "expireAt": config.get("expireAt"),
            "checksum": "sha256:" + "0" * 64,
        }
        rows = config.get("sampleRows", 1000)
        bytes_ = rows * 256
        return artifact_url, meta, rows, bytes_

    def _deliver_via_database_direct(
        self, config: dict[str, Any]
    ) -> tuple[str, dict[str, Any], int, int]:
        """数据库直连交付：生成授权访问凭证.

        Returns:
            (artifact_url, meta, data_rows, data_bytes)
        """
        jdbc_url = config.get("jdbcUrl", "jdbc:postgresql://localhost:5432/data")
        table = config.get("tableName", "data_table")
        # Mock: 生成只读账号
        artifact_url = jdbc_url
        meta = {
            "username": "ro_user",
            "password": "***",  # 实际应加密
            "tableName": table,
            "privilege": "SELECT",
            "expireAt": config.get("expireAt"),
        }
        rows = config.get("sampleRows", 10000)
        bytes_ = rows * 256
        return artifact_url, meta, rows, bytes_

    async def get_delivery_status(
        self, subscription_id: str
    ) -> DeliveryStatusResponse:
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