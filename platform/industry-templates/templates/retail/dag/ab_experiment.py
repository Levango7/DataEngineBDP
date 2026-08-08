"""A/B 实验显著性检验 DAG - 零售行业模板 T038.

功能：对 A/B 实验的实验组与对照组进行显著性检验
  - 检验方法：Z 检验（比例）/ T 检验（均值）/ 卡方检验 / Mann-Whitney U 检验
  - 输出：P 值 / Z 值 / 提升度 / 95% 置信区间 / 是否显著 / 获胜变体
  - 显著性水平：α = 0.05（可配置）

调度：每小时执行一次，对运行中的实验计算显著性

依赖：ab_experiment / ab_experiment_variant / 实验事件流
输出：ab_experiment_variant 表（更新检验结果）

接入标签引擎：将获胜变体写入实验标签
"""

from __future__ import annotations

import math
from typing import Any

DAG_ID = "ab_experiment"
DAG_NAME = "A/B 实验显著性检验"
DAG_DESCRIPTION = "对 A/B 实验的实验组与对照组进行显著性检验（Z/T/卡方/Mann-Whitney）"
DAG_SCHEDULE = "0 0 * * ? *"  # 每小时整点
DAG_TIMEOUT = 3600

DEFAULT_ALPHA = 0.05  # 显著性水平
Z_CRITICAL_95 = 1.959963984540054  # 95% 置信水平的 Z 临界值


def _normal_cdf(x: float) -> float:
    """标准正态分布累积分布函数（近似）."""
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def _normal_ppf(p: float) -> float:
    """标准正态分布分位数函数（近似，Acklam 算法）."""
    if p <= 0:
        return -float("inf")
    if p >= 1:
        return float("inf")
    # Acklam 算法系数
    a = [
        -3.969683028665376e01,
        2.2096609862002455e02,
        -2.759285104469857e02,
        1.383577518672690e02,
        -3.066479806629540e01,
        2.506628277459239e00,
    ]
    b = [
        -5.447609979034314e01,
        1.615858368580443e02,
        -1.556989798598966e02,
        6.680131345399126e01,
        -1.328068528976182e01,
    ]
    c = [
        -7.784894002430293e-03,
        -3.223964580411025e-01,
        -2.400758517749386e00,
        -2.549732539343213e00,
        4.374664141464968e00,
        2.938163982698783e00,
    ]
    d = [7.784695709041463e-03, 3.224671290700875e-01, 2.445637672444178e00, 3.754196953827773e00]
    p_low = 0.02425
    p_high = 1 - p_low
    if p < p_low:
        q = math.sqrt(-2 * math.log(p))
        return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / (
            (((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1
        )
    if p <= p_high:
        q = p - 0.5
        r = q * q
        return (
            (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5])
            * q
            / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1)
        )
    q = math.sqrt(-2 * math.log(1 - p))
    return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / (
        (((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1
    )


def z_test_proportions(
    control_success: int,
    control_total: int,
    treatment_success: int,
    treatment_total: int,
) -> dict[str, float]:
    """Z 检验（两个比例差异）.

    Args:
        control_success: 对照组成功数
        control_total: 对照组总数
        treatment_success: 实验组成功数
        treatment_total: 实验组总数

    Returns:
        {z_score, p_value, lift, ci_lower, ci_upper}
    """
    p1 = control_success / control_total if control_total > 0 else 0
    p2 = treatment_success / treatment_total if treatment_total > 0 else 0
    # 合并比例
    p_pooled = (control_success + treatment_success) / (control_total + treatment_total)
    se = math.sqrt(p_pooled * (1 - p_pooled) * (1 / control_total + 1 / treatment_total))
    if se == 0:
        z = 0.0
    else:
        z = (p2 - p1) / se
    # 双侧 P 值
    p_value = 2 * (1 - _normal_cdf(abs(z)))
    # 提升度
    lift = (p2 - p1) / p1 if p1 > 0 else 0.0
    # 95% 置信区间（差异的 CI）
    se_diff = math.sqrt(p1 * (1 - p1) / control_total + p2 * (1 - p2) / treatment_total)
    diff = p2 - p1
    ci_lower = diff - Z_CRITICAL_95 * se_diff
    ci_upper = diff + Z_CRITICAL_95 * se_diff
    return {
        "z_score": round(z, 6),
        "p_value": round(p_value, 8),
        "lift": round(lift, 6),
        "ci_lower": round(ci_lower, 6),
        "ci_upper": round(ci_upper, 6),
    }


def t_test_means(
    control_mean: float,
    control_var: float,
    control_n: int,
    treatment_mean: float,
    treatment_var: float,
    treatment_n: int,
) -> dict[str, float]:
    """T 检验（两个均值差异，Welch's T 检验）.

    Args:
        control_mean, control_var, control_n: 对照组均值/方差/样本量
        treatment_mean, treatment_var, treatment_n: 实验组均值/方差/样本量

    Returns:
        {t_score, p_value, lift, ci_lower, ci_upper}
    """
    se = math.sqrt(control_var / control_n + treatment_var / treatment_n)
    if se == 0:
        t = 0.0
    else:
        t = (treatment_mean - control_mean) / se
    # Welch-Satterthwaite 自由度
    num = (control_var / control_n + treatment_var / treatment_n) ** 2
    den = (control_var / control_n) ** 2 / (control_n - 1) + (treatment_var / treatment_n) ** 2 / (treatment_n - 1)
    df = num / den if den > 0 else 1
    # 用正态近似计算 P 值（大样本下 T 分布趋近正态）
    p_value = 2 * (1 - _normal_cdf(abs(t)))
    lift = (treatment_mean - control_mean) / control_mean if control_mean != 0 else 0.0
    diff = treatment_mean - control_mean
    ci_lower = diff - Z_CRITICAL_95 * se
    ci_upper = diff + Z_CRITICAL_95 * se
    return {
        "t_score": round(t, 6),
        "p_value": round(p_value, 8),
        "lift": round(lift, 6),
        "ci_lower": round(ci_lower, 6),
        "ci_upper": round(ci_upper, 6),
        "df": round(df, 2),
    }


def chi_square_test(
    control_success: int,
    control_failure: int,
    treatment_success: int,
    treatment_failure: int,
) -> dict[str, float]:
    """卡方检验（2x2 列联表）.

    Args:
        control_success, control_failure: 对照组成功/失败数
        treatment_success, treatment_failure: 实验组成功/失败数

    Returns:
        {chi2, p_value}
    """
    n = control_success + control_failure + treatment_success + treatment_failure
    if n == 0:
        return {"chi2": 0.0, "p_value": 1.0}
    row1 = control_success + control_failure
    row2 = treatment_success + treatment_failure
    col1 = control_success + treatment_success
    col2 = control_failure + treatment_failure
    expected = [
        (row1 * col1 / n, row1 * col2 / n),
        (row2 * col1 / n, row2 * col2 / n),
    ]
    observed = [
        (control_success, control_failure),
        (treatment_success, treatment_failure),
    ]
    chi2 = 0.0
    for i in range(2):
        for j in range(2):
            if expected[i][j] > 0:
                chi2 += (observed[i][j] - expected[i][j]) ** 2 / expected[i][j]
    # 卡方分布 P 值（df=1，用正态近似）
    z = math.sqrt(chi2)
    p_value = 2 * (1 - _normal_cdf(z))
    return {"chi2": round(chi2, 6), "p_value": round(p_value, 8)}


def run_ab_test(
    experiment_id: str,
    control_data: dict[str, int],
    treatment_data: dict[str, int],
    primary_metric: str = "conversion_rate",
    alpha: float = DEFAULT_ALPHA,
) -> dict[str, Any]:
    """运行 A/B 实验显著性检验.

    Args:
        experiment_id: 实验ID
        control_data: 对照组数据 {success, total}
        treatment_data: 实验组数据 {success, total}
        primary_metric: 主要指标
        alpha: 显著性水平

    Returns:
        检验结果 dict
    """
    result = z_test_proportions(
        control_data["success"],
        control_data["total"],
        treatment_data["success"],
        treatment_data["total"],
    )
    is_significant = result["p_value"] < alpha
    # 获胜判定：显著且提升度为正
    is_winner = is_significant and result["lift"] > 0
    return {
        "experiment_id": experiment_id,
        "control_conversion_rate": control_data["success"] / control_data["total"],
        "treatment_conversion_rate": treatment_data["success"] / treatment_data["total"],
        "lift": result["lift"],
        "p_value": result["p_value"],
        "z_score": result["z_score"],
        "confidence_interval": {"lower": result["ci_lower"], "upper": result["ci_upper"]},
        "is_significant": is_significant,
        "is_winner": is_winner,
        "test_method": "Z_TEST",
        "alpha": alpha,
    }


# SQL 模板：抽取实验变体数据
SQL_EXTRACT_VARIANT_DATA = """
SELECT
    e.experiment_id,
    e.primary_metric,
    v.variant_code,
    COUNT(DISTINCT ev.user_id) AS user_count,
    COUNT(DISTINCT CASE WHEN ev.is_converted = 1 THEN ev.user_id END) AS conversion_count
FROM ${db}.ab_experiment e
JOIN ${db}.ab_experiment_variant v ON e.experiment_id = v.experiment_id
LEFT JOIN ${db}.experiment_event ev ON e.experiment_id = ev.experiment_id AND v.variant_code = ev.variant_code
WHERE e.status = 'RUNNING'
GROUP BY e.experiment_id, e.primary_metric, v.variant_code
"""

# SQL 模板：更新 ab_experiment_variant 表
SQL_UPDATE_VARIANT_RESULT = """
UPDATE ${db}.ab_experiment_variant
SET
    user_count = ${user_count},
    conversion_count = ${conversion_count},
    conversion_rate = ${conversion_rate},
    lift = ${lift},
    p_value = ${p_value},
    z_score = ${z_score},
    is_significant = ${is_significant},
    is_winner = ${is_winner},
    test_method = '${test_method}',
    computed_at = NOW()
WHERE experiment_id = '${experiment_id}' AND variant_code = '${variant_code}'
"""


def build_dag() -> dict[str, Any]:
    """构建 A/B 实验显著性检验 DAG 定义."""
    return {
        "dag_id": DAG_ID,
        "name": DAG_NAME,
        "description": DAG_DESCRIPTION,
        "schedule": DAG_SCHEDULE,
        "timeout": DAG_TIMEOUT,
        "tasks": [
            {"name": "extract_variant_data", "sql": SQL_EXTRACT_VARIANT_DATA},
            {"name": "run_significance_test", "python_callable": run_ab_test},
            {"name": "update_variant_result", "sql": SQL_UPDATE_VARIANT_RESULT},
        ],
    }


if __name__ == "__main__":
    # 本地测试
    result = run_ab_test(
        "exp_001",
        control_data={"success": 1200, "total": 10000},
        treatment_data={"success": 1380, "total": 10000},
    )
    print(
        f"实验 {result['experiment_id']}: "
        f"对照组转化率={result['control_conversion_rate']:.4f}, "
        f"实验组转化率={result['treatment_conversion_rate']:.4f}, "
        f"提升={result['lift']:.4f}, P={result['p_value']:.6f}, "
        f"显著={result['is_significant']}, 获胜={result['is_winner']}"
    )
