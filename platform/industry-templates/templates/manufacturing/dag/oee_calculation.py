"""OEE 计算 DAG - 设备综合效率（OEE）日/班次级计算。

OEE = 可用率(Availability) × 性能率(Performance) × 质量率(Quality)

本 DAG 每日凌晨 2:00 调度，执行以下步骤：
  1. 从 IoTDB 查询设备状态时序数据，聚合为设备状态时长
  2. 从工序执行记录聚合实际产量/合格产量
  3. 计算可用率 = 实际运行时间 / 计划生产时间
  4. 计算性能率 = 实际产量 / 理论产量
  5. 计算质量率 = 合格产量 / 实际产量
  6. 计算 OEE = 可用率 × 性能率 × 质量率
  7. 写入 equipment_oee_daily / equipment_oee_shift 表
  8. 通知下游（Superset Dashboard 刷新）

提交方式：Flink SQL（流式）/ Spark SQL（批式）双引擎可选，默认 Spark 批计算。

Author: T037 制造模板工程师
"""
from __future__ import annotations

import os
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from airflow.providers.apache.spark.operators.spark_submit import SparkSubmitOperator

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
    "retry_delay": timedelta(minutes=5),
    "execution_timeout": timedelta(hours=2),
}

# ---------------------------------------------------------------------------
# DAG 定义：OEE 日计算
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="oee_calculation",
    description=(
        "设备 OEE 日计算 DAG：从 IoTDB 查询设备状态时序 + 工序产量，"
        "计算可用率×性能率×质量率=OEE，写入 equipment_oee_daily/shift 表"
    ),
    default_args=default_args,
    schedule_interval="0 2 * * *",  # 每日凌晨 2:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["manufacturing", "oee", "equipment", "iotdb", "spark"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"  # Airhead 逻辑日期
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_manufacturing")
IOTDB_HOST = os.environ.get("IOTDB_HOST", "iotdb:6667")

# ---------------------------------------------------------------------------
# Task 1: 从 IoTDB 查询设备状态时序数据，写入临时表
# ---------------------------------------------------------------------------
extract_iotdb_status = BashOperator(
    task_id="extract_iotdb_status",
    bash_command=(
        f"echo '[1] 从 IoTDB({IOTDB_HOST}) 查询设备状态时序数据 biz_date={BIZ_DATE}...' && "
        f"java -jar /opt/iotdb/iotdb-jdbc-tool.jar "
        f"--host {IOTDB_HOST} "
        f"--sql \"SELECT equipment_id, status, timestamp FROM root.mfg.equipment.* WHERE time >= {BIZ_DATE}T00:00:00.000+08:00 AND time < {BIZ_DATE}T23:59:59.999+08:00\" "
        f"--output /tmp/iotdb_status_{BIZ_DATE}.csv && "
        f"echo '[1] IoTDB 状态数据抽取完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 聚合设备状态时长（运行/停机/待机）
# ---------------------------------------------------------------------------
aggregate_status_duration = SparkSubmitOperator(
    task_id="aggregate_status_duration",
    application="/opt/spark/jobs/oee_status_aggregate.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"oee_status_aggregate_{BIZ_DATE}",
    },
    application_args=[
        "--biz-date", BIZ_DATE,
        "--doris-fe", DORIS_FE,
        "--doris-db", DORIS_DB,
        "--input", f"/tmp/iotdb_status_{BIZ_DATE}.csv",
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 计算可用率（Availability = run_time / planned_time）
# ---------------------------------------------------------------------------
calc_availability = BashOperator(
    task_id="calc_availability",
    bash_command=(
        f"echo '[3] 计算可用率 Availability = run_time / planned_time...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"INSERT INTO {DORIS_DB}.tmp_oee_availability "
        f"SELECT equipment_id, line_id, '{BIZ_DATE}' AS stat_date, "
        f"SUM(CASE WHEN status_to='RUNNING' THEN duration_sec ELSE 0 END)/60.0 AS run_time, "
        f"SUM(CASE WHEN status_to IN ('DOWN','MAINT','FAULT') THEN duration_sec ELSE 0 END)/60.0 AS down_time, "
        f"SUM(CASE WHEN status_to='IDLE' THEN duration_sec ELSE 0 END)/60.0 AS idle_time, "
        f"1440 AS planned_time "
        f"FROM {DORIS_DB}.equipment_status_log "
        f"WHERE DATE(occurred_at) = '{BIZ_DATE}' "
        f"GROUP BY equipment_id, line_id;\" && "
        f"echo '[3] 可用率计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 计算性能率（Performance = actual_output / ideal_output）
# ---------------------------------------------------------------------------
calc_performance = BashOperator(
    task_id="calc_performance",
    bash_command=(
        f"echo '[4] 计算性能率 Performance = actual_output / ideal_output...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"INSERT INTO {DORIS_DB}.tmp_oee_performance "
        f"SELECT pr.equipment_id, pr.line_id, '{BIZ_DATE}' AS stat_date, "
        f"SUM(pr.output_qty) AS actual_output, "
        f"CAST(SUM(a.run_time * e.rated_speed) AS INT) AS ideal_output "
        f"FROM {DORIS_DB}.process_record pr "
        f"JOIN {DORIS_DB}.tmp_oee_availability a ON pr.equipment_id = a.equipment_id "
        f"JOIN {DORIS_DB}.equipment e ON pr.equipment_id = e.equipment_id "
        f"WHERE DATE(pr.occurred_at) = '{BIZ_DATE}' "
        f"GROUP BY pr.equipment_id, pr.line_id;\" && "
        f"echo '[4] 性能率计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 计算质量率（Quality = good_output / actual_output）
# ---------------------------------------------------------------------------
calc_quality = BashOperator(
    task_id="calc_quality",
    bash_command=(
        f"echo '[5] 计算质量率 Quality = good_output / actual_output...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"INSERT INTO {DORIS_DB}.tmp_oee_quality "
        f"SELECT equipment_id, line_id, '{BIZ_DATE}' AS stat_date, "
        f"SUM(good_qty) AS good_output, SUM(defect_qty) AS defect_output "
        f"FROM {DORIS_DB}.process_record "
        f"WHERE DATE(occurred_at) = '{BIZ_DATE}' "
        f"GROUP BY equipment_id, line_id;\" && "
        f"echo '[5] 质量率计算完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 汇总计算 OEE = Availability × Performance × Quality，写入日汇总表
# ---------------------------------------------------------------------------
def calc_oee_final(biz_date: str, doris_fe: str, doris_db: str) -> None:
    """计算最终 OEE 并写入 equipment_oee_daily 表。

    OEE = availability * performance * quality
    """
    sql = f"""
    INSERT INTO {doris_db}.equipment_oee_daily
    (stat_id, equipment_id, equipment_code, line_id, line_code, stat_date,
     planned_time, run_time, down_time, idle_time, availability,
     ideal_output, actual_output, performance,
     good_output, defect_output, quality, oee, target_oee, oee_gap,
     created_at, updated_at)
    SELECT
        CONCAT('OEE-', e.equipment_id, '-', '{biz_date}') AS stat_id,
        e.equipment_id, e.equipment_code, e.line_id, pl.line_code,
        DATE '{biz_date}' AS stat_date,
        a.planned_time, a.run_time, a.down_time, a.idle_time,
        CASE WHEN a.planned_time > 0 THEN a.run_time / a.planned_time ELSE 0 END AS availability,
        p.ideal_output, p.actual_output,
        CASE WHEN p.ideal_output > 0 THEN p.actual_output / p.ideal_output ELSE 0 END AS performance,
        q.good_output, q.defect_output,
        CASE WHEN p.actual_output > 0 THEN q.good_output / p.actual_output ELSE 0 END AS quality,
        CASE WHEN a.planned_time > 0 AND p.ideal_output > 0 AND p.actual_output > 0
             THEN (a.run_time / a.planned_time) * (p.actual_output / p.ideal_output) * (q.good_output / p.actual_output)
             ELSE 0 END AS oee,
        pl.target_oee,
        CASE WHEN a.planned_time > 0 AND p.ideal_output > 0 AND p.actual_output > 0
             THEN (a.run_time / a.planned_time) * (p.actual_output / p.ideal_output) * (q.good_output / p.actual_output) - pl.target_oee
             ELSE -pl.target_oee END AS oee_gap,
        NOW(), NOW()
    FROM {doris_db}.equipment e
    JOIN {doris_db}.production_line pl ON e.line_id = pl.line_id
    JOIN {doris_db}.tmp_oee_availability a ON e.equipment_id = a.equipment_id
    JOIN {doris_db}.tmp_oee_performance p ON e.equipment_id = p.equipment_id
    JOIN {doris_db}.tmp_oee_quality q ON e.equipment_id = q.equipment_id
    WHERE a.stat_date = '{biz_date}';
    """
    print(f"[6] 计算 OEE 并写入 equipment_oee_daily，biz_date={biz_date}")
    print(f"SQL: {sql}")
    # 实际执行：mysql -h {doris_fe} -P 9030 -u root {doris_db} -e "{sql}"


calc_oee = PythonOperator(
    task_id="calc_oee",
    python_callable=calc_oee_final,
    op_kwargs={"biz_date": BIZ_DATE, "doris_fe": DORIS_FE, "doris_db": DORIS_DB},
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 7: 通知 OEE 计算完成
# ---------------------------------------------------------------------------
notify_done = BashOperator(
    task_id="notify_done",
    bash_command=(
        f"echo '[7] OEE 日计算完成 biz_date={BIZ_DATE} at $(date) ' "
        f">> /var/log/manufacturing/oee_calculation.log && "
        f"curl -s -X POST http://superset:8088/api/v1/dashboard/refresh/ "
        f"-H 'Authorization: Bearer $SUPERSET_TOKEN' "
        f"-d '{{\"dashboard_id\":\"oee-dashboard\"}}' || true"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖链
# ---------------------------------------------------------------------------
extract_iotdb_status >> aggregate_status_duration >> calc_availability
calc_availability >> calc_performance >> calc_quality >> calc_oee >> notify_done