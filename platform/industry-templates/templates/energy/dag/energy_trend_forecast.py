"""能耗趋势预测 DAG - 时序预测模型训练、预测与评估.

本 DAG 每日凌晨 4:00 调度，执行以下步骤：
  1. 加载预测任务参数（forecast_parameter），筛选启用的预测任务
  2. 从 energy_consumption_summary / emission_calculation_result 提取历史时序数据
  3. 训练预测模型（ARIMA/Prophet/LSTM/指数平滑/线性回归/集成）
  4. 生成预测结果（forecast_result），含 95% 置信区间
  5. 评估模型（forecast_model_evaluation），计算 MAPE/RMSE/MAE/R²
  6. 选择最优模型，注册到 forecast_model_registry
  7. 通知下游（Superset Dashboard 刷新 / 预测结果应用）

评估指标：
  MAPE = mean(|actual - forecast| / |actual|) × 100%
  RMSE = sqrt(mean((actual - forecast)^2))
  MAE  = mean(|actual - forecast|)

提交方式：Spark + MLlib（分布式训练）/ 单机 Python（Prophet/LSTM）。

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
    "owner": "energy-analyst",
    "depends_on_past": False,
    "email": ["energy-analyst@shuqing.com"],
    "email_on_failure": True,
    "email_on_retry": False,
    "retries": 2,
    "retry_delay": timedelta(minutes=10),
    "execution_timeout": timedelta(hours=4),
}

# ---------------------------------------------------------------------------
# DAG 定义：能耗趋势预测
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="energy_trend_forecast",
    description=(
        "能耗趋势预测 DAG：加载历史时序数据，训练 ARIMA/Prophet/LSTM 等模型，"
        "生成预测结果与置信区间，评估模型并选择最优"
    ),
    default_args=default_args,
    schedule_interval="0 4 * * *",  # 每日凌晨 4:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["energy", "forecast", "arima", "prophet", "lstm", "spark"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_energy")
MLFLOW_TRACKING_URI = os.environ.get("MLFLOW_TRACKING_URI", "http://mlflow:5000")

# ---------------------------------------------------------------------------
# Task 1: 加载预测任务参数，筛选启用的预测任务
# ---------------------------------------------------------------------------
load_forecast_params = PythonOperator(
    task_id="load_forecast_params",
    python_callable=lambda: print(
        f"[1] 加载 forecast_parameter 表，筛选 enabled=true 的预测任务（biz_date={BIZ_DATE}）"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 提取历史时序数据
# ---------------------------------------------------------------------------
extract_history_data = BashOperator(
    task_id="extract_history_data",
    bash_command=(
        f"echo '[2] 提取历史时序数据...' && "
        f"spark-submit --master {SPARK_MASTER} "
        f"/opt/spark/jobs/energy_history_extract.py "
        f"--biz-date '{BIZ_DATE}' "
        f"--doris-fe {DORIS_FE} --doris-db {DORIS_DB} "
        f"--output /tmp/energy_history_{BIZ_DATE}.parquet && "
        f"echo '[2] 历史数据提取完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 训练预测模型（ARIMA/Prophet/LSTM/指数平滑/线性回归/集成）
# ---------------------------------------------------------------------------
train_models = BashOperator(
    task_id="train_models",
    bash_command=(
        f"echo '[3] 训练预测模型...' && "
        f"python /opt/ml/energy_forecast_train.py "
        f"--biz-date '{BIZ_DATE}' "
        f"--input /tmp/energy_history_{BIZ_DATE}.parquet "
        f"--mlflow-uri {MLFLOW_TRACKING_URI} "
        f"--models ARIMA,PROPHET,LSTM,EXPONENTIAL_SMOOTHING,LINEAR_REGRESSION,ENSEMBLE && "
        f"echo '[3] 模型训练完成，工件已注册到 MLflow'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 生成预测结果（含 95% 置信区间）
# ---------------------------------------------------------------------------
generate_forecast = BashOperator(
    task_id="generate_forecast",
    bash_command=(
        f"echo '[4] 生成预测结果（含 95% 置信区间）...' && "
        f'spark-sql --master {SPARK_MASTER} -e "'
        f"INSERT INTO {DORIS_DB}.forecast_result "
        f"SELECT CONCAT('fc_', param_id, '_', forecast_date) AS result_id, "
        f"param_id, model_version_id, target_metric, measure_medium, "
        f"dimension_type, dimension_id, dimension_name, "
        f"forecast_date, NULL AS forecast_time, granularity, "
        f"forecast_value, lower_bound, upper_bound, 0.95 AS confidence_level, "
        f"NULL AS actual_value, NULL AS error_value, NULL AS error_pct, "
        f"unit, false AS is_out_of_bounds, NOW() AS generated_at, NOW() AS created_at "
        f"FROM tmp_forecast_output WHERE forecast_date > '{BIZ_DATE}';\" && "
        f"echo '[4] 预测结果生成完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 评估模型，计算 MAPE/RMSE/MAE/R²
# MAPE = mean(|actual - forecast| / |actual|) × 100%
# RMSE = sqrt(mean((actual - forecast)^2))
# MAE  = mean(|actual - forecast|)
# ---------------------------------------------------------------------------
evaluate_models = SparkSubmitOperator(
    task_id="evaluate_models",
    application="/opt/spark/jobs/energy_forecast_evaluate.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"energy_forecast_evaluate_{BIZ_DATE}",
    },
    application_args=[
        "--biz-date",
        BIZ_DATE,
        "--doris-fe",
        DORIS_FE,
        "--doris-db",
        DORIS_DB,
        "--metrics",
        "MAPE,RMSE,MAE,R_SQUARED,BIAS,TRACKING_SIGNAL",
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 选择最优模型，注册到 forecast_model_registry
# ---------------------------------------------------------------------------
select_best_model = PythonOperator(
    task_id="select_best_model",
    python_callable=lambda: print(
        f"[6] 选择 MAPE 最小的模型作为生产模型，"
        f"更新 forecast_model_registry.is_production=true，"
        f"forecast_model_evaluation.is_selected=true（biz_date={BIZ_DATE}）"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 7: 通知下游（Superset Dashboard 刷新 / 预测结果应用）
# ---------------------------------------------------------------------------
notify_downstream = PythonOperator(
    task_id="notify_downstream",
    python_callable=lambda: print(
        f"[7] 通知下游：刷新趋势预测 Dashboard，" f"将预测结果应用到定额管理与能源计划（biz_date={BIZ_DATE}）"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
load_forecast_params >> extract_history_data >> train_models
train_models >> generate_forecast >> evaluate_models
evaluate_models >> select_best_model >> notify_downstream
