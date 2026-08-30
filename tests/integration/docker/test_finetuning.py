"""微调任务引擎集成测试.

被测对象：model-finetuning 服务（FastAPI，端口 8095）。

支持两种运行模式：
1. **TestClient 模式（默认）**：直接使用 FastAPI TestClient，
   无需启动 Docker 容器，Mock 模式下零外部依赖即可运行。
   适用于本地验证与 CI。
2. **HTTP 模式**：通过环境变量 ``FINETUNE_URL`` 指定服务地址，
   对运行中的服务（Docker 容器或远程）发起 HTTP 请求。
   适用于 Docker 集成测试。

覆盖范围：
- 健康检查
- 任务提交（POST /api/v1/finetune/tasks）
- 任务查询（GET /api/v1/finetune/tasks/{id}）
- 任务列表（GET /api/v1/finetune/tasks）
- 任务终止（DELETE /api/v1/finetune/tasks/{id}）
- 任务日志（GET /api/v1/finetune/tasks/{id}/logs）
- 三种微调方式（LoRA/QLoRA/全参）配置验证
- 三个框架适配器（LLaMA-Factory/PEFT/DeepSpeed）
- GPU 节点池调度
- 适配器与节点信息端点

运行方式：
    # TestClient 模式（默认，无需启动服务）
    pytest tests/integration/docker/test_finetuning.py -v

    # HTTP 模式（需先启动服务）
    FINETUNE_URL=http://localhost:8095 \
      pytest tests/integration/docker/test_finetuning.py -v
"""
from __future__ import annotations

import os
import time
from typing import Any

import pytest


# ============================================================
# 服务地址与模式判定
# ============================================================
FINETUNE_URL = os.environ.get("FINETUNE_URL", "")
HTTP_MODE = bool(FINETUNE_URL)

# 微调引擎项目根目录（用于 TestClient 模式导入）
_PROJECT_ROOT = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..", "platform", "model-finetuning")
)


# ============================================================
# 公共 fixtures
# ============================================================
@pytest.fixture(scope="session")
def client():
    """提供测试客户端.

    - TestClient 模式：返回 FastAPI TestClient，直接调用 ASGI 应用。
    - HTTP 模式：返回 None，测试通过 ``http_get`` / ``http_post`` 辅助函数调用。
    """
    if HTTP_MODE:
        yield None
    else:
        # TestClient 模式：导入微调引擎应用
        import sys

        # 清理已加载的 app / app.* / main 模块，防止 app 包命名冲突
        # （多个组件都有 app 包，需确保从 model-finetuning 导入）
        for mod in list(sys.modules.keys()):
            if mod == "app" or mod.startswith("app.") or mod == "main":
                del sys.modules[mod]

        # 强制将 _PROJECT_ROOT 放到 sys.path[0]，确保 app 包从 model-finetuning 导入
        if _PROJECT_ROOT in sys.path:
            sys.path.remove(_PROJECT_ROOT)
        sys.path.insert(0, _PROJECT_ROOT)

        # 设置 Mock 模式环境变量
        os.environ["FINETUNE_MOCK_MODE"] = "true"
        os.environ["FINETUNE_WORK_DIR"] = os.path.join(
            _PROJECT_ROOT, "..", "..", ".tmp", "finetune-test"
        )

        from fastapi.testclient import TestClient

        import main

        with TestClient(main.app) as tc:
            yield tc


def _request(method: str, path: str, client: Any, **kwargs):
    """统一请求封装，兼容 TestClient 与 HTTP 模式."""
    if HTTP_MODE:
        import requests

        url = FINETUNE_URL.rstrip("/") + path
        return requests.request(method, url, timeout=15, **kwargs)
    return getattr(client, method.lower())(path, **kwargs)


# ============================================================
# 测试数据工厂
# ============================================================
def _make_lora_request(taskName: str = "test-lora") -> dict:
    """构造 LoRA 微调任务请求."""
    return {
        "taskName": taskName,
        "baseModel": "meta-llama/Llama-2-7b-hf",
        "dataset": {
            "name": "alpaca-zh",
            "path": "/data/datasets/alpaca-zh.json",
            "format": "alpaca",
        },
        "config": {
            "method": "lora",
            "framework": "peft",
            "lora": {
                "rank": 16,
                "alpha": 32,
                "dropout": 0.05,
                "targetModules": ["q_proj", "k_proj", "v_proj", "o_proj"],
            },
            "hyperparams": {
                "epochs": 1,
                "batchSize": 4,
                "learningRate": 0.0002,
                "maxSeqLength": 1024,
                "loggingSteps": 5,
            },
        },
        "gpu": {"count": 1, "type": "any", "memoryGB": 0},
        "outputDir": "/tmp/finetune-test/output",
        "tenantId": "test-tenant",
        "description": "集成测试 LoRA 任务",
    }


def _make_qlora_request(taskName: str = "test-qlora") -> dict:
    """构造 QLoRA 微调任务请求."""
    return {
        "taskName": taskName,
        "baseModel": "meta-llama/Llama-2-13b-hf",
        "dataset": {
            "name": "alpaca-zh",
            "path": "/data/datasets/alpaca-zh.json",
            "format": "alpaca",
        },
        "config": {
            "method": "qlora",
            "framework": "peft",
            "qlora": {
                "quantization": "4bit",
                "computeDtype": "bfloat16",
                "doubleQuantization": True,
                "lora": {
                    "rank": 32,
                    "alpha": 64,
                    "dropout": 0.05,
                    "targetModules": ["q_proj", "v_proj"],
                },
            },
            "hyperparams": {
                "epochs": 1,
                "batchSize": 2,
                "learningRate": 0.0001,
                "maxSeqLength": 2048,
                "loggingSteps": 5,
            },
        },
        "gpu": {"count": 1, "type": "any"},
        "outputDir": "/tmp/finetune-test/output",
        "tenantId": "test-tenant",
    }


def _make_full_request(taskName: str = "test-full") -> dict:
    """构造全参微调任务请求."""
    return {
        "taskName": taskName,
        "baseModel": "meta-llama/Llama-2-7b-hf",
        "dataset": {
            "name": "alpaca-zh",
            "path": "/data/datasets/alpaca-zh.json",
            "format": "alpaca",
        },
        "config": {
            "method": "full",
            "framework": "deepspeed",
            "full": {
                "gradientCheckpointing": True,
                "freezeEmbeddings": False,
            },
            "deepspeed": {
                "stage": "zero3",
                "tensorParallelSize": 1,
                "dataParallelSize": 1,
                "offloadOptimizer": True,
                "offloadParam": True,
            },
            "hyperparams": {
                "epochs": 1,
                "batchSize": 2,
                "learningRate": 0.00005,
                "maxSeqLength": 2048,
                "loggingSteps": 5,
                "bf16": True,
                "fp16": False,
            },
        },
        "gpu": {"count": 1, "type": "any"},
        "outputDir": "/tmp/finetune-test/output",
        "tenantId": "test-tenant",
    }


# ============================================================
# 健康检查
# ============================================================
class TestHealth:
    """健康检查测试."""

    def test_health(self, client):
        """验证健康检查端点返回 200 且 status=UP."""
        resp = _request("GET", "/api/v1/health", client)
        assert resp.status_code == 200
        body = resp.json()
        assert body.get("status") == "UP"
        assert body.get("service") == "model-finetuning"

    def test_root(self, client):
        """验证根路径返回服务信息."""
        resp = _request("GET", "/", client)
        assert resp.status_code == 200
        body = resp.json()
        assert "service" in body
        assert "docs" in body


# ============================================================
# 任务提交 API
# ============================================================
class TestSubmitTask:
    """任务提交 API 测试."""

    def test_submit_lora_task(self, client):
        """验证提交 LoRA 任务返回 201 且包含 taskId."""
        payload = _make_lora_request("submit-lora-test")
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201, f"提交失败: {resp.text}"
        body = resp.json()
        assert "taskId" in body
        assert body["taskName"] == payload["taskName"]
        assert body["status"] == "running"
        assert body["method"] == "lora"
        assert body["framework"] == "peft"
        assert body["baseModel"] == payload["baseModel"]
        assert body["assignedNode"] is not None
        assert len(body["assignedGPUs"]) == 1

    def test_submit_qlora_task(self, client):
        """验证提交 QLoRA 任务返回 201."""
        payload = _make_qlora_request("submit-qlora-test")
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201, f"提交失败: {resp.text}"
        body = resp.json()
        assert body["method"] == "qlora"
        assert body["framework"] == "peft"

    def test_submit_full_task(self, client):
        """验证提交全参微调任务返回 201."""
        payload = _make_full_request("submit-full-test")
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201, f"提交失败: {resp.text}"
        body = resp.json()
        assert body["method"] == "full"
        assert body["framework"] == "deepspeed"

    def test_submit_task_missing_required_field(self, client):
        """验证缺少必填字段返回 422."""
        payload = {"taskName": "missing-model"}  # 缺少 baseModel/dataset/config
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 422

    def test_submit_task_invalid_lora_rank(self, client):
        """验证 LoRA rank 非法值返回 422."""
        payload = _make_lora_request("invalid-rank")
        payload["config"]["lora"]["rank"] = 0  # rank 必须 >= 1
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 422


# ============================================================
# 任务查询 API
# ============================================================
class TestQueryTask:
    """任务查询 API 测试."""

    def test_get_task(self, client):
        """验证查询任务详情返回 200."""
        # 先提交任务
        payload = _make_lora_request("query-get-test")
        create_resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert create_resp.status_code == 201
        task_id = create_resp.json()["taskId"]

        # 查询
        resp = _request("GET", f"/api/v1/finetune/tasks/{task_id}", client)
        assert resp.status_code == 200
        body = resp.json()
        assert body["taskId"] == task_id
        assert body["taskName"] == payload["taskName"]

    def test_get_task_not_found(self, client):
        """验证查询不存在的任务返回 404."""
        resp = _request("GET", "/api/v1/finetune/tasks/non-existent-id", client)
        assert resp.status_code == 404

    def test_list_tasks(self, client):
        """验证任务列表返回 200 且包含 data 与 total."""
        # 先提交一个任务确保列表非空
        payload = _make_lora_request("list-test")
        _request("POST", "/api/v1/finetune/tasks", client, json=payload)

        resp = _request("GET", "/api/v1/finetune/tasks", client)
        assert resp.status_code == 200
        body = resp.json()
        assert "total" in body
        assert "data" in body
        assert isinstance(body["data"], list)
        assert body["total"] >= 1

    def test_list_tasks_with_pagination(self, client):
        """验证任务列表分页参数."""
        resp = _request(
            "GET", "/api/v1/finetune/tasks", client, params={"limit": 5, "offset": 0}
        )
        assert resp.status_code == 200
        body = resp.json()
        assert len(body["data"]) <= 5

    def test_list_tasks_filter_by_status(self, client):
        """验证按状态过滤任务列表."""
        resp = _request(
            "GET",
            "/api/v1/finetune/tasks",
            client,
            params={"status": "running"},
        )
        assert resp.status_code == 200
        body = resp.json()
        for item in body["data"]:
            assert item["status"] == "running"


# ============================================================
# 任务终止 API
# ============================================================
class TestTerminateTask:
    """任务终止 API 测试."""

    def test_terminate_task(self, client):
        """验证终止运行中任务返回 200 且状态为 terminated."""
        payload = _make_lora_request("terminate-test")
        create_resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert create_resp.status_code == 201
        task_id = create_resp.json()["taskId"]

        # 终止
        resp = _request("DELETE", f"/api/v1/finetune/tasks/{task_id}", client)
        assert resp.status_code == 200
        body = resp.json()
        assert body["taskId"] == task_id
        assert body["status"] == "terminated"

    def test_terminate_task_not_found(self, client):
        """验证终止不存在的任务返回 404."""
        resp = _request("DELETE", "/api/v1/finetune/tasks/non-existent", client)
        assert resp.status_code == 404

    def test_terminate_already_terminated(self, client):
        """验证终止已终止任务仍返回 200（幂等）."""
        payload = _make_lora_request("terminate-idempotent")
        create_resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        task_id = create_resp.json()["taskId"]

        # 第一次终止
        resp1 = _request("DELETE", f"/api/v1/finetune/tasks/{task_id}", client)
        assert resp1.status_code == 200
        # 第二次终止（幂等）
        resp2 = _request("DELETE", f"/api/v1/finetune/tasks/{task_id}", client)
        assert resp2.status_code == 200
        assert resp2.json()["status"] == "terminated"


# ============================================================
# 任务日志 API
# ============================================================
class TestTaskLogs:
    """任务日志 API 测试."""

    def test_get_logs(self, client):
        """验证查询任务日志返回 200 且包含 entries."""
        payload = _make_lora_request("logs-test")
        create_resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert create_resp.status_code == 201
        task_id = create_resp.json()["taskId"]

        # 查询日志
        resp = _request("GET", f"/api/v1/finetune/tasks/{task_id}/logs", client)
        assert resp.status_code == 200
        body = resp.json()
        assert body["taskId"] == task_id
        assert "entries" in body
        assert isinstance(body["entries"], list)
        # Mock 模式下应生成日志
        assert body["total"] > 0

    def test_get_logs_with_tail(self, client):
        """验证 tail 参数限制返回行数."""
        payload = _make_lora_request("logs-tail-test")
        create_resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        task_id = create_resp.json()["taskId"]

        resp = _request(
            "GET",
            f"/api/v1/finetune/tasks/{task_id}/logs",
            client,
            params={"tail": 5},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] <= 5

    def test_get_logs_not_found(self, client):
        """验证查询不存在任务的日志返回 404."""
        resp = _request("GET", "/api/v1/finetune/tasks/no-id/logs", client)
        assert resp.status_code == 404

    def test_logs_contain_loss_metrics(self, client):
        """验证 Mock 日志包含 loss 指标（解析模式）."""
        payload = _make_lora_request("logs-metrics-test")
        create_resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        task_id = create_resp.json()["taskId"]

        resp = _request(
            "GET",
            f"/api/v1/finetune/tasks/{task_id}/logs",
            client,
            params={"tail": 50, "parse": True},
        )
        assert resp.status_code == 200
        body = resp.json()
        # 应有包含 loss 的日志条目
        loss_entries = [e for e in body["entries"] if e.get("loss") is not None]
        assert len(loss_entries) > 0, "Mock 日志应包含 loss 指标"


# ============================================================
# 三种微调方式配置验证
# ============================================================
class TestFinetuneMethods:
    """三种微调方式（LoRA/QLoRA/全参）配置验证."""

    def test_lora_rank_8(self, client):
        """验证 LoRA rank=8 配置合法."""
        payload = _make_lora_request("lora-rank-8")
        payload["config"]["lora"]["rank"] = 8
        payload["config"]["lora"]["alpha"] = 16
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201

    def test_lora_rank_16(self, client):
        """验证 LoRA rank=16 配置合法."""
        payload = _make_lora_request("lora-rank-16")
        payload["config"]["lora"]["rank"] = 16
        payload["config"]["lora"]["alpha"] = 32
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201

    def test_lora_rank_32(self, client):
        """验证 LoRA rank=32 配置合法."""
        payload = _make_lora_request("lora-rank-32")
        payload["config"]["lora"]["rank"] = 32
        payload["config"]["lora"]["alpha"] = 64
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201

    def test_qlora_4bit(self, client):
        """验证 QLoRA 4bit 量化配置合法."""
        payload = _make_qlora_request("qlora-4bit")
        payload["config"]["qlora"]["quantization"] = "4bit"
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201

    def test_qlora_8bit(self, client):
        """验证 QLoRA 8bit 量化配置合法."""
        payload = _make_qlora_request("qlora-8bit")
        payload["config"]["qlora"]["quantization"] = "8bit"
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201

    def test_full_finetune(self, client):
        """验证全参微调配置合法."""
        payload = _make_full_request("full-test")
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201
        body = resp.json()
        assert body["method"] == "full"


# ============================================================
# 三框架适配器测试
# ============================================================
class TestAdapters:
    """三个框架适配器（LLaMA-Factory/PEFT/DeepSpeed）测试."""

    def test_llama_factory_adapter_lora(self, client):
        """验证 LLaMA-Factory 适配器执行 LoRA 任务."""
        payload = _make_lora_request("lf-lora")
        payload["config"]["framework"] = "llama_factory"
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201
        body = resp.json()
        assert body["framework"] == "llama_factory"

    def test_llama_factory_adapter_qlora(self, client):
        """验证 LLaMA-Factory 适配器执行 QLoRA 任务."""
        payload = _make_qlora_request("lf-qlora")
        payload["config"]["framework"] = "llama_factory"
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201

    def test_llama_factory_adapter_full(self, client):
        """验证 LLaMA-Factory 适配器执行全参微调任务."""
        payload = _make_full_request("lf-full")
        payload["config"]["framework"] = "llama_factory"
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201

    def test_peft_adapter_lora(self, client):
        """验证 PEFT 适配器执行 LoRA 任务."""
        payload = _make_lora_request("peft-lora")
        payload["config"]["framework"] = "peft"
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201
        assert resp.json()["framework"] == "peft"

    def test_peft_adapter_qlora(self, client):
        """验证 PEFT 适配器执行 QLoRA 任务."""
        payload = _make_qlora_request("peft-qlora")
        payload["config"]["framework"] = "peft"
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201

    def test_deepspeed_adapter_zero2(self, client):
        """验证 DeepSpeed 适配器 ZeRO-2 配置."""
        payload = _make_full_request("ds-zero2")
        payload["config"]["framework"] = "deepspeed"
        payload["config"]["deepspeed"] = {
            "stage": "zero2",
            "tensorParallelSize": 1,
            "dataParallelSize": 1,
            "offloadOptimizer": False,
            "offloadParam": False,
        }
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201

    def test_deepspeed_adapter_zero3(self, client):
        """验证 DeepSpeed 适配器 ZeRO-3 配置."""
        payload = _make_full_request("ds-zero3")
        payload["config"]["framework"] = "deepspeed"
        payload["config"]["deepspeed"] = {
            "stage": "zero3",
            "tensorParallelSize": 1,
            "dataParallelSize": 1,
            "offloadOptimizer": True,
            "offloadParam": True,
        }
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201

    def test_deepspeed_invalid_offload_param_with_zero2(self, client):
        """验证 ZeRO-2 下开启 offloadParam 返回 400（仅 ZeRO-3 支持）."""
        payload = _make_full_request("ds-invalid")
        payload["config"]["framework"] = "deepspeed"
        payload["config"]["deepspeed"] = {
            "stage": "zero2",
            "offloadParam": True,  # ZeRO-2 不支持参数卸载
        }
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 400

    def test_list_adapters(self, client):
        """验证 GET /api/v1/finetune/adapters 返回三个适配器."""
        resp = _request("GET", "/api/v1/finetune/adapters", client)
        assert resp.status_code == 200
        body = resp.json()
        assert "adapters" in body
        names = [a.get("name") for a in body["adapters"]]
        assert "llama_factory" in names
        assert "peft" in names
        assert "deepspeed" in names


# ============================================================
# GPU 节点池调度测试
# ============================================================
class TestGPUScheduler:
    """GPU 节点池调度测试."""

    def test_list_nodes(self, client):
        """验证 GET /api/v1/finetune/nodes 返回节点池."""
        resp = _request("GET", "/api/v1/finetune/nodes", client)
        assert resp.status_code == 200
        body = resp.json()
        assert "nodes" in body
        assert len(body["nodes"]) > 0
        # 每个节点应含必要字段
        for node in body["nodes"]:
            assert "name" in node
            assert "gpuType" in node
            assert "totalGPUs" in node
            assert "freeGPUs" in node

    def test_schedule_to_a100_node(self, client):
        """验证指定 A100 型号可调度到 A100 节点."""
        payload = _make_lora_request("schedule-a100")
        payload["gpu"] = {"count": 1, "type": "A100-40G", "memoryGB": 40}
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201
        body = resp.json()
        assert body["assignedNode"] is not None
        assert "gpu-node" in body["assignedNode"]

    def test_schedule_multi_gpu_affinity(self, client):
        """验证多卡任务调度到同节点."""
        payload = _make_lora_request("schedule-multi-gpu")
        payload["gpu"] = {"count": 4, "type": "any"}
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 201
        body = resp.json()
        assert len(body["assignedGPUs"]) == 4

    def test_schedule_insufficient_gpu(self, client):
        """验证 GPU 资源不足时返回 400.

        申请 32 卡（GPURequirement 上限），超过单节点 16 卡容量，
        因多卡亲和性要求同节点，故调度失败。
        """
        payload = _make_lora_request("schedule-insufficient")
        payload["gpu"] = {"count": 32, "type": "any"}  # 超过单节点 16 卡
        resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert resp.status_code == 400


# ============================================================
# 服务统计
# ============================================================
class TestStats:
    """服务统计端点测试."""

    def test_stats(self, client):
        """验证 GET /api/v1/finetune/stats 返回统计信息."""
        resp = _request("GET", "/api/v1/finetune/stats", client)
        assert resp.status_code == 200
        body = resp.json()
        assert "totalTasks" in body
        assert "byStatus" in body
        assert "mockMode" in body
        assert "scheduler" in body
        assert isinstance(body["totalTasks"], int)
        assert isinstance(body["byStatus"], dict)


# ============================================================
# 端到端流程测试
# ============================================================
class TestEndToEnd:
    """端到端流程测试：提交 → 查询 → 日志 → 终止."""

    def test_full_lifecycle(self, client):
        """验证任务完整生命周期：提交 → 查询 → 日志 → 终止.

        Mock 模式下，查询详情会触发 refresh_task_status，若日志已含"训练完成"
        则任务自动标记为 succeeded。此时终止操作幂等返回当前终态。
        因此终止后状态可能是 terminated（主动终止 running 任务）或
        succeeded（任务已自动完成）。两者均为有效终态。
        """
        # 1. 提交
        payload = _make_lora_request("e2e-lifecycle")
        create_resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
        assert create_resp.status_code == 201
        task_id = create_resp.json()["taskId"]

        # 2. 查询
        get_resp = _request("GET", f"/api/v1/finetune/tasks/{task_id}", client)
        assert get_resp.status_code == 200
        assert get_resp.json()["taskId"] == task_id

        # 3. 日志
        logs_resp = _request(
            "GET", f"/api/v1/finetune/tasks/{task_id}/logs", client, params={"tail": 10}
        )
        assert logs_resp.status_code == 200
        assert logs_resp.json()["total"] > 0

        # 4. 终止
        del_resp = _request("DELETE", f"/api/v1/finetune/tasks/{task_id}", client)
        assert del_resp.status_code == 200
        final_status = del_resp.json()["status"]
        assert final_status in ("terminated", "succeeded"), (
            f"终止后状态应为终态，实际: {final_status}"
        )

        # 5. 再次查询确认状态稳定
        get_resp2 = _request("GET", f"/api/v1/finetune/tasks/{task_id}", client)
        assert get_resp2.status_code == 200
        assert get_resp2.json()["status"] == final_status

    def test_three_methods_three_frameworks_matrix(self, client):
        """验证 3 种微调方式 × 3 框架的组合矩阵（部分组合）.

        合理组合：
        - LoRA + PEFT
        - LoRA + LLaMA-Factory
        - QLoRA + PEFT
        - QLoRA + LLaMA-Factory
        - Full + DeepSpeed
        - Full + LLaMA-Factory
        """
        combinations = [
            ("lora", "peft", _make_lora_request),
            ("lora", "llama_factory", _make_lora_request),
            ("qlora", "peft", _make_qlora_request),
            ("qlora", "llama_factory", _make_qlora_request),
            ("full", "deepspeed", _make_full_request),
            ("full", "llama_factory", _make_full_request),
        ]

        for method, framework, make_fn in combinations:
            payload = make_fn(f"matrix-{method}-{framework}")
            payload["config"]["method"] = method
            payload["config"]["framework"] = framework
            resp = _request("POST", "/api/v1/finetune/tasks", client, json=payload)
            assert resp.status_code == 201, (
                f"组合 {method}+{framework} 提交失败: {resp.text}"
            )
            body = resp.json()
            assert body["method"] == method
            assert body["framework"] == framework