"""E2E 测试报告生成器（HTML + JSON 双格式）。

功能：
- 解析 pytest 的 junit-xml 结果文件，提取测试用例列表、通过/失败状态、耗时；
- 结合测试源码中的 ``@pytest.mark.requirement`` 与 ``@pytest.mark.cross_domain``
  标记，标注每个用例覆盖的需求与所属跨领域场景；
- 生成 HTML 报告（含汇总卡片、需求覆盖矩阵、用例明细表）与 JSON 报告；
- 支持命令行调用：``python e2e_report.py --junit report.xml --output e2e_report.html``

依赖：
- Python 3.11+ 标准库（xml.etree.ElementTree / json / argparse / html / datetime）
- 无需第三方包，便于在 CI 环境直接运行。
"""

from __future__ import annotations

import argparse
import html as html_lib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field, asdict
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional


# ---------------------------------------------------------------------------
# 数据模型
# ---------------------------------------------------------------------------
@dataclass
class TestCaseRecord:
    """单个测试用例的记录。"""

    name: str
    classname: str
    status: str  # passed / failed / skipped / error
    duration: float = 0.0
    requirement: Optional[str] = None
    cross_domain: bool = False
    priority: Optional[str] = None  # P0 / P1 / P2
    message: Optional[str] = None
    file: Optional[str] = None
    line: Optional[int] = None


@dataclass
class TestReport:
    """E2E 测试报告汇总。"""

    generated_at: str
    total: int = 0
    passed: int = 0
    failed: int = 0
    skipped: int = 0
    errors: int = 0
    duration: float = 0.0
    cases: List[TestCaseRecord] = field(default_factory=list)
    requirement_coverage: Dict[str, Dict[str, int]] = field(default_factory=dict)
    cross_domain_summary: Dict[str, int] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# junit-xml 解析
# ---------------------------------------------------------------------------
def parse_junit_xml(junit_path: str) -> List[TestCaseRecord]:
    """解析 pytest junit-xml 文件，返回测试用例记录列表。

    Args:
        junit_path: junit-xml 文件路径。

    Returns:
        TestCaseRecord 列表。
    """
    tree = ET.parse(junit_path)
    root = tree.getroot()
    records: List[TestCaseRecord] = []

    for testcase in root.iter("testcase"):
        name = testcase.get("name", "")
        classname = testcase.get("classname", "")
        time_str = testcase.get("time", "0")
        try:
            duration = float(time_str)
        except ValueError:
            duration = 0.0

        # 判断状态：子节点决定 passed/failed/skipped/error
        status = "passed"
        message: Optional[str] = None
        for child in testcase:
            if child.tag == "failure":
                status = "failed"
                message = child.get("message")
            elif child.tag == "skipped":
                status = "skipped"
                message = child.get("message")
            elif child.tag == "error":
                status = "error"
                message = child.get("message")

        # 从 name 中提取 priority（如 test_req_xxx 标记无法直接从 junit 获取，
        # 这里通过 classname + name 启发式推断）
        priority = _infer_priority(classname, name)
        requirement = _infer_requirement(name)
        cross_domain = "cross_domain" in name or "test_e2e_cross_domain" in classname

        records.append(
            TestCaseRecord(
                name=name,
                classname=classname,
                status=status,
                duration=duration,
                requirement=requirement,
                cross_domain=cross_domain,
                priority=priority,
                message=message,
                file=testcase.get("file"),
                line=int(testcase.get("line", 0)) or None,
            )
        )

    return records


def _infer_priority(classname: str, name: str) -> Optional[str]:
    """从测试名启发式推断优先级（P0/P1/P2）。

    约定：test_e2e_all_requirements.py 中 test_req_xxx 命名，
    结合源码标记顺序可推断；此处用名字特征做粗略推断。
    """
    if "test_e2e_all_requirements" in classname:
        # P2 测试名含 data_virtualization / energy_template / government_template
        if any(
            keyword in name
            for keyword in (
                "data_virtualization",
                "energy_template",
                "government_template",
            )
        ):
            return "P2"
        # 其余按命名顺序粗分：前 11 个为 P0，后续为 P1
        p0_names = {
            "test_req_cn_native",
            "test_req_ai_inference",
            "test_req_finetuning",
            "test_req_data_federation",
            "test_req_realtime_warehouse",
            "test_req_industry_template",
            "test_req_security_compliance",
            "test_req_unified_observation",
            "test_req_cost_management",
            "test_req_multi_cluster",
            "test_req_serverless",
        }
        if name in p0_names:
            return "P0"
        return "P1"
    return None


def _infer_requirement(name: str) -> Optional[str]:
    """从测试名推断覆盖的需求（粗略映射）。"""
    mapping = {
        "test_req_cn_native": "P0-1 云原生多租户",
        "test_req_ai_inference": "P0-2 AI推理服务",
        "test_req_finetuning": "P0-3 微调能力",
        "test_req_data_federation": "P0-4 数据联邦",
        "test_req_realtime_warehouse": "P0-5 实时数仓",
        "test_req_industry_template": "P0-6 行业模板",
        "test_req_security_compliance": "P0-7 安全合规",
        "test_req_unified_observation": "P0-8 统一可观测",
        "test_req_cost_management": "P0-9 成本管理",
        "test_req_multi_cluster": "P0-10 多集群管理",
        "test_req_serverless": "P0-11 Serverless",
        "test_req_serverless_runtime": "P1-12 Serverless运行时",
        "test_req_failover": "P1-13 多集群故障迁移",
        "test_req_finops_dashboard": "P1-14 FinOps看板",
        "test_req_model_evaluation": "P1-15 模型评测平台",
        "test_req_federated_query": "P1-16 跨集群查询",
        "test_req_loop": "P1-17 微调闭环",
        "test_req_stream_batch": "P1-18 流批一体",
        "test_req_realtime_governance": "P1-19 实时治理",
        "test_req_manufacturing": "P1-20 制造模板",
        "test_req_retail": "P1-21 零售模板",
        "test_req_asset_exchange": "P1-22 资产流通",
        "test_req_open_api": "P1-23 开放API",
        "test_req_grafana_dual": "P1-24 Grafana双视图",
        "test_req_multimodal": "P1-25 多模态",
        "test_req_data_virtualization": "P2-26 数据虚拟化",
        "test_req_energy_template": "P2-27 能源模板",
        "test_req_government_template": "P2-28 政务模板",
        "test_nl2sql_to_federated_query": "跨领域: NL2SQL→联邦查询",
        "test_federated_query_to_materialized_view": "跨领域: 联邦查询→物化视图",
        "test_materialized_view_to_ai_interpretation": "跨领域: 物化视图→AI解读",
        "test_finetuning_to_evaluation_to_deployment": "跨领域: 微调→评测→部署",
        "test_data_governance_to_quality_check": "跨领域: 治理→质量检查",
        "test_cost_collection_to_finops_dashboard": "跨领域: 成本→FinOps看板",
        "test_multi_cluster_failover_to_query_recovery": "跨领域: 故障迁移→查询恢复",
        "test_stream_batch_unified_scheduling": "跨领域: 流批一体调度",
        "test_asset_registration_to_exchange": "跨领域: 资产注册→交易",
        "test_open_api_subscription_to_billing": "跨领域: API订阅→计费",
    }
    return mapping.get(name)


# ---------------------------------------------------------------------------
# 报告汇总
# ---------------------------------------------------------------------------
def build_report(records: List[TestCaseRecord]) -> TestReport:
    """汇总测试记录为 TestReport。"""
    report = TestReport(generated_at=datetime.now().isoformat(timespec="seconds"))
    report.total = len(records)
    for r in records:
        report.duration += r.duration
        if r.status == "passed":
            report.passed += 1
        elif r.status == "failed":
            report.failed += 1
        elif r.status == "skipped":
            report.skipped += 1
        elif r.status == "error":
            report.errors += 1

        # 需求覆盖统计
        if r.requirement:
            cov = report.requirement_coverage.setdefault(
                r.requirement, {"total": 0, "passed": 0, "failed": 0, "skipped": 0}
            )
            cov["total"] += 1
            if r.status in cov:
                cov[r.status] += 1

        # 跨领域统计
        if r.cross_domain:
            report.cross_domain_summary[r.status] = (
                report.cross_domain_summary.get(r.status, 0) + 1
            )

    report.cases = records
    return report


# ---------------------------------------------------------------------------
# HTML 报告生成
# ---------------------------------------------------------------------------
HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<title>E2E 测试报告 - 数擎大数据平台</title>
<style>
  body {{ font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; margin: 0; padding: 24px; background: #f5f7fa; color: #1f2937; }}
  h1 {{ color: #111827; border-bottom: 2px solid #4f46e5; padding-bottom: 8px; }}
  h2 {{ color: #1f2937; margin-top: 32px; }}
  .summary {{ display: grid; grid-template-columns: repeat(5, 1fr); gap: 16px; margin: 16px 0 24px; }}
  .card {{ background: #fff; border-radius: 8px; padding: 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.08); text-align: center; }}
  .card .num {{ font-size: 28px; font-weight: 600; }}
  .card .label {{ color: #6b7280; font-size: 13px; margin-top: 4px; }}
  .card.passed .num {{ color: #10b981; }}
  .card.failed .num {{ color: #ef4444; }}
  .card.skipped .num {{ color: #f59e0b; }}
  .card.errors .num {{ color: #dc2626; }}
  .card.total .num {{ color: #4f46e5; }}
  table {{ width: 100%; border-collapse: collapse; background: #fff; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }}
  th, td {{ padding: 10px 12px; text-align: left; border-bottom: 1px solid #e5e7eb; font-size: 13px; }}
  th {{ background: #f9fafb; color: #374151; font-weight: 600; }}
  tr:hover {{ background: #f9fafb; }}
  .status-passed {{ color: #10b981; font-weight: 600; }}
  .status-failed {{ color: #ef4444; font-weight: 600; }}
  .status-skipped {{ color: #f59e0b; font-weight: 600; }}
  .status-error {{ color: #dc2626; font-weight: 600; }}
  .badge {{ display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: 600; }}
  .badge-p0 {{ background: #fee2e2; color: #991b1b; }}
  .badge-p1 {{ background: #fef3c7; color: #92400e; }}
  .badge-p2 {{ background: #dbeafe; color: #1e40af; }}
  .badge-cross {{ background: #ede9fe; color: #5b21b6; }}
  .meta {{ color: #6b7280; font-size: 13px; margin-bottom: 16px; }}
  .message {{ color: #6b7280; font-family: monospace; font-size: 12px; white-space: pre-wrap; max-width: 480px; overflow: hidden; text-overflow: ellipsis; }}
</style>
</head>
<body>
<h1>E2E 测试报告 - 数擎大数据平台</h1>
<div class="meta">生成时间：{generated_at}　总耗时：{duration:.2f}s</div>
<div class="summary">
  <div class="card total"><div class="num">{total}</div><div class="label">总用例</div></div>
  <div class="card passed"><div class="num">{passed}</div><div class="label">通过</div></div>
  <div class="card failed"><div class="num">{failed}</div><div class="label">失败</div></div>
  <div class="card skipped"><div class="num">{skipped}</div><div class="label">跳过</div></div>
  <div class="card errors"><div class="num">{errors}</div><div class="label">错误</div></div>
</div>

<h2>需求覆盖矩阵</h2>
<table>
<tr><th>需求</th><th>用例数</th><th>通过</th><th>失败</th><th>跳过</th></tr>
{requirement_rows}
</table>

<h2>跨领域场景汇总</h2>
<table>
<tr><th>状态</th><th>数量</th></tr>
{cross_domain_rows}
</table>

<h2>用例明细</h2>
<table>
<tr><th>#</th><th>用例</th><th>状态</th><th>优先级</th><th>需求/场景</th><th>耗时(s)</th><th>消息</th></tr>
{case_rows}
</table>
</body>
</html>
"""


def render_html(report: TestReport) -> str:
    """将 TestReport 渲染为 HTML 字符串。"""
    esc = html_lib.escape

    # 需求覆盖行
    req_rows = []
    for req, cov in sorted(report.requirement_coverage.items()):
        req_rows.append(
            f"<tr><td>{esc(req)}</td><td>{cov['total']}</td>"
            f"<td>{cov.get('passed', 0)}</td>"
            f"<td>{cov.get('failed', 0)}</td>"
            f"<td>{cov.get('skipped', 0)}</td></tr>"
        )
    requirement_rows = "\n".join(req_rows) if req_rows else "<tr><td colspan='5'>无</td></tr>"

    # 跨领域汇总行
    cross_rows = []
    for status, count in sorted(report.cross_domain_summary.items()):
        cross_rows.append(
            f"<tr><td class='status-{status}'>{esc(status)}</td><td>{count}</td></tr>"
        )
    cross_domain_rows = "\n".join(cross_rows) if cross_rows else "<tr><td colspan='2'>无</td></tr>"

    # 用例明细行
    case_rows = []
    for idx, case in enumerate(report.cases, 1):
        priority_badge = ""
        if case.priority:
            cls = {"P0": "badge-p0", "P1": "badge-p1", "P2": "badge-p2"}.get(
                case.priority, "badge-p1"
            )
            priority_badge = f"<span class='badge {cls}'>{case.priority}</span>"
        if case.cross_domain:
            priority_badge += " <span class='badge badge-cross'>跨领域</span>"
        req_text = esc(case.requirement) if case.requirement else "-"
        msg = esc(case.message) if case.message else ""
        case_rows.append(
            f"<tr><td>{idx}</td><td>{esc(case.name)}</td>"
            f"<td class='status-{case.status}'>{case.status}</td>"
            f"<td>{priority_badge}</td><td>{req_text}</td>"
            f"<td>{case.duration:.3f}</td>"
            f"<td class='message'>{msg}</td></tr>"
        )
    case_rows_html = "\n".join(case_rows) if case_rows else "<tr><td colspan='7'>无用例</td></tr>"

    return HTML_TEMPLATE.format(
        generated_at=esc(report.generated_at),
        duration=report.duration,
        total=report.total,
        passed=report.passed,
        failed=report.failed,
        skipped=report.skipped,
        errors=report.errors,
        requirement_rows=requirement_rows,
        cross_domain_rows=cross_domain_rows,
        case_rows=case_rows_html,
    )


# ---------------------------------------------------------------------------
# JSON 报告生成
# ---------------------------------------------------------------------------
def render_json(report: TestReport) -> str:
    """将 TestReport 序列化为 JSON 字符串。"""
    return json.dumps(asdict(report), ensure_ascii=False, indent=2)


# ---------------------------------------------------------------------------
# 命令行入口
# ---------------------------------------------------------------------------
def main(argv: Optional[List[str]] = None) -> int:
    """命令行入口。

    用法：
        python e2e_report.py --junit report.xml --output e2e_report.html [--json report.json]
    """
    parser = argparse.ArgumentParser(description="E2E 测试报告生成器")
    parser.add_argument(
        "--junit",
        default="e2e-junit.xml",
        help="pytest junit-xml 结果文件路径（默认 e2e-junit.xml）",
    )
    parser.add_argument(
        "--output",
        default="e2e_report.html",
        help="HTML 报告输出路径（默认 e2e_report.html）",
    )
    parser.add_argument(
        "--json",
        default=None,
        help="JSON 报告输出路径（可选）",
    )
    args = parser.parse_args(argv)

    if not os.path.exists(args.junit):
        print(f"错误：junit 文件不存在: {args.junit}", file=sys.stderr)
        return 1

    records = parse_junit_xml(args.junit)
    report = build_report(records)

    # 写 HTML
    Path(args.output).write_text(render_html(report), encoding="utf-8")
    print(f"HTML 报告已生成: {args.output}")

    # 写 JSON
    json_path = args.json or str(Path(args.output).with_suffix(".json"))
    Path(json_path).write_text(render_json(report), encoding="utf-8")
    print(f"JSON 报告已生成: {json_path}")

    # 控制台汇总
    print(
        f"\n汇总：总数 {report.total}，通过 {report.passed}，"
        f"失败 {report.failed}，跳过 {report.skipped}，错误 {report.errors}，"
        f"耗时 {report.duration:.2f}s"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())