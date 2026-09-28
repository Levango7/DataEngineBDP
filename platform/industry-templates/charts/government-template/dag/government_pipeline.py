"""government 行业模板 DAG - 政务数据共享与一网通办分析.
资产打包进 ConfigMap government-template-assets，由 import Job 导入 DolphinScheduler.
"""

from datetime import datetime

from airflow import DAG
from airflow.operators.python import PythonOperator

default_args = {"owner": "government", "start_date": datetime(2026, 1, 1), "retries": 1}

with DAG("government_pipeline", default_args=default_args, schedule="*/10 * * * *", catchup=False) as dag:
    t_collect_affair = PythonOperator(task_id="collect_affair", python_callable=lambda: "collect_affair")
    t_share_catalog = PythonOperator(task_id="share_catalog", python_callable=lambda: "share_catalog")
    t_gov_kpi = PythonOperator(task_id="gov_kpi", python_callable=lambda: "gov_kpi")
    t_collect_affair >> t_share_catalog >> t_gov_kpi
