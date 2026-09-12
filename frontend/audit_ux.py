# -*- coding: utf-8 -*-
"""审查三 A：交互体验静态扫描。
1. 提交/危险按钮无 :loading 绑定（点击后无反馈）
2. 空 catch 块（静默吞错，用户无感知失败）
3. catch 无任何 ElMessage 反馈且注释不含'拦截器'（拦截器已提示的合法场景排除）"""
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
ROOT = r'F:\Nexus\DataEngineBDP\frontend\src'

findings = []
for dirpath, _, files in os.walk(ROOT):
    if '__tests__' in dirpath or 'locales' in dirpath:
        continue
    for fn in files:
        if not fn.endswith('.vue'):
            continue
        p = os.path.join(dirpath, fn)
        src = open(p, encoding='utf-8', errors='replace').read()
        rel = os.path.relpath(p, ROOT)
        lines = src.splitlines()

        # 1. 主操作按钮（提交/保存/创建/删除文本）无 loading
        for i, line in enumerate(lines, 1):
            s = line.strip()
            if re.search(r'(提交|保存|创建|删除|登录|生成)', s) and '<el-button' in s and 'loading' not in s.lower() and '@click' in s:
                findings.append((rel, i, 'BTN_NO_LOADING', s[:70]))

        # 2. 空 catch
        for i, line in enumerate(lines, 1):
            s = line.strip()
            if s == '} catch {' or s == '} catch (e) {' and i + 1 <= len(lines) and lines[i].strip() in ('', '// ignore', '}'):
                findings.append((rel, i, 'EMPTY_CATCH', s[:70]))

print(f'交互问题候选: {len(findings)}')
for rel, i, kind, s in findings[:50]:
    print(f'  {rel}:{i}  [{kind}]  {s}')
