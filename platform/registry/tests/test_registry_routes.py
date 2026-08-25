"""模型仓库 API 路由单元测试.

使用 ``fastapi.testclient.TestClient`` 覆盖 ``app.api.registry_routes`` 全部端点：
- 模型注册 / 查询 / 列表 / 版本历史
- 部署创建 / 查询 / 列表 / 停止 / 更新
- 健康检查
- 服务统计
- 404 / 500 等异常路径
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from app.api.registry_routes import SimpleModelRegistry
from app.core.deployment_manager import DeploymentManager
from app.core.health_checker import HealthChecker
from app.models import (
    DeploymentRecord,
    DeploymentStatus,
    DeployRequest,
    HealthCheckResult,
    ModelRegisterRequest,
)


# ============================================================
# SimpleModelRegistry 单元测试
# ============================================================
class TestSimpleModelRegistry:
    """简化模型仓库内存逻辑测试."""

    def test_register_and_get(self, sample_register_request):
        """注册后应能按名称+默认租户取回."""
        reg = SimpleModelRegistry()
        rec = reg.register(sample_register_request)
        got = reg.get_model("qwen2-7b-finetuned", tenant="tenant-001")
        assert got is not None
        assert got.version == "0.1.0"

    def test_get_model_not_found(self):
        """查询不存在的模型应返回 None."""
        reg = SimpleModelRegistry()
        assert reg.get_model("nope") is None

    def test_get_model_by_version(self, sample_register_request):
        """按版本精确取回."""
        reg = SimpleModelRegistry()
        reg.register(sample_register_request)
        got = reg.get_model(
            "qwen2-7b-finetuned", version="0.1.0", tenant="tenant-001"
        )
        assert got is not None
        assert got.version == "0.1.0"
        # 不存在的版本
        assert reg.get_model(
            "qwen2-7b-finetuned", version="9.9.9", tenant="tenant-001"
        ) is None

    def test_list_models_filtered_by_tenant(self, sample_register_request):
        """list_models 按 tenant 过滤."""
        reg = SimpleModelRegistry()
        reg.register(sample_register_request)
        # 另一租户
        req2 = sample_register_request.model_copy(
            update={"tenantId": "tenant-002", "modelName": "model-b"}
        )
        reg.register(req2)
        assert len(reg.list_models(tenant="tenant-001")) == 1
        assert len(reg.list_models(tenant="tenant-002")) == 1
        assert len(reg.list_models(tenant="")) == 2

    def test_list_versions(self, sample_register_request):
        """list_versions 返回所有版本（含非活跃）."""
        reg = SimpleModelRegistry()
        reg.register(sample_register_request)
        v2 = sample_register_request.model_copy(update={"version": "0.2.0"})
        reg.register(v2)
        versions = reg.list_versions("qwen2-7b-finetuned", tenant="tenant-001")
        assert len(versions) == 2

    def test_list_versions_empty(self):
        """list_versions 不存在的模型返回空列表."""
        reg = SimpleModelRegistry()
        assert reg.list_versions("nope") == []

    def test_inactive_model_not_returned_by_get(self, sample_register_request):
        """isActive=False 的模型不应被 get_model 返回."""
        reg = SimpleModelRegistry()
        rec = reg.register(sample_register_request)
        rec.isActive = False
        # 不指定版本时取活跃最新
        assert reg.get_model("qwen2-7b-finetuned", tenant="tenant-001") is None
        # 指定版本时也要求 isActive
        assert reg.get_model(
            "qwen2-7b-finetuned", version="0.1.0", tenant="tenant-001"
        ) is None


# ============================================================
# API 路由：模型注册
# ============================================================
class TestRegisterModelRoute:
    """POST /api/v1/registry/models 路由测试."""

    def test_register_success(self, client, sample_register_request):
        """正常注册应返回 201 与模型记录."""
        resp = client.post(
            "/api/v1/registry/models",
            json=sample_register_request.model_dump(),
        )
        assert resp.status_code == 201
        body = resp.json()
        assert body["modelName"] == "qwen2-7b-finetuned"
        assert body["version"] == "0.1.0"
        assert body["tenantId"] == "tenant-001"

    def test_register_invalid_payload(self, client):
        """modelName 为空应返回 422 校验错误."""
        resp = client.post(
            "/api/v1/registry/models",
            json={"modelName": "", "path": "/x"},
        )
        assert resp.status_code == 422

    def test_register_internal_error(self, client, sample_register_request):
        """仓库 register 抛异常时应返回 500."""
        with patch(
            "app.api.registry_routes.SimpleModelRegistry.register",
            side_effect=RuntimeError("disk full"),
        ):
            resp = client.post(
                "/api/v1/registry/models",
                json=sample_register_request.model_dump(),
            )
        assert resp.status_code == 500
        assert "disk full" in resp.json()["detail"]


# ============================================================
# API 路由：模型查询
# ============================================================
class TestModelQueryRoutes:
    """GET /models, /models/{name}, /models/{name}/versions 测试."""

    def test_list_models_empty(self, client):
        """空仓库列表 total=0."""
        resp = client.get("/api/v1/registry/models")
        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] == 0
        assert body["models"] == []

    def test_list_models_after_register(self, client, registered_model):
        """注册后列表 total=1."""
        resp = client.get("/api/v1/registry/models")
        assert resp.status_code == 200
        assert resp.json()["total"] == 1

    def test_list_models_filter_tenant(
        self, client, sample_register_request
    ):
        """按 tenantId 过滤."""
        # 注册两个不同租户
        client.post(
            "/api/v1/registry/models",
            json=sample_register_request.model_dump(),
        )
        req2 = sample_register_request.model_copy(
            update={"tenantId": "tenant-002", "modelName": "model-b"}
        )
        client.post(
            "/api/v1/registry/models", json=req2.model_dump()
        )
        resp = client.get("/api/v1/registry/models?tenantId=tenant-001")
        assert resp.json()["total"] == 1

    def test_get_model_detail_success(self, client, registered_model):
        """查询已注册模型详情."""
        resp = client.get(
            "/api/v1/registry/models/qwen2-7b-finetuned?tenantId=tenant-001"
        )
        assert resp.status_code == 200
        assert resp.json()["modelName"] == "qwen2-7b-finetuned"

    def test_get_model_detail_not_found(self, client):
        """查询不存在模型应 404."""
        resp = client.get("/api/v1/registry/models/nope")
        assert resp.status_code == 404
        assert "nope" in resp.json()["detail"]

    def test_list_model_versions(self, client, sample_register_request):
        """查询模型版本历史."""
        client.post(
            "/api/v1/registry/models",
            json=sample_register_request.model_dump(),
        )
        v2 = sample_register_request.model_copy(update={"version": "0.2.0"})
        client.post("/api/v1/registry/models", json=v2.model_dump())
        resp = client.get(
            "/api/v1/registry/models/qwen2-7b-finetuned/versions?tenantId=tenant-001"
        )
        assert resp.status_code == 200
        assert len(resp.json()["versions"]) == 2

    def test_list_model_versions_empty(self, client):
        """不存在模型的版本历史为空列表."""
        resp = client.get("/api/v1/registry/models/nope/versions")
        assert resp.status_code == 200
        assert resp.json()["versions"] == []


# ============================================================
# API 路由：部署管理
# ============================================================
class TestDeploymentRoutes:
    """部署相关路由测试."""

    def test_create_deployment_success(
        self, client, registered_model, sample_deploy_request
    ):
        """创建部署应返回 201 与部署记录（mock 模式 running）."""
        resp = client.post(
            "/api/v1/registry/deployments",
            json=sample_deploy_request.model_dump(),
        )
        assert resp.status_code == 201
        body = resp.json()
        assert body["modelName"] == "qwen2-7b-finetuned"
        assert body["status"] == "running"
        assert body["endpoint"].startswith("http://localhost:")
        assert body["containerId"].startswith("mock-container-")

    def test_create_deployment_invalid_port(self, client):
        """port 越界应 422."""
        resp = client.post(
            "/api/v1/registry/deployments",
            json={"modelName": "m", "port": 80},
        )
        assert resp.status_code == 422

    def test_create_deployment_internal_error(
        self, client, registered_model, sample_deploy_request
    ):
        """deploy 抛异常应 500."""
        with patch(
            "app.api.registry_routes.DeploymentManager.deploy",
            side_effect=RuntimeError("no gpu"),
        ):
            resp = client.post(
                "/api/v1/registry/deployments",
                json=sample_deploy_request.model_dump(),
            )
        assert resp.status_code == 500
        assert "no gpu" in resp.json()["detail"]

    def test_list_deployments_empty(self, client):
        """空部署列表."""
        resp = client.get("/api/v1/registry/deployments")
        assert resp.status_code == 200
        assert resp.json()["total"] == 0

    def test_list_deployments_after_create(
        self, client, running_deployment
    ):
        """创建后列表 total=1."""
        resp = client.get("/api/v1/registry/deployments")
        assert resp.status_code == 200
        assert resp.json()["total"] == 1

    def test_list_deployments_filter_status(
        self, client, registered_model, sample_deploy_request
    ):
        """按 status 过滤部署列表."""
        client.post(
            "/api/v1/registry/deployments",
            json=sample_deploy_request.model_dump(),
        )
        resp = client.get("/api/v1/registry/deployments?status=running")
        assert resp.json()["total"] == 1
        resp = client.get("/api/v1/registry/deployments?status=stopped")
        assert resp.json()["total"] == 0

    def test_get_deployment_detail(self, client, running_deployment):
        """查询部署详情."""
        dep_id = running_deployment.deploymentId
        resp = client.get(f"/api/v1/registry/deployments/{dep_id}")
        assert resp.status_code == 200
        assert resp.json()["deploymentId"] == dep_id

    def test_get_deployment_not_found(self, client):
        """查询不存在部署应 404."""
        resp = client.get("/api/v1/registry/deployments/dep-nope")
        assert resp.status_code == 404

    def test_stop_deployment_success(self, client, running_deployment):
        """停止部署应返回 stopped 记录."""
        dep_id = running_deployment.deploymentId
        resp = client.delete(f"/api/v1/registry/deployments/{dep_id}")
        assert resp.status_code == 200
        assert resp.json()["status"] == "stopped"

    def test_stop_deployment_not_found(self, client):
        """停止不存在的部署应 404."""
        resp = client.delete("/api/v1/registry/deployments/dep-nope")
        assert resp.status_code == 404

    def test_update_deployment_scale(self, client, running_deployment):
        """更新部署副本数."""
        dep_id = running_deployment.deploymentId
        resp = client.put(
            f"/api/v1/registry/deployments/{dep_id}?replicas=3&gpuCount=2"
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["replicas"] == 3
        assert body["gpuCount"] == 2

    def test_update_deployment_not_found(self, client):
        """更新不存在的部署应 404."""
        resp = client.put(
            "/api/v1/registry/deployments/dep-nope?replicas=3"
        )
        assert resp.status_code == 404


# ============================================================
# API 路由：健康检查
# ============================================================
class TestHealthRoute:
    """健康检查路由测试."""

    def test_check_health_success(self, client, running_deployment):
        """健康检查应返回 HealthCheckResult."""
        dep_id = running_deployment.deploymentId
        resp = client.get(f"/api/v1/registry/deployments/{dep_id}/health")
        assert resp.status_code == 200
        body = resp.json()
        assert body["deploymentId"] == dep_id
        assert body["healthy"] is True

    def test_check_health_deployment_not_found(self, client):
        """不存在的部署健康检查应 404."""
        resp = client.get("/api/v1/registry/deployments/dep-nope/health")
        assert resp.status_code == 404


# ============================================================
# API 路由：服务统计
# ============================================================
class TestStatsRoute:
    """服务统计路由测试."""

    def test_stats_empty(self, client):
        """空仓库 + 空部署的统计."""
        resp = client.get("/api/v1/registry/stats")
        assert resp.status_code == 200
        body = resp.json()
        assert body["deployment"]["totalDeployments"] == 0
        assert body["models"]["totalModels"] == 0
        assert body["models"]["totalVersions"] == 0

    def test_stats_with_data(
        self, client, registered_model, running_deployment
    ):
        """有数据后的统计."""
        resp = client.get("/api/v1/registry/stats")
        assert resp.status_code == 200
        body = resp.json()
        assert body["deployment"]["totalDeployments"] == 1
        assert body["models"]["totalModels"] == 1
        assert body["models"]["totalVersions"] == 1