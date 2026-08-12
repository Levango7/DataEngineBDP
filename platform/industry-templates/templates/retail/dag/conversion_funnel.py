"""转化漏斗分析 DAG - 零售行业模板 T038.

功能：计算营销转化漏斗（曝光 → 点击 → 加购 → 下单 → 支付，5 步漏斗）
  - 各步骤转化率（点击率/加购率/下单率/支付率）
  - 各步骤流失率
  - 总体转化率 = 支付数 / 曝光数
  - 平均转化时长

调度：每日凌晨 05:00 执行

依赖：营销事件流（曝光/点击/加购/下单/支付）
输出：conversion_funnel 表
"""

from __future__ import annotations

from typing import Any

DAG_ID = "conversion_funnel"
DAG_NAME = "转化漏斗分析"
DAG_DESCRIPTION = "计算营销转化漏斗（曝光→点击→加购→下单→支付，5 步漏斗）"
DAG_SCHEDULE = "0 5 * * ?"  # 每日凌晨 05:00
DAG_TIMEOUT = 3600

# 漏斗步骤定义（5 步）
FUNNEL_STEPS = [
    {"step": 1, "name": "曝光", "code": "EXPOSURE"},
    {"step": 2, "name": "点击", "code": "CLICK"},
    {"step": 3, "name": "加购", "code": "CART"},
    {"step": 4, "name": "下单", "code": "ORDER"},
    {"step": 5, "name": "支付", "code": "PAY"},
]


def compute_step_rate(current_count: int, previous_count: int) -> float:
    """计算单步转化率.

    Args:
        current_count: 当前步骤数
        previous_count: 上一步骤数

    Returns:
        转化率 (0~1)
    """
    if previous_count == 0:
        return 0.0
    return current_count / previous_count


def compute_drop_off_rate(current_count: int, previous_count: int) -> float:
    """计算单步流失率.

    Args:
        current_count: 当前步骤数
        previous_count: 上一步骤数

    Returns:
        流失率 (0~1)
    """
    if previous_count == 0:
        return 0.0
    return 1.0 - (current_count / previous_count)


def compute_funnel(
    funnel_name: str,
    campaign_id: str | None,
    stat_date: str,
    exposure_count: int,
    click_count: int,
    cart_count: int,
    order_count: int,
    pay_count: int,
    avg_time_to_pay_seconds: int = 0,
) -> dict[str, Any]:
    """计算转化漏斗.

    Args:
        funnel_name: 漏斗名称
        campaign_id: 营销活动ID（全站漏斗为 None）
        stat_date: 统计日期
        exposure_count: 曝光数
        click_count: 点击数
        cart_count: 加购数
        order_count: 下单数
        pay_count: 支付数
        avg_time_to_pay_seconds: 平均转化时长（秒）

    Returns:
        漏斗计算结果 dict
    """
    # 各步骤转化率
    click_rate = compute_step_rate(click_count, exposure_count)
    cart_rate = compute_step_rate(cart_count, click_count)
    order_rate = compute_step_rate(order_count, cart_count)
    pay_rate = compute_step_rate(pay_count, order_count)
    # 总体转化率
    overall_conversion_rate = compute_step_rate(pay_count, exposure_count)
    # 各步骤流失率
    drop_off_exposure_click = compute_drop_off_rate(click_count, exposure_count)
    drop_off_click_cart = compute_drop_off_rate(cart_count, click_count)
    drop_off_cart_order = compute_drop_off_rate(order_count, cart_count)
    drop_off_order_pay = compute_drop_off_rate(pay_count, order_count)
    return {
        "funnel_name": funnel_name,
        "campaign_id": campaign_id,
        "stat_date": stat_date,
        "step_exposure_count": exposure_count,
        "step_click_count": click_count,
        "step_cart_count": cart_count,
        "step_order_count": order_count,
        "step_pay_count": pay_count,
        "step_click_rate": round(click_rate, 6),
        "step_cart_rate": round(cart_rate, 6),
        "step_order_rate": round(order_rate, 6),
        "step_pay_rate": round(pay_rate, 6),
        "overall_conversion_rate": round(overall_conversion_rate, 6),
        "drop_off_exposure_click": round(drop_off_exposure_click, 6),
        "drop_off_click_cart": round(drop_off_click_cart, 6),
        "drop_off_cart_order": round(drop_off_cart_order, 6),
        "drop_off_order_pay": round(drop_off_order_pay, 6),
        "avg_time_to_pay_seconds": avg_time_to_pay_seconds,
    }


# SQL 模板：聚合漏斗各步骤数
SQL_AGGREGATE_FUNNEL = """
SELECT
    '${funnel_name}' AS funnel_name,
    '${campaign_id}' AS campaign_id,
    '${biz_date}' AS stat_date,
    SUM(CASE WHEN event_type = 'EXPOSURE' THEN 1 ELSE 0 END) AS exposure_count,
    SUM(CASE WHEN event_type = 'CLICK'    THEN 1 ELSE 0 END) AS click_count,
    SUM(CASE WHEN event_type = 'CART'     THEN 1 ELSE 0 END) AS cart_count,
    SUM(CASE WHEN event_type = 'ORDER'    THEN 1 ELSE 0 END) AS order_count,
    SUM(CASE WHEN event_type = 'PAY'      THEN 1 ELSE 0 END) AS pay_count,
    AVG(TIMESTAMPDIFF(SECOND, first_exposure_time, pay_time)) AS avg_time_to_pay_seconds
FROM ${db}.marketing_event
WHERE stat_date = '${biz_date}'
  AND (${campaign_id} IS NULL OR campaign_id = '${campaign_id}')
"""

# SQL 模板：写入 conversion_funnel 表
SQL_LOAD_FUNNEL = """
INSERT INTO ${db}.conversion_funnel
SELECT
    UUID() AS funnel_id,
    campaign_id,
    funnel_name,
    stat_date,
    exposure_count,
    click_count,
    cart_count,
    order_count,
    pay_count,
    click_rate,
    cart_rate,
    order_rate,
    pay_rate,
    overall_conversion_rate,
    drop_off_exposure_click,
    drop_off_click_cart,
    drop_off_cart_order,
    drop_off_order_pay,
    avg_time_to_pay_seconds,
    NOW() AS computed_at,
    NOW() AS created_at
FROM ${db}.tmp_funnel_computed
"""


def build_dag() -> dict[str, Any]:
    """构建转化漏斗分析 DAG 定义."""
    return {
        "dag_id": DAG_ID,
        "name": DAG_NAME,
        "description": DAG_DESCRIPTION,
        "schedule": DAG_SCHEDULE,
        "timeout": DAG_TIMEOUT,
        "tasks": [
            {"name": "aggregate_funnel", "sql": SQL_AGGREGATE_FUNNEL},
            {"name": "compute_rates", "python_callable": compute_funnel},
            {"name": "load_funnel", "sql": SQL_LOAD_FUNNEL},
        ],
    }


if __name__ == "__main__":
    # 本地测试
    result = compute_funnel(
        funnel_name="全站转化漏斗",
        campaign_id=None,
        stat_date="2026-08-08",
        exposure_count=100000,
        click_count=15000,
        cart_count=6000,
        order_count=3000,
        pay_count=2400,
        avg_time_to_pay_seconds=3600,
    )
    print(f"漏斗 {result['funnel_name']}:")
    print(f"  曝光→点击: {result['step_click_rate']:.4f} (流失 {result['drop_off_exposure_click']:.4f})")
    print(f"  点击→加购: {result['step_cart_rate']:.4f} (流失 {result['drop_off_click_cart']:.4f})")
    print(f"  加购→下单: {result['step_order_rate']:.4f} (流失 {result['drop_off_cart_order']:.4f})")
    print(f"  下单→支付: {result['step_pay_rate']:.4f} (流失 {result['drop_off_order_pay']:.4f})")
    print(f"  总体转化率: {result['overall_conversion_rate']:.4f}")
