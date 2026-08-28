"""部署管理器单元测试.

覆盖 ``app.core.deployment_manager.DeploymentManager`` 全部 public 方法：
- ``deploy``：mock 模式 / 真实模式成功 / 真实模式失败
- ``get_deployment`` / ``list_deployments``：查询与过滤
- ``stop_deployment``：mock / 真实 / 终态幂等 / 不存在
- ``update_deployment``：扩缩容 / 不存在
- ``delete_deployment``：删除 / 先停后删 / 不存在
- ``stats``：统计聚合
- ``_start_container``：真实模式 docker 调用与失败
"""

from __future__ import annotations

from datetime import datetime, timezone
import subprocess
from unittest.mock import MagicMock, patch

from app.core.deployment_manager import DeploymentManager
from app.models import DeploymentRecord, DeploymentStatus, DeployRequest
import pytest


# ============================================================
# deploy()
# ============================================================
class TestDeploy:
    """DeploymentManager.deploy 测试."""

    def test_deploy_mock_mode_running(self, sample_deploy_request):
        """mock 模式部署应直接进入 running 状态并填充 endpoint/containerId."""
        mgr = DeploymentManager(mock_mode=True)
        rec = mgr.deploy(sample_deploy_request)
        assert rec.status == DeploymentStatus.RUNNING
        assert rec.healthy is True
        assert rec.endpoint == f"http://localhost:{sample_deploy_request.port}"
        assert rec.containerId.startswith("mock-container-")
        assert rec.deploymentId.startswith("dep-")

    def test_deploy_unique_ids(self, sample_deploy_request):
        """多次部署应生成不同 deploymentId."""
        mgr = DeploymentManager(mock_mode=True)
        r1 = mgr.deploy(sample_deploy_request)
        r2 = mgr.deploy(sample_deploy_request)
        assert r1.deploymentId != r2.deploymentId

    def test_deploy_real_mode_success(self, sample_deploy_request):
        """真实模式 _start_container 成功时应进入 running."""
        mgr = DeploymentManager(mock_mode=False)
        with patch.object(mgr, "_start_container", return_value=None) as mock_start:
            rec = mgr.deploy(sample_deploy_request)
        mock_start.assert_called_once()
        assert rec.status == DeploymentStatus.RUNNING
        assert rec.healthy is True

    def test_deploy_real_mode_failure(self, sample_deploy_request):
        """真实模式 _start_container 抛异常时应进入 failed 并记录 error."""
        mgr = DeploymentManager(mock_mode=False)
        with patch.object(
            mgr,
            "_start_container",
            side_effect=RuntimeError("docker daemon down"),
        ):
            rec = mgr.deploy(sample_deploy_request)
        assert rec.status == DeploymentStatus.FAILED
        assert rec.error == "docker daemon down"
        assert rec.finishedAt is not None
        assert rec.healthy is False


# ============================================================
# get_deployment() / list_deployments()
# ============================================================
class TestQueryDeployments:
    """查询部署测试."""

    def test_get_deployment_found(self, running_deployment):
        """已存在部署应能取回."""
        mgr = DeploymentManager(mock_mode=True)
        # running_deployment 来自独立 fixture 的独立 manager，这里自建验证
        mgr2 = DeploymentManager(mock_mode=True)
        req = DeployRequest(modelName="m")
        rec = mgr2.deploy(req)
        got = mgr2.get_deployment(rec.deploymentId)
        assert got is not None
        assert got.deploymentId == rec.deploymentId

    def test_get_deployment_not_found(self):
        """不存在的部署返回 None."""
        mgr = DeploymentManager(mock_mode=True)
        assert mgr.get_deployment("nope") is None

    def test_list_deployments_empty(self):
        """空列表."""
        mgr = DeploymentManager(mock_mode=True)
        assert mgr.list_deployments() == []

    def test_list_deployments_all(self):
        """列出全部部署."""
        mgr = DeploymentManager(mock_mode=True)
        mgr.deploy(DeployRequest(modelName="a"))
        mgr.deploy(DeployRequest(modelName="b"))
        deps = mgr.list_deployments()
        assert len(deps) == 2

    def test_list_deployments_filter_status(self):
        """按 status 过滤."""
        mgr = DeploymentManager(mock_mode=True)
        r1 = mgr.deploy(DeployRequest(modelName="a"))
        mgr.stop_deployment(r1.deploymentId)
        mgr.deploy(DeployRequest(modelName="b"))
        running = mgr.list_deployments(status=DeploymentStatus.RUNNING)
        stopped = mgr.list_deployments(status=DeploymentStatus.STOPPED)
        assert len(running) == 1
        assert len(stopped) == 1

    def test_list_deployments_filter_tenant(self):
        """按 tenantId 过滤."""
        mgr = DeploymentManager(mock_mode=True)
        mgr.deploy(DeployRequest(modelName="a", tenantId="t1"))
        mgr.deploy(DeployRequest(modelName="b", tenantId="t2"))
        assert len(mgr.list_deployments(tenantId="t1")) == 1
        assert len(mgr.list_deployments(tenantId="t2")) == 1
        assert len(mgr.list_deployments(tenantId="t3")) == 0

    def test_list_deployments_sorted_by_created_at_desc(self):
        """列表按 createdAt 倒序."""
        mgr = DeploymentManager(mock_mode=True)
        r1 = mgr.deploy(DeployRequest(modelName="a"))
        # 推进时间确保排序可观测
        r1.createdAt = datetime(2024, 1, 1, tzinfo=timezone.utc)
        r2 = mgr.deploy(DeployRequest(modelName="b"))
        r2.createdAt = datetime(2024, 1, 2, tzinfo=timezone.utc)
        deps = mgr.list_deployments()
        assert deps[0].modelName == "b"
        assert deps[1].modelName == "a"


# ============================================================
# stop_deployment()
# ============================================================
class TestStopDeployment:
    """停止部署测试."""

    def test_stop_mock_mode(self):
        """mock 模式停止应置为 stopped."""
        mgr = DeploymentManager(mock_mode=True)
        rec = mgr.deploy(DeployRequest(modelName="m"))
        stopped = mgr.stop_deployment(rec.deploymentId)
        assert stopped.status == DeploymentStatus.STOPPED
        assert stopped.healthy is False
        assert stopped.finishedAt is not None

    def test_stop_not_found(self):
        """停止不存在的部署返回 None."""
        mgr = DeploymentManager(mock_mode=True)
        assert mgr.stop_deployment("nope") is None

    def test_stop_terminal_idempotent(self):
        """对终态部署再次停止应直接返回（幂等）."""
        mgr = DeploymentManager(mock_mode=True)
        rec = mgr.deploy(DeployRequest(modelName="m"))
        first_stop = mgr.stop_deployment(rec.deploymentId)
        assert first_stop.status == DeploymentStatus.STOPPED
        second_stop = mgr.stop_deployment(rec.deploymentId)
        assert second_stop.status == DeploymentStatus.STOPPED
        # finishedAt 不变（幂等）
        assert second_stop.finishedAt == first_stop.finishedAt

    def test_stop_real_mode_calls_docker_stop(self):
        """真实模式停止应调用 docker stop."""
        mgr = DeploymentManager(mock_mode=False)
        # mock _start_container 使 deploy 成功进入 RUNNING
        with patch.object(mgr, "_start_container", return_value=None):
            rec = mgr.deploy(DeployRequest(modelName="m"))
        # 模拟真实容器 id
        rec.containerId = "real-container-123"
        with patch("subprocess.run", return_value=MagicMock(returncode=0)) as mock_run:
            stopped = mgr.stop_deployment(rec.deploymentId)
        mock_run.assert_called_once()
        args = mock_run.call_args[0][0]
        assert args[0] == "docker" and args[1] == "stop"
        assert "real-container-123" in args
        assert stopped.status == DeploymentStatus.STOPPED

    def test_stop_real_mode_docker_failure_does_not_block(self):
        """真实模式 docker stop 失败不应阻塞状态更新."""
        mgr = DeploymentManager(mock_mode=False)
        # mock _start_container 使 deploy 成功进入 RUNNING
        with patch.object(mgr, "_start_container", return_value=None):
            rec = mgr.deploy(DeployRequest(modelName="m"))
        rec.containerId = "real-container-456"
        with patch(
            "subprocess.run",
            side_effect=subprocess.TimeoutExpired(cmd="docker", timeout=30),
        ):
            stopped = mgr.stop_deployment(rec.deploymentId)
        # 仍应置为 stopped（仅记录 warning）
        assert stopped.status == DeploymentStatus.STOPPED


# ============================================================
# update_deployment()
# ============================================================
class TestUpdateDeployment:
    """更新部署（扩缩容）测试."""

    def test_update_replicas(self):
        """更新副本数."""
        mgr = DeploymentManager(mock_mode=True)
        rec = mgr.deploy(DeployRequest(modelName="m", replicas=1))
        updated = mgr.update_deployment(rec.deploymentId, replicas=4)
        assert updated.replicas == 4
        assert updated.status == DeploymentStatus.RUNNING

    def test_update_gpu_count(self):
        """更新 GPU 数."""
        mgr = DeploymentManager(mock_mode=True)
        rec = mgr.deploy(DeployRequest(modelName="m", gpuCount=1))
        updated = mgr.update_deployment(rec.deploymentId, gpu_count=2)
        assert updated.gpuCount == 2

    def test_update_no_change_when_zero(self):
        """replicas=0/gpu_count=0 不改变现有值."""
        mgr = DeploymentManager(mock_mode=True)
        rec = mgr.deploy(DeployRequest(modelName="m", replicas=3, gpuCount=2))
        updated = mgr.update_deployment(rec.deploymentId, replicas=0, gpu_count=0)
        assert updated.replicas == 3
        assert updated.gpuCount == 2

    def test_update_not_found(self):
        """更新不存在的部署返回 None."""
        mgr = DeploymentManager(mock_mode=True)
        assert mgr.update_deployment("nope", replicas=2) is None


# ============================================================
# delete_deployment()
# ============================================================
class TestDeleteDeployment:
    """删除部署记录测试."""

    def test_delete_terminal(self):
        """删除终态部署应直接移除."""
        mgr = DeploymentManager(mock_mode=True)
        rec = mgr.deploy(DeployRequest(modelName="m"))
        mgr.stop_deployment(rec.deploymentId)
        assert mgr.delete_deployment(rec.deploymentId) is True
        assert mgr.get_deployment(rec.deploymentId) is None

    def test_delete_running_stops_first(self):
        """删除运行中部署应先停止再删除."""
        mgr = DeploymentManager(mock_mode=True)
        rec = mgr.deploy(DeployRequest(modelName="m"))
        assert mgr.delete_deployment(rec.deploymentId) is True
        assert mgr.get_deployment(rec.deploymentId) is None

    def test_delete_not_found(self):
        """删除不存在的部署返回 False."""
        mgr = DeploymentManager(mock_mode=True)
        assert mgr.delete_deployment("nope") is False


# ============================================================
# stats()
# ============================================================
class TestStats:
    """统计信息测试."""

    def test_stats_empty(self):
        """空统计."""
        mgr = DeploymentManager(mock_mode=True)
        s = mgr.stats()
        assert s["totalDeployments"] == 0
        assert s["byStatus"] == {}

    def test_stats_with_deployments(self):
        """含部署的统计."""
        mgr = DeploymentManager(mock_mode=True)
        r1 = mgr.deploy(DeployRequest(modelName="a"))
        mgr.deploy(DeployRequest(modelName="b"))
        mgr.stop_deployment(r1.deploymentId)
        s = mgr.stats()
        assert s["totalDeployments"] == 2
        assert s["byStatus"].get("running") == 1
        assert s["byStatus"].get("stopped") == 1


# ============================================================
# _start_container()（真实模式 docker 调用）
# ============================================================
class TestStartContainer:
    """_start_container 真实 docker 调用测试."""

    def test_start_container_success(self):
        """docker run 成功应填充 containerId 与 endpoint."""
        mgr = DeploymentManager(mock_mode=False)
        rec = DeploymentRecord(
            deploymentId="dep-x",
            modelName="m",
            version="0.1",
            runtime="vllm",
            port=8000,
            replicas=1,
            gpuCount=1,
            tenantId="t",
        )
        mock_result = MagicMock()
        mock_result.returncode = 0
        mock_result.stdout = "abc123container\n"
        with patch("subprocess.run", return_value=mock_result) as mock_run:
            mgr._start_container(rec)
        assert rec.containerId == "abc123container"
        assert rec.endpoint == "http://localhost:8000"
        cmd = mock_run.call_args[0][0]
        assert cmd[0] == "docker" and cmd[1] == "run"

    def test_start_container_failure_raises(self):
        """docker run 失败应抛 RuntimeError."""
        mgr = DeploymentManager(mock_mode=False)
        rec = DeploymentRecord(
            deploymentId="dep-y",
            modelName="m",
            version="0.1",
            runtime="vllm",
            port=8000,
            replicas=1,
            gpuCount=1,
            tenantId="t",
        )
        mock_result = MagicMock()
        mock_result.returncode = 1
        mock_result.stderr = "image not found"
        with patch("subprocess.run", return_value=mock_result):
            with pytest.raises(RuntimeError, match="docker run 失败"):
                mgr._start_container(rec)

    def test_start_container_image_map(self):
        """不同 runtime 应映射到不同镜像."""
        mgr = DeploymentManager(mock_mode=False)
        cases = {
            "vllm": "vllm/vllm-openai:latest",
            "triton": "nvcr.io/nvidia/tritonserver:24.05-py3",
            "simple": "python:3.11-slim",
            "unknown": "python:3.11-slim",  # 默认
        }
        for runtime, expected_image in cases.items():
            rec = DeploymentRecord(
                deploymentId=f"dep-{runtime}",
                modelName="m",
                version="0.1",
                runtime=runtime,
                port=8000,
                replicas=1,
                gpuCount=1,
                tenantId="t",
            )
            mock_result = MagicMock()
            mock_result.returncode = 0
            mock_result.stdout = "cid\n"
            with patch("subprocess.run", return_value=mock_result) as mock_run:
                mgr._start_container(rec)
            cmd = mock_run.call_args[0][0]
            assert cmd[-1] == expected_image, f"runtime={runtime}"
