"""education 行业模板 DAG - 学情画像与教学评估.
资产打包进 ConfigMap education-template-assets，由 import Job 导入 DolphinScheduler.
"""

from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator

default_args = {"owner": "education", "start_date": datetime(2026, 1, 1), "retries": 1}

with DAG("education_pipeline", default_args=default_args, schedule="*/10 * * * *", catchup=False) as dag:
    t_collect_edu = PythonOperator(task_id="collect_edu", python_callable=lambda: "collect_edu")
    t_profile_build = PythonOperator(task_id="profile_build", python_callable=lambda: "profile_build")
    t_teach_kpi = PythonOperator(task_id="teach_kpi", python_callable=lambda: "teach_kpi")
    t_collect_edu >> t_profile_build >> t_teach_kpi
