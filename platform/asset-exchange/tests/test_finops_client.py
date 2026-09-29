"""FinOps 账单客户端单元测试.

聚焦出网前的入参门禁：billingId 会被拼进请求 URL，必须拒绝可改写目标主机的形式。
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest

from asset_exchange.services.finops_client import (
    FinOpsBillingClient,
    FinOpsBillingError,
)

_UNSAFE_IDS = ["", "x/../../@evil", "//evil.example/x", "id?param=1", "id#frag", "a" * 65]


def _client() -> FinOpsBillingClient:
    """构造客户端：域名用 .invalid 保证不出网，仅验证入参门禁."""
    settings = SimpleNamespace(
        finopsBillingUrl="http://finops.invalid/api/finops/v1/billing",
        finopsBillingTimeout=1.0,
    )
    return FinOpsBillingClient(settings)  # type: ignore[arg-type]


@pytest.mark.parametrize("billingId", _UNSAFE_IDS)
def test_getBilling_rejectsUnsafeBillingId(billingId: str) -> None:
    """非法账单 ID 应在出网前被拒绝，并以 400 结束."""
    with pytest.raises(FinOpsBillingError) as exc:
        asyncio.run(_client().getBilling(billingId))
    assert exc.value.status_code == 400


def test_getBilling_passesNormalIdToNetworkStage() -> None:
    """正常账单 ID 不应被入参门禁拦下，应止步于网络阶段而非 400."""
    with pytest.raises(FinOpsBillingError) as exc:
        asyncio.run(_client().getBilling("bill-2026_09.1"))
    assert exc.value.status_code != 400
