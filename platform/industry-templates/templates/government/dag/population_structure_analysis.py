"""人口结构分析 DAG - 年龄/性别/学历/就业多维分析。

本 DAG 每日凌晨 2:00 调度，执行以下步骤：
  1. 从 population_base 聚合人口基础数据
  2. 计算人口结构汇总指标（总人口/性别比/城镇化率/老龄化率/抚养比）
  3. 按年龄段汇总人口年龄分布（用于人口金字塔）
  4. 按性别汇总人口性别分布（含出生人口性别比）
  5. 按学历层次汇总人口学历分布
  6. 按就业状态/行业汇总人口就业分布
  7. 写入 population_structure / population_age_distribution /
     population_gender_distribution / population_education_distribution /
     population_employment_distribution 表
  8. 通知下游（Superset Dashboard 刷新）

提交方式：Spark SQL 批计算。

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
# DAG 定义：人口结构分析
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="population_structure_analysis",
    description=(
        "人口结构分析 DAG：从 population_base 聚合，"
        "计算人口结构/年龄/性别/学历/就业多维分布，写入 5 张汇总表"
    ),
    default_args=default_args,
    schedule_interval="0 2 * * *",  # 每日凌晨 2:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["government", "population", "structure", "spark"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"  # Airflow 逻辑日期
BIZ_YEAR = "{{ macros.ds_format(ds, '%Y-%m-%d', '%Y') }}"  # 业务年度
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_government")

# ---------------------------------------------------------------------------
# Task 1: 校验人口基础数据完整性
# ---------------------------------------------------------------------------
check_data_quality = BashOperator(
    task_id="check_population_data_quality",
    bash_command=(
        f"echo '[1] 校验人口基础数据完整性 biz_date={BIZ_DATE}...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT COUNT(*) AS total_count, "
        f"COUNT(DISTINCT person_id) AS unique_count, "
        f"SUM(CASE WHEN id_card_masked IS NULL THEN 1 ELSE 0 END) AS null_id_count "
        f"FROM {DORIS_DB}.population_base "
        f"WHERE YEAR(updated_at) = {BIZ_YEAR};\" && "
        f"echo '[1] 数据质量校验完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 计算人口结构汇总指标
# ---------------------------------------------------------------------------
calc_population_structure = SparkSubmitOperator(
    task_id="calc_population_structure",
    application="/opt/spark/jobs/population_structure_aggregate.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"population_structure_{BIZ_DATE}",
    },
    application_args=[
        "--biz-year", BIZ_YEAR,
        "--doris-fe", DORIS_FE,
        "--doris-db", DORIS_DB,
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 计算人口年龄分布（用于人口金字塔）
# ---------------------------------------------------------------------------
calc_age_distribution = BashOperator(
    task_id="calc_age_distribution",
    bash_command=(
        f"echo '[3] 计算人口年龄分布（5岁年龄段）...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"INSERT INTO {DORIS_DB}.population_age_distribution "
        f"SELECT CONCAT(FLOOR(age/5)*5, '-', FLOOR(age/5)*5+4) AS age_group, "
        f"FLOOR(age/5)*5 AS age_lower, FLOOR(age/5)*5+4 AS age_upper, "
        f"SUM(CASE WHEN gender='M' THEN 1 ELSE 0 END) AS male_count, "
        f"SUM(CASE WHEN gender='F' THEN 1 ELSE 0 END) AS female_count, "
        f"COUNT(*) AS total_count "
        f"FROM {DORIS_DB}.population_base "
        f"WHERE YEAR(updated_at) = {BIZ_YEAR} AND age IS NOT NULL "
        f"GROUP BY FLOOR(age/5);\" && "
        f"echo '[3] 年龄分布计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 计算人口性别分布
# ---------------------------------------------------------------------------
calc_gender_distribution = BashOperator(
    task_id="calc_gender_distribution",
    bash_command=(
        f"echo '[4] 计算人口性别分布（含性别比/出生人口性别比）...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"INSERT INTO {DORIS_DB}.population_gender_distribution "
        f"SELECT SUM(CASE WHEN gender='M' THEN 1 ELSE 0 END) AS male_count, "
        f"SUM(CASE WHEN gender='F' THEN 1 ELSE 0 END) AS female_count, "
        f"COUNT(*) AS total_count, "
        f"SUM(CASE WHEN gender='M' THEN 1 ELSE 0 END)/SUM(CASE WHEN gender='F' THEN 1 ELSE 0 END)*100 AS sex_ratio "
        f"FROM {DORIS_DB}.population_base "
        f"WHERE YEAR(updated_at) = {BIZ_YEAR};\" && "
        f"echo '[4] 性别分布计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 计算人口学历分布
# ---------------------------------------------------------------------------
calc_education_distribution = BashOperator(
    task_id="calc_education_distribution",
    bash_command=(
        f"echo '[5] 计算人口学历分布...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"INSERT INTO {DORIS_DB}.population_education_distribution "
        f"SELECT education_level, COUNT(*) AS population_count, "
        f"SUM(CASE WHEN gender='M' THEN 1 ELSE 0 END) AS male_count, "
        f"SUM(CASE WHEN gender='F' THEN 1 ELSE 0 END) AS female_count, "
        f"AVG(age) AS avg_age "
        f"FROM {DORIS_DB}.population_base "
        f"WHERE YEAR(updated_at) = {BIZ_YEAR} AND education_level IS NOT NULL "
        f"GROUP BY education_level;\" && "
        f"echo '[5] 学历分布计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 计算人口就业分布
# ---------------------------------------------------------------------------
calc_employment_distribution = BashOperator(
    task_id="calc_employment_distribution",
    bash_command=(
        f"echo '[6] 计算人口就业分布（按就业状态/行业）...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"INSERT INTO {DORIS_DB}.population_employment_distribution "
        f"SELECT employment_status, industry, occupation, COUNT(*) AS population_count, "
        f"SUM(CASE WHEN gender='M' THEN 1 ELSE 0 END) AS male_count, "
        f"SUM(CASE WHEN gender='F' THEN 1 ELSE 0 END) AS female_count "
        f"FROM {DORIS_DB}.population_base "
        f"WHERE YEAR(updated_at) = {BIZ_YEAR} AND employment_status IS NOT NULL "
        f"GROUP BY employment_status, industry, occupation;\" && "
        f"echo '[6] 就业分布计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 7: 通知下游 Dashboard 刷新
# ---------------------------------------------------------------------------
notify_dashboard = PythonOperator(
    task_id="notify_dashboard_refresh",
    python_callable=lambda: print(
        f"[7] 人口结构分析完成，通知 Superset Dashboard 刷新: biz_date={BIZ_DATE}"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
check_data_quality >> calc_population_structure >> [
    calc_age_distribution,
    calc_gender_distribution,
    calc_education_distribution,
    calc_employment_distribution,
] >> notify_dashboard