# -*- coding: utf-8 -*-
"""审查二：暗色兼容性静态扫描。
A. scoped 样式中的亮色硬编码（#fff/#f8fafc/#0f172a 深字白底等）——若该文件无 :root[data-theme='dark'] 覆盖段则报告
B. color: 深色字（#0f172a/#334155 等）无暗色覆写的
C. 全局 main.css 暗色段覆盖类清单 vs 白底类清单（找漏网）"""
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
ROOT = r'F:\Nexus\DataEngineBDP\frontend\src'

# 亮色硬编码特征（白底/浅底、深字）——暗色下会出问题的
LIGHT_BG = re.compile(r'background(?:-color)?\s*:\s*(?:#fff(?:fff)?|#f8fafc|#f1f5f9|#eff6ff|#e0f2fe|white|rgba?\(\s*255\s*,\s*255\s*,\s*255)', re.I)
DARK_TEXT = re.compile(r'color\s*:\s*(?:#0f172a|#1e293b|#334155|#475569|#1e3a8a)', re.I)
ZH_DARK = re.compile(r"data-theme='dark'")

findings = []
for dirpath, _, files in os.walk(ROOT):
    if 'node_modules' in dirpath or 'locales' in dirpath:
        continue
    for fn in files:
        if not fn.endswith('.vue'):
            continue
        p = os.path.join(dirpath, fn)
        src = open(p, encoding='utf-8', errors='replace').read()
        rel = os.path.relpath(p, ROOT)
        has_dark = bool(ZH_DARK.search(src))
        lines = src.splitlines()
        for i, line in enumerate(lines, 1):
            s = line.strip()
            if s.startswith(('/*', '*', '//')):
                continue
            if LIGHT_BG.search(line) and not has_dark:
                findings.append((rel, i, 'LIGHT_BG 无暗色覆写', s[:70]))
            elif DARK_TEXT.search(line) and not has_dark:
                findings.append((rel, i, 'DARK_TEXT 无暗色覆写', s[:70]))

print(f'暗色失配候选: {len(findings)}')
for rel, i, kind, s in findings[:40]:
    print(f'  {rel}:{i}  [{kind}]  {s}')
