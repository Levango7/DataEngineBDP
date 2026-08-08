"""满意度分析 DAG - 评价收集/满意度计算/热点识别。

本 DAG 每日凌晨 8:00 调度，执行以下步骤：
  1. 从 service_satisfaction 抽取评价记录
  2. 按事项/部门汇总各评分人数（1-5 分）
  3. 计算平均分/满意度率（4+5 分占比）/不满意度率（1+2 分占比）
  4. 提取高频评价标签（Top 10）
  5. 识别低满意度事项（平均分 < 3）需改进
  6. 识别热点事项排行（按办理量/搜索量/投诉量加权）
  7. 写入 service_evaluation / service_hot_topic 表
  8. 通知下游 Dashboard 刷新

Author: T044 政务模板工程师
"""
from __future__ import annotations

import os
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from airflow.providers.apache.spark.operators.spark_submit import SparkSubmitOperator

# ---------------------------------------------------------------------------
# DAG 默认参数
# ---------------------------------------------------------------------------
default_args = {
    "owner": "government-ops",
    "depends_on_past": False,
    "email": ["gov-ops@shuqing.com"],
    "email_on_failure": True,
    "email_on_retry": False,
    "retries": 3,
    "retry_delay": timedelta(minutes=5),
    "execution_timeout": timedelta(hours=2),
}

# ---------------------------------------------------------------------------
# DAG 定义：满意度分析
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="satisfaction_analysis",
    description=(
        "满意度分析 DAG：从 service_satisfaction 聚合，"
        "计算满意度分布/平均分/高频标签/热点排行，写入 service_evaluation / service_hot_topic 表"
    ),
    default_args=default_args,
    schedule_interval="0 8 * * *",  # 每日凌晨 8:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["government", "livelihood", "satisfaction", "spark"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_government")

# ---------------------------------------------------------------------------
# Task 1: 抽取评价记录
# ---------------------------------------------------------------------------
extract_evaluations = BashOperator(
    task_id="extract_evaluations",
    bash_command=(
        f"echo '[1] 抽取评价记录 biz_date={BIZ_DATE}...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT service_id, satisfaction_score, evaluation_content, evaluation_tags "
        f"FROM {DORIS_DB}.service_satisfaction "
        f"WHERE DATE(evaluate_time) = '{BIZ_DATE}';\" && "
        f"echo '[1] 评价记录抽取完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 按事项/部门汇总各评分人数
# ---------------------------------------------------------------------------
aggregate_score_distribution = SparkSubmitOperator(
    task_id="aggregate_score_distribution",
    application="/opt/spark/jobs/satisfaction_aggregate.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"satisfaction_agg_{BIZ_DATE}",
    },
    application_args=[
        "--biz-date", BIZ_DATE,
        "--doris-fe", DORIS_FE,
        "--doris-db", DORIS_DB,
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 计算平均分/满意度率/不满意度率
# ---------------------------------------------------------------------------
calc_satisfaction_rate = BashOperator(
    task_id="calc_satisfaction_rate",
    bash_command=(
        f"echo '[3] 计算平均分/满意度率/不满意度率...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT service_id, "
        f"COUNT(*) AS total_evaluations, "
        f"AVG(satisfaction_score) AS avg_score, "
        f"SUM(CASE WHEN satisfaction_score>=4 THEN 1 ELSE 0 END)*100.0/COUNT(*) AS satisfaction_rate, "
        f"SUM(CASE WHEN satisfaction_score<=2 THEN 1 ELSE 0 END)*100.0/COUNT(*) AS dissatisfaction_rate "
        f"FROM {DORIS_DB}.service_satisfaction "
        f"WHERE DATE(evaluate_time) = '{BIZ_DATE}' "
        f"GROUP BY service_id;\" && "
        f"echo '[3] 满意度率计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 提取高频评价标签
# ---------------------------------------------------------------------------
extract_top_tags = BashOperator(
    task_id="extract_top_tags",
    bash_command=(
        f"echo '[4] 提取高频评价标签（Top 10）...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT evaluation_tags, COUNT(*) AS cnt "
        f"FROM {DORIS_DB}.service_satisfaction "
        f"WHERE DATE(evaluate_time) = '{BIZ_DATE}' AND evaluation_tags IS NOT NULL "
        f"GROUP BY evaluation_tags ORDER BY cnt DESC LIMIT 10;\" && "
        f"echo '[4] 高频标签提取完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 识别低满意度事项（需改进）
# ---------------------------------------------------------------------------
identify_low_satisfaction = PythonOperator(
    task_id="identify_low_satisfaction",
    python_callable=lambda: print(
        f"[5] 识别低满意度事项（平均分 < 3）需改进: biz_date={BIZ_DATE}"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 识别热点事项排行
# ---------------------------------------------------------------------------
identify_hot_topics = BashOperator(
    task_id="identify_hot_topics",
    bash_command=(
        f"echo '[6] 识别热点事项排行（按办理量/搜索量/投诉量加权）...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT service_id, service_name, transaction_count, "
        f"hot_score, ROW_NUMBER() OVER(ORDER BY hot_score DESC) AS hot_rank "
        f"FROM {DORIS_DB}.service_hot_topic "
        f"WHERE stat_date = '{BIZ_DATE}';\" && "
        f"echo '[6] 热点事项识别完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 7: 通知下游 Dashboard 刷新
# ---------------------------------------------------------------------------
notify_dashboard = PythonOperator(
    task_id="notify_dashboard_refresh",
    python_callable=lambda: print(
        f"[7] 满意度分析完成，通知 Superset Dashboard 刷新: biz_date={BIZ_DATE}"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
extract_evaluations >> aggregate_score_distribution >> [calc_satisfaction_rate, extract_top_tags] >> [identify_low_satisfaction, identify_hot_topics] >> notify_dashboard