#!/usr/bin/env python3
"""把 Trivy SARIF 渲染成人能读的漏洞清单（供 CI 日志与 artifact 使用）。

为什么需要：`trivy fs --format sarif --exit-code 1` 阻断了 job，但 SARIF 不进日志，
于是 CI 只留下一行 "Process completed with exit code 1" —— 没人知道要修什么，
只能去 Security tab 猜。而 tab 里的告警是按 analysis key 累积的，历史上会出现
上百条来自已失效 commit 的僵尸告警（本仓 2026-09-25 实测 active 告警分散在 8 个
不同 commit 上），根本不代表当前 HEAD。

本脚本只读已经产出的 SARIF：不重跑扫描（扫描要预热 Maven 缓存、耗时数十秒），
不碰 SARIF 上传（那是 code-scanning 的入口，另有过失败），因此不会引入新的门禁语义。

用法：
  python3 scripts/trivy-sarif-report.py trivy-results.sarif [--markdown out.md]
"""

from __future__ import annotations

import argparse
from collections import defaultdict
import json
import re
import sys

# Trivy 把包信息塞在 result.message.text 里，形如：
#   Package: X / Installed Version: 1.2 / Vulnerability CVE-... / Severity: HIGH / Fixed Version: 1.3
_PKG = re.compile(r"Package:\s*(\S+)")
_INSTALLED = re.compile(r"Installed Version:\s*(\S+)")
_FIXED = re.compile(r"Fixed Version:\s*(\S+)")
_SEVERITY = re.compile(r"Severity:\s*(\S+)")
_VULN = re.compile(r"Vulnerability\s+(\S+)")

# 计数权重：让"最该修的"排在前面，而不是字面量排序。
_SEV_RANK = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3, "UNKNOWN": 4, "RULE": 5}

# SARIF 的 result.level / rule.defaultConfiguration.severity 是 error/warning/note，
# 与 Trivy 的 CRITICAL/HIGH 不是一套量纲 —— 非依赖类结果统一标成 RULE，避免汇总里混类。
_SARIF_LEVELS = {"ERROR", "WARNING", "NOTE"}


def _norm_sev(raw: str) -> str:
    s = (raw or "").strip().upper()
    return "RULE" if s in _SARIF_LEVELS else (s or "UNKNOWN")


def load_results(path: str):
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    for run in doc.get("runs", []):
        tool = run.get("tool", {}).get("driver", {}).get("name", "?")
        rules = {r.get("id"): r for r in run.get("tool", {}).get("driver", {}).get("rules", [])}
        for res in run.get("results", []):
            yield tool, rules, res


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("sarif", nargs="?", default="trivy-results.sarif")
    ap.add_argument("--markdown", help="同时写出 markdown 报告路径")
    ap.add_argument(
        "--fail-on",
        default=None,
        metavar="N",
        help="发现条数 > N 时退出 1（默认不改门禁语义，仅报告）",
    )
    args = ap.parse_args()

    try:
        results = list(load_results(args.sarif))
    except FileNotFoundError:
        print(f"SKIP: 未找到 {args.sarif}（扫描步骤可能未产出结果）")
        return 0
    except json.JSONDecodeError as exc:
        print(f"ERROR: SARIF 解析失败 {args.sarif}: {exc}", file=sys.stderr)
        return 2

    # key = (工具, 包, 已装版本) → {严重级集合, 修复版本集合, 漏洞编号集合}
    agg: dict[tuple, dict] = defaultdict(lambda: {"sev": set(), "fix": set(), "ids": set()})
    for tool, rules, res in results:
        msg = (res.get("message") or {}).get("text", "") or ""
        rid = res.get("ruleId", "")
        pkg = _PKG.search(msg)
        if not pkg:
            # 非依赖类结果（IaC/密钥等）按规则聚合，保留可见性
            rule = rules.get(rid) or {}
            key = (tool, rule.get("name") or rid or "<unknown>", "-")
            sev = rule.get("defaultConfiguration", {}).get("severity", "") or res.get("level", "")
            agg[key]["sev"].add(_norm_sev(sev))
            agg[key]["ids"].add(rid)
            continue
        installed = _INSTALLED.search(msg)
        key = (tool, pkg.group(1), installed.group(1) if installed else "?")
        sev = _SEVERITY.search(msg)
        fix = _FIXED.search(msg)
        vuln = _VULN.search(msg)
        if sev:
            agg[key]["sev"].add(_norm_sev(sev.group(1)))
        if fix:
            agg[key]["fix"].add(fix.group(1).rstrip(","))
        if vuln:
            agg[key]["ids"].add(vuln.group(1))

    if not agg:
        print("OK: SARIF 中无 CRITICAL/HIGH 且上游已发修复版的依赖漏洞")
        return 0

    rows = sorted(
        agg.items(),
        key=lambda kv: (
            min((_SEV_RANK.get(s, 9) for s in kv[1]["sev"]), default=9),
            -len(kv[1]["ids"]),
            kv[0][1],
        ),
    )

    print("=" * 78)
    print(f"Trivy 明细（来自 {args.sarif}）：{len(rows)} 个受影响组件")
    print("=" * 78)
    sev_count: dict[str, int] = defaultdict(int)
    for (tool, pkg, installed), info in rows:
        top = min(info["sev"], key=lambda s: _SEV_RANK.get(s, 9), default="?")
        for s in info["sev"]:
            sev_count[s] += 1
        fixes = ", ".join(sorted(f for f in info["fix"] if f and f != "?")) or "-"
        ids = sorted(info["ids"])
        shown = ", ".join(ids[:3]) + (f" +{len(ids) - 3}" if len(ids) > 3 else "")
        print(f"[{top:8s}] {pkg} @ {installed}")
        print(f"           修复版本: {fixes}")
        print(f"           漏洞({len(ids)}): {shown}   [{tool}]")

    print("-" * 78)
    print("按严重级汇总: " + ", ".join(f"{k}={v}" for k, v in sorted(sev_count.items())))
    print("提示：--ignore-unfixed 已过滤上游无修复版的条目，故此处每一条都存在可执行的升级路径。")

    if args.markdown:
        lines = ["# Trivy 依赖漏洞明细", "", "| 严重级 | 包 | 已装版本 | 修复版本 | 漏洞数 |", "|---|---|---|---|---|"]
        for (tool, pkg, installed), info in rows:
            top = min(info["sev"], key=lambda s: _SEV_RANK.get(s, 9), default="?")
            fixes = ", ".join(sorted(f for f in info["fix"] if f and f != "?")) or "-"
            lines.append(f"| {top} | `{pkg}` | {installed} | {fixes} | {len(info['ids'])} ({tool}) |")
        with open(args.markdown, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")
        print(f"markdown 报告已写出: {args.markdown}")

    if args.fail_on is not None and len(rows) > int(args.fail_on):
        print(f"FAIL: 受影响组件 {len(rows)} 个 > 允许上限 {args.fail_on}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
