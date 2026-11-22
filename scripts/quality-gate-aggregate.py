#!/usr/bin/env python3
"""Quality Gate 聚合收敛判据：判定「该聚合了 / 不该重复聚合」（单一事实来源）。

背景（台账 #102③）：`.github/workflows/quality-gate.yml` 由 `workflow_run` 下游触发，
在 CI / Security / CodeQL / SonarQube **各完成一次时各排一条聚合**。实测同一 SHA 上
Quality Gate 聚合器跑过 10 次（success 4 / failure 3 / cancelled 2），同一 SHA 上留下
多条同名 `Quality Gate` check-run。`concurrency` 只是取消同分支旧聚合，**不是去重**。

本脚本负责三件事（只判定，不盖章）：

1. **有界等待判定（settled）**：四个前置在该 SHA 上是否都已「不会再变化」。
   - `status == completed` ⇒ 已出结论；
   - `status == absent` 且 `conclusion == not_found` ⇒ 该 commit 上根本没有这条工作流
     的运行记录（此事件不触发它），**不会再变化**；
   - 其余（in_progress / queued / requested / waiting / 取数异常 error）⇒ **未 settled**。
   未 settled ⇒ 本轮不盖终态结论，等预算内再看；预算耗尽仍未 settled ⇒ 盖 neutral
   （Inconclusive＝继续等，**不是红**）。

2. **状态指纹（fingerprint）**：四个前置的 (name, status, conclusion) 归一化后取哈希。
   指纹只由「结论内容」决定 ⇒ 同一 SHA 上重复触发（定时重跑、手工重跑、四前置依次
   完成）会算出**同一个指纹**，于是「同指纹已盖章 ⇒ 跳过」即可收敛为有界条数。

3. **去重判定（action）**：
   - `wait`  —— 未 settled 且等待预算未耗尽 ⇒ 继续等（不盖章）；
   - `stamp` —— 已 settled（或预算耗尽）且该 SHA 上还没有同指纹的 `Quality Gate`
     check-run ⇒ 盖章；
   - `skip`  —— 该 SHA 上已有同指纹的 `Quality Gate` check-run ⇒ **不重复盖章**。

判定「已盖章」用 check-run 的 `external_id`（= `qg-fp:<指纹>`），并同时在
`output.summary` 里留同样的指纹串，两处任一命中即算已盖章。

**最终结论（success / failure / neutral）不在这里产生**：仍由
`scripts/quality-gate-verdict.py` 判定（判据单一事实来源，本脚本不复制其规则）。
本脚本只回答「该不该盖章、盖什么指纹」。

用法：
    python3 scripts/quality-gate-aggregate.py --self-test
    python3 scripts/quality-gate-aggregate.py --wait --budget-minutes 30
    python3 scripts/quality-gate-aggregate.py --legacy-ref 309e8a89...   # 红侧复跑

自测覆盖（离线，不碰网络）：
    场景 A：同一 SHA 的四个前置全部 completed ⇒ 聚合只产出 **1** 条；
    场景 B：只完成 1 个前置、其余 in_progress ⇒ pending/neutral，**不是** failure；
    场景 C：前置之一 cancelled ⇒ 按仓内既有裁决口径判红（取消＝未出结论，处置是重跑）；
    场景 D：同一指纹重复触发 ⇒ 第二条被去重（skip）；
    场景 E：结论内容变化（failure → success）⇒ 指纹变化 ⇒ 允许重新盖章；
    接线断言：四触发保留 / 有界等待 / 去重闸 / 未新增 CI job / 判据仍在 verdict 脚本。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional, Tuple

# 四个前置工作流（与 quality-gate.yml 的 on.workflow_run.workflows 一致）
PREREQ_WORKFLOWS: Tuple[str, ...] = (
    "CI",
    "Security",
    "CodeQL Security Analysis",
    "SonarQube Analysis",
)

# Quality Gate check-run 的名字（与分支保护 required 名单里的第 9 条同名）
CHECK_NAME = "Quality Gate"

# 指纹前缀（写进 check-run 的 external_id 与 output.summary）
FINGERPRINT_PREFIX = "qg-fp:"

# 终态：status=completed
# 缺席终态：status=absent 且 conclusion=not_found（该 commit 上没有这条工作流的运行记录）
TERMINAL_STATUSES = ("completed",)
ABSENT_CONCLUSION = "not_found"
ABSENT_STATUS = "absent"

DEFAULT_BUDGET_MINUTES = 30
DEFAULT_POLL_SECONDS = 30


# ── 纯判定部分（离线可测，不碰网络）─────────────────────────────────────────

def is_settled(results: Dict[str, Any]) -> bool:
    """四个前置是否都已「不会再变化」。

    未 settled 的三种典型：还在跑（in_progress/queued/...）、取数异常（error）、
    以及压根没被判定过（缺该条记录 ⇒ 不算未完成，但也不算已取到证据）。
    """
    for name in PREREQ_WORKFLOWS:
        info = results.get(name)
        if not isinstance(info, dict):
            return False
        status = (info.get("status") or "").strip()
        conclusion = (info.get("conclusion") or "").strip()
        if status in TERMINAL_STATUSES:
            continue
        if status == ABSENT_STATUS and conclusion == ABSENT_CONCLUSION:
            continue
        return False
    return True


def pending_prereqs(results: Dict[str, Any]) -> List[str]:
    """列出尚未 settled 的前置（供报告说明「继续等」的具体对象）。"""
    out: List[str] = []
    for name in PREREQ_WORKFLOWS:
        info = results.get(name)
        if not isinstance(info, dict):
            out.append(name)
            continue
        status = (info.get("status") or "").strip()
        conclusion = (info.get("conclusion") or "").strip()
        if status in TERMINAL_STATUSES:
            continue
        if status == ABSENT_STATUS and conclusion == ABSENT_CONCLUSION:
            continue
        out.append(name)
    return out


def fingerprint(results: Dict[str, Any]) -> str:
    """状态指纹：只由四个前置的 (name, status, conclusion) 决定。

    刻意**不**包含 run id / created_at：同一 SHA 上「定时重跑」「四前置依次完成」
    得到的结论内容相同 ⇒ 指纹相同 ⇒ 可被去重闸收敛；而结论内容一变（红转绿），
    指纹必变 ⇒ 允许重新盖章。
    """
    parts = []
    for name in PREREQ_WORKFLOWS:
        info = results.get(name) or {}
        status = (info.get("status") or "").strip()
        conclusion = (info.get("conclusion") or "").strip()
        parts.append(f"{name}={status}/{conclusion}")
    blob = "|".join(parts).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()[:16]


def dedup_key(fp: str) -> str:
    return FINGERPRINT_PREFIX + fp


def _check_fingerprint(check: Dict[str, Any]) -> str:
    """从已有 check-run 里取出它盖的指纹（external_id 优先，summary 兜底）。"""
    ext = (check.get("external_id") or "").strip()
    if ext.startswith(FINGERPRINT_PREFIX):
        return ext[len(FINGERPRINT_PREFIX):].strip()
    output = check.get("output") or {}
    summary = (output.get("summary") or "") if isinstance(output, dict) else ""
    marker = FINGERPRINT_PREFIX
    idx = summary.find(marker)
    if idx >= 0:
        tail = summary[idx + len(marker):]
        token = ""
        for ch in tail:
            if ch.isalnum():
                token += ch
            else:
                break
        return token
    return ""


def already_stamped(existing_checks: List[Dict[str, Any]], fp: str) -> bool:
    """该 SHA 上是否已经有同指纹的 `Quality Gate` check-run。"""
    for check in existing_checks or []:
        if (check.get("name") or "") != CHECK_NAME:
            continue
        if _check_fingerprint(check) == fp:
            return True
    return False


def decide(results: Dict[str, Any],
           existing_checks: Optional[List[Dict[str, Any]]] = None,
           budget_remaining_seconds: int = 0) -> Dict[str, Any]:
    """给出本轮聚合动作。

    返回：action(wait|stamp|skip) / settled / fingerprint / dedup_key / pending / reason
    """
    existing_checks = existing_checks or []
    settled = is_settled(results)
    fp = fingerprint(results)
    pending = pending_prereqs(results)

    if not settled and budget_remaining_seconds > 0:
        action = "wait"
        reason = ("四个前置尚未全部出结论（未完成：" + "、".join(pending) +
                  "）⇒ 继续等（有界），本轮不盖章")
    elif not settled:
        action = "stamp"
        reason = ("等待预算耗尽仍有两个前置未出结论（" + "、".join(pending) +
                  "）⇒ 盖 neutral（继续等，不是红）")
    elif already_stamped(existing_checks, fp):
        action = "skip"
        reason = ("该 SHA 上已有同指纹（" + fp + "）的 `Quality Gate` 结论 ⇒ "
                  "跳过重复盖章（收敛，不新增 check-run）")
    else:
        action = "stamp"
        reason = "四个前置均已出结论且该 SHA 上无同指纹结论 ⇒ 盖章"

    return {
        "action": action,
        "settled": settled,
        "fingerprint": fp,
        "dedup_key": dedup_key(fp),
        "pending": pending,
        "reason": reason,
    }


# ── 取数部分（CI 运行时用；离线自测不碰网络）────────────────────────────────

class GitHubApi:
    """极简 GitHub REST 客户端（只读）。

    取数失败一律抛异常，由调用方 fail-closed 处理（不静默当成「没跑过」）。
    """

    def __init__(self, repo: str, token: str = "", timeout: int = 60) -> None:
        self.repo = repo
        self.token = token
        self.timeout = timeout

    def get(self, path: str) -> Any:
        url = "https://api.github.com/" + path.lstrip("/")
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "DataEngineBDP-quality-gate-aggregate",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(url, headers=headers)
        try:
            # 核实依据：url = "https://api.github.com/" + path.lstrip("/")，scheme 与 host 均为字面量常量，path 由仓内固定 API 路径 + sha/event 取值拼成，非终端用户输入 ⇒ 无法被改成 file:// 等自定义 scheme。
            # nosemgrep: python.lang.security.audit.dynamic-urllib-use-detected.dynamic-urllib-use-detected
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raise RuntimeError(f"GitHub API HTTP {exc.code}: {path}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"GitHub API 连接失败: {path}: {exc.reason}") from exc

    # ── 具体取数 ──
    def workflow_runs_for_sha(self, sha: str) -> List[Dict[str, Any]]:
        data = self.get(f"repos/{self.repo}/actions/runs?head_sha={sha}&per_page=100")
        return list(data.get("workflow_runs") or [])

    def branch_head(self, branch: str) -> str:
        data = self.get(f"repos/{self.repo}/branches/{branch}")
        return ((data.get("commit") or {}).get("sha") or "")

    def check_runs_for_sha(self, sha: str) -> List[Dict[str, Any]]:
        data = self.get(
            f"repos/{self.repo}/commits/{sha}/check-runs?filter=all&per_page=100")
        return list(data.get("check_runs") or [])


def collect_prereq_results(api: GitHubApi, sha: str) -> Dict[str, Any]:
    """按 head_sha 精确取四个前置在该 SHA 上的运行（同名取最新一条）。"""
    runs = api.workflow_runs_for_sha(sha)
    results: Dict[str, Any] = {}
    for name in PREREQ_WORKFLOWS:
        candidates = [r for r in runs
                      if r.get("name") == name and r.get("head_sha") == sha]
        if not candidates:
            results[name] = {
                "conclusion": ABSENT_CONCLUSION,
                "status": ABSENT_STATUS,
                "html_url": None,
                "created_at": None,
            }
            continue
        newest = sorted(candidates, key=lambda r: (r.get("created_at") or "",
                                                    r.get("id") or 0))[-1]
        done = newest.get("status") == "completed"
        results[name] = {
            "conclusion": newest.get("conclusion") if done else newest.get("status"),
            "status": newest.get("status"),
            "html_url": newest.get("html_url"),
            "created_at": newest.get("created_at"),
            "run_id": newest.get("id"),
            "event": newest.get("event"),
        }
    return results


def collect_evaluation(api: GitHubApi, payload: Dict[str, Any], sha: str) -> Dict[str, Any]:
    """构造评估上下文（与既有 quality-gate.yml 的取数口径一致）。"""
    wr = payload.get("workflow_run") or {}
    event = wr.get("event") or payload.get("event") or "unknown"
    head_branch = wr.get("head_branch") or None
    expected_head_sha = sha
    staleness_known = False
    if event == "pull_request":
        prs = wr.get("pull_requests") or []
        pr_head_sha = None
        if prs and isinstance(prs[0], dict):
            pr_head_sha = ((prs[0].get("head") or {}).get("sha")) or None
        if pr_head_sha:
            expected_head_sha = pr_head_sha
            staleness_known = True
    elif head_branch:
        expected_head_sha = sha
        staleness_known = True
    current_head_sha = ""
    if head_branch:
        try:
            current_head_sha = api.branch_head(head_branch)
        except RuntimeError:
            current_head_sha = ""
    return {
        "head_sha": sha,
        "event": event,
        "head_branch": head_branch,
        "expected_head_sha": expected_head_sha,
        "current_head_sha": current_head_sha,
        "staleness_known": staleness_known,
    }


def _emit(key: str, value: str) -> None:
    """写 step output。

    有 $GITHUB_OUTPUT 时写进文件（step output 的唯一来源），并把短字段回显到日志；
    没有时直接打到 stdout（本地排查用）。
    """
    line = f"{key}={value}"
    out_path = os.environ.get("GITHUB_OUTPUT", "")
    if not out_path:
        print(line)
        return
    with open(out_path, "a", encoding="utf-8") as fh:
        fh.write(line + "\n")
    if key in ("action", "settled", "fingerprint", "dedup_key", "pending_prereqs",
               "waited_seconds"):
        print(line)


def run_ci_mode(budget_minutes: int, poll_seconds: int) -> int:
    """CI 模式：取数 → 有界等待 → 给出动作，写 $GITHUB_OUTPUT。"""
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN", "")
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    payload: Dict[str, Any] = {}
    event_path = os.environ.get("GITHUB_EVENT_PATH", "")
    if event_path and os.path.exists(event_path):
        try:
            with open(event_path, "r", encoding="utf-8") as fh:
                payload = json.load(fh)
        except (OSError, json.JSONDecodeError):
            payload = {}

    sha = ""
    wr = payload.get("workflow_run") or {}
    if wr.get("head_sha"):
        sha = wr["head_sha"]
    elif payload.get("after"):
        sha = payload["after"]
    elif os.environ.get("GITHUB_SHA"):
        sha = os.environ["GITHUB_SHA"]
    if not sha:
        print("::error::拿不到评估对象 head_sha ⇒ 不盖章（证据不全）", file=sys.stderr)
        return 2

    api = GitHubApi(repo, token)
    deadline = time.time() + budget_minutes * 60
    waited = 0
    results: Dict[str, Any] = {}
    evaluation: Dict[str, Any] = {}
    existing: List[Dict[str, Any]] = []
    last_error = ""

    while True:
        try:
            results = collect_prereq_results(api, sha)
            evaluation = collect_evaluation(api, payload, sha)
            existing = api.check_runs_for_sha(sha)
            last_error = ""
        except RuntimeError as exc:
            last_error = str(exc)
            results = {
                name: {"conclusion": "error", "status": ABSENT_STATUS,
                       "html_url": None, "created_at": None}
                for name in PREREQ_WORKFLOWS
            }
            evaluation = {
                "head_sha": sha,
                "event": event_name or "unknown",
                "head_branch": None,
                "expected_head_sha": sha,
                "current_head_sha": "",
                "staleness_known": False,
            }
            existing = []

        remaining = int(deadline - time.time())
        decision = decide(results, existing, budget_remaining_seconds=max(0, remaining))
        if decision["action"] != "wait":
            break
        if remaining <= 0:
            break
        sleep_for = min(poll_seconds, remaining)
        print(f"::notice::等待四个前置出结论：{decision['reason']}（剩余 {remaining}s）")
        time.sleep(sleep_for)
        waited += sleep_for

    if last_error:
        print(f"::warning::取数异常（{last_error}）⇒ 按证据不全处理，不冒充通过", file=sys.stderr)

    _emit("results_json", json.dumps(results, ensure_ascii=False))
    _emit("evaluation_json", json.dumps(evaluation, ensure_ascii=False))
    _emit("action", decision["action"])
    _emit("settled", "true" if decision["settled"] else "false")
    _emit("fingerprint", decision["fingerprint"])
    _emit("dedup_key", decision["dedup_key"])
    _emit("pending_prereqs", ",".join(decision["pending"]))
    _emit("aggregate_reason", decision["reason"])
    _emit("waited_seconds", str(waited))
    return 0


# ── 接线断言（文本级，不依赖 PyYAML：CI runner 不保证装了 PyYAML）─────────────

def _read(path: str) -> str:
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def workflow_contract_checks(workflow_path: str) -> List[Tuple[str, bool, str]]:
    """对 quality-gate.yml 做接线断言，返回 [(描述, 是否通过, 细节)]。"""
    checks: List[Tuple[str, bool, str]] = []
    if not os.path.exists(workflow_path):
        checks.append(("quality-gate.yml 存在", False, f"缺文件：{workflow_path}"))
        return checks
    text = _read(workflow_path)

    # ① 四个 workflow_run 触发保留
    checks.append(("保留 workflow_run 触发面", "workflow_run:" in text, "on.workflow_run"))
    for wf in PREREQ_WORKFLOWS:
        ok = f'- "{wf}"' in text or f"- '{wf}'" in text
        checks.append((f"保留 workflow_run 触发：{wf}", ok, "on.workflow_run.workflows"))
    checks.append(("workflow_run 仍只监听 completed（未引入 requested 等新类型）",
                   bool(re.search(r"-\s*completed\b", text)) and "requested" not in text,
                   "on.workflow_run.types"))

    # ② 聚合判据脚本被接线（收敛判据单一事实来源）
    checks.append(("接线 scripts/quality-gate-aggregate.py",
                   "scripts/quality-gate-aggregate.py" in text, "聚合收敛判据"))

    # ③ 有界等待
    checks.append(("聚合有界等待（--wait + 预算）",
                   "--wait" in text and ("budget-minutes" in text or
                                         "QUALITY_GATE_WAIT_MINUTES" in text),
                   "有界等待参数"))

    # ④ 去重闸：盖章动作由聚合判据给出，且盖章步骤被 action 门控
    checks.append(("盖章步骤由聚合动作门控（action == 'stamp'）",
                   "steps.aggregate.outputs.action" in text, "去重闸"))
    checks.append(("盖章写入指纹（dedup_key / fingerprint）",
                   "dedup_key" in text or "fingerprint" in text, "指纹写入"))

    # ⑤ 未新增 CI job（聚合器仍是单作业）
    job_count = text.count("\n  aggregate-quality-gate:")
    checks.append(("未新增 CI job（聚合器仍是 1 个作业）", job_count == 1,
                   f"aggregate-quality-gate 出现 {job_count} 次"))

    # ⑥ 最终结论仍由 quality-gate-verdict.py 判定
    checks.append(("最终结论仍由 scripts/quality-gate-verdict.py 判定",
                   "scripts/quality-gate-verdict.py" in text, "判据单一事实来源"))

    # ⑦ 并发组按 SHA 收敛（同 SHA 的聚合串行、旧的被抢占）
    checks.append(("并发组按 head_sha 收敛",
                   "quality-gate-${{ github.event.workflow_run.head_sha" in text,
                   "concurrency.group"))
    return checks


# ── 离线自测 ────────────────────────────────────────────────────────────────

def _ok(info: Dict[str, Any]) -> Dict[str, Any]:
    return {"conclusion": "success", "status": "completed", "created_at": "2026-01-01T00:00:00Z"}


def _failed(info: Dict[str, Any]) -> Dict[str, Any]:
    return {"conclusion": "failure", "status": "completed", "created_at": "2026-01-01T00:00:00Z"}


def _cancelled(info: Dict[str, Any]) -> Dict[str, Any]:
    return {"conclusion": "cancelled", "status": "completed", "created_at": "2026-01-01T00:00:00Z"}


def _running(info: Dict[str, Any]) -> Dict[str, Any]:
    return {"conclusion": "in_progress", "status": "in_progress",
            "created_at": "2026-01-01T00:00:00Z"}


def _absent(info: Dict[str, Any]) -> Dict[str, Any]:
    return {"conclusion": ABSENT_CONCLUSION, "status": ABSENT_STATUS, "created_at": None}


def _load_verdict_module():
    """加载 scripts/quality-gate-verdict.py（文件名带连字符，用 importlib 取）。"""
    import importlib.util
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "quality-gate-verdict.py")
    spec = importlib.util.spec_from_file_location("quality_gate_verdict", path)
    if spec is None or spec.loader is None:
        return None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def simulate_aggregation(results_sequence: List[Dict[str, Any]],
                         existing: Optional[List[Dict[str, Any]]] = None,
                         budget_remaining_seconds: int = 600,
                         policy: str = "converged") -> Dict[str, Any]:
    """模拟一串「前置完成事件」在一条 SHA 上的聚合过程。

    policy='converged'：走本脚本的收敛策略（settled 闸 + 指纹去重）。
    policy='legacy'   ：走修复前口径——每个完成事件都盖章（无 settled 闸、无去重）。
    返回 {'stamps': n, 'actions': [...], 'fingerprints': [...]}。
    """
    existing = list(existing or [])
    stamps = 0
    actions: List[str] = []
    fps: List[str] = []
    for results in results_sequence:
        if policy == "legacy":
            action = "stamp"
            fp = fingerprint(results)
        else:
            decision = decide(results, existing,
                              budget_remaining_seconds=budget_remaining_seconds)
            action = decision["action"]
            fp = decision["fingerprint"]
        actions.append(action)
        fps.append(fp)
        if action == "stamp":
            stamps += 1
            existing.append({
                "name": CHECK_NAME,
                "conclusion": "success",
                "external_id": dedup_key(fp),
                "output": {"summary": f"{dedup_key(fp)}"},
            })
    return {"stamps": stamps, "actions": actions, "fingerprints": fps,
            "existing": existing}


def _self_test(workflow_path: str, legacy_ref: str = "") -> int:
    passed = 0
    failures: List[str] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        nonlocal passed
        if ok:
            passed += 1
            print(f"  [PASS] {name}")
        else:
            failures.append(f"{name}" + (f"（{detail}）" if detail else ""))
            print(f"  [FAIL] {name}" + (f"（{detail}）" if detail else ""))

    verdict_mod = _load_verdict_module()
    if verdict_mod is None:
        print("  [FAIL] 载入 scripts/quality-gate-verdict.py 失败")
        return 1

    # ── 场景 A：四个前置全部 completed ⇒ 只产出 1 条 ──────────────────────
    print("-- 场景 A：同一 SHA 的四个前置全部 completed ⇒ 聚合只产出 1 条 --")
    all_done = {name: _ok({}) for name in PREREQ_WORKFLOWS}
    seq_a = [
        {**all_done, "CI": _running({})},                       # 事件 1：CI 完成
        {**all_done, "CI": _ok({}), "Security": _running({})},   # 事件 2：Security 完成
        {**all_done, "CI": _ok({}), "Security": _ok({}),
         "CodeQL Security Analysis": _running({})},              # 事件 3：CodeQL 完成
        all_done,                                                 # 事件 4：SonarQube 完成
        all_done,                                                 # 事件 5：定时重跑再触发一次
    ]
    sim_new = simulate_aggregation(seq_a, policy="converged")
    sim_legacy = simulate_aggregation(seq_a, policy="legacy")
    check("收敛策略下场景 A 只盖 1 条", sim_new["stamps"] == 1,
          f"实际 {sim_new['stamps']} 条（动作序列 {sim_new['actions']}）")
    check("修复前口径下场景 A 会盖 5 条（对照：这就是 #102③ 的放大面）",
          sim_legacy["stamps"] == 5, f"实际 {sim_legacy['stamps']} 条")
    check("场景 A 的重复触发被判为 skip", sim_new["actions"][-1] == "skip",
          f"动作序列 {sim_new['actions']}")

    # ── 场景 B：只完成 1 个前置 ⇒ pending/neutral，不是 failure ───────────
    print("-- 场景 B：只完成 1 个前置、其余 in_progress ⇒ pending/neutral，不是 failure --")
    partial = {
        "CI": _ok({}),
        "Security": _running({}),
        "CodeQL Security Analysis": _running({}),
        "SonarQube Analysis": _running({}),
    }
    dec_b = decide(partial, [], budget_remaining_seconds=600)
    check("场景 B 判定为未 settled", dec_b["settled"] is False)
    check("场景 B 动作为 wait（有界等待，不盖章）", dec_b["action"] == "wait",
          f"实际 {dec_b['action']}")
    v_b = verdict_mod.verdict(partial, {"staleness_known": True,
                                        "expected_head_sha": "a", "current_head_sha": "a"})
    check("场景 B 最终结论为 neutral（继续等）", v_b["overall"] == "neutral",
          f"实际 {v_b['overall']}")
    check("场景 B 不算 failure", v_b["overall"] != "failure" and v_b["fail_count"] == 0,
          f"overall={v_b['overall']} fail_count={v_b['fail_count']}")
    # 等待预算耗尽 ⇒ 仍不是 failure，只盖 neutral
    dec_b0 = decide(partial, [], budget_remaining_seconds=0)
    check("场景 B 预算耗尽后动作为 stamp（盖 neutral）", dec_b0["action"] == "stamp",
          f"实际 {dec_b0['action']}")
    check("场景 B 预算耗尽仍不算 failure",
          verdict_mod.verdict(partial, {"staleness_known": True,
                                        "expected_head_sha": "a",
                                        "current_head_sha": "a"})["overall"] != "failure")

    # ── 场景 C：前置之一 cancelled ⇒ 按仓内既有裁决口径判红 ───────────────
    print("-- 场景 C：前置之一 cancelled ⇒ 按仓内既有裁决口径判红 --")
    cancelled_case = {
        "CI": _cancelled({}),
        "Security": _ok({}),
        "CodeQL Security Analysis": _ok({}),
        "SonarQube Analysis": _ok({}),
    }
    dec_c = decide(cancelled_case, [], budget_remaining_seconds=0)
    check("场景 C 已 settled（取消也是终态）", dec_c["settled"] is True)
    v_c = verdict_mod.verdict(cancelled_case, {"staleness_known": True,
                                               "expected_head_sha": "a",
                                               "current_head_sha": "a"})
    check("场景 C 判 failure（取消＝未出结论，处置是重跑不是改代码）",
          v_c["overall"] == "failure" and v_c["fail_count"] == 1,
          f"overall={v_c['overall']} fail_count={v_c['fail_count']}")
    check("场景 C 理由写明「重跑」", "重跑" in v_c["reasons"], v_c["reasons"])

    # ── 场景 D/E：去重与指纹随结论变化 ───────────────────────────────────
    print("-- 场景 D/E：同指纹去重 / 结论变化后允许重新盖章 --")
    fp_all = fingerprint(all_done)
    check("同结论 ⇒ 同指纹", fp_all == fingerprint(all_done))
    check("已盖章同指纹 ⇒ skip",
          decide(all_done, [{"name": CHECK_NAME, "external_id": dedup_key(fp_all)}],
                  0)["action"] == "skip")
    red = {**all_done, "CI": _failed({})}
    check("结论变化 ⇒ 指纹变化", fingerprint(red) != fp_all)
    check("指纹变化 ⇒ 允许重新盖章",
          decide(red, [{"name": CHECK_NAME, "external_id": dedup_key(fp_all)}],
                  0)["action"] == "stamp")
    check("external_id 缺失但 summary 有指纹 ⇒ 仍判已盖章",
          decide(all_done, [{"name": CHECK_NAME, "external_id": "",
                             "output": {"summary": f"指纹 {dedup_key(fp_all)} ok"}}],
                  0)["action"] == "skip")

    # ── 接线断言 ──────────────────────────────────────────────────────────
    print("-- 接线断言（quality-gate.yml）--")
    target = workflow_path
    if legacy_ref:
        target = _materialize_legacy(legacy_ref)
        print(f"   （红侧：用 git 取出 {legacy_ref[:12]} 的 quality-gate.yml 做对照）")
    for name, ok, detail in workflow_contract_checks(target):
        check(name, ok, detail)

    # ── 汇总 ──────────────────────────────────────────────────────────────
    total = passed + len(failures)
    print()
    print(f"self-test: {passed} PASS / {len(failures)} FAIL（共 {total} 项）")
    for line in failures:
        print(f"FAIL {line}")
    return 0 if not failures else 1


def _materialize_legacy(ref: str) -> str:
    """把指定 ref 的 quality-gate.yml 落到临时文件（红侧对照用）。"""
    import subprocess
    import tempfile
    out = os.path.join(tempfile.gettempdir(), f"qg-legacy-{ref[:12]}.yml")
    proc = subprocess.run(["git", "show", f"{ref}:.github/workflows/quality-gate.yml"],
                          capture_output=True)
    if proc.returncode != 0:
        return out
    with open(out, "wb") as fh:
        fh.write(proc.stdout)
    return out


def _parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Quality Gate 聚合收敛判据")
    parser.add_argument("--self-test", action="store_true", help="运行离线自测")
    parser.add_argument("--wait", action="store_true", help="CI 模式：有界等待后给出动作")
    parser.add_argument("--budget-minutes", type=int, default=DEFAULT_BUDGET_MINUTES,
                        help=f"等待预算（分钟，默认 {DEFAULT_BUDGET_MINUTES}）")
    parser.add_argument("--poll-seconds", type=int, default=DEFAULT_POLL_SECONDS,
                        help=f"轮询间隔（秒，默认 {DEFAULT_POLL_SECONDS}）")
    parser.add_argument("--workflow", default="", help="接线断言用的 workflow 文件路径")
    parser.add_argument("--legacy-ref", default="",
                        help="红侧对照：对该 ref 的 quality-gate.yml 做接线断言")
    return parser.parse_args(argv)


def main(argv: List[str]) -> int:
    args = _parse_args(argv)
    if args.self_test:
        workflow = args.workflow or os.path.join(
            os.path.dirname(os.path.abspath(__file__)),
            "..", ".github", "workflows", "quality-gate.yml")
        return _self_test(workflow, args.legacy_ref)
    if args.wait:
        return run_ci_mode(args.budget_minutes, args.poll_seconds)
    parser = _parse_args(["--help"])
    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
