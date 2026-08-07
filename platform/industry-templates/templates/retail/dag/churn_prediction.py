"""流失预测 DAG - 零售行业模板 T038.

功能：基于机器学习二分类模型预测会员流失概率
  - 模型：逻辑回归 + GBDT 集成（Soft Voting）
  - 特征：RFM 指标 + 行为画像 + 人口属性
  - 输出：流失概率 / 流失标签 / 风险等级 / 特征重要性
  - 风险等级：HIGH (>0.7) / MEDIUM (0.3~0.7) / LOW (<0.3)

调度：每日凌晨 03:00 执行，预测未来 30 天流失

依赖：member / member_rfm / member_behavior_profile 表
输出：member_churn_prediction 表 + member_tag 表（CHURN 标签）

接入标签引擎：将流失风险等级写入 member_tag 表，tag_category=CHURN
"""
from __future__ import annotations

import math
from typing import Any

DAG_ID = "churn_prediction"
DAG_NAME = "流失预测"
DAG_DESCRIPTION = "基于机器学习二分类模型（逻辑回归 + GBDT 集成）预测会员流失概率"
DAG_SCHEDULE = "0 3 * * ?"  # 每日凌晨 03:00
DAG_TIMEOUT = 10800  # 3 小时

MODEL_NAME = "churn_lr_gbdt_ensemble_v1"
MODEL_VERSION = "1.0.0"
PREDICTION_WINDOW_DAYS = 30
DEFAULT_THRESHOLD = 0.5

# 流失定义：未来 30 天内无任何购买行为
CHURN_DEFINITION = "未来 30 天内无任何购买行为"


def sigmoid(x: float) -> float:
    """Sigmoid 激活函数."""
    if x >= 0:
        z = math.exp(-x)
        return 1.0 / (1.0 + z)
    z = math.exp(x)
    return z / (1.0 + z)


def logistic_regression_predict(
    features: dict[str, float], weights: dict[str, float], bias: float = 0.0
) -> float:
    """逻辑回归预测.

    Args:
        features: 特征字典 {feature_name: value}
        weights: 权重字典 {feature_name: weight}
        bias: 偏置项

    Returns:
        流失概率 (0~1)
    """
    linear_output = bias
    for feat, value in features.items():
        linear_output += weights.get(feat, 0.0) * value
    return sigmoid(linear_output)


def gbdt_predict(
    features: dict[str, float], tree_outputs: list[float]
) -> float:
    """GBDT 预测（简化版：累加各树输出后 sigmoid）.

    Args:
        features: 特征字典
        tree_outputs: 各树的输出值（实际应由决策树遍历得到）

    Returns:
        流失概率 (0~1)
    """
    # 简化：直接累加树输出
    total = sum(tree_outputs)
    return sigmoid(total)


def ensemble_predict(
    lr_prob: float, gbdt_prob: float, lr_weight: float = 0.4, gbdt_weight: float = 0.6
) -> float:
    """集成预测（Soft Voting）.

    Args:
        lr_prob: 逻辑回归概率
        gbdt_prob: GBDT 概率
        lr_weight: 逻辑回归权重
        gbdt_weight: GBDT 权重

    Returns:
        集成概率 (0~1)
    """
    return lr_weight * lr_prob + gbdt_weight * gbdt_prob


def classify_risk_level(probability: float) -> str:
    """根据流失概率分类风险等级.

    Args:
        probability: 流失概率 (0~1)

    Returns:
        风险等级：HIGH / MEDIUM / LOW
    """
    if probability > 0.7:
        return "HIGH"
    if probability >= 0.3:
        return "MEDIUM"
    return "LOW"


def predict_churn_for_member(
    member_id: str,
    features: dict[str, float],
    lr_weights: dict[str, float],
    lr_bias: float,
    gbdt_tree_outputs: list[float],
    threshold: float = DEFAULT_THRESHOLD,
) -> dict[str, Any]:
    """预测单个会员的流失概率.

    Args:
        member_id: 会员ID
        features: 特征字典
        lr_weights: 逻辑回归权重
        lr_bias: 逻辑回归偏置
        gbdt_tree_outputs: GBDT 各树输出
        threshold: 二分类阈值

    Returns:
        预测结果 dict
    """
    lr_prob = logistic_regression_predict(features, lr_weights, lr_bias)
    gbdt_prob = gbdt_predict(features, gbdt_tree_outputs)
    ensemble_prob = ensemble_predict(lr_prob, gbdt_prob)
    churn_label = "YES" if ensemble_prob >= threshold else "NO"
    risk_level = classify_risk_level(ensemble_prob)
    return {
        "member_id": member_id,
        "churn_probability": round(ensemble_prob, 4),
        "churn_label": churn_label,
        "risk_level": risk_level,
        "model_name": MODEL_NAME,
        "model_version": MODEL_VERSION,
        "prediction_window": PREDICTION_WINDOW_DAYS,
        "threshold": threshold,
    }


# 默认特征权重（逻辑回归，实际由训练得到）
DEFAULT_LR_WEIGHTS = {
    "recency_days": 0.025,  # 最近购买间隔越大，流失风险越高
    "frequency": -0.15,  # 购买频率越高，流失风险越低
    "monetary": -0.0001,  # 累计金额越高，流失风险越低
    "login_count_30d": -0.08,  # 近 30 天登录次数越多，流失风险越低
    "browse_count_30d": -0.02,  # 近 30 天浏览次数越多，流失风险越低
    "cart_count_30d": -0.05,  # 近 30 天加购次数越多，流失风险越低
    "days_since_last_purchase": 0.02,  # 距上次购买天数越多，流失风险越高
    "return_rate": 0.3,  # 退货率越高，流失风险越高
}
DEFAULT_LR_BIAS = -1.5

# SQL 模板：抽取流失预测特征
SQL_EXTRACT_FEATURES = """
SELECT
    m.member_id,
    r.recency_days,
    r.frequency,
    r.monetary,
    b.login_count_30d,
    b.browse_count_30d,
    b.cart_count_30d,
    DATEDIFF('${biz_date}', m.last_purchase_at) AS days_since_last_purchase,
    b.return_rate
FROM ${db}.member m
LEFT JOIN ${db}.member_rfm r ON m.member_id = r.member_id AND r.stat_date = '${biz_date}'
LEFT JOIN ${db}.member_behavior_profile b ON m.member_id = b.member_id
WHERE m.status = 'ACTIVE'
"""

# SQL 模板：写入 member_churn_prediction 表
SQL_LOAD_PREDICTION = """
INSERT INTO ${db}.member_churn_prediction
SELECT
    UUID() AS prediction_id,
    member_id,
    '${biz_date}' AS predicted_at,
    churn_probability,
    churn_label,
    risk_level,
    '${model_name}' AS model_name,
    '${model_version}' AS model_version,
    feature_importance_json,
    ${prediction_window} AS prediction_window,
    ${threshold} AS threshold,
    NOW() AS computed_at,
    NOW() AS created_at
FROM ${db}.tmp_churn_predicted
"""

# SQL 模板：将流失风险写入 member_tag 表（接入标签引擎）
SQL_LOAD_CHURN_TAGS = """
INSERT INTO ${db}.member_tag (tag_id, member_id, tag_code, tag_value, tag_category, tag_source, confidence, tagged_at, created_at, updated_at, created_by, updated_by)
SELECT
    UUID(),
    member_id,
    CONCAT('CHURN_', risk_level, '_RISK'),
    risk_level,
    'CHURN',
    'MODEL',
    churn_probability,
    '${biz_date}',
    NOW(), NOW(),
    'dag_churn_prediction', 'dag_churn_prediction'
FROM ${db}.tmp_churn_predicted
"""


def build_dag() -> dict[str, Any]:
    """构建流失预测 DAG 定义."""
    return {
        "dag_id": DAG_ID,
        "name": DAG_NAME,
        "description": DAG_DESCRIPTION,
        "schedule": DAG_SCHEDULE,
        "timeout": DAG_TIMEOUT,
        "tasks": [
            {"name": "extract_features", "sql": SQL_EXTRACT_FEATURES},
            {"name": "lr_train_or_load", "type": "ML_TRAIN", "model": "logistic_regression"},
            {"name": "gbdt_train_or_load", "type": "ML_TRAIN", "model": "gbdt"},
            {"name": "predict_churn", "python_callable": predict_churn_for_member},
            {"name": "load_prediction", "sql": SQL_LOAD_PREDICTION},
            {"name": "load_churn_tags", "sql": SQL_LOAD_CHURN_TAGS},
        ],
    }


if __name__ == "__main__":
    # 本地测试：验证流失预测逻辑
    features = {
        "recency_days": 90,
        "frequency": 2,
        "monetary": 500,
        "login_count_30d": 1,
        "browse_count_30d": 3,
        "cart_count_30d": 0,
        "days_since_last_purchase": 90,
        "return_rate": 0.1,
    }
    result = predict_churn_for_member(
        "m001", features, DEFAULT_LR_WEIGHTS, DEFAULT_LR_BIAS, [0.5]
    )
    print(f"会员 {result['member_id']}: 流失概率={result['churn_probability']}, "
          f"标签={result['churn_label']}, 风险={result['risk_level']}")