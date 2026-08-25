"""CORS 预检与白名单测试（main.py 进程内；loop 因同名 app 包冲突走子进程隔离）."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

if "FINETUNE_WORK_DIR" not in os.environ:
    os.environ["FINETUNE_WORK_DIR"] = tempfile.mkdtemp(prefix="finetune-cors-")

_ROOT = str(Path(__file__).resolve().parents[1])
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from fastapi.testclient import TestClient
import pytest

import main as finetune_main

_LOOP_ROOT = Path(__file__).resolve().parents[1] / "loop"

_PREFLIGHT_HEADERS = {
    "Origin": "http://localhost:5173",
    "Access-Control-Request-Method": "GET",
}

_CORS_SCRIPT = r'''
import json
import sys

sys.path.insert(0, sys.argv[1])

from fastapi.testclient import TestClient

from app.main import create_app

client = TestClient(create_app())
allowed = client.options(
    "/health",
    headers={"Origin": "http://localhost:5173", "Access-Control-Request-Method": "GET"},
)
denied = client.options(
    "/health",
    headers={"Origin": "http://evil.example.com", "Access-Control-Request-Method": "GET"},
)
print(json.dumps({
    "allowedStatus": allowed.status_code,
    "allowedOrigin": allowed.headers.get("access-control-allow-origin"),
    "allowedCredentials": allowed.headers.get("access-control-allow-credentials"),
    "deniedHasAllowOrigin": "access-control-allow-origin" in denied.headers,
}))
'''


class TestFinetuneCors:
    def test_preflight_default_origin_allowed(self, monkeypatch) -> None:
        monkeypatch.delenv("CORS_ORIGINS", raising=False)
        client = TestClient(finetune_main.create_app())
        resp = client.options("/api/v1/health", headers=_PREFLIGHT_HEADERS)
        assert resp.status_code == 200
        assert resp.headers["access-control-allow-origin"] == "http://localhost:5173"
        assert resp.headers["access-control-allow-credentials"] == "true"

    def test_preflight_unknown_origin_not_echoed(self) -> None:
        c = TestClient(finetune_main.create_app())
        resp = c.options(
            "/api/v1/health",
            headers={
                "Origin": "http://evil.example.com",
                "Access-Control-Request-Method": "GET",
            },
        )
        assert "access-control-allow-origin" not in resp.headers

    def test_preflight_env_whitelist(self, monkeypatch) -> None:
        monkeypatch.setenv("CORS_ORIGINS", "https://front.example.com")
        client = TestClient(finetune_main.create_app())
        resp = client.options(
            "/api/v1/health",
            headers={
                "Origin": "https://front.example.com",
                "Access-Control-Request-Method": "GET",
            },
        )
        assert resp.status_code == 200
        assert resp.headers["access-control-allow-origin"] == "https://front.example.com"

    def test_wildcard_no_longer_effective(self) -> None:
        c = TestClient(finetune_main.create_app())
        resp = c.get("/api/v1/health", headers={"Origin": "http://random-site.org"})
        assert "access-control-allow-origin" not in resp.headers


class TestLoopCors:
    def _run(self) -> dict:
        proc = subprocess.run(
            [sys.executable, "-c", _CORS_SCRIPT, str(_LOOP_ROOT)],
            capture_output=True,
            text=True,
            timeout=180,
            cwd=str(_LOOP_ROOT),
        )
        assert proc.returncode == 0, proc.stderr
        return json.loads(proc.stdout.strip().splitlines()[-1])

    def test_preflight_default_origin_allowed(self) -> None:
        payload = self._run()
        assert payload["allowedStatus"] == 200
        assert payload["allowedOrigin"] == "http://localhost:5173"
        assert payload["allowedCredentials"] == "true"

    def test_unknown_origin_not_echoed(self) -> None:
        payload = self._run()
        assert payload["deniedHasAllowOrigin"] is False
