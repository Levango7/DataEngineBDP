#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DESIGN.md 的 YAML 令牌区必须是 design-tokens.css 的**镜像**，不许领先。

为什么要这条门禁：契约与代码各写一份颜色，早晚分叉——本轮就分叉过两次
（YAML 里先写了 Stripe 的 #e3e8ee，而代码真值是 #e9ecf1；状态色照参考稿写成
#0b6b45，代码其实是 #0e7a4a）。分叉的契约比没有契约更坏：读的人会按不存在的值施工。

口径：逐条比对 `colors` / `colorsDark` 与 CSS 的 `--ds-<key>`；
暗色键若在暗色块里没重写，则按"继承浅色"核算。发现集为空判红。
不依赖 PyYAML——YAML 区是平铺的 `key: "#hex"`，用正则即可，runner 上少一个前提。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

MIN_KEYS = 12  # 少于这个数说明令牌名或 YAML 区块被改坏，不能算"通过"

CSS_RE = re.compile(r'--ds-([a-z0-9-]+):\s*(#[0-9a-fA-F]{3,8})\b')


def css_themes(text: str) -> tuple[dict[str, str], dict[str, str]]:
    """返回 (浅色表, 暗色表)。暗色表只含显式重写项，查不到时回落到浅色。"""
    light_m = re.search(r"^:root \{\n(.*?)\n\}", text, re.S | re.M)
    dark_m = re.search(r":root\[data-theme='dark'\] \{\n(.*?)\n\}", text, re.S)
    if not light_m or not dark_m:
        raise SystemExit("::error::design-tokens.css 的 :root 或暗色块定位失败")
    light = dict(CSS_RE.findall(light_m.group(1)))
    dark = dict(CSS_RE.findall(dark_m.group(1)))
    return {k.lower(): v.lower() for k, v in light.items()}, {k.lower(): v.lower() for k, v in dark.items()}


def yaml_block(md_text: str, name: str) -> dict[str, str]:
    m = re.search(rf"^{name}:\n((?:[ \t]+[a-z0-9-]+:\s*\"#[0-9a-fA-F]+\"\n)+)", md_text, re.M)
    if not m:
        return {}
    out = {}
    for line in m.group(1).splitlines():
        kv = re.match(r'\s*([a-z0-9-]+):\s*"(#[0-9a-fA-F]+)"', line)
        if kv:
            out[kv.group(1)] = kv.group(2).lower()
    return out


def check(md: Path, css: Path) -> int:
    light, dark = css_themes(css.read_text(encoding="utf-8"))
    md_text = md.read_text(encoding="utf-8")
    bad = 0
    for section, table, inherit in (("colors", light, {}), ("colorsDark", dark, light)):
        declared = yaml_block(md_text, section)
        if len(declared) < MIN_KEYS:
            print(f"::error::{section}: 只解析到 {len(declared)} 条令牌（下限 {MIN_KEYS}）"
                  f"⇒ YAML 区块或键名格式失效，按发现集不完整判红")
            bad += 1
            continue
        drift = []
        for key, want in declared.items():
            got = table.get(key, inherit.get(key))
            if got is None:
                drift.append(f"{key}: CSS 里不存在 --ds-{key}")
            elif got != want:
                drift.append(f"{key}: DESIGN.md {want} vs CSS {got}")
        if drift:
            print(f"::error::{section} 与代码不一致（{len(drift)} 条）：")
            for d in drift:
                print(f"   {d}")
            bad += 1
        else:
            print(f"{section}: {len(declared)} 条与 CSS 逐条一致")
    print(f"SYNC: {'FAIL' if bad else 'OK'}")
    return 1 if bad else 0


GOOD_MD = '''---
colors:
  bg-base: "#ffffff"
  accent: "#0057d2"
  text-primary: "#0d253d"
  border-strong: "#475e75"
  border-subtle: "#e9ecf1"
  success: "#0e7a4a"
  warning: "#b45309"
  danger: "#c62828"
  info: "#0b6e99"
  bg-surface: "#ffffff"
  bg-muted: "#eaecee"
  surface-2: "#f5f6f7"
colorsDark:
  bg-base: "#101317"
  accent: "#7fb0ff"
  text-primary: "#e7eaf0"
  border-strong: "#7d8797"
  border-subtle: "#23272e"
  success: "#4ade80"
  warning: "#fbbf24"
  danger: "#f87171"
  info: "#38bdf8"
  bg-surface: "#171a20"
  bg-muted: "#1a1e24"
  surface-2: "#1d2128"
---
'''

DRIFT_MD = GOOD_MD.replace('border-subtle: "#e9ecf1"', 'border-subtle: "#e3e8ee"')
EMPTY_MD = '---\ncolors:\n  bg-base: "#ffffff"\n---\n'
CSS_FIXTURE = """
:root {
  --ds-bg-base: #ffffff; --ds-accent: #0057d2; --ds-text-primary: #0d253d;
  --ds-border-strong: #475e75; --ds-border-subtle: #e9ecf1; --ds-success: #0e7a4a;
  --ds-warning: #b45309; --ds-danger: #c62828; --ds-info: #0b6e99;
  --ds-bg-surface: #ffffff; --ds-bg-muted: #eaecee; --ds-surface-2: #f5f6f7;
}
:root[data-theme='dark'] {
  --ds-bg-base: #101317; --ds-accent: #7fb0ff; --ds-text-primary: #e7eaf0;
  --ds-border-strong: #7d8797; --ds-border-subtle: #23272e; --ds-success: #4ade80;
  --ds-warning: #fbbf24; --ds-danger: #f87171; --ds-info: #38bdf8;
  --ds-bg-surface: #171a20; --ds-bg-muted: #1a1e24; --ds-surface-2: #1d2128;
}
"""


def self_test() -> int:
    import tempfile

    cases = [("夹具 A：YAML 与 CSS 逐条一致 ⇒ 退 0", GOOD_MD, 0),
             ("夹具 B：YAML 领先一个 Stripe 值 ⇒ 退 1", DRIFT_MD, 1),
             ("夹具 C：只声明 1 条令牌 ⇒ 退 1（不许空跑）", EMPTY_MD, 1)]
    bad = 0
    for label, md_body, want in cases:
        with tempfile.TemporaryDirectory() as d:
            md = Path(d) / "DESIGN.md"
            css = Path(d) / "design-tokens.css"
            md.write_text(md_body, encoding="utf-8")
            css.write_text(CSS_FIXTURE, encoding="utf-8")
            got = check(md, css)
        print(f"[{'PASS' if got == want else 'FAIL'}] {label} ⇒ 实得 {got}")
        bad += 0 if got == want else 1
    print("SELFTEST=" + ("OK" if bad == 0 else f"FAIL({bad})"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--design", default=None)
    ap.add_argument("--tokens", default=None)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    repo = Path(__file__).resolve().parents[1]
    return check(Path(args.design) if args.design else repo / "frontend/DESIGN.md",
                 Path(args.tokens) if args.tokens else repo / "frontend/src/styles/design-tokens.css")


if __name__ == "__main__":
    sys.exit(main())
