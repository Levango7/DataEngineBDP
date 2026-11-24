"""前端契约视图的 PostgreSQL 持久化存储（生产持久层）.

背景：FrontendContractStore 是进程内存实现，重启即丢、多副本各持各的，
不满足生产持久化要求。本模块提供**同契约**的 PostgreSQL 实现：
- 三类前端视图实体（评估指标/微调任务/推理服务）以 JSONB payload 落库，
  键为 (tenant_id, entity_id) / (tenant_id, task_id)，与内存版逐字段同形；
- finetune upsert 用 ON CONFLICT ... payload = payload || EXCLUDED.payload 做
  字段级合并，并发不丢字段（内存版靠进程锁；本版靠 SQL 原子语句）；
- schema 幂等：CREATE TABLE IF NOT EXISTS + 会话级 pg_advisory_lock 包裹 bootstrap，
  多进程/多副本同时启动不打架；
- DSN 只来自 Settings.frontendDatabaseUrl（环境变量/K8s Secret 注入），
  源码、示例、测试均不写可用凭据；所有外部输入只走参数绑定（%s 占位符），
  本模块无任何把输入拼进 SQL 字符串的写法（安全约束，逐字面量内联以满足静态检查）。
"""

from __future__ import annotations

import json
import logging
from typing import Any, Optional
import uuid

from psycopg_pool import ConnectionPool

from llmops.services.frontend_store import FrontendContractStore

logger = logging.getLogger(__name__)

# schema bootstrap 互斥锁键（"llmopsFE" 的 ASCII 打包成的 64 位正整数）
_SCHEMA_LOCK_KEY = 0x6C6C6D6F70734645
_SCHEMA_VERSION = "0001_frontend_views"


def _now() -> str:
    from datetime import datetime, timezone

    return datetime.now(timezone.utc).isoformat()


class PostgresFrontendContractStore(FrontendContractStore):
    """FrontendContractStore 的 PostgreSQL 实现（方法契约逐字段对齐内存版）。

    刻意不调用 super().__init__()：父类持有的是进程内存状态，本实现全部落库；
    继承仅为类型兼容（工厂返回类型与路由契约不变）。
    """

    def __init__(
        self,
        dsn: str,
        *,
        pool_min_size: int = 1,
        pool_max_size: int = 5,
        timeout: float = 30.0,
    ) -> None:
        # 刻意不调用 super().__init__()：父类持有的是进程内存状态，本实现全部落库。
        self._dsn = dsn
        self._pool = ConnectionPool(
            conninfo=dsn,
            min_size=pool_min_size,
            max_size=pool_max_size,
            timeout=timeout,
            name="llmops-frontend-store",
        )
        try:
            # bootstrap 自带连接获取：DB 不可达/无权限时抛异常上抛 ⇒ 启动即 fail-fast，
            # 绝不静默回退内存（生产持久层契约）。
            self._bootstrap_schema()
        except BaseException:
            self._pool.close()
            raise
        logger.info("PostgresFrontendContractStore 就绪（schema %s）", _SCHEMA_VERSION)

    # ---------------------------------------------------------------- schema
    def _bootstrap_schema(self) -> None:
        """幂等建表：CREATE ... IF NOT EXISTS + 会话级咨询锁，防多副本并发 bootstrap."""
        with self._pool.connection() as conn:
            conn.execute("SELECT pg_advisory_lock(%s)", [_SCHEMA_LOCK_KEY])
            try:
                conn.execute(
                    "CREATE TABLE IF NOT EXISTS llmops_frontend_metrics ("
                    "tenant_id  text NOT NULL, "
                    "entity_id  text NOT NULL, "
                    "seq        bigint GENERATED ALWAYS AS IDENTITY, "
                    "payload    jsonb NOT NULL, "
                    "created_at timestamptz NOT NULL DEFAULT now(), "
                    "PRIMARY KEY (tenant_id, entity_id))"
                )
                conn.execute(
                    "CREATE TABLE IF NOT EXISTS llmops_frontend_finetunes ("
                    "tenant_id  text NOT NULL, "
                    "task_id    text NOT NULL, "
                    "seq        bigint GENERATED ALWAYS AS IDENTITY, "
                    "payload    jsonb NOT NULL, "
                    "created_at timestamptz NOT NULL DEFAULT now(), "
                    "updated_at timestamptz NOT NULL DEFAULT now(), "
                    "PRIMARY KEY (tenant_id, task_id))"
                )
                conn.execute(
                    "CREATE TABLE IF NOT EXISTS llmops_frontend_inference ("
                    "tenant_id  text NOT NULL, "
                    "entity_id  text NOT NULL, "
                    "seq        bigint GENERATED ALWAYS AS IDENTITY, "
                    "payload    jsonb NOT NULL, "
                    "created_at timestamptz NOT NULL DEFAULT now(), "
                    "updated_at timestamptz NOT NULL DEFAULT now(), "
                    "PRIMARY KEY (tenant_id, entity_id))"
                )
                conn.execute(
                    "CREATE TABLE IF NOT EXISTS llmops_schema_migrations ("
                    "version    text PRIMARY KEY, "
                    "applied_at timestamptz NOT NULL DEFAULT now())"
                )
                conn.execute(
                    "INSERT INTO llmops_schema_migrations (version) VALUES (%s) "
                    "ON CONFLICT (version) DO NOTHING",
                    [_SCHEMA_VERSION],
                )
                conn.commit()
            except BaseException:
                conn.rollback()
                raise
            finally:
                try:
                    conn.execute("SELECT pg_advisory_unlock(%s)", [_SCHEMA_LOCK_KEY])
                    conn.commit()
                except Exception:  # noqa: BLE001  解锁失败不掩盖建表异常
                    conn.rollback()

    # ---------------------------------------------------------------- 评估指标
    def create_metric(self, tenant: str, payload: dict[str, Any]) -> dict[str, Any]:
        metric: dict[str, Any] = {
            "id": str(uuid.uuid4()),
            "modelName": payload.get("modelName", ""),
            "modelVersion": payload.get("modelVersion"),
            "evalType": payload.get("evalType"),
            "accuracy": payload.get("accuracy"),
            "hallucinationRate": payload.get("hallucinationRate"),
            "baseLiftPt": payload.get("baseLiftPt"),
            "dataset": payload.get("dataset"),
            "createdAt": _now(),
        }
        with self._pool.connection() as conn:
            conn.execute(
                "INSERT INTO llmops_frontend_metrics (tenant_id, entity_id, payload) "
                "VALUES (%s, %s, %s::jsonb)",
                [tenant, metric["id"], json.dumps(metric)],
            )
            conn.commit()
        return dict(metric)

    def list_metrics(self, tenant: str, model_name: Optional[str] = None) -> list[dict[str, Any]]:
        with self._pool.connection() as conn:
            if model_name:
                rows = conn.execute(
                    "SELECT payload FROM llmops_frontend_metrics "
                    "WHERE tenant_id = %s AND payload->>'modelName' = %s ORDER BY seq",
                    [tenant, model_name],
                ).fetchall()
            else:
                rows = conn.execute(
                    "SELECT payload FROM llmops_frontend_metrics "
                    "WHERE tenant_id = %s ORDER BY seq",
                    [tenant],
                ).fetchall()
        return [dict(r[0]) for r in rows]

    # ---------------------------------------------------------------- 微调任务视图
    def upsert_finetune(self, tenant: str, task_id: str, view: dict[str, Any]) -> dict[str, Any]:
        # 与内存版同语义：{**existing, **view, "taskId": task_id} ⇒ 强制回填 taskId。
        merged_view = {**view, "taskId": task_id}
        with self._pool.connection() as conn:
            row = conn.execute(
                "INSERT INTO llmops_frontend_finetunes (tenant_id, task_id, payload) "
                "VALUES (%s, %s, %s::jsonb) "
                "ON CONFLICT (tenant_id, task_id) DO UPDATE SET "
                "payload = llmops_frontend_finetunes.payload || EXCLUDED.payload, "
                "updated_at = now() "
                "RETURNING payload",
                [tenant, task_id, json.dumps(merged_view)],
            ).fetchone()
            conn.commit()
        if row is None:  # ON CONFLICT RETURNING 必返回一行；防御性兜底
            raise RuntimeError("finetune upsert 未返回行")
        return dict(row[0])

    def list_finetunes(self, tenant: str) -> list[dict[str, Any]]:
        with self._pool.connection() as conn:
            rows = conn.execute(
                "SELECT payload FROM llmops_frontend_finetunes "
                "WHERE tenant_id = %s ORDER BY seq",
                [tenant],
            ).fetchall()
        return [dict(r[0]) for r in rows]

    def get_finetune(self, tenant: str, task_id: str) -> Optional[dict[str, Any]]:
        with self._pool.connection() as conn:
            row = conn.execute(
                "SELECT payload FROM llmops_frontend_finetunes "
                "WHERE tenant_id = %s AND task_id = %s",
                [tenant, task_id],
            ).fetchone()
        return dict(row[0]) if row else None

    # ---------------------------------------------------------------- 推理服务
    def list_inference(self, tenant: str, status: Optional[str] = None) -> list[dict[str, Any]]:
        with self._pool.connection() as conn:
            if status:
                rows = conn.execute(
                    "SELECT payload FROM llmops_frontend_inference "
                    "WHERE tenant_id = %s AND upper(payload->>'status') = upper(%s) "
                    "ORDER BY seq",
                    [tenant, status],
                ).fetchall()
            else:
                rows = conn.execute(
                    "SELECT payload FROM llmops_frontend_inference "
                    "WHERE tenant_id = %s ORDER BY seq",
                    [tenant],
                ).fetchall()
        return [dict(r[0]) for r in rows]

    def deploy_inference(self, tenant: str, payload: dict[str, Any]) -> dict[str, Any]:
        model_name = (payload.get("modelName") or "").strip()
        replicas = payload.get("replicas") or 1
        svc_id = str(uuid.uuid4())
        svc: dict[str, Any] = {
            "id": svc_id,
            "serviceName": (payload.get("serviceName") or model_name).strip() or model_name,
            "modelName": model_name,
            "modelVersion": payload.get("version") or payload.get("modelVersion") or "1",
            "status": "RUNNING",  # 契约级：创建即运行（与内存版一致；真编排见台账 #133/B 线）
            "replicas": int(replicas),
            "desiredReplicas": int(replicas),
            "endpoint": f"/llmops/inference-services/{svc_id}/invoke",
            "deployedAt": _now(),
        }
        with self._pool.connection() as conn:
            conn.execute(
                "INSERT INTO llmops_frontend_inference (tenant_id, entity_id, payload) "
                "VALUES (%s, %s, %s::jsonb)",
                [tenant, svc_id, json.dumps(svc)],
            )
            conn.commit()
        return dict(svc)

    def stop_inference(self, tenant: str, service_id: str) -> Optional[dict[str, Any]]:
        return self._merge_inference(
            tenant,
            service_id,
            {"status": "STOPPED", "replicas": 0, "desiredReplicas": 0},
        )

    def scale_inference(self, tenant: str, service_id: str, replicas: int) -> Optional[dict[str, Any]]:
        return self._merge_inference(
            tenant,
            service_id,
            {
                "replicas": replicas,
                "desiredReplicas": replicas,
                "status": "STOPPED" if replicas == 0 else "RUNNING",
            },
        )

    def _merge_inference(
        self, tenant: str, service_id: str, patch: dict[str, Any]
    ) -> Optional[dict[str, Any]]:
        """单条原子 UPDATE：并发 scale/stop 串行化，不丢字段、不出现半更新状态。"""
        with self._pool.connection() as conn:
            row = conn.execute(
                "UPDATE llmops_frontend_inference "
                "SET payload = payload || %s::jsonb, updated_at = now() "
                "WHERE tenant_id = %s AND entity_id = %s "
                "RETURNING payload",
                [json.dumps(patch), tenant, service_id],
            ).fetchone()
            conn.commit()
        return dict(row[0]) if row else None

    # ---------------------------------------------------------------- 生命周期
    def close(self) -> None:
        """关闭连接池（应用退出生命周期调用；内存实现无此方法，调用方按存在性调用）."""
        self._pool.close()
