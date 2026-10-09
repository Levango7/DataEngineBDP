#!/usr/bin/env python3
"""finance-template 三副本归属与结构门禁（T018 冻结存档快照锁）。

背景（docs/KNOWN-FAILURES.md #52 及其后续裁决）：finance-template 在仓内有三处
副本，各自角色不同，历史上的"副本漂移"正是 #52 发布侧缺陷的温床：

1. design/deploy/charts/finance-template/    —— **发布权威**（release.yml 只打包
   design/deploy/charts/*），version 2.0.0，含 #36/#52 的修复；
2. platform/industry-templates/charts/finance-template/ —— **CI 门禁侧**
   （ci.yml 的 chart_roots 同时扫它，helm lint 生效），version 1.0.0，带
   dag/dashboards/ddl 等资产，允许与 design 副本存在元数据差异；
3. platform/industry-templates/templates/finance/helm/ —— **T018 冻结存档**
   （原始 Chart，其 configmap-assets.tpl 缺 #36 修复），**不进发布、不进任何
   扫描面**；本门禁对其做 sha256 快照锁定，防止它被无意改动后又没人说得清
   "到底哪份才是真的"。

判定口径：
- 三份 Chart.yaml 必须存在且 name == "finance-template"（结构存在性）；
- T018 六个文件的 sha256 与本脚本内嵌快照逐一比对；任何一处不等 = 有人动了
  冻结存档 = 失败，要么还原，要么走裁决改快照。
- 资产键名规则（#36）不由本脚本重复覆盖——那已由 scripts/check-chart-asset-keys.py
  独立守护（扫两份"活"副本）；本脚本只补"归属/存在性/冻结"这一层。两脚本的
  扫描面刻意不同，不要互相"对齐"。

退出码：
  0 = 全部通过
  1 = 有结构性缺失 / 快照漂移
  2 = 前提不成立（仓内根本找不到任何 finance-template 副本——防空集合冒充通过）

用法：
  python3 scripts/check-chart-asset-keys.py   # 资产键名（原有）
  python3 scripts/check-chart-ownership.py    # 三副本归属与快照（本脚本）
  python3 scripts/check-chart-ownership.py --self-test
"""

from __future__ import annotations

import hashlib
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

PUBLISH_CHART = ROOT / "design" / "deploy" / "charts" / "finance-template"
GATE_CHART = ROOT / "platform" / "industry-templates" / "charts" / "finance-template"
ARCHIVE_CHART = ROOT / "platform" / "industry-templates" / "templates" / "finance" / "helm"

# T018 冻结存档的字节级快照（sha256）。改这里 = 裁决，不是修复。
ARCHIVE_SNAPSHOT = {
    ".helmignore": "844f972cdcb3caa2a4c5fdbd44f23e6f9e6feb5fa4728e6f073efa7dd65bafb6",
    "Chart.yaml": "9024065c70fe727461771ac505337d623a301ba8b9b81f3999fa188e20addd38",
    "README.md": "60c546363ddc549cd64001ad92ada5dbc0aa6e5683cf2cda24f805304e94c389",
    "templates/configmap-assets.tpl": "e39d74c0972da7804dd9d79340787dffb3feb448b8471fd9db3f346abcd22439",
    "templates/import-job.tpl": "41bcca4124aef7c255e69da1a9dedf82ae34223f1df4a09239effdf59c1e388d",
    "values.yaml": "a464ee5ba78cd8c4b2335c670f2d8d93fa932a8828b345af1f2937ce1a3f7f70",
}

CHART_NAME = "finance-template"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_structure(chart_dir: Path) -> list[str]:
    errors: list[str] = []
    chart_yaml = chart_dir / "Chart.yaml"
    if not chart_yaml.is_file():
        errors.append(f"缺少 Chart.yaml：{chart_dir}")
        return errors
    name = None
    for line in chart_yaml.read_text(encoding="utf-8").splitlines():
        if line.startswith("name:"):
            name = line.split(":", 1)[1].strip()
            break
    if name != CHART_NAME:
        errors.append(f"Chart.yaml name={name!r}，期望 {CHART_NAME!r}：{chart_yaml}")
    return errors


def check_archive_snapshot() -> list[str]:
    errors: list[str] = []
    for rel, expected in ARCHIVE_SNAPSHOT.items():
        path = ARCHIVE_CHART / rel
        if not path.is_file():
            errors.append(f"冻结存档缺文件（结构被改？）：{path}")
            continue
        actual = sha256(path)
        if actual != expected:
            errors.append(
                f"冻结存档快照漂移：{rel} sha256={actual} 期望 {expected}"
            )
    return errors


def run_checks() -> int:
    charts = [PUBLISH_CHART, GATE_CHART, ARCHIVE_CHART]
    present = [c for c in charts if (c / "Chart.yaml").is_file()]
    if not present:
        print("前提不成立：仓内找不到任何 finance-template 副本", file=sys.stderr)
        return 2

    errors: list[str] = []
    for chart in charts:
        errors += check_structure(chart)
    errors += check_archive_snapshot()

    if errors:
        for e in errors:
            print(f"FAIL {e}", file=sys.stderr)
        return 1

    print(
        "check-chart-ownership: 三副本结构齐全（name="
        + CHART_NAME
        + "）、T018 冻结存档快照一致"
    )
    return 0


def self_test() -> int:
    """合成正反例：①当前仓应通过 ②篡改冻结存档文件必须判红 ③新增旁文件不算漂移 ④空集合 → 2。"""
    global PUBLISH_CHART, GATE_CHART, ARCHIVE_CHART

    ok = True

    # 正例：当前仓必须全绿（若这一步失败，后两个反例没有意义）
    if run_checks() != 0:
        print("SELF-TEST FAIL：当前仓应通过", file=sys.stderr)
        ok = False

    # 反例 1：改冻结存档一个字节 → 必须 1（用未在快照清单内的新文件放在清单内文件名之外，
    # 这里直接覆盖 values.yaml 再还原，避免依赖临时命名）
    victim = ARCHIVE_CHART / "values.yaml"
    original = victim.read_bytes()
    victim.write_bytes(original + b"\n# tampered\n")
    try:
        rc = run_checks()
        if rc != 1:
            print(f"SELF-TEST FAIL：篡改冻结存档后应 1，实得 {rc}", file=sys.stderr)
            ok = False
    finally:
        victim.write_bytes(original)

    # 反例 2：T018 新增旁文件不算漂移（快照只锁清单内六个文件）
    extra = ARCHIVE_CHART / "EXTRA_PROBE.txt"
    extra.write_text("extra", encoding="utf-8")
    try:
        rc = run_checks()
        if rc != 0:
            print(f"SELF-TEST FAIL：新增旁文件应仍 0，实得 {rc}", file=sys.stderr)
            ok = False
    finally:
        extra.unlink(missing_ok=True)

    # 反例 3：前提不成立 → 2（把三个 chart 路径指向空目录模拟空集合）
    with tempfile.TemporaryDirectory() as td:
        old = (PUBLISH_CHART, GATE_CHART, ARCHIVE_CHART)
        PUBLISH_CHART = Path(td) / "a"
        GATE_CHART = Path(td) / "b"
        ARCHIVE_CHART = Path(td) / "c"
        try:
            rc = run_checks()
            if rc != 2:
                print(f"SELF-TEST FAIL：空集合应 2，实得 {rc}", file=sys.stderr)
                ok = False
        finally:
            PUBLISH_CHART, GATE_CHART, ARCHIVE_CHART = old

    # 验证反例 1 已还原
    if run_checks() != 0:
        print("SELF-TEST FAIL：还原后应恢复全绿", file=sys.stderr)
        ok = False

    if ok:
        print("SELF-TEST PASS")
        return 0
    return 1


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    return run_checks()


if __name__ == "__main__":
    sys.exit(main())
