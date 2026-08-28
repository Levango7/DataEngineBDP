"""NebulaGraph 同步 SDK 经 asyncio.to_thread 卸载的事件循环回归测试.

沿 llmops/tests/test_mlflow_store.py 同款范式：
fake session execute 注入 sleep(0.5)，并发健康探针须在 <0.45s 内完成；
同时验证 to_thread 下实例级 _executeLock 仍完全串行化共享 session。
"""

from __future__ import annotations

import asyncio
import time

import pytest
from test_ngql_injection import _OverlappingSession, bare_store

SDK_DELAY_SECONDS = 0.5
PROBE_TIME_BUDGET_SECONDS = 0.45


class _SlowSession:
    """同步假 session：execute 可注入延迟，模拟阻塞的 nebula3 SDK."""

    def __init__(self, delay: float = 0.0) -> None:
        self.delay = delay
        self.calls = 0

    def execute(self, nql: str):
        self.calls += 1
        if self.delay > 0:
            time.sleep(self.delay)

        class _Resp:
            def is_succeeded(self) -> bool:
                return True

            def keys(self) -> list:
                return []

            def rows(self) -> list:
                return []

        return _Resp()


async def _run_with_probe(op) -> tuple[float, float]:
    start = time.perf_counter()

    async def probe() -> float:
        await asyncio.sleep(0.05)
        return time.perf_counter() - start

    results = await asyncio.gather(op(), probe())
    total_elapsed = time.perf_counter() - start
    return results[1], total_elapsed


def _assert_probe_responsive(case_name: str, probe_elapsed: float, total_elapsed: float) -> None:
    assert (
        probe_elapsed < PROBE_TIME_BUDGET_SECONDS
    ), f"{case_name}: 并发探测耗时 {probe_elapsed:.3f}s，事件循环疑似被阻塞"
    assert total_elapsed >= SDK_DELAY_SECONDS, f"{case_name}: 总耗时 {total_elapsed:.3f}s，慢操作未真实发生"


@pytest.mark.asyncio
async def test_query_offloads_slow_sdk_keeps_loop_responsive() -> None:
    store = bare_store()
    session = _SlowSession(SDK_DELAY_SECONDS)
    store._session = session

    probe_elapsed, total_elapsed = await _run_with_probe(lambda: store.query("kg1", "MATCH (v) RETURN v LIMIT 1"))
    _assert_probe_responsive("query", probe_elapsed, total_elapsed)
    assert session.calls == 1


@pytest.mark.asyncio
async def test_insert_vertex_offloads_slow_sdk() -> None:
    store = bare_store()
    session = _SlowSession(SDK_DELAY_SECONDS)
    store._session = session

    probe_elapsed, total_elapsed = await _run_with_probe(
        lambda: store.insert_vertex("kg1", "Person", "v1", {"name": "n"})
    )
    _assert_probe_responsive("insert_vertex", probe_elapsed, total_elapsed)


@pytest.mark.asyncio
async def test_list_spaces_offloads_slow_sdk() -> None:
    store = bare_store()
    store._session = _SlowSession(SDK_DELAY_SECONDS)

    probe_elapsed, total_elapsed = await _run_with_probe(store.list_spaces)
    _assert_probe_responsive("list_spaces", probe_elapsed, total_elapsed)


@pytest.mark.asyncio
async def test_concurrent_async_executes_remain_serialized() -> None:
    """to_thread 卸载后多线程并发 execute，实例级锁仍保证零重叠."""
    store = bare_store()
    session = _OverlappingSession()
    store._session = session

    await asyncio.gather(*[store.insert_vertex("kg1", "Person", f"v{i}", {"name": "n"}) for i in range(6)])
    assert session.calls == 6
    assert session.maxActive == 1
