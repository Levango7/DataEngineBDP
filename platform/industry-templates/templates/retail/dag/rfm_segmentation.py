"""RFM 分群 DAG - 零售行业模板 T038.

功能：基于会员购买行为计算 RFM 模型分群
  - R (Recency)：最近购买距今天数（越小越优）
  - F (Frequency)：统计周期内购买次数
  - M (Monetary)：统计周期内累计消费金额
  - RFM 评分：R/F/M 各 1~5 分，组合为 125~555
  - RFM 分群：10 个分群（CHAMPION/LOYAL/POTENTIAL_LOYAL/NEW/PROMISING/
            NEED_ATTENTION/ABOUT_TO_SLEEP/HIBERNATING/LOST/LOST_CHEAP）

调度：每日凌晨 02:00 执行，统计周期默认 365 天

依赖：member 表（会员主数据）+ 订单事实表（外部）
输出：member_rfm 表（按日快照）+ member_tag 表（RFM 标签）

接入标签引擎：将 RFM 分群结果写入 member_tag 表，tag_category=RFM
"""

from __future__ import annotations

from typing import Any

# DolphinScheduler / Airflow 风格的 DAG 定义
# 实际部署时由 DolphinScheduler 导入并调度执行

DAG_ID = "rfm_segmentation"
DAG_NAME = "RFM 分群"
DAG_DESCRIPTION = "基于会员购买行为计算 RFM 模型分群（R/F/M 各 1~5 分，10 个分群）"
DAG_SCHEDULE = "0 2 * * ?"  # 每日凌晨 02:00
DAG_TIMEOUT = 7200  # 2 小时

# RFM 分群定义（10 个分群）
RFM_SEGMENTS: dict[str, dict[str, Any]] = {
    "CHAMPION": {
        "desc": "冠军：最近购买 + 高频 + 高金额",
        "condition": {"R": [4, 5], "F": [4, 5], "M": [4, 5]},
        "value_level": "HIGH",
    },
    "LOYAL": {
        "desc": "忠诚：高频 + 高金额（不要求最近）",
        "condition": {"R": [2, 5], "F": [4, 5], "M": [4, 5]},
        "value_level": "HIGH",
    },
    "POTENTIAL_LOYAL": {
        "desc": "潜力忠诚：最近购买 + 高频（金额不限）",
        "condition": {"R": [4, 5], "F": [3, 5], "M": [1, 5]},
        "value_level": "MEDIUM",
    },
    "NEW": {
        "desc": "新客：最近购买 + 低频",
        "condition": {"R": [4, 5], "F": [1, 2], "M": [1, 5]},
        "value_level": "MEDIUM",
    },
    "PROMISING": {
        "desc": "有潜力：最近购买 + 中频 + 中金额",
        "condition": {"R": [3, 5], "F": [2, 3], "M": [2, 3]},
        "value_level": "MEDIUM",
    },
    "NEED_ATTENTION": {
        "desc": "需关注：中频 + 中金额（最近不限）",
        "condition": {"R": [2, 5], "F": [2, 3], "M": [2, 3]},
        "value_level": "MEDIUM",
    },
    "ABOUT_TO_SLEEP": {
        "desc": "将沉睡：较久未购买 + 中频",
        "condition": {"R": [2, 3], "F": [2, 5], "M": [2, 5]},
        "value_level": "LOW",
    },
    "HIBERNATING": {
        "desc": "沉睡：久未购买 + 低频",
        "condition": {"R": [1, 2], "F": [1, 2], "M": [1, 5]},
        "value_level": "LOW",
    },
    "LOST": {
        "desc": "流失：久未购买 + 低频 + 低金额",
        "condition": {"R": [1, 2], "F": [1, 2], "M": [1, 2]},
        "value_level": "LOW",
    },
    "LOST_CHEAP": {
        "desc": "低价值流失：久未购买 + 低频 + 极低金额",
        "condition": {"R": [1, 2], "F": [1, 1], "M": [1, 1]},
        "value_level": "LOW",
    },
}


def compute_rfm_scores(
    recency_days: int,
    frequency: int,
    monetary: float,
    r_quantiles: list[float],
    f_quantiles: list[float],
    m_quantiles: list[float],
) -> tuple[int, int, int]:
    """根据分位数计算 R/F/M 评分（1~5）.

    Args:
        recency_days: 最近购买距今天数（越小越优）
        frequency: 购买次数（越大越优）
        monetary: 累计消费金额（越大越优）
        r_quantiles: R 分位数 [20%, 40%, 60%, 80%]（递增）
        f_quantiles: F 分位数 [20%, 40%, 60%, 80%]（递增）
        m_quantiles: M 分位数 [20%, 40%, 60%, 80%]（递增）

    Returns:
        (r_score, f_score, m_score) 各 1~5
    """
    # R 越小越优（反向评分）
    r_score = 5 - sum(1 for q in r_quantiles if recency_days > q)
    # F/M 越大越优（正向评分）
    f_score = 1 + sum(1 for q in f_quantiles if frequency > q)
    m_score = 1 + sum(1 for q in m_quantiles if monetary > q)
    # 边界裁剪
    r_score = max(1, min(5, r_score))
    f_score = max(1, min(5, f_score))
    m_score = max(1, min(5, m_score))
    return r_score, f_score, m_score


def assign_rfm_segment(r_score: int, f_score: int, m_score: int) -> tuple[str, str]:
    """根据 R/F/M 评分分群.

    Args:
        r_score: R 评分 1~5
        f_score: F 评分 1~5
        m_score: M 评分 1~5

    Returns:
        (segment_code, value_level)
    """
    # 按优先级匹配分群（顺序重要）
    if r_score >= 4 and f_score >= 4 and m_score >= 4:
        return "CHAMPION", "HIGH"
    if f_score >= 4 and m_score >= 4:
        return "LOYAL", "HIGH"
    if r_score >= 4 and f_score >= 3:
        return "POTENTIAL_LOYAL", "MEDIUM"
    if r_score >= 4 and f_score <= 2:
        return "NEW", "MEDIUM"
    if r_score >= 3 and 2 <= f_score <= 3 and 2 <= m_score <= 3:
        return "PROMISING", "MEDIUM"
    if 2 <= f_score <= 3 and 2 <= m_score <= 3:
        return "NEED_ATTENTION", "MEDIUM"
    if r_score <= 3 and f_score >= 2:
        return "ABOUT_TO_SLEEP", "LOW"
    if r_score <= 2 and f_score <= 2:
        return "HIBERNATING", "LOW"
    if r_score <= 2 and f_score <= 2 and m_score <= 2:
        return "LOST", "LOW"
    return "LOST_CHEAP", "LOW"


def compute_rfm_for_member(
    member_id: str,
    recency_days: int,
    frequency: int,
    monetary: float,
    r_quantiles: list[float],
    f_quantiles: list[float],
    m_quantiles: list[float],
) -> dict[str, Any]:
    """计算单个会员的 RFM 指标.

    Returns:
        包含 rfm_score / rfm_segment / segment_value_level 等字段的 dict
    """
    r_score, f_score, m_score = compute_rfm_scores(
        recency_days, frequency, monetary, r_quantiles, f_quantiles, m_quantiles
    )
    rfm_score = r_score * 100 + f_score * 10 + m_score
    segment, value_level = assign_rfm_segment(r_score, f_score, m_score)
    avg_order_value = monetary / frequency if frequency > 0 else 0.0
    return {
        "member_id": member_id,
        "recency_days": recency_days,
        "frequency": frequency,
        "monetary": monetary,
        "avg_order_value": avg_order_value,
        "r_score": r_score,
        "f_score": f_score,
        "m_score": m_score,
        "rfm_score": rfm_score,
        "rfm_segment": segment,
        "segment_value_level": value_level,
    }


# SQL 模板：聚合会员 RFM 原始指标
SQL_EXTRACT_RFM_RAW = """
SELECT
    m.member_id,
    DATEDIFF('${biz_date}', MAX(o.pay_time)) AS recency_days,
    COUNT(DISTINCT o.order_id) AS frequency,
    SUM(o.pay_amount) AS monetary
FROM ${db}.member m
INNER JOIN ${db}.order_fact o ON m.member_id = o.member_id
WHERE o.pay_time >= DATE_SUB('${biz_date}', INTERVAL ${stat_period_days} DAY)
  AND o.pay_time <= '${biz_date}'
  AND o.status = 'PAID'
GROUP BY m.member_id
"""

# SQL 模板：计算 R/F/M 分位数（用于评分）
SQL_COMPUTE_QUANTILES = """
SELECT
    PERCENTILE_CONT(0.2) WITHIN GROUP (ORDER BY recency_days) AS r_q20,
    PERCENTILE_CONT(0.4) WITHIN GROUP (ORDER BY recency_days) AS r_q40,
    PERCENTILE_CONT(0.6) WITHIN GROUP (ORDER BY recency_days) AS r_q60,
    PERCENTILE_CONT(0.8) WITHIN GROUP (ORDER BY recency_days) AS r_q80,
    PERCENTILE_CONT(0.2) WITHIN GROUP (ORDER BY frequency)   AS f_q20,
    PERCENTILE_CONT(0.4) WITHIN GROUP (ORDER BY frequency)   AS f_q40,
    PERCENTILE_CONT(0.6) WITHIN GROUP (ORDER BY frequency)   AS f_q60,
    PERCENTILE_CONT(0.8) WITHIN GROUP (ORDER BY frequency)   AS f_q80,
    PERCENTILE_CONT(0.2) WITHIN GROUP (ORDER BY monetary)    AS m_q20,
    PERCENTILE_CONT(0.4) WITHIN GROUP (ORDER BY monetary)    AS m_q40,
    PERCENTILE_CONT(0.6) WITHIN GROUP (ORDER BY monetary)    AS m_q60,
    PERCENTILE_CONT(0.8) WITHIN GROUP (ORDER BY monetary)    AS m_q80
FROM ${db}.tmp_rfm_raw
"""

# SQL 模板：写入 member_rfm 表
SQL_LOAD_MEMBER_RFM = """
INSERT INTO ${db}.member_rfm
SELECT
    UUID() AS rfm_id,
    member_id,
    '${biz_date}' AS stat_date,
    recency_days,
    frequency,
    monetary,
    avg_order_value,
    r_score,
    f_score,
    m_score,
    rfm_score,
    rfm_segment,
    segment_value_level,
    ${stat_period_days} AS stat_period_days,
    NOW() AS computed_at,
    NOW() AS created_at
FROM ${db}.tmp_rfm_scored
"""

# SQL 模板：将 RFM 分群写入 member_tag 表（接入标签引擎）
SQL_LOAD_RFM_TAGS = """
INSERT INTO ${db}.member_tag (
    tag_id, member_id, tag_code, tag_value, tag_category,
    tag_source, confidence, tagged_at, created_at, updated_at, created_by, updated_by
)
SELECT
    UUID() AS tag_id,
    member_id,
    CONCAT('RFM_', rfm_segment) AS tag_code,
    rfm_segment AS tag_value,
    'RFM' AS tag_category,
    'MODEL' AS tag_source,
    1.0000 AS confidence,
    '${biz_date}' AS tagged_at,
    NOW() AS created_at,
    NOW() AS updated_at,
    'dag_rfm_segmentation' AS created_by,
    'dag_rfm_segmentation' AS updated_by
FROM ${db}.tmp_rfm_scored
"""


def build_dag() -> dict[str, Any]:
    """构建 RFM 分群 DAG 定义（DolphinScheduler JSON 格式）."""
    return {
        "dag_id": DAG_ID,
        "name": DAG_NAME,
        "description": DAG_DESCRIPTION,
        "schedule": DAG_SCHEDULE,
        "timeout": DAG_TIMEOUT,
        "tasks": [
            {"name": "extract_rfm_raw", "sql": SQL_EXTRACT_RFM_RAW},
            {"name": "compute_quantiles", "sql": SQL_COMPUTE_QUANTILES},
            {"name": "score_rfm", "python_callable": compute_rfm_for_member},
            {"name": "load_member_rfm", "sql": SQL_LOAD_MEMBER_RFM},
            {"name": "load_rfm_tags", "sql": SQL_LOAD_RFM_TAGS},
        ],
    }


if __name__ == "__main__":
    # 本地测试：验证 RFM 分群逻辑
    r_quantiles = [30, 60, 120, 240]  # R 分位数（天数）
    f_quantiles = [1, 3, 6, 12]  # F 分位数（次数）
    m_quantiles = [100, 500, 2000, 5000]  # M 分位数（金额）

    # 测试用例
    test_cases = [
        ("m001", 7, 20, 8000),  # 冠军
        ("m002", 200, 1, 50),  # 流失
        ("m003", 10, 1, 200),  # 新客
    ]
    for member_id, r, f, m in test_cases:
        result = compute_rfm_for_member(member_id, r, f, m, r_quantiles, f_quantiles, m_quantiles)
        print(
            f"{member_id}: R={result['r_score']} F={result['f_score']} "
            f"M={result['m_score']} → {result['rfm_segment']} ({result['segment_value_level']})"
        )
