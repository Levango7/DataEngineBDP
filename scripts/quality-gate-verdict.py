#!/usr/bin/env python3
"""Quality Gate 聚合判据：把前置工作流结论映射为聚合结论（单一事实来源）。

背景：`.github/workflows/quality-gate.yml` 的聚合器此前把 `cancelled`（未出结论）
与 `failure`（已出结论且失败）混为一谈，两处由此产生实锤缺陷：

1. 取数异常（`catch` 分支写入 `conclusion: 'error'`）既不算失败也不算未完成，
   于是「一条都没取到」也可能被判成 success——违反该工作流自己在文件头写的
   「不允许在证据不全时自称 success」原则（台账 #103）。
2. `cancelled` 被计入「失败工作流数」，PR 面板上出现一条与代码无关的红，
   处置动作却应是「重跑」而不是「改代码」（台账 #99 选项③④）。

本脚本只做判定，不取数、不盖章；工作流负责取数与呈现。

用法：
    python3 scripts/quality-gate-verdict.py --self-test
    python3 scripts/quality-gate-verdict.py --input results.json --evaluation eval.json
    cat results.json | python3 scripts/quality-gate-verdict.py --stdin

    # CI 推荐走环境变量（无引号转义风险）：
    QUALITY_GATE_RESULTS='{"CI":{...}}' QUALITY_GATE_EVALUATION='{...}' \
        python3 scripts/quality-gate-verdict.py >> "$GITHUB_OUTPUT"

输出：默认输出 `key=value` 形式的 GitHub Actions step output；
加 `--format json` 输出 JSON（便于本地排查）。

判据（自上而下，先命中者生效）：
    1. 评估对象已被后续提交取代（stale）      -> neutral（结论作废，不冒充红也不冒充绿）
    2. 存在 failure / timed_out / action_required -> failure
    3. 存在 cancelled（未被判 stale）          -> failure（保守：闸门不放松）
    4. 存在取数异常 / 未知结论 / 未完成         -> neutral（证据不全不自称通过）
    5. 其余（success / neutral / skipped / not_found）-> success
    6. 一条前置都没取到                         -> neutral（无证据不冒充通过）

陈旧隔离（stale）只在下述条件全部成立时生效，否则一律按保守档处理：
    - evaluation.staleness_known == true（评估方明确说「我知道该提交是不是最新」）
    - evaluation.expected_head_sha 与 evaluation.current_head_sha 均非空且不相等
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any, Dict, List

# 结论分类：阻断性失败（已出结论且失败）
BLOCKING_CONCLUSIONS = frozenset({"failure", "timed_out", "action_required"})
# 视为「没有跑起来 / 不阻断」的前置结论
OK_CONCLUSIONS = frozenset({"success", "neutral", "skipped", "not_found"})
# 取数异常：本脚本产出，由工作流的 catch 分支写入
ERROR_CONCLUSION = "error"
CANCELLED_CONCLUSION = "cancelled"


def _is_pending(info: Dict[str, Any]) -> bool:
    """前置工作流在该 commit 上尚未出结论。

    `absent` 表示该 commit 上根本没有这条工作流的运行记录（或取数失败），
    不属于「还在跑」，由结论字段自身定性（not_found 不阻断 / error 证据不全）。
    """
    status = (info.get("status") or "").strip()
    if not status:
        return False
    if status in ("completed", "absent"):
        return False
    return True


def is_stale(evaluation: Dict[str, Any] | None) -> bool:
    """评估对象是否已被后续提交取代（结论对该 commit 已失去意义）。"""
    if not evaluation:
        return False
    if not evaluation.get("staleness_known"):
        return False
    expected = evaluation.get("expected_head_sha")
    current = evaluation.get("current_head_sha")
    if not expected or not current:
        return False
    return expected != current


def classify(info: Dict[str, Any]) -> str:
    """把单条前置工作流结论归入本脚本的分类。"""
    conclusion = (info.get("conclusion") or "").strip()
    status = (info.get("status") or "").strip()

    if _is_pending(info) and conclusion not in BLOCKING_CONCLUSIONS:
        # status != completed：还没跑完，不按结论定性
        return "pending"
    if conclusion == ERROR_CONCLUSION:
        return "unknown"
    if conclusion in BLOCKING_CONCLUSIONS:
        return "failed"
    if conclusion == CANCELLED_CONCLUSION:
        return "cancelled"
    if conclusion in OK_CONCLUSIONS:
        return "ok"
    # 结论字段缺失或出现未知取值：按证据不全处理，不猜
    return "unknown"


def verdict(results: Dict[str, Any] | None, evaluation: Dict[str, Any] | None = None) -> Dict[str, Any]:
    """给出聚合结论。返回结构对工作流与自测共用。"""
    results = results or {}
    stale = is_stale(evaluation)

    buckets: Dict[str, List[str]] = {
        "failed": [],
        "cancelled": [],
        "pending": [],
        "unknown": [],
        "ok": [],
    }
    details: Dict[str, str] = {}
    for name, info in sorted(results.items()):
        info = info or {}
        bucket = classify(info)
        buckets[bucket].append(name)
        details[name] = bucket

    failed_count = len(buckets["failed"])
    cancelled_count = len(buckets["cancelled"])
    pending_count = len(buckets["pending"])
    unknown_count = len(buckets["unknown"])
    # fail_count 保持旧语义（阻断性结论个数）：failed + cancelled。
    # 评估对象陈旧时，这些结论对当前分支头已无意义，只记为作废、不计阻断。
    blocking = failed_count + cancelled_count
    fail_count = 0 if stale else blocking
    stale_count = blocking if stale else 0

    reasons: List[str] = []
    if stale:
        overall = "neutral"
        reasons.append(f"评估对象已被后续提交取代，本次结论作废（{stale_count} 条结论不再有效）")
    elif failed_count or cancelled_count:
        overall = "failure"
        if failed_count:
            reasons.append("失败：" + "、".join(buckets["failed"]))
        if cancelled_count:
            reasons.append("取消（未出结论，处置是重跑）：" + "、".join(buckets["cancelled"]))
    elif pending_count or unknown_count:
        overall = "neutral"
        if pending_count:
            reasons.append("未完成：" + "、".join(buckets["pending"]))
        if unknown_count:
            reasons.append("取数异常/未知结论：" + "、".join(buckets["unknown"]))
    elif not results:
        overall = "neutral"
        reasons.append("一条前置都没取到，证据不全不冒充通过")
    else:
        overall = "success"

    return {
        "overall": overall,
        "failed_count": failed_count,
        "cancelled_count": cancelled_count,
        "stale_count": stale_count,
        "pending_count": pending_count,
        "unknown_count": unknown_count,
        "fail_count": fail_count,
        "stale": stale,
        "reasons": "；".join(reasons) if reasons else "全部前置工作流通过",
        "buckets": buckets,
        "details": details,
        "head_sha": (evaluation or {}).get("head_sha") or "",
    }


def _self_test() -> int:
    """判据自测：每条用例都是一次可复现的判据断言。"""
    ok = {"conclusion": "success", "status": "completed"}
    failed = {"conclusion": "failure", "status": "completed"}
    timed_out = {"conclusion": "timed_out", "status": "completed"}
    cancelled = {"conclusion": "cancelled", "status": "completed"}
    pending = {"conclusion": "", "status": "in_progress"}
    errored = {"conclusion": "error", "status": "absent"}
    not_found = {"conclusion": "not_found", "status": "absent"}

    fresh = {"staleness_known": True, "expected_head_sha": "aaa", "current_head_sha": "aaa"}
    stale = {"staleness_known": True, "expected_head_sha": "aaa", "current_head_sha": "bbb"}
    unknown_staleness = {"staleness_known": False, "expected_head_sha": "aaa", "current_head_sha": "bbb"}

    # 用例：名称 / 前置结果 / 评估上下文 / 期望 overall / 期望 fail_count / 期望 stale_count / 期望 stale 标记
    cases = [
        ("全 success -> success",
         {"CI": ok, "Security": ok}, fresh, "success", 0, 0, False),
        ("failure -> failure 且计失败",
         {"CI": failed, "Security": ok}, fresh, "failure", 1, 0, False),
        ("timed_out -> failure",
         {"Security": timed_out}, fresh, "failure", 1, 0, False),
        ("cancelled（非陈旧）-> failure，处置是重跑不是改代码",
         {"CI": cancelled}, fresh, "failure", 1, 0, False),
        ("cancelled（陈旧）-> 不阻断，整体 neutral，计入作废",
         {"CI": cancelled}, stale, "neutral", 0, 1, True),
        ("取数异常 -> neutral，不许盖绿章",
         {"CI": errored, "Security": ok}, fresh, "neutral", 0, 0, False),
        ("未完成 -> neutral",
         {"CI": pending}, fresh, "neutral", 0, 0, False),
        ("not_found 不阻断 -> success",
         {"CI": ok, "Security": not_found}, fresh, "success", 0, 0, False),
        ("陈旧评估对象 + 全 success -> neutral（不替过期提交盖章）",
         {"CI": ok}, stale, "neutral", 0, 0, True),
        ("陈旧评估对象 + 真 failure -> neutral（结论作废，不计阻断）",
         {"CI": failed}, stale, "neutral", 0, 1, True),
        ("陈旧不可判定 -> 按保守档（cancelled 仍阻断）",
         {"CI": cancelled}, unknown_staleness, "failure", 1, 0, False),
        ("一条前置都没取到 -> neutral",
         {}, fresh, "neutral", 0, 0, False),
        ("结论字段缺失 -> neutral",
         {"CI": {"status": "completed"}}, fresh, "neutral", 0, 0, False),
        ("failure 与 cancelled 并存 -> 两条都计阻断",
         {"CI": failed, "Security": cancelled}, fresh, "failure", 2, 0, False),
    ]

    passed = 0
    failed_cases: List[str] = []
    for name, results, evaluation, want_overall, want_fail, want_stale, want_flag in cases:
        got = verdict(results, evaluation)
        problems = []
        if got["overall"] != want_overall:
            problems.append(f"overall={got['overall']} 期望 {want_overall}")
        if got["fail_count"] != want_fail:
            problems.append(f"fail_count={got['fail_count']} 期望 {want_fail}")
        if got["stale_count"] != want_stale:
            problems.append(f"stale_count={got['stale_count']} 期望 {want_stale}")
        if got["stale"] != want_flag:
            problems.append(f"stale={got['stale']} 期望 {want_flag}")
        if problems:
            failed_cases.append(f"{name}: " + "；".join(problems))
        else:
            passed += 1

    total = len(cases)
    print(f"self-test: {passed} PASS / {total - passed} FAIL")
    for line in failed_cases:
        print(f"FAIL {line}")
    return 0 if passed == total else 1


def _parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Quality Gate 聚合判据")
    parser.add_argument("--self-test", action="store_true", help="运行判据自测")
    parser.add_argument("--stdin", action="store_true", help="从 stdin 读前置工作流结果 JSON")
    parser.add_argument("--input", help="从前置工作流结果 JSON 文件读入")
    parser.add_argument("--evaluation", help="评估上下文 JSON（head_sha/event/head_branch/expected_head_sha/current_head_sha/staleness_known）")
    parser.add_argument("--format", choices=["outputs", "json"], default="outputs", help="输出格式")
    return parser.parse_args(argv)


def main(argv: List[str]) -> int:
    args = _parse_args(argv)
    if args.self_test:
        return _self_test()

    # 优先用显式参数；否则回落到环境变量（CI 与 PowerShell 都免引号转义问题）
    raw = os.environ.get("QUALITY_GATE_RESULTS", "")
    if args.input:
        with open(args.input, "r", encoding="utf-8-sig") as fh:
            raw = fh.read()
    elif args.stdin:
        raw = sys.stdin.read()
    elif not raw and not sys.stdin.isatty():
        raw = sys.stdin.read()
    raw = raw.lstrip("\ufeff").strip()  # 容忍 BOM（Windows 管道/重定向会带 BOM）
    if not raw:
        print("::error::未读到前置工作流结果 JSON（QUALITY_GATE_RESULTS / --input / --stdin 三者取一）", file=sys.stderr)
        return 2
    try:
        results = json.loads(raw)
    except json.JSONDecodeError as exc:
        print(f"::error::前置工作流结果不是合法 JSON: {exc}", file=sys.stderr)
        return 2

    evaluation: Dict[str, Any] = {}
    eval_raw = args.evaluation or os.environ.get("QUALITY_GATE_EVALUATION", "")
    if eval_raw:
        try:
            evaluation = json.loads(eval_raw)
        except json.JSONDecodeError as exc:
            print(f"::error::评估上下文不是合法 JSON: {exc}", file=sys.stderr)
            return 2

    got = verdict(results, evaluation)

    if args.format == "json":
        print(json.dumps(got, ensure_ascii=False, indent=2))
        return 0

    print(f"overall={got['overall']}")
    print(f"fail_count={got['fail_count']}")
    print(f"failed_count={got['failed_count']}")
    print(f"cancelled_count={got['cancelled_count']}")
    print(f"stale_count={got['stale_count']}")
    print(f"pending_count={got['pending_count']}")
    print(f"unknown_count={got['unknown_count']}")
    print(f"stale={'true' if got['stale'] else 'false'}")
    print(f"head_sha={got['head_sha']}")
    print(f"reasons={got['reasons']}")
    print(f"buckets_json={json.dumps(got['buckets'], ensure_ascii=False)}")
    print(f"results_json={json.dumps(results, ensure_ascii=False)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
