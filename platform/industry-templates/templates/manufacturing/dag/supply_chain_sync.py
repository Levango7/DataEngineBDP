"""供应链协同 DAG - 订单/库存/物流协同与预警。

本 DAG 每日凌晨 4:00 调度，执行以下步骤：
  1. 库存预警检测（低于安全库存/高于最大库存/触发再订货点）
  2. 交期预警检测（采购订单逾期/销售订单交期临近）
  3. 订单-库存协同（销售订单触发生产工单/采购需求）
  4. 物流状态同步（更新发货单物流轨迹）
  5. 生成供应链事件，写入 supply_chain_event 表
  6. 通知供应链经理，刷新 Dashboard

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
    "owner": "supply-chain-manager",
    "depends_on_past": False,
    "email": ["scm@shuqing.com"],
    "email_on_failure": True,
    "email_on_retry": False,
    "retries": 3,
    "retry_delay": timedelta(minutes=5),
    "execution_timeout": timedelta(hours=2),
}

# ---------------------------------------------------------------------------
# DAG 定义：供应链协同
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="supply_chain_sync",
    description=(
        "供应链协同 DAG：库存预警/交期预警/订单-库存协同/物流状态同步，"
        "生成供应链事件写入 supply_chain_event 表"
    ),
    default_args=default_args,
    schedule_interval="0 4 * * *",  # 每日凌晨 4:00
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["manufacturing", "supply-chain", "inventory", "logistics", "spark"],
)

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
BIZ_DATE = "{{ ds }}"
SPARK_MASTER = os.environ.get("SPARK_MASTER", "spark://spark-master:7077")
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_manufacturing")

# ---------------------------------------------------------------------------
# Task 1: 库存预警检测
# ---------------------------------------------------------------------------
def detect_stock_alert(biz_date: str, doris_db: str) -> None:
    """检测库存预警并生成事件。

    预警类型：
      - STOCK_LOW: 库存 < 安全库存
      - STOCK_OVER: 库存 > 最大库存
      - 触发再订货点: 库存 <= 再订货点
    """
    sql = f"""
    INSERT INTO {doris_db}.supply_chain_event
    (event_id, event_type, event_level, ref_type, ref_id, ref_no,
     event_desc, impact_analysis, action_suggest, occurred_at, detected_at,
     status, created_at, updated_at)
    SELECT
        CONCAT('EVT-STOCK-', i.inventory_id, '-', '{biz_date}'),
        CASE
            WHEN i.quantity < i.safety_stock THEN 'STOCK_LOW'
            WHEN i.quantity > i.max_stock THEN 'STOCK_OVER'
            WHEN i.quantity <= i.reorder_point THEN 'STOCK_LOW'
        END,
        CASE WHEN i.quantity < i.safety_stock THEN 'CRITICAL' ELSE 'WARN' END,
        'INVENTORY', i.inventory_id, i.material_code,
        CONCAT('物料 ', i.material_name, ' 当前库存 ', CAST(i.quantity AS VARCHAR),
               ', 安全库存 ', CAST(i.safety_stock AS VARCHAR),
               ', 最大库存 ', CAST(i.max_stock AS VARCHAR)),
        CONCAT('可能影响生产计划/客户交期'),
        CASE
            WHEN i.quantity < i.safety_stock THEN '立即发起采购订单'
            WHEN i.quantity > i.max_stock THEN '暂停采购/调拨至其他仓库'
            ELSE '触发再订货流程'
        END,
        NOW(), NOW(), 'OPEN', NOW(), NOW()
    FROM {doris_db}.inventory i
    WHERE i.quantity < i.safety_stock OR i.quantity > i.max_stock
       OR i.quantity <= i.reorder_point;
    """
    print(f"[1] 库存预警检测 biz_date={biz_date}")
    print(f"SQL: {sql}")


detect_stock = PythonOperator(
    task_id="detect_stock_alert",
    python_callable=detect_stock_alert,
    op_kwargs={"biz_date": BIZ_DATE, "doris_db": DORIS_DB},
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 2: 交期预警检测
# ---------------------------------------------------------------------------
detect_delivery_delay = BashOperator(
    task_id="detect_delivery_delay",
    bash_command=(
        f"echo '[2] 交期预警检测...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"INSERT INTO {DORIS_DB}.supply_chain_event "
        f"(event_id, event_type, event_level, ref_type, ref_id, ref_no, event_desc, occurred_at, detected_at, status, created_at, updated_at) "
        f"SELECT CONCAT('EVT-DELAY-PO-', p.po_id), 'DELAY', 'WARN', 'PO', p.po_id, p.po_no, "
        f"CONCAT('采购订单 ', p.po_no, ' 逾期，计划交货 ', CAST(p.plan_delivery AS VARCHAR)), "
        f"NOW(), NOW(), 'OPEN', NOW(), NOW() "
        f"FROM {DORIS_DB}.purchase_order p "
        f"WHERE p.status NOT IN ('CLOSED') AND p.plan_delivery < CURRENT_DATE;\" && "
        f"echo '[2] 交期预警检测完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 3: 订单-库存协同（销售订单触发生产工单/采购需求）
# ---------------------------------------------------------------------------
order_inventory_sync = SparkSubmitOperator(
    task_id="order_inventory_sync",
    application="/opt/spark/jobs/scm_order_inventory_sync.py",
    conn_id="spark_default",
    conf={
        "spark.master": SPARK_MASTER,
        "spark.app.name": f"scm_order_sync_{BIZ_DATE}",
    },
    application_args=[
        "--biz-date", BIZ_DATE,
        "--doris-fe", DORIS_FE,
        "--doris-db", DORIS_DB,
    ],
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 4: 物流状态同步
# ---------------------------------------------------------------------------
sync_logistics = BashOperator(
    task_id="sync_logistics",
    bash_command=(
        f"echo '[4] 物流状态同步...' && "
        f"python3 /opt/jobs/sync_logistics_tracking.py --biz-date {BIZ_DATE} "
        f"--doris-fe {DORIS_FE} --doris-db {DORIS_DB} && "
        f"echo '[4] 物流状态同步完成'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 5: 生成供应链协同事件汇总
# ---------------------------------------------------------------------------
generate_sc_events = BashOperator(
    task_id="generate_sc_events",
    bash_command=(
        f"echo '[5] 生成供应链事件汇总 biz_date={BIZ_DATE}...' && "
        f"spark-sql --master {SPARK_MASTER} -e \""
        f"SELECT event_type, event_level, COUNT(*) AS cnt, "
        f"GROUP_CONCAT(ref_no) AS ref_list "
        f"FROM {DORIS_DB}.supply_chain_event "
        f"WHERE DATE(detected_at) = '{BIZ_DATE}' "
        f"GROUP BY event_type, event_level "
        f"ORDER BY event_level, event_type;\" > /tmp/sc_events_{BIZ_DATE}.csv && "
        f"echo '[5] 供应链事件汇总完成: /tmp/sc_events_{BIZ_DATE}.csv'"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# Task 6: 通知供应链经理，刷新 Dashboard
# ---------------------------------------------------------------------------
notify_done = BashOperator(
    task_id="notify_done",
    bash_command=(
        f"echo '[6] 供应链协同 DAG 完成 biz_date={BIZ_DATE}' "
        f">> /var/log/manufacturing/supply_chain_sync.log && "
        f"curl -s -X POST http://superset:8088/api/v1/dashboard/refresh/ "
        f"-H 'Authorization: Bearer $SUPERSET_TOKEN' "
        f"-d '{{\"dashboard_id\":\"supply-chain-dashboard\"}}' || true"
    ),
    dag=dag,
)

# ---------------------------------------------------------------------------
# 任务依赖链
# ---------------------------------------------------------------------------
detect_stock >> detect_delivery_delay >> order_inventory_sync
order_inventory_sync >> sync_logistics >> generate_sc_events >> notify_done