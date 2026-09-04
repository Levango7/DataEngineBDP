"""能源行业模板 DAG - 能耗聚合与告警流水线.
资产打包进 ConfigMap energy-template-assets，由 import Job 导入 DolphinScheduler.
对应 template energy-iot-monitor dataFlow: collect_telemetry -> energy_agg -> efficiency -> load_forecast -> alert_dispatch
"""
from datetime import datetime, timedelta
from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator

default_args = {"owner": "energy", "start_date": datetime(2026, 1, 1), "retries": 2, "retry_delay": timedelta(minutes=5)}

def collect_telemetry():
    # 采集发电/输变电设备遥测 -> ods.device_telemetry
    return "collect iotdb telemetry -> ods.device_telemetry"

def energy_agg():
    # 按机组+5分钟窗口聚合电耗热耗 -> dwd.energy_usage
    return "aggregate energy -> dwd.energy_usage"

def efficiency():
    # 计算能效比/煤耗率 -> dws.energy_kpi
    return "compute efficiency -> dws.energy_kpi"

def load_forecast():
    # 时序模型预测未来负荷 -> dws.load_pred
    return "forecast load -> dws.load_pred"

def alert_dispatch():
    # 温度/振动越限生成告警 -> ads.alert_events
    return "dispatch alert -> ads.alert_events"

with DAG("energy_alert_pipeline", default_args=default_args, schedule="*/5 * * * *", catchup=False) as dag:
    t1 = PythonOperator(task_id="collect_telemetry", python_callable=collect_telemetry)
    t2 = PythonOperator(task_id="energy_agg", python_callable=energy_agg)
    t3 = PythonOperator(task_id="efficiency", python_callable=efficiency)
    t4 = PythonOperator(task_id="load_forecast", python_callable=load_forecast)
    t5 = BashOperator(task_id="alert_dispatch", bash_command=alert_dispatch.__doc__ or "echo alert")
    t1 >> t2 >> t3 >> t4 >> t5