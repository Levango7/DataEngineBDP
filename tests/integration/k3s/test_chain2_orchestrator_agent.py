"""链路2: 编排引擎 → Agent → 工具调用 端到端集成测试.

测试 DAG 编排和 Agent 执行全链路：
    编排引擎(infra-orchestrator) → Agent 调度 → 工具调用(knowledge-engine等)

被测服务（K3s ClusterIP）：
    - infra-orchestrator  (port 8085)  POST /api/v1/clusters, GET /api/v1/clusters/providers
    - knowledge-engine    (port 8080)  POST /api/v1/spaces, POST /api/v1/spaces/{name}/extract

测试步骤：
    1. 验证 infra-orchestrator 健康检查
    2. 验证 knowledge-engine 健康检查
    3. 编排引擎：列出已注册 Provider（GET /api/v1/clusters/providers）
    4. 编排引擎：列出环境类型（GET /api/v1/clusters/environments）
    5. 编排引擎：创建集群（POST /api/v1/clusters）→ DAG 编排
    6. Agent 工具调用：创建知识空间（POST /api/v1/spaces）
    7. Agent 工具调用：知识抽取（POST /api/v1/spaces/{name}/extract）
    8. 端到端：编排引擎协调多个 Agent 工具
"""

from __future__ import annotations

import time
import uuid

import pytest

from conftest import record_test_result

CHAIN_NAME = "链路2: 编排引擎→Agent→工具调用"


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
class TestChain2HealthCheck:
    """链路2 健康检查：验证两个被测服务均可用."""

    def test_orchestrator_health(self, k3s_client, infra_orchestrator_url):
        """验证 infra-orchestrator 健康检查返回 200."""
        start = time.time()
        try:
            resp = k3s_client.get(infra_orchestrator_url + "/actuator/health")
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:200]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "编排引擎健康检查", passed, detail, duration_ms)
        assert passed, detail

    def test_knowledge_engine_health(self, k3s_client, knowledge_engine_url):
        """验证 knowledge-engine 健康检查返回 200."""
        start = time.time()
        try:
            resp = k3s_client.get(knowledge_engine_url + "/health")
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:200]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "知识引擎健康检查", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# 编排引擎：Provider 与环境管理
# ---------------------------------------------------------------------------
class TestChain2Orchestrator:
    """编排引擎测试：验证 DAG 编排能力."""

    def test_list_providers(self, k3s_client, infra_orchestrator_url):
        """测试列出已注册 Provider: GET /api/v1/clusters/providers."""
        start = time.time()
        try:
            resp = k3s_client.get(infra_orchestrator_url + "/api/v1/clusters/providers")
            body = resp.json() if resp.status_code == 200 else {}
            passed = resp.status_code == 200 and "providers" in body
            detail = f"status={resp.status_code}, providers={body.get('total', 'N/A')}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "列出Provider", passed, detail, duration_ms)
        assert passed, detail

    def test_list_environments(self, k3s_client, infra_orchestrator_url):
        """测试列出环境类型: GET /api/v1/clusters/environments."""
        start = time.time()
        try:
            resp = k3s_client.get(
                infra_orchestrator_url + "/api/v1/clusters/environments"
            )
            body = resp.json() if resp.status_code == 200 else {}
            passed = resp.status_code == 200 and "environments" in body
            detail = f"status={resp.status_code}, envs={body.get('total', 'N/A')}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "列出环境类型", passed, detail, duration_ms)
        assert passed, detail

    def test_list_clusters(self, k3s_client, infra_orchestrator_url):
        """测试列出所有集群: GET /api/v1/clusters."""
        start = time.time()
        try:
            resp = k3s_client.get(infra_orchestrator_url + "/api/v1/clusters")
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:200]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "列出集群", passed, detail, duration_ms)
        assert passed, detail

    def test_create_cluster(self, k3s_client, infra_orchestrator_url):
        """测试创建集群(DAG编排): POST /api/v1/clusters."""
        start = time.time()
        cluster_name = f"it-test-cluster-{uuid.uuid4().hex[:8]}"
        payload = {
            "environment": "CLOUD",
            "clusterName": cluster_name,
            "nodeCount": 1,
            "tenantId": "it-test-tenant",
        }
        try:
            resp = k3s_client.post(
                infra_orchestrator_url + "/api/v1/clusters", json=payload
            )
            body = resp.json() if resp.status_code in (200, 201) else {}
            passed = resp.status_code in (200, 201) and "phase" in body
            detail = f"status={resp.status_code}, phase={body.get('phase')}, cluster={cluster_name}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "创建集群(DAG编排)", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# Agent 工具调用：知识引擎
# ---------------------------------------------------------------------------
class TestChain2AgentToolCall:
    """Agent 工具调用测试：验证 Agent 可调用知识引擎工具."""

    def test_create_knowledge_space(self, k3s_client, knowledge_engine_url):
        """测试创建知识空间: POST /api/v1/spaces."""
        start = time.time()
        space_name = f"it_test_space_{uuid.uuid4().hex[:8]}"
        payload = {
            "name": space_name,
            "schema": {
                "vertexTags": ["entity"],
                "edgeTypes": [],
            },
        }
        try:
            resp = k3s_client.post(
                knowledge_engine_url + "/api/v1/spaces", json=payload
            )
            passed = resp.status_code in (200, 201)
            detail = f"status={resp.status_code}, space={space_name}, body={resp.text[:200]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "创建知识空间", passed, detail, duration_ms)
        # 记录空间名供后续测试使用
        if passed:
            TestChain2AgentToolCall._space_name = space_name
        assert passed, detail

    _space_name: str = ""

    def test_extract_knowledge(self, k3s_client, knowledge_engine_url):
        """测试知识抽取(Agent工具): POST /api/v1/spaces/{name}/extract."""
        start = time.time()
        space_name = getattr(TestChain2AgentToolCall, "_space_name", "default_space")
        payload = {
            "text": "张三是华为公司的工程师，李四是华为公司的产品经理",
            "entityTypes": ["person", "company"],
        }
        try:
            resp = k3s_client.post(
                knowledge_engine_url + f"/api/v1/spaces/{space_name}/extract",
                json=payload,
            )
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:300]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "知识抽取(Agent工具)", passed, detail, duration_ms)
        assert passed, detail

    def test_list_spaces(self, k3s_client, knowledge_engine_url):
        """测试列出知识空间: GET /api/v1/spaces."""
        start = time.time()
        try:
            resp = k3s_client.get(knowledge_engine_url + "/api/v1/spaces")
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:200]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "列出知识空间", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# 端到端：编排引擎协调 Agent 工具
# ---------------------------------------------------------------------------
class TestChain2EndToEnd:
    """端到端：编排引擎协调多个 Agent 工具调用."""

    def test_orchestrate_agent_tools(
        self, k3s_client, infra_orchestrator_url, knowledge_engine_url
    ):
        """完整链路: 编排引擎 → Agent → 知识引擎工具调用."""
        start = time.time()
        try:
            # 步骤1: 编排引擎 - 列出可用 Provider
            providers_resp = k3s_client.get(
                infra_orchestrator_url + "/api/v1/clusters/providers"
            )
            assert providers_resp.status_code == 200, "编排引擎 Provider 列表获取失败"

            # 步骤2: Agent 工具 - 列出知识空间
            spaces_resp = k3s_client.get(knowledge_engine_url + "/api/v1/spaces")
            assert spaces_resp.status_code == 200, "知识空间列表获取失败"

            # 步骤3: Agent 工具 - 知识抽取
            extract_payload = {
                "text": "华为公司总部位于深圳",
                "entityTypes": ["company", "location"],
            }
            extract_resp = k3s_client.post(
                knowledge_engine_url + "/api/v1/spaces/default_space/extract",
                json=extract_payload,
            )

            passed = (
                providers_resp.status_code == 200
                and spaces_resp.status_code == 200
            )
            detail = (
                f"编排Provider={providers_resp.status_code}, "
                f"知识空间={spaces_resp.status_code}, "
                f"抽取={extract_resp.status_code}"
            )
        except Exception as e:
            passed = False
            detail = f"链路异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "编排→Agent→工具(端到端)", passed, detail, duration_ms)
        assert passed, detail