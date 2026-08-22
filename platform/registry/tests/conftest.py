"""pytest 公共 fixtures.

为模型仓库注册部署服务测试提供：
- FastAPI TestClient（含路由装配）
- DeploymentManager（mock 模式）
- HealthChecker（mock 模式）
- SimpleModelRegistry（空仓库）
- 预注册模型/部署记录
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

# 将 platform/registry 加入 sys.path，使 app 包可导入
_REGISTRY_ROOT = Path(__file__).resolve().parent.parent
if str(_REGISTRY_ROOT) not in sys.path:
    sys.path.insert(0, str(_REGISTRY_ROOT))

from app.api.registry_routes import SimpleModelRegistry, create_router
from app.core.deployment_manager import DeploymentManager
from app.core.health_checker import HealthChecker
from app.models import (
    DeploymentRecord,
    DeploymentStatus,
    DeployRequest,
    ModelRecord,
    ModelRegisterRequest,
)


# ============================================================
# 基础组件 fixtures
# ============================================================
@pytest.fixture
def deployment_manager() -> DeploymentManager:
    """mock 模式部署管理器."""
    return DeploymentManager(mock_mode=True)


@pytest.fixture
def health_checker() -> HealthChecker:
    """mock 模式健康检查器."""
    return HealthChecker(mock_mode=True, timeout=5)


@pytest.fixture
def model_registry() -> SimpleModelRegistry:
    """空模型仓库."""
    return SimpleModelRegistry()


@pytest.fixture
def app(
    deployment_manager: DeploymentManager,
    health_checker: HealthChecker,
    model_registry: SimpleModelRegistry,
) -> FastAPI:
    """装配好路由的 FastAPI 应用（不含 lifespan，避免全局状态污染）."""
    application = FastAPI()
    router = create_router(
        deployment_manager=deployment_manager,
        health_checker=health_checker,
        model_registry=model_registry,
    )
    application.include_router(router)
    return application


@pytest.fixture
def client(app: FastAPI) -> TestClient:
    """FastAPI 测试客户端."""
    return TestClient(app)


# ============================================================
# 数据 fixtures
# ============================================================
@pytest.fixture
def sample_register_request() -> ModelRegisterRequest:
    """示例模型注册请求."""
    return ModelRegisterRequest(
        modelName="qwen2-7b-finetuned",
        version="0.1.0",
        path="/data/models/qwen2-7b-finetuned",
        baseModel="Qwen/Qwen2-7B",
        framework="peft",
        method="lora",
        tenantId="tenant-001",
        metadata={"task": "text-classification", "dataset": "imdb"},
    )


@pytest.fixture
def sample_deploy_request() -> DeployRequest:
    """示例部署请求."""
    return DeployRequest(
        modelName="qwen2-7b-finetuned",
        version="0.1.0",
        runtime="vllm",
        port=8000,
        replicas=1,
        gpuCount=1,
        tenantId="tenant-001",
        env={"LOG_LEVEL": "INFO"},
    )


@pytest.fixture
def registered_model(
    model_registry: SimpleModelRegistry,
    sample_register_request: ModelRegisterRequest,
) -> ModelRecord:
    """已注册的模型记录."""
    return model_registry.register(sample_register_request)


@pytest.fixture
def running_deployment(
    deployment_manager: DeploymentManager,
    sample_deploy_request: DeployRequest,
) -> DeploymentRecord:
    """已创建的运行中部署."""
    return deployment_manager.deploy(sample_deploy_request)


@pytest.fixture
def stopped_deployment(
    deployment_manager: DeploymentManager,
    sample_deploy_request: DeployRequest,
) -> DeploymentRecord:
    """已停止的部署."""
    record = deployment_manager.deploy(sample_deploy_request)
    return deployment_manager.stop_deployment(record.deploymentId)