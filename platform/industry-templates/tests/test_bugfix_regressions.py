"""缺陷修复回归测试：事件循环卸载 / 异常边界 / namespace 校验 / bool 参数."""

from __future__ import annotations

import asyncio
import time

import httpx
import pytest
from fastapi.testclient import TestClient

from industry_templates.api.app import create_app
from industry_templates.config.settings import Settings
from industry_templates.models import DeploymentRequest, DeploymentStatus
from industry_templates.services.exceptions import (
    NamespaceValidationError,
    ParameterValidationError,
    RenderError,
)
from industry_templates.services.helm_executor import HelmCommandResult
from industry_templates.services.registry import ServiceRegistry
from industry_templates.services.template_engine import TemplateEngine
from industry_templates.templates import get_builtin_templates


_FIN_VALUES = {
    "datasource.order_db": "jdbc:mysql://order:3306/order",
    "datasource.user_db": "jdbc:mysql://user:3306/user",
}


def _deploy_payload(**overrides) -> dict:
    payload = {
        "tenantId": "tenant-001",
        "releaseName": "regression-release",
        "values": dict(_FIN_VALUES),
    }
    payload.update(overrides)
    return payload


class _SlowFakeHelmExecutor:
    def __init__(self, delay: float = 0.5) -> None:
        self.delay = delay
        self.calls: list[dict] = []

    def install_or_upgrade(self, **kwargs) -> HelmCommandResult:
        self.calls.append(kwargs)
        time.sleep(self.delay)
        return HelmCommandResult(returncode=0, stdout="", stderr="")

    def uninstall(self, releaseName: str, namespace: str) -> HelmCommandResult:
        return HelmCommandResult(returncode=0, stdout="", stderr="")


def _helm_mode_app(delay: float = 0.5):
    settings = Settings(deployMode="helm")
    engine = TemplateEngine(
        templates=get_builtin_templates(),
        deployMode="helm",
        helmExecutor=_SlowFakeHelmExecutor(delay),
        chartBase="./charts",
    )
    registry = ServiceRegistry(settings=settings, engine=engine)
    return create_app(settings=settings, registry=registry), registry


# ---------- P1: 事件循环不被 helm 阻塞 ----------


class TestAsyncDeployOffload:
    async def test_deploy_helm_does_not_block_event_loop(self):
        app, _registry = _helm_mode_app(delay=0.5)
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as ac:
            deploy_task = asyncio.create_task(
                ac.post("/api/v1/templates/fin-risk-scorecard/deploy", json=_deploy_payload())
            )
            start = time.perf_counter()
            health_resp = await ac.get("/api/v1/health")
            templates_resp = await ac.get("/api/v1/templates")
            other_elapsed = time.perf_counter() - start
            deploy_resp = await deploy_task

        assert other_elapsed < 0.4
        assert health_resp.status_code == 200
        assert templates_resp.status_code == 200
        assert len(templates_resp.json()) == 7
        assert deploy_resp.status_code == 201
        assert deploy_resp.json()["status"] == "running"

    def test_deploy_async_facade_offloads_to_thread(self):
        app, registry = _helm_mode_app(delay=0.5)

        async def main() -> float:
            start = time.perf_counter()
            record = await registry.engine.deploy_async(
                "fin-risk-scorecard",
                DeploymentRequest(
                    tenantId="tenant-001",
                    releaseName="facade-release",
                    values=dict(_FIN_VALUES),
                ),
            )
            assert record.status == DeploymentStatus.RUNNING
            return time.perf_counter() - start

        elapsed = asyncio.run(main())
        assert elapsed >= 0.45


# ---------- P2: 泛异常兜底置 FAILED，不外泄堆栈 ----------


class TestExceptionBoundary:
    def test_deploy_generic_exception_marks_record_failed(self, engine, monkeypatch):
        def boom(record):
            raise OSError("k8s api server unreachable")

        monkeypatch.setattr(engine, "_mock_deploy", boom)
        with pytest.raises(OSError):
            engine.deploy(
                "fin-risk-scorecard",
                DeploymentRequest(tenantId="t1", releaseName="r1", values=dict(_FIN_VALUES)),
            )
        records = list(engine.deployments.values())
        assert len(records) == 1
        assert records[0].status == DeploymentStatus.FAILED
        assert records[0].errorMessage == "k8s api server unreachable"
        assert records[0].finishedAt is not None

    def test_deploy_error_message_truncated_to_safe_length(self, engine, monkeypatch):
        def boom(record):
            raise RuntimeError("E" * 2000)

        monkeypatch.setattr(engine, "_mock_deploy", boom)
        with pytest.raises(RuntimeError):
            engine.deploy(
                "fin-risk-scorecard",
                DeploymentRequest(tenantId="t1", releaseName="r1", values=dict(_FIN_VALUES)),
            )
        record = next(iter(engine.deployments.values()))
        assert len(record.errorMessage) == 500

    def test_deploy_template_error_keeps_original_granularity(self, engine, monkeypatch):
        def boom(record):
            raise RenderError("模板渲染炸了")

        monkeypatch.setattr(engine, "_mock_deploy", boom)
        with pytest.raises(RenderError):
            engine.deploy(
                "fin-risk-scorecard",
                DeploymentRequest(tenantId="t1", releaseName="r1", values=dict(_FIN_VALUES)),
            )
        record = next(iter(engine.deployments.values()))
        assert record.status == DeploymentStatus.FAILED
        assert record.errorMessage is None

    def test_api_generic_exception_returns_500_without_traceback(self, engine, client, monkeypatch):
        def boom(record):
            raise RuntimeError("connection reset by peer")

        monkeypatch.setattr(engine, "_mock_deploy", boom)
        raw_client = TestClient(client.app, raise_server_exceptions=False)
        resp = raw_client.post("/api/v1/templates/fin-risk-scorecard/deploy", json=_deploy_payload())
        assert resp.status_code == 500
        body = resp.json()
        assert "Traceback" not in resp.text
        assert "Traceback" not in body["message"]
        record = next(iter(engine.deployments.values()))
        assert record.status == DeploymentStatus.FAILED
        assert record.errorMessage is not None


# ---------- P2: namespace DNS 标签校验 ----------


class TestNamespaceValidation:
    @pytest.mark.parametrize("tenant_id", ["Tenant:A", "大写"])
    def test_deploy_rejects_invalid_tenant_ids(self, client, tenant_id):
        resp = client.post(
            "/api/v1/templates/fin-risk-scorecard/deploy",
            json=_deploy_payload(tenantId=tenant_id),
        )
        assert resp.status_code == 400
        assert "namespace" in resp.json()["message"]

    def test_deploy_rejects_invalid_explicit_namespace(self, client):
        resp = client.post(
            "/api/v1/templates/fin-risk-scorecard/deploy",
            json=_deploy_payload(namespace="-bad"),
        )
        assert resp.status_code == 400
        assert "namespace" in resp.json()["message"]

    @pytest.mark.parametrize("namespace", ["tenant-acme-corp", "a", "0abc", "a" * 63])
    def test_valid_namespaces_accepted_by_regex(self, namespace):
        from industry_templates.services.template_engine import _NAMESPACE_RE

        assert _NAMESPACE_RE.match(namespace)

    @pytest.mark.parametrize("namespace", ["-bad", "bad-", "Bad", "a" * 64, "", "ten ant"])
    def test_invalid_namespaces_rejected_by_regex(self, namespace):
        from industry_templates.services.template_engine import _NAMESPACE_RE

        assert not _NAMESPACE_RE.match(namespace)

    def test_invalid_namespace_leaves_no_deployment_record(self, engine):
        before = len(engine.deployments)
        with pytest.raises(NamespaceValidationError):
            engine.deploy(
                "fin-risk-scorecard",
                DeploymentRequest(tenantId="Tenant:A", releaseName="r1", values=dict(_FIN_VALUES)),
            )
        assert len(engine.deployments) == before

    def test_deploy_valid_tenant_namespace_passes(self, client):
        resp = client.post(
            "/api/v1/templates/fin-risk-scorecard/deploy",
            json=_deploy_payload(tenantId="acme-corp"),
        )
        assert resp.status_code == 201, resp.text
        assert resp.json()["namespace"] == "tenant-acme-corp"


# ---------- P2: bool 穿透 int/float 校验 ----------


class TestBoolParameterRejection:
    def test_validate_integer_rejects_bool(self, engine):
        template = engine.get_template("fin-risk-scorecard")
        values = {
            "datasource.order_db": "jdbc://...",
            "datasource.user_db": "jdbc://...",
            "model.xgboost.max_depth": True,
        }
        with pytest.raises(ParameterValidationError) as exc_info:
            TemplateEngine.validate_parameters(template, values)
        assert "bool" in exc_info.value.message

    def test_validate_float_rejects_bool(self, engine):
        template = engine.get_template("fin-risk-scorecard")
        values = {
            "datasource.order_db": "jdbc://...",
            "datasource.user_db": "jdbc://...",
            "scoring.threshold_high": True,
        }
        with pytest.raises(ParameterValidationError) as exc_info:
            TemplateEngine.validate_parameters(template, values)
        assert "bool" in exc_info.value.message

    def test_validate_integer_accepts_real_int(self, engine):
        template = engine.get_template("fin-risk-scorecard")
        values = {
            "datasource.order_db": "jdbc://...",
            "datasource.user_db": "jdbc://...",
            "model.xgboost.max_depth": 6,
        }
        TemplateEngine.validate_parameters(template, values)

    def test_api_integer_bool_rejected_422(self, client):
        values = dict(_FIN_VALUES)
        values["model.xgboost.max_depth"] = True
        resp = client.post(
            "/api/v1/templates/fin-risk-scorecard/deploy",
            json=_deploy_payload(values=values),
        )
        assert resp.status_code == 422
        assert "bool" in resp.json()["message"]
