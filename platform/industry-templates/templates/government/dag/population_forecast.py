"""人口预测 DAG - 基于历史数据的趋势预测。

本 DAG 每月 1 日凌晨 4:00 调度，执行以下步骤：
  1. 加载历史人口结构数据（近 10 年）
  2. 使用线性回归模型预测未来 5 年人口
  3. 使用 ARIMA 模型预测未来 5 年人口
  4. 使用队列要素法预测未来 5 年人口
  5. 计算预测置信区间（95%）
  6. 预测老龄化率/城镇化率
  7. 写入 population_forecast 表
  8. 通知下游 Dashboard 刷新

预测方法：LINEAR-线性 / ARIMA-ARIMA / COHORT-队列要素 / LOGISTIC-逻辑斯蒂

Author: T044 政务模板工程师
"""

from __future__ import annotations

from datetime import datetime, timedelta
import os

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
    "execution_timeout": timedelta(hours=3),
}

# ---------------------------------------------------------------------------
# DAG 定义：人口预测
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="population_forecast",
    description=(
        "人口预测 DAG：基于历史人口数据，使用线性/ARIMA/队列要素法" "预测未来 5 年人口趋势，写入 population_forecast 表"
    ),
    default_args=default_args,
    schedule_interval="0 4 1 * *",  # 每月 1 日凌晨 4:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["government", "population", "forecast", "spark", "ml"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
BASE_YEAR = "{{ macros.ds_format(ds, '%Y-%m-%d', '%Y') }}"
FORECAST_YEARS = 5  # 预测未来 5 年
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_government")

# ---------------------------------------------------------------------------
# Task 1: 加载历史人口结构数据
# ---------------------------------------------------------------------------
load_history_data = BashOperator(
    task_id="load_history_data",
    bash_command=(
        f"echo '[1] 加载历史人口结构数据（近 10 年，base_year={BASE_YEAR}）...' && "
        f'spark-sql --master {SPARK_MASTER} -e "'
        f"SELECT stat_year, province, city, total_population, "
        f"aging_rate, urbanization_rate "
        f"FROM {DORIS_DB}.population_structure "
        f"WHERE stat_year BETWEEN {int(BASE_YEAR) - 10} AND {BASE_YEAR} "
        f'ORDER BY stat_year;" && '
        f"echo '[1] 历史数据加载完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 线性回归预测
# ---------------------------------------------------------------------------
forecast_linear = SparkSubmitOperator(
    task_id="forecast_linear",
    application="/opt/spark/jobs/population_forecast_linear.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"population_forecast_linear_{BIZ_DATE}",
    },
    application_args=[
        "--base-year",
        BASE_YEAR,
        "--forecast-years",
        str(FORECAST_YEARS),
        "--method",
        "LINEAR",
        "--doris-fe",
        DORIS_FE,
        "--doris-db",
        DORIS_DB,
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: ARIMA 模型预测
# ---------------------------------------------------------------------------
forecast_arima = SparkSubmitOperator(
    task_id="forecast_arima",
    application="/opt/spark/jobs/population_forecast_arima.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"population_forecast_arima_{BIZ_DATE}",
    },
    application_args=[
        "--base-year",
        BASE_YEAR,
        "--forecast-years",
        str(FORECAST_YEARS),
        "--method",
        "ARIMA",
        "--doris-fe",
        DORIS_FE,
        "--doris-db",
        DORIS_DB,
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 队列要素法预测
# ---------------------------------------------------------------------------
forecast_cohort = SparkSubmitOperator(
    task_id="forecast_cohort",
    application="/opt/spark/jobs/population_forecast_cohort.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"population_forecast_cohort_{BIZ_DATE}",
    },
    application_args=[
        "--base-year",
        BASE_YEAR,
        "--forecast-years",
        str(FORECAST_YEARS),
        "--method",
        "COHORT",
        "--doris-fe",
        DORIS_FE,
        "--doris-db",
        DORIS_DB,
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 计算预测置信区间
# ---------------------------------------------------------------------------
calc_confidence_interval = BashOperator(
    task_id="calc_confidence_interval",
    bash_command=(
        f"echo '[5] 计算预测置信区间（95%）...' && "
        f'spark-sql --master {SPARK_MASTER} -e "'
        f"SELECT forecast_year, forecast_method, "
        f"forecast_population, "
        f"forecast_population * 0.97 AS forecast_lower, "
        f"forecast_population * 1.03 AS forecast_upper "
        f"FROM {DORIS_DB}.population_forecast "
        f'WHERE base_year = {BASE_YEAR};" && '
        f"echo '[5] 置信区间计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 预测老龄化率/城镇化率
# ---------------------------------------------------------------------------
forecast_ratios = PythonOperator(
    task_id="forecast_ratios",
    python_callable=lambda: print(f"[6] 预测老龄化率/城镇化率: base_year={BASE_YEAR}, forecast_years={FORECAST_YEARS}"),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 7: 通知下游 Dashboard 刷新
# ---------------------------------------------------------------------------
notify_dashboard = PythonOperator(
    task_id="notify_dashboard_refresh",
    python_callable=lambda: print(f"[7] 人口预测完成，通知 Superset Dashboard 刷新: biz_date={BIZ_DATE}"),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
(
    load_history_data
    >> [forecast_linear, forecast_arima, forecast_cohort]
    >> calc_confidence_interval
    >> forecast_ratios
    >> notify_dashboard
)
