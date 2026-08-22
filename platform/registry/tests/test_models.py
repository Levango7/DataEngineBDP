"""数据模型单元测试.

覆盖 ``app.models`` 中所有 Pydantic 模型与枚举：
- ``DeploymentStatus`` 枚举与 ``TERMINAL_STATUSES`` 集合
- ``ModelRegisterRequest`` / ``ModelRecord`` / ``ModelListResponse``
- ``DeployRequest`` / ``DeploymentRecord`` / ``DeploymentListResponse``
- ``HealthCheckResult`` / ``HealthResponse``
- 边界校验、默认值、``is_terminal`` / ``touch`` 行为
"""

from __future__ import annotations

from datetime import datetime, timezone, timedelta

import pytest
from pydantic import ValidationError

from app.models import (
    TERMINAL_STATUSES,
    DeploymentListResponse,
    DeploymentRecord,
    DeploymentStatus,
    DeployRequest,
    HealthCheckResult,
    HealthResponse,
    ModelListResponse,
    ModelRecord,
    ModelRegisterRequest,
)


# ============================================================
# DeploymentStatus 枚举
# ============================================================
class TestDeploymentStatus:
    """部署状态枚举测试."""

    def test_status_values(self):
        """枚举值应与字符串保持一致（str, Enum 混入）."""
        assert DeploymentStatus.PENDING == "pending"
        assert DeploymentStatus.RUNNING == "running"
        assert DeploymentStatus.STOPPED == "stopped"
        assert DeploymentStatus.FAILED == "failed"
        assert DeploymentStatus.UPDATING == "updating"

    def test_terminal_statuses_contains_stopped_and_failed(self):
        """终态集合应包含 STOPPED 与 FAILED，不含 RUNNING/PENDING/UPDATING."""
        assert DeploymentStatus.STOPPED in TERMINAL_STATUSES
        assert DeploymentStatus.FAILED in TERMINAL_STATUSES
        assert DeploymentStatus.RUNNING not in TERMINAL_STATUSES
        assert DeploymentStatus.PENDING not in TERMINAL_STATUSES
        assert DeploymentStatus.UPDATING not in TERMINAL_STATUSES

    def test_terminal_statuses_is_frozen(self):
        """``TERMINAL_STATUSES`` 应为不可变集合."""
        assert isinstance(TERMINAL_STATUSES, frozenset)


# ============================================================
# ModelRegisterRequest
# ============================================================
class TestModelRegisterRequest:
    """模型注册请求模型测试."""

    def test_minimal_valid_request(self):
        """仅提供必填字段应可创建，其余字段取默认值."""
        req = ModelRegisterRequest(
            modelName="qwen2-7b", path="/data/models/qwen2-7b"
        )
        assert req.modelName == "qwen2-7b"
        assert req.path == "/data/models/qwen2-7b"
        assert req.version == "0.1.0"
        assert req.baseModel == ""
        assert req.framework == "peft"
        assert req.method == "lora"
        assert req.tenantId == "default"
        assert req.metadata == {}

    def test_full_request_with_metadata(self):
        """完整字段 + metadata 应正确解析."""
        req = ModelRegisterRequest(
            modelName="llama3-8b",
            version="1.2.0",
            path="/data/m/llama3",
            baseModel="meta-llama/Llama-3-8B",
            framework="peft",
            method="qlora",
            tenantId="tenant-99",
            metadata={"task": "ner", "epochs": 3},
        )
        assert req.version == "1.2.0"
        assert req.metadata["epochs"] == 3

    def test_empty_model_name_rejected(self):
        """modelName 为空字符串应触发校验错误（min_length=1）."""
        with pytest.raises(ValidationError) as exc:
            ModelRegisterRequest(modelName="", path="/x")
        assert "min_length" in str(exc.value) or "String should have at least" in str(exc.value)

    def test_model_name_too_long_rejected(self):
        """modelName 超过 128 字符应触发校验错误."""
        with pytest.raises(ValidationError):
            ModelRegisterRequest(modelName="a" * 129, path="/x")

    def test_metadata_default_is_independent(self):
        """metadata 默认值应为独立字典（不共享引用）."""
        a = ModelRegisterRequest(modelName="a", path="/a")
        b = ModelRegisterRequest(modelName="b", path="/b")
        a.metadata["k"] = "v"
        assert "k" not in b.metadata


# ============================================================
# ModelRecord
# ============================================================
class TestModelRecord:
    """模型记录模型测试."""

    def test_default_created_at_is_utc(self):
        """createdAt 默认值应为带时区的 UTC 时间."""
        rec = ModelRecord(modelName="m", version="0.1", path="/p")
        assert rec.createdAt.tzinfo is not None
        # 偏差不超过 5 秒
        now = datetime.now(timezone.utc)
        assert abs((now - rec.createdAt).total_seconds()) < 5

    def test_is_active_default_true(self):
        """isActive 默认为 True."""
        rec = ModelRecord(modelName="m", version="0.1", path="/p")
        assert rec.isActive is True

    def test_metadata_default_independent(self):
        """metadata 默认值独立."""
        a = ModelRecord(modelName="a", version="0.1", path="/a")
        b = ModelRecord(modelName="b", version="0.1", path="/b")
        a.metadata["k"] = 1
        assert "k" not in b.metadata


# ============================================================
# ModelListResponse
# ============================================================
class TestModelListResponse:
    """模型列表响应测试."""

    def test_empty_response(self):
        """空响应 total=0, models=[]."""
        resp = ModelListResponse(total=0)
        assert resp.total == 0
        assert resp.models == []

    def test_response_with_models(self):
        """含模型的响应应正确承载列表."""
        m = ModelRecord(modelName="m", version="0.1", path="/p")
        resp = ModelListResponse(total=1, models=[m])
        assert resp.total == 1
        assert resp.models[0].modelName == "m"

    def test_negative_total_rejected(self):
        """total 为负数应触发校验错误（ge=0）."""
        with pytest.raises(ValidationError):
            ModelListResponse(total=-1)


# ============================================================
# DeployRequest
# ============================================================
class TestDeployRequest:
    """部署请求模型测试."""

    def test_minimal_request_defaults(self):
        """仅提供 modelName 应可创建，其余取默认值."""
        req = DeployRequest(modelName="m")
        assert req.version == ""
        assert req.runtime == "vllm"
        assert req.port == 8000
        assert req.replicas == 1
        assert req.gpuCount == 1
        assert req.tenantId == "default"
        assert req.env == {}

    def test_port_out_of_range_rejected(self):
        """port < 1024 或 > 65535 应被拒绝."""
        with pytest.raises(ValidationError):
            DeployRequest(modelName="m", port=80)
        with pytest.raises(ValidationError):
            DeployRequest(modelName="m", port=70000)

    def test_replicas_out_of_range_rejected(self):
        """replicas < 1 或 > 10 应被拒绝."""
        with pytest.raises(ValidationError):
            DeployRequest(modelName="m", replicas=0)
        with pytest.raises(ValidationError):
            DeployRequest(modelName="m", replicas=11)

    def test_gpu_count_out_of_range_rejected(self):
        """gpuCount < 1 或 > 8 应被拒绝."""
        with pytest.raises(ValidationError):
            DeployRequest(modelName="m", gpuCount=0)
        with pytest.raises(ValidationError):
            DeployRequest(modelName="m", gpuCount=9)

    def test_env_default_independent(self):
        """env 默认值应为独立字典."""
        a = DeployRequest(modelName="a")
        b = DeployRequest(modelName="b")
        a.env["K"] = "V"
        assert "K" not in b.env


# ============================================================
# DeploymentRecord
# ============================================================
class TestDeploymentRecord:
    """部署记录模型测试."""

    def _make_record(self, status: DeploymentStatus = DeploymentStatus.RUNNING) -> DeploymentRecord:
        return DeploymentRecord(
            deploymentId="dep-1",
            modelName="m",
            version="0.1",
            runtime="vllm",
            port=8000,
            replicas=1,
            gpuCount=1,
            tenantId="default",
            status=status,
        )

    def test_default_status_pending(self):
        """未指定 status 时默认为 PENDING."""
        rec = DeploymentRecord(
            deploymentId="d", modelName="m", version="0.1",
            runtime="vllm", port=8000, replicas=1, gpuCount=1, tenantId="t",
        )
        assert rec.status == DeploymentStatus.PENDING

    def test_is_terminal_true_for_stopped_and_failed(self):
        """STOPPED / FAILED 为终态."""
        assert self._make_record(DeploymentStatus.STOPPED).is_terminal() is True
        assert self._make_record(DeploymentStatus.FAILED).is_terminal() is True

    def test_is_terminal_false_for_non_terminal(self):
        """RUNNING / PENDING / UPDATING 非终态."""
        assert self._make_record(DeploymentStatus.RUNNING).is_terminal() is False
        assert self._make_record(DeploymentStatus.PENDING).is_terminal() is False
        assert self._make_record(DeploymentStatus.UPDATING).is_terminal() is False

    def test_touch_updates_updated_at(self):
        """touch() 应将 updatedAt 推到当前时间."""
        rec = self._make_record()
        old_updated = rec.updatedAt
        # 确保时间推进（避免极快执行导致时间相同）
        rec.updatedAt = old_updated - timedelta(seconds=10)
        rec.touch()
        assert rec.updatedAt > old_updated - timedelta(seconds=10)
        now = datetime.now(timezone.utc)
        assert abs((now - rec.updatedAt).total_seconds()) < 5

    def test_optional_fields_default_none(self):
        """endpoint / containerId / finishedAt / error 默认 None."""
        rec = self._make_record()
        assert rec.endpoint is None
        assert rec.containerId is None
        assert rec.finishedAt is None
        assert rec.error is None

    def test_healthy_default_false(self):
        """healthy 默认 False."""
        rec = self._make_record()
        assert rec.healthy is False


# ============================================================
# DeploymentListResponse
# ============================================================
class TestDeploymentListResponse:
    """部署列表响应测试."""

    def test_empty_response(self):
        """空响应."""
        resp = DeploymentListResponse(total=0)
        assert resp.total == 0
        assert resp.deployments == []

    def test_negative_total_rejected(self):
        """total 为负应被拒绝."""
        with pytest.raises(ValidationError):
            DeploymentListResponse(total=-1)


# ============================================================
# HealthCheckResult
# ============================================================
class TestHealthCheckResult:
    """健康检查结果测试."""

    def test_minimal_result(self):
        """仅提供 deploymentId + healthy 应可创建."""
        r = HealthCheckResult(deploymentId="dep-1", healthy=True)
        assert r.deploymentId == "dep-1"
        assert r.healthy is True
        assert r.latencyMs == 0.0
        assert r.endpoint is None
        assert r.error is None
        assert r.checkedAt.tzinfo is not None

    def test_full_result(self):
        """完整字段."""
        r = HealthCheckResult(
            deploymentId="dep-1",
            healthy=False,
            endpoint="http://localhost:8000",
            latencyMs=123.45,
            error="timeout",
        )
        assert r.healthy is False
        assert r.latencyMs == 123.45
        assert r.error == "timeout"


# ============================================================
# HealthResponse
# ============================================================
class TestHealthResponse:
    """服务健康响应测试."""

    def test_defaults(self):
        """默认值."""
        r = HealthResponse()
        assert r.status == "UP"
        assert r.service == "model-registry"
        assert r.version == "0.1.0"
        assert r.mockMode is True

    def test_override(self):
        """覆写字段."""
        r = HealthResponse(status="DOWN", mockMode=False)
        assert r.status == "DOWN"
        assert r.mockMode is False