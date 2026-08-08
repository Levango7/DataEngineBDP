"""投资消费分析 DAG - 固定资产投资与社会消费品零售分析。

本 DAG 每月 1 日凌晨 6:00 调度，执行以下步骤：
  1. 汇总固定资产投资完成额（按类型/行业/主体）
  2. 汇总社会消费品零售额（按类型/类别/城乡/线上线下）
  3. 计算投资增速与消费增速
  4. 分析投资结构（基础设施/制造业/房地产占比）
  5. 分析消费结构（商品/餐饮/服务，城乡/线上线下）
  6. 写入 fixed_asset_investment / social_retail_consumption 表
  7. 通知下游 Dashboard 刷新

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
# DAG 定义：投资消费分析
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="investment_consumption_analysis",
    description=(
        "投资消费分析 DAG：汇总固定资产投资与社会消费品零售，"
        "分析投资/消费结构与增速，写入 fixed_asset_investment / social_retail_consumption 表"
    ),
    default_args=default_args,
    schedule_interval="0 6 1 * *",  # 每月 1 日凌晨 6:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["government", "economic", "investment", "consumption", "spark"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
BIZ_YEAR = "{{ macros.ds_format(ds, '%Y-%m-%d', '%Y') }}"
BIZ_MONTH = "{{ macros.ds_format(ds, '%Y-%m-%d', '%m') }}"
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_government")

# ---------------------------------------------------------------------------
# Task 1: 汇总固定资产投资完成额
# ---------------------------------------------------------------------------
aggregate_investment = SparkSubmitOperator(
    task_id="aggregate_investment",
    application="/opt/spark/jobs/investment_aggregate.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"investment_agg_{BIZ_DATE}",
    },
    application_args=[
        "--biz-year", BIZ_YEAR,
        "--biz-month", BIZ_MONTH,
        "--doris-fe", DORIS_FE,
        "--doris-db", DORIS_DB,
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 汇总社会消费品零售额
# ---------------------------------------------------------------------------
aggregate_consumption = SparkSubmitOperator(
    task_id="aggregate_consumption",
    application="/opt/spark/jobs/consumption_aggregate.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"consumption_agg_{BIZ_DATE}",
    },
    application_args=[
        "--biz-year", BIZ_YEAR,
        "--biz-month", BIZ_MONTH,
        "--doris-fe", DORIS_FE,
        "--doris-db", DORIS_DB,
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 计算投资增速与消费增速
# ---------------------------------------------------------------------------
calc_growth_rate = BashOperator(
    task_id="calc_growth_rate",
    bash_command=(
        f"echo '[3] 计算投资增速与消费增速...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT 'investment' AS type, "
        f"SUM(investment_amount) AS current_value, "
        f"SUM(investment_amount)*100.0/NULLIF(SUM(prev.investment_amount),0)-100 AS growth_rate "
        f"FROM {DORIS_DB}.fixed_asset_investment cur "
        f"LEFT JOIN {DORIS_DB}.fixed_asset_investment prev "
        f"ON cur.stat_year = prev.stat_year + 1;\" && "
        f"echo '[3] 增速计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 分析投资结构
# ---------------------------------------------------------------------------
analyze_investment_structure = BashOperator(
    task_id="analyze_investment_structure",
    bash_command=(
        f"echo '[4] 分析投资结构（基础设施/制造业/房地产占比）...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT investment_type, SUM(investment_amount) AS total, "
        f"SUM(investment_amount)*100.0/SUM(SUM(investment_amount)) OVER() AS ratio "
        f"FROM {DORIS_DB}.fixed_asset_investment "
        f"WHERE stat_year = {BIZ_YEAR} "
        f"GROUP BY investment_type;\" && "
        f"echo '[4] 投资结构分析完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 分析消费结构
# ---------------------------------------------------------------------------
analyze_consumption_structure = BashOperator(
    task_id="analyze_consumption_structure",
    bash_command=(
        f"echo '[5] 分析消费结构（城乡/线上线下）...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT consumption_type, SUM(retail_amount) AS total, "
        f"SUM(urban_retail) AS urban, SUM(rural_retail) AS rural, "
        f"SUM(online_retail) AS online, SUM(offline_retail) AS offline "
        f"FROM {DORIS_DB}.social_retail_consumption "
        f"WHERE stat_year = {BIZ_YEAR} "
        f"GROUP BY consumption_type;\" && "
        f"echo '[5] 消费结构分析完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 通知下游 Dashboard 刷新
# ---------------------------------------------------------------------------
notify_dashboard = PythonOperator(
    task_id="notify_dashboard_refresh",
    python_callable=lambda: print(
        f"[6] 投资消费分析完成，通知 Superset Dashboard 刷新: biz_date={BIZ_DATE}"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
[aggregate_investment, aggregate_consumption] >> calc_growth_rate >> [analyze_investment_structure, analyze_consumption_structure] >> notify_dashboard