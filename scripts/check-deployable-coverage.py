#!/usr/bin/env python3
"""校验「每个可构建组件都有部署单元」，缺口只减不增。

背景（立项时口径，2026-10-06 提交 f2f0e0e1 当时）：仓库里有 13 个组件既没有同名
Helm Chart，其镜像名也没出现在任何 Chart / k3s manifest 中 ⇒ 只能在 docker-compose
集成拓扑里跑起来，生产形态无法部署。这类缺口此前只写在台账里，没有机器校验，于是
"新增组件却忘了配 Chart"完全不会报警（治理链的 real-time-pipeline 即此例：
跨进程血缘边接好了，但集群里根本没有这个东西）。

现状（2026-11-16 当轮实测，取数 git ls-files "**/Dockerfile"）：全仓 53 个 Dockerfile
文件 / 53 个目录 ⇒ platform/ 口径 44 个组件目录，扣 2 个非服务单元（见
NON_SERVICE_UNITS）后 = 42 个可构建组件，缺口 0 个；docs/deployable-backlog.yaml 的
uncovered: 自 2026-10-11 起为空（当日 platform/ 下 38 个可构建组件全部具备部署单元，
38 = 当日 platform/ 40 个目录 − 2 个非服务单元，见该文件注释）。

判定口径（与 docs/deployable-backlog.yaml 一致）：
  覆盖 = 存在 design/deploy/charts/<组件名>/，或镜像名 shuqing/<组件名>、sq/<组件名>
         出现在 design/deploy/charts/** 或 deploy/k3s/manifests/** 里。
  组件名取 Dockerfile 所在目录的 basename；同一目录下多个 Dockerfile 只记一次。

退出码：
  0 = 缺口集合与 backlog 完全一致（既不新增，也不留下"已补好却还挂在名单上"的过期项）
  1 = 出现新缺口，或 backlog 里有已闭环未销账的条目
  2 = 取数失败（git 不可用 / 非 git 目录 / git 报错 / 组件清单为空）⇒ fail-closed，绝不判绿
用法：python3 scripts/check-deployable-coverage.py [--write-backlog]
      python3 scripts/check-deployable-coverage.py --self-test
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKLOG = os.path.join(REPO, "docs", "deployable-backlog.yaml")
CHART_ROOT = os.path.join("design", "deploy", "charts")
K3S_ROOT = os.path.join("deploy", "k3s", "manifests")
SCAN_SUFFIXES = (".yaml", ".yml", ".tpl", ".json")

# 非服务单元（2026-10-11 台账 #2 证据订正后重分类）：docker/ 子目录的辅助/测试镜像，
# 属测试拓扑资产（CI 集群测试腿 / karmada 多集群模拟成员），**不适用生产 chart 口径**，
# 文件与 docker-compose 用法全部保留——只是不进"生产部署缺口"清单。
# 例：batch-pipeline/docker/spark-cluster=CI 集群测试腿专跑拓扑（ci.yml:1637 注释）；
#     karmada/docker/mock-cluster=karmada 多集群模拟成员、federated-query 远端测试桩（ROADMAP:287）。
NON_SERVICE_UNITS = {
    "platform/batch-pipeline/docker/spark-cluster",
    "platform/karmada/docker/mock-cluster",
}


class GitListError(RuntimeError):
    """git ls-files 取数失败。

    为什么必须抛异常而不是让空列表自然流下去：
      旧实现只取 subprocess 的 stdout、**不检 returncode**。git 一旦失败（非 git 目录、
      git 不在 PATH、索引损坏、权限不足……），stdout 为空 ⇒ buildable_components()
      返回 [] ⇒ 「含 Dockerfile 的组件 0 个」「OK: 部署单元缺口与登记清单一致」
      且退出码 0 ⇒ **假绿**。而本脚本是 ci.yml 的**阻断门禁**
      （ci.yml `run: python3 scripts/check-deployable-coverage.py`），
      假绿意味着"新增组件却忘了配 Chart"这条告警在最需要它的时刻静默失效。
      取数失败的代价不对称：误判「无缺口」= 失去唯一告警；误判「有缺口」= 多查一次。
      故一律 fail-closed（退出码 2）。
    """


def _git_files(pattern: str, repo: str = REPO) -> list:
    """列出匹配 pattern 的受版本控制文件。

    git 失败时抛 GitListError（不再静默返回空列表）。
    """
    try:
        proc = subprocess.run(
            ["git", "ls-files", pattern],
            cwd=repo,
            capture_output=True,
            text=True,
        )
    except (OSError, FileNotFoundError) as exc:
        raise GitListError(f"无法执行 git ls-files（git 不可用？）：{exc}") from exc
    if proc.returncode != 0:
        raise GitListError(
            f"git ls-files {pattern} 失败（rc={proc.returncode}）："
            f"{(proc.stderr or '').strip() or '（无 stderr 输出）'}"
        )
    return sorted(set(proc.stdout.split()))


def _components_from_paths(paths: list) -> list:
    """由 Dockerfile 路径列表算出可构建组件目录。

    空结果一律当作**取数异常**抛错：git 返回 0 但匹配为空（例如 pattern 写错、
    仓库被清空索引）时，若放行就会落进「0 组件 / 0 缺口 ⇒ OK」的假绿分支。
    """
    dirs = {
        os.path.dirname(p).replace("\\", "/")
        for p in paths
        if p.replace("\\", "/").startswith("platform/")
    }
    result = sorted(d for d in dirs if d and d not in NON_SERVICE_UNITS)
    if not result:
        raise GitListError(
            "未发现任何含 Dockerfile 的可构建组件（git 返回成功但匹配为空）"
        )
    return result


def buildable_components() -> list:
    """platform/ 下含 Dockerfile 的组件目录（相对仓库根，用 / 分隔）。

    口径只取 platform/：design/planned/** 是规划组件（未立项部署）、frontend 由
    Chart 里的静态资源/nginx 镜像承接、ske/node-image 是节点基座镜像，
    把它们混进"服务组件清单"会让分母失真（2026-11-16 当轮实测：全仓 53 个目录 vs
    platform/ 口径 44 个 ⇒ 扣 2 个非服务单元后 42 个服务组件）。
    """
    return _components_from_paths(_git_files("**/Dockerfile"))


def deployment_surface() -> str:
    """Chart 与 k3s 清单的全部文本，用于搜镜像名。"""
    chunks = []
    for root in (os.path.join(REPO, CHART_ROOT), os.path.join(REPO, K3S_ROOT)):
        for dirpath, _, files in os.walk(root):
            for name in files:
                if name.endswith(SCAN_SUFFIXES):
                    path = os.path.join(dirpath, name)
                    try:
                        with open(path, encoding="utf-8", errors="replace") as fh:
                            chunks.append(fh.read())
                    except OSError:
                        continue
    return "\n".join(chunks)


def chart_names() -> set:
    root = os.path.join(REPO, CHART_ROOT)
    if not os.path.isdir(root):
        return set()
    return {
        d
        for d in os.listdir(root)
        if os.path.isdir(os.path.join(root, d))
        and not d.startswith("_")
        and os.path.exists(os.path.join(root, d, "Chart.yaml"))
    }


def classify(components: list) -> tuple:
    surface = deployment_surface()
    charts = chart_names()
    covered, missing = [], []
    for comp in components:
        name = comp.rsplit("/", 1)[-1]
        if name in charts or re.search(rf"(shuqing|sq)/{re.escape(name)}[:\s\"]", surface):
            covered.append(comp)
        else:
            missing.append(comp)
    return covered, missing


def read_backlog() -> list:
    if not os.path.exists(BACKLOG):
        return []
    with open(BACKLOG, encoding="utf-8") as fh:
        text = fh.read()
    block = re.search(r"^uncovered:\n((?:[ \t]*-.*\n)+)", text, re.M)
    if not block:
        return []
    return [re.sub(r"^[ \t]*-[ \t]*", "", ln).strip().strip('"')
            for ln in block.group(1).splitlines() if ln.strip().startswith("-")]


def write_backlog(missing: list) -> None:
    lines = [
        "# 可构建但无部署单元的组件（scripts/check-deployable-coverage.py --write-backlog 生成）",
        "# 含义：该组件有 Dockerfile，但没有同名 Chart，镜像名也不在任何 Chart/k3s 清单里",
        "#       ⇒ 只能在 tests/integration/docker-compose.yml 的测试拓扑里跑，生产不可部署。",
        "# 销账条件：补齐 Chart 或在 k3s manifest 中引用其镜像后，重跑脚本从本名单移除。",
        "uncovered:",
    ]
    lines += [f'  - "{m}"' for m in missing]
    with open(BACKLOG, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")


def self_test() -> int:
    """离线自测：证明取数失败确实 fail-closed（不会落进「0 组件 / 0 缺口 ⇒ OK」的假绿分支）。

    不碰网络、不改仓库状态，秒级完成。断言分五组：
      ① 取数失败路径（子进程层 OSError：工作目录不存在）；
      ② 取数失败路径（git 层 returncode≠0：GIT_DIR 指向不存在路径 ⇒ git 确定性返回 128）；
      ③ 正对照（仓内取数成功且非空）；
      ④ 空组件清单路径；
      ⑤ main() 的退出码契约（取数失败 ⇒ 2；正常路径 ⇒ 0 或 1）+ ci.yml 挂载点接线。
    """
    import unittest.mock as mock

    passed = 0
    failed = 0

    def check(desc: str, cond: bool, detail: str = "") -> None:
        nonlocal passed, failed
        if cond:
            passed += 1
            print(f"  ✓ {desc}" + (f" ⇒ {detail}" if detail else ""))
        else:
            failed += 1
            print(f"  ✗ {desc}" + (f" ⇒ {detail}" if detail else ""))

    print("-- 取数失败必须抛错（子进程层 OSError：工作目录不存在）--")
    missing_dir = os.path.join(REPO, ".__no_such_dir_for_self_test__")
    try:
        _git_files("**/Dockerfile", repo=missing_dir)
        check("在不存在的工作目录下取数必须抛 GitListError", False, "未抛错")
    except GitListError as exc:
        check("在不存在的工作目录下取数必须抛 GitListError", True, str(exc)[:90])

    # 这一条覆盖的是**核心修复本身**：git 成功启动但返回非 0（如 GIT_DIR 失效、索引损坏）。
    # 只测「工作目录不存在」是不够的——那条走的是 subprocess 的 OSError 分支，
    # 把 returncode 检查整段删掉，它照样绿 ⇒ 门禁会退回「取不到组件 ⇒ 判绿」的假绿形态。
    # GIT_DIR 指向不存在的路径时，git 在任何合法 cwd 下都确定性地返回 128。
    print("-- 取数失败必须抛错（git 层 returncode≠0：GIT_DIR 失效）--")
    bogus_git_dir = os.path.join(REPO, ".__no_such_git_dir_for_self_test__")
    with mock.patch.dict(os.environ, {"GIT_DIR": bogus_git_dir}):
        try:
            _git_files("**/Dockerfile")
            check("git 返回非 0 时必须抛 GitListError（returncode 分支）", False, "未抛错")
        except GitListError as exc:
            check("git 返回非 0 时必须抛 GitListError（returncode 分支）", True, str(exc)[:90])

    print("-- 正对照：仓内正常取数必须能取到 Dockerfile --")
    try:
        paths = _git_files("**/Dockerfile")
        check("仓内取数成功且非空", len(paths) > 0, f"实测 {len(paths)} 条")
    except GitListError as exc:
        check("仓内取数成功且非空", False, str(exc)[:90])

    print("-- 组件清单为空必须当作取数异常（不得判绿）--")
    try:
        _components_from_paths([])
        check("空组件清单必须抛 GitListError", False, "未抛错")
    except GitListError as exc:
        check("空组件清单必须抛 GitListError", True, str(exc)[:90])

    print("-- main() 退出码契约 --")
    with mock.patch.object(
        sys.modules[__name__],
        "buildable_components",
        side_effect=GitListError("自测注入：模拟 git 取数失败"),
    ):
        rc_failed = main([])
    check("取数失败时 main() 必须返回 2", rc_failed == 2, f"实测 rc={rc_failed}")

    rc_ok = main([])
    check("正常路径 main() 必须返回 0 或 1（不得是 2）", rc_ok in (0, 1), f"实测 rc={rc_ok}")

    print("-- ci.yml 接线：自测必须在真判定之前跑，否则本文件等于没人守 --")
    ci_path = os.path.join(REPO, ".github", "workflows", "ci.yml")
    try:
        with open(ci_path, encoding="utf-8") as fh:
            ci_lines = fh.read().splitlines()
    except OSError as exc:
        check("ci.yml 必须挂载 --self-test 且在真判定之前", False, f"读取失败：{exc}")
    else:
        idx_self = [
            i for i, ln in enumerate(ci_lines)
            if "check-deployable-coverage.py" in ln and "--self-test" in ln
        ]
        idx_plain = [
            i for i, ln in enumerate(ci_lines)
            if "check-deployable-coverage.py" in ln and "--self-test" not in ln
        ]
        ok = bool(idx_self) and bool(idx_plain) and idx_self[0] < idx_plain[0]
        detail = (
            f"--self-test@L{idx_self[0] + 1} / 真判定@L{idx_plain[0] + 1}"
            if ok
            else f"--self-test 命中 {len(idx_self)} 处、真判定命中 {len(idx_plain)} 处"
        )
        check("ci.yml 必须挂载 --self-test 且在真判定之前", ok, detail)

    print()
    print(f"自测结果：通过 {passed} 项 / 失败 {failed} 项")
    return 1 if failed else 0


def main(argv: list = None) -> int:
    argv = sys.argv[1:] if argv is None else argv

    try:
        components = buildable_components()
    except GitListError as exc:
        # fail-closed：取数失败 ⇒ 退出码 2，绝不落进「0 组件 / 0 缺口 ⇒ OK」的假绿分支。
        print(f"FAIL: 取数失败，无法判定部署单元覆盖率：{exc}", file=sys.stderr)
        print(
            "（fail-closed：git 不可用时不能把「取不到组件」当成「没有缺口」）",
            file=sys.stderr,
        )
        return 2

    covered, missing = classify(components)
    print(f"含 Dockerfile 的组件 {len(components)} 个：有部署单元 {len(covered)}，无部署单元 {len(missing)}")

    if "--write-backlog" in argv:
        write_backlog(missing)
        print(f"已写入 {os.path.relpath(BACKLOG, REPO)}（{len(missing)} 条）")
        return 0

    backlog = read_backlog()
    new_gaps = [m for m in missing if m not in backlog]
    stale = [b for b in backlog if b not in missing]

    for m in missing:
        tag = "新缺口" if m in new_gaps else "已登记"
        print(f"  [{tag}] {m}")
    for b in stale:
        print(f"  [已闭环未销账] {b} —— 请从 docs/deployable-backlog.yaml 移除")

    if new_gaps or stale:
        print(
            f"FAIL: 新缺口 {len(new_gaps)} 个，过期登记 {len(stale)} 个"
            f"（补 Chart 后用 --write-backlog 重新生成名单）"
        )
        return 1
    print("OK: 部署单元缺口与登记清单一致（只减不增）")
    return 0


if __name__ == "__main__":
    _argv = sys.argv[1:]
    if "--self-test" in _argv:
        sys.exit(self_test())
    sys.exit(main(_argv))
