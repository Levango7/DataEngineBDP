"""会话持久化层：SQLite 写穿透 + 启动回充.

内存 OrderedDict 仍是运行期权威（LRU+TTL 语义不变）；本层为可选持久层：
Settings.sessionDbPath 非空时启用，为空串时 ServiceRegistry 不构造本层
（行为同改造前）。

时钟分层：内存层用 time.monotonic（不受挂起/跳变影响）；本层 last_access
用 time.time（wall-clock），TTL 判定在本层完成，跨进程可比。
"""

from __future__ import annotations

import os
import sqlite3
import threading
import time
from typing import List, Tuple


class SqliteSessionStore:
    """SQLite 会话持久化（写穿透 + 启动回充）.

    表 sessions(tenant_id, session_id, state_json, last_access)：
    - 键为 (tenant_id, session_id)，与内存层一致（租户隔离）；
    - state_json 为 DialogueState.model_dump_json() 的序列化结果，本层不做模型解释。
    """

    def __init__(self, db_path: str, ttl_seconds: float) -> None:
        self._ttl = ttl_seconds
        self._lock = threading.Lock()
        parent = os.path.dirname(db_path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        self._db = sqlite3.connect(db_path, check_same_thread=False)
        self._db.execute(
            "CREATE TABLE IF NOT EXISTS sessions ("
            "tenant_id TEXT NOT NULL, session_id TEXT NOT NULL, "
            "state_json TEXT NOT NULL, last_access REAL NOT NULL, "
            "PRIMARY KEY (tenant_id, session_id))"
        )
        self._db.commit()
        self._sweep()

    def _now(self) -> float:
        return time.time()

    def _sweep(self) -> None:
        with self._lock:
            self._db.execute("DELETE FROM sessions WHERE last_access < ?", (self._now() - self._ttl,))
            self._db.commit()

    def save(self, tenantId: str, sessionId: str, stateJson: str) -> None:
        with self._lock:
            self._db.execute(
                "INSERT INTO sessions (tenant_id, session_id, state_json, last_access) "
                "VALUES (?, ?, ?, ?) "
                "ON CONFLICT(tenant_id, session_id) DO UPDATE SET "
                "state_json = excluded.state_json, last_access = excluded.last_access",
                (tenantId, sessionId, stateJson, self._now()),
            )
            self._db.commit()

    def touch(self, tenantId: str, sessionId: str) -> None:
        with self._lock:
            self._db.execute(
                "UPDATE sessions SET last_access = ? WHERE tenant_id = ? AND session_id = ?",
                (self._now(), tenantId, sessionId),
            )
            self._db.commit()

    def delete(self, tenantId: str, sessionId: str) -> None:
        with self._lock:
            self._db.execute("DELETE FROM sessions WHERE tenant_id = ? AND session_id = ?", (tenantId, sessionId))
            self._db.commit()

    def loadAll(self) -> List[Tuple[str, str, str]]:
        self._sweep()
        with self._lock:
            rows = self._db.execute(
                "SELECT tenant_id, session_id, state_json FROM sessions ORDER BY last_access ASC"
            ).fetchall()
        return [(r[0], r[1], r[2]) for r in rows]
