#!/usr/bin/env python3
"""告警规则指标覆盖校验（防"死规则"回归）。

背景：本仓曾出现告警规则引用了 8 个从未被任何组件发射的指标（shuqing_job_* 等）
与 2 个不存在的 process_* 指标，Prometheus 能加载、能评估、永不触发 —— 监控看起来
配好了实际是空的。本脚本解析生效规则里的 PromQL，断言每个指标都在
platform/observability/metric-contract.yaml 中登记为 available。

用法：
    python scripts/check-alert-metric-coverage.py            # 校验生效规则
    python scripts/check-alert-metric-coverage.py --verbose  # 打印每个文件解析到的指标

退出码：0=全部已声明；1=存在未声明指标或引用了 pending 指标；2=用法/文件错误。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - 环境缺 PyYAML 时给出明确提示
    print("ERROR: 需要 PyYAML（pip install pyyaml）", file=sys.stderr)
    sys.exit(2)

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RULES_DIR = REPO_ROOT / "platform" / "observability" / "rules"
DEFAULT_CONTRACT = REPO_ROOT / "platform" / "observability" / "metric-contract.yaml"

# PromQL 函数与关键字：不是指标名
PROMQL_FUNCTIONS = {
    "abs", "absent", "absent_over_time", "avg", "avg_over_time", "ceil", "changes",
    "clamp", "clamp_max", "clamp_min", "count", "count_over_time", "count_values",
    "day_of_month", "day_of_week", "day_of_year", "days_in_month", "delta", "deriv",
    "exp", "floor", "histogram_quantile", "holt_winters", "hour", "idelta", "increase",
    "irate", "label_join", "label_replace", "ln", "log2", "log10", "max", "max_over_time",
    "min", "min_over_time", "minute", "month", "predict_linear", "quantile",
    "quantile_over_time", "rate", "resets", "round", "scalar", "sgn", "sort", "sort_desc",
    "sqrt", "stddev", "stddev_over_time", "stdvar", "stdvar_over_time", "sum",
    "sum_over_time", "time", "timestamp", "topk", "bottomk", "vector", "year",
    "avg_over_time", "changes", "timestamp",
}
PROMQL_KEYWORDS = {
    "and", "or", "unless", "bool", "offset", "inf", "nan", "ignoring", "on", "group_left",
    "group_right", "by", "without", "le", "alertname", "alertstate", "job", "instance",
    "severity", "component", "category", "tenant_id", "namespace", "pod", "container",
    "code", "status", "method", "path", "outcome", "area", "id", "engine", "type", "result",
    "cache", "fstype", "mountpoint", "rule_group", "gpu_model", "persistentvolumeclaim",
    "device", "interface", "cpu", "mode", "app", "node", "service", "kubernetes_io_hostname",
    "endpoint", "apiserver", "firing", "pending", "success", "degraded", "heap", "nonheap",
    "SERVER_ERROR", "metrics", "prometheus", "kubelet", "apisix", "cadvisor", "kube_apiserver",
}

# 需整体剥离的子句（括号内是标签名或参数，不是指标）
CLAUSE_RE = re.compile(r"\b(?:by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)")
BRACE_RE = re.compile(r"\{[^}]*\}")
STRING_RE = re.compile(r'"[^"]*"|\'[^\']*\'')
RANGE_RE = re.compile(r"\[[0-9]+[smhdwy]\]")
IDENT_RE = re.compile(r"[a-zA-Z_:][a-zA-Z0-9_:]*")


def extract_metric_names(expr: str) -> set[str]:
    """从单条 PromQL 表达式里提取候选指标名。"""
    text = expr
    text = CLAUSE_RE.sub(" ", text)
    text = BRACE_RE.sub(" ", text)
    text = STRING_RE.sub(" ", text)
    text = RANGE_RE.sub(" ", text)

    names: set[str] = set()
    for token in IDENT_RE.findall(text):
        if token in PROMQL_FUNCTIONS or token in PROMQL_KEYWORDS:
            continue
        # 纯时间单位（y/w/d/h/m/s 组合）不是指标
        if re.fullmatch(r"[0-9]+[smhdwy]?", token):
            continue
        names.add(token)
    return names


def iter_rule_files(rules_dir: Path) -> list[Path]:
    """生效规则文件（不含 pending/ 子目录与说明文件）。"""
    return sorted(p for p in rules_dir.glob("*.yaml") if p.is_file())


def collect_exprs(rule_file: Path) -> list[str]:
    """从规则 YAML 中取出所有 expr 文本（支持块标量）。"""
    try:
        doc = yaml.safe_load(rule_file.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        print(f"ERROR: 解析 YAML 失败 {rule_file}: {exc}", file=sys.stderr)
        sys.exit(2)

    exprs: list[str] = []
    for group in (doc or {}).get("groups", []) or []:
        for rule in group.get("rules", []) or []:
            expr = rule.get("expr")
            if expr:
                exprs.append(str(expr))
    return exprs


def load_contract(contract_path: Path) -> tuple[set[str], dict[str, str]]:
    """返回 (available 指标集合, pending 指标->依赖说明)。"""
    try:
        doc = yaml.safe_load(contract_path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        print(f"ERROR: 解析指标契约失败 {contract_path}: {exc}", file=sys.stderr)
        sys.exit(2)

    available = {entry["name"] for entry in (doc or {}).get("available", []) or []}
    pending = {
        entry["name"]: str(entry.get("requires", ""))
        for entry in (doc or {}).get("pending", []) or []
    }
    if not available:
        print(f"ERROR: 指标契约未声明任何 available 指标: {contract_path}", file=sys.stderr)
        sys.exit(2)
    return available, pending


def main() -> int:
    parser = argparse.ArgumentParser(description="校验告警规则指标是否已在指标契约中声明")
    parser.add_argument("--rules-dir", type=Path, default=DEFAULT_RULES_DIR,
                        help=f"规则目录（默认 {DEFAULT_RULES_DIR}）")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT,
                        help=f"指标契约文件（默认 {DEFAULT_CONTRACT}）")
    parser.add_argument("--verbose", action="store_true", help="打印每个规则文件解析到的指标")
    args = parser.parse_args()

    if not args.rules_dir.is_dir():
        print(f"ERROR: 规则目录不存在: {args.rules_dir}", file=sys.stderr)
        return 2

    available, pending = load_contract(args.contract)
    rule_files = iter_rule_files(args.rules_dir)
    if not rule_files:
        print(f"ERROR: {args.rules_dir} 下没有规则文件", file=sys.stderr)
        return 2

    undeclared: dict[str, set[str]] = {}
    using_pending: dict[str, set[str]] = {}

    for rule_file in rule_files:
        found: set[str] = set()
        for expr in collect_exprs(rule_file):
            found |= extract_metric_names(expr)

        if args.verbose:
            print(f"[parse] {rule_file.name}: {sorted(found)}")

        unknown = {m for m in found if m not in available and m not in pending}
        if unknown:
            undeclared[rule_file.name] = unknown

        pending_used = found & set(pending)
        if pending_used:
            using_pending[rule_file.name] = pending_used

    if not undeclared and not using_pending:
        total = sum(len(collect_exprs(f)) for f in rule_files)
        print(f"OK: {len(rule_files)} 个规则文件 / {total} 条规则，引用的指标均已声明为 available")
        return 0

    for name, metrics in sorted(undeclared.items()):
        print(f"FAIL: {name} 引用了未声明指标: {', '.join(sorted(metrics))}")
        print("      处理：指标真实存在 → 登记到 metric-contract.yaml 的 available；"
              "否则把规则移到 rules/pending/")
    for name, metrics in sorted(using_pending.items()):
        print(f"FAIL: {name} 引用了 pending 指标: {', '.join(sorted(metrics))}")
        print("      处理：pending 指标无数据源，规则必须放在 rules/pending/ 下")
    return 1


if __name__ == "__main__":
    sys.exit(main())
