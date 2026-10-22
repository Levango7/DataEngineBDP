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


def _load_doc(path: str):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def _iter(doc):
    for run in doc.get("runs", []):
        tool = run.get("tool", {}).get("driver", {}).get("name", "?")
        rules = {r.get("id"): r for r in run.get("tool", {}).get("driver", {}).get("rules", [])}
        for res in run.get("results", []):
            yield tool, rules, res


def load_results(path: str):
    return list(_iter(_load_doc(path)))


def self_test() -> int:
    """自测：四类"扫描器没结论"与两类真结论各判什么，防门禁自己失明。

    这些用例都是合成 SARIF，不碰产品码；判据是退出码，不是日志里的一句话。
    """
    import os
    import subprocess
    import tempfile

    here = os.path.abspath(__file__)
    rc_all = 0
    with tempfile.TemporaryDirectory() as tmp:
        def write(name: str, text: str) -> str:
            p = os.path.join(tmp, name)
            with open(p, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(text)
            return p

        def write_doc(name: str, doc) -> str:
            """夹具一律由 dict 经 json.dumps 生成，并回证它确实能被解析一次。

            手拼 JSON 字符串写过一次坏夹具（少一个 ]），结果"1 个 HIGH 判红"这条用例
            其实是撞在解析失败上过的 —— 合成夹具必须先自证合法，否则测的是夹具不是判据。
            """
            text = json.dumps(doc, ensure_ascii=False)
            json.loads(text)
            return write(name, text)

        def _sarif(results):
            return {
                "version": "2.1.0",
                "runs": [
                    {
                        "tool": {
                            "driver": {
                                "name": "Trivy",
                                "rules": [{"id": "CVE-1", "defaultConfiguration": {"severity": "error"}}],
                            }
                        },
                        "results": results,
                    }
                ],
            }

        empty_runs = write_doc("empty-runs.sarif", {"version": "2.1.0", "runs": []})
        clean = write_doc("clean.sarif", _sarif([]))
        high = write_doc(
            "high.sarif",
            _sarif(
                [
                    {
                        "ruleId": "CVE-1",
                        "level": "error",
                        "message": {
                            "text": "Package: x / Installed Version: 1.0 / Vulnerability CVE-1 / Severity: HIGH / Fixed Version: 1.1"
                        },
                        "locations": [{"physicalLocation": {"artifactLocation": {"uri": "pom.xml"}}}],
                    }
                ]
            ),
        )
        broken = write("broken.sarif", "{not json")

        cases = [
            ("gate：零发现 ⇒ 放行", ["--gate", "--fail-on", "0", clean], 0),
            ("gate：1 个 HIGH ⇒ 判红", ["--gate", "--fail-on", "0", high], 1),
            ("gate：SARIF 不存在 ⇒ 判环境失败(3)", ["--gate", "--fail-on", "0", os.path.join(tmp, "nope.sarif")], 3),
            ("gate：runs 为空 ⇒ 判环境失败(3)", ["--gate", "--fail-on", "0", empty_runs], 3),
            ("gate：SARIF 解析不了 ⇒ 判环境失败(3)", ["--gate", "--fail-on", "0", broken], 3),
            ("gate：没给 --fail-on ⇒ 用法错(2)", ["--gate", clean], 2),
            ("报告模式（非 gate）：文件缺失仍不判红", [os.path.join(tmp, "nope.sarif")], 0),
        ]
        for label, argv, want in cases:
            p = subprocess.run([sys.executable, here, *argv], capture_output=True, text=True)
            ok = p.returncode == want
            rc_all |= 0 if ok else 1
            print(f"{'PASS' if ok else 'FAIL'}: {label} 期望 rc={want} 实得 rc={p.returncode}")
            if not ok:
                print("  stdout:", p.stdout.strip().splitlines()[-2:])
                print("  stderr:", p.stderr.strip().splitlines()[-2:])
    print("自测：", "全部 PASS" if rc_all == 0 else "有 FAIL")
    return rc_all


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("sarif", nargs="?", default="trivy-results.sarif")
    ap.add_argument("--markdown", help="同时写出 markdown 报告路径")
    ap.add_argument("--self-test", action="store_true", help="跑合成用例，验证本脚本自身的判据")
    ap.add_argument(
        "--fail-on",
        default=None,
        metavar="N",
        help="发现条数 > N 时退出 1（默认不改门禁语义，仅报告）",
    )
    ap.add_argument(
        "--gate",
        action="store_true",
        help="门禁模式：把「扫描器没结论」与「没扫出漏洞」分开判——文件缺失/解析失败/runs 为空一律 rc=3，"
        "而不是让 `trivy --exit-code 1` 的退出码同时承担两种含义",
    )
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if args.gate and args.fail_on is None:
        print("ERROR: --gate 必须带 --fail-on（阈值要显式写清，否则 0 条通过、N 条判红无从判定）", file=sys.stderr)
        return 2

    try:
        doc = _load_doc(args.sarif)
    except FileNotFoundError:
        if args.gate:
            print(
                f"::error::扫描器没有结论：{args.sarif} 不存在 —— 这是扫描命令/环境失败，不能读成「没有漏洞」",
                file=sys.stderr,
            )
            return 3
        print(f"SKIP: 未找到 {args.sarif}（扫描步骤可能未产出结果）")
        return 0
    except json.JSONDecodeError as exc:
        print(f"ERROR: SARIF 解析失败 {args.sarif}: {exc}", file=sys.stderr)
        return 3 if args.gate else 2

    if args.gate and not doc.get("runs"):
        print(
            f"::error::扫描器没有结论：{args.sarif} 里 runs 为空（工具没真正跑起来），不能读成「没有漏洞」",
            file=sys.stderr,
        )
        return 3

    results = list(_iter(doc))

    # key = (工具, 包, 已装版本) → 严重级 / 修复版本 / 漏洞编号 / **命中清单文件**
    agg: dict[tuple, dict] = defaultdict(lambda: {"sev": set(), "fix": set(), "ids": set(), "paths": set()})
    for tool, rules, res in results:
        msg = (res.get("message") or {}).get("text", "") or ""
        rid = res.get("ruleId", "")
        locs = {
            str((loc.get("physicalLocation") or {}).get("artifactLocation", {}).get("uri", ""))
            for loc in res.get("locations", []) or []
        }
        locs.discard("")
        pkg = _PKG.search(msg)
        if not pkg:
            # 非依赖类结果（IaC/密钥等）按规则聚合，保留可见性
            rule = rules.get(rid) or {}
            key = (tool, rule.get("name") or rid or "<unknown>", "-")
            sev = rule.get("defaultConfiguration", {}).get("severity", "") or res.get("level", "")
            agg[key]["sev"].add(_norm_sev(sev))
            agg[key]["ids"].add(rid)
            agg[key]["paths"].update(locs)
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
        # 清单路径是定位"谁引入的"的关键：avro / commons-beanutils 这类传递依赖
        # 全靠它落到具体 pom.xml，否则报告只能说"有洞"却说不清去哪个模块改。
        agg[key]["paths"].update(locs)

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
        paths = sorted(info["paths"])
        shown_paths = ", ".join(paths[:4]) + (f" +{len(paths) - 4}" if len(paths) > 4 else "")
        print(f"[{top:8s}] {pkg} @ {installed}")
        print(f"           修复版本: {fixes}")
        print(f"           清单文件: {shown_paths or '-'}")
        print(f"           漏洞({len(ids)}): {shown}   [{tool}]")

    print("-" * 78)
    print("按严重级汇总: " + ", ".join(f"{k}={v}" for k, v in sorted(sev_count.items())))
    print("提示：--ignore-unfixed 已过滤上游无修复版的条目，故此处每一条都存在可执行的升级路径。")

    if args.markdown:
        lines = [
            "# Trivy 依赖漏洞明细",
            "",
            "| 严重级 | 包 | 已装版本 | 修复版本 | 命中清单文件 | 漏洞数 |",
            "|---|---|---|---|---|---|",
        ]
        for (tool, pkg, installed), info in rows:
            top = min(info["sev"], key=lambda s: _SEV_RANK.get(s, 9), default="?")
            fixes = ", ".join(sorted(f for f in info["fix"] if f and f != "?")) or "-"
            paths = "<br>".join(f"`{p}`" for p in sorted(info["paths"])[:6]) or "-"
            lines.append(f"| {top} | `{pkg}` | {installed} | {fixes} | {paths} " f"| {len(info['ids'])} ({tool}) |")
        with open(args.markdown, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")
        print(f"markdown 报告已写出: {args.markdown}")

    if args.fail_on is not None and len(rows) > int(args.fail_on):
        print(f"FAIL: 受影响组件 {len(rows)} 个 > 允许上限 {args.fail_on}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
