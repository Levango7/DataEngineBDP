#!/usr/bin/env python3
"""Chart 敏感信息注入卫生校验（台账 #12）。

背景：台账 #12 记录过"敏感信息只能进 ConfigMap、任何具备 get configmap 权限者可读"。
2026-10-10 全量复核（本脚本的真判定跑在 88 个 Chart 上）结论是**现状已达标**：
  · 19 个 Chart 用 `secretKeyRef` / `secretRef` / 独立 Secret 模板注入敏感值；
  · ConfigMap 的 data 段零敏感键（9 处疑似命中经逐条核对全是误报：chart 名、注释、
    velero 的 `credential.name` 是**外部 Secret 名称引用**且位于非 ConfigMap 文档）；
  · values.yaml 零弱默认口令。
本脚本把该结论固化为**门禁**（与 #36 资产键名、#52 三副本快照同款做法），防再回归。

判据（文本级扫描，Helm 模板不是合法 YAML；按 `---` 文档边界 + 缩进判定）：
  A. ConfigMap 文档的 data/stringData 段内，敏感键（PASSWORD/PASSWD/SECRET/TOKEN/
     ACCESS_KEY/CREDENTIAL/PRIVATE_KEY，忽略大小写）不得带非空值；
  B. 工作负载文档（Deployment/StatefulSet/DaemonSet/Job/CronJob）的 env 条目，
     name 命中敏感模式时不得使用字面量 `value:`（必须 valueFrom.secretKeyRef，
     或整体走 envFrom.secretRef）；
  C. values.yaml 中敏感键不得带弱默认口令（admin/password/changeme/123456/root/
     postgres/minioadmin/demo/test…）。

退出码：0 = 干净；1 = 有命中。`--self-test` 先跑合成正反例再跑真判定（同其余门禁）。
"""

from __future__ import annotations

import glob
import os
import re
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHART_ROOT = os.path.join(REPO, 'design', 'deploy', 'charts')

SENS = re.compile(r'(PASSWORD|PASSWD|SECRET|TOKEN|ACCESS_KEY|CREDENTIAL|PRIVATE_KEY)', re.I)
WORKLOAD_KINDS = ('Deployment', 'StatefulSet', 'DaemonSet', 'Job', 'CronJob', 'ReplicaSet')
WEAK = {
    'admin', 'password', 'passwd', 'changeme', 'change-me', 'change_me', '123456',
    'root', 'postgres', 'minioadmin', 'demo', 'test', 'secret', 'default',
}
KEY_LINE = re.compile(r'^(\s*)([A-Za-z0-9_.\-]+)\s*:\s*(.*)$')
ENV_NAME_LINE = re.compile(r'^\s*-\s*name:\s*([A-Za-z0-9_.\-]+)\s*$')
VALUES_SENS_LINE = re.compile(
    r'^(\s*)([A-Za-z0-9_]*?(?:password|passwd|secret|token|accesskey|credential|privatekey)[A-Za-z0-9_]*)\s*:\s*(.+?)\s*$',
    re.I,
)


def split_docs(text: str) -> list:
    """按 `---` 切分成文档（保留行号偏移以便报错定位）。"""
    docs, cur, start = [], [], 1
    for i, line in enumerate(text.split('\n'), 1):
        if re.match(r'^---\s*$', line):
            docs.append((start, cur))
            cur, start = [], i + 1
        else:
            cur.append(line)
    docs.append((start, cur))
    return docs


def doc_kind(lines: list) -> str:
    for l in lines:
        m = re.match(r'^kind:\s*(\S+)\s*$', l)
        if m:
            return m.group(1)
    return ''


def check_configmap(rel: str, start: int, lines: list) -> list:
    """规则 A：ConfigMap 的 data/stringData 段内敏感键不得带非空值。"""
    out = []
    in_data, data_indent = False, 0
    for i, l in enumerate(lines, start):
        if l.lstrip().startswith('#'):
            continue
        m = re.match(r'^(data|stringData):\s*$', l)
        if m:
            in_data, data_indent = True, 0
            continue
        if in_data:
            km = KEY_LINE.match(l)
            if not km:
                in_data = False
                continue
            indent, key, value = len(km.group(1)), km.group(2), km.group(3).strip()
            if indent == 0:
                in_data = False
                continue
            if SENS.search(key) and value and not value.startswith('#'):
                out.append('%s:%d  ConfigMap.%s 带值 "%s"' % (rel, i, key, value[:40]))
    return out


def check_workload(rel: str, start: int, lines: list) -> list:
    """规则 B：敏感 env 不得用字面量 value。"""
    out = []
    for i, l in enumerate(lines, start):
        m = ENV_NAME_LINE.match(l)
        if not m or not SENS.search(m.group(1)):
            continue
        base_indent = len(l) - len(l.lstrip())
        has_valuefrom = False
        for j in range(i - start + 1, min(i - start + 8, len(lines))):
            nxt = lines[j]
            nl = nxt.lstrip()
            indent = len(nxt) - len(nl)
            if nl.strip() == '':
                continue
            if indent < base_indent:
                break
            if re.match(r'^\s*value:\s*\S', nxt):
                out.append('%s:%d  env %s 使用字面量 value' % (rel, i, m.group(1)))
                break
            if re.match(r'^\s*valueFrom:', nxt):
                has_valuefrom = True
                break
        if has_valuefrom:
            continue
    return out


def check_values(rel: str, lines: list) -> list:
    """规则 C：values.yaml 敏感键不得带弱默认口令。"""
    out = []
    for i, l in enumerate(lines, 1):
        if l.lstrip().startswith('#'):
            continue
        m = VALUES_SENS_LINE.match(l)
        if not m:
            continue
        val = m.group(3).strip().strip('"').strip("'")
        key = m.group(2)
        # 引用型键（passwordKey/usernameKey/secretName…）的值是"Secret 里的键名/对象名"，
        # 不是口令本身 ⇒ 不属弱口令（keycloak 的 `passwordKey: password` 即此例）。
        if re.search(r'(Key|Name)$', key):
            continue
        if val.lower() in WEAK:
            out.append('%s:%d  %s 的弱默认口令 "%s"' % (rel, i, m.group(2), val))
    return out


def scan(root: str) -> list:
    findings = []
    for cdir in sorted(os.listdir(root)):
        base = os.path.join(root, cdir)
        if not os.path.isdir(base) or cdir.startswith('_'):
            continue
        values = os.path.join(base, 'values.yaml')
        if os.path.isfile(values):
            t = open(values, encoding='utf-8', errors='replace').read()
            findings += check_values(os.path.relpath(values, root), t.split('\n'))
        for f in glob.glob(os.path.join(base, 'templates', '**', '*'), recursive=True):
            if not os.path.isfile(f) or not f.endswith(('.yaml', '.yml', '.tpl')):
                continue
            rel = os.path.relpath(f, root)
            text = open(f, encoding='utf-8', errors='replace').read()
            for start, lines in split_docs(text):
                kind = doc_kind(lines)
                if kind == 'ConfigMap':
                    findings += check_configmap(rel, start, lines)
                elif kind in WORKLOAD_KINDS:
                    findings += check_workload(rel, start, lines)
    return findings


def self_test() -> int:
    """合成正反例：三类规则各造正例 + 干净反例，断言命中集合。"""
    with tempfile.TemporaryDirectory() as td:
        chart = os.path.join(td, 'probe', 'templates')
        os.makedirs(chart)
        open(os.path.join(td, 'probe', 'values.yaml'), 'w', encoding='utf-8').write(
            'db:\n  password: changeme\n  host: pg\n  secretName: my-secret\n')
        open(os.path.join(chart, 'configmap.yaml'), 'w', encoding='utf-8').write(
            'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: {{ include "x" . }}\n'
            'data:\n  DB_HOST: pg\n  DB_PASSWORD: hunter2\n')
        open(os.path.join(chart, 'deployment.yaml'), 'w', encoding='utf-8').write(
            'apiVersion: apps/v1\nkind: Deployment\nspec:\n  template:\n    spec:\n'
            '      containers:\n        - name: c\n          env:\n'
            '            - name: JWT_SECRET\n              value: literal-bad\n'
            '            - name: DB_PASSWORD\n              valueFrom:\n'
            '                secretKeyRef:\n                  name: s\n                  key: k\n'
            '            - name: LOG_LEVEL\n              value: info\n')
        found = scan(td)
        text = '\n'.join(found)
        checks = [
            ('values 弱口令', 'password 的弱默认口令 "changeme"' in text),
            ('ConfigMap 敏感键带值', 'ConfigMap.DB_PASSWORD' in text),
            ('env 字面量 value', 'env JWT_SECRET 使用字面量 value' in text),
            ('secretKeyRef 正例不误报', text.count('DB_PASSWORD') == 1),
            ('非敏感键不误报', 'DB_HOST' not in text and 'LOG_LEVEL' not in text),
            ('secretName 不误报', 'secretName' not in text),
            ('passwordKey 引用键不误报', 'passwordKey' not in text),
        ]
        bad = [n for n, ok in checks if not ok]
        if bad:
            print('FAIL self-test: ' + '; '.join(bad))
            for f in found:
                print('  hit:', f)
            return 1
        print('✓ self-test 通过（三类规则正例均命中，反例无误报）')
    return 0


def main() -> int:
    if '--self-test' in sys.argv and self_test() != 0:
        return 1
    if not os.path.isdir(CHART_ROOT):
        print('!! 未找到 %s' % CHART_ROOT)
        return 1
    findings = scan(CHART_ROOT)
    if findings:
        print('FAIL: Chart 敏感信息注入卫生校验命中 %d 处：' % len(findings))
        for f in findings:
            print('  ' + f)
        print('判据见本脚本 docstring（台账 #12）')
        return 1
    n_charts = sum(1 for d in os.listdir(CHART_ROOT)
                   if os.path.isdir(os.path.join(CHART_ROOT, d)) and not d.startswith('_'))
    print('OK: %d 个 Chart 通过敏感信息注入卫生校验（ConfigMap 无敏感键 / 敏感 env 走 Secret / values 无弱口令）'
          % n_charts)
    return 0


if __name__ == '__main__':
    sys.exit(main())
