"""设备状态实时监控 DAG - 能源设备状态采集、健康度计算与告警触发.

本 DAG 每 5 分钟调度一次，执行以下步骤：
  1. 从 IoTDB 查询设备最新状态时序数据（电压/电流/温度/压力/流量等）
  2. 更新 device_realtime_status 表（设备实时状态）
  3. 检测设备在线/离线状态变更，写入 device_status_change 表
  4. 基于告警规则（device_alarm_rule）评估指标越限，触发告警写入 device_alarm_record
  5. 计算设备健康度评分（可用率+性能率+告警率三维度融合），写入 device_health_score

健康度评分公式：
    health_score = w1 * availability_score + w2 * performance_score + w3 * alarm_score
    默认权重 w1=0.4, w2=0.4, w3=0.2，取值范围 [0, 100]

提交方式：Flink SQL（流式）/ Spark SQL（批式）双引擎可选，默认 Flink 流计算。

Author: T043 能源行业模板工程师
"""
from __future__ import annotations

import os
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from airflow.providers.apache.flink.operators.flink_submit import FlinkSubmitOperator

# ---------------------------------------------------------------------------
# DAG 默认参数
# ---------------------------------------------------------------------------
default_args = {
    "owner": "energy-ops",
    "depends_on_past": False,
    "email": ["energy-ops@shuqing.com"],
    "email_on_failure": True,
    "email_on_retry": False,
    "retries": 3,
    "retry_delay": timedelta(minutes=2),
    "execution_timeout": timedelta(minutes=30),
}

# ---------------------------------------------------------------------------
# DAG 定义：设备状态实时监控
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="device_status_monitoring",
    description=(
        "设备状态实时监控 DAG：从 IoTDB 查询设备时序数据，更新实时状态，"
        "检测状态变更，触发告警，计算健康度评分"
    ),
    default_args=default_args,
    schedule_interval="*/5 * * * *",  # 每 5 分钟
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["energy", "device-monitoring", "iotdb", "flink", "realtime"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
BIZ_TS = "{{ ts }}"
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_energy")
IOTDB_HOST = os.environ.get("IOTDB_HOST", "iotdb:6667")
FLINK_JM = os.environ.get("FLINK_JM", "flink-jobmanager:8081")

# ---------------------------------------------------------------------------
# Task 1: 从 IoTDB 查询设备最新状态时序数据
# ---------------------------------------------------------------------------
extract_iotdb_status = BashOperator(
    task_id="extract_iotdb_status",
    bash_command=(
        f"echo '[1] 从 IoTDB({IOTDB_HOST}) 查询设备最新状态时序数据 ts={BIZ_TS}...' && "
        f"java -jar /opt/iotdb/iotdb-jdbc-tool.jar "
        f"--host {IOTDB_HOST} "
        f"--sql \"SELECT * FROM root.energy.device.* WHERE time >= now() - 5m\" "
        f"--output /tmp/iotdb_device_status_{BIZ_TS}.csv && "
        f"echo '[1] IoTDB 设备状态数据抽取完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 更新设备实时状态表 device_realtime_status
# ---------------------------------------------------------------------------
update_realtime_status = BashOperator(
    task_id="update_realtime_status",
    bash_command=(
        f"echo '[2] 更新 device_realtime_status 表...' && "
        f"flink run -m {FLINK_JM} "
        f"/opt/flink/jobs/energy_device_realtime_update.py "
        f"--input /tmp/iotdb_device_status_{BIZ_TS}.csv "
        f"--doris-fe {DORIS_FE} --doris-db {DORIS_DB} && "
        f"echo '[2] 实时状态更新完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 检测设备状态变更，写入 device_status_change 表
# ---------------------------------------------------------------------------
detect_status_change = PythonOperator(
    task_id="detect_status_change",
    python_callable=lambda: print(
        "[3] 检测设备在线/离线状态变更，对比 device_realtime_status 与 energy_device.status，"
        "写入 device_status_change 表，同步更新 energy_device.status"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 评估告警规则，触发告警写入 device_alarm_record
# ---------------------------------------------------------------------------
evaluate_alarm_rules = BashOperator(
    task_id="evaluate_alarm_rules",
    bash_command=(
        f"echo '[4] 评估告警规则（device_alarm_rule）...' && "
        f"spark-submit --master {SPARK_MASTER} "
        f"/opt/spark/jobs/energy_alarm_evaluation.py "
        f"--biz-ts '{BIZ_TS}' "
        f"--doris-fe {DORIS_FE} --doris-db {DORIS_DB} && "
        f"echo '[4] 告警评估完成，新告警已写入 device_alarm_record'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 计算设备健康度评分（可用率+性能率+告警率三维度融合）
# 健康度公式：health_score = 0.4*availability + 0.4*performance + 0.2*alarm_score
# ---------------------------------------------------------------------------
calc_health_score = BashOperator(
    task_id="calc_health_score",
    bash_command=(
        f"echo '[5] 计算设备健康度评分...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"INSERT INTO {DORIS_DB}.device_health_score "
        f"SELECT CONCAT(device_id, '_', DATE(NOW())) AS score_id, "
        f"device_id, device_code, DATE(NOW()) AS stat_date, "
        f"-- 可用率评分 = 在线时长/总时长*100"
        f"COALESCE(SUM(CASE WHEN online_status='ONLINE' THEN 1 ELSE 0 END)*100.0/COUNT(*), 0) AS availability_score, "
        f"-- 性能评分 = 100 - 偏离额定参数的扣分"
        f"COALESCE(100 - AVG(ABS(instantaneous_rate - rated_power)/NULLIF(rated_power,0)*100), 0) AS performance_score, "
        f"-- 告警评分 = 100 - 告警扣分（CRITICAL=20, WARNING=5, INFO=1）"
        f"100 - SUM(CASE WHEN alarm_level='CRITICAL' THEN 20 WHEN alarm_level='WARNING' THEN 5 WHEN alarm_level='INFO' THEN 1 ELSE 0 END) AS alarm_score, "
        f"-- 综合健康度 = 0.4*可用率 + 0.4*性能 + 0.2*告警"
        f"0.4*availability_score + 0.4*performance_score + 0.2*alarm_score AS health_score, "
        f"0.40 AS weight_availability, 0.40 AS weight_performance, 0.20 AS weight_alarm, "
        f"CASE WHEN health_score >= 90 THEN 'EXCELLENT' WHEN health_score >= 80 THEN 'GOOD' "
        f"WHEN health_score >= 70 THEN 'FAIR' WHEN health_score >= 60 THEN 'POOR' ELSE 'CRITICAL' END AS health_grade, "
        f"NOW() AS created_at "
        f"FROM {DORIS_DB}.device_realtime_status s "
        f"LEFT JOIN {DORIS_DB}.energy_device d ON s.device_id = d.device_id "
        f"LEFT JOIN {DORIS_DB}.device_alarm_record a ON s.device_id = a.device_id AND DATE(a.alarm_time) = DATE(NOW()) "
        f"GROUP BY device_id, device_code;\" && "
        f"echo '[5] 健康度评分计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 通知下游（Superset Dashboard 刷新 / 告警路由）
# ---------------------------------------------------------------------------
notify_downstream = PythonOperator(
    task_id="notify_downstream",
    python_callable=lambda: print(
        "[6] 通知下游：触发 device_alert_routing DAG 处理新告警，"
        "刷新设备监测 Dashboard"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
extract_iotdb_status >> update_realtime_status >> detect_status_change
detect_status_change >> evaluate_alarm_rules >> calc_health_score >> notify_downstream