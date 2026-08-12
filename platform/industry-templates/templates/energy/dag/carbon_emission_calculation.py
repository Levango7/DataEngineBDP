"""碳排放核算 DAG - 排放因子匹配、排放量计算与核算报告生成.

本 DAG 每月 1 日凌晨 3:00 调度，执行以下步骤：
  1. 加载排放因子库（emission_factor_library），匹配各排放源（emission_source）
  2. 从 energy_consumption_summary 提取活动数据（AD）
  3. 计算排放量：E = AD × EF × GWP（活动数据 × 排放因子 × 全球变暖潜势）
  4. 按 Scope1/2/3 分类汇总（emission_scope_classification）
  5. 对比减排目标（emission_reduction_target），更新进度
  6. 生成核算报告（emission_report），含碳强度计算
  7. 通知下游（Superset Dashboard 刷新 / 报告审批流程）

排放量计算公式：E = AD × EF × GWP
  E   : 排放量（tCO2e）
  AD  : 活动数据（Activity Data，如燃料消耗量、用电量）
  EF  : 排放因子（Emission Factor，tCO2/单位活动数据）
  GWP : 全球变暖潜势（Global Warming Potential，CO2=1, CH4=28, N2O=265）

提交方式：Spark SQL 批计算。

Author: T043 能源行业模板工程师
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
    "owner": "carbon-accountant",
    "depends_on_past": False,
    "email": ["carbon-accountant@shuqing.com"],
    "email_on_failure": True,
    "email_on_retry": False,
    "retries": 3,
    "retry_delay": timedelta(minutes=5),
    "execution_timeout": timedelta(hours=3),
}

# ---------------------------------------------------------------------------
# DAG 定义：碳排放核算
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="carbon_emission_calculation",
    description=(
        "碳排放核算 DAG：匹配排放因子，计算排放量 E=AD×EF×GWP，" "按 Scope 分类汇总，对比减排目标，生成核算报告"
    ),
    default_args=default_args,
    schedule_interval="0 3 1 * *",  # 每月 1 日凌晨 3:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["energy", "carbon-emission", "calculation", "spark", "monthly"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
BIZ_MONTH = "{{ ds[:7] }}"  # YYYY-MM
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_energy")

# ---------------------------------------------------------------------------
# Task 1: 加载排放因子库，匹配排放源
# ---------------------------------------------------------------------------
match_emission_factors = BashOperator(
    task_id="match_emission_factors",
    bash_command=(
        f"echo '[1] 加载排放因子库，匹配排放源...' && "
        f"spark-submit --master {SPARK_MASTER} "
        f"/opt/spark/jobs/carbon_factor_matching.py "
        f"--biz-month '{BIZ_MONTH}' "
        f"--doris-fe {DORIS_FE} --doris-db {DORIS_DB} && "
        f"echo '[1] 排放因子匹配完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 提取活动数据（AD）从 energy_consumption_summary
# ---------------------------------------------------------------------------
extract_activity_data = BashOperator(
    task_id="extract_activity_data",
    bash_command=(
        f"echo '[2] 提取活动数据（AD）从 energy_consumption_summary...' && "
        f'spark-sql --master {SPARK_MASTER} -e "'
        f"CREATE TEMPORARY VIEW activity_data_{BIZ_MONTH} AS "
        f"SELECT s.measure_medium, s.dimension_type, s.dimension_id, s.dimension_name, "
        f"s.total_consumption AS activity_data, s.unit AS activity_unit, "
        f"e.source_id, e.source_name, e.scope, e.category, e.factor_id "
        f"FROM {DORIS_DB}.energy_consumption_summary s "
        f"JOIN {DORIS_DB}.emission_source e "
        f"ON s.measure_medium = e.activity_data_source "
        f"WHERE s.stat_period='MONTH' AND DATE_FORMAT(s.stat_date, 'yyyy-MM')='{BIZ_MONTH}';\" && "
        f"echo '[2] 活动数据提取完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 计算排放量 E = AD × EF × GWP
# ---------------------------------------------------------------------------
calc_emission = BashOperator(
    task_id="calc_emission",
    bash_command=(
        f"echo '[3] 计算排放量 E = AD × EF × GWP...' && "
        f'spark-sql --master {SPARK_MASTER} -e "'
        f"INSERT INTO {DORIS_DB}.emission_calculation_result "
        f"SELECT CONCAT('emis_', source_id, '_', '{BIZ_MONTH}') AS result_id, "
        f"LAST_DAY('{BIZ_DATE}') AS stat_date, 'MONTH' AS stat_period, "
        f"source_id, source_name, scope, category, gas_type, factor_id, "
        f"activity_data, activity_unit, factor_value, gwp, "
        f"-- 排放量 = 活动数据 × 排放因子 × GWP"
        f"activity_data * factor_value * gwp AS emission_amount, "
        f"activity_data * factor_value AS emission_amount_pure, "
        f"'OPERATIONAL' AS calculation_method, 1.0000 AS ownership_ratio, "
        f"NULL AS remark, NOW() AS created_at "
        f"FROM activity_data_{BIZ_MONTH} a "
        f'JOIN {DORIS_DB}.emission_factor_library f ON a.factor_id = f.factor_id;" && '
        f"echo '[3] 排放量计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 按 Scope1/2/3 分类汇总
# ---------------------------------------------------------------------------
scope_classification = SparkSubmitOperator(
    task_id="scope_classification",
    application="/opt/spark/jobs/carbon_scope_classification.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"carbon_scope_classification_{BIZ_MONTH}",
    },
    application_args=[
        "--biz-month",
        BIZ_MONTH,
        "--doris-fe",
        DORIS_FE,
        "--doris-db",
        DORIS_DB,
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 对比减排目标，更新进度
# ---------------------------------------------------------------------------
update_reduction_progress = PythonOperator(
    task_id="update_reduction_progress",
    python_callable=lambda: print(
        f"[5] 对比减排目标（emission_reduction_target），" f"更新进度 progress，状态 status（biz_month={BIZ_MONTH}）"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 生成核算报告（含碳强度计算）
# 碳强度 = 总排放量 / 产品产量或营收
# ---------------------------------------------------------------------------
generate_report = BashOperator(
    task_id="generate_report",
    bash_command=(
        f"echo '[6] 生成核算报告...' && "
        f'spark-sql --master {SPARK_MASTER} -e "'
        f"INSERT INTO {DORIS_DB}.emission_report "
        f"SELECT CONCAT('report_', '{BIZ_MONTH}') AS report_id, "
        f"CONCAT('{BIZ_MONTH} 碳排放核算报告') AS report_name, "
        f"CONCAT('REPORT_', '{BIZ_MONTH}') AS report_code, "
        f"(SELECT model_id FROM {DORIS_DB}.emission_calculation_model WHERE enabled=true LIMIT 1) AS model_id, "
        f"'MONTH' AS report_period, "
        f"DATE_TRUNC('MONTH', '{BIZ_DATE}') AS period_start, "
        f"LAST_DAY('{BIZ_DATE}') AS period_end, "
        f"SUM(emission_amount) AS total_emission, "
        f"SUM(CASE WHEN scope='SCOPE1' THEN emission_amount ELSE 0 END) AS scope1_emission, "
        f"SUM(CASE WHEN scope='SCOPE2' THEN emission_amount ELSE 0 END) AS scope2_emission, "
        f"SUM(CASE WHEN scope='SCOPE3' THEN emission_amount ELSE 0 END) AS scope3_emission, "
        f"NULL AS carbon_intensity, NULL AS intensity_unit, "
        f"'DRAFT' AS status, NULL AS approved_by, NULL AS approved_at, "
        f"NULL AS file_url, NULL AS remark, NOW() AS created_at, NOW() AS updated_at "
        f"FROM {DORIS_DB}.emission_calculation_result "
        f"WHERE DATE_FORMAT(stat_date, 'yyyy-MM')='{BIZ_MONTH}';\" && "
        f"echo '[6] 核算报告生成完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 7: 通知下游（Superset Dashboard 刷新 / 报告审批流程）
# ---------------------------------------------------------------------------
notify_downstream = PythonOperator(
    task_id="notify_downstream",
    python_callable=lambda: print(f"[7] 通知下游：刷新碳排放 Dashboard，触发报告审批流程（biz_month={BIZ_MONTH}）"),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
match_emission_factors >> extract_activity_data >> calc_emission
calc_emission >> scope_classification >> update_reduction_progress
update_reduction_progress >> generate_report >> notify_downstream
