"""SQLite CatalogStore 实现."""

from __future__ import annotations

import asyncio
from collections import deque
from datetime import timedelta
import json
from pathlib import Path
import re
import secrets
import sqlite3
import threading
from typing import Any

from openapi_catalog.models import (
    APIDefinition,
    APIFilter,
    APIStatus,
    APISubscription,
    CallMetric,
    SubscriptionFilter,
    SubscriptionStatus,
)
from openapi_catalog.models.api import (
    APIParam,
    APIResponse,
    APIUpstream,
)
from openapi_catalog.models.base import (
    AuthType,
    CostStrategy,
    HttpMethod,
    SLALevel,
    utc_now,
)
from openapi_catalog.repositories import (
    APIAlreadyExistsError,
    APINotFoundError,
    SubscriptionAlreadyExistsError,
    SubscriptionNotFoundError,
)

DEFAULT_DB_PATH = "data/openapi_catalog.db"
# 内存中保留最近 N 条计量用于 list_metrics 查询
METRICS_BUFFER_SIZE = 10000
# range 参数单位到秒数的映射
_RANGE_UNIT_SECONDS = {
    "s": 1,
    "m": 60,
    "h": 3600,
    "d": 86400,
    "w": 604800,
}


class SQLiteConnection:
    """SQLite 连接封装."""

    def __init__(self, db_path: str = DEFAULT_DB_PATH) -> None:
        path = Path(db_path)
        if path.parent and not path.parent.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
        self.dbPath = db_path
        self._conn = sqlite3.connect(
            db_path,
            check_same_thread=False,
            isolation_level=None,
        )
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA foreign_keys = ON;")
        self._conn.execute("PRAGMA journal_mode = WAL;")
        self._conn.execute("PRAGMA busy_timeout = 5000;")

    @property
    def conn(self) -> sqlite3.Connection:
        return self._conn

    def close(self) -> None:
        self._conn.close()

    def init_schema(self) -> None:
        """初始化全部表 schema."""
        self._conn.executescript("""
            CREATE TABLE IF NOT EXISTS apis (
                id                  TEXT PRIMARY KEY,
                name                TEXT NOT NULL,
                version             TEXT NOT NULL,
                description         TEXT,
                category            TEXT NOT NULL DEFAULT 'default',
                tags_json           TEXT NOT NULL DEFAULT '[]',
                method              TEXT NOT NULL,
                path                TEXT NOT NULL,
                params_json         TEXT NOT NULL DEFAULT '[]',
                responses_json      TEXT NOT NULL DEFAULT '[]',
                auth_type           TEXT NOT NULL,
                upstream_json       TEXT NOT NULL,
                sla                 TEXT NOT NULL,
                cost_strategy       TEXT NOT NULL,
                cost_unit_price     REAL NOT NULL DEFAULT 0,
                monthly_quota       INTEGER,
                status              TEXT NOT NULL,
                provider_tenant_id  TEXT NOT NULL,
                call_count          INTEGER NOT NULL DEFAULT 0,
                error_count         INTEGER NOT NULL DEFAULT 0,
                total_latency_ms    REAL NOT NULL DEFAULT 0,
                total_traffic_bytes INTEGER NOT NULL DEFAULT 0,
                created_at          TEXT NOT NULL,
                updated_at          TEXT NOT NULL,
                UNIQUE(name, version)
            );
            CREATE INDEX IF NOT EXISTS idx_apis_status ON apis(status);
            CREATE INDEX IF NOT EXISTS idx_apis_provider ON apis(provider_tenant_id);
            CREATE INDEX IF NOT EXISTS idx_apis_category ON apis(category);

            CREATE TABLE IF NOT EXISTS subscriptions (
                id                  TEXT PRIMARY KEY,
                api_id              TEXT NOT NULL,
                subscriber_id       TEXT NOT NULL,
                subscriber_tenant_id TEXT NOT NULL,
                provider_tenant_id  TEXT NOT NULL,
                purpose             TEXT NOT NULL,
                quota_expect        INTEGER NOT NULL,
                status              TEXT NOT NULL,
                access_key          TEXT,
                secret_key          TEXT,
                approve_reason      TEXT,
                approved_by         TEXT,
                granted_quota       INTEGER NOT NULL DEFAULT 0,
                call_count          INTEGER NOT NULL DEFAULT 0,
                error_count         INTEGER NOT NULL DEFAULT 0,
                last_called_at      TEXT,
                created_at          TEXT NOT NULL,
                updated_at          TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_subs_api ON subscriptions(api_id);
            CREATE INDEX IF NOT EXISTS idx_subs_subscriber ON subscriptions(subscriber_id);
            CREATE INDEX IF NOT EXISTS idx_subs_status ON subscriptions(status);
            CREATE INDEX IF NOT EXISTS idx_subs_access_key ON subscriptions(access_key);

            CREATE TABLE IF NOT EXISTS call_metrics (
                call_id             TEXT PRIMARY KEY,
                api_id              TEXT NOT NULL,
                api_version         TEXT NOT NULL,
                subscription_id     TEXT NOT NULL,
                consumer_tenant_id  TEXT NOT NULL,
                provider_tenant_id  TEXT NOT NULL,
                timestamp           TEXT NOT NULL,
                latency_ms          REAL NOT NULL,
                request_bytes       INTEGER NOT NULL DEFAULT 0,
                response_bytes      INTEGER NOT NULL DEFAULT 0,
                status_code         INTEGER NOT NULL,
                cost_strategy       TEXT NOT NULL,
                cost_amount         REAL NOT NULL DEFAULT 0,
                error_message       TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_metrics_api ON call_metrics(api_id);
            CREATE INDEX IF NOT EXISTS idx_metrics_ts ON call_metrics(timestamp);
            CREATE INDEX IF NOT EXISTS idx_metrics_sub ON call_metrics(subscription_id);
            """)


class SQLiteCatalogStore:
    """SQLite 存储实现.

    接口契约与 MockCatalogStore 完全一致。
    调用计量同时写入 SQLite 与内存环形缓冲，list_metrics 走内存以提速。
    """

    def __init__(self, conn: SQLiteConnection) -> None:
        self._conn = conn
        # 实例级写锁：串行化共享连接上的多语句读改写（save_metric / delete_api）
        self._write_lock = threading.Lock()
        # 内存缓冲：list_metrics 高频查询走内存
        self._metrics_buffer: deque[CallMetric] = deque(maxlen=METRICS_BUFFER_SIZE)
        self._load_metrics_into_buffer()

    def _load_metrics_into_buffer(self) -> None:
        """启动时把最近 N 条计量载入内存."""
        cur = self._conn.conn.execute(
            f"SELECT * FROM call_metrics ORDER BY timestamp DESC LIMIT {METRICS_BUFFER_SIZE};"
        )
        rows = cur.fetchall()
        for r in reversed(rows):
            self._metrics_buffer.append(self._row_to_metric(r))

    # ---------- API ----------

    async def save_api(self, api: APIDefinition) -> APIDefinition:
        try:
            self._conn.conn.execute(
                """
                INSERT INTO apis (
                    id, name, version, description, category, tags_json,
                    method, path, params_json, responses_json, auth_type,
                    upstream_json, sla, cost_strategy, cost_unit_price,
                    monthly_quota, status, provider_tenant_id,
                    call_count, error_count, total_latency_ms, total_traffic_bytes,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    version = excluded.version,
                    description = excluded.description,
                    category = excluded.category,
                    tags_json = excluded.tags_json,
                    method = excluded.method,
                    path = excluded.path,
                    params_json = excluded.params_json,
                    responses_json = excluded.responses_json,
                    auth_type = excluded.auth_type,
                    upstream_json = excluded.upstream_json,
                    sla = excluded.sla,
                    cost_strategy = excluded.cost_strategy,
                    cost_unit_price = excluded.cost_unit_price,
                    monthly_quota = excluded.monthly_quota,
                    status = excluded.status,
                    provider_tenant_id = excluded.provider_tenant_id,
                    call_count = excluded.call_count,
                    error_count = excluded.error_count,
                    total_latency_ms = excluded.total_latency_ms,
                    total_traffic_bytes = excluded.total_traffic_bytes,
                    updated_at = excluded.updated_at;
                """,
                (
                    api.id,
                    api.name,
                    api.version,
                    api.description,
                    api.category,
                    json.dumps(api.tags),
                    api.method.value,
                    api.path,
                    json.dumps([p.model_dump() for p in api.params]),
                    json.dumps([r.model_dump() for r in api.responses]),
                    api.authType.value,
                    api.upstream.model_dump_json(),
                    api.sla.value,
                    api.costStrategy.value,
                    api.costUnitPrice,
                    api.monthlyQuota,
                    api.status.value,
                    api.providerTenantId,
                    api.callCount,
                    api.errorCount,
                    api.totalLatencyMs,
                    api.totalTrafficBytes,
                    api.createdAt.isoformat(),
                    api.updatedAt.isoformat(),
                ),
            )
        except sqlite3.IntegrityError as e:
            # 同名同版本冲突
            raise APIAlreadyExistsError(api.name, api.version) from e
        return api

    async def get_api(self, api_id: str) -> APIDefinition:
        cur = self._conn.conn.execute("SELECT * FROM apis WHERE id = ?;", (api_id,))
        row = cur.fetchone()
        if row is None:
            raise APINotFoundError(api_id)
        return self._row_to_api(row)

    async def list_apis(self, filter_: APIFilter) -> list[APIDefinition]:
        clauses: list[str] = []
        params: list[Any] = []
        if filter_.name:
            clauses.append("LOWER(name) LIKE ?")
            params.append(f"%{filter_.name.lower()}%")
        if filter_.category:
            clauses.append("category = ?")
            params.append(filter_.category)
        if filter_.status:
            clauses.append("status = ?")
            params.append(filter_.status.value)
        if filter_.providerTenantId:
            clauses.append("provider_tenant_id = ?")
            params.append(filter_.providerTenantId)
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        sql = f"SELECT * FROM apis{where} ORDER BY created_at DESC LIMIT ? OFFSET ?;"
        params.extend([filter_.limit, filter_.offset])
        cur = self._conn.conn.execute(sql, params)
        rows = cur.fetchall()
        result = [self._row_to_api(r) for r in rows]
        # tag / keyword 过滤在 Python 中做（避免 JSON 查询复杂度）
        if filter_.tag:
            result = [a for a in result if filter_.tag in a.tags]
        if filter_.keyword:
            kw = filter_.keyword.lower()
            result = [
                a
                for a in result
                if kw in a.name.lower() or kw in (a.description or "").lower() or any(kw in t.lower() for t in a.tags)
            ]
        return result

    async def delete_api(self, api_id: str) -> None:
        cur = self._conn.conn.execute("SELECT id FROM apis WHERE id = ?;", (api_id,))
        if cur.fetchone() is None:
            raise APINotFoundError(api_id)
        await asyncio.to_thread(self._delete_api_sync, api_id)

    def _delete_api_sync(self, api_id: str) -> None:
        conn = self._conn.conn
        with self._write_lock:
            try:
                conn.execute("BEGIN;")
                conn.execute("DELETE FROM subscriptions WHERE api_id = ?;", (api_id,))
                conn.execute("DELETE FROM apis WHERE id = ?;", (api_id,))
                conn.execute("COMMIT;")
            except sqlite3.Error:
                try:
                    conn.execute("ROLLBACK;")
                except sqlite3.Error:
                    pass
                raise

    # ---------- Subscription ----------

    async def save_subscription(self, sub: APISubscription) -> APISubscription:
        # 检查重复订阅（仅当状态非 REJECTED/REVOKED 时）
        cur = self._conn.conn.execute(
            """
            SELECT id FROM subscriptions
            WHERE api_id = ? AND subscriber_id = ?
              AND status IN ('pending', 'active', 'approved')
              AND id != ?;
            """,
            (sub.apiId, sub.subscriberId, sub.id),
        )
        if cur.fetchone() is not None:
            raise SubscriptionAlreadyExistsError(sub.apiId, sub.subscriberId)
        self._conn.conn.execute(
            """
            INSERT INTO subscriptions (
                id, api_id, subscriber_id, subscriber_tenant_id,
                provider_tenant_id, purpose, quota_expect, status,
                access_key, secret_key, approve_reason, approved_by,
                granted_quota, call_count, error_count, last_called_at,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                api_id = excluded.api_id,
                subscriber_id = excluded.subscriber_id,
                subscriber_tenant_id = excluded.subscriber_tenant_id,
                provider_tenant_id = excluded.provider_tenant_id,
                purpose = excluded.purpose,
                quota_expect = excluded.quota_expect,
                status = excluded.status,
                access_key = excluded.access_key,
                secret_key = excluded.secret_key,
                approve_reason = excluded.approve_reason,
                approved_by = excluded.approved_by,
                granted_quota = excluded.granted_quota,
                call_count = excluded.call_count,
                error_count = excluded.error_count,
                last_called_at = excluded.last_called_at,
                updated_at = excluded.updated_at;
            """,
            (
                sub.id,
                sub.apiId,
                sub.subscriberId,
                sub.subscriberTenantId,
                sub.providerTenantId,
                sub.purpose,
                sub.quotaExpect,
                sub.status.value,
                sub.accessKey,
                sub.secretKey,
                sub.approveReason,
                sub.approvedBy,
                sub.grantedQuota,
                sub.callCount,
                sub.errorCount,
                sub.lastCalledAt,
                sub.createdAt.isoformat(),
                sub.updatedAt.isoformat(),
            ),
        )
        return sub

    async def get_subscription(self, subscription_id: str) -> APISubscription:
        cur = self._conn.conn.execute("SELECT * FROM subscriptions WHERE id = ?;", (subscription_id,))
        row = cur.fetchone()
        if row is None:
            raise SubscriptionNotFoundError(subscription_id)
        return self._row_to_sub(row)

    async def list_subscriptions(self, filter_: SubscriptionFilter) -> list[APISubscription]:
        clauses: list[str] = []
        params: list[Any] = []
        if filter_.apiId:
            clauses.append("api_id = ?")
            params.append(filter_.apiId)
        if filter_.subscriberId:
            clauses.append("subscriber_id = ?")
            params.append(filter_.subscriberId)
        if filter_.subscriberTenantId:
            clauses.append("subscriber_tenant_id = ?")
            params.append(filter_.subscriberTenantId)
        if filter_.status:
            clauses.append("status = ?")
            params.append(filter_.status.value)
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        sql = f"SELECT * FROM subscriptions{where} ORDER BY created_at DESC LIMIT ? OFFSET ?;"
        params.extend([filter_.limit, filter_.offset])
        cur = self._conn.conn.execute(sql, params)
        return [self._row_to_sub(r) for r in cur.fetchall()]

    async def find_subscription_by_key(self, access_key: str) -> APISubscription | None:
        cur = self._conn.conn.execute("SELECT * FROM subscriptions WHERE access_key = ?;", (access_key,))
        row = cur.fetchone()
        return self._row_to_sub(row) if row else None

    # ---------- Metrics ----------

    async def save_metric(self, metric: CallMetric) -> CallMetric:
        return await asyncio.to_thread(self._save_metric_sync, metric)

    def _save_metric_sync(self, metric: CallMetric) -> CallMetric:
        """单次调用计量的完整落库与聚合（同步原子段，由实例写锁串行化）.

        连接为 autocommit 模式（isolation_level=None），单条语句即独立提交；
        此处显式 BEGIN IMMEDIATE 开启写事务（避免读后写升级死锁），
        保证 INSERT + 2 条 UPDATE 聚合的原子性：任一步失败整体 ROLLBACK。
        仅 COMMIT 成功后才写入 _metrics_buffer，确保内存视图与 DB 状态一致。
        """
        with self._write_lock:
            conn = self._conn.conn
            try:
                conn.execute("BEGIN IMMEDIATE;")
                conn.execute(
                    """
                    INSERT INTO call_metrics (
                        call_id, api_id, api_version, subscription_id,
                        consumer_tenant_id, provider_tenant_id, timestamp,
                        latency_ms, request_bytes, response_bytes, status_code,
                        cost_strategy, cost_amount, error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                    """,
                    (
                        metric.callId,
                        metric.apiId,
                        metric.apiVersion,
                        metric.subscriptionId,
                        metric.consumerTenantId,
                        metric.providerTenantId,
                        metric.timestamp.isoformat(),
                        metric.latencyMs,
                        metric.requestBytes,
                        metric.responseBytes,
                        metric.statusCode,
                        metric.costStrategy.value,
                        metric.costAmount,
                        metric.errorMessage,
                    ),
                )
                error_delta = 1 if metric.statusCode >= 400 else 0
                traffic = metric.requestBytes + metric.responseBytes
                updated_at = utc_now().isoformat()
                # API 聚合统计（SQL 侧自增，避免读改写竞态）
                conn.execute(
                    """
                    UPDATE apis SET
                        call_count = call_count + 1,
                        error_count = error_count + ?,
                        total_latency_ms = total_latency_ms + ?,
                        total_traffic_bytes = total_traffic_bytes + ?,
                        updated_at = ?
                    WHERE id = ?;
                    """,
                    (error_delta, metric.latencyMs, traffic, updated_at, metric.apiId),
                )
                # 订阅统计
                conn.execute(
                    """
                    UPDATE subscriptions SET
                        call_count = call_count + 1,
                        error_count = error_count + ?,
                        last_called_at = ?,
                        updated_at = ?
                    WHERE id = ?;
                    """,
                    (error_delta, metric.timestamp.isoformat(), updated_at, metric.subscriptionId),
                )
                conn.execute("COMMIT;")
            except BaseException:
                # 任一步失败必须显式终止事务，防止 autocommit 连接残留悬挂事务；
                # ROLLBACK 自身失败（如连接已损坏）不应掩盖原始异常
                try:
                    conn.execute("ROLLBACK;")
                except sqlite3.Error:
                    pass
                raise
            self._metrics_buffer.append(metric)
        return metric

    async def list_metrics(
        self,
        api_id: str,
        range_str: str = "7d",
        consumer_tenant_id: str | None = None,
    ) -> list[CallMetric]:
        now = utc_now()
        match = re.fullmatch(r"(\d+)([smhdw])", range_str)
        if match:
            seconds = int(match.group(1)) * _RANGE_UNIT_SECONDS[match.group(2)]
            since = now - timedelta(seconds=seconds)
        else:
            since = now - timedelta(days=7)
        return [
            m
            for m in self._metrics_buffer
            if m.apiId == api_id
            and m.timestamp >= since
            and (consumer_tenant_id is None or m.consumerTenantId == consumer_tenant_id)
        ]

    async def clear(self) -> None:
        """清空所有数据（测试用）."""
        self._conn.conn.execute("DELETE FROM call_metrics;")
        self._conn.conn.execute("DELETE FROM subscriptions;")
        self._conn.conn.execute("DELETE FROM apis;")
        self._metrics_buffer.clear()

    # ---------- 行转换 ----------

    @staticmethod
    def _row_to_api(row) -> APIDefinition:
        return APIDefinition(
            id=row["id"],
            name=row["name"],
            version=row["version"],
            description=row["description"],
            category=row["category"],
            tags=json.loads(row["tags_json"]),
            method=HttpMethod(row["method"]),
            path=row["path"],
            params=[APIParam.model_validate(p) for p in json.loads(row["params_json"])],
            responses=[APIResponse.model_validate(r) for r in json.loads(row["responses_json"])],
            authType=AuthType(row["auth_type"]),
            upstream=APIUpstream.model_validate_json(row["upstream_json"]),
            sla=SLALevel(row["sla"]),
            costStrategy=CostStrategy(row["cost_strategy"]),
            costUnitPrice=row["cost_unit_price"],
            monthlyQuota=row["monthly_quota"],
            status=APIStatus(row["status"]),
            providerTenantId=row["provider_tenant_id"],
            callCount=row["call_count"],
            errorCount=row["error_count"],
            totalLatencyMs=row["total_latency_ms"],
            totalTrafficBytes=row["total_traffic_bytes"],
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )

    @staticmethod
    def _row_to_sub(row) -> APISubscription:
        return APISubscription(
            id=row["id"],
            apiId=row["api_id"],
            subscriberId=row["subscriber_id"],
            subscriberTenantId=row["subscriber_tenant_id"],
            providerTenantId=row["provider_tenant_id"],
            purpose=row["purpose"],
            quotaExpect=row["quota_expect"],
            status=SubscriptionStatus(row["status"]),
            accessKey=row["access_key"],
            secretKey=row["secret_key"],
            approveReason=row["approve_reason"],
            approvedBy=row["approved_by"],
            grantedQuota=row["granted_quota"],
            callCount=row["call_count"],
            errorCount=row["error_count"],
            lastCalledAt=row["last_called_at"],
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )

    @staticmethod
    def _row_to_metric(row) -> CallMetric:
        return CallMetric(
            callId=row["call_id"],
            apiId=row["api_id"],
            apiVersion=row["api_version"],
            subscriptionId=row["subscription_id"],
            consumerTenantId=row["consumer_tenant_id"],
            providerTenantId=row["provider_tenant_id"],
            timestamp=row["timestamp"],
            latencyMs=row["latency_ms"],
            requestBytes=row["request_bytes"],
            responseBytes=row["response_bytes"],
            statusCode=row["status_code"],
            costStrategy=CostStrategy(row["cost_strategy"]),
            costAmount=row["cost_amount"],
            errorMessage=row["error_message"],
        )


def generate_ak_sk() -> tuple[str, str]:
    """生成 AK/SK."""
    ak = "AK" + secrets.token_hex(12)
    sk = "SK" + secrets.token_hex(24)
    return ak, sk
