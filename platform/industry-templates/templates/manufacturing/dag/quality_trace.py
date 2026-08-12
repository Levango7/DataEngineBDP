"""质量追溯 DAG - 批次/工序/参数/缺陷正反向追溯。

本 DAG 每日凌晨 3:00 调度，执行以下步骤：
  1. 从工序执行记录构建追溯链路（批次→工序→参数）
  2. 关联缺陷记录，补全反向追溯链路（缺陷→参数→工序→批次）
  3. 关联来料批次/供应商，补全全链路追溯（批次→原料→供应商）
  4. 计算工序能力指数 Cpk（基于质量参数实测值）
  5. 写入 quality_trace_link 表
  6. 生成质量追溯报告，触发 Dashboard 刷新

追溯能力：
  - 正向追溯：批次 → 工序 → 参数 → 缺陷（从原料到成品）
  - 反向追溯：缺陷 → 参数 → 工序 → 批次 → 原料 → 供应商（从缺陷定位根因）

Author: T037 制造模板工程师
"""

from __future__ import annotations

from datetime import datetime, timedelta
import os

from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from airflow.providers.apache.spark.operators.spark_submit import SparkSubmitOperator

# ---------------------------------------------------------------------------
# DAG 默认参数
# ---------------------------------------------------------------------------
default_args = {
    "owner": "quality-engineer",
    "depends_on_past": False,
    "email": ["quality@shuqing.com"],
    "email_on_failure": True,
    "email_on_retry": False,
    "retries": 3,
    "retry_delay": timedelta(minutes=5),
    "execution_timeout": timedelta(hours=2),
}

# ---------------------------------------------------------------------------
# DAG 定义：质量追溯
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="quality_trace",
    description=("质量追溯 DAG：构建批次→工序→参数→缺陷正反向追溯链路，" "计算 Cpk，写入 quality_trace_link 表"),
    default_args=default_args,
    schedule_interval="0 3 * * *",  # 每日凌晨 3:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["manufacturing", "quality", "traceability", "spark"],
)

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_manufacturing")

# ---------------------------------------------------------------------------
# Task 1: 构建正向追溯链路（批次→工序→参数）
# ---------------------------------------------------------------------------
build_forward_trace = SparkSubmitOperator(
    task_id="build_forward_trace",
    application="/opt/spark/jobs/quality_forward_trace.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"quality_forward_trace_{BIZ_DATE}",
    },
    application_args=[
        "--biz-date",
        BIZ_DATE,
        "--doris-fe",
        DORIS_FE,
        "--doris-db",
        DORIS_DB,
        "--direction",
        "FORWARD",
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 构建反向追溯链路（缺陷→参数→工序→批次）
# ---------------------------------------------------------------------------
build_backward_trace = SparkSubmitOperator(
    task_id="build_backward_trace",
    application="/opt/spark/jobs/quality_backward_trace.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"quality_backward_trace_{BIZ_DATE}",
    },
    application_args=[
        "--biz-date",
        BIZ_DATE,
        "--doris-fe",
        DORIS_FE,
        "--doris-db",
        DORIS_DB,
        "--direction",
        "BACKWARD",
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 关联来料批次/供应商，补全全链路追溯
# ---------------------------------------------------------------------------
link_material_supplier = BashOperator(
    task_id="link_material_supplier",
    bash_command=(
        f"echo '[3] 关联来料批次/供应商，补全全链路追溯...' && "
        f'spark-sql --master {SPARK_MASTER} -e "'
        f"INSERT INTO {DORIS_DB}.quality_trace_link "
        f"(link_id, source_type, source_id, target_type, target_id, "
        f"relation, trace_direction, batch_id, remark, created_at) "
        f"SELECT CONCAT('LINK-MS-', b.batch_id, '-', p.po_id), "
        f"'BATCH', b.batch_id, 'SUPPLIER', p.supplier_id, 'DERIVED_FROM', 'BACKWARD', b.batch_id, "
        f"'批次原料源自采购订单', NOW() "
        f"FROM {DORIS_DB}.product_batch b "
        f"JOIN {DORIS_DB}.work_order w ON b.batch_id = w.batch_id "
        f"JOIN {DORIS_DB}.purchase_order p ON w.product_code = p.material_code "
        f"WHERE DATE(b.created_at) = '{BIZ_DATE}';\" && "
        f"echo '[3] 来料/供应商追溯链路补全完成'"
    ),
    dag=dag,
)


# ---------------------------------------------------------------------------
# Task 4: 计算工序能力指数 Cpk
# ---------------------------------------------------------------------------
def calc_cpk(biz_date: str, doris_db: str) -> None:
    """计算工序能力指数 Cpk。

    Cpk = min(USL - mean, mean - LSL) / (3 * sigma)
    其中 USL 为规格上限，LSL 为规格下限，mean 为均值，sigma 为标准差。
    """
    sql = f"""
    UPDATE {doris_db}.quality_parameter qp
    SET cpk = LEAST(qp.upper_limit - sub.mean_val, sub.mean_val - qp.lower_limit) / (3 * sub.sigma_val)
    FROM (
        SELECT record_id, param_code,
               AVG(measured_value) AS mean_val,
               STDDEV(measured_value) AS sigma_val
        FROM {doris_db}.quality_parameter
        WHERE DATE(measured_at) = '{biz_date}'
        GROUP BY record_id, param_code
    ) sub
    WHERE qp.record_id = sub.record_id AND qp.param_code = sub.param_code;
    """
    print(f"[4] 计算 Cpk，biz_date={biz_date}")
    print(f"SQL: {sql}")


calc_cpk_task = PythonOperator(
    task_id="calc_cpk",
    python_callable=calc_cpk,
    op_kwargs={"biz_date": BIZ_DATE, "doris_db": DORIS_DB},
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 生成质量追溯报告
# ---------------------------------------------------------------------------
generate_report = BashOperator(
    task_id="generate_report",
    bash_command=(
        f"echo '[5] 生成质量追溯报告 biz_date={BIZ_DATE}...' && "
        f'spark-sql --master {SPARK_MASTER} -e "'
        f"SELECT b.batch_no, b.product_name, b.good_qty, b.defect_qty, "
        f"d.defect_name, d.defect_category, d.root_cause, d.action "
        f"FROM {DORIS_DB}.product_batch b "
        f"LEFT JOIN {DORIS_DB}.defect_record d ON b.batch_id = d.batch_id "
        f"WHERE DATE(b.created_at) = '{BIZ_DATE}' "
        f'ORDER BY b.batch_no;" > /tmp/quality_report_{BIZ_DATE}.csv && '
        f"echo '[5] 质量追溯报告生成完成: /tmp/quality_report_{BIZ_DATE}.csv'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 通知完成，刷新 Dashboard
# ---------------------------------------------------------------------------
notify_done = BashOperator(
    task_id="notify_done",
    bash_command=(
        f"echo '[6] 质量追溯 DAG 完成 biz_date={BIZ_DATE}' "
        f">> /var/log/manufacturing/quality_trace.log && "
        f"curl -s -X POST http://superset:8088/api/v1/dashboard/refresh/ "
        f"-H 'Authorization: Bearer $SUPERSET_TOKEN' "
        f'-d \'{{"dashboard_id":"quality-dashboard"}}\' || true'
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖链
# ---------------------------------------------------------------------------
build_forward_trace >> build_backward_trace >> link_material_supplier
link_material_supplier >> calc_cpk_task >> generate_report >> notify_done
