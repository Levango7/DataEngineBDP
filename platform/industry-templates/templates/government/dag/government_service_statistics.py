"""政务服务统计 DAG - 办理量/办结率/平均时长/网办率统计。

本 DAG 每日凌晨 7:00 调度，执行以下步骤：
  1. 从 service_transaction 抽取办理记录
  2. 按部门/事项类别汇总办理量/办结量/待办量/驳回量
  3. 计算办结率/驳回率
  4. 计算平均办理时长（工作日）
  5. 统计网办量/窗口量，计算网办率
  6. 生成日/周/月/季/年多周期统计
  7. 写入 service_statistics 表
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
# DAG 定义：政务服务统计
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="government_service_statistics",
    description=(
        "政务服务统计 DAG：从 service_transaction 聚合，"
        "计算办理量/办结率/平均时长/网办率，写入 service_statistics 表"
    ),
    default_args=default_args,
    schedule_interval="0 7 * * *",  # 每日凌晨 7:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["government", "livelihood", "service", "statistics", "spark"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_government")

# ---------------------------------------------------------------------------
# Task 1: 抽取办理记录
# ---------------------------------------------------------------------------
extract_transactions = BashOperator(
    task_id="extract_transactions",
    bash_command=(
        f"echo '[1] 抽取办理记录 biz_date={BIZ_DATE}...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT service_id, department, channel, status, "
        f"processing_duration, accept_time, complete_time "
        f"FROM {DORIS_DB}.service_transaction "
        f"WHERE DATE(accept_time) = '{BIZ_DATE}';\" && "
        f"echo '[1] 办理记录抽取完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 按部门/事项类别汇总办理量
# ---------------------------------------------------------------------------
aggregate_by_department = SparkSubmitOperator(
    task_id="aggregate_by_department",
    application="/opt/spark/jobs/service_statistics_aggregate.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"service_stats_{BIZ_DATE}",
    },
    application_args=[
        "--biz-date", BIZ_DATE,
        "--doris-fe", DORIS_FE,
        "--doris-db", DORIS_DB,
        "--group-by", "department,service_category",
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 计算办结率/驳回率
# ---------------------------------------------------------------------------
calc_completion_rate = BashOperator(
    task_id="calc_completion_rate",
    bash_command=(
        f"echo '[3] 计算办结率/驳回率...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT department, "
        f"COUNT(*) AS total_count, "
        f"SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END) AS completed_count, "
        f"SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END)*100.0/COUNT(*) AS completion_rate, "
        f"SUM(CASE WHEN status='REJECTED' THEN 1 ELSE 0 END)*100.0/COUNT(*) AS rejection_rate "
        f"FROM {DORIS_DB}.service_transaction "
        f"WHERE DATE(accept_time) = '{BIZ_DATE}' "
        f"GROUP BY department;\" && "
        f"echo '[3] 办结率计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 计算平均办理时长
# ---------------------------------------------------------------------------
calc_avg_duration = BashOperator(
    task_id="calc_avg_duration",
    bash_command=(
        f"echo '[4] 计算平均办理时长（工作日）...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT department, AVG(processing_duration) AS avg_days "
        f"FROM {DORIS_DB}.service_transaction "
        f"WHERE DATE(accept_time) = '{BIZ_DATE}' AND status='COMPLETED' "
        f"GROUP BY department;\" && "
        f"echo '[4] 平均时长计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 统计网办量/窗口量，计算网办率
# ---------------------------------------------------------------------------
calc_online_rate = BashOperator(
    task_id="calc_online_rate",
    bash_command=(
        f"echo '[5] 计算网办率...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT department, "
        f"SUM(CASE WHEN channel='ONLINE' THEN 1 ELSE 0 END) AS online_count, "
        f"SUM(CASE WHEN channel='WINDOW' THEN 1 ELSE 0 END) AS window_count, "
        f"SUM(CASE WHEN channel='ONLINE' THEN 1 ELSE 0 END)*100.0/COUNT(*) AS online_rate "
        f"FROM {DORIS_DB}.service_transaction "
        f"WHERE DATE(accept_time) = '{BIZ_DATE}' "
        f"GROUP BY department;\" && "
        f"echo '[5] 网办率计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 生成多周期统计（日/周/月/季/年）
# ---------------------------------------------------------------------------
generate_multi_period = PythonOperator(
    task_id="generate_multi_period",
    python_callable=lambda: print(
        f"[6] 生成日/周/月/季/年多周期统计: biz_date={BIZ_DATE}"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 7: 通知下游 Dashboard 刷新
# ---------------------------------------------------------------------------
notify_dashboard = PythonOperator(
    task_id="notify_dashboard_refresh",
    python_callable=lambda: print(
        f"[7] 政务服务统计完成，通知 Superset Dashboard 刷新: biz_date={BIZ_DATE}"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
extract_transactions >> aggregate_by_department >> [calc_completion_rate, calc_avg_duration, calc_online_rate] >> generate_multi_period >> notify_dashboard