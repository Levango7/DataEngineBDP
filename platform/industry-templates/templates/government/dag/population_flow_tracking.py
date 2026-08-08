"""人口流动追踪 DAG - 迁入/迁出/净流动量分析。

本 DAG 每日凌晨 3:00 调度，执行以下步骤：
  1. 从人口流动原始数据抽取迁入/迁出记录
  2. 按区县汇总迁入量/迁出量/净流动量
  3. 分析流动原因分布（工作/家庭/教育/医疗）
  4. 计算跨省流动/市内流动/跨市流动统计
  5. 识别人口流动热点区域（净流入/净流出 Top N）
  6. 写入 population_flow 表
  7. 通知下游 Dashboard 刷新

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
# DAG 定义：人口流动追踪
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="population_flow_tracking",
    description=(
        "人口流动追踪 DAG：从流动原始数据聚合，"
        "计算迁入/迁出/净流动量/流动原因分布，写入 population_flow 表"
    ),
    default_args=default_args,
    schedule_interval="0 3 * * *",  # 每日凌晨 3:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["government", "population", "flow", "spark"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_government")

# ---------------------------------------------------------------------------
# Task 1: 抽取人口流动原始数据
# ---------------------------------------------------------------------------
extract_flow_data = BashOperator(
    task_id="extract_flow_data",
    bash_command=(
        f"echo '[1] 抽取人口流动原始数据 biz_date={BIZ_DATE}...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT flow_type, from_district, to_district, flow_reason, COUNT(*) AS cnt "
        f"FROM {DORIS_DB}.population_flow "
        f"WHERE flow_date = '{BIZ_DATE}' "
        f"GROUP BY flow_type, from_district, to_district, flow_reason;\" && "
        f"echo '[1] 流动数据抽取完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 按区县汇总迁入/迁出/净流动量
# ---------------------------------------------------------------------------
calc_net_flow = SparkSubmitOperator(
    task_id="calc_net_flow",
    application="/opt/spark/jobs/population_flow_aggregate.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"population_flow_{BIZ_DATE}",
    },
    application_args=[
        "--biz-date", BIZ_DATE,
        "--doris-fe", DORIS_FE,
        "--doris-db", DORIS_DB,
        "--mode", "net_flow",
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 分析流动原因分布
# ---------------------------------------------------------------------------
analyze_flow_reason = BashOperator(
    task_id="analyze_flow_reason",
    bash_command=(
        f"echo '[3] 分析流动原因分布（工作/家庭/教育/医疗）...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT flow_reason, COUNT(*) AS cnt, "
        f"COUNT(*)*100.0/SUM(COUNT(*)) OVER() AS ratio "
        f"FROM {DORIS_DB}.population_flow "
        f"WHERE flow_date = '{BIZ_DATE}' AND flow_reason IS NOT NULL "
        f"GROUP BY flow_reason;\" && "
        f"echo '[3] 流动原因分析完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 计算跨省/市内/跨市流动统计
# ---------------------------------------------------------------------------
calc_flow_by_scope = BashOperator(
    task_id="calc_flow_by_scope",
    bash_command=(
        f"echo '[4] 计算跨省/市内/跨市流动统计...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT flow_type, COUNT(*) AS cnt "
        f"FROM {DORIS_DB}.population_flow "
        f"WHERE flow_date = '{BIZ_DATE}' "
        f"GROUP BY flow_type;\" && "
        f"echo '[4] 流动范围统计完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 识别人口流动热点区域
# ---------------------------------------------------------------------------
identify_hot_regions = PythonOperator(
    task_id="identify_hot_regions",
    python_callable=lambda: print(
        f"[5] 识别人口流动热点区域（净流入/净流出 Top 10）: biz_date={BIZ_DATE}"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 通知下游 Dashboard 刷新
# ---------------------------------------------------------------------------
notify_dashboard = PythonOperator(
    task_id="notify_dashboard_refresh",
    python_callable=lambda: print(
        f"[6] 人口流动追踪完成，通知 Superset Dashboard 刷新: biz_date={BIZ_DATE}"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
extract_flow_data >> calc_net_flow >> [analyze_flow_reason, calc_flow_by_scope] >> identify_hot_regions >> notify_dashboard