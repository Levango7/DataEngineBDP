#!/usr/bin/env python3
"""统计仓库内 mermaid 块的类型与样式使用情况（图样规范的事实依据）。

用法：python3 scripts/mermaid-inventory.py [--json]
口径：扫描全仓 *.md（排除 node_modules / .gstack-tmp / target），
按 ```mermaid 围栏切块，取块内首个非注释行作为图类型。
"""

from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
EXCLUDE_PARTS = ("node_modules", ".gstack-tmp", "target", "dist", "test-results")

FENCE = re.compile(r"^```mermaid\s*$")
TYPE_RE = re.compile(
    r"^(graph|flowchart|sequenceDiagram|erDiagram|classDiagram|stateDiagram"
    r"(?:-v2)?|journey|gantt|pie|mindmap|timeline|quadrantChart|C4(?:Context|Container|"
    r"Component|Dynamic|Deployment)|requirementDiagram|gitGraph|packet-beta|block-beta)\b"
)
INIT_RE = re.compile(r"%%\{\s*init")
CLASSDEF_RE = re.compile(r"\bclassDef\s+([A-Za-z][\w-]*)")
STYLE_RE = re.compile(r"\bstyle\s+\S+")
THEMECONF_RE = re.compile(r'"themeVariables"')


def iter_blocks():
    for path in ROOT.rglob("*.md"):
        rel = path.relative_to(ROOT).as_posix()
        if any(p in EXCLUDE_PARTS for p in rel.split("/")[:-1]) or any(f"/{x}/" in f"/{rel}" for x in EXCLUDE_PARTS):
            continue
        try:
            lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
        except OSError:
            continue
        i = 0
        while i < len(lines):
            if FENCE.match(lines[i].strip()):
                start = i + 1
                j = start
                while j < len(lines) and not lines[j].strip().startswith("```"):
                    j += 1
                yield rel, lines[start:j]
                i = j
            i += 1


def classify(body: list[str]) -> str:
    for line in body:
        s = line.strip()
        if not s or s.startswith("%%"):
            continue
        m = TYPE_RE.match(s)
        if m:
            return m.group(1)
        return "(无类型行/缩进异常)"
    return "(空块)"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    types: collections.Counter[str] = collections.Counter()
    files: set[str] = set()
    with_init = 0
    with_classdef = 0
    with_style = 0
    total = 0
    classdefs: collections.Counter[str] = collections.Counter()
    bad_first_line = 0

    for rel, body in iter_blocks():
        total += 1
        files.add(rel)
        types[classify(body)] += 1
        text = "\n".join(body)
        if INIT_RE.search(text):
            with_init += 1
        else:
            # 首行不是 %%{init}%% 也不算错，只统计"完全没写主题"的数量
            pass
        cd = CLASSDEF_RE.findall(text)
        if cd:
            with_classdef += 1
            classdefs.update(cd)
        if STYLE_RE.search(text):
            with_style += 1
        first = next((ln.strip() for ln in body if ln.strip()), "")
        if first.startswith("```") or (first and not TYPE_RE.match(first) and not first.startswith("%%")):
            bad_first_line += 1

    payload = {
        "blocks": total,
        "files": len(files),
        "types": dict(types.most_common()),
        "with_init_theme": with_init,
        "with_classdef": with_classdef,
        "with_inline_style": with_style,
        "classdef_names": dict(classdefs.most_common(20)),
        "first_line_not_type_or_init": bad_first_line,
    }
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0

    print(f"mermaid 块：{total} 个，分布在 {len(files)} 个 .md 文件")
    print(f"带 %%%{{init}}%% 主题：{with_init}（{with_init * 100 // max(total, 1)}%）")
    print(f"用 classDef 的块：{with_classdef}；用行内 style 的块：{with_style}")
    print("按类型：")
    for k, v in types.most_common():
        print(f"  {v:5d}  {k}")
    if classdefs:
        print("已存在的 classDef 名：", ", ".join(f"{k}×{v}" for k, v in classdefs.most_common(15)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
