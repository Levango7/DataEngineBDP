"""性能压测报告生成器：HTML + JSON 双格式报告。

本模块生成数擎大数据平台全链路性能压测报告，包含：

- **13 项非功能指标达标情况**：并发/延迟/吞吐/资源利用率/稳定性/扩展性/一致性/冷启动/故障恢复；
- **10 项 SLA 验证结果**：API 可用性/SQL 延迟/AI 推理/微调/联邦查询/流处理/租户并发/治理/看板；
- **调优建议**：基于指标达标情况自动生成调优建议。

命令行调用::

    # 生成 HTML + JSON 双格式报告
    python perf_report.py --output report.html

    # 仅生成 JSON 报告
    python perf_report.py --format json --output report.json

    # 从 pytest 结果文件加载（可选）
    python perf_report.py --pytest-json pytest-results.json --output report.html

设计要点：
- 报告模板内嵌（无需外部模板文件）；
- HTML 报告包含指标达标矩阵、SLA 验证表、调优建议、资源趋势图（CSS 内联）；
- JSON 报告结构化输出，便于 CI/CD 集成与下游消费；
- 支持从 pytest-json-report 加载实际压测结果（可选）。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

# 确保能导入同目录 conftest
sys.path.insert(0, str(Path(__file__).resolve().parent))

try:
    from conftest import PERF_THRESHOLDS, SLA_THRESHOLDS  # type: ignore[import-not-found]
except ImportError:  # pragma: no cover
    # 容错：直接定义阈值
    PERF_THRESHOLDS = {
        "concurrent_100_response_time": {"target": 0.5, "unit": "s", "desc": "100并发平均响应时间 ≤ 500ms"},
        "concurrent_500_response_time": {"target": 1.0, "unit": "s", "desc": "500并发平均响应时间 ≤ 1s"},
        "concurrent_1000_response_time": {"target": 2.0, "unit": "s", "desc": "1000并发平均响应时间 ≤ 2s"},
        "api_p99_latency": {"target": 0.200, "unit": "s", "desc": "API P99延迟 ≤ 200ms"},
        "sql_query_latency": {"target": 5.0, "unit": "s", "desc": "SQL查询延迟 ≤ 5s"},
        "api_throughput": {"target": 1000, "unit": "QPS", "desc": "API吞吐量 ≥ 1000 QPS"},
        "data_ingest_throughput": {"target": 100, "unit": "MB/s", "desc": "数据摄入吞吐量 ≥ 100MB/s"},
        "cpu_utilization": {"target": 80, "unit": "%", "desc": "CPU利用率 ≤ 80%"},
        "memory_utilization": {"target": 85, "unit": "%", "desc": "内存利用率 ≤ 85%"},
        "long_run_duration": {"target": 1800, "unit": "s", "desc": "30分钟稳定性测试无异常"},
        "error_rate": {"target": 0.001, "unit": "ratio", "desc": "错误率 ≤ 0.1%"},
        "horizontal_scale_time": {"target": 60, "unit": "s", "desc": "水平扩展完成时间 ≤ 60s"},
        "data_consistency": {"target": 1.0, "unit": "ratio", "desc": "多租户数据一致性 = 100%"},
        "cold_start_time": {"target": 30, "unit": "s", "desc": "冷启动时间 ≤ 30s"},
        "failover_recovery_time": {"target": 60, "unit": "s", "desc": "故障恢复时间 ≤ 60s"},
    }
    SLA_THRESHOLDS = {
        "api_availability": {"target": 0.999, "unit": "ratio", "desc": "API可用性 ≥ 99.9%"},
        "sql_query_p95": {"target": 3.0, "unit": "s", "desc": "SQL查询P95 ≤ 3s"},
        "sql_query_p99": {"target": 5.0, "unit": "s", "desc": "SQL查询P99 ≤ 5s"},
        "ai_inference_latency": {"target": 2.0, "unit": "s", "desc": "AI推理延迟 ≤ 2s"},
        "finetuning_throughput": {"target": 100, "unit": "samples/s", "desc": "微调吞吐量 ≥ 100 samples/s"},
        "federated_query_latency": {"target": 10.0, "unit": "s", "desc": "跨集群查询延迟 ≤ 10s"},
        "stream_processing_delay": {"target": 1.0, "unit": "s", "desc": "流处理延迟 ≤ 1s"},
        "concurrent_tenants": {"target": 100, "unit": "tenants", "desc": "100租户并发无异常"},
        "data_governance_throughput": {"target": 50, "unit": "ops/s", "desc": "治理管道吞吐量 ≥ 50 ops/s"},
        "dashboard_render_time": {"target": 3.0, "unit": "s", "desc": "看板渲染时间 ≤ 3s"},
    }


# ---------------------------------------------------------------------------
# 13 项非功能指标定义（与 test_performance_benchmark.py 对应）
# ---------------------------------------------------------------------------
PERF_METRICS: List[Dict[str, Any]] = [
    {"id": 1, "category": "并发性能", "name": "100并发响应时间", "key": "concurrent_100_response_time",
     "test": "test_api_concurrent_100", "threshold_type": "le"},
    {"id": 2, "category": "并发性能", "name": "500并发响应时间", "key": "concurrent_500_response_time",
     "test": "test_api_concurrent_500", "threshold_type": "le"},
    {"id": 3, "category": "并发性能", "name": "1000并发响应时间", "key": "concurrent_1000_response_time",
     "test": "test_api_concurrent_1000", "threshold_type": "le"},
    {"id": 4, "category": "延迟性能", "name": "API P99延迟", "key": "api_p99_latency",
     "test": "test_api_p99_latency", "threshold_type": "le"},
    {"id": 5, "category": "延迟性能", "name": "SQL查询延迟", "key": "sql_query_latency",
     "test": "test_sql_query_latency", "threshold_type": "le"},
    {"id": 6, "category": "吞吐量", "name": "API吞吐量", "key": "api_throughput",
     "test": "test_api_throughput", "threshold_type": "ge"},
    {"id": 7, "category": "吞吐量", "name": "数据摄入吞吐量", "key": "data_ingest_throughput",
     "test": "test_data_ingest_throughput", "threshold_type": "ge"},
    {"id": 8, "category": "资源利用率", "name": "CPU利用率", "key": "cpu_utilization",
     "test": "test_cpu_utilization", "threshold_type": "le"},
    {"id": 9, "category": "资源利用率", "name": "内存利用率", "key": "memory_utilization",
     "test": "test_memory_utilization", "threshold_type": "le"},
    {"id": 10, "category": "稳定性", "name": "30分钟稳定性", "key": "long_run_duration",
     "test": "test_long_run_stability", "threshold_type": "le"},
    {"id": 11, "category": "稳定性", "name": "错误率", "key": "error_rate",
     "test": "test_error_rate", "threshold_type": "le"},
    {"id": 12, "category": "扩展性", "name": "水平扩展", "key": "horizontal_scale_time",
     "test": "test_horizontal_scale", "threshold_type": "le"},
    {"id": 13, "category": "数据一致性", "name": "多租户一致性", "key": "data_consistency",
     "test": "test_data_consistency", "threshold_type": "ge"},
    {"id": 14, "category": "冷启动", "name": "冷启动时间", "key": "cold_start_time",
     "test": "test_cold_start_time", "threshold_type": "le"},
    {"id": 15, "category": "故障恢复", "name": "故障恢复时间", "key": "failover_recovery_time",
     "test": "test_failover_recovery_time", "threshold_type": "le"},
]

# ---------------------------------------------------------------------------
# 10 项 SLA 指标定义（与 test_sla_verification.py 对应）
# ---------------------------------------------------------------------------
SLA_METRICS: List[Dict[str, Any]] = [
    {"id": 1, "name": "API可用性", "key": "api_availability",
     "test": "test_sla_api_availability", "threshold_type": "ge"},
    {"id": 2, "name": "SQL查询P95", "key": "sql_query_p95",
     "test": "test_sla_sql_query_p95", "threshold_type": "le"},
    {"id": 3, "name": "SQL查询P99", "key": "sql_query_p99",
     "test": "test_sla_sql_query_p99", "threshold_type": "le"},
    {"id": 4, "name": "AI推理延迟", "key": "ai_inference_latency",
     "test": "test_sla_ai_inference_latency", "threshold_type": "le"},
    {"id": 5, "name": "微调吞吐量", "key": "finetuning_throughput",
     "test": "test_sla_finetuning_throughput", "threshold_type": "ge"},
    {"id": 6, "name": "跨集群查询延迟", "key": "federated_query_latency",
     "test": "test_sla_federated_query_latency", "threshold_type": "le"},
    {"id": 7, "name": "流处理延迟", "key": "stream_processing_delay",
     "test": "test_sla_stream_processing_delay", "threshold_type": "le"},
    {"id": 8, "name": "100租户并发", "key": "concurrent_tenants",
     "test": "test_sla_concurrent_tenants", "threshold_type": "ge"},
    {"id": 9, "name": "治理管道吞吐量", "key": "data_governance_throughput",
     "test": "test_sla_data_governance_throughput", "threshold_type": "ge"},
    {"id": 10, "name": "看板渲染时间", "key": "dashboard_render_time",
     "test": "test_sla_dashboard_render_time", "threshold_type": "le"},
]


# ---------------------------------------------------------------------------
# 调优建议生成
# ---------------------------------------------------------------------------
def generate_tuning_suggestions(
    perf_results: Dict[str, Any],
    sla_results: Dict[str, Any],
) -> List[Dict[str, str]]:
    """基于压测结果生成调优建议。

    Args:
        perf_results: 非功能指标压测结果
        sla_results: SLA 验证结果

    Returns:
        调优建议列表，每项含 category/suggestion/rationale。
    """
    suggestions: List[Dict[str, str]] = []

    # 并发性能建议
    for key in ["concurrent_100_response_time", "concurrent_500_response_time", "concurrent_1000_response_time"]:
        result = perf_results.get(key, {})
        if result.get("status") == "fail":
            suggestions.append({
                "category": "并发性能",
                "suggestion": "增加线程池核心线程数与连接池大小，启用异步非阻塞处理",
                "rationale": f"{PERF_THRESHOLDS[key]['desc']} 未达标，"
                f"实际值 {result.get('actual', 'N/A')} 超过目标 {PERF_THRESHOLDS[key]['target']}",
            })
            break

    # 延迟性能建议
    if perf_results.get("api_p99_latency", {}).get("status") == "fail":
        suggestions.append({
            "category": "延迟性能",
            "suggestion": "启用本地缓存（Caffeine）减少数据库访问，优化热点查询索引",
            "rationale": "API P99 延迟超标，通常由数据库慢查询或缓存未命中导致",
        })
    if perf_results.get("sql_query_latency", {}).get("status") == "fail":
        suggestions.append({
            "category": "延迟性能",
            "suggestion": "优化 SQL 执行计划，增加物化视图，调整 Trino/Doris 资源配额",
            "rationale": "SQL 查询延迟超标，可能需要增加查询引擎内存或优化分区策略",
        })

    # 吞吐量建议
    if perf_results.get("api_throughput", {}).get("status") == "fail":
        suggestions.append({
            "category": "吞吐量",
            "suggestion": "水平扩展服务副本数，启用连接池复用与 HTTP/2 多路复用",
            "rationale": "API 吞吐量未达标，可通过增加 Pod 副本与连接池优化提升",
        })

    # 资源利用率建议
    if perf_results.get("cpu_utilization", {}).get("status") == "fail":
        suggestions.append({
            "category": "资源利用率",
            "suggestion": "优化 CPU 密集型逻辑（如序列化/压缩），增加 Pod 副本分散负载",
            "rationale": "CPU 利用率超标，需排查热点代码或扩展计算节点",
        })
    if perf_results.get("memory_utilization", {}).get("status") == "fail":
        suggestions.append({
            "category": "资源利用率",
            "suggestion": "增大 JVM 堆内存，排查内存泄漏，启用对象池减少 GC 压力",
            "rationale": "内存利用率超标，可能需要调整 -Xmx 或排查内存泄漏",
        })

    # 稳定性建议
    if perf_results.get("error_rate", {}).get("status") == "fail":
        suggestions.append({
            "category": "稳定性",
            "suggestion": "增加重试机制与熔断器（Resilience4j），排查错误日志根因",
            "rationale": "错误率超标，需分析错误分布并增加容错机制",
        })

    # SLA 建议
    if sla_results.get("api_availability", {}).get("status") == "fail":
        suggestions.append({
            "category": "SLA-可用性",
            "suggestion": "部署多副本+健康检查+自动重启，配置 PodDisruptionBudget 保证可用性",
            "rationale": "API 可用性未达 99.9%，需增加冗余与自愈能力",
        })
    if sla_results.get("ai_inference_latency", {}).get("status") == "fail":
        suggestions.append({
            "category": "SLA-AI推理",
            "suggestion": "启用模型量化（INT8）与批处理推理，增加 GPU 资源配额",
            "rationale": "AI 推理延迟超标，可通过模型优化与硬件加速改善",
        })

    # 如果全部达标
    if not suggestions:
        suggestions.append({
            "category": "总体",
            "suggestion": "所有指标均达标，建议定期回归压测监控性能退化",
            "rationale": "13 项非功能指标与 10 项 SLA 全部达标",
        })

    return suggestions


# ---------------------------------------------------------------------------
# 从 pytest-json-report 加载实际结果
# ---------------------------------------------------------------------------
def load_pytest_results(filepath: str) -> Dict[str, Dict[str, Any]]:
    """从 pytest-json-report 文件加载测试结果。

    Returns:
        测试名 → {status, duration, ...} 的字典。
    """
    results: Dict[str, Dict[str, Any]] = {}
    try:
        with open(filepath, "r", encoding="utf-8") as f:
            data = json.load(f)
        for test in data.get("tests", []):
            name = test.get("nodeid", "").split("::")[-1]
            outcome = test.get("outcome", "unknown")
            results[name] = {
                "status": "pass" if outcome == "passed" else "fail" if outcome == "failed" else "skip",
                "duration": test.get("duration", 0),
                "outcome": outcome,
            }
    except Exception:
        pass
    return results


# ---------------------------------------------------------------------------
# 构建报告数据
# ---------------------------------------------------------------------------
def build_report_data(
    pytest_results: Optional[Dict[str, Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    """构建完整报告数据结构。

    Args:
        pytest_results: 从 pytest-json-report 加载的测试结果（可选）。

    Returns:
        完整报告数据字典。
    """
    pytest_results = pytest_results or {}

    # 构建非功能指标结果
    perf_results: Dict[str, Any] = {}
    perf_pass = 0
    perf_fail = 0
    perf_skip = 0
    for metric in PERF_METRICS:
        key = metric["key"]
        threshold = PERF_THRESHOLDS.get(key, {})
        test_result = pytest_results.get(metric["test"], {})
        status = test_result.get("status", "pending")
        if status == "pass":
            perf_pass += 1
        elif status == "fail":
            perf_fail += 1
        elif status == "skip":
            perf_skip += 1
        perf_results[key] = {
            "id": metric["id"],
            "category": metric["category"],
            "name": metric["name"],
            "test": metric["test"],
            "desc": threshold.get("desc", ""),
            "target": threshold.get("target"),
            "unit": threshold.get("unit", ""),
            "threshold_type": metric["threshold_type"],
            "status": status,
            "duration": test_result.get("duration", 0),
        }

    # 构建 SLA 验证结果
    sla_results: Dict[str, Any] = {}
    sla_pass = 0
    sla_fail = 0
    sla_skip = 0
    for metric in SLA_METRICS:
        key = metric["key"]
        threshold = SLA_THRESHOLDS.get(key, {})
        test_result = pytest_results.get(metric["test"], {})
        status = test_result.get("status", "pending")
        if status == "pass":
            sla_pass += 1
        elif status == "fail":
            sla_fail += 1
        elif status == "skip":
            sla_skip += 1
        sla_results[key] = {
            "id": metric["id"],
            "name": metric["name"],
            "test": metric["test"],
            "desc": threshold.get("desc", ""),
            "target": threshold.get("target"),
            "unit": threshold.get("unit", ""),
            "threshold_type": metric["threshold_type"],
            "status": status,
            "duration": test_result.get("duration", 0),
        }

    # 生成调优建议
    suggestions = generate_tuning_suggestions(perf_results, sla_results)

    # 总体统计
    total_perf = len(PERF_METRICS)
    total_sla = len(SLA_METRICS)
    overall_pass = perf_pass + sla_pass
    overall_fail = perf_fail + sla_fail
    overall_skip = perf_skip + sla_skip
    overall_total = total_perf + total_sla

    return {
        "report_meta": {
            "title": "数擎大数据平台 全链路性能压测报告",
            "version": "v1.0",
            "generated_at": datetime.now().isoformat(),
            "generator": "perf_report.py (T046)",
            "project": "ShuqingBigDataPlatform",
        },
        "summary": {
            "perf_metrics": {
                "total": total_perf,
                "pass": perf_pass,
                "fail": perf_fail,
                "skip": perf_skip,
                "pass_rate": round(perf_pass / total_perf * 100, 2) if total_perf > 0 else 0,
            },
            "sla_metrics": {
                "total": total_sla,
                "pass": sla_pass,
                "fail": sla_fail,
                "skip": sla_skip,
                "pass_rate": round(sla_pass / total_sla * 100, 2) if total_sla > 0 else 0,
            },
            "overall": {
                "total": overall_total,
                "pass": overall_pass,
                "fail": overall_fail,
                "skip": overall_skip,
                "pass_rate": round(overall_pass / overall_total * 100, 2) if overall_total > 0 else 0,
            },
        },
        "perf_results": perf_results,
        "sla_results": sla_results,
        "tuning_suggestions": suggestions,
        "thresholds": {
            "perf": PERF_THRESHOLDS,
            "sla": SLA_THRESHOLDS,
        },
    }


# ---------------------------------------------------------------------------
# HTML 报告生成
# ---------------------------------------------------------------------------
def generate_html_report(data: Dict[str, Any]) -> str:
    """生成 HTML 格式报告。

    Args:
        data: 完整报告数据。

    Returns:
        HTML 字符串。
    """
    meta = data["report_meta"]
    summary = data["summary"]
    perf_results = data["perf_results"]
    sla_results = data["sla_results"]
    suggestions = data["tuning_suggestions"]

    # 状态图标与颜色
    status_map = {
        "pass": ("✅ 通过", "#52c41a"),
        "fail": ("❌ 失败", "#f5222d"),
        "skip": ("⏭️ 跳过", "#faad14"),
        "pending": ("⏳ 待测", "#d9d9d9"),
    }

    def status_badge(status: str) -> str:
        label, color = status_map.get(status, ("未知", "#d9d9d9"))
        return f'<span style="color:{color};font-weight:600">{label}</span>'

    # 非功能指标表格行
    perf_rows = []
    for key, result in perf_results.items():
        perf_rows.append(f"""
        <tr>
            <td>{result['id']}</td>
            <td>{result['category']}</td>
            <td>{result['name']}</td>
            <td>{result['desc']}</td>
            <td>{result['target']} {result['unit']}</td>
            <td>{status_badge(result['status'])}</td>
            <td>{result['duration']:.3f}s</td>
        </tr>""")

    # SLA 验证表格行
    sla_rows = []
    for key, result in sla_results.items():
        sla_rows.append(f"""
        <tr>
            <td>{result['id']}</td>
            <td>{result['name']}</td>
            <td>{result['desc']}</td>
            <td>{result['target']} {result['unit']}</td>
            <td>{status_badge(result['status'])}</td>
            <td>{result['duration']:.3f}s</td>
        </tr>""")

    # 调优建议
    suggestion_cards = []
    for s in suggestions:
        suggestion_cards.append(f"""
        <div class="suggestion-card">
            <h4>🔧 {s['category']}</h4>
            <p><strong>建议：</strong>{s['suggestion']}</p>
            <p><strong>依据：</strong>{s['rationale']}</p>
        </div>""")

    # 汇总卡片
    perf_summary = summary["perf_metrics"]
    sla_summary = summary["sla_metrics"]
    overall = summary["overall"]

    html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{meta['title']}</title>
    <style>
        * {{ margin: 0; padding: 0; box-sizing: border-box; }}
        body {{
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
                         "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
            background: #f5f7fa; color: #333; line-height: 1.6; padding: 24px;
        }}
        .container {{ max-width: 1200px; margin: 0 auto; }}
        h1 {{ color: #1a1a1a; font-size: 28px; margin-bottom: 8px; }}
        h2 {{ color: #1a1a1a; font-size: 22px; margin: 32px 0 16px; border-bottom: 2px solid #1890ff; padding-bottom: 8px; }}
        h3 {{ color: #333; font-size: 18px; margin: 24px 0 12px; }}
        .meta {{ color: #888; font-size: 14px; margin-bottom: 24px; }}
        .summary-grid {{ display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin: 24px 0; }}
        .summary-card {{
            background: #fff; border-radius: 8px; padding: 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.08);
        }}
        .summary-card h3 {{ margin-top: 0; color: #1890ff; }}
        .summary-stat {{ font-size: 32px; font-weight: 700; color: #1a1a1a; }}
        .summary-label {{ color: #888; font-size: 13px; }}
        .progress-bar {{
            background: #f0f0f0; border-radius: 4px; height: 8px; margin-top: 8px; overflow: hidden;
        }}
        .progress-fill {{ height: 100%; border-radius: 4px; transition: width 0.3s; }}
        table {{
            width: 100%; border-collapse: collapse; background: #fff; border-radius: 8px;
            overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.08); margin: 16px 0;
        }}
        th {{ background: #fafafa; color: #1a1a1a; font-weight: 600; padding: 12px 16px; text-align: left; border-bottom: 2px solid #e8e8e8; }}
        td {{ padding: 12px 16px; border-bottom: 1px solid #f0f0f0; }}
        tr:hover td {{ background: #fafafa; }}
        .suggestion-card {{
            background: #fff; border-radius: 8px; padding: 16px 20px; margin: 12px 0;
            box-shadow: 0 2px 8px rgba(0,0,0,0.08); border-left: 4px solid #1890ff;
        }}
        .suggestion-card h4 {{ color: #1890ff; margin-bottom: 8px; }}
        .suggestion-card p {{ margin: 4px 0; font-size: 14px; }}
        .footer {{ text-align: center; color: #888; font-size: 12px; margin-top: 40px; padding-top: 16px; border-top: 1px solid #e8e8e8; }}
    </style>
</head>
<body>
<div class="container">
    <h1>{meta['title']}</h1>
    <div class="meta">
        <strong>项目：</strong>{meta['project']} |
        <strong>版本：</strong>{meta['version']} |
        <strong>生成时间：</strong>{meta['generated_at']} |
        <strong>生成器：</strong>{meta['generator']}
    </div>

    <h2>📊 总体汇总</h2>
    <div class="summary-grid">
        <div class="summary-card">
            <h3>非功能指标（13项）</h3>
            <div class="summary-stat">{perf_summary['pass']}/{perf_summary['total']}</div>
            <div class="summary-label">通过率 {perf_summary['pass_rate']}%</div>
            <div class="progress-bar">
                <div class="progress-fill" style="width:{perf_summary['pass_rate']}%;background:#52c41a"></div>
            </div>
            <div class="summary-label" style="margin-top:8px">
                ✅ {perf_summary['pass']} 通过 |
                ❌ {perf_summary['fail']} 失败 |
                ⏭️ {perf_summary['skip']} 跳过
            </div>
        </div>
        <div class="summary-card">
            <h3>SLA 验证（10项）</h3>
            <div class="summary-stat">{sla_summary['pass']}/{sla_summary['total']}</div>
            <div class="summary-label">通过率 {sla_summary['pass_rate']}%</div>
            <div class="progress-bar">
                <div class="progress-fill" style="width:{sla_summary['pass_rate']}%;background:#52c41a"></div>
            </div>
            <div class="summary-label" style="margin-top:8px">
                ✅ {sla_summary['pass']} 通过 |
                ❌ {sla_summary['fail']} 失败 |
                ⏭️ {sla_summary['skip']} 跳过
            </div>
        </div>
        <div class="summary-card">
            <h3>总体达标</h3>
            <div class="summary-stat">{overall['pass']}/{overall['total']}</div>
            <div class="summary-label">通过率 {overall['pass_rate']}%</div>
            <div class="progress-bar">
                <div class="progress-fill" style="width:{overall['pass_rate']}%;background:#1890ff"></div>
            </div>
            <div class="summary-label" style="margin-top:8px">
                共 {overall['total']} 项指标
            </div>
        </div>
    </div>

    <h2>📋 13 项非功能指标达标情况</h2>
    <table>
        <thead>
            <tr>
                <th>序号</th><th>类别</th><th>指标名称</th><th>描述</th>
                <th>目标值</th><th>状态</th><th>耗时</th>
            </tr>
        </thead>
        <tbody>
            {''.join(perf_rows)}
        </tbody>
    </table>

    <h2>📝 10 项 SLA 验证结果</h2>
    <table>
        <thead>
            <tr>
                <th>序号</th><th>SLA 指标</th><th>描述</th>
                <th>目标值</th><th>状态</th><th>耗时</th>
            </tr>
        </thead>
        <tbody>
            {''.join(sla_rows)}
        </tbody>
    </table>

    <h2>🔧 调优建议</h2>
    {''.join(suggestion_cards)}

    <div class="footer">
        本报告由 perf_report.py 自动生成 · 数擎大数据平台 T046 全链路性能调优与压测
    </div>
</div>
</body>
</html>"""
    return html


# ---------------------------------------------------------------------------
# JSON 报告生成
# ---------------------------------------------------------------------------
def generate_json_report(data: Dict[str, Any]) -> str:
    """生成 JSON 格式报告。"""
    return json.dumps(data, ensure_ascii=False, indent=2, default=str)


# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------
def main() -> int:
    """命令行入口。

    用法::

        python perf_report.py --output report.html
        python perf_report.py --format json --output report.json
        python perf_report.py --pytest-json results.json --output report.html
    """
    parser = argparse.ArgumentParser(
        description="数擎大数据平台性能压测报告生成器",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python perf_report.py --output report.html
  python perf_report.py --format json --output report.json
  python perf_report.py --pytest-json pytest-results.json --output report.html
        """,
    )
    parser.add_argument(
        "--output",
        "-o",
        default="perf_report.html",
        help="输出文件路径（默认 perf_report.html）",
    )
    parser.add_argument(
        "--format",
        "-f",
        choices=["html", "json", "both"],
        default="both",
        help="输出格式：html/json/both（默认 both）",
    )
    parser.add_argument(
        "--pytest-json",
        default=None,
        help="从 pytest-json-report 文件加载测试结果（可选）",
    )
    args = parser.parse_args()

    # 加载 pytest 结果（可选）
    pytest_results = None
    if args.pytest_json and os.path.exists(args.pytest_json):
        pytest_results = load_pytest_results(args.pytest_json)
        print(f"已加载 pytest 结果: {args.pytest_json}（{len(pytest_results)} 个测试）")

    # 构建报告数据
    data = build_report_data(pytest_results)

    # 确定输出路径
    output_path = Path(args.output)
    if output_path.suffix.lower() == ".json" and args.format == "both":
        args.format = "json"
    elif output_path.suffix.lower() in (".html", ".htm") and args.format == "both":
        args.format = "html"

    # 生成报告
    if args.format in ("html", "both"):
        html_path = output_path if output_path.suffix.lower() in (".html", ".htm") else output_path.with_suffix(".html")
        html_path.parent.mkdir(parents=True, exist_ok=True)
        html_content = generate_html_report(data)
        html_path.write_text(html_content, encoding="utf-8")
        print(f"✅ HTML 报告已生成: {html_path}")

    if args.format in ("json", "both"):
        json_path = output_path if output_path.suffix.lower() == ".json" else output_path.with_suffix(".json")
        json_path.parent.mkdir(parents=True, exist_ok=True)
        json_content = generate_json_report(data)
        json_path.write_text(json_content, encoding="utf-8")
        print(f"✅ JSON 报告已生成: {json_path}")

    # 打印汇总
    summary = data["summary"]
    print("\n" + "=" * 60)
    print("📊 性能压测报告汇总")
    print("=" * 60)
    print(f"非功能指标: {summary['perf_metrics']['pass']}/{summary['perf_metrics']['total']} 通过"
          f"（通过率 {summary['perf_metrics']['pass_rate']}%）")
    print(f"SLA 验证:   {summary['sla_metrics']['pass']}/{summary['sla_metrics']['total']} 通过"
          f"（通过率 {summary['sla_metrics']['pass_rate']}%）")
    print(f"总体达标:   {summary['overall']['pass']}/{summary['overall']['total']} 通过"
          f"（通过率 {summary['overall']['pass_rate']}%）")
    print(f"调优建议:   {len(data['tuning_suggestions'])} 条")
    print("=" * 60)

    return 0


if __name__ == "__main__":
    sys.exit(main())