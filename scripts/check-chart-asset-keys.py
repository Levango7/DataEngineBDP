#!/usr/bin/env python3
"""守护 ConfigMap 资产键名规则（防 KNOWN-FAILURES #36 同类缺陷回归）。

背景：#36 —— ConfigMap data 键含 "/" 会被 API Server 拒绝（键字符集仅
[-._a-zA-Z0-9]+），而行业模板 chart 惯用 ".Files.Glob 路径直接当键"。修法是在
键名表达式里把 "/" 替换为 "_"，并用卷投射 items.path 还原目录结构。

本检查扫描两处 finance-template chart（发布权威 design/deploy/charts 与
CI 门禁侧 platform/industry-templates/charts，以及两棵树内全部 chart）：
  1. ConfigMap data 键生成行：`{{ $path ... }}: |-` → 表达式必须含 `replace "/"`
  2. 卷投射 items 的 `- key: {{ $path ... }}` → 同样必须含 `replace "/"`

退出码：0=通过；1=存在违规。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
CHART_ROOTS = [
    REPO_ROOT / "design" / "deploy" / "charts",
    REPO_ROOT / "platform" / "industry-templates" / "charts",
]

# ConfigMap data 键生成行（键名来自 $path 表达式，值为块标量 |- 或 |- 后的内容）
KEY_LINE_RE = re.compile(r"^\s*\{\{\s*\$path[^}]*\}\}\s*:\s*\|")
# 卷投射 items 里的 key 行
ITEMS_KEY_RE = re.compile(r"^\s*-\s*key:\s*\{\{\s*\$path[^}]*\}\}")


def offenders() -> list[str]:
    out: list[str] = []
    for root in CHART_ROOTS:
        if not root.is_dir():
            continue
        for f in sorted(root.rglob("*")):
            if not f.is_file() or f.suffix not in (".yaml", ".yml", ".tpl"):
                continue
            for i, line in enumerate(f.read_text(encoding="utf-8", errors="ignore").split("\n"), 1):
                if KEY_LINE_RE.match(line) or ITEMS_KEY_RE.match(line):
                    if 'replace "/"' not in line:
                        out.append(f"{f.relative_to(REPO_ROOT).as_posix()}:{i}: {line.strip()}")
    return out


def main() -> int:
    bad = offenders()
    if bad:
        print(f"FAIL: 发现 {len(bad)} 处 ConfigMap 键未对 \"/\" 做替换（键会含 \"/\"，API Server 将拒绝）:")
        for b in bad:
            print(f"  - {b}")
        print("  修法：键名表达式加 `| replace \"/\" \"_\"`，并用 items.path 还原目录结构")
        return 1
    print("OK: chart 资产键名规则一致（无含 \"/\" 的 ConfigMap 键生成）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
