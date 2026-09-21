#!/usr/bin/env python3
"""
暗色 token 副本一致性校验（防漂移）。

背景：
  暗色取值在样式里存在**两份副本**（每条 token 写两遍，服务于两条匹配路径）：

    A. `:root[data-theme='dark']`
       —— 首选路径。theme.ts 会把 mode + 系统偏好解析成最终主题并**始终**写入
          data-theme（含 system 档），三种模式都命中这一条。
    B. `@media (prefers-color-scheme: dark)` 内的 `:root:not([data-theme='light'])`
       —— 兜底路径。仅服务 JS 尚未执行的首屏窗口与"脚本不可用"的极端情况。

  两条路径的**特异性相同**（均为 0,2,0），而 B 在源码中靠后 —— 于是当
  system 档 + 系统暗色 时（data-theme='dark' 与媒体查询同时命中）**B 胜出**。
  因此两份副本一旦漂移，system 档就会与显式 data-theme='dark' 取到不同取值，
  表现为"跟随系统时组件级覆写落空 / 取值与手动选暗色不一致"。
  2026-09-21 修复前，main.css 的两份副本正是漏了 20 条声明、4 处取值不同。

本脚本对每个含暗色副本的样式文件断言两份副本的声明集合（属性 + 归一化值，
含 !important）diff 为空；不一致时打印具体差异并以退出码 1 失败。

覆盖文件：
  - frontend/src/styles/design-tokens.css   （--ds-* 设计 token）
  - frontend/src/styles/main.css            （--el-* 与 main.css 老变量）

用法：
  python3 scripts/check-dark-token-parity.py

退出码：
  0 = 两份副本逐条一致
  1 = 存在差异（或未找到预期块，说明样式结构被改动，需同步更新本脚本）
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# (相对路径, 说明)
TARGETS = [
    ("frontend/src/styles/design-tokens.css", "设计 token（--ds-*）"),
    ("frontend/src/styles/main.css", "EP 组件变量与老变量（--el-* / --bg 等）"),
]

# A 路径：:root[data-theme='dark'] { ... }（选择器后紧跟 { 的"声明块"，不含组件规则）
SELECTOR_A = r""":root\[data-theme=['"]dark['"]\]\s*\{"""
# B 路径：@media (prefers-color-scheme: dark) 内的 :root:not([data-theme='light']) { ... }
SELECTOR_B = r""":root:not\(\[data-theme=['"]light['"]\]\)\s*\{"""
MEDIA_DARK = r"""@media\s*\(\s*prefers-color-scheme\s*:\s*dark\s*\)"""

COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)


def strip_comments(css: str) -> str:
    """去掉 /* ... */ 注释（含 // 行注释不作为 CSS 语法处理）。"""
    return COMMENT_RE.sub("", css)


def brace_block(css: str, selector_re: str) -> list[str]:
    """返回所有匹配选择器的块的**块体**（用花括号配对切分，支持嵌套）。"""
    bodies: list[str] = []
    for m in re.finditer(selector_re, css, re.S):
        open_idx = css.index("{", m.end() - 1)
        depth = 0
        idx = open_idx
        while idx < len(css):
            if css[idx] == "{":
                depth += 1
            elif css[idx] == "}":
                depth -= 1
                if depth == 0:
                    break
            idx += 1
        if depth != 0:
            raise ValueError(f"花括号不配对，选择器片段：{m.group(0)!r}")
        bodies.append(css[open_idx + 1:idx])
    return bodies


def media_dark_block(css: str) -> list[str]:
    """返回所有 @media (prefers-color-scheme: dark) 的块体。"""
    bodies: list[str] = []
    for m in re.finditer(MEDIA_DARK, css):
        open_idx = css.index("{", m.end() - 1)
        depth = 0
        idx = open_idx
        while idx < len(css):
            if css[idx] == "{":
                depth += 1
            elif css[idx] == "}":
                depth -= 1
                if depth == 0:
                    break
            idx += 1
        bodies.append(css[open_idx + 1:idx])
    return bodies


def parse_decls(body: str) -> dict[str, str]:
    """解析块体里的自定义属性声明；值做空白归一化，保留 !important。"""
    decls: dict[str, str] = {}
    depth = 0
    current: list[str] = []
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        if ch == ";" and depth == 0:
            chunk = "".join(current)
            current = []
            prop, sep, value = chunk.partition(":")
            prop = prop.strip()
            if sep and prop.startswith("--"):
                decls[prop] = " ".join(value.split())
            continue
        current.append(ch)
    chunk = "".join(current)
    prop, sep, value = chunk.partition(":")
    prop = prop.strip()
    if sep and prop.startswith("--"):
        decls[prop] = " ".join(value.split())
    return decls


def diff_decls(a: dict[str, str], b: dict[str, str]) -> tuple[list[str], list[str], list[str]]:
    only_a = sorted(set(a) - set(b))
    only_b = sorted(set(b) - set(a))
    changed = sorted(k for k in set(a) & set(b) if a[k] != b[k])
    return only_a, only_b, changed


def check_file(rel_path: str, label: str) -> tuple[list[str], bool]:
    """返回 (差异行列表, 是否漂移)；差异行列表为空表示一致。"""
    path = ROOT / rel_path
    if not path.exists():
        return [f"{rel_path} 不存在"], True

    css = strip_comments(path.read_text(encoding="utf-8"))

    bodies_a = brace_block(css, SELECTOR_A)
    if len(bodies_a) != 1:
        return [
            f"{rel_path} 期望恰好 1 个 `:root[data-theme='dark']` 声明块，实际 {len(bodies_a)} 个"
            f"（样式结构已改动？请同步更新 {Path(__file__).name}）"
        ], True

    media_bodies = media_dark_block(css)
    if len(media_bodies) != 1:
        return [
            f"{rel_path} 期望恰好 1 个 `@media (prefers-color-scheme: dark)` 块，"
            f"实际 {len(media_bodies)} 个（样式结构已改动？请同步更新 {Path(__file__).name}）"
        ], True

    bodies_b = brace_block(media_bodies[0], SELECTOR_B)
    if len(bodies_b) != 1:
        return [
            f"{rel_path} 的暗色媒体查询里期望恰好 1 个 `:root:not([data-theme='light'])` 块，"
            f"实际 {len(bodies_b)} 个"
        ], True

    decls_a = parse_decls(bodies_a[0])
    decls_b = parse_decls(bodies_b[0])
    only_a, only_b, changed = diff_decls(decls_a, decls_b)

    if not (only_a or only_b or changed):
        return [], False

    problems = [
        f"{rel_path}（{label}）：两份暗色副本漂移 "
        f"[A 独有 {len(only_a)} / B 独有 {len(only_b)} / 取值不同 {len(changed)}]"
    ]
    for k in only_a:
        problems.append(f"    仅 `:root[data-theme='dark']` 有： {k}: {decls_a[k]}")
    for k in only_b:
        problems.append(f"    仅 @media 兜底块有：       {k}: {decls_b[k]}")
    for k in changed:
        problems.append(
            f"    取值不同： {k}\n"
            f"        :root[data-theme='dark'] → {decls_a[k]}\n"
            f"        @media 兜底块            → {decls_b[k]}"
        )
    return problems, True


def main() -> int:
    all_problems: list[str] = []
    drifted: list[str] = []
    for rel_path, label in TARGETS:
        problems, is_drift = check_file(rel_path, label)
        all_problems.extend(problems)
        if is_drift:
            drifted.append(rel_path)

    if all_problems:
        print("[check-dark-token-parity] 暗色 token 副本不一致：\n")
        for line in all_problems:
            print(f"  {line}")
        print(
            "\n修复方式：把两份副本改成逐条一致（属性 + 值 + !important 都相同）。"
            "\n注意两条路径特异性相同、兜底块在源码中靠后，漂移会导致 system 档与"
            "显式 dark 取值不同。"
        )
        for rel_path in drifted:
            print(f"::error::{rel_path} 两份暗色 token 副本漂移")
        return 1

    total = 0
    for rel, _ in TARGETS:
        css = strip_comments((ROOT / rel).read_text(encoding="utf-8"))
        total += len(parse_decls(brace_block(css, SELECTOR_A)[0]))
    print(f"[check-dark-token-parity] ✓ {len(TARGETS)} 个样式文件的暗色副本逐条一致（共 {total} 条声明）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
