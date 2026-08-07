"""ROI 分析 DAG - 零售行业模板 T038.

功能：计算营销活动投入产出比（ROI）
  - ROI = (产出 - 投入) / 投入
  - ROAS = 产出 / 投入（广告支出回报率）
  - CPA = 投入 / 转化数（每次行动成本）
  - CPC = 投入 / 点击数（每次点击成本）
  - CPM = 投入 / 曝光数 * 1000（千次曝光成本）
  - 回本周期估算

调度：每日凌晨 06:00 执行

依赖：marketing_campaign / 营销事件流 / 订单事实表
输出：marketing_roi 表 + marketing_channel_stat 表
"""
from __future__ import annotations

from typing import Any

DAG_ID = "roi_analysis"
DAG_NAME = "ROI 分析"
DAG_DESCRIPTION = "计算营销活动投入产出比（ROI/ROAS/CPA/CPC/CPM）"
DAG_SCHEDULE = "0 6 * * ?"  # 每日凌晨 06:00
DAG_TIMEOUT = 3600


def compute_roi(
    campaign_id: str,
    stat_date: str,
    investment_amount: float,
    revenue_amount: float,
    conversion_count: int,
    click_count: int,
    impression_count: int,
) -> dict[str, Any]:
    """计算营销活动 ROI.

    Args:
        campaign_id: 活动ID
        stat_date: 统计日期
        investment_amount: 投入金额
        revenue_amount: 产出金额（增量 GMV）
        conversion_count: 转化数
        click_count: 点击数
        impression_count: 曝光数

    Returns:
        ROI 计算结果 dict
    """
    profit_amount = revenue_amount - investment_amount
    roi = profit_amount / investment_amount if investment_amount > 0 else 0.0
    roas = revenue_amount / investment_amount if investment_amount > 0 else 0.0
    cpa = investment_amount / conversion_count if conversion_count > 0 else 0.0
    cpc = investment_amount / click_count if click_count > 0 else 0.0
    cpv = investment_amount / impression_count if impression_count > 0 else 0.0
    ctr = click_count / impression_count if impression_count > 0 else 0.0
    cvr = conversion_count / click_count if click_count > 0 else 0.0
    # 回本周期估算（按日均产出推算）
    if revenue_amount > 0 and revenue_amount >= investment_amount:
        # 已回本，回本周期 = 投入 / 日均产出
        payback_period_days = max(1, round(investment_amount / (revenue_amount / 1)))
    else:
        # 未回本，返回 -1 表示未回本
        payback_period_days = -1
    is_profitable = roi > 0
    return {
        "campaign_id": campaign_id,
        "stat_date": stat_date,
        "investment_amount": round(investment_amount, 2),
        "revenue_amount": round(revenue_amount, 2),
        "profit_amount": round(profit_amount, 2),
        "roi": round(roi, 4),
        "roas": round(roas, 4),
        "cpa": round(cpa, 2),
        "cpc": round(cpc, 2),
        "cpv": round(cpv, 6),
        "conversion_count": conversion_count,
        "click_count": click_count,
        "impression_count": impression_count,
        "ctr": round(ctr, 6),
        "cvr": round(cvr, 6),
        "payback_period_days": payback_period_days,
        "is_profitable": is_profitable,
    }


def compute_channel_stat(
    campaign_id: str | None,
    channel_code: str,
    channel_name: str,
    stat_date: str,
    impression_count: int,
    click_count: int,
    conversion_count: int,
    investment_amount: float,
    revenue_amount: float,
) -> dict[str, Any]:
    """计算渠道效能统计.

    Args:
        campaign_id: 活动ID（全站统计为 None）
        channel_code: 渠道编码
        channel_name: 渠道名称
        stat_date: 统计日期
        impression_count: 曝光数
        click_count: 点击数
        conversion_count: 转化数
        investment_amount: 投入金额
        revenue_amount: 产出金额

    Returns:
        渠道统计 dict
    """
    ctr = click_count / impression_count if impression_count > 0 else 0.0
    cvr = conversion_count / click_count if click_count > 0 else 0.0
    cpc = investment_amount / click_count if click_count > 0 else 0.0
    cpa = investment_amount / conversion_count if conversion_count > 0 else 0.0
    roas = revenue_amount / investment_amount if investment_amount > 0 else 0.0
    return {
        "campaign_id": campaign_id,
        "channel_code": channel_code,
        "channel_name": channel_name,
        "stat_date": stat_date,
        "impression_count": impression_count,
        "click_count": click_count,
        "conversion_count": conversion_count,
        "investment_amount": round(investment_amount, 2),
        "revenue_amount": round(revenue_amount, 2),
        "ctr": round(ctr, 6),
        "cvr": round(cvr, 6),
        "cpc": round(cpc, 2),
        "cpa": round(cpa, 2),
        "roas": round(roas, 4),
    }


# SQL 模板：聚合活动投入产出
SQL_AGGREGATE_CAMPAIGN_ROI = """
SELECT
    c.campaign_id,
    '${biz_date}' AS stat_date,
    c.actual_cost AS investment_amount,
    COALESCE(SUM(o.pay_amount), 0) AS revenue_amount,
    COUNT(DISTINCT o.order_id) AS conversion_count,
    COUNT(DISTINCT e_click.user_id) AS click_count,
    COUNT(DISTINCT e_exp.user_id) AS impression_count
FROM ${db}.marketing_campaign c
LEFT JOIN ${db}.order_fact o ON c.campaign_id = o.campaign_id AND o.pay_time = '${biz_date}'
LEFT JOIN ${db}.marketing_event e_click ON c.campaign_id = e_click.campaign_id AND e_click.event_type = 'CLICK' AND e_click.stat_date = '${biz_date}'
LEFT JOIN ${db}.marketing_event e_exp ON c.campaign_id = e_exp.campaign_id AND e_exp.event_type = 'EXPOSURE' AND e_exp.stat_date = '${biz_date}'
WHERE c.status IN ('RUNNING', 'COMPLETED')
GROUP BY c.campaign_id, c.actual_cost
"""

# SQL 模板：写入 marketing_roi 表
SQL_LOAD_ROI = """
INSERT INTO ${db}.marketing_roi
SELECT
    UUID() AS roi_id,
    campaign_id,
    stat_date,
    investment_amount,
    revenue_amount,
    profit_amount,
    roi,
    roas,
    cpa,
    cpc,
    cpv,
    conversion_count,
    click_count,
    impression_count,
    ctr,
    cvr,
    payback_period_days,
    is_profitable,
    NOW() AS computed_at,
    NOW() AS created_at
FROM ${db}.tmp_roi_computed
"""

# SQL 模板：聚合渠道效能
SQL_AGGREGATE_CHANNEL_STAT = """
SELECT
    campaign_id,
    channel_code,
    channel_name,
    '${biz_date}' AS stat_date,
    SUM(CASE WHEN event_type = 'EXPOSURE' THEN 1 ELSE 0 END) AS impression_count,
    SUM(CASE WHEN event_type = 'CLICK'    THEN 1 ELSE 0 END) AS click_count,
    SUM(CASE WHEN event_type = 'PAY'      THEN 1 ELSE 0 END) AS conversion_count,
    SUM(investment_amount) AS investment_amount,
    SUM(revenue_amount) AS revenue_amount
FROM ${db}.marketing_channel_event
WHERE stat_date = '${biz_date}'
GROUP BY campaign_id, channel_code, channel_name
"""

# SQL 模板：写入 marketing_channel_stat 表
SQL_LOAD_CHANNEL_STAT = """
INSERT INTO ${db}.marketing_channel_stat
SELECT
    UUID() AS stat_id,
    campaign_id,
    channel_code,
    channel_name,
    stat_date,
    impression_count,
    click_count,
    conversion_count,
    investment_amount,
    revenue_amount,
    ctr,
    cvr,
    cpc,
    cpa,
    roas,
    ROW_NUMBER() OVER (ORDER BY roas DESC) AS channel_rank,
    NOW() AS computed_at,
    NOW() AS created_at
FROM ${db}.tmp_channel_stat_computed
"""


def build_dag() -> dict[str, Any]:
    """构建 ROI 分析 DAG 定义."""
    return {
        "dag_id": DAG_ID,
        "name": DAG_NAME,
        "description": DAG_DESCRIPTION,
        "schedule": DAG_SCHEDULE,
        "timeout": DAG_TIMEOUT,
        "tasks": [
            {"name": "aggregate_campaign_roi", "sql": SQL_AGGREGATE_CAMPAIGN_ROI},
            {"name": "compute_roi", "python_callable": compute_roi},
            {"name": "load_roi", "sql": SQL_LOAD_ROI},
            {"name": "aggregate_channel_stat", "sql": SQL_AGGREGATE_CHANNEL_STAT},
            {"name": "compute_channel_stat", "python_callable": compute_channel_stat},
            {"name": "load_channel_stat", "sql": SQL_LOAD_CHANNEL_STAT},
        ],
    }


if __name__ == "__main__":
    # 本地测试
    result = compute_roi(
        campaign_id="camp_001",
        stat_date="2026-08-08",
        investment_amount=10000,
        revenue_amount=35000,
        conversion_count=350,
        click_count=5000,
        impression_count=100000,
    )
    print(f"活动 {result['campaign_id']}: ROI={result['roi']:.4f}, "
          f"ROAS={result['roas']:.4f}, CPA={result['cpa']:.2f}, "
          f"CPC={result['cpc']:.2f}, 盈利={result['is_profitable']}")