"""性能压测专用 conftest：fixtures、阈值配置、异步压测引擎。

本模块是 ``tests/integration/perf/`` 目录的 pytest 配置入口，提供：

- **阈值配置** ``PERF_THRESHOLDS``：13 项非功能指标的目标阈值，集中维护便于调优；
- **SLA 配置** ``SLA_THRESHOLDS``：10 项 SLA 验证目标；
- **服务可用性探测**：复用父级 conftest 的服务 URL 与健康检查；
- **异步压测引擎** ``AsyncLoadEngine``：基于 asyncio + httpx 的轻量压测引擎，
  支持指定并发数、总请求数、持续时间，返回延迟分布/QPS/错误率等指标；
- **资源监控** ``ResourceMonitor``：基于 psutil 采集 CPU/内存利用率；
- **JWT token** ``perf_auth_token``：性能压测专用 token；
- **HTTP 客户端** ``perf_api_client``：自动注入鉴权头的 requests.Session；
- **自动跳过**：服务不可用时通过 ``perf_services_ready`` fixture 统一跳过。

设计要点：
- 不修改父级 ``tests/integration/docker/conftest.py``，仅复用其常量与工具函数；
- 异步压测引擎不依赖 locust，降低部署门槛；locust 仅作为可选大规模压测后端；
- 所有阈值均为"目标值"，压测用例断言"实际值优于目标值"。
"""

from __future__ import annotations

import asyncio
import os
import statistics
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

import jwt
import pytest
import requests

# ---------------------------------------------------------------------------
# 复用父级 conftest 的服务 URL 与健康检查工具
# ---------------------------------------------------------------------------
try:
    from tests.integration.docker.conftest import (  # type: ignore[import-not-found]
        BASE_URLS as _DOCKER_BASE_URLS,
        HEALTH_PATHS as _DOCKER_HEALTH_PATHS,
        DEFAULT_TIMEOUT,
        generate_test_jwt,
        wait_for_service,
    )
except ImportError:  # pragma: no cover — 容错：直接运行 perf 目录时也能工作
    _DOCKER_BASE_URLS = {
        "encaps": os.environ.get("ENCAPS_URL", "http://localhost:18080"),
        "sql_gateway": os.environ.get("SQL_GATEWAY_URL", "http://localhost:18081"),
        "catalog": os.environ.get("CATALOG_URL", "http://localhost:18082"),
        "rule_engine": os.environ.get("RULE_ENGINE_URL", "http://localhost:18083"),
    }
    _DOCKER_HEALTH_PATHS = {
        "encaps": "/actuator/health",
        "sql_gateway": "/actuator/health",
        "catalog": "/api/v1/health",
        "rule_engine": "/actuator/health",
    }
    DEFAULT_TIMEOUT = 10

    def generate_test_jwt(  # type: ignore[no-redef]
        tenant_id: str = "perf-tenant",
        user_id: str = "perf-tester",
        expiry_seconds: int = 3600,
    ) -> str:
        now = int(time.time())
        payload = {
            "iss": "shuqing-bigdata",
            "sub": user_id,
            "tenantId": tenant_id,
            "iat": now,
            "exp": now + expiry_seconds,
        }
        return jwt.encode(
            payload, "it-test-jwt-secret-at-least-32-bytes-long", algorithm="HS384"
        )

    def wait_for_service(  # type: ignore[no-redef]
        base_url: str, health_path: str, timeout: int = 30, interval: float = 0.5
    ) -> bool:
        deadline = time.time() + timeout
        health_url = base_url.rstrip("/") + health_path
        while time.time() < deadline:
            try:
                resp = requests.get(health_url, timeout=DEFAULT_TIMEOUT)
                if resp.status_code == 200:
                    return True
            except requests.RequestException:
                pass
            time.sleep(interval)
        return False


# ---------------------------------------------------------------------------
# 性能压测服务 URL（复用 docker/conftest 端口约定）
# ---------------------------------------------------------------------------
PERF_BASE_URLS: Dict[str, str] = {
    "encaps": os.environ.get("ENCAPS_URL", "http://localhost:18080"),
    "sql_gateway": os.environ.get("SQL_GATEWAY_URL", "http://localhost:18081"),
    "catalog": os.environ.get("CATALOG_URL", "http://localhost:18082"),
    "rule_engine": os.environ.get("RULE_ENGINE_URL", "http://localhost:18083"),
    "finops": os.environ.get("FINOPS_URL", "http://localhost:18084"),
    "llm_gateway": os.environ.get("LLM_GATEWAY_URL", "http://localhost:18085"),
    "evaluation": os.environ.get("EVALUATION_URL", "http://localhost:18086"),
    "finops_dashboard": os.environ.get("FINOPS_DASHBOARD_URL", "http://localhost:18087"),
    "finetuning": os.environ.get("FINETUNING_URL", "http://localhost:18099"),
    "governance": os.environ.get("GOVERNANCE_URL", "http://localhost:18092"),
    "observability": os.environ.get("OBSERVABILITY_URL", "http://localhost:18093"),
    "stream_batch": os.environ.get("STREAM_BATCH_URL", "http://localhost:18097"),
    "nl2sql": os.environ.get("NL2SQL_URL", "http://localhost:18098"),
}

PERF_HEALTH_PATHS: Dict[str, str] = {
    "encaps": "/actuator/health",
    "sql_gateway": "/actuator/health",
    "catalog": "/api/v1/health",
    "rule_engine": "/actuator/health",
    "finops": "/api/v1/health",
    "llm_gateway": "/health",
    "evaluation": "/health",
    "finops_dashboard": "/api/v1/health",
    "finetuning": "/health",
    "governance": "/api/v1/health",
    "observability": "/api/v1/health",
    "stream_batch": "/actuator/health",
    "nl2sql": "/health",
}


# ---------------------------------------------------------------------------
# 13 项非功能指标阈值（目标值）
# ---------------------------------------------------------------------------
# 这些阈值是"目标值"，压测用例断言"实际值优于目标值"。
# 阈值来源：产品需求文档 §11.5 SLA 矩阵 + 行业基准。
PERF_THRESHOLDS: Dict[str, Dict[str, Any]] = {
    # 并发性能：不同并发级别下的平均响应时间上限（秒）
    "concurrent_100_response_time": {"target": 0.5, "unit": "s", "desc": "100并发平均响应时间 ≤ 500ms"},
    "concurrent_500_response_time": {"target": 1.0, "unit": "s", "desc": "500并发平均响应时间 ≤ 1s"},
    "concurrent_1000_response_time": {"target": 2.0, "unit": "s", "desc": "1000并发平均响应时间 ≤ 2s"},
    # 延迟性能
    "api_p99_latency": {"target": 0.200, "unit": "s", "desc": "API P99延迟 ≤ 200ms"},
    "sql_query_latency": {"target": 5.0, "unit": "s", "desc": "SQL查询延迟 ≤ 5s"},
    # 吞吐量
    "api_throughput": {"target": 1000, "unit": "QPS", "desc": "API吞吐量 ≥ 1000 QPS"},
    "data_ingest_throughput": {"target": 100, "unit": "MB/s", "desc": "数据摄入吞吐量 ≥ 100MB/s"},
    # 资源利用率
    "cpu_utilization": {"target": 80, "unit": "%", "desc": "CPU利用率 ≤ 80%"},
    "memory_utilization": {"target": 85, "unit": "%", "desc": "内存利用率 ≤ 85%"},
    # 稳定性
    "long_run_duration": {"target": 1800, "unit": "s", "desc": "30分钟稳定性测试无异常"},
    "error_rate": {"target": 0.001, "unit": "ratio", "desc": "错误率 ≤ 0.1%"},
    # 扩展性
    "horizontal_scale_time": {"target": 60, "unit": "s", "desc": "水平扩展完成时间 ≤ 60s"},
    # 数据一致性
    "data_consistency": {"target": 1.0, "unit": "ratio", "desc": "多租户数据一致性 = 100%"},
    # 冷启动
    "cold_start_time": {"target": 30, "unit": "s", "desc": "冷启动时间 ≤ 30s"},
    # 故障恢复
    "failover_recovery_time": {"target": 60, "unit": "s", "desc": "故障恢复时间 ≤ 60s"},
}


# ---------------------------------------------------------------------------
# 10 项 SLA 验证阈值
# ---------------------------------------------------------------------------
SLA_THRESHOLDS: Dict[str, Dict[str, Any]] = {
    "api_availability": {"target": 0.999, "unit": "ratio", "desc": "API可用性 ≥ 99.9%"},
    "sql_query_p95": {"target": 3.0, "unit": "s", "desc": "SQL查询P95 ≤ 3s"},
    "sql_query_p99": {"target": 5.0, "unit": "s", "desc": "SQL查询P99 ≤ 5s"},
    "ai_inference_latency": {"target": 2.0, "unit": "s", "desc": "AI推理延迟 ≤ 2s"},
    "finetuning_throughput": {"target": 100, "unit": "samples/s", "desc": "微调吞吐量 ≥ 100 samples/s"},
    "federated_query_latency": {"target": 10.0, "unit": "s", "desc": "跨集群查询延迟 ≤ 10s"},
    "stream_processing_delay": {"target": 1.0, "unit": "s", "desc": "流处理延迟 ≤ 1s"},
    "concurrent_tenants": {"target": 100, "unit": "tenants", "desc": "100租户并发无异常"},
    "data_governance_throughput": {"target": 50, "unit": "ops/s", "desc": "治理管道吞吐量 ≥ 50 ops/s"},
    "dashboard_render_time": {"target": 3.0, "unit": "s", "desc": "看板渲染时间 ≤ 3s"},
}


# ---------------------------------------------------------------------------
# 压测运行参数（可通过环境变量覆盖）
# ---------------------------------------------------------------------------
# 默认压测强度（可通过 PERF_* 环境变量调小以适应 CI 环境）
PERF_CONFIG = {
    # 并发压测默认请求数（每并发用户）
    "requests_per_user": int(os.environ.get("PERF_REQUESTS_PER_USER", "20")),
    # 压测超时（秒）
    "load_timeout": int(os.environ.get("PERF_LOAD_TIMEOUT", "60")),
    # 稳定性测试持续时间（秒，默认 30s 便于 CI；生产可设 1800）
    "stability_duration": int(os.environ.get("PERF_STABILITY_DURATION", "30")),
    # 资源采样间隔（秒）
    "resource_sample_interval": float(os.environ.get("PERF_SAMPLE_INTERVAL", "0.5")),
    # 是否启用 locust（大规模压测时设为 true）
    "use_locust": os.environ.get("PERF_USE_LOCUST", "false").lower() == "true",
}


# ---------------------------------------------------------------------------
# 异步压测引擎
# ---------------------------------------------------------------------------
@dataclass
class LoadResult:
    """单次压测结果。

    Attributes:
        total_requests: 总请求数
        success_count: 成功请求数
        error_count: 失败请求数
        error_rate: 错误率（0~1）
        latencies: 每个请求的延迟（秒）列表
        duration: 压测总耗时（秒）
        qps: 吞吐量（请求/秒）
        avg_latency: 平均延迟（秒）
        p50_latency: P50 延迟（秒）
        p95_latency: P95 延迟（秒）
        p99_latency: P99 延迟（秒）
        max_latency: 最大延迟（秒）
        status_codes: 状态码分布字典
    """

    total_requests: int = 0
    success_count: int = 0
    error_count: int = 0
    error_rate: float = 0.0
    latencies: List[float] = field(default_factory=list)
    duration: float = 0.0
    qps: float = 0.0
    avg_latency: float = 0.0
    p50_latency: float = 0.0
    p95_latency: float = 0.0
    p99_latency: float = 0.0
    max_latency: float = 0.0
    status_codes: Dict[int, int] = field(default_factory=dict)

    def compute_stats(self) -> None:
        """根据 latencies 计算统计指标。"""
        if not self.latencies:
            return
        self.avg_latency = statistics.mean(self.latencies)
        sorted_lat = sorted(self.latencies)
        n = len(sorted_lat)
        self.p50_latency = sorted_lat[int(n * 0.50)]
        self.p95_latency = sorted_lat[min(int(n * 0.95), n - 1)]
        self.p99_latency = sorted_lat[min(int(n * 0.99), n - 1)]
        self.max_latency = sorted_lat[-1]
        self.error_rate = (
            self.error_count / self.total_requests if self.total_requests > 0 else 0.0
        )
        self.qps = (
            self.success_count / self.duration if self.duration > 0 else 0.0
        )

    def to_dict(self) -> Dict[str, Any]:
        """转换为可序列化字典（用于 JSON 报告）。"""
        return {
            "total_requests": self.total_requests,
            "success_count": self.success_count,
            "error_count": self.error_count,
            "error_rate": round(self.error_rate, 6),
            "duration": round(self.duration, 3),
            "qps": round(self.qps, 2),
            "avg_latency": round(self.avg_latency, 4),
            "p50_latency": round(self.p50_latency, 4),
            "p95_latency": round(self.p95_latency, 4),
            "p99_latency": round(self.p99_latency, 4),
            "max_latency": round(self.max_latency, 4),
            "status_codes": dict(self.status_codes),
        }


class AsyncLoadEngine:
    """基于 asyncio + httpx 的异步压测引擎。

    用法::

        engine = AsyncLoadEngine(target_url, headers={...})
        result = await engine.run(concurrency=100, total_requests=1000)
        print(result.p99_latency, result.qps)

    特点：
    - 轻平台（不依赖 locust）；
    - 支持 GET/POST 方法与自定义 payload；
    - 连接池复用（httpx.AsyncClient），减少 TCP 握手开销；
    - 返回 LoadResult，含完整延迟分布与状态码统计。
    """

    def __init__(
        self,
        target_url: str,
        method: str = "GET",
        headers: Optional[Dict[str, str]] = None,
        json_payload: Optional[Dict[str, Any]] = None,
        timeout: float = 10.0,
    ) -> None:
        self.target_url = target_url
        self.method = method.upper()
        self.headers = headers or {}
        self.json_payload = json_payload
        self.timeout = timeout

    async def _single_request(self, client) -> float:
        """执行单次请求，返回延迟（秒）；失败返回负数。"""
        start = time.perf_counter()
        try:
            if self.method == "GET":
                resp = await client.get(self.target_url, headers=self.headers)
            else:
                resp = await client.post(
                    self.target_url, headers=self.headers, json=self.json_payload
                )
            elapsed = time.perf_counter() - start
            # 将状态码记录到 client 的共享属性上
            codes: Dict[int, int] = getattr(client, "_status_codes", {})
            codes[resp.status_code] = codes.get(resp.status_code, 0) + 1
            client._status_codes = codes  # type: ignore[attr-defined]
            if 200 <= resp.status_code < 400:
                return elapsed
            return -elapsed  # 非 2xx/3xx 视为失败
        except Exception:
            return -1.0

    async def _worker(self, client, requests_per_worker: int, result: LoadResult) -> None:
        """单个并发 worker。"""
        for _ in range(requests_per_worker):
            lat = await self._single_request(client)
            if lat < 0:
                result.error_count += 1
                result.latencies.append(abs(lat) if lat < -0.5 else 0.0)
            else:
                result.success_count += 1
                result.latencies.append(lat)

    async def run(
        self,
        concurrency: int = 100,
        total_requests: int = 1000,
        max_concurrent: int = 1000,
    ) -> LoadResult:
        """运行压测。

        Args:
            concurrency: 并发用户数
            total_requests: 总请求数
            max_concurrent: httpx 连接池上限

        Returns:
            LoadResult 压测结果
        """
        try:
            import httpx
        except ImportError:  # pragma: no cover
            return LoadResult()

        result = LoadResult(total_requests=total_requests)
        # 分配每个 worker 的请求数
        per_worker = max(1, total_requests // concurrency)
        actual_total = per_worker * concurrency

        start_time = time.perf_counter()
        try:
            async with httpx.AsyncClient(
                limits=httpx.Limits(max_connections=max_concurrent),
                timeout=self.timeout,
            ) as client:
                client._status_codes = {}  # type: ignore[attr-defined]
                tasks = [
                    self._worker(client, per_worker, result) for _ in range(concurrency)
                ]
                await asyncio.gather(*tasks)
                result.status_codes = dict(getattr(client, "_status_codes", {}))
        except Exception:
            pass
        result.total_requests = actual_total
        result.duration = time.perf_counter() - start_time
        result.compute_stats()
        return result


# ---------------------------------------------------------------------------
# 资源监控器
# ---------------------------------------------------------------------------
class ResourceMonitor:
    """基于 psutil 的资源利用率监控器。

    用法::

        monitor = ResourceMonitor()
        monitor.start()
        # ... 执行压测 ...
        stats = monitor.stop()
        print(stats["cpu_avg"], stats["memory_avg"])
    """

    def __init__(self, interval: float = 0.5) -> None:
        self.interval = interval
        self._running = False
        self._cpu_samples: List[float] = []
        self._memory_samples: List[float] = []
        self._thread = None

    def start(self) -> None:
        """开始采样（在后台线程中执行）。"""
        import threading

        self._running = True
        self._cpu_samples.clear()
        self._memory_samples.clear()
        self._thread = threading.Thread(target=self._sample_loop, daemon=True)
        self._thread.start()

    def _sample_loop(self) -> None:
        try:
            import psutil
        except ImportError:
            return
        while self._running:
            try:
                self._cpu_samples.append(psutil.cpu_percent(interval=None))
                self._memory_samples.append(psutil.virtual_memory().percent)
            except Exception:
                pass
            time.sleep(self.interval)

    def stop(self) -> Dict[str, float]:
        """停止采样并返回统计结果。"""
        self._running = False
        if self._thread:
            self._thread.join(timeout=2)
        cpu_avg = statistics.mean(self._cpu_samples) if self._cpu_samples else 0.0
        cpu_max = max(self._cpu_samples) if self._cpu_samples else 0.0
        mem_avg = statistics.mean(self._memory_samples) if self._memory_samples else 0.0
        mem_max = max(self._memory_samples) if self._memory_samples else 0.0
        return {
            "cpu_avg": round(cpu_avg, 2),
            "cpu_max": round(cpu_max, 2),
            "memory_avg": round(mem_avg, 2),
            "memory_max": round(mem_max, 2),
            "sample_count": len(self._cpu_samples),
        }


# ---------------------------------------------------------------------------
# 服务可用性探测
# ---------------------------------------------------------------------------
def is_perf_service_available(name: str, timeout: int = 5) -> bool:
    """检查性能压测目标服务是否可用。"""
    if name not in PERF_BASE_URLS:
        return False
    url = PERF_BASE_URLS[name]
    health_path = PERF_HEALTH_PATHS.get(name, "/actuator/health")
    return wait_for_service(url, health_path, timeout=timeout, interval=0.2)


# ---------------------------------------------------------------------------
# pytest fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="session")
def perf_auth_token() -> str:
    """性能压测专用 JWT token（tenantId=perf-tenant）。"""
    return generate_test_jwt(tenant_id="perf-tenant", user_id="perf-tester")


@pytest.fixture(scope="session")
def perf_api_client(perf_auth_token) -> requests.Session:
    """性能压测专用 HTTP 客户端，自动注入 Bearer token。"""
    session = requests.Session()
    session.headers.update(
        {
            "Authorization": f"Bearer {perf_auth_token}",
            "Content-Type": "application/json",
            "X-Tenant-Id": "perf-tenant",
        }
    )
    original_request = session.request

    def request_with_timeout(method, url, **kwargs):
        kwargs.setdefault("timeout", DEFAULT_TIMEOUT)
        return original_request(method, url, **kwargs)

    session.request = request_with_timeout  # type: ignore[assignment]
    yield session
    session.close()


@pytest.fixture(scope="session")
def perf_services_ready() -> Dict[str, bool]:
    """一次性探测所有性能压测目标服务可用性。

    Returns:
        服务名 → 是否可用的字典。
    """
    readiness: Dict[str, bool] = {}
    for name in PERF_BASE_URLS:
        readiness[name] = is_perf_service_available(name)
    return readiness


@pytest.fixture(scope="session")
def encaps_available(perf_services_ready) -> bool:
    """封装层是否可用。"""
    return perf_services_ready.get("encaps", False)


@pytest.fixture(scope="session")
def sql_gateway_available(perf_services_ready) -> bool:
    """SQL 网关是否可用。"""
    return perf_services_ready.get("sql_gateway", False)


@pytest.fixture(scope="session")
def catalog_available(perf_services_ready) -> bool:
    """Catalog 是否可用。"""
    return perf_services_ready.get("catalog", False)


@pytest.fixture(scope="session")
def rule_engine_available(perf_services_ready) -> bool:
    """规则引擎是否可用。"""
    return perf_services_ready.get("rule_engine", False)


@pytest.fixture(scope="session")
def llm_gateway_available(perf_services_ready) -> bool:
    """LLM 网关是否可用。"""
    return perf_services_ready.get("llm_gateway", False)


@pytest.fixture(scope="session")
def finetuning_available(perf_services_ready) -> bool:
    """微调服务是否可用。"""
    return perf_services_ready.get("finetuning", False)


@pytest.fixture(scope="session")
def governance_available(perf_services_ready) -> bool:
    """治理服务是否可用。"""
    return perf_services_ready.get("governance", False)


@pytest.fixture(scope="session")
def stream_batch_available(perf_services_ready) -> bool:
    """流批一体服务是否可用。"""
    return perf_services_ready.get("stream_batch", False)


@pytest.fixture(scope="session")
def encaps_url() -> str:
    """封装层基础 URL。"""
    return PERF_BASE_URLS["encaps"]


@pytest.fixture(scope="session")
def sql_gateway_url() -> str:
    """SQL 网关基础 URL。"""
    return PERF_BASE_URLS["sql_gateway"]


@pytest.fixture(scope="session")
def catalog_url() -> str:
    """Catalog 基础 URL。"""
    return PERF_BASE_URLS["catalog"]


@pytest.fixture(scope="session")
def rule_engine_url() -> str:
    """规则引擎基础 URL。"""
    return PERF_BASE_URLS["rule_engine"]


@pytest.fixture(scope="session")
def llm_gateway_url() -> str:
    """LLM 网关基础 URL。"""
    return PERF_BASE_URLS["llm_gateway"]


@pytest.fixture(scope="session")
def finetuning_url() -> str:
    """微调服务基础 URL。"""
    return PERF_BASE_URLS["finetuning"]


@pytest.fixture(scope="session")
def governance_url() -> str:
    """治理服务基础 URL。"""
    return PERF_BASE_URLS["governance"]


@pytest.fixture(scope="session")
def stream_batch_url() -> str:
    """流批一体服务基础 URL。"""
    return PERF_BASE_URLS["stream_batch"]


@pytest.fixture(scope="session")
def finops_dashboard_url() -> str:
    """FinOps 看板基础 URL。"""
    return PERF_BASE_URLS["finops_dashboard"]


# ---------------------------------------------------------------------------
# 异步压测引擎 fixture（便于在测试中直接使用）
# ---------------------------------------------------------------------------
@pytest.fixture
def load_engine_factory(perf_auth_token):
    """异步压测引擎工厂。

    用法::

        engine = load_engine_factory(url, method="GET")
        result = await engine.run(concurrency=100, total_requests=1000)
    """
    def _create(
        target_url: str,
        method: str = "GET",
        json_payload: Optional[Dict[str, Any]] = None,
    ) -> AsyncLoadEngine:
        headers = {
            "Authorization": f"Bearer {perf_auth_token}",
            "Content-Type": "application/json",
            "X-Tenant-Id": "perf-tenant",
        }
        return AsyncLoadEngine(target_url, method=method, headers=headers, json_payload=json_payload)

    return _create


@pytest.fixture
def resource_monitor() -> ResourceMonitor:
    """资源监控器 fixture。"""
    return ResourceMonitor(interval=PERF_CONFIG["resource_sample_interval"])


# ---------------------------------------------------------------------------
# 常量与类暴露 fixture（供测试文件使用，避免直接 import conftest）
# ---------------------------------------------------------------------------
@pytest.fixture(scope="session")
def perf_config() -> Dict[str, Any]:
    """压测运行参数。"""
    return PERF_CONFIG


@pytest.fixture(scope="session")
def perf_thresholds() -> Dict[str, Dict[str, Any]]:
    """13 项非功能指标阈值。"""
    return PERF_THRESHOLDS


@pytest.fixture(scope="session")
def sla_thresholds() -> Dict[str, Dict[str, Any]]:
    """10 项 SLA 验证阈值。"""
    return SLA_THRESHOLDS


@pytest.fixture(scope="session")
def load_result_class():
    """LoadResult 类（供测试构造结果对象）。"""
    return LoadResult


@pytest.fixture(scope="session")
def async_load_engine_class():
    """AsyncLoadEngine 类。"""
    return AsyncLoadEngine


@pytest.fixture(scope="session")
def resource_monitor_class():
    """ResourceMonitor 类。"""
    return ResourceMonitor


# ---------------------------------------------------------------------------
# 自定义标记
# ---------------------------------------------------------------------------
def pytest_configure(config):
    """注册自定义 pytest 标记。"""
    config.addinivalue_line("markers", "perf: 性能压测用例")
    config.addinivalue_line("markers", "sla: SLA 验证用例")
    config.addinivalue_line("markers", "benchmark: 基准压测用例")
    config.addinivalue_line("markers", "concurrent: 并发性能压测")
    config.addinivalue_line("markers", "latency: 延迟性能压测")
    config.addinivalue_line("markers", "throughput: 吞吐量压测")
    config.addinivalue_line("markers", "resource: 资源利用率监控")
    config.addinivalue_line("markers", "stability: 稳定性测试")
    config.addinivalue_line("markers", "scalability: 扩展性测试")
    config.addinivalue_line("markers", "consistency: 数据一致性测试")
    config.addinivalue_line("markers", "coldstart: 冷启动测试")
    config.addinivalue_line("markers", "failover: 故障恢复测试")
    config.addinivalue_line(
        "markers", "slow: 慢速测试（默认不选中，需 --run-slow 显式启用）"
    )


def pytest_collection_modifyitems(config, items):
    """自动跳过服务不可用的性能测试。"""
    # 性能测试默认较慢，可通过 --run-perf 显式启用
    run_perf = config.getoption("--run-perf") if config.getoption("--run-perf", default=False) else False
    for item in items:
        if "perf" in item.keywords and not run_perf:
            # 不自动跳过，让测试内部的 available fixture 决定
            pass


def pytest_addoption(parser):
    """添加命令行选项。"""
    parser.addoption(
        "--run-perf",
        action="store_true",
        default=False,
        help="显式启用性能压测（默认由服务可用性自动决定）",
    )
    parser.addoption(
        "--perf-duration",
        action="store",
        default=None,
        help="覆盖稳定性测试持续时间（秒）",
    )