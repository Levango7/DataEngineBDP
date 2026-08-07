"""SQL 网关对接客户端.

职责：
    1. 将生成的 SQL 发送到 sql-gateway（Java :8081）执行。
    2. 传递租户、引擎、行数限制等参数。
    3. 处理网关响应（成功 / 失败 / 模拟）。
    4. 网关不可达时返回结构化错误（不抛异常），由上层决定降级策略。

对接端点（参见 platform/sql-gateway/README.md）：
    POST /api/v1/sql/execute
    Body: { sql, engine, tenantId, limit }
    Resp: { queryId, status, columns, rows, durationMs, engine }
"""
from __future__ import annotations

from typing import Any, Optional

import httpx
from loguru import logger

from config.settings import Settings
from models import GatewayExecuteResult


class GatewayClient:
    """SQL 网关客户端."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._baseUrl = settings.sqlGatewayUrl.rstrip("/")
        self._timeout = settings.sqlGatewayTimeout

    async def execute(
        self,
        sql: str,
        engine: Optional[str] = None,
        tenantId: Optional[str] = None,
        limit: Optional[int] = None,
    ) -> GatewayExecuteResult:
        """执行 SQL.

        Args:
            sql: SQL 文本。
            engine: 引擎 trino / doris，None 用默认。
            tenantId: 租户 ID，None 用默认。
            limit: 行数限制，None 用默认。

        Returns:
            GatewayExecuteResult（含执行状态与结果或错误信息）。
        """
        payload: dict[str, Any] = {
            "sql": sql,
            "engine": engine or self.settings.defaultEngine,
            "tenantId": tenantId or self.settings.tenantId,
            "limit": limit or self.settings.defaultLimit,
        }
        url = f"{self._baseUrl}/api/v1/sql/execute"
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                resp = await client.post(url, json=payload)
                resp.raise_for_status()
                data = resp.json()
            return self._parseResponse(data)
        except httpx.HTTPStatusError as e:
            logger.warning("SQL 网关返回非 2xx: {} {}", e.response.status_code, e.response.text)
            return GatewayExecuteResult(
                status="ERROR",
                engine=payload["engine"],
                error=f"网关 HTTP {e.response.status_code}: {e.response.text[:200]}",
            )
        except httpx.RequestError as e:
            logger.warning("SQL 网关请求失败: {}", e)
            return GatewayExecuteResult(
                status="UNREACHABLE",
                engine=payload["engine"],
                error=f"网关不可达: {e}",
            )
        except Exception as e:  # noqa: BLE001
            logger.warning("SQL 网关调用异常: {}", e)
            return GatewayExecuteResult(
                status="ERROR",
                engine=payload["engine"],
                error=f"网关调用异常: {e}",
            )

    @staticmethod
    def _parseResponse(data: dict) -> GatewayExecuteResult:
        """解析网关响应."""
        columns = data.get("columns") or []
        # columns 可能是字符串列表或对象列表
        colNames: list[str] = []
        for c in columns:
            if isinstance(c, str):
                colNames.append(c)
            elif isinstance(c, dict):
                colNames.append(c.get("name") or c.get("columnName") or "")
        rows = data.get("rows") or []
        # rows 统一为 list[dict]
        normRows: list[dict[str, Any]] = []
        for r in rows:
            if isinstance(r, dict):
                normRows.append(r)
            elif isinstance(r, list):
                # 按列顺序转 dict
                normRows.append({colNames[i] if i < len(colNames) else f"col_{i}": v for i, v in enumerate(r)})
        return GatewayExecuteResult(
            queryId=data.get("queryId"),
            status=data.get("status", "OK"),
            columns=colNames,
            rows=normRows,
            durationMs=float(data.get("durationMs") or 0.0),
            engine=data.get("engine"),
            error=data.get("error"),
        )

    async def health(self) -> bool:
        """网关健康检查."""
        url = f"{self._baseUrl}/api/v1/health"
        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                resp = await client.get(url)
                return resp.status_code == 200
        except Exception:  # noqa: BLE001
            return False