"""manufacturing 行业模板 DAG - 产线质检流水线.
资产打包进 ConfigMap manufacturing-template-assets，由 import Job 导入 DolphinScheduler.
"""

from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator

default_args = {"owner": "manufacturing", "start_date": datetime(2026, 1, 1), "retries": 1}

with DAG("manufacturing_pipeline", default_args=default_args, schedule="*/10 * * * *", catchup=False) as dag:
    t_image_defect = PythonOperator(task_id="image_defect", python_callable=lambda: "image_defect")
    t_spc_control = PythonOperator(task_id="spc_control", python_callable=lambda: "spc_control")
    t_quality_grade = PythonOperator(task_id="quality_grade", python_callable=lambda: "quality_grade")
    t_image_defect >> t_spc_control >> t_quality_grade
