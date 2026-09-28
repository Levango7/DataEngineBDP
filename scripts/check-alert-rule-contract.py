#!/usr/bin/env python3
"""校验告警规则的"租户模板复用"产品契约（秒级门禁）。

为什么需要：契约本体由 tests/integration/docker/test_grafana_dual_view.py 的
TestRuleTemplateReuse 断言，但那要等 12 分钟的集成测试腿才暴露。本脚本把同样的断言
前移到 CI 的 standards 阶段，任何一条被破坏都在几秒内报错。

同时固化一条此前无人守护的不变量：rules/pending/ 里的规则名不得与生效规则重名
—— 历史上租户作业规则与生效规则同名，启用后会在 Alertmanager 里互相覆盖。

修改本脚本里的契约时，必须同步修改该测试文件。
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys

REPO_ROOT = Path(__file__).resolve().parent.parent
RULES_DIR = REPO_ROOT / "platform" / "observability" / "rules"

VAR_RE = re.compile(r"\{\{([a-z_][a-z0-9_]*)\}\}")

# 各档：最少规则数 + expr 中必须出现的模板变量（键为文件名，值为该文件内的断言）
CONTRACT: dict[str, dict] = {
    "p0-rules.yaml": {"severity": "P0", "min_rules": 5, "required_vars": ["tenant_id"]},
    "p1-rules.yaml": {
        "severity": "P1",
        "min_rules": 5,
        "required_vars": ["tenant_id", "fail_threshold", "latency_p95"],
    },
    "p2-rules.yaml": {
        "severity": "P2",
        "min_rules": 5,
        "required_vars": ["slow_query_threshold"],
    },
}

# 生效规则里必须存在的具体规则名（测试用 next() 取它们，缺失即 StopIteration）
REQUIRED_ALERTS: dict[str, list[str]] = {
    "p1-rules.yaml": ["TenantJobFailureRateHigh"],
}


def load_yaml(path: Path):
    try:
        import yaml  # noqa: PLC0415
    except ImportError:
        print("ERROR: 需要 PyYAML 才能校验规则契约（pip install pyyaml）", file=sys.stderr)
        raise SystemExit(2) from None
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def rules_of(doc) -> list[dict]:
    return [r for g in (doc or {}).get("groups", []) or [] for r in g.get("rules", []) or []]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--rules-dir", type=Path, default=RULES_DIR)
    args = ap.parse_args()

    if not args.rules_dir.is_dir():
        print(f"ERROR: 规则目录不存在: {args.rules_dir}", file=sys.stderr)
        return 2

    errors: list[str] = []
    live_alert_names: dict[str, str] = {}  # alert 名 → 所在文件

    for fname, spec in CONTRACT.items():
        path = args.rules_dir / fname
        if not path.is_file():
            errors.append(f"{fname}: 文件不存在")
            continue
        rules = rules_of(load_yaml(path))
        n = len(rules)
        if n < spec["min_rules"]:
            errors.append(f"{fname}: 仅 {n} 条规则，契约要求 ≥{spec['min_rules']}")

        for rule in rules:
            name = rule.get("alert", "<无名>")
            sev = (rule.get("labels") or {}).get("severity")
            if sev != spec["severity"]:
                errors.append(f"{fname}/{name}: severity={sev!r}，应为 {spec['severity']!r}")
            if name in live_alert_names:
                errors.append(
                    f"{fname}/{name}: 与 {live_alert_names[name]} 中的生效规则重名"
                    "（Alertmanager 按 alertname 聚合，会互相覆盖）"
                )
            live_alert_names.setdefault(name, fname)

        body = "\n".join(str(rule.get("expr", "")) for rule in rules)
        present = set(VAR_RE.findall(body))
        for var in spec["required_vars"]:
            if var not in present:
                errors.append(f"{fname}: 无任一 expr 含 {{{{{var}}}}} —— 租户自定义阈值/维度的契约已断")

        for alert in REQUIRED_ALERTS.get(fname, []):
            if alert not in {r.get("alert") for r in rules}:
                errors.append(f"{fname}: 缺少契约要求的规则 {alert}")

        # 模板替换冒烟：契约变量全部替换后不应残留未替换变量（测试即如此断言）
        for rule in rules:
            expr = str(rule.get("expr", ""))
            if "tenant_id" not in VAR_RE.findall(expr) and "fail_threshold" not in VAR_RE.findall(expr):
                continue
            substituted = expr.replace("{{tenant_id}}", "acme-corp").replace("{{fail_threshold}}", "0.05")
            leftover = sorted(
                set(VAR_RE.findall(substituted))
                - {"latency_p95", "disk_threshold", "mem_threshold", "slow_query_threshold", "threshold"}
            )
            if leftover:
                errors.append(
                    f"{fname}/{rule.get('alert')}: 租户侧只替换 tenant_id/fail_threshold，"
                    f"仍残留 {leftover} → 该租户规则不会触发"
                )

    pending = args.rules_dir / "pending"
    if pending.is_dir():
        for pfile in sorted(pending.glob("*.yaml")):
            for rule in rules_of(load_yaml(pfile)):
                name = rule.get("alert")
                if name in live_alert_names:
                    errors.append(
                        f"pending/{pfile.name}/{name}: 与生效规则 {live_alert_names[name]} 重名，"
                        "启用后会在 Alertmanager 里互相覆盖 —— 请改名或删重"
                    )

    if errors:
        print(f"FAIL: 告警规则契约校验未过（{len(errors)} 项）", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        print(
            "\n契约来源：tests/integration/docker/test_grafana_dual_view.py::TestRuleTemplateReuse\n"
            "新增规则请只引用 metric-contract.yaml 中 available 的指标；"
            "占位符须由 scripts/render-tenant-alert-rules.py 渲染后才进 Prometheus。",
            file=sys.stderr,
        )
        return 1

    total = sum(len(rules_of(load_yaml(args.rules_dir / f))) for f in CONTRACT)
    print(f"OK: 3 档规则 / {total} 条，租户模板契约与命名唯一性均成立")
    return 0


if __name__ == "__main__":
    sys.exit(main())
