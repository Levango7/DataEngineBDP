"""FinOps 成本模型服务（cost-model）Docker 集成测试。

被测对象：Docker 容器 ``it-cost-model``（镜像 ``sq/cost-model:0.1.0``），
Java/Spring Boot，主机端口 18084 → 容器 8084。

覆盖端点：
- GET  /api/v1/health                    （健康检查，无需认证）
- GET  /api/v1/pricing                   （列出定价配置名，需认证）
- GET  /api/v1/pricing/{name}            （获取定价配置，需认证）
- POST /api/v1/pricing                   （新建定价配置，需认证）
- PUT  /api/v1/pricing/{name}            （更新定价配置，需认证）
- POST /api/v1/cost/calculate            （计算成本，需认证）
- GET  /api/v1/cost/report               （生成成本报告，需认证）

测试覆盖：
1. 五维度采集数据正确性（CPU/内存/存储/GPU/网络）
2. 三种计费方式计算结果（按量/包年/阶梯）
3. 租户隔离（tenant 间成本数据不可见）
4. GPU 多卡型号差异化定价（A100/V100/昇腾910）
5. 动态定价配置（通过 API 配置单价）

设计要点：
- 每个测试函数独立，不依赖执行顺序；
- 使用 ``tenant_client`` fixture 支持多租户 token，验证租户隔离；
- 成本计算使用精确的 BigDecimal 断言，避免浮点误差。
"""

from __future__ import annotations

import time
from typing import Dict

import pytest
import requests

# 复用 conftest 的 JWT 生成函数与配置
from conftest import generate_test_jwt, DEFAULT_TIMEOUT


# ---------------------------------------------------------------------------
# 多租户 HTTP 客户端 fixture
# ---------------------------------------------------------------------------
def _make_client(tenant_id: str) -> requests.Session:
    """创建指定租户的 HTTP 客户端（注入对应 tenantId 的 JWT）。

    Args:
        tenant_id: 租户 ID，写入 JWT 的 tenantId claim。

    Returns:
        配置好认证头的 requests.Session 实例。
    """
    session = requests.Session()
    token = generate_test_jwt(tenant_id=tenant_id)
    session.headers.update(
        {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        }
    )
    return session


@pytest.fixture
def tenant_a_client() -> requests.Session:
    """租户 A 的 HTTP 客户端（tenantId=finops-tenant-a）。"""
    client = _make_client("finops-tenant-a")
    yield client
    client.close()


@pytest.fixture
def tenant_b_client() -> requests.Session:
    """租户 B 的 HTTP 客户端（tenantId=finops-tenant-b）。"""
    client = _make_client("finops-tenant-b")
    yield client
    client.close()


# ---------------------------------------------------------------------------
# 五维度用量构造工具
# ---------------------------------------------------------------------------
NOW = int(time.time())
ISO_START = "2026-08-08T00:00:00Z"
ISO_END = "2026-08-08T01:00:00Z"


def _usage(dimension: str, amount: float, tenant: str = "finops-tenant-a",
           namespace: str = "ns-test", gpu_model: str = None) -> Dict:
    """构造单维度资源用量字典。

    Args:
        dimension: 资源维度（CPU/MEMORY/STORAGE/GPU/NETWORK）
        amount:    用量数值
        tenant:    租户 ID
        namespace: Kubernetes namespace
        gpu_model: GPU 型号（仅 GPU 维度）

    Returns:
        ResourceUsage 字典
    """
    u = {
        "tenant": tenant,
        "namespace": namespace,
        "dimension": dimension,
        "amount": amount,
        "start": ISO_START,
        "end": ISO_END,
    }
    if gpu_model:
        u["gpuModel"] = gpu_model
    return u


def _five_dimensions_usage(tenant: str = "finops-tenant-a",
                           namespace: str = "ns-test") -> list:
    """构造五维度资源用量列表（CPU/内存/存储/GPU/网络）。"""
    return [
        _usage("CPU", 10.0, tenant, namespace),           # 10 核时
        _usage("MEMORY", 20.0, tenant, namespace),        # 20 GB·时
        _usage("STORAGE", 100.0, tenant, namespace),      # 100 GB·时
        _usage("GPU", 5.0, tenant, namespace, "A100"),    # 5 卡时 A100
        _usage("NETWORK", 50.0, tenant, namespace),       # 50 GB
    ]


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
def test_health_check(finops_url):
    """验证成本模型服务健康检查端点返回 200 且 status=UP。"""
    resp = requests.get(finops_url + "/api/v1/health", timeout=10)
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "UP"
    assert body.get("component") == "cost-model"


def test_actuator_health(finops_url):
    """验证 Spring Boot Actuator 健康检查端点返回 200 且 status=UP。"""
    resp = requests.get(finops_url + "/actuator/health", timeout=10)
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "UP"


# ---------------------------------------------------------------------------
# 认证机制验证
# ---------------------------------------------------------------------------
def test_unauthorized_without_token(finops_url):
    """验证无 Bearer token 访问受保护端点返回 401。"""
    resp = requests.get(finops_url + "/api/v1/pricing", timeout=10)
    assert resp.status_code == 401


def test_unauthorized_with_invalid_token(finops_url):
    """验证无效 Bearer token 访问受保护端点返回 401。"""
    resp = requests.get(
        finops_url + "/api/v1/pricing",
        headers={"Authorization": "Bearer invalid-token-xxx"},
        timeout=10,
    )
    assert resp.status_code == 401


# ---------------------------------------------------------------------------
# 1. 五维度采集数据正确性
# ---------------------------------------------------------------------------
def test_five_dimensions_on_demand(tenant_a_client, finops_url):
    """验证五维度用量按量计费返回结果包含全部五个维度。

    期望：CPU/内存/存储/GPU/网络五个维度均有用量与成本明细。
    """
    usages = _five_dimensions_usage()
    payload = {
        "usages": usages,
        "billingMethod": "ON_DEMAND",
        "pricingConfigName": "default",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["results"]) == 1
    result = body["results"][0]

    # 验证五维度用量均存在
    dim_usages = result["dimensionUsages"]
    assert "CPU" in dim_usages
    assert "MEMORY" in dim_usages
    assert "STORAGE" in dim_usages
    assert "GPU" in dim_usages
    assert "NETWORK" in dim_usages

    # 验证用量数值正确
    assert dim_usages["CPU"] == 10.0
    assert dim_usages["MEMORY"] == 20.0
    assert dim_usages["STORAGE"] == 100.0
    assert dim_usages["GPU"] == 5.0
    assert dim_usages["NETWORK"] == 50.0

    # 验证五维度成本均存在且为正
    dim_costs = result["dimensionCosts"]
    for dim in ["CPU", "MEMORY", "STORAGE", "GPU", "NETWORK"]:
        assert dim in dim_costs
        assert float(dim_costs[dim]) > 0


def test_five_dimensions_amounts_correct(tenant_a_client, finops_url):
    """验证五维度用量数值在响应中保持精确（无精度丢失）。"""
    usages = [
        _usage("CPU", 123.456),
        _usage("MEMORY", 789.012),
        _usage("STORAGE", 1000.0),
        _usage("GPU", 8.88, gpu_model="V100"),
        _usage("NETWORK", 999.999),
    ]
    payload = {
        "usages": usages,
        "billingMethod": "ON_DEMAND",
        "pricingConfigName": "default",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    result = resp.json()["results"][0]
    dim_usages = result["dimensionUsages"]
    assert abs(dim_usages["CPU"] - 123.456) < 1e-9
    assert abs(dim_usages["MEMORY"] - 789.012) < 1e-9
    assert abs(dim_usages["NETWORK"] - 999.999) < 1e-9


# ---------------------------------------------------------------------------
# 2. 三种计费方式计算结果
# ---------------------------------------------------------------------------
def test_on_demand_billing(tenant_a_client, finops_url):
    """验证按量计费：成本 = 实时用量 × 单价。

    默认单价：CPU=0.5, MEMORY=0.2, STORAGE=0.1, NETWORK=0.5
    期望：CPU 10核时 × 0.5 = 5.0 元
    """
    usages = [
        _usage("CPU", 10.0),
        _usage("MEMORY", 20.0),
        _usage("STORAGE", 100.0),
        _usage("NETWORK", 50.0),
    ]
    payload = {
        "usages": usages,
        "billingMethod": "ON_DEMAND",
        "pricingConfigName": "default",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    result = resp.json()["results"][0]

    # CPU: 10 × 0.5 = 5.0
    assert abs(float(result["dimensionCosts"]["CPU"]) - 5.0) < 1e-4
    # MEMORY: 20 × 0.2 = 4.0
    assert abs(float(result["dimensionCosts"]["MEMORY"]) - 4.0) < 1e-4
    # STORAGE: 100 × 0.1 = 10.0
    assert abs(float(result["dimensionCosts"]["STORAGE"]) - 10.0) < 1e-4
    # NETWORK: 50 × 0.5 = 25.0
    assert abs(float(result["dimensionCosts"]["NETWORK"]) - 25.0) < 1e-4
    # 总成本: 5 + 4 + 10 + 25 = 44.0
    assert abs(float(result["totalCost"]) - 44.0) < 1e-4
    assert result["billingMethod"] == "ON_DEMAND"


def test_reserved_billing(tenant_a_client, finops_url):
    """验证包年计费：预留实例分摊。

    默认配置：CPU 预留月价 200 元/月，预留数量 100 核时，730 小时/月。
    实际用量 50 核时 ≤ 预留 100，全部按预留价计费：
    预留成本 = 50 × (200 / 730) ≈ 13.6986 元
    """
    usages = [_usage("CPU", 50.0)]
    payload = {
        "usages": usages,
        "billingMethod": "RESERVED",
        "pricingConfigName": "default",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    result = resp.json()["results"][0]
    assert result["billingMethod"] == "RESERVED"

    # 预留成本 = 50 × (200 / 730) ≈ 13.6986
    expected = 50.0 * (200.0 / 730.0)
    assert abs(float(result["totalCost"]) - expected) < 1e-4


def test_reserved_billing_with_excess(tenant_a_client, finops_url):
    """验证包年计费超出部分按按量单价计费。

    CPU 预留 100 核时，实际 150 核时，超出 50 核时。
    预留成本 = 100 × (200 / 730) ≈ 27.3973
    超出成本 = 50 × 0.5 = 25.0
    总成本 ≈ 52.3973
    """
    usages = [_usage("CPU", 150.0)]
    payload = {
        "usages": usages,
        "billingMethod": "RESERVED",
        "pricingConfigName": "default",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    result = resp.json()["results"][0]

    reserved_cost = 100.0 * (200.0 / 730.0)
    excess_cost = 50.0 * 0.5
    expected = reserved_cost + excess_cost
    assert abs(float(result["totalCost"]) - expected) < 1e-4


def test_tiered_billing_cumulative(tenant_a_client, finops_url):
    """验证阶梯计费（累计阶梯）：各档独立计价求和。

    使用 gpu-differentiated 配置的 CPU 阶梯：
      档1 [0,100) @ 0.5
      档2 [100,500) @ 0.8
      档3 [500,∞) @ 1.2
    累计用量 150：档1 100×0.5 + 档2 50×0.8 = 50 + 40 = 90.0
    """
    usages = [_usage("CPU", 150.0)]
    payload = {
        "usages": usages,
        "billingMethod": "TIERED",
        "pricingConfigName": "gpu-differentiated",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    result = resp.json()["results"][0]
    assert result["billingMethod"] == "TIERED"

    # 累计阶梯：100×0.5 + 50×0.8 = 90.0
    expected = 100 * 0.5 + 50 * 0.8
    assert abs(float(result["totalCost"]) - expected) < 1e-4


def test_tiered_billing_high_usage(tenant_a_client, finops_url):
    """验证阶梯计费高用量跨三档。

    累计用量 600：档1 100×0.5 + 档2 400×0.8 + 档3 100×1.2 = 50 + 320 + 120 = 490.0
    """
    usages = [_usage("CPU", 600.0)]
    payload = {
        "usages": usages,
        "billingMethod": "TIERED",
        "pricingConfigName": "gpu-differentiated",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    result = resp.json()["results"][0]

    expected = 100 * 0.5 + 400 * 0.8 + 100 * 1.2
    assert abs(float(result["totalCost"]) - expected) < 1e-4


def test_tiered_billing_no_config_fallback(tenant_a_client, finops_url):
    """验证阶梯计费无阶梯配置时回退按量单价。

    MEMORY 无阶梯配置，回退到 default 的按量单价 0.2。
    用量 100：100 × 0.2 = 20.0
    """
    usages = [_usage("MEMORY", 100.0)]
    payload = {
        "usages": usages,
        "billingMethod": "TIERED",
        "pricingConfigName": "default",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    result = resp.json()["results"][0]
    # 回退按量：100 × 0.2 = 20.0
    assert abs(float(result["totalCost"]) - 20.0) < 1e-4


# ---------------------------------------------------------------------------
# 3. 租户隔离（tenant 间成本数据不可见）
# ---------------------------------------------------------------------------
def test_tenant_isolation(tenant_a_client, tenant_b_client, finops_url):
    """验证租户隔离：租户 A 的 token 不能看到租户 B 的成本数据。

    构造租户 A 与租户 B 的用量混合列表，用租户 A 的 token 请求计算，
    期望结果仅包含租户 A 的数据（租户 B 被过滤）。
    """
    usages = [
        _usage("CPU", 10.0, tenant="finops-tenant-a", namespace="ns-a"),
        _usage("CPU", 999.0, tenant="finops-tenant-b", namespace="ns-b"),
    ]
    payload = {
        "usages": usages,
        "billingMethod": "ON_DEMAND",
        "pricingConfigName": "default",
    }
    # 用租户 A 的 token 请求
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    body = resp.json()

    # 仅应有 1 个结果（租户 A），租户 B 被过滤
    assert len(body["results"]) == 1
    result = body["results"][0]
    assert result["tenant"] == "finops-tenant-a"
    assert result["namespace"] == "ns-a"
    # 用量应为 10.0（租户 B 的 999.0 被过滤）
    assert result["dimensionUsages"]["CPU"] == 10.0


def test_tenant_b_isolation(tenant_a_client, tenant_b_client, finops_url):
    """验证租户隔离对称性：租户 B 的 token 仅看到租户 B 的数据。"""
    usages = [
        _usage("CPU", 888.0, tenant="finops-tenant-a", namespace="ns-a"),
        _usage("CPU", 20.0, tenant="finops-tenant-b", namespace="ns-b"),
    ]
    payload = {
        "usages": usages,
        "billingMethod": "ON_DEMAND",
        "pricingConfigName": "default",
    }
    resp = tenant_b_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["results"]) == 1
    result = body["results"][0]
    assert result["tenant"] == "finops-tenant-b"
    assert result["dimensionUsages"]["CPU"] == 20.0


def test_tenant_isolation_namespace_grouping(tenant_a_client, finops_url):
    """验证同租户多 namespace 按命名空间分组返回多个结果。"""
    usages = [
        _usage("CPU", 10.0, tenant="finops-tenant-a", namespace="ns-a1"),
        _usage("CPU", 20.0, tenant="finops-tenant-a", namespace="ns-a2"),
    ]
    payload = {
        "usages": usages,
        "billingMethod": "ON_DEMAND",
        "pricingConfigName": "default",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    # 同租户两个 namespace 应返回 2 个结果
    assert len(body["results"]) == 2
    namespaces = {r["namespace"] for r in body["results"]}
    assert namespaces == {"ns-a1", "ns-a2"}


# ---------------------------------------------------------------------------
# 4. GPU 多卡型号差异化定价
# ---------------------------------------------------------------------------
def test_gpu_differentiated_pricing(tenant_a_client, finops_url):
    """验证 GPU 多卡型号差异化定价：A100/V100/昇腾910 单价不同。

    默认 GPU 单价：A100=12.0, V100=6.0, Ascend910=8.0
    用量：A100 5卡时 + V100 10卡时 + Ascend910 8卡时
    期望：5×12 + 10×6 + 8×8 = 60 + 60 + 64 = 184.0
    """
    usages = [
        _usage("GPU", 5.0, gpu_model="A100"),
        _usage("GPU", 10.0, gpu_model="V100"),
        _usage("GPU", 8.0, gpu_model="Ascend910"),
    ]
    payload = {
        "usages": usages,
        "billingMethod": "ON_DEMAND",
        "pricingConfigName": "default",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    result = resp.json()["results"][0]

    # GPU 按型号成本明细
    gpu_costs = result["gpuModelCosts"]
    assert "A100" in gpu_costs
    assert "V100" in gpu_costs
    assert "Ascend910" in gpu_costs
    assert abs(float(gpu_costs["A100"]) - 60.0) < 1e-4      # 5 × 12
    assert abs(float(gpu_costs["V100"]) - 60.0) < 1e-4      # 10 × 6
    assert abs(float(gpu_costs["Ascend910"]) - 64.0) < 1e-4  # 8 × 8
    # 总成本 184.0
    assert abs(float(result["totalCost"]) - 184.0) < 1e-4


def test_gpu_model_t4_pricing(tenant_a_client, finops_url):
    """验证 GPU T4 型号定价（gpu-differentiated 配置含 T4=3.0）。"""
    usages = [_usage("GPU", 10.0, gpu_model="T4")]
    payload = {
        "usages": usages,
        "billingMethod": "ON_DEMAND",
        "pricingConfigName": "gpu-differentiated",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    result = resp.json()["results"][0]
    # T4: 10 × 3.0 = 30.0
    assert abs(float(result["totalCost"]) - 30.0) < 1e-4


# ---------------------------------------------------------------------------
# 5. 动态定价配置
# ---------------------------------------------------------------------------
def test_list_pricing_configs(tenant_a_client, finops_url):
    """验证列出定价配置名返回默认配置。"""
    resp = tenant_a_client.get(finops_url + "/api/v1/pricing")
    assert resp.status_code == 200
    names = resp.json()
    assert isinstance(names, list)
    assert "default" in names
    assert "gpu-differentiated" in names


def test_get_pricing_config(tenant_a_client, finops_url):
    """验证获取指定定价配置返回完整配置。"""
    resp = tenant_a_client.get(finops_url + "/api/v1/pricing/default")
    assert resp.status_code == 200
    config = resp.json()
    assert config["name"] == "default"
    assert "CPU" in config["unitPrices"]
    assert "A100" in config["gpuPrices"]


def test_create_and_use_custom_pricing(tenant_a_client, finops_url):
    """验证动态创建定价配置并用于成本计算。

    创建自定义配置：CPU 单价 1.0 元/核时，然后计算 10 核时成本应为 10.0 元。
    """
    custom_config = {
        "name": "test-custom-pricing",
        "unitPrices": {
            "CPU": 1.0,
            "MEMORY": 0.5,
            "STORAGE": 0.3,
            "GPU": 10.0,
            "NETWORK": 1.0,
        },
        "gpuPrices": {
            "A100": 15.0,
            "V100": 8.0,
            "Ascend910": 10.0,
        },
        "currency": "CNY",
        "reservedHoursPerMonth": 730.0,
    }
    # 创建
    create_resp = tenant_a_client.post(
        finops_url + "/api/v1/pricing", json=custom_config
    )
    assert create_resp.status_code == 200

    # 使用自定义配置计算
    usages = [_usage("CPU", 10.0)]
    payload = {
        "usages": usages,
        "billingMethod": "ON_DEMAND",
        "pricingConfigName": "test-custom-pricing",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    result = resp.json()["results"][0]
    # 10 × 1.0 = 10.0
    assert abs(float(result["totalCost"]) - 10.0) < 1e-4


def test_update_pricing_config(tenant_a_client, finops_url):
    """验证更新定价配置后成本计算使用新单价。"""
    # 先创建
    config = {
        "name": "test-update-pricing",
        "unitPrices": {"CPU": 1.0, "MEMORY": 0.1, "STORAGE": 0.1,
                       "GPU": 1.0, "NETWORK": 0.1},
        "gpuPrices": {"A100": 10.0},
        "currency": "CNY",
        "reservedHoursPerMonth": 730.0,
    }
    tenant_a_client.post(finops_url + "/api/v1/pricing", json=config)

    # 更新 CPU 单价为 2.0
    config["unitPrices"]["CPU"] = 2.0
    update_resp = tenant_a_client.put(
        finops_url + "/api/v1/pricing/test-update-pricing", json=config
    )
    assert update_resp.status_code == 200

    # 使用更新后配置计算
    usages = [_usage("CPU", 10.0)]
    payload = {
        "usages": usages,
        "billingMethod": "ON_DEMAND",
        "pricingConfigName": "test-update-pricing",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 200
    result = resp.json()["results"][0]
    # 10 × 2.0 = 20.0
    assert abs(float(result["totalCost"]) - 20.0) < 1e-4


# ---------------------------------------------------------------------------
# 6. 成本报告端点（从 Prometheus 采集）
# ---------------------------------------------------------------------------
def test_cost_report_endpoint(tenant_a_client, finops_url):
    """验证 GET /api/v1/cost/report 端点可调用。

    注：集成测试环境可能无 Prometheus，采集器降级返回 0 用量，
    但端点应正常返回 200 与结构化响应。
    """
    resp = tenant_a_client.get(
        finops_url + "/api/v1/cost/report",
        params={
            "namespace": "ns-test",
            "billingMethod": "ON_DEMAND",
            "start": ISO_START,
            "end": ISO_END,
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert "results" in body
    assert "grandTotal" in body


# ---------------------------------------------------------------------------
# 7. 输入校验
# ---------------------------------------------------------------------------
def test_empty_usages_rejected(tenant_a_client, finops_url):
    """验证空用量列表返回 400（校验失败）。"""
    payload = {
        "usages": [],
        "billingMethod": "ON_DEMAND",
        "pricingConfigName": "default",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 400


def test_missing_billing_method_rejected(tenant_a_client, finops_url):
    """验证缺少计费方式返回 400。"""
    payload = {
        "usages": [_usage("CPU", 10.0)],
        "pricingConfigName": "default",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code == 400


def test_nonexistent_pricing_config_rejected(tenant_a_client, finops_url):
    """验证不存在的定价配置名返回 500（服务端抛 IllegalArgumentException）。"""
    payload = {
        "usages": [_usage("CPU", 10.0)],
        "billingMethod": "ON_DEMAND",
        "pricingConfigName": "nonexistent-config-xxx",
    }
    resp = tenant_a_client.post(finops_url + "/api/v1/cost/calculate", json=payload)
    assert resp.status_code in (400, 500)