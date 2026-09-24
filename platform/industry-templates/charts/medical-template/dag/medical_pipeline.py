"""medical 行业模板 DAG - 医疗质控与DRG/DIP.
资产打包进 ConfigMap medical-template-assets，由 import Job 导入 DolphinScheduler.
"""

from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator

default_args = {"owner": "medical", "start_date": datetime(2026, 1, 1), "retries": 1}

with DAG("medical_pipeline", default_args=default_args, schedule="*/10 * * * *", catchup=False) as dag:
    t_emr_struct = PythonOperator(task_id="emr_struct", python_callable=lambda: "emr_struct")
    t_quality_check = PythonOperator(task_id="quality_check", python_callable=lambda: "quality_check")
    t_drg_analyze = PythonOperator(task_id="drg_analyze", python_callable=lambda: "drg_analyze")
    t_emr_struct >> t_quality_check >> t_drg_analyze
