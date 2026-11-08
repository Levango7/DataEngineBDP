"""会话存储 LRU/TTL 与租户隔离回归测试.

覆盖：
    - 容量上限 LRU 驱逐（OrderedDict，max 500 默认）
    - 30 分钟 TTL 惰性清理（创建/访问时顺带清扫过期）
    - 键为 (tenantId, sessionId)，同 sessionId 跨租户互不可见
    - SQLite 持久化（B2）：写穿透 + 启动回充 + TTL 联动 + 坏行跳过
"""

from __future__ import annotations

import sqlite3

from app import build_services
from config.settings import Settings, reset_settings
from models import DialogueState, DialogueTurn


def _registry(**overrides) -> object:
    reset_settings()
    return build_services(Settings(llmMode="mock", **overrides))


def _state(sid: str) -> DialogueState:
    return DialogueState(sessionId=sid)


class TestSessionCapacityLru:
    def test_default_capacity_and_ttl(self) -> None:
        reg = _registry()
        assert reg.settings.maxSessions == 500
        assert reg.settings.sessionTtlSeconds == 1800.0

    def test_lru_evicts_oldest_beyond_capacity(self) -> None:
        reg = _registry(maxSessions=2)
        reg.saveSession(_state("s1"), tenantId="t1")
        reg.saveSession(_state("s2"), tenantId="t1")
        # 访问 s1 使其变为最近使用
        assert reg.getSession("t1", "s1") is not None
        reg.saveSession(_state("s3"), tenantId="t1")
        assert len(reg._sessions) == 2
        assert reg.getSession("t1", "s1") is not None
        assert reg.getSession("t1", "s2") is None
        assert reg.getSession("t1", "s3") is not None

    def test_eviction_per_tenant_key_not_session_id(self) -> None:
        reg = _registry(maxSessions=2)
        reg.saveSession(_state("shared"), tenantId="t1")
        reg.saveSession(_state("other"), tenantId="t1")
        reg.saveSession(_state("shared"), tenantId="t2")
        assert len(reg._sessions) == 2
        assert reg.getSession("t1", "shared") is None
        assert reg.getSession("t2", "shared") is not None
        assert reg.getSession("t1", "other") is not None


class TestSessionTtlLazyExpiry:
    def test_expired_entry_swept_on_access(self) -> None:
        reg = _registry(sessionTtlSeconds=1800)
        reg.saveSession(_state("s1"), tenantId="t1")
        key = ("t1", "s1")
        state, ts = reg._sessions[key]
        reg._sessions[key] = (state, ts - 3600)
        assert reg.getSession("t1", "s1") is None
        assert key not in reg._sessions

    def test_fresh_entry_survives_access_after_other_expired(self) -> None:
        reg = _registry(sessionTtlSeconds=1800)
        reg.saveSession(_state("old"), tenantId="t1")
        reg.saveSession(_state("fresh"), tenantId="t1")
        staleKey = ("t1", "old")
        state, ts = reg._sessions[staleKey]
        reg._sessions[staleKey] = (state, ts - 3600)
        assert reg.getSession("t1", "fresh") is not None
        assert ("t1", "old") not in reg._sessions

    def test_access_refreshes_timestamp(self) -> None:
        import time as _time

        reg = _registry(sessionTtlSeconds=1800)
        reg.saveSession(_state("s1"), tenantId="t1")
        key = ("t1", "s1")
        _, before = reg._sessions[key]
        _time.sleep(0.01)
        reg.getSession("t1", "s1")
        _, after = reg._sessions[key]
        assert after > before


class TestTenantScopedKeys:
    def test_same_session_id_isolated_across_tenants(self) -> None:
        reg = _registry()
        reg.saveSession(_state("shared"), tenantId="t1")
        reg.saveSession(_state("shared"), tenantId="t2")
        a = reg.getSession("t1", "shared")
        b = reg.getSession("t2", "shared")
        assert a is not None and b is not None and a is not b

    def test_unknown_tenant_gets_none(self) -> None:
        reg = _registry()
        reg.saveSession(_state("s9"), tenantId="t1")
        assert reg.getSession("intruder", "s9") is None


class TestSessionPersistence:
    def test_default_disabled_no_store(self) -> None:
        reg = _registry()
        assert reg.settings.sessionDbPath == ""
        assert reg._sessionStore is None

    def test_restart_reloads_full_state(self, tmp_path) -> None:
        db = str(tmp_path / "sessions.db")
        reg1 = _registry(sessionDbPath=db)
        state = DialogueState(
            sessionId="s1",
            turns=[DialogueTurn(role="user", content="查销售额")],
            database="default",
            clarified=False,
            turnCount=1,
        )
        reg1.saveSession(state, tenantId="t1")

        reg2 = _registry(sessionDbPath=db)  # 模拟重启
        got = reg2.getSession("t1", "s1")
        assert got is not None
        assert got.sessionId == "s1"
        assert got.turns[0].role == "user" and got.turns[0].content == "查销售额"
        assert got.database == "default" and got.turnCount == 1 and got.clarified is False
        assert got is not state  # 回充的是反序列化新对象

    def test_expired_row_not_resurrected(self, tmp_path) -> None:
        db = str(tmp_path / "sessions.db")
        reg1 = _registry(sessionDbPath=db, sessionTtlSeconds=60)
        reg1.saveSession(_state("s1"), tenantId="t1")
        con = sqlite3.connect(db)
        con.execute("UPDATE sessions SET last_access = last_access - 120")
        con.commit()
        con.close()
        reg2 = _registry(sessionDbPath=db, sessionTtlSeconds=60)
        assert reg2.getSession("t1", "s1") is None

    def test_tenant_isolation_holds_across_restart(self, tmp_path) -> None:
        db = str(tmp_path / "sessions.db")
        reg1 = _registry(sessionDbPath=db)
        reg1.saveSession(_state("shared"), tenantId="t1")
        reg2 = _registry(sessionDbPath=db)
        assert reg2.getSession("t2", "shared") is None
        assert reg2.getSession("t1", "shared") is not None

    def test_corrupted_row_skipped_not_fatal(self, tmp_path) -> None:
        db = str(tmp_path / "sessions.db")
        reg1 = _registry(sessionDbPath=db)
        reg1.saveSession(_state("s1"), tenantId="t1")
        con = sqlite3.connect(db)
        con.execute("UPDATE sessions SET state_json = '{not json'")
        con.commit()
        con.close()
        reg2 = _registry(sessionDbPath=db)
        assert reg2.getSession("t1", "s1") is None
