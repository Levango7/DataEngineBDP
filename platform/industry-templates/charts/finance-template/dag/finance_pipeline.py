"""finance 行业模板 DAG - 风控评分卡.
资产打包进 ConfigMap finance-template-assets，由 import Job 导入 DolphinScheduler.
"""

from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator

default_args = {"owner": "finance", "start_date": datetime(2026, 1, 1), "retries": 1}

with DAG("finance_pipeline", default_args=default_args, schedule="*/10 * * * *", catchup=False) as dag:
    t_feature_eng = PythonOperator(task_id="feature_eng", python_callable=lambda: "feature_eng")
    t_xgboost_score = PythonOperator(task_id="xgboost_score", python_callable=lambda: "xgboost_score")
    t_decision = PythonOperator(task_id="decision", python_callable=lambda: "decision")
    t_feature_eng >> t_xgboost_score >> t_decision
