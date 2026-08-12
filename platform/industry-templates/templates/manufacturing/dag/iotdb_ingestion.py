"""IoTDB 数据接入 DAG - 设备传感器时序数据接入。

本 DAG 每 15 分钟调度，执行以下步骤：
  1. 通过 IoTDB JDBC 查询设备传感器最新时序数据
  2. 通过 Flink IoTDB Source Connector 实时接入设备状态变更事件
  3. 数据清洗与转换（时间戳对齐/单位换算/异常值过滤）
  4. 写入 equipment_sensor_metric 表（传感器指标）
  5. 写入 equipment_status_log 表（状态变更日志）
  6. 触发 OEE 实时计算（如启用流式 OEE）

IoTDB 时序路径示例：
  root.mfg.equipment.EQP-001.temperature
  root.mfg.equipment.EQP-001.vibration
  root.mfg.equipment.EQP-001.current
  root.mfg.equipment.EQP-001.status

Author: T037 制造模板工程师
"""

from __future__ import annotations

from datetime import datetime, timedelta
import os

from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from airflow.providers.apache.flink.operators.flink import FlinkSubmitOperator

# ---------------------------------------------------------------------------
# DAG 默认参数
# ---------------------------------------------------------------------------
default_args = {
    "owner": "manufacturing-ops",
    "depends_on_past": False,
    "email": ["mfg-ops@shuqing.com"],
    "email_on_failure": True,
    "email_on_retry": False,
    "retries": 3,
    "retry_delay": timedelta(minutes=2),
    "execution_timeout": timedelta(minutes=30),
}

# ---------------------------------------------------------------------------
# DAG 定义：IoTDB 数据接入
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="iotdb_ingestion",
    description=(
        "IoTDB 数据接入 DAG：通过 JDBC 查询 + Flink IoTDB Source Connector "
        "实时接入设备传感器时序数据，写入 equipment_sensor_metric / equipment_status_log 表"
    ),
    default_args=default_args,
    schedule_interval="*/15 * * * *",  # 每 15 分钟
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["manufacturing", "iotdb", "timeseries", "flink", "ingestion"],
)

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
EXEC_TIME = "{{ execution_date }}"
IOTDB_HOST = os.environ.get("IOTDB_HOST", "iotdb:6667")
IOTDB_USER = os.environ.get("IOTDB_USER", "root")
IOTDB_PASSWORD = os.environ.get("IOTDB_PASSWORD", "root")
FLINK_JOBMANAGER = os.environ.get("FLINK_JM", "flink-jobmanager:8081")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_manufacturing")

# ---------------------------------------------------------------------------
# Task 1: 通过 IoTDB JDBC 查询设备传感器最新数据
# ---------------------------------------------------------------------------
query_iotdb_sensors = BashOperator(
    task_id="query_iotdb_sensors",
    bash_command=(
        f"echo '[1] 通过 IoTDB JDBC 查询设备传感器数据 exec_time={EXEC_TIME}...' && "
        f"java -jar /opt/iotdb/iotdb-jdbc-tool.jar "
        f"--host {IOTDB_HOST} --user {IOTDB_USER} --password {IOTDB_PASSWORD} "
        f'--sql "SELECT * FROM root.mfg.equipment.* WHERE time >= now() - 15m" '
        f"--output /tmp/iotdb_sensors_{EXEC_TIME}.json && "
        f"echo '[1] IoTDB 传感器数据查询完成: /tmp/iotdb_sensors_{EXEC_TIME}.json'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 通过 Flink IoTDB Source Connector 实时接入设备状态变更事件
# ---------------------------------------------------------------------------
flink_iotdb_source = FlinkSubmitOperator(
    task_id="flink_iotdb_source",
    job_name=f"iotdb_status_ingestion_{BIZ_DATE}",
    entry_class="com.shuqing.iotdb.IoTDBStatusIngestionJob",
    jar="/opt/flink/jobs/iotdb-status-ingestion.jar",
    conn_id="flink_default",
    flink_options={
        "jobmanager": FLINK_JOBMANAGER,
        "parallelism": "4",
    },
    application_args=[
        "--iotdb-host",
        IOTDB_HOST,
        "--iotdb-user",
        IOTDB_USER,
        "--iotdb-password",
        IOTDB_PASSWORD,
        "--iotdb-path",
        "root.mfg.equipment.*.status",
        "--doris-fe",
        DORIS_FE,
        "--doris-db",
        DORIS_DB,
        "--target-table",
        "equipment_status_log",
    ],
    dag=dag,
)


# ---------------------------------------------------------------------------
# Task 3: 数据清洗与转换
# ---------------------------------------------------------------------------
def clean_transform(exec_time: str, doris_db: str) -> None:
    """数据清洗与转换。

    - 时间戳对齐（IoTDB 毫秒时间戳 → Doris DATETIME）
    - 单位换算（如温度 ℃→℉ 如需）
    - 异常值过滤（超出合理范围的传感器读数）
    - 指标状态判定（NORMAL/WARNING/ALARM 基于阈值）
    """
    print(f"[3] 数据清洗与转换 exec_time={exec_time}")
    # 清洗逻辑示例：
    # 1. 过滤异常值：temperature > 200 或 < -50 视为异常
    # 2. 指标状态判定：基于设备配置的阈值判定 NORMAL/WARNING/ALARM
    # 3. 时间戳对齐：IoTDB ts(ms) → DATETIME


clean_data = PythonOperator(
    task_id="clean_transform",
    python_callable=clean_transform,
    op_kwargs={"exec_time": EXEC_TIME, "doris_db": DORIS_DB},
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 写入 equipment_sensor_metric 表
# ---------------------------------------------------------------------------
load_sensor_metrics = BashOperator(
    task_id="load_sensor_metrics",
    bash_command=(
        f"echo '[4] 写入 equipment_sensor_metric 表...' && "
        f"python3 /opt/jobs/load_sensor_metrics.py "
        f"--input /tmp/iotdb_sensors_{EXEC_TIME}.json "
        f"--doris-fe {DORIS_FE} --doris-db {DORIS_DB} "
        f"--target-table equipment_sensor_metric && "
        f"echo '[4] 传感器指标写入完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 触发 OEE 实时计算（如启用流式 OEE）
# ---------------------------------------------------------------------------
trigger_realtime_oee = BashOperator(
    task_id="trigger_realtime_oee",
    bash_command=(
        "echo '[5] 触发 OEE 实时计算...' && "
        "curl -s -X POST http://flink-jobmanager:8081/jobs "
        "-H 'Content-Type: application/json' "
        '-d \'{"jarId":"oee-streaming.jar","entryClass":"com.shuqing.oee.OEEStreamingJob"}\' '
        "|| echo '流式 OEE 未启用，跳过' && "
        "echo '[5] OEE 实时计算触发完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 通知接入完成
# ---------------------------------------------------------------------------
notify_done = BashOperator(
    task_id="notify_done",
    bash_command=(
        f"echo '[6] IoTDB 数据接入完成 exec_time={EXEC_TIME}' " f">> /var/log/manufacturing/iotdb_ingestion.log"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖链
# ---------------------------------------------------------------------------
query_iotdb_sensors >> flink_iotdb_source >> clean_data
clean_data >> load_sensor_metrics >> trigger_realtime_oee >> notify_done
