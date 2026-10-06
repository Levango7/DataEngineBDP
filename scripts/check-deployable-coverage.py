#!/usr/bin/env python3
"""校验「每个可构建组件都有部署单元」，缺口只减不增。

背景：本仓 37 个含 Dockerfile 的组件里，有 13 个既没有同名 Helm Chart，
其镜像名也没出现在任何 Chart / k3s manifest 中 ⇒ 只能在 docker-compose 集成拓扑里
跑起来，生产形态无法部署。这类缺口此前只写在台账里，没有机器校验，于是
"新增组件却忘了配 Chart"完全不会报警（治理链的 real-time-pipeline 即此例：
跨进程血缘边接好了，但集群里根本没有这个东西）。

判定口径（与 docs/deployable-backlog.yaml 一致）：
  覆盖 = 存在 design/deploy/charts/<组件名>/，或镜像名 shuqing/<组件名>、sq/<组件名>
         出现在 design/deploy/charts/** 或 deploy/k3s/manifests/** 里。
  组件名取 Dockerfile 所在目录的 basename；同一目录下多个 Dockerfile 只记一次。

退出码：
  0 = 缺口集合与 backlog 完全一致（既不新增，也不留下"已补好却还挂在名单上"的过期项）
  1 = 出现新缺口，或 backlog 里有已闭环未销账的条目
用法：python3 scripts/check-deployable-coverage.py [--write-backlog]
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


def _git_files(pattern: str) -> list:
    out = subprocess.run(
        ["git", "ls-files", pattern], cwd=REPO, capture_output=True, text=True
    ).stdout
    return sorted(set(out.split()))


def buildable_components() -> list:
    """platform/ 下含 Dockerfile 的组件目录（相对仓库根，用 / 分隔）。

    口径只取 platform/：design/planned/** 是规划组件（未立项部署）、frontend 由
    Chart 里的静态资源/nginx 镜像承接、ske/node-image 是节点基座镜像，
    把它们混进"服务组件清单"会让分母失真（实测全仓 46 个 vs platform/ 口径 37 个）。
    """
    dirs = {
        os.path.dirname(p).replace("\\", "/")
        for p in _git_files("**/Dockerfile")
        if p.replace("\\", "/").startswith("platform/")
    }
    return sorted(d for d in dirs if d)


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


def main() -> int:
    components = buildable_components()
    covered, missing = classify(components)
    print(f"含 Dockerfile 的组件 {len(components)} 个：有部署单元 {len(covered)}，无部署单元 {len(missing)}")

    if "--write-backlog" in sys.argv:
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
    sys.exit(main())
