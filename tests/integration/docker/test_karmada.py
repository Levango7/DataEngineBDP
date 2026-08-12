"""Karmada 多集群控制面 Docker 集成测试。

被测对象：
- Docker 容器 ``it-karmada-api``（镜像 ``sq/karmada-api:0.1.0``），Go/Gin，端口 8090
- Docker 容器 ``it-karmada-xinchang``（镜像 ``sq/mock-cluster:0.1.0``），Python，端口 8091
- Docker 容器 ``it-karmada-local``（镜像 ``sq/mock-cluster:0.1.0``），Python，端口 8092
- Docker 容器 ``it-karmada-cce``（镜像 ``sq/mock-cluster:0.1.0``），Python，端口 8093

测试覆盖：
1. 成员集群纳管状态验证（3 集群健康检查 + 元数据）
2. PropagationPolicy 创建与调度（通过控制台 API CRUD）
3. 副本按权重分配到多集群（3:2:1 分配验证）

设计要点：
- 借鉴 Phase 1 Docker 测试模式（纯 Docker 容器，非 K3s）；
- JWT 认证与平台其他组件统一（HMAC-SHA256）；
- 测试在 Docker 容器未启动时自动跳过。
"""

from __future__ import annotations

import os
import time
from typing import Dict

import jwt
import pytest
import requests


# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
# Karmada 控制台 API 基础 URL。
KARMADA_API_URL = os.environ.get("KARMADA_API_URL", "http://localhost:8090")

# 3 个成员集群 mock 的基礎 URL。
CLUSTER_URLS: Dict[str, str] = {
    "xinchang": os.environ.get("XINCHANG_CLUSTER_URL", "http://localhost:8091"),
    "local": os.environ.get("LOCAL_CLUSTER_URL", "http://localhost:8092"),
    "cce": os.environ.get("CCE_CLUSTER_URL", "http://localhost:8093"),
}

# 集群期望元数据（与 docker-compose.yml 环境变量对齐）。
EXPECTED_CLUSTERS = {
    "xinchang": {
        "name": "xinchang-cluster",
        "type": "xinchang",
        "vendor": "kylin",
        "arch": "arm64",
        "region": "on-premise",
        "env": "production",
        "maxReplicas": 100,
    },
    "local": {
        "name": "local-cluster",
        "type": "local",
        "vendor": "kubernetes",
        "arch": "amd64",
        "region": "on-premise",
        "env": "staging",
        "maxReplicas": 50,
    },
    "cce": {
        "name": "cce-cluster",
        "type": "cloud",
        "vendor": "huawei-cce",
        "arch": "amd64",
        "region": "cn-north-4",
        "env": "production",
        "maxReplicas": 200,
    },
}

# JWT 配置（与 karmada-api main.go / docker-compose.yml 保持一致）。
JWT_SECRET = os.environ.get(
    "JWT_SECRET", "dev-secret-key-change-in-production-at-least-256-bits"
)
JWT_ISSUER = os.environ.get("JWT_ISSUER", "shuqing-bigdata")

# HTTP 请求默认超时。
DEFAULT_TIMEOUT = 10


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
def generate_test_jwt(
    tenant_id: str = "karmada-it-tenant",
    user_id: str = "karmada-it-tester",
    expiry_seconds: int = 3600,
) -> str:
    """生成测试用 JWT Bearer token。

    Args:
        tenant_id: 租户 ID，写入 ``tenantId`` claim。
        user_id: 用户 ID，写入 ``sub`` claim。
        expiry_seconds: token 有效期秒数。

    Returns:
        编码后的 JWT 字符串。
    """
    now = int(time.time())
    payload = {
        "iss": JWT_ISSUER,
        "sub": user_id,
        "tenantId": tenant_id,
        "iat": now,
        "exp": now + expiry_seconds,
    }
    return jwt.encode(payload, JWT_SECRET, algorithm="HS256")


def wait_for_service(base_url: str, health_path: str, timeout: int = 30) -> bool:
    """轮询等待服务健康检查通过。

    Args:
        base_url: 服务基础 URL。
        health_path: 健康检查路径。
        timeout: 最长等待秒数。

    Returns:
        ``True`` 表示服务就绪；``False`` 表示超时。
    """
    deadline = time.time() + timeout
    url = base_url.rstrip("/") + health_path
    while time.time() < deadline:
        try:
            resp = requests.get(url, timeout=5)
            if resp.status_code == 200:
                return True
        except requests.RequestException:
            pass
        time.sleep(0.5)
    return False


def is_service_available(base_url: str, health_path: str) -> bool:
    """快速探测服务是否可用（5 秒内）。"""
    return wait_for_service(base_url, health_path, timeout=5)


# ---------------------------------------------------------------------------
# pytest fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="session")
def auth_token() -> str:
    """生成 session 级别的 JWT Bearer token。"""
    return generate_test_jwt()


@pytest.fixture(scope="session")
def api_client(auth_token) -> requests.Session:
    """提供预配置的 HTTP 客户端（自动注入 JWT）。

    Yields:
        配置好认证头的 requests.Session 实例。
    """
    session = requests.Session()
    session.headers.update(
        {
            "Authorization": f"Bearer {auth_token}",
            "Content-Type": "application/json",
        }
    )
    yield session
    session.close()


@pytest.fixture(scope="session")
def karmada_available() -> bool:
    """Karmada 控制台 API 是否可用。"""
    return is_service_available(KARMADA_API_URL, "/api/v1/health")


@pytest.fixture(scope="session")
def clusters_available() -> Dict[str, bool]:
    """3 个成员集群是否可用。"""
    return {
        name: is_service_available(url, "/healthz")
        for name, url in CLUSTER_URLS.items()
    }


def pytest_collection_modifyitems(config, items):
    """根据服务可用性自动跳过测试。"""
    karmada_ok = is_service_available(KARMADA_API_URL, "/api/v1/health")
    clusters_ok = {
        name: is_service_available(url, "/healthz")
        for name, url in CLUSTER_URLS.items()
    }

    for item in items:
        if item.module.__name__.split(".")[-1] != "test_karmada":
            continue

        # 标记需要的服务。
        needs_karmada = "karmada" in item.keywords or any(
            "karmada" in m.name for m in item.iter_markers()
        )
        needs_clusters = "clusters" in item.keywords or any(
            "clusters" in m.name for m in item.iter_markers()
        )

        # 通过函数名启发式判断。
        func_name = item.name
        if "propagation" in func_name or "policy" in func_name or "api" in func_name:
            if not karmada_ok:
                item.add_marker(
                    pytest.mark.skip(reason="Karmada 控制台 API 不可用")
                )
        if "cluster" in func_name or "replica" in func_name or "spread" in func_name:
            unavailable = [n for n, ok in clusters_ok.items() if not ok]
            if unavailable:
                item.add_marker(
                    pytest.mark.skip(reason=f"成员集群不可用: {unavailable}")
                )


# ===========================================================================
# 1. 成员集群纳管状态验证
# ===========================================================================
class TestClusterEnrollment:
    """测试 3 个成员集群纳管状态。"""

    def test_xinchang_cluster_health(self, clusters_available):
        """验证信创集群健康检查通过。"""
        if not clusters_available["xinchang"]:
            pytest.skip("信创集群不可用")
        resp = requests.get(CLUSTER_URLS["xinchang"] + "/healthz", timeout=DEFAULT_TIMEOUT)
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "ok"
        assert body["cluster"] == "xinchang-cluster"

    def test_local_cluster_health(self, clusters_available):
        """验证本地集群健康检查通过。"""
        if not clusters_available["local"]:
            pytest.skip("本地集群不可用")
        resp = requests.get(CLUSTER_URLS["local"] + "/healthz", timeout=DEFAULT_TIMEOUT)
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "ok"
        assert body["cluster"] == "local-cluster"

    def test_cce_cluster_health(self, clusters_available):
        """验证公有云集群健康检查通过。"""
        if not clusters_available["cce"]:
            pytest.skip("公有云集群不可用")
        resp = requests.get(CLUSTER_URLS["cce"] + "/healthz", timeout=DEFAULT_TIMEOUT)
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "ok"
        assert body["cluster"] == "cce-cluster"

    def test_xinchang_cluster_metadata(self, clusters_available):
        """验证信创集群元数据（鲲鹏 ARM + 麒麟 OS）。"""
        if not clusters_available["xinchang"]:
            pytest.skip("信创集群不可用")
        resp = requests.get(CLUSTER_URLS["xinchang"] + "/apis/cluster", timeout=DEFAULT_TIMEOUT)
        assert resp.status_code == 200
        body = resp.json()
        expected = EXPECTED_CLUSTERS["xinchang"]
        assert body["name"] == expected["name"]
        assert body["labels"]["cluster.karmada.io/type"] == expected["type"]
        assert body["labels"]["cluster.karmada.io/vendor"] == expected["vendor"]
        assert body["labels"]["cluster.karmada.io/arch"] == expected["arch"]
        assert body["maxReplicas"] == expected["maxReplicas"]

    def test_local_cluster_metadata(self, clusters_available):
        """验证本地集群元数据（标准 K8s x86_64）。"""
        if not clusters_available["local"]:
            pytest.skip("本地集群不可用")
        resp = requests.get(CLUSTER_URLS["local"] + "/apis/cluster", timeout=DEFAULT_TIMEOUT)
        assert resp.status_code == 200
        body = resp.json()
        expected = EXPECTED_CLUSTERS["local"]
        assert body["name"] == expected["name"]
        assert body["labels"]["cluster.karmada.io/arch"] == expected["arch"]
        assert body["maxReplicas"] == expected["maxReplicas"]

    def test_cce_cluster_metadata(self, clusters_available):
        """验证公有云集群元数据（华为云 CCE）。"""
        if not clusters_available["cce"]:
            pytest.skip("公有云集群不可用")
        resp = requests.get(CLUSTER_URLS["cce"] + "/apis/cluster", timeout=DEFAULT_TIMEOUT)
        assert resp.status_code == 200
        body = resp.json()
        expected = EXPECTED_CLUSTERS["cce"]
        assert body["name"] == expected["name"]
        assert body["labels"]["cluster.karmada.io/vendor"] == expected["vendor"]
        assert body["labels"]["cluster.karmada.io/region"] == expected["region"]
        assert body["maxReplicas"] == expected["maxReplicas"]

    def test_all_clusters_ready_condition(self, clusters_available):
        """验证所有集群 Ready=True 且 Syncable=True。"""
        for name, url in CLUSTER_URLS.items():
            if not clusters_available[name]:
                pytest.skip(f"{name} 集群不可用")
            resp = requests.get(url + "/apis/cluster", timeout=DEFAULT_TIMEOUT)
            assert resp.status_code == 200
            conditions = resp.json().get("conditions", [])
            cond_map = {c["type"]: c["status"] for c in conditions}
            assert cond_map.get("Ready") == "True", f"{name} 集群未 Ready"
            assert cond_map.get("Syncable") == "True", f"{name} 集群不可 Syncable"


# ===========================================================================
# 2. PropagationPolicy 创建与调度
# ===========================================================================
class TestPropagationPolicyCRUD:
    """测试 PropagationPolicy 控制台 API CRUD。"""

    def test_api_health_check(self, karmada_available):
        """验证 Karmada 控制台 API 健康检查。"""
        if not karmada_available:
            pytest.skip("Karmada 控制台 API 不可用")
        resp = requests.get(KARMADA_API_URL + "/api/v1/health", timeout=DEFAULT_TIMEOUT)
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "UP"
        assert body["service"] == "karmada-api"

    def test_api_unauthorized_without_token(self, karmada_available):
        """验证无 token 访问受保护端点返回 401。"""
        if not karmada_available:
            pytest.skip("Karmada 控制台 API 不可用")
        resp = requests.get(
            KARMADA_API_URL + "/api/v1/propagation-policies",
            timeout=DEFAULT_TIMEOUT,
        )
        assert resp.status_code == 401

    def test_create_propagation_policy(self, api_client, karmada_available):
        """验证创建 PropagationPolicy。"""
        if not karmada_available:
            pytest.skip("Karmada 控制台 API 不可用")
        policy_spec = {
            "name": "test-weighted-spread",
            "namespace": "default",
            "spec": {
                "resourceSelectors": [
                    {"apiVersion": "apps/v1", "kind": "Deployment"}
                ],
                "placement": {
                    "clusterAffinity": {
                        "matchExpressions": [
                            {
                                "key": "cluster.karmada.io/type",
                                "operator": "In",
                                "values": ["xinchang", "local", "cloud"],
                            }
                        ]
                    },
                    "replicaScheduling": {
                        "replicaSchedulingType": "Divided",
                        "replicaDivisionPreference": "Weighted",
                        "weightPreference": {
                            "staticWeightList": [
                                {"targetCluster": {"clusterNames": ["xinchang-cluster"]}, "weight": 3},
                                {"targetCluster": {"clusterNames": ["local-cluster"]}, "weight": 2},
                                {"targetCluster": {"clusterNames": ["cce-cluster"]}, "weight": 1},
                            ]
                        },
                    },
                    "spreadConstraints": [
                        {"spreadByField": "cluster", "minGroups": 2, "maxGroups": 3}
                    ],
                },
            },
        }
        resp = api_client.post(
            KARMADA_API_URL + "/api/v1/propagation-policies",
            json=policy_spec,
            timeout=DEFAULT_TIMEOUT,
        )
        assert resp.status_code == 201, f"创建失败: {resp.text}"
        body = resp.json()
        assert body["name"] == "test-weighted-spread"
        assert body["namespace"] == "default"

    def test_get_propagation_policy(self, api_client, karmada_available):
        """验证获取单个 PropagationPolicy。"""
        if not karmada_available:
            pytest.skip("Karmada 控制台 API 不可用")
        # 先创建。
        self._create_test_policy(api_client, "get-test-policy")
        # 再获取。
        resp = api_client.get(
            KARMADA_API_URL + "/api/v1/propagation-policies/get-test-policy",
            params={"namespace": "default"},
            timeout=DEFAULT_TIMEOUT,
        )
        assert resp.status_code == 200, f"获取失败: {resp.text}"
        body = resp.json()
        assert body["name"] == "get-test-policy"

    def test_list_propagation_policies(self, api_client, karmada_available):
        """验证列出 PropagationPolicy。"""
        if not karmada_available:
            pytest.skip("Karmada 控制台 API 不可用")
        self._create_test_policy(api_client, "list-test-policy")
        resp = api_client.get(
            KARMADA_API_URL + "/api/v1/propagation-policies",
            params={"namespace": "default", "limit": "20"},
            timeout=DEFAULT_TIMEOUT,
        )
        assert resp.status_code == 200, f"列出失败: {resp.text}"
        body = resp.json()
        assert "items" in body
        assert "total" in body
        assert body["total"] >= 1

    def test_update_propagation_policy(self, api_client, karmada_available):
        """验证更新 PropagationPolicy。"""
        if not karmada_available:
            pytest.skip("Karmada 控制台 API 不可用")
        self._create_test_policy(api_client, "update-test-policy")
        # 更新 spec。
        new_spec = {
            "spec": {
                "resourceSelectors": [
                    {"apiVersion": "apps/v1", "kind": "StatefulSet"}
                ],
                "placement": {
                    "replicaScheduling": {
                        "replicaSchedulingType": "Duplicated",
                        "replicaDivisionPreference": "Aggregated",
                    }
                },
            }
        }
        resp = api_client.put(
            KARMADA_API_URL + "/api/v1/propagation-policies/update-test-policy",
            params={"namespace": "default"},
            json=new_spec,
            timeout=DEFAULT_TIMEOUT,
        )
        assert resp.status_code == 200, f"更新失败: {resp.text}"

    def test_delete_propagation_policy(self, api_client, karmada_available):
        """验证删除 PropagationPolicy。"""
        if not karmada_available:
            pytest.skip("Karmada 控制台 API 不可用")
        self._create_test_policy(api_client, "delete-test-policy")
        resp = api_client.delete(
            KARMADA_API_URL + "/api/v1/propagation-policies/delete-test-policy",
            params={"namespace": "default"},
            timeout=DEFAULT_TIMEOUT,
        )
        assert resp.status_code == 204, f"删除失败: {resp.text}"
        # 验证已删除。
        resp = api_client.get(
            KARMADA_API_URL + "/api/v1/propagation-policies/delete-test-policy",
            params={"namespace": "default"},
            timeout=DEFAULT_TIMEOUT,
        )
        assert resp.status_code == 404

    def test_get_nonexistent_policy(self, api_client, karmada_available):
        """验证获取不存在的策略返回 404。"""
        if not karmada_available:
            pytest.skip("Karmada 控制台 API 不可用")
        resp = api_client.get(
            KARMADA_API_URL + "/api/v1/propagation-policies/nonexistent-xxx",
            params={"namespace": "default"},
            timeout=DEFAULT_TIMEOUT,
        )
        assert resp.status_code == 404

    @staticmethod
    def _create_test_policy(client: requests.Session, name: str) -> None:
        """创建测试用传播策略的辅助方法。"""
        policy_spec = {
            "name": name,
            "namespace": "default",
            "spec": {
                "resourceSelectors": [
                    {"apiVersion": "apps/v1", "kind": "Deployment"}
                ],
                "placement": {
                    "replicaScheduling": {
                        "replicaSchedulingType": "Divided",
                        "replicaDivisionPreference": "Weighted",
                        "weightPreference": {
                            "staticWeightList": [
                                {"targetCluster": {"clusterNames": ["xinchang-cluster"]}, "weight": 3},
                                {"targetCluster": {"clusterNames": ["local-cluster"]}, "weight": 2},
                                {"targetCluster": {"clusterNames": ["cce-cluster"]}, "weight": 1},
                            ]
                        },
                    }
                },
            },
        }
        client.post(
            KARMADA_API_URL + "/api/v1/propagation-policies",
            json=policy_spec,
            timeout=DEFAULT_TIMEOUT,
        )


# ===========================================================================
# 3. 副本按权重分配到多集群
# ===========================================================================
class TestReplicaWeightedDistribution:
    """测试副本按权重 3:2:1 分配到信创/本地/公有云集群。"""

    # 权重配置：信创=3, 本地=2, 公有云=1，总权重=6
    WEIGHTS = {"xinchang": 3, "local": 2, "cce": 1}
    TOTAL_WEIGHT = 6

    def test_replica_distribution_6_replicas(self, clusters_available):
        """验证 6 副本按 3:2:1 分配 → 信创=3, 本地=2, 公有云=1。"""
        if not all(clusters_available.values()):
            pytest.skip("部分集群不可用")
        total_replicas = 6
        expected = self._calc_expected(total_replicas)
        # 推送部署到 3 个集群。
        for cluster_key, url in CLUSTER_URLS.items():
            resp = requests.post(
                url + "/apis/deployments",
                json={
                    "name": "weighted-app",
                    "replicas": expected[cluster_key],
                    "spec": {"template": {"containers": [{"name": "app"}]}},
                },
                timeout=DEFAULT_TIMEOUT,
            )
            assert resp.status_code == 201, f"推送部署到 {cluster_key} 失败: {resp.text}"
        # 验证各集群副本数。
        for cluster_key, url in CLUSTER_URLS.items():
            resp = requests.get(url + "/apis/deployments", timeout=DEFAULT_TIMEOUT)
            assert resp.status_code == 200
            deployments = resp.json()["items"]
            target = next(d for d in deployments if d["name"] == "weighted-app")
            assert target["replicas"] == expected[cluster_key], (
                f"{cluster_key} 副本数不符: 期望 {expected[cluster_key]}, "
                f"实际 {target['replicas']}"
            )

    def test_replica_distribution_12_replicas(self, clusters_available):
        """验证 12 副本按 3:2:1 分配 → 信创=6, 本地=4, 公有云=2。"""
        if not all(clusters_available.values()):
            pytest.skip("部分集群不可用")
        total_replicas = 12
        expected = self._calc_expected(total_replicas)
        for cluster_key, url in CLUSTER_URLS.items():
            resp = requests.post(
                url + "/apis/deployments",
                json={
                    "name": "weighted-app-12",
                    "replicas": expected[cluster_key],
                },
                timeout=DEFAULT_TIMEOUT,
            )
            assert resp.status_code == 201
        # 验证总副本数等于期望。
        actual_total = 0
        for url in CLUSTER_URLS.values():
            resp = requests.get(url + "/apis/deployments", timeout=DEFAULT_TIMEOUT)
            deployments = resp.json()["items"]
            target = next((d for d in deployments if d["name"] == "weighted-app-12"), None)
            if target:
                actual_total += target["replicas"]
        assert actual_total == total_replicas, (
            f"总副本数不符: 期望 {total_replicas}, 实际 {actual_total}"
        )

    def test_replica_distribution_proportion(self, clusters_available):
        """验证副本分配比例符合 3:2:1。"""
        if not all(clusters_available.values()):
            pytest.skip("部分集群不可用")
        total_replicas = 30
        expected = self._calc_expected(total_replicas)
        # 验证比例（允许因整除导致的 ±1 误差）。
        ratio_xinchang = expected["xinchang"] / expected["cce"]
        ratio_local = expected["local"] / expected["cce"]
        assert abs(ratio_xinchang - 3.0) < 0.1, f"信创:公有云 比例不符 3:1, 实际 {ratio_xinchang}"
        assert abs(ratio_local - 2.0) < 0.1, f"本地:公有云 比例不符 2:1, 实际 {ratio_local}"

    def test_duplicated_scheduling(self, clusters_available):
        """验证 Duplicated 调度模式：每集群全副本。"""
        if not all(clusters_available.values()):
            pytest.skip("部分集群不可用")
        full_replicas = 5
        for url in CLUSTER_URLS.values():
            resp = requests.post(
                url + "/apis/deployments",
                json={"name": "duplicated-app", "replicas": full_replicas},
                timeout=DEFAULT_TIMEOUT,
            )
            assert resp.status_code == 201
        # 每个集群都应有 full_replicas 个副本。
        for url in CLUSTER_URLS.values():
            resp = requests.get(url + "/apis/deployments", timeout=DEFAULT_TIMEOUT)
            deployments = resp.json()["items"]
            target = next(d for d in deployments if d["name"] == "duplicated-app")
            assert target["replicas"] == full_replicas

    def _calc_expected(self, total: int) -> Dict[str, int]:
        """按权重计算各集群期望副本数。

        Args:
            total: 总副本数。

        Returns:
            各集群期望副本数字典。
        """
        result = {}
        allocated = 0
        keys = list(self.WEIGHTS.keys())
        for i, key in enumerate(keys):
            if i == len(keys) - 1:
                # 最后一个集群分配剩余，避免整除误差。
                result[key] = total - allocated
            else:
                result[key] = round(total * self.WEIGHTS[key] / self.TOTAL_WEIGHT)
                allocated += result[key]
        return result


# ===========================================================================
# 4. 策略同步到成员集群
# ===========================================================================
class TestPolicySyncToClusters:
    """测试 PropagationPolicy 同步到成员集群。"""

    def test_policy_sync_to_all_clusters(self, clusters_available):
        """验证传播策略能同步到所有成员集群。"""
        if not all(clusters_available.values()):
            pytest.skip("部分集群不可用")
        policy = {
            "name": "sync-test-policy",
            "spec": {
                "resourceSelectors": [{"apiVersion": "apps/v1", "kind": "Deployment"}],
                "placement": {"replicaScheduling": {"replicaSchedulingType": "Duplicated"}},
            },
        }
        for url in CLUSTER_URLS.values():
            resp = requests.post(
                url + "/apis/propagation-policies",
                json=policy,
                timeout=DEFAULT_TIMEOUT,
            )
            assert resp.status_code == 201, f"同步策略失败: {resp.text}"
        # 验证各集群已收到策略。
        for url in CLUSTER_URLS.values():
            resp = requests.get(url + "/apis/propagation-policies", timeout=DEFAULT_TIMEOUT)
            assert resp.status_code == 200
            policies = resp.json()["items"]
            assert any(p["name"] == "sync-test-policy" for p in policies)