#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""硬编码色棘轮：新增裸十六进制色判红，减掉允许（并把基线往下压）。

为什么需要它：DESIGN.md 定了"只用 --ds-* 令牌"，但没有任何东西阻止下一个 PR
再写一个 `#1677ff`。历史上 5 个独立子看板就是这么攒出 93 处裸色的。

口径：
  - 扫描 `frontend/**/*.vue` 与 `frontend/src/styles/*.css` 里的 3/6 位十六进制色；
  - 定义令牌本身的文件豁免（design-tokens.css / theme.css / contract-v2.css）——
    那里就是该写十六进制的地方；
  - 计数与 docs/design-hardcoded-color-baseline.json 比对：超过即判红。
发现集为空（一个文件都没扫到）同样判红，避免路径写错导致"永远通过"。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

HEX = re.compile(r"#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}\b")
EXEMPT = {"design-tokens.css", "theme.css", "contract-v2.css"}


def scan(root: Path) -> dict[str, int]:
    """返回 {posix 相对路径: 裸色数量}。路径统一用正斜杠——基线文件要在
    Windows 本机与 Linux runner 之间可比，否则会因分隔符产生假差异。"""
    counts: dict[str, int] = {}
    for p in sorted(root.rglob("*.vue")):
        if "node_modules" in p.parts:
            continue
        n = len(HEX.findall(p.read_text(encoding="utf-8", errors="ignore")))
        if n:
            counts[p.relative_to(root).as_posix()] = n
    for p in sorted((root / "src" / "styles").glob("*.css")):
        if p.name in EXEMPT:
            continue
        n = len(HEX.findall(p.read_text(encoding="utf-8", errors="ignore")))
        if n:
            counts[p.relative_to(root).as_posix()] = n
    return counts


def self_test() -> int:
    import tempfile

    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        (root / "src" / "styles").mkdir(parents=True)
        (root / "src" / "views").mkdir(parents=True)
        (root / "src" / "views" / "A.vue").write_text("<style>.x{color:#ff0000}</style>", encoding="utf-8")
        (root / "src" / "styles" / "design-tokens.css").write_text(":root{--ds-a:#001122}", encoding="utf-8")
        got = scan(root)
        ok_scan = got == {"src/views/A.vue": 1}
        print(f"[{'PASS' if ok_scan else 'FAIL'}] 夹具：数到 {got}，期望只算 A.vue 的 1 处、令牌文件豁免")
        # 负例：路径写错 ⇒ 发现集为空 ⇒ 必须能被上层判红（这里只断言 scan 返回空）
        empty = scan(root / "nope")
        print(f"[{'PASS' if empty == {} else 'FAIL'}] 夹具：不存在的路径返回空集合（上层据此判红）")
        return 0 if ok_scan and empty == {} else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--frontend-dir", default=None)
    ap.add_argument("--baseline", default=None)
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--write", action="store_true", help="把当前计数写回基线（清偿后使用，只允许下调）")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    repo = Path(__file__).resolve().parents[1]
    frontend = Path(args.frontend_dir) if args.frontend_dir else repo / "frontend"
    baseline_path = Path(args.baseline) if args.baseline else repo / "docs/design-hardcoded-color-baseline.json"

    counts = scan(frontend)
    total = sum(counts.values())
    if not counts:
        print(f"::error::一个十六进制色都没扫到（root={frontend}）⇒ 扫描路径或口径失效，按发现集为空判红")
        return 1

    if not baseline_path.exists():
        print(f"::error::缺基线文件 {baseline_path}")
        return 1
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    base_total = int(baseline.get("total", 0))

    print(f"硬编码色：现 {total} / 基线 {base_total}（文件数 {len(counts)}）")
    if args.write:
        if total > base_total:
            print("::error::只允许下调基线"); return 1
        baseline_path.write_text(json.dumps({"total": total, "by_file": counts}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"已把基线压到 {total}")
        return 0
    if total > base_total:
        worst = sorted(counts.items(), key=lambda kv: -kv[1])[:8]
        print("::error::新增硬编码色，违反 DESIGN.md §10 第 3 条。Top 文件：")
        for f, n in worst:
            print(f"   {n:>4}  {f}")
        return 1
    if total < base_total:
        print(f"::notice::已清偿 {base_total - total} 处，可跑 `--write` 下调基线")
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
