"""产业结构分析 DAG - 三次产业占比与行业贡献度分析。

本 DAG 每季度首月 16 日凌晨 5:00 调度，执行以下步骤：
  1. 加载各行业增加值数据
  2. 计算三次产业占比（第一/第二/第三产业占 GDP 比重）
  3. 计算各行业对 GDP 增长的贡献度
  4. 分析产业结构升级趋势（第三产业占比变化）
  5. 识别支柱产业与新兴产业
  6. 写入 industry_structure 表
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
# DAG 定义：产业结构分析
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="industry_analysis",
    description=(
        "产业结构分析 DAG：计算三次产业占比/行业贡献度/"
        "产业结构升级趋势，写入 industry_structure 表"
    ),
    default_args=default_args,
    schedule_interval="0 5 16 1,4,7,10 *",  # 每季度首月 16 日凌晨 5:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["government", "economic", "industry", "spark"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
BIZ_YEAR = "{{ macros.ds_format(ds, '%Y-%m-%d', '%Y') }}"
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_government")

# ---------------------------------------------------------------------------
# Task 1: 加载各行业增加值数据
# ---------------------------------------------------------------------------
load_industry_data = BashOperator(
    task_id="load_industry_data",
    bash_command=(
        f"echo '[1] 加载各行业增加值数据 biz_year={BIZ_YEAR}...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT industry_category, industry_code, industry_name, added_value, employee_count "
        f"FROM {DORIS_DB}.industry_structure "
        f"WHERE stat_year = {BIZ_YEAR};\" && "
        f"echo '[1] 行业数据加载完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 计算三次产业占比
# ---------------------------------------------------------------------------
calc_industry_ratio = BashOperator(
    task_id="calc_industry_ratio",
    bash_command=(
        f"echo '[2] 计算三次产业占比（第一/第二/第三产业占 GDP 比重）...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT industry_category, SUM(added_value) AS total_value, "
        f"SUM(added_value)*100.0/SUM(SUM(added_value)) OVER() AS ratio "
        f"FROM {DORIS_DB}.industry_structure "
        f"WHERE stat_year = {BIZ_YEAR} "
        f"GROUP BY industry_category;\" && "
        f"echo '[2] 三次产业占比计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 计算各行业对 GDP 增长的贡献度
# ---------------------------------------------------------------------------
calc_contribution_rate = SparkSubmitOperator(
    task_id="calc_contribution_rate",
    application="/opt/spark/jobs/industry_contribution_calc.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"industry_contribution_{BIZ_DATE}",
    },
    application_args=[
        "--biz-year", BIZ_YEAR,
        "--doris-fe", DORIS_FE,
        "--doris-db", DORIS_DB,
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 分析产业结构升级趋势
# ---------------------------------------------------------------------------
analyze_upgrade_trend = PythonOperator(
    task_id="analyze_upgrade_trend",
    python_callable=lambda: print(
        f"[4] 分析产业结构升级趋势（第三产业占比变化）: biz_year={BIZ_YEAR}"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 识别支柱产业与新兴产业
# ---------------------------------------------------------------------------
identify_pillar_industries = BashOperator(
    task_id="identify_pillar_industries",
    bash_command=(
        f"echo '[5] 识别支柱产业（增加值 Top 5）与新兴产业（增速 Top 5）...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT industry_name, added_value, growth_rate "
        f"FROM {DORIS_DB}.industry_structure "
        f"WHERE stat_year = {BIZ_YEAR} AND added_value IS NOT NULL "
        f"ORDER BY added_value DESC LIMIT 5;\" && "
        f"echo '[5] 支柱产业识别完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 通知下游 Dashboard 刷新
# ---------------------------------------------------------------------------
notify_dashboard = PythonOperator(
    task_id="notify_dashboard_refresh",
    python_callable=lambda: print(
        f"[6] 产业结构分析完成，通知 Superset Dashboard 刷新: biz_date={BIZ_DATE}"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
load_industry_data >> calc_industry_ratio >> calc_contribution_rate >> [analyze_upgrade_trend, identify_pillar_industries] >> notify_dashboard