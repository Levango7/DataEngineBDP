#!/usr/bin/env python3
"""Mermaid 图样样式门禁（配套 docs/图样规范.md）。

三件事：
  1. 类名白名单：块内 classDef / :::class 只允许规范登记的语义类，防止各文档自造配色；
  2. 对比度复算：按 WCAG 重算配色（文本/填充 ≥4.5:1，描边 ≥3:1），不达标即失败；
  3. 棘轮：统计"未带主题块的 Mermaid 块"总数，只允许下降不允许上升 ——
     现存 189 个历史块不做一次性改写，但新写的每张图必须合规。

用法：
  python3 scripts/check-mermaid-style.py                # 校验（CI 用）
  python3 scripts/check-mermaid-style.py --update       # 收敛后下调基线
  python3 scripts/check-mermaid-style.py --contrast     # 只打印对比度实测表
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "docs" / "mermaid-style-baseline.json"
EXCLUDE = ("node_modules", ".gstack-tmp", "target", "dist", "test-results")

# 规范第 4 节的词汇表：类名 -> (填充, 描边)。改这里必须同步改 docs/图样规范.md。
CLASSES: dict[str, tuple[str, str]] = {
    "ui": ("#eef4ff", "#2563eb"),
    "gw": ("#e7f2f8", "#0b6e99"),
    "svc": ("#ffffff", "#7c8695"),
    "exp": ("#fdf3e3", "#b45309"),
    "planned": ("#f5f7fb", "#5f6875"),
    "engine": ("#e6f6ee", "#0e7a4a"),
    "store": ("#eef1f6", "#454d5a"),
    "sec": ("#f1ecfb", "#6b3fa0"),
    "obs": ("#e2f3f4", "#0f766e"),
    "ext": ("#f4f4f5", "#8a5a00"),
}
TEXT_PRIMARY = "#16181d"  # --ds-text-primary
WHITE = "#ffffff"

FENCE = re.compile(r"^```mermaid\s*$")
INIT_RE = re.compile(r"^%%\{\s*init\s*:", re.IGNORECASE)
CLASSDEF_RE = re.compile(r"\bclassDef\s+([A-Za-z][\w-]*)")
APPLY_RE = re.compile(r":::([A-Za-z][\w-]*)")


def _lin(c: float) -> float:
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def contrast(a: str, b: str) -> float:
    """WCAG 2.x 相对亮度对比度。"""

    def lum(h: str) -> float:
        v = h.lstrip("#")
        r, g, bl = (int(v[i : i + 2], 16) / 255 for i in (0, 2, 4))
        return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(bl)

    l1, l2 = sorted((lum(a), lum(b)), reverse=True)
    return (l1 + 0.05) / (l2 + 0.05)


def check_contrast() -> list[str]:
    errs = []
    for name, (fill, stroke) in CLASSES.items():
        c_text = contrast(TEXT_PRIMARY, fill)
        c_stroke = min(contrast(stroke, WHITE), contrast(stroke, fill))
        if c_text < 4.5:
            errs.append(f"{name}: 文本/填充对比度 {c_text:.2f}:1 < 4.5:1")
        if c_stroke < 3.0:
            errs.append(f"{name}: 描边对比度 {c_stroke:.2f}:1 < 3:1（WCAG 1.4.11）")
    return errs


def print_contrast() -> None:
    print(f"{'类名':<9} {'填充':<9} {'描边':<9} {'文本/填充':>9} {'描边(最弱)':>10}")
    for name, (fill, stroke) in CLASSES.items():
        ct = contrast(TEXT_PRIMARY, fill)
        cs = min(contrast(stroke, WHITE), contrast(stroke, fill))
        flag = "" if (ct >= 4.5 and cs >= 3.0) else "  <-- 不达标"
        print(f"{name:<9} {fill:<9} {stroke:<9} {ct:8.2f}:1 {cs:9.2f}:1{flag}")


def iter_blocks():
    for path in sorted(ROOT.rglob("*.md")):
        rel = path.relative_to(ROOT).as_posix()
        if any(seg in EXCLUDE for seg in rel.split("/")[:-1]):
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
                yield rel, start, lines[start:j]
                i = j
            i += 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--update", action="store_true", help="把当前未样式化块数写为基线")
    ap.add_argument("--contrast", action="store_true", help="只打印对比度实测表")
    args = ap.parse_args()

    if args.contrast:
        print_contrast()
        errs = check_contrast()
        print("\n全部达标" if not errs else "\n" + "\n".join(errs))
        return 1 if errs else 0

    errors: list[str] = []
    unstyled = 0
    total = 0

    for rel, line_no, body in iter_blocks():
        total += 1
        text = "\n".join(body)
        first = next((ln.strip() for ln in body if ln.strip()), "")
        styled = bool(INIT_RE.match(first))
        if not styled:
            unstyled += 1
        for cls in set(CLASSDEF_RE.findall(text)) | set(APPLY_RE.findall(text)):
            if cls not in CLASSES:
                errors.append(
                    f"{rel}:{line_no}: 未登记的类名 '{cls}' —— 需先在 docs/图样规范.md "
                    "第 4 节登记（含对比度实测）再使用"
                )

    errors.extend(check_contrast())

    baseline = None
    if BASELINE.is_file():
        try:
            baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            errors.append(f"{BASELINE.name}: JSON 解析失败")

    if args.update:
        BASELINE.write_text(
            json.dumps(
                {"unstyled_blocks": unstyled, "total_blocks": total},
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        print(f"基线已写入：未样式化 {unstyled} / 共 {total}")
        return 1 if errors else 0

    if baseline is None:
        print(
            f"NOTE: 无基线文件（{BASELINE.name}）。当前未样式化 {unstyled}/{total}。"
            "先跑 --update 建立基线，此后只允许下降。"
        )
    else:
        allowed = int(baseline.get("unstyled_blocks", 0))
        if unstyled > allowed:
            errors.append(
                f"未样式化 Mermaid 块由基线 {allowed} 增至 {unstyled}（棘轮只降不升）："
                "新增图必须带 docs/图样规范.md 第 3 节的 %%" + "{init}%% 主题块"
            )
        elif unstyled < allowed:
            print(f"GOOD: 未样式化块由基线 {allowed} 降至 {unstyled}，可跑 --update 收紧基线")

    if errors:
        print(f"FAIL: Mermaid 样式校验未过（{len(errors)} 项）", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    print(f"OK: {total} 个 Mermaid 块，类名与对比度均符合规范（未样式化 {unstyled} 个）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
