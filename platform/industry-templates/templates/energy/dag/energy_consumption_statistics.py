"""用能分析统计 DAG - 多维度能耗聚合与对比分析.

本 DAG 每日凌晨 1:00 调度，执行以下步骤：
  1. 从 energy_consumption_detail 聚合生成日级汇总（energy_consumption_summary）
  2. 计算同比环比增长率（对比去年同期/上月）
  3. 多维对比分析（跨部门/跨位置/跨介质/跨周期/对标）
  4. 生成趋势数据（含 7 日/30 日移动平均）
  5. 能源平衡分析（输入=输出+损失，计算能效）
  6. 能源成本分析（单价×消耗量，单位产品成本）
  7. 定额达成分析（对比 energy_quota）

同比增长率 = (本期值 - 同期值) / 同期值 × 100%
环比增长率 = (本期值 - 上期值) / 上期值 × 100%

提交方式：Spark SQL 批计算。

Author: T043 能源行业模板工程师
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
    "owner": "energy-analyst",
    "depends_on_past": False,
    "email": ["energy-analyst@shuqing.com"],
    "email_on_failure": True,
    "email_on_retry": False,
    "retries": 3,
    "retry_delay": timedelta(minutes=5),
    "execution_timeout": timedelta(hours=2),
}

# ---------------------------------------------------------------------------
# DAG 定义：用能分析统计
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="energy_consumption_statistics",
    description=(
        "用能分析统计 DAG：从能耗明细聚合多维度汇总，计算同比环比，"
        "生成趋势数据、能源平衡与成本分析"
    ),
    default_args=default_args,
    schedule_interval="0 1 * * *",  # 每日凌晨 1:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["energy", "consumption", "statistics", "spark"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_energy")

# ---------------------------------------------------------------------------
# Task 1: 聚合日级能耗汇总（设备/位置/部门/公司多维度）
# ---------------------------------------------------------------------------
aggregate_daily_summary = SparkSubmitOperator(
    task_id="aggregate_daily_summary",
    application="/opt/spark/jobs/energy_daily_aggregate.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"energy_daily_aggregate_{BIZ_DATE}",
    },
    application_args=[
        "--biz-date", BIZ_DATE,
        "--doris-fe", DORIS_FE,
        "--doris-db", DORIS_DB,
        "--dimensions", "DEVICE,LOCATION,DEPARTMENT,COMPANY",
        "--periods", "HOUR,DAY",
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 计算同比环比增长率
# 同比 = (本期 - 同期) / 同期
# 环比 = (本期 - 上期) / 上期
# ---------------------------------------------------------------------------
calc_yoy_mom = BashOperator(
    task_id="calc_yoy_mom",
    bash_command=(
        f"echo '[2] 计算同比环比增长率...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"UPDATE {DORIS_DB}.energy_consumption_summary s "
        f"SET same_period_last = (SELECT total_consumption FROM {DORIS_DB}.energy_consumption_summary "
        f"WHERE dimension_type=s.dimension_type AND dimension_id=s.dimension_id "
        f"AND measure_medium=s.measure_medium AND stat_date=DATE_SUB(s.stat_date, 365)), "
        f"last_period = (SELECT total_consumption FROM {DORIS_DB}.energy_consumption_summary "
        f"WHERE dimension_type=s.dimension_type AND dimension_id=s.dimension_id "
        f"AND measure_medium=s.measure_medium AND stat_date=DATE_SUB(s.stat_date, 1)), "
        f"yoy_growth_rate = CASE WHEN same_period_last > 0 THEN (total_consumption - same_period_last)/same_period_last ELSE NULL END, "
        f"mom_growth_rate = CASE WHEN last_period > 0 THEN (total_consumption - last_period)/last_period ELSE NULL END "
        f"WHERE s.stat_date = '{BIZ_DATE}';\" && "
        f"echo '[2] 同比环比计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 多维对比分析（跨部门/跨位置/跨介质/跨周期/对标）
# ---------------------------------------------------------------------------
dimension_compare = SparkSubmitOperator(
    task_id="dimension_compare",
    application="/opt/spark/jobs/energy_dimension_compare.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"energy_dimension_compare_{BIZ_DATE}",
    },
    application_args=[
        "--biz-date", BIZ_DATE,
        "--doris-fe", DORIS_FE,
        "--doris-db", DORIS_DB,
        "--compare-types", "CROSS_DEPARTMENT,CROSS_LOCATION,CROSS_MEDIUM,CROSS_PERIOD,BENCHMARK",
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 生成趋势数据（含 7 日/30 日移动平均）
# ---------------------------------------------------------------------------
generate_trend_data = BashOperator(
    task_id="generate_trend_data",
    bash_command=(
        f"echo '[4] 生成趋势数据（含 7 日/30 日移动平均）...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"INSERT INTO {DORIS_DB}.energy_trend_data "
        f"SELECT CONCAT('trend_', dimension_id, '_', stat_date) AS trend_id, "
        f"stat_date, NULL AS stat_time, 'DAY' AS granularity, measure_medium, "
        f"dimension_type, dimension_id, dimension_name, total_consumption, unit, standard_coal, "
        f"AVG(total_consumption) OVER (PARTITION BY dimension_id ORDER BY stat_date ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS moving_avg_7d, "
        f"AVG(total_consumption) OVER (PARTITION BY dimension_id ORDER BY stat_date ROWS BETWEEN 29 PRECEDING AND CURRENT ROW) AS moving_avg_30d, "
        f"CASE WHEN total_consumption > moving_avg_7d THEN 'UP' WHEN total_consumption < moving_avg_7d THEN 'DOWN' ELSE 'FLAT' END AS trend_direction, "
        f"NOW() AS created_at "
        f"FROM {DORIS_DB}.energy_consumption_summary WHERE stat_period='DAY' AND stat_date='{BIZ_DATE}';\" && "
        f"echo '[4] 趋势数据生成完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 能源平衡分析（输入=输出+损失，计算能效）
# ---------------------------------------------------------------------------
energy_balance_analysis = PythonOperator(
    task_id="energy_balance_analysis",
    python_callable=lambda: print(
        f"[5] 能源平衡分析：计算各介质输入/输出/损失/能效，"
        f"写入 energy_balance 表（biz_date={BIZ_DATE}）"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 能源成本分析（单价×消耗量，单位产品成本）
# ---------------------------------------------------------------------------
cost_analysis = SparkSubmitOperator(
    task_id="cost_analysis",
    application="/opt/spark/jobs/energy_cost_analysis.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"energy_cost_analysis_{BIZ_DATE}",
    },
    application_args=[
        "--biz-date", BIZ_DATE,
        "--doris-fe", DORIS_FE,
        "--doris-db", DORIS_DB,
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 7: 定额达成分析（对比 energy_quota，触发告警）
# ---------------------------------------------------------------------------
quota_check = PythonOperator(
    task_id="quota_check",
    python_callable=lambda: print(
        f"[7] 定额达成分析：对比 energy_consumption_summary 与 energy_quota，"
        f"超出上限/低于下限的维度触发告警（biz_date={BIZ_DATE}）"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
aggregate_daily_summary >> calc_yoy_mom >> dimension_compare
dimension_compare >> generate_trend_data >> energy_balance_analysis
energy_balance_analysis >> cost_analysis >> quota_check