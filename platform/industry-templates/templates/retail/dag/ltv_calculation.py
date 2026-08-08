"""LTV 计算 DAG - 零售行业模板 T038.

功能：基于 BG/NBD + Gamma-Gamma 模型预测会员生命周期价值
  - BG/NBD 模型：预测会员未来购买次数与活跃概率
  - Gamma-Gamma 模型：预测会员每次交易金额
  - LTV = 历史价值 + 未来 365 天预测价值
  - LTV 分层：VIP / HIGH / MEDIUM / LOW / BOTTOM

调度：每周一凌晨 04:00 执行

依赖：member / 订单事实表
输出：member_ltv 表 + member_tag 表（LTV 标签）

接入标签引擎：将 LTV 分层写入 member_tag 表，tag_category=LTV
"""

from __future__ import annotations

import math
from typing import Any

DAG_ID = "ltv_calculation"
DAG_NAME = "LTV 计算"
DAG_DESCRIPTION = "基于 BG/NBD + Gamma-Gamma 模型预测会员生命周期价值"
DAG_SCHEDULE = "0 4 ? * MON"  # 每周一凌晨 04:00
DAG_TIMEOUT = 10800  # 3 小时

MODEL_NAME = "bgnbd_gamma_gamma_v1"
MODEL_VERSION = "1.0.0"


def bgnbd_expected_purchases(
    frequency: int,
    recency_days: int,
    customer_age_days: int,
    r: float,
    alpha: float,
    a: float,
    b: float,
    forecast_days: int = 365,
) -> float:
    """BG/NBD 模型：预测会员未来购买次数.

    简化的 BG/NBD 模型实现，参数 r/alpha/a/b 由训练得到。

    Args:
        frequency: 历史购买次数
        recency_days: 最近购买距今天数
        customer_age_days: 会员注册至今天数（T）
        r, alpha, a, b: BG/NBD 模型参数
        forecast_days: 预测窗口天数

    Returns:
        预测未来购买次数
    """
    if frequency == 0:
        return 0.0
    # 简化公式：基于历史频率与活跃概率外推
    p_alive = bgnbd_p_alive(frequency, recency_days, customer_age_days, r, alpha, a, b)
    historical_rate = frequency / max(customer_age_days, 1)
    return historical_rate * forecast_days * p_alive


def bgnbd_p_alive(
    frequency: int,
    recency_days: int,
    customer_age_days: int,
    r: float,
    alpha: float,
    a: float,
    b: float,
) -> float:
    """BG/NBD 模型：计算会员仍活跃概率 P(Alive).

    Args:
        frequency: 历史购买次数
        recency_days: 最近购买距今天数
        customer_age_days: 会员注册至今天数
        r, alpha, a, b: 模型参数

    Returns:
        P(Alive) 概率 (0~1)
    """
    if frequency == 0:
        return 0.0
    # 简化公式
    x = frequency
    t_x = customer_age_days - recency_days
    T = customer_age_days
    # P(Alive) = 1 / (1 + (a / (a + b + x - 1)) * ((alpha + T) / (alpha + t_x))^(r + x))
    base = (alpha + T) / max(alpha + t_x, 1e-10)
    exponent = r + x
    factor = a / max(a + b + x - 1, 1e-10)
    p = 1.0 / (1.0 + factor * math.pow(base, exponent))
    return max(0.0, min(1.0, p))


def gamma_gamma_expected_value(
    frequency: int,
    monetary: float,
    p: float,
    q: float,
    gamma: float,
) -> float:
    """Gamma-Gamma 模型：预测会员每次交易期望金额.

    Args:
        frequency: 历史购买次数
        monetary: 历史平均交易金额
        p, q, gamma: Gamma-Gamma 模型参数

    Returns:
        期望交易金额
    """
    if frequency == 0:
        return 0.0
    # E[M] = ((p * gamma + frequency * monetary) / (p + frequency)) * (q / (q - 1))
    expected = ((p * gamma + frequency * monetary) / max(p + frequency, 1e-10)) * (q / max(q - 1, 1e-10))
    return max(0.0, expected)


def compute_ltv(
    member_id: str,
    frequency: int,
    recency_days: int,
    customer_age_days: int,
    monetary: float,
    bgnbd_params: dict[str, float],
    gg_params: dict[str, float],
) -> dict[str, Any]:
    """计算会员 LTV.

    Args:
        member_id: 会员ID
        frequency: 历史购买次数
        recency_days: 最近购买距今天数
        customer_age_days: 会员注册至今天数
        monetary: 历史平均交易金额
        bgnbd_params: BG/NBD 参数 {r, alpha, a, b}
        gg_params: Gamma-Gamma 参数 {p, q, gamma}

    Returns:
        LTV 计算结果 dict
    """
    historical_value = frequency * monetary
    p_alive = bgnbd_p_alive(
        frequency,
        recency_days,
        customer_age_days,
        bgnbd_params["r"],
        bgnbd_params["alpha"],
        bgnbd_params["a"],
        bgnbd_params["b"],
    )
    # 预测未来 365 天购买次数
    pred_purchases_365 = bgnbd_expected_purchases(
        frequency,
        recency_days,
        customer_age_days,
        bgnbd_params["r"],
        bgnbd_params["alpha"],
        bgnbd_params["a"],
        bgnbd_params["b"],
        forecast_days=365,
    )
    pred_purchases_30 = pred_purchases_365 * 30 / 365
    pred_purchases_90 = pred_purchases_365 * 90 / 365
    pred_purchases_180 = pred_purchases_365 * 180 / 365
    # 预测期望交易金额
    expected_value = gamma_gamma_expected_value(
        frequency,
        monetary,
        gg_params["p"],
        gg_params["q"],
        gg_params["gamma"],
    )
    # 预测价值 = 预测次数 * 期望金额
    pred_value_30d = pred_purchases_30 * expected_value
    pred_value_90d = pred_purchases_90 * expected_value
    pred_value_180d = pred_purchases_180 * expected_value
    pred_value_365d = pred_purchases_365 * expected_value
    total_ltv = historical_value + pred_value_365d
    # LTV 分层
    ltv_segment = assign_ltv_segment(total_ltv)
    # 95% 置信区间（简化：±20%）
    ci_lower = total_ltv * 0.8
    ci_upper = total_ltv * 1.2
    return {
        "member_id": member_id,
        "historical_value": round(historical_value, 2),
        "predicted_value_30d": round(pred_value_30d, 2),
        "predicted_value_90d": round(pred_value_90d, 2),
        "predicted_value_180d": round(pred_value_180d, 2),
        "predicted_value_365d": round(pred_value_365d, 2),
        "total_ltv": round(total_ltv, 2),
        "ltv_segment": ltv_segment,
        "predicted_p_alive": round(p_alive, 4),
        "predicted_p_purchase": round(pred_purchases_30, 4),
        "model_name": MODEL_NAME,
        "model_version": MODEL_VERSION,
        "confidence_interval": {"lower": round(ci_lower, 2), "upper": round(ci_upper, 2)},
    }


def assign_ltv_segment(total_ltv: float) -> str:
    """根据 LTV 分层.

    Args:
        total_ltv: 总 LTV

    Returns:
        LTV 分层：VIP / HIGH / MEDIUM / LOW / BOTTOM
    """
    if total_ltv > 10000:
        return "VIP"
    if total_ltv > 5000:
        return "HIGH"
    if total_ltv > 1000:
        return "MEDIUM"
    if total_ltv > 100:
        return "LOW"
    return "BOTTOM"


# 默认模型参数（实际由训练得到）
DEFAULT_BGNBD_PARAMS = {"r": 0.243, "alpha": 5.074, "a": 0.793, "b": 2.426}
DEFAULT_GG_PARAMS = {"p": 6.0, "q": 3.0, "gamma": 10.0}

# SQL 模板：抽取 LTV 计算输入
SQL_EXTRACT_LTV_INPUT = """
SELECT
    m.member_id,
    r.frequency,
    r.recency_days,
    DATEDIFF('${biz_date}', m.register_at) AS customer_age_days,
    r.avg_order_value AS monetary
FROM ${db}.member m
INNER JOIN ${db}.member_rfm r ON m.member_id = r.member_id AND r.stat_date = '${biz_date}'
WHERE r.frequency > 0
"""

# SQL 模板：写入 member_ltv 表
SQL_LOAD_LTV = """
INSERT INTO ${db}.member_ltv
SELECT
    UUID() AS ltv_id,
    member_id,
    '${biz_date}' AS computed_at,
    historical_value,
    predicted_value_30d,
    predicted_value_90d,
    predicted_value_180d,
    predicted_value_365d,
    total_ltv,
    ltv_segment,
    predicted_p_alive,
    predicted_p_purchase,
    '${model_name}' AS model_name,
    '${model_version}' AS model_version,
    confidence_interval_json,
    NOW() AS created_at
FROM ${db}.tmp_ltv_computed
"""

# SQL 模板：将 LTV 分层写入 member_tag 表（接入标签引擎）
SQL_LOAD_LTV_TAGS = """
INSERT INTO ${db}.member_tag (
    tag_id, member_id, tag_code, tag_value, tag_category,
    tag_source, confidence, tagged_at, created_at, updated_at, created_by, updated_by
)
SELECT
    UUID(),
    member_id,
    CONCAT('LTV_', ltv_segment),
    ltv_segment,
    'LTV',
    'MODEL',
    1.0000,
    '${biz_date}',
    NOW(), NOW(),
    'dag_ltv_calculation', 'dag_ltv_calculation'
FROM ${db}.tmp_ltv_computed
"""


def build_dag() -> dict[str, Any]:
    """构建 LTV 计算 DAG 定义."""
    return {
        "dag_id": DAG_ID,
        "name": DAG_NAME,
        "description": DAG_DESCRIPTION,
        "schedule": DAG_SCHEDULE,
        "timeout": DAG_TIMEOUT,
        "tasks": [
            {"name": "extract_ltv_input", "sql": SQL_EXTRACT_LTV_INPUT},
            {"name": "train_bgnbd", "type": "ML_TRAIN", "model": "bgnbd"},
            {"name": "train_gamma_gamma", "type": "ML_TRAIN", "model": "gamma_gamma"},
            {"name": "compute_ltv", "python_callable": compute_ltv},
            {"name": "load_ltv", "sql": SQL_LOAD_LTV},
            {"name": "load_ltv_tags", "sql": SQL_LOAD_LTV_TAGS},
        ],
    }


if __name__ == "__main__":
    # 本地测试
    result = compute_ltv(
        "m001",
        frequency=10,
        recency_days=15,
        customer_age_days=365,
        monetary=200,
        bgnbd_params=DEFAULT_BGNBD_PARAMS,
        gg_params=DEFAULT_GG_PARAMS,
    )
    print(
        f"会员 {result['member_id']}: LTV={result['total_ltv']}, "
        f"分层={result['ltv_segment']}, P(Alive)={result['predicted_p_alive']}"
    )
