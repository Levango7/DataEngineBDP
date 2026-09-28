"""transportation 行业模板 DAG - 交通流量预测与轨迹分析.
资产打包进 ConfigMap transportation-template-assets，由 import Job 导入 DolphinScheduler.
"""

from datetime import datetime

from airflow import DAG
from airflow.operators.python import PythonOperator

default_args = {"owner": "transportation", "start_date": datetime(2026, 1, 1), "retries": 1}

with DAG("transportation_pipeline", default_args=default_args, schedule="*/10 * * * *", catchup=False) as dag:
    t_traj_analyze = PythonOperator(task_id="traj_analyze", python_callable=lambda: "traj_analyze")
    t_flow_forecast = PythonOperator(task_id="flow_forecast", python_callable=lambda: "flow_forecast")
    t_signal_plan = PythonOperator(task_id="signal_plan", python_callable=lambda: "signal_plan")
    t_traj_analyze >> t_flow_forecast >> t_signal_plan
