#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""数擎控制台设计令牌对比度门禁（WCAG 2.1）。

事实源：frontend/src/styles/design-tokens.css（组件全部按 --ds-* 变量消费）。
本脚本不依赖任何外部技能，只做三件事：
  1. 解析 :root 与 :root[data-theme='dark'] 两个主题块；
  2. 按 DESIGN.md §2 声明的「角色配对」核算对比度（文本 4.5:1，可交互边界 3:1）；
  3. 发现集为空即判红——门禁不许对着空集合说"全部通过"。

用法：
  python3 scripts/check-design-contrast.py            # CI 口径
  python3 scripts/check-design-contrast.py --self-test # 夹具自检（含负例）
退出码：0 全通过 / 1 有失败或发现集为空 / 2 输入不可解析
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# 角色配对：(前景令牌, 背景令牌, 最低对比度, 用途)。与 DESIGN.md §2 一一对应。
PAIRS = [
    ("text-primary", "bg-base", 4.5, "页面主文本"),
    ("text-primary", "bg-surface", 4.5, "卡片主文本"),
    ("text-secondary", "bg-surface", 4.5, "次级文本"),
    ("text-tertiary", "bg-surface", 4.5, "辅助 / 元数据"),
    ("accent", "bg-base", 4.5, "链接 / 激活文字"),
    ("accent", "bg-surface", 4.5, "链接 / 激活文字（卡片内）"),
    ("accent-on", "accent", 4.5, "品牌实底按钮"),
    ("accent-on", "accent-hover", 4.5, "品牌实底 hover"),
    ("accent-on", "accent-active", 4.5, "品牌实底 active"),
    ("accent", "accent-soft", 4.5, "品牌浅底 Badge"),
    ("success", "success-soft", 4.5, "成功 Badge"),
    ("warning", "warning-soft", 4.5, "告警 Badge"),
    ("danger", "danger-soft", 4.5, "危险 Badge"),
    ("border-control", "bg-surface", 3.0, "可交互控件边界（1.4.11）"),
    ("border-strong", "bg-surface", 3.0, "强调边界（1.4.11）"),
    # v3 外壳：深色导航 + 专属底栏。侧栏实际填充是 --ds-nav-glass（94% 不透明），
    # 压在画布上比这里的实底基准 --ds-nav-bg 略浅，实测文字最低仍有 13.8:1、
    # 图标/指示条最低 7.1:1，都远高于此处门槛。
    ("nav-ink", "nav-bg", 4.5, "侧栏主文字 / 分组标题"),
    ("nav-muted", "nav-bg", 4.5, "侧栏次文字（菜单项常态）"),
    ("statusbar-ink", "statusbar-bg", 4.5, "底栏文字"),
    ("group-infra", "nav-bg", 3.0, "分组图标与 active 指示条（1.4.11）"),
    ("group-engine", "nav-bg", 3.0, "分组图标与 active 指示条（1.4.11）"),
    ("group-governance", "nav-bg", 3.0, "分组图标与 active 指示条（1.4.11）"),
    ("group-devtools", "nav-bg", 3.0, "分组图标与 active 指示条（1.4.11）"),
    ("group-tenant", "nav-bg", 3.0, "分组图标与 active 指示条（1.4.11）"),
    ("group-intelligent", "nav-bg", 3.0, "分组图标与 active 指示条（1.4.11）"),
    ("group-operations", "nav-bg", 3.0, "分组图标与 active 指示条（1.4.11）"),
    ("group-platform", "nav-bg", 3.0, "分组图标与 active 指示条（1.4.11）"),
]

# 少于这个数说明角色令牌被改名/删除，判红而不是空跑。
# v3 加了 11 条外壳配对（侧栏 / 底栏 / 8 组色），门槛随之抬到 24——
# 抬数是为了让"删掉一批配对"不能悄悄通过。
MIN_PAIRS_PER_THEME = 24

HEX_RE = re.compile(r"^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")


def parse_css(text: str) -> dict[str, dict[str, str]]:
    """返回 {"light": {...}, "dark": {...}}，值为十六进制色。"""
    themes: dict[str, dict[str, str]] = {"light": {}, "dark": {}}
    # 按块切：:root { ... } 与 :root[data-theme='dark'] { ... }
    for m in re.finditer(r":root(?:\[data-theme='dark'\])?\s*\{(.*?)\n\}", text, re.S):
        name = "dark" if "data-theme='dark'" in m.group(0) else "light"
        for line in m.group(1).splitlines():
            kv = re.match(r"\s*(--ds-[a-z0-9-]+):\s*([^;]+);", line)
            if not kv:
                continue
            token, raw = kv.group(1), kv.group(2).strip()
            if HEX_RE.match(raw):
                themes[name][token[5:]] = raw  # 去 --ds- 前缀
    # 暗色档继承浅色值：组件在暗色下未重写的令牌仍用浅色值
    themes["dark"] = {**themes["light"], **themes["dark"]}
    return themes


def channel(c: float) -> float:
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(hexcolor: str) -> float:
    h = hexcolor.lstrip("#")
    if len(h) == 3:
        h = "".join(ch * 2 for ch in h)
    r, g, b = (channel(int(h[i : i + 2], 16) / 255.0) for i in (0, 2, 4))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(fg: str, bg: str) -> float:
    l1, l2 = sorted((luminance(fg), luminance(bg)), reverse=True)
    return (l1 + 0.05) / (l2 + 0.05)


def check(tokens_css: Path) -> int:
    try:
        themes = parse_css(tokens_css.read_text(encoding="utf-8"))
    except OSError as exc:
        print(f"::error::读不到令牌文件：{exc}")
        return 2

    failed = 0
    for theme, table in themes.items():
        rows, skipped = [], []
        for fg_name, bg_name, minimum, use in PAIRS:
            fg, bg = table.get(fg_name), table.get(bg_name)
            if fg is None or bg is None:
                skipped.append(f"{fg_name} on {bg_name}（令牌缺失或值不是十六进制，无法核算）")
                continue
            got = ratio(fg, bg)
            rows.append((fg_name, bg_name, minimum, got, got >= minimum))

        if len(rows) < MIN_PAIRS_PER_THEME:
            print(f"::error::{theme}: 只核算到 {len(rows)} 组配对（下限 {MIN_PAIRS_PER_THEME}）"
                  f"⇒ 角色令牌可能被改名或删除，按发现集不完整判红，不给『通过』")
            failed += 1
        print(f"-- {theme}: {sum(1 for r in rows if r[4])} pass / {sum(1 for r in rows if not r[4])} fail")
        for fg_name, bg_name, minimum, got, ok in rows:
            if not ok:
                print(f"   FAIL  {fg_name}({table[fg_name]}) on {bg_name}({table[bg_name]}) "
                      f"{got:.2f} < {minimum}  [{PAIRS_NOTE[(fg_name, bg_name)]}]")
                failed += 1
        for s in skipped:
            print(f"   ::warning::{theme} 跳过 {s}")

    print(f"TOTAL: {'FAIL ' + str(failed) + ' 项' if failed else 'ALL PASS'}")
    return 1 if failed else 0


PAIRS_NOTE = {p[:2]: p[3] for p in PAIRS}


FIXTURE_LIGHT = """
:root {
  --ds-text-primary: #0d253d;
  --ds-bg-base: #f5f6f7;
  --ds-bg-surface: #ffffff;
  --ds-text-secondary: #273951;
  --ds-text-tertiary: #55647a;
  --ds-accent: #0057d2;
  --ds-accent-hover: #0040b0;
  --ds-accent-active: #00348f;
  --ds-accent-on: #ffffff;
  --ds-accent-soft: #ebf3fd;
  --ds-success: #0b6b45;
  --ds-success-soft: #dff3e9;
  --ds-warning: #8a5a00;
  --ds-warning-soft: #fbf0d9;
  --ds-danger: #b42318;
  --ds-danger-soft: #fbe3e0;
  --ds-border-control: #475e75;
  --ds-border-strong: #475e75;
  /* v3 外壳配对也要进夹具：夹具 A 必须能覆盖 PAIRS 的全部角色，
   * 否则抬高的 MIN_PAIRS_PER_THEME 会让自检反过来红。 */
  --ds-nav-bg: #16202e;
  --ds-nav-ink: #eef2f8;
  --ds-nav-muted: #a9b6c8;
  --ds-statusbar-bg: #dfe4ec;
  --ds-statusbar-ink: #3c4452;
  --ds-group-infra: #7cb1ff;
  --ds-group-engine: #5fd3c0;
  --ds-group-governance: #c0a6ff;
  --ds-group-devtools: #ffc861;
  --ds-group-tenant: #8fe3a8;
  --ds-group-intelligent: #ff9fb2;
  --ds-group-operations: #79dff5;
  --ds-group-platform: #c8d3e3;
}
"""

# 负例：把强调边界退回历史上真实存在过的弱值（旧 --ds-border-strong 就是 #b6bec9，实测 1.88）
FIXTURE_BAD_LIGHT = FIXTURE_LIGHT.replace("--ds-border-strong: #475e75", "--ds-border-strong: #b6bec9")
# 负例：角色令牌消失 ⇒ 发现集变小 ⇒ 必须判红而不是"全通过"
FIXTURE_EMPTY = ":root { --ds-bg-base: #f5f6f7; }"


def self_test() -> int:
    import tempfile

    cases = [
        ("夹具 A：合规浅色 ⇒ 退 0", FIXTURE_LIGHT, 0),
        ("夹具 B：border-strong 弱到 1.88 ⇒ 退 1", FIXTURE_BAD_LIGHT, 1),
        ("夹具 C：角色令牌缺失 ⇒ 退 1（不许空跑报绿）", FIXTURE_EMPTY, 1),
        ("夹具 D：数学自检 白/黑 = 21.0", None, 0),
    ]
    bad = 0
    r = ratio("#ffffff", "#000000")
    if abs(r - 21.0) > 0.01:
        print(f"[FAIL] 夹具 D：白/黑比值算成 {r:.2f}，应为 21.00")
        bad += 1
    else:
        print("[PASS] 夹具 D：白/黑 = 21.00")
    for label, css, want in cases:
        if css is None:
            continue
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "design-tokens.css"
            p.write_text(css, encoding="utf-8")
            got = check(p)
        if got == want:
            print(f"[PASS] {label} ⇒ 退 {got}")
        else:
            print(f"[FAIL] {label} ⇒ 期望退 {want}，实得 {got}")
            bad += 1
    print("SELFTEST=" + ("OK" if bad == 0 else f"FAIL({bad})"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="数擎设计令牌对比度门禁")
    ap.add_argument("--tokens", default=None, help="design-tokens.css 路径")
    ap.add_argument("--self-test", action="store_true", help="跑内置夹具（含负例）")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    tokens = Path(args.tokens) if args.tokens else Path(__file__).resolve().parents[1] / "frontend/src/styles/design-tokens.css"
    if not tokens.exists():
        print(f"::error::{tokens} 不存在")
        return 2
    return check(tokens)


if __name__ == "__main__":
    sys.exit(main())
