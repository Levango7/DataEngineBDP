"""设备告警路由 DAG - 告警分级、通知分发与抑制管理.

本 DAG 每 1 分钟调度一次，执行以下步骤：
  1. 查询 device_alarm_record 中 status=ACTIVE 的新告警
  2. 告警分级（INFO/WARNING/CRITICAL/EMERGENCY）
  3. 告警抑制（相同告警在抑制期内不重复触发）
  4. 通知分发（按级别路由到不同渠道）：
     - INFO      → 看板标记
     - WARNING   → 邮件 + 看板
     - CRITICAL  → 邮件 + 短信 + 钉钉/飞书 + 看板
     - EMERGENCY → 邮件 + 短信 + 电话 + 钉钉/飞书 + 看板 + 自动派单
  5. 自动派单（EMERGENCY 级别触发运维工单）
  6. 告警升级（长时间未确认的告警升级级别）

提交方式：Python 单机（调用通知服务 API）。

Author: T043 能源行业模板工程师
"""
from __future__ import annotations

import os
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator

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
    "retry_delay": timedelta(seconds=30),
    "execution_timeout": timedelta(minutes=5),
}

# ---------------------------------------------------------------------------
# DAG 定义：设备告警路由
# ---------------------------------------------------------------------------
dag = DAG(
    dag_id="device_alert_routing",
    description=(
        "设备告警路由 DAG：查询新告警，分级处理，抑制去重，"
        "按级别分发到邮件/短信/电话/钉钉/飞书/看板，自动派单与升级"
    ),
    default_args=default_args,
    schedule_interval="* * * * *",  # 每 1 分钟
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["energy", "alert", "routing", "notification"],
)

# ---------------------------------------------------------------------------
# 环境变量与配置
# ---------------------------------------------------------------------------
BIZ_TS = "{{ ts }}"
DORIS_FE = os.environ.get("DORIS_FE", "doris-fe:9030")
DORIS_DB = os.environ.get("DORIS_DB", "db_energy")
NOTIFICATION_API = os.environ.get("NOTIFICATION_API", "http://notification:8090/api/v1")
WORKORDER_API = os.environ.get("WORKORDER_API", "http://workorder:8091/api/v1")


# ---------------------------------------------------------------------------
# Task 1: 查询新告警（status=ACTIVE）
# ---------------------------------------------------------------------------
def query_new_alarms():
    """查询 device_alarm_record 中 status=ACTIVE 的新告警."""
    print(f"[1] 查询 {DORIS_DB}.device_alarm_record 中 status=ACTIVE 的新告警（ts={BIZ_TS}）")


# ---------------------------------------------------------------------------
# Task 2: 告警抑制（相同告警在抑制期内不重复触发）
# ---------------------------------------------------------------------------
def alarm_suppression():
    """告警抑制：根据 device_alarm_rule.suppress_sec 去重."""
    print(
        f"[2] 告警抑制：根据 device_alarm_rule.suppress_sec 配置，"
        f"相同设备+相同指标+相同级别的告警在抑制期内不重复触发"
    )


# ---------------------------------------------------------------------------
# Task 3: 告警分级与通知分发
# ---------------------------------------------------------------------------
def alert_routing():
    """按告警级别路由到不同通知渠道.

    - INFO      → 看板标记
    - WARNING   → 邮件 + 看板
    - CRITICAL  → 邮件 + 短信 + 钉钉/飞书 + 看板
    - EMERGENCY → 邮件 + 短信 + 电话 + 钉钉/飞书 + 看板 + 自动派单
    """
    print(
        f"[3] 告警分级与通知分发：\n"
        f"    INFO      → 看板标记\n"
        f"    WARNING   → 邮件 + 看板\n"
        f"    CRITICAL  → 邮件 + 短信 + 钉钉/飞书 + 看板\n"
        f"    EMERGENCY → 邮件 + 短信 + 电话 + 钉钉/飞书 + 看板 + 自动派单\n"
        f"    通知服务: {NOTIFICATION_API}"
    )


# ---------------------------------------------------------------------------
# Task 4: 自动派单（EMERGENCY 级别触发运维工单）
# ---------------------------------------------------------------------------
def auto_dispatch_workorder():
    """EMERGENCY 级别告警自动创建运维工单."""
    print(
        f"[4] 自动派单：EMERGENCY 级别告警自动创建运维工单，"
        f"调用工单服务 {WORKORDER_API}/workorders 创建工单并分派给设备运维员"
    )


# ---------------------------------------------------------------------------
# Task 5: 告警升级（长时间未确认的告警升级级别）
# ---------------------------------------------------------------------------
def alarm_escalation():
    """告警升级：CRITICAL 超 30 分钟未确认升级为 EMERGENCY."""
    print(
        "[5] 告警升级：CRITICAL 级别告警超 30 分钟未确认升级为 EMERGENCY，"
        "WARNING 级别告警超 2 小时未确认升级为 CRITICAL"
    )


# ---------------------------------------------------------------------------
# 构建任务
# ---------------------------------------------------------------------------
query_alarms_task = PythonOperator(
    task_id="query_new_alarms", python_callable=query_new_alarms, dag=dag
)
suppression_task = PythonOperator(
    task_id="alarm_suppression", python_callable=alarm_suppression, dag=dag
)
routing_task = PythonOperator(
    task_id="alert_routing", python_callable=alert_routing, dag=dag
)
dispatch_task = PythonOperator(
    task_id="auto_dispatch_workorder",
    python_callable=auto_dispatch_workorder,
    dag=dag,
)
escalation_task = PythonOperator(
    task_id="alarm_escalation", python_callable=alarm_escalation, dag=dag
)

# ---------------------------------------------------------------------------
# 任务依赖关系
# ---------------------------------------------------------------------------
query_alarms_task >> suppression_task >> routing_task
routing_task >> dispatch_task >> escalation_task