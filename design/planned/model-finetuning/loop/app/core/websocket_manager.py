"""WebSocket 连接管理器.

负责管理前端 WebSocket 连接，实时推送闭环任务进度与训练指标。

消息类型：
    - status：状态变更（pending → finetuning → evaluating → deploying → completed）
    - progress：步骤进度
    - metrics：训练指标（loss/lr/GPU 利用率）
    - log：日志条目
    - error：错误信息
    - completed：闭环完成

设计要点：
    - 每个闭环任务对应一个广播组，多个前端可订阅同一任务。
    - 线程安全：通过 asyncio.Lock 保护订阅集合。
    - 心跳：定期发送 ping 保持连接。
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import json
import logging
from typing import Any

from fastapi import WebSocket

logger = logging.getLogger(__name__)


class WebSocketManager:
    """WebSocket 连接管理器.

    维护 taskId → set[WebSocket] 的订阅关系，
    提供 broadcast 方法向所有订阅某任务的前端推送消息。
    """

    def __init__(self) -> None:
        # taskId → set[WebSocket]
        self._subscriptions: dict[str, set[WebSocket]] = {}
        # WebSocket → taskId（反向索引，便于断开时清理）
        self._reverse: dict[WebSocket, str] = {}
        self._lock = asyncio.Lock()

    async def connect(self, taskId: str, ws: WebSocket) -> None:
        """接受 WebSocket 连接并订阅指定任务.

        Args:
            taskId: 闭环任务 ID.
            ws: WebSocket 连接.
        """
        await ws.accept()
        async with self._lock:
            if taskId not in self._subscriptions:
                self._subscriptions[taskId] = set()
            self._subscriptions[taskId].add(ws)
            self._reverse[ws] = taskId
        logger.info(
            "WebSocket 已连接: task=%s, 当前订阅数=%d",
            taskId,
            len(self._subscriptions[taskId]),
        )

    async def disconnect(self, taskId: str, ws: WebSocket) -> None:
        """断开 WebSocket 连接并清理订阅.

        Args:
            taskId: 闭环任务 ID.
            ws: WebSocket 连接.
        """
        async with self._lock:
            if taskId in self._subscriptions:
                self._subscriptions[taskId].discard(ws)
                if not self._subscriptions[taskId]:
                    del self._subscriptions[taskId]
            self._reverse.pop(ws, None)
        logger.info(
            "WebSocket 已断开: task=%s",
            taskId,
        )

    async def broadcast(self, taskId: str, message: dict[str, Any]) -> None:
        """向订阅指定任务的所有前端推送消息.

        Args:
            taskId: 闭环任务 ID.
            message: 消息字典，将序列化为 JSON.
        """
        async with self._lock:
            subscribers = list(self._subscriptions.get(taskId, set()))

        if not subscribers:
            return

        # 注入时间戳（若未提供）
        message.setdefault("timestamp", datetime.now(timezone.utc).isoformat())
        text = json.dumps(message, ensure_ascii=False, default=str)

        # 并行发送，忽略失败连接
        results = await asyncio.gather(
            *[self._safe_send(ws, text) for ws in subscribers],
            return_exceptions=True,
        )
        # 清理失败连接
        failed = [ws for ws, ok in zip(subscribers, results) if not ok]
        if failed:
            async with self._lock:
                for ws in failed:
                    tid = self._reverse.pop(ws, None)
                    if tid and tid in self._subscriptions:
                        self._subscriptions[tid].discard(ws)

    async def _safe_send(self, ws: WebSocket, text: str) -> bool:
        """安全发送文本，失败返回 False."""
        try:
            await ws.send_text(text)
            return True
        except Exception as e:  # noqa: BLE001
            logger.warning("WebSocket 发送失败: %s", e)
            return False

    # ============================================================
    # 便捷推送方法
    # ============================================================
    async def push_status(
        self,
        taskId: str,
        status: str,
        step: str,
        **extra: Any,
    ) -> None:
        """推送状态变更消息."""
        await self.broadcast(
            taskId,
            {
                "type": "status",
                "taskId": taskId,
                "data": {"status": status, "step": step, **extra},
            },
        )

    async def push_metrics(
        self,
        taskId: str,
        step: str,
        metrics: dict[str, Any],
    ) -> None:
        """推送训练指标（loss/lr/GPU 利用率）."""
        await self.broadcast(
            taskId,
            {
                "type": "metrics",
                "taskId": taskId,
                "data": {"step": step, **metrics},
            },
        )

    async def push_log(
        self,
        taskId: str,
        step: str,
        message: str,
    ) -> None:
        """推送日志条目."""
        await self.broadcast(
            taskId,
            {
                "type": "log",
                "taskId": taskId,
                "data": {"step": step, "message": message},
            },
        )

    async def push_error(
        self,
        taskId: str,
        step: str,
        error: str,
    ) -> None:
        """推送错误信息."""
        await self.broadcast(
            taskId,
            {
                "type": "error",
                "taskId": taskId,
                "data": {"step": step, "error": error},
            },
        )

    async def push_completed(
        self,
        taskId: str,
        summary: dict[str, Any],
    ) -> None:
        """推送闭环完成消息."""
        await self.broadcast(
            taskId,
            {
                "type": "completed",
                "taskId": taskId,
                "data": summary,
            },
        )

    # ============================================================
    # 统计
    # ============================================================
    def stats(self) -> dict[str, Any]:
        """返回管理器统计信息."""
        return {
            "taskSubscriptions": len(self._subscriptions),
            "totalConnections": sum(len(s) for s in self._subscriptions.values()),
        }
