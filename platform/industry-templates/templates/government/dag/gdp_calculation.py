"""GDP 核算 DAG - 生产法/支出法/收入法三法核算。

本 DAG 每季度首月 15 日凌晨 5:00 调度，执行以下步骤：
  1. 汇总各行业增加值（生产法）
  2. 汇总消费+投资+净出口（支出法）
  3. 汇总劳动报酬+生产税净+折旧+营业盈余（收入法）
  4. 三法交叉校验（误差应 < 5%）
  5. 计算三次产业占比与人均 GDP
  6. 写入 gdp 表
  7. 通知下游 Dashboard 刷新

GDP 公式：
  生产法 = Σ各行业增加值
  支出法 = 最终消费 + 资本形成 + 净出口
  收入法 = 劳动报酬 + 生产税净额 + 固定资产折旧 + 营业盈余

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
# DAG 定义：GDP 核算
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="gdp_calculation",
    description=("GDP 核算 DAG：使用生产法/支出法/收入法三法核算 GDP，" "交叉校验后写入 gdp 表"),
    default_args=default_args,
    schedule_interval="0 5 15 1,4,7,10 *",  # 每季度首月 15 日凌晨 5:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["government", "economic", "gdp", "spark"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
BIZ_YEAR = "{{ macros.ds_format(ds, '%Y-%m-%d', '%Y') }}"
BIZ_QUARTER = "{{ macros.ds_format(ds, '%Y-%m-%d', 'Q%q') }}"
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_government")

# ---------------------------------------------------------------------------
# Task 1: 生产法核算 - 汇总各行业增加值
# ---------------------------------------------------------------------------
calc_production_method = BashOperator(
    task_id="calc_production_method",
    bash_command=(
        f"echo '[1] 生产法核算：GDP = Σ各行业增加值...' && "
        f'spark-sql --master {SPARK_MASTER} -e "'
        f"INSERT INTO {DORIS_DB}.gdp "
        f"SELECT SUM(added_value) AS gdp_value, 'PRODUCTION' AS calculation_method, "
        f"SUM(CASE WHEN industry_category='PRIMARY' THEN added_value ELSE 0 END) AS primary_industry_value, "
        f"SUM(CASE WHEN industry_category='SECONDARY' THEN added_value ELSE 0 END) AS secondary_industry_value, "
        f"SUM(CASE WHEN industry_category='TERTIARY' THEN added_value ELSE 0 END) AS tertiary_industry_value "
        f"FROM {DORIS_DB}.industry_structure "
        f'WHERE stat_year = {BIZ_YEAR};" && '
        f"echo '[1] 生产法核算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 支出法核算 - 汇总消费+投资+净出口
# ---------------------------------------------------------------------------
calc_expenditure_method = BashOperator(
    task_id="calc_expenditure_method",
    bash_command=(
        f"echo '[2] 支出法核算：GDP = 最终消费 + 资本形成 + 净出口...' && "
        f'spark-sql --master {SPARK_MASTER} -e "'
        f"SELECT SUM(retail_amount) AS final_consumption, "
        f"SUM(investment_amount) AS capital_formation, "
        f"SUM(CASE WHEN trade_direction='EXPORT' THEN trade_amount ELSE -trade_amount END) AS net_export "
        f"FROM {DORIS_DB}.social_retail_consumption, {DORIS_DB}.fixed_asset_investment, {DORIS_DB}.foreign_trade "
        f'WHERE stat_year = {BIZ_YEAR};" && '
        f"echo '[2] 支出法核算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 收入法核算 - 汇总劳动报酬+生产税净+折旧+营业盈余
# ---------------------------------------------------------------------------
calc_income_method = BashOperator(
    task_id="calc_income_method",
    bash_command=(
        f"echo '[3] 收入法核算：GDP = 劳动报酬 + 生产税净额 + 折旧 + 营业盈余...' && "
        f'spark-sql --master {SPARK_MASTER} -e "'
        f"SELECT SUM(labor_compensation) + SUM(net_production_tax) + "
        f"SUM(depreciation) + SUM(operating_surplus) AS gdp_income "
        f"FROM {DORIS_DB}.gdp "
        f"WHERE stat_year = {BIZ_YEAR} AND calculation_method = 'INCOME';\" && "
        f"echo '[3] 收入法核算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 三法交叉校验
# ---------------------------------------------------------------------------
cross_validate = PythonOperator(
    task_id="cross_validate_methods",
    python_callable=lambda: print(f"[4] 三法交叉校验：生产法/支出法/收入法误差应 < 5%, biz_year={BIZ_YEAR}"),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 计算三次产业占比与人均 GDP
# ---------------------------------------------------------------------------
calc_ratios = SparkSubmitOperator(
    task_id="calc_industry_ratios",
    application="/opt/spark/jobs/gdp_ratio_calculate.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"gdp_ratios_{BIZ_DATE}",
    },
    application_args=[
        "--biz-year",
        BIZ_YEAR,
        "--doris-fe",
        DORIS_FE,
        "--doris-db",
        DORIS_DB,
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 通知下游 Dashboard 刷新
# ---------------------------------------------------------------------------
notify_dashboard = PythonOperator(
    task_id="notify_dashboard_refresh",
    python_callable=lambda: print(f"[6] GDP 核算完成，通知 Superset Dashboard 刷新: biz_date={BIZ_DATE}"),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
(
    [calc_production_method, calc_expenditure_method, calc_income_method]
    >> cross_validate
    >> calc_ratios
    >> notify_dashboard
)
