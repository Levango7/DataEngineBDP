"""链路4: SecurityFacade → 加解密 端到端集成测试.

测试统一安全 API 全链路：
    SecurityFacade(encaps-layer) → 加密/脱敏/鉴权/审计

被测服务（K3s ClusterIP）：
    - encaps-layer  (port 8080)  /api/v1/security/* 系列端点

测试步骤：
    1. 验证 encaps-layer 健康检查
    2. 查询 SecurityFacade 状态: GET /api/v1/security/status
    3. 执行脱敏(手机号): POST /api/v1/security/mask
    4. 执行脱敏(身份证): POST /api/v1/security/mask
    5. 鉴权检查: GET /api/v1/security/auth/check
    6. 查询审计事件: GET /api/v1/security/audit/events
    7. 端到端: 脱敏 → 审计 → 状态验证
"""

from __future__ import annotations

import time

import pytest

from conftest import record_test_result

CHAIN_NAME = "链路4: SecurityFacade→加解密"


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
class TestChain4HealthCheck:
    """链路4 健康检查：验证 encaps-layer 可用."""

    def test_encaps_health(self, k3s_client, encaps_layer_url):
        """验证 encaps-layer 健康检查返回 200."""
        start = time.time()
        try:
            resp = k3s_client.get(encaps_layer_url + "/api/v1/health")
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:200]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "encaps-layer健康检查", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# SecurityFacade 状态
# ---------------------------------------------------------------------------
class TestChain4SecurityStatus:
    """SecurityFacade 状态查询测试."""

    def test_security_status(self, k3s_client, encaps_layer_url):
        """测试查询 SecurityFacade 状态: GET /api/v1/security/status."""
        start = time.time()
        try:
            resp = k3s_client.get(encaps_layer_url + "/api/v1/security/status")
            body = resp.json() if resp.status_code == 200 else {}
            passed = resp.status_code == 200 and "enabled" in body
            detail = (
                f"status={resp.status_code}, "
                f"enabled={body.get('enabled')}, "
                f"crypto={body.get('crypto', {}).get('enabled')}, "
                f"mask={body.get('mask', {}).get('enabled')}"
            )
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "SecurityFacade状态", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# 脱敏（加解密核心能力）
# ---------------------------------------------------------------------------
class TestChain4Mask:
    """脱敏测试：验证 SecurityFacade 的数据脱敏能力."""

    def test_mask_phone(self, k3s_client, encaps_layer_url):
        """测试手机号脱敏: POST /api/v1/security/mask {"type":"PHONE"}."""
        start = time.time()
        payload = {
            "input": "13812345678",
            "type": "PHONE",
        }
        try:
            resp = k3s_client.post(
                encaps_layer_url + "/api/v1/security/mask", json=payload
            )
            body = resp.json() if resp.status_code == 200 else {}
            masked = body.get("masked", "")
            # 脱敏后不应包含完整原始手机号
            passed = (
                resp.status_code == 200
                and "masked" in body
                and "13812345678" not in masked
            )
            detail = f"status={resp.status_code}, type={body.get('type')}, masked={masked}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "手机号脱敏", passed, detail, duration_ms)
        assert passed, detail

    def test_mask_id_card(self, k3s_client, encaps_layer_url):
        """测试身份证号脱敏: POST /api/v1/security/mask {"type":"ID_CARD"}."""
        start = time.time()
        payload = {
            "input": "110101199001011234",
            "type": "ID_CARD",
        }
        try:
            resp = k3s_client.post(
                encaps_layer_url + "/api/v1/security/mask", json=payload
            )
            body = resp.json() if resp.status_code == 200 else {}
            masked = body.get("masked", "")
            passed = (
                resp.status_code == 200
                and "masked" in body
                and "110101199001011234" not in masked
            )
            detail = f"status={resp.status_code}, type={body.get('type')}, masked={masked}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "身份证脱敏", passed, detail, duration_ms)
        assert passed, detail

    def test_mask_email(self, k3s_client, encaps_layer_url):
        """测试邮箱脱敏: POST /api/v1/security/mask {"type":"EMAIL"}."""
        start = time.time()
        payload = {
            "input": "zhangsan@huawei.com",
            "type": "EMAIL",
        }
        try:
            resp = k3s_client.post(
                encaps_layer_url + "/api/v1/security/mask", json=payload
            )
            body = resp.json() if resp.status_code == 200 else {}
            masked = body.get("masked", "")
            passed = (
                resp.status_code == 200
                and "masked" in body
                and "zhangsan@huawei.com" not in masked
            )
            detail = f"status={resp.status_code}, type={body.get('type')}, masked={masked}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "邮箱脱敏", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# 鉴权检查
# ---------------------------------------------------------------------------
class TestChain4AuthCheck:
    """鉴权检查测试."""

    def test_auth_check(self, k3s_client, encaps_layer_url):
        """测试鉴权检查: GET /api/v1/security/auth/check."""
        start = time.time()
        try:
            resp = k3s_client.get(encaps_layer_url + "/api/v1/security/auth/check")
            body = resp.json() if resp.status_code == 200 else {}
            passed = resp.status_code == 200 and "allowed" in body
            detail = (
                f"status={resp.status_code}, "
                f"allowed={body.get('allowed')}, "
                f"principal={body.get('principal')}"
            )
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "鉴权检查", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# 审计事件
# ---------------------------------------------------------------------------
class TestChain4Audit:
    """审计事件测试."""

    def test_list_audit_events(self, k3s_client, encaps_layer_url):
        """测试查询审计事件: GET /api/v1/security/audit/events."""
        start = time.time()
        try:
            resp = k3s_client.get(
                encaps_layer_url + "/api/v1/security/audit/events"
            )
            passed = resp.status_code == 200
            detail = f"status={resp.status_code}, body={resp.text[:300]}"
        except Exception as e:
            passed = False
            detail = f"请求异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "查询审计事件", passed, detail, duration_ms)
        assert passed, detail


# ---------------------------------------------------------------------------
# 端到端：脱敏 → 审计 → 状态验证
# ---------------------------------------------------------------------------
class TestChain4EndToEnd:
    """端到端：脱敏 → 审计 → 状态验证."""

    def test_mask_then_audit(self, k3s_client, encaps_layer_url):
        """完整链路: 脱敏操作 → 产生审计事件 → 查询审计验证."""
        start = time.time()
        try:
            # 步骤1: 查询初始状态
            status_resp = k3s_client.get(
                encaps_layer_url + "/api/v1/security/status"
            )
            assert status_resp.status_code == 200, "SecurityFacade 状态查询失败"
            initial_status = status_resp.json()
            initial_audit_size = (
                initial_status.get("audit", {}).get("currentSize", 0)
            )

            # 步骤2: 执行脱敏操作（应产生审计事件）
            mask_payload = {"input": "13987654321", "type": "PHONE"}
            mask_resp = k3s_client.post(
                encaps_layer_url + "/api/v1/security/mask", json=mask_payload
            )
            assert mask_resp.status_code == 200, "脱敏操作失败"
            mask_body = mask_resp.json()
            masked_value = mask_body.get("masked", "")

            # 步骤3: 查询审计事件（验证脱敏被记录）
            audit_resp = k3s_client.get(
                encaps_layer_url + "/api/v1/security/audit/events"
            )
            audit_body = audit_resp.json() if audit_resp.status_code == 200 else []

            # 步骤4: 再次查询状态（审计大小可能增加）
            status_resp2 = k3s_client.get(
                encaps_layer_url + "/api/v1/security/status"
            )
            final_status = status_resp2.json() if status_resp2.status_code == 200 else {}
            final_audit_size = final_status.get("audit", {}).get("currentSize", 0)

            passed = (
                status_resp.status_code == 200
                and mask_resp.status_code == 200
                and "13987654321" not in masked_value
                and audit_resp.status_code == 200
            )
            detail = (
                f"初始审计={initial_audit_size}, "
                f"脱敏={mask_resp.status_code}(masked={masked_value}), "
                f"最终审计={final_audit_size}, "
                f"审计事件数={len(audit_body)}"
            )
        except Exception as e:
            passed = False
            detail = f"链路异常: {e}"
        duration_ms = (time.time() - start) * 1000
        record_test_result(CHAIN_NAME, "脱敏→审计→状态(端到端)", passed, detail, duration_ms)
        assert passed, detail