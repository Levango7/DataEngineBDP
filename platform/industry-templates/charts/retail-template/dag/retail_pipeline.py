"""retail 行业模板 DAG - 用户画像标签体系.
资产打包进 ConfigMap retail-template-assets，由 import Job 导入 DolphinScheduler.
"""

from datetime import datetime

from airflow import DAG
from airflow.operators.python import PythonOperator

default_args = {"owner": "retail", "start_date": datetime(2026, 1, 1), "retries": 1}

with DAG("retail_pipeline", default_args=default_args, schedule="*/10 * * * *", catchup=False) as dag:
    t_rfm_compute = PythonOperator(task_id="rfm_compute", python_callable=lambda: "rfm_compute")
    t_value_tag = PythonOperator(task_id="value_tag", python_callable=lambda: "value_tag")
    t_behavior_tag = PythonOperator(task_id="behavior_tag", python_callable=lambda: "behavior_tag")
    t_rfm_compute >> t_value_tag >> t_behavior_tag
