"""P1-8 Phase 1 真实存储泳道：catalog×PostgreSQL 与 MinIO 的「写→读」内容级断言。

运行前提：本栈带 real-storage override 拉起（REAL_STORAGE=1）：

    docker compose -f docker-compose.yml -f docker-compose.real-storage.yml up -d --wait
    REAL_STORAGE=1 pytest e2e/test_e2e_real_storage.py

默认 mock/SQLite 栈上本文件整体 skip（PR 必检泳道零影响）；
nightly/dispatch 专用泳道见 .github/workflows/it-real-storage.yml
（deploy-completeness-plan §四 Phase 1 口径：不塞 PR 必检）。

断言口径（内容级，拒绝「200 即过」）：
- catalog 经 API 建库 → 按 id 读回 → 名称/描述逐字一致；
- 上述记录在 catalog 容器进程重启后仍可读回（数据真实落在 PG，不是进程内存）；
- MinIO 经 S3 API put 对象 → get 回字节级一致 + list 命中。
"""

from __future__ import annotations

import os
import subprocess
import time
import uuid
from pathlib import Path

import pytest
import requests

pytestmark = pytest.mark.skipif(
    os.environ.get("REAL_STORAGE") != "1",
    reason="真实存储泳道：需 docker-compose.real-storage.yml override 拉起（REAL_STORAGE=1）",
)

COMPOSE_FILES = ["-f", "docker-compose.yml", "-f", "docker-compose.real-storage.yml"]


def _compose(*args: str) -> subprocess.CompletedProcess:
    """在 tests/integration 目录下执行 docker compose（与 CI 泳道同一工程名）。"""
    return subprocess.run(
        ["docker", "compose", *COMPOSE_FILES, *args],
        cwd=Path(__file__).resolve().parent.parent,
        capture_output=True,
        text=True,
        timeout=300,
    )


def _wait_catalog_health(catalog_url: str, timeout: int = 120) -> None:
    """restart 后等 catalog 重新 healthy（内容级读断言的前置就绪等待）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if requests.get(catalog_url + "/api/v1/health", timeout=5).status_code == 200:
                return
        except requests.RequestException:
            pass
        time.sleep(2)
    pytest.fail(f"catalog 健康检查在 {timeout}s 内未恢复（restart 后持久性读断言的前置等待）")


def test_catalog_write_read_via_postgres(e2e_api_client, catalog_url):
    """写：API 建库；读：按 id 读回字段逐字一致（此时数据已在 PG，非进程内存）。"""
    name = "it-real-storage-probe-" + uuid.uuid4().hex[:8]
    description = "P1-8 Phase 1 真实存储写→读探针（PG）"
    resp = e2e_api_client.post(
        catalog_url + "/api/v1/catalog/databases",
        json={"name": name, "description": description},
    )
    assert resp.status_code in (200, 201), resp.text
    created = resp.json()
    db_id = created["id"]
    assert created["name"] == name

    got = e2e_api_client.get(f"{catalog_url}/api/v1/catalog/databases/{db_id}")
    assert got.status_code == 200, got.text
    row = got.json()
    assert row["name"] == name, "读回的名称与写入不一致"
    assert row["description"] == description, "读回的描述与写入不一致"


def test_catalog_row_survives_process_restart(e2e_api_client, catalog_url):
    """跨进程持久性实证：重启 catalog 容器后记录仍可读回 ⇒ 真落在 PG。"""
    name = "it-restart-probe-" + uuid.uuid4().hex[:8]
    resp = e2e_api_client.post(
        catalog_url + "/api/v1/catalog/databases",
        json={"name": name, "description": "restart 持久性探针"},
    )
    assert resp.status_code in (200, 201), resp.text
    db_id = resp.json()["id"]

    restarted = _compose("restart", "catalog")
    assert restarted.returncode == 0, restarted.stdout + restarted.stderr
    _wait_catalog_health(catalog_url)

    got = e2e_api_client.get(f"{catalog_url}/api/v1/catalog/databases/{db_id}")
    assert got.status_code == 200, got.text
    assert got.json()["name"] == name, "容器重启后记录不可读回 ⇒ 数据未真实落库"


def test_minio_object_write_read_byte_level():
    """MinIO 经 S3 API 写对象 → 读回字节级一致 + list 命中（对象存储真实可用）。"""
    import boto3
    from botocore.config import Config

    endpoint = os.environ.get("MINIO_ENDPOINT", "http://localhost:19000")
    client = boto3.client(
        "s3",
        endpoint_url=endpoint,
        aws_access_key_id="itminio",
        aws_secret_access_key="it-minio-password",
        region_name="us-east-1",
        config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 3}),
    )
    bucket = "it-real-storage-" + uuid.uuid4().hex[:8]
    key = "probe/hello.txt"
    content = b"P1-8 Phase 1: real object storage write->read marker\n"

    client.create_bucket(Bucket=bucket)
    client.put_object(Bucket=bucket, Key=key, Body=content)

    got = client.get_object(Bucket=bucket, Key=key)
    assert got["Body"].read() == content, "读回字节与写入不一致"

    listed = client.list_objects_v2(Bucket=bucket)
    assert listed.get("KeyCount", 0) == 1, f"bucket {bucket} 列表未命中写入的对象"
    assert listed["Contents"][0]["Key"] == key
