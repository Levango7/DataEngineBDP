#!/usr/bin/env python3
"""push 扇出侧收敛闸：判定「本次 push 的内容是否已被 PR 侧验证过」（单一事实来源）。

台账 #102②：**合并后 main push 与 PR 侧高度重叠**——PR 侧已经在 PR 头上跑过
CI / Security / CodeQL / SonarQube，合并后 main 又对同一份内容再跑一遍
（实测 PR #854：PR 头 SHA 6 个 workflow run / 57 个 check-run；合并 SHA 上再跑一遍
CI/Security/CodeQL/SonarQube/Build/Image Sign）。

本脚本给出「本波是否需要重复执行」的判定，**只在作业内部使用**（与仓内既有范式
scripts/check-docker-build-relevance.sh 一致）：作业照常跑、照常出结论（否则 required
check 会永远缺失 ⇒ PR 永久阻塞），只是把「PR 侧已覆盖」的作业的重活跳过。

判定口径（四条全中才收敛，缺一即照常执行）：
  ① 事件是 push，且推到 main（PR 侧 / 定时 / 手工触发不适用）；
  ② 该 push 的 commit 是**已合并 PR** 的产物（commits/{sha}/pulls 里有 merged_at 非空的 PR）；
  ③ 该 commit 的 **tree SHA 与 PR 头 commit 的 tree SHA 完全一致**
     （内容等价；内容一变必须重跑。实测 PR #854：合并 commit 与 PR 头 tree 同为
     ca3df9300a559671c0b358149b910dcdda0a183a）；
  ④ 该 PR 头上**本工作流**（--workflow 指定）的 pull_request 运行**已 completed 且 success**
     （同名多条取最新一条；最新一条不是成功 ⇒ 不收敛）。

**fail-closed**：任何取数异常（API 失败 / PR 查不到 / tree 取不到）一律判「不收敛」
⇒ 照常执行。判定错误的代价不对称——误判「已覆盖」会静默丢掉一次验证，
误判「未覆盖」只是多跑一次。

用法：
    python3 scripts/ci-push-converge.py --workflow CI          # CI 模式：写 $GITHUB_OUTPUT
    python3 scripts/ci-push-converge.py --self-test            # 离线自测（不碰网络）
    python3 scripts/ci-push-converge.py --self-test --legacy-ref 309e8a89
        # 红侧对照：用 git 取出该 ref 的四个 workflow 做接线断言（未接线 ⇒ 真红）

输出（写入 $GITHUB_OUTPUT）：
    converge=true|false   —— 是否收敛（true ⇒ 本作业本波不重复执行重活）
    reason=...            —— 判定理由（写进日志，便于复验）
    pr_head_sha=...       —— 证据来源（PR 头 SHA）
    pr_run_id=...         —— 证据来源（PR 侧那次 run 的 id）
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional, Tuple

MAIN_REF_PREFIX = "refs/heads/main"


class GitHubApi:
    """只读 GitHub REST 客户端（取数失败抛异常，由调用方 fail-closed 处理）。"""

    def __init__(self, repo: str, token: str = "", timeout: int = 60) -> None:
        self.repo = repo
        self.token = token
        self.timeout = timeout

    def get(self, path: str) -> Any:
        url = "https://api.github.com/" + path.lstrip("/")
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "DataEngineBDP-ci-push-converge",
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
    def commit_tree(self, sha: str) -> str:
        data = self.get(f"repos/{self.repo}/commits/{sha}")
        return ((data.get("commit") or {}).get("tree") or {}).get("sha") or ""

    def merged_prs_for_commit(self, sha: str) -> List[Dict[str, Any]]:
        data = self.get(f"repos/{self.repo}/commits/{sha}/pulls")
        if not isinstance(data, list):
            return []
        return [p for p in data if isinstance(p, dict) and p.get("merged_at")]

    def workflow_runs_for_sha(self, sha: str, event: str = "") -> List[Dict[str, Any]]:
        url = f"repos/{self.repo}/actions/runs?head_sha={sha}&per_page=100"
        if event:
            url += f"&event={event}"
        data = self.get(url)
        return list(data.get("workflow_runs") or [])


def _single_line(text: str) -> str:
    return " ".join((text or "").split()).replace("=", "＝")


def evaluate(api: GitHubApi,
             sha: str,
             workflow_name: str,
             event_name: str,
             ref: str) -> Dict[str, Any]:
    """给出收敛判定。返回 converge / reason / pr_head_sha / pr_run_id。"""
    out = {"converge": False, "reason": "", "pr_head_sha": "", "pr_run_id": ""}

    if event_name != "push":
        out["reason"] = (f"事件不是 push（{event_name or '未知'}）⇒ PR 侧/定时/手工触发不适用收敛")
        return out
    if not (ref or "").startswith(MAIN_REF_PREFIX):
        out["reason"] = f"推的不是 main（{ref or '未知'}）⇒ 不收敛"
        return out

    try:
        tree = api.commit_tree(sha)
        if not tree:
            out["reason"] = "取不到该 commit 的 tree ⇒ fail-closed 不收敛"
            return out

        prs = api.merged_prs_for_commit(sha)
        if not prs:
            out["reason"] = "该 push 不是已合并 PR 的产物（查不到 merged PR）⇒ 无 PR 侧证据，不收敛"
            return out
        pr = prs[0]
        pr_head_sha = ((pr.get("head") or {}).get("sha")) or ""
        if not pr_head_sha:
            out["reason"] = "关联 PR 取不到 head SHA ⇒ fail-closed 不收敛"
            return out

        pr_tree = api.commit_tree(pr_head_sha)
        if not pr_tree:
            out["reason"] = "取不到 PR 头的 tree ⇒ fail-closed 不收敛"
            return out
        if pr_tree != tree:
            out["reason"] = (f"内容不等价（push commit tree={tree[:12]} ≠ PR 头 tree="
                             f"{pr_tree[:12]}）⇒ 必须重跑")
            return out

        runs = api.workflow_runs_for_sha(pr_head_sha, event="pull_request")
        candidates = [r for r in runs if r.get("name") == workflow_name]
        if not candidates:
            out["reason"] = (f"PR 头 {pr_head_sha[:12]} 上没有 {workflow_name} 的 pull_request "
                             f"运行 ⇒ 无可复用证据，不收敛")
            return out
        newest = sorted(candidates, key=lambda r: (r.get("created_at") or "",
                                                    r.get("id") or 0))[-1]
        if newest.get("status") != "completed" or newest.get("conclusion") != "success":
            out["reason"] = (f"PR 头 {pr_head_sha[:12]} 上 {workflow_name} 的最新一次运行未成功"
                             f"（{newest.get('status')}/{newest.get('conclusion')}）⇒ 不收敛")
            return out

        out["converge"] = True
        out["pr_head_sha"] = pr_head_sha
        out["pr_run_id"] = str(newest.get("id") or "")
        out["reason"] = (f"PR #{pr.get('number')}（head {pr_head_sha[:12]}）已验证同一内容"
                         f"（tree {tree[:12]}）且 {workflow_name} 的 pull_request 运行成功"
                         f"（run {newest.get('id')}）⇒ 本波不重复执行")
        return out
    except RuntimeError as exc:
        out["reason"] = f"取数异常（{exc}）⇒ fail-closed 不收敛（照常执行）"
        return out
    except (KeyError, TypeError, ValueError) as exc:
        out["reason"] = f"取数结果异常（{exc}）⇒ fail-closed 不收敛（照常执行）"
        return out


def _emit(key: str, value: str) -> None:
    line = f"{key}={value}"
    out_path = os.environ.get("GITHUB_OUTPUT", "")
    if not out_path:
        print(line)
        return
    with open(out_path, "a", encoding="utf-8") as fh:
        fh.write(line + "\n")
    print(line)


def run_ci_mode(workflow_name: str) -> int:
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN", "")
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    ref = os.environ.get("GITHUB_REF", "")
    sha = os.environ.get("GITHUB_SHA", "")
    payload: Dict[str, Any] = {}
    event_path = os.environ.get("GITHUB_EVENT_PATH", "")
    if event_path and os.path.exists(event_path):
        try:
            with open(event_path, "r", encoding="utf-8") as fh:
                payload = json.load(fh)
            sha = payload.get("after") or sha
        except (OSError, json.JSONDecodeError):
            payload = {}

    if not repo or not sha:
        _emit("converge", "false")
        _emit("reason", "缺少 GITHUB_REPOSITORY / GITHUB_SHA ⇒ fail-closed 不收敛")
        return 0

    api = GitHubApi(repo, token)
    result = evaluate(api, sha, workflow_name, event_name, ref)
    _emit("converge", "true" if result["converge"] else "false")
    _emit("reason", _single_line(result["reason"]))
    _emit("pr_head_sha", result["pr_head_sha"])
    _emit("pr_run_id", result["pr_run_id"])
    return 0


# ── 离线自测 ────────────────────────────────────────────────────────────────

class StubApi:
    """离线夹具 API：按路径子串匹配返回预置响应；未命中即抛异常（模拟取数失败）。"""

    def __init__(self, responses: Dict[str, Any]) -> None:
        self.responses = responses
        self.calls: List[str] = []

    def _match(self, path: str) -> Any:
        # 取**最长**匹配键：否则 "/commits/<sha>" 会抢走 "/commits/<sha>/pulls" 的响应
        best_key = ""
        for key in self.responses:
            if key in path and len(key) > len(best_key):
                best_key = key
        if not best_key:
            raise RuntimeError(f"stub 未预置该路径：{path}")
        return self.responses[best_key]

    def commit_tree(self, sha: str) -> str:
        self.calls.append(f"commit_tree:{sha}")
        data = self._match(f"/commits/{sha}")
        return ((data.get("commit") or {}).get("tree") or {}).get("sha") or ""

    def merged_prs_for_commit(self, sha: str) -> List[Dict[str, Any]]:
        self.calls.append(f"merged_prs:{sha}")
        data = self._match(f"/commits/{sha}/pulls")
        return [p for p in data if isinstance(p, dict) and p.get("merged_at")]

    def workflow_runs_for_sha(self, sha: str, event: str = "") -> List[Dict[str, Any]]:
        self.calls.append(f"runs:{sha}:{event}")
        data = self._match("/actions/runs")
        return list(data.get("workflow_runs") or [])


def _commit_obj(tree: str) -> Dict[str, Any]:
    return {"commit": {"tree": {"sha": tree}}}


def _pr(number: int, head_sha: str, merged: bool = True) -> Dict[str, Any]:
    return {
        "number": number,
        "head": {"sha": head_sha},
        "merged_at": "2026-11-22T03:55:00Z" if merged else None,
    }


def _run(rid: int, name: str, status: str, conclusion: Any, created: str) -> Dict[str, Any]:
    return {"id": rid, "name": name, "status": status, "conclusion": conclusion,
            "created_at": created, "event": "pull_request", "head_sha": "prhead"}


TREE_M = "ca3df9300a559671c0b358149b910dcdda0a183a"
TREE_OTHER = "0000000000000000000000000000000000000000"


def _materialize_legacy(ref: str) -> List[str]:
    """把指定 ref 的四个 workflow 落到临时目录（红侧对照用）。

    用途：复现「修复前」形态——把接线断言跑在**未接收敛闸**的 workflow 上 ⇒ 真红。
    """
    import subprocess
    import tempfile
    out_dir = os.path.join(tempfile.gettempdir(), f"ci-push-converge-legacy-{ref[:12]}")
    os.makedirs(out_dir, exist_ok=True)
    paths: List[str] = []
    for name in ("ci.yml", "codeql.yml", "security.yml", "sonarqube.yml"):
        rel = f".github/workflows/{name}"
        proc = subprocess.run(["git", "show", f"{ref}:{rel}"], capture_output=True)
        target = os.path.join(out_dir, name)
        if proc.returncode == 0:
            with open(target, "wb") as fh:
                fh.write(proc.stdout)
        paths.append(target)
    return paths


def _self_test(workflow_paths: List[str]) -> int:
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

    base_responses = {
        "/commits/prhead": _commit_obj(TREE_M),
        "/commits/pushsha": _commit_obj(TREE_M),
        "/commits/pushsha/pulls": [_pr(854, "prhead")],
        "/actions/runs": {"workflow_runs": [
            _run(111, "CI", "completed", "success", "2026-11-22T03:00:00Z")]},
    }

    # ① 事件不是 push ⇒ 不收敛
    r = evaluate(StubApi(base_responses), "pushsha", "CI", "pull_request", "refs/pull/854/merge")
    check("PR 触发不收敛", r["converge"] is False, r["reason"])

    # ② 推的不是 main ⇒ 不收敛
    r = evaluate(StubApi(base_responses), "pushsha", "CI", "push", "refs/heads/feature/x")
    check("非 main 的 push 不收敛", r["converge"] is False, r["reason"])

    # ③ 不是已合并 PR 的产物 ⇒ 不收敛
    responses = dict(base_responses)
    responses["/commits/pushsha/pulls"] = []
    r = evaluate(StubApi(responses), "pushsha", "CI", "push", MAIN_REF_PREFIX)
    check("查不到关联 PR ⇒ 不收敛", r["converge"] is False, r["reason"])

    # ④ PR 未合并 ⇒ 不收敛
    responses = dict(base_responses)
    responses["/commits/pushsha/pulls"] = [_pr(854, "prhead", merged=False)]
    r = evaluate(StubApi(responses), "pushsha", "CI", "push", MAIN_REF_PREFIX)
    check("关联 PR 未合并 ⇒ 不收敛", r["converge"] is False, r["reason"])

    # ⑤ tree 不一致 ⇒ 不收敛（内容变了必须重跑）
    responses = dict(base_responses)
    responses["/commits/prhead"] = _commit_obj(TREE_OTHER)
    r = evaluate(StubApi(responses), "pushsha", "CI", "push", MAIN_REF_PREFIX)
    check("内容不等价（tree 不一致）⇒ 不收敛", r["converge"] is False, r["reason"])

    # ⑥ tree 一致 + PR 侧成功 ⇒ 收敛
    r = evaluate(StubApi(base_responses), "pushsha", "CI", "push", MAIN_REF_PREFIX)
    check("tree 一致 + PR 侧该工作流成功 ⇒ 收敛", r["converge"] is True, r["reason"])
    check("收敛时记录证据来源（PR 头 SHA / run id）",
          r["pr_head_sha"] == "prhead" and r["pr_run_id"] == "111",
          f"pr_head_sha={r['pr_head_sha']} pr_run_id={r['pr_run_id']}")

    # ⑦ PR 侧失败 ⇒ 不收敛
    responses = dict(base_responses)
    responses["/actions/runs"] = {"workflow_runs": [
        _run(111, "CI", "completed", "failure", "2026-11-22T03:00:00Z")]}
    r = evaluate(StubApi(responses), "pushsha", "CI", "push", MAIN_REF_PREFIX)
    check("PR 侧运行失败 ⇒ 不收敛", r["converge"] is False, r["reason"])

    # ⑧ PR 侧没有该工作流的运行 ⇒ 不收敛
    responses = dict(base_responses)
    responses["/actions/runs"] = {"workflow_runs": [
        _run(111, "Security", "completed", "success", "2026-11-22T03:00:00Z")]}
    r = evaluate(StubApi(responses), "pushsha", "CI", "push", MAIN_REF_PREFIX)
    check("PR 侧无该工作流运行 ⇒ 不收敛", r["converge"] is False, r["reason"])

    # ⑨ 同名多条取最新一条：最新失败 ⇒ 不收敛
    responses = dict(base_responses)
    responses["/actions/runs"] = {"workflow_runs": [
        _run(110, "CI", "completed", "success", "2026-11-22T02:00:00Z"),
        _run(112, "CI", "completed", "failure", "2026-11-22T04:00:00Z")]}
    r = evaluate(StubApi(responses), "pushsha", "CI", "push", MAIN_REF_PREFIX)
    check("同名多条取最新一条（最新失败 ⇒ 不收敛）", r["converge"] is False, r["reason"])

    # ⑩ 同名多条取最新一条：最新成功 ⇒ 收敛
    responses = dict(base_responses)
    responses["/actions/runs"] = {"workflow_runs": [
        _run(110, "CI", "completed", "failure", "2026-11-22T02:00:00Z"),
        _run(112, "CI", "completed", "success", "2026-11-22T04:00:00Z")]}
    r = evaluate(StubApi(responses), "pushsha", "CI", "push", MAIN_REF_PREFIX)
    check("同名多条取最新一条（最新成功 ⇒ 收敛）", r["converge"] is True, r["reason"])

    # ⑪ 取数异常 ⇒ fail-closed 不收敛
    r = evaluate(StubApi({}), "pushsha", "CI", "push", MAIN_REF_PREFIX)
    check("取数异常 ⇒ fail-closed 不收敛", r["converge"] is False, r["reason"])

    # ⑫ 输出协议：$GITHUB_OUTPUT 必须写出 converge
    import tempfile
    out_file = os.path.join(tempfile.gettempdir(), "ci-push-converge-selftest.out")
    if os.path.exists(out_file):
        os.remove(out_file)
    os.environ["GITHUB_OUTPUT"] = out_file
    try:
        _emit("converge", "false")
        content = open(out_file, "r", encoding="utf-8").read()
        check("写出 converge=false", "converge=false" in content, content.strip())
    finally:
        os.environ.pop("GITHUB_OUTPUT", None)

    # ── 接线断言（作业内部收敛：作业照常跑、照常出结论）──────────────────
    print("-- 接线断言（收敛闸落在作业内部，required check 不缺失）--")
    for path in workflow_paths:
        name = os.path.basename(path)
        if not os.path.exists(path):
            check(f"{name} 存在", False, path)
            continue
        text = open(path, "r", encoding="utf-8").read()
        check(f"{name} 接线 scripts/ci-push-converge.py",
              "scripts/ci-push-converge.py" in text, "收敛闸脚本")
        check(f"{name} 的收敛闸有 step id: converge",
              "id: converge" in text, "step id")
        check(f"{name} 的重活受 steps.converge.outputs.converge 门控",
              "steps.converge.outputs.converge" in text, "门控条件")

    print()
    print(f"self-test: {passed} PASS / {len(failures)} FAIL（共 {passed + len(failures)} 项）")
    for line in failures:
        print(f"FAIL {line}")
    return 0 if not failures else 1


def _parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="push 扇出侧收敛闸")
    parser.add_argument("--self-test", action="store_true", help="运行离线自测")
    parser.add_argument("--workflow", default="", help="本工作流名（workflow_run.name）")
    parser.add_argument("--workflows", default="",
                        help="接线断言用的 workflow 文件路径（逗号分隔）")
    parser.add_argument("--legacy-ref", default="",
                        help="红侧对照：对该 ref 的四个 workflow 做接线断言")
    return parser.parse_args(argv)


def main(argv: List[str]) -> int:
    args = _parse_args(argv)
    if args.self_test:
        here = os.path.dirname(os.path.abspath(__file__))
        root = os.path.normpath(os.path.join(here, ".."))
        paths = [os.path.join(root, ".github", "workflows", p) for p in
                 ("ci.yml", "codeql.yml", "security.yml", "sonarqube.yml")]
        if args.workflows:
            paths = [p.strip() for p in args.workflows.split(",") if p.strip()]
        if args.legacy_ref:
            paths = _materialize_legacy(args.legacy_ref)
            print(f"   （红侧：用 git 取出 {args.legacy_ref[:12]} 的四个 workflow 做对照）")
        return _self_test(paths)
    if not args.workflow:
        print("::error::缺 --workflow（本工作流名）", file=sys.stderr)
        return 2
    return run_ci_mode(args.workflow)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
