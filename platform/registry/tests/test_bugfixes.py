"""缺陷修复回归测试.

覆盖：
- 创建部署引用不存在模型 → 404 model_not_found
- 真实模式 docker run 慢调用经 asyncio.to_thread 卸载，事件循环不被阻塞
"""

from __future__ import annotations

import asyncio
import subprocess
import time

from app.api.registry_routes import SimpleModelRegistry, create_router
from app.core.deployment_manager import DeploymentManager
from app.core.health_checker import HealthChecker
from app.models import DeployRequest, ModelRegisterRequest
from fastapi import FastAPI
import httpx


class TestDeploymentModelValidation:
    """create_deployment 模型存在性校验测试."""

    def test_create_deployment_unknown_model_returns_404_model_not_found(self, client, sample_deploy_request):
        """引用未注册模型创建部署应 404 且响应体含 model_not_found."""
        resp = client.post(
            "/api/v1/registry/deployments",
            json=sample_deploy_request.model_dump(),
        )
        assert resp.status_code == 404
        assert "model_not_found" in resp.json()["detail"]

    def test_create_deployment_unknown_version_returns_404(self, client, registered_model, sample_deploy_request):
        """模型存在但指定版本不存在也应 404 model_not_found."""
        payload = sample_deploy_request.model_copy(update={"version": "9.9.9"})
        resp = client.post(
            "/api/v1/registry/deployments",
            json=payload.model_dump(),
        )
        assert resp.status_code == 404
        assert "model_not_found" in resp.json()["detail"]

    def test_create_deployment_default_version_resolves_latest_active(
        self, client, registered_model, sample_deploy_request
    ):
        """version 缺省时解析最新活跃版本的语义保持不变."""
        payload = sample_deploy_request.model_copy(update={"version": ""})
        resp = client.post(
            "/api/v1/registry/deployments",
            json=payload.model_dump(),
        )
        assert resp.status_code == 201
        assert resp.json()["status"] == "running"


class TestEventLoopNotBlocked:
    """真实模式 docker 调用 to_thread 卸载测试."""

    def test_real_mode_docker_run_does_not_block_event_loop(self, monkeypatch):
        """docker run 慢调用期间事件循环仍可响应其他请求."""

        def fake_docker_run(cmd, capture_output=False, text=False, timeout=None, **kwargs):
            time.sleep(0.5)
            return subprocess.CompletedProcess(cmd, 0, stdout="fake-container-id\n", stderr="")

        monkeypatch.setattr("subprocess.run", fake_docker_run)

        registry = SimpleModelRegistry()
        registry.register(
            ModelRegisterRequest(
                modelName="qwen2-7b-finetuned",
                version="0.1.0",
                path="/data/models/qwen2-7b-finetuned",
                tenantId="tenant-001",
            )
        )
        application = FastAPI()
        application.include_router(
            create_router(
                deployment_manager=DeploymentManager(mock_mode=False),
                health_checker=HealthChecker(mock_mode=True),
                model_registry=registry,
            )
        )
        payload = DeployRequest(
            modelName="qwen2-7b-finetuned",
            version="0.1.0",
            runtime="vllm",
            port=8000,
            tenantId="tenant-001",
        ).model_dump()

        async def scenario():
            transport = httpx.ASGITransport(app=application)
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
                slow = asyncio.create_task(ac.post("/api/v1/registry/deployments", json=payload))
                await asyncio.sleep(0.1)
                start = time.perf_counter()
                quick = await ac.get("/api/v1/registry/deployments")
                elapsed = time.perf_counter() - start
                created = await slow
            return created, quick, elapsed

        created, quick, elapsed = asyncio.run(scenario())
        assert created.status_code == 201
        body = created.json()
        assert body["status"] == "running"
        assert body["containerId"] == "fake-container-id"
        assert quick.status_code == 200
        assert elapsed < 0.3

    def test_real_mode_docker_stop_does_not_block_event_loop(self, monkeypatch):
        """docker stop 慢调用期间事件循环仍可响应其他请求."""

        def fake_docker_run(cmd, capture_output=False, text=False, timeout=None, **kwargs):
            return subprocess.CompletedProcess(cmd, 0, stdout="fake-container-id\n", stderr="")

        def fake_docker_stop(cmd, capture_output=False, text=False, timeout=None, **kwargs):
            time.sleep(0.5)
            return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

        monkeypatch.setattr("subprocess.run", fake_docker_run)

        manager = DeploymentManager(mock_mode=False)
        record = manager.deploy(
            DeployRequest(
                modelName="m",
                version="0.1.0",
                port=8000,
                tenantId="tenant-001",
            )
        )

        monkeypatch.setattr("subprocess.run", fake_docker_stop)

        registry = SimpleModelRegistry()
        application = FastAPI()
        application.include_router(
            create_router(
                deployment_manager=manager,
                health_checker=HealthChecker(mock_mode=True),
                model_registry=registry,
            )
        )

        async def scenario():
            transport = httpx.ASGITransport(app=application)
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
                slow = asyncio.create_task(ac.delete(f"/api/v1/registry/deployments/{record.deploymentId}"))
                await asyncio.sleep(0.1)
                start = time.perf_counter()
                quick = await ac.get("/api/v1/registry/deployments")
                elapsed = time.perf_counter() - start
                stopped = await slow
            return stopped, quick, elapsed

        stopped, quick, elapsed = asyncio.run(scenario())
        assert stopped.status_code == 200
        assert stopped.json()["status"] == "stopped"
        assert quick.status_code == 200
        assert elapsed < 0.3
