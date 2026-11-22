#!/usr/bin/env python3
"""从「包 + 版本水位」账生成 Trivy 镜像豁免文件（台账 #113）。

为什么要有这个生成器
--------------------
Trivy 原生的 `.trivyignore.yaml` **没有「按包 + 版本」豁免的字段**
（官方 filtering 文档列的字段只有 id / paths / purls / expired_at / statement），
所以「豁免的单位从 CVE 号上移到包 + 版本水位」这件事没法靠 Trivy 原生能力落地，
只能由「水位账 + 生成器」落地：

  docs/trivy-image-watermark.yaml   ← 唯一事实源（人工维护）
            │  scripts/gen-trivy-ignore.py（本脚本，唯一写入者）
            ▼
  docs/trivy-image-ignore.yaml       ← 生成物（**不要手改**）

台账 #113 的现场：4 周内同一个包、同一个修复版，CVE 逐个新增
（libexpat→libx11→brotli→curl），按 CVE 号逐条豁免等于打地鼠；
水位账把「豁免意图」记在包 + 版本上，CVE 列表成为派生结果。

用法：
  python3 scripts/gen-trivy-ignore.py             # 重新生成 docs/trivy-image-ignore.yaml
  python3 scripts/gen-trivy-ignore.py --check     # 只比对，不写盘；不一致 rc=1
  python3 scripts/gen-trivy-ignore.py --stdout    # 生成到 stdout，不写盘
  python3 scripts/gen-trivy-ignore.py --self-test # 离线自测（不碰网络）

退出码：0 正常；1 --check 发现漂移 / 自测失败；2 水位账不合法（解析失败、字段缺失、语义矛盾）。
"""
import argparse
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEDGER_REL = os.path.join('docs', 'trivy-image-watermark.yaml')
IGNORE_REL = os.path.join('docs', 'trivy-image-ignore.yaml')

REQUIRED_FIELDS = ('package', 'ecosystem', 'vulnerable_version',
                   'fixed_versions', 'expired_at', 'statement', 'cves')
CVE_ID_RE = re.compile(r'^CVE-\d{4}-\d{4,}$')
DATE_RE = re.compile(r'^\d{4}-\d{2}-\d{2}$')
# 保留字：上游没有版本级修复线（只有「内容更新」），fixed_versions 写它
UNVERSIONED = 'unversioned'

# 「修复线」的标记词：statement 里只有出现在这些词之后的版本才算修复版本水位。
# 这是刻意保守的取法——脆弱版本（Installed）写在箭头左边，不参与校验。
FIX_MARKERS = ('→', '修复线', '后为', '升到', '取到', '≥')
# 终止符：修复线片段到这里为止（避免把后面无关的版本号吃进来）
FIX_TERMINATORS = ('；', ';', '。', '，', ',', '（', '(', '）', ')', '、', '⇒', '\n')
VERSION_TOKEN_RE = re.compile(r'\d+(?:[0-9A-Za-z.:+~_-]*[0-9A-Za-z.+~_-])?')


class LedgerError(Exception):
    """水位账本身不可用（解析失败 / 字段缺失 / 语义不合法）。"""


def load_ledger(path):
    """读 + 解析水位账。解析失败抛 LedgerError（⇒ rc=2，门禁失明不放行）。"""
    try:
        import yaml
    except ImportError:  # pragma: no cover
        raise LedgerError('需要 PyYAML（pip install pyyaml）')
    if not os.path.isfile(path):
        raise LedgerError('水位账不存在：%s' % path)
    try:
        with io.open(path, encoding='utf-8') as fh:
            doc = yaml.safe_load(fh.read())
    except Exception as exc:  # yaml.YAMLError 等
        raise LedgerError('水位账解析失败：%s（%s）' % (path, exc))
    if not isinstance(doc, dict):
        raise LedgerError('水位账顶层不是映射：%s' % path)
    return doc


def _version_like(token):
    """像版本号（含 . : + - ~ 且含数字）才当版本；"19"、"0" 这类计数不算。"""
    return bool(re.search(r'[.:+\-~]', token)) and any(c.isdigit() for c in token)


def extract_fix_versions(statements):
    """从 statement 集合里抽出「修复版本水位」。

    只认出现在 FIX_MARKERS 之后的版本号；箭头左边的脆弱版本（Installed）
    与正文里的其它数字（Dockerfile 数、日期、tag 号）都不算。
    """
    found = set()
    for text in statements:
        for marker in FIX_MARKERS:
            idx = 0
            while True:
                pos = text.find(marker, idx)
                if pos < 0:
                    break
                tail = text[pos + len(marker):]
                cut = len(tail)
                for term in FIX_TERMINATORS:
                    hit = tail.find(term)
                    if hit >= 0:
                        cut = min(cut, hit)
                for token in VERSION_TOKEN_RE.findall(tail[:cut]):
                    if _version_like(token):
                        found.add(token)
                idx = pos + len(marker)
    return found


def validate_ledger(doc):
    """返回错误清单（空列表＝合法）。语义不合法也在这里判。"""
    errs = []
    for field in ('preamble', 'watermarks'):
        if field not in doc:
            errs.append('水位账缺字段：%s' % field)
    records = doc.get('watermarks')
    if not isinstance(records, list) or not records:
        errs.append('watermarks 缺失或为空（判据集合为空 ⇒ 门禁失明）')
        return errs

    seen_ids = {}
    for idx, rec in enumerate(records):
        label = 'watermarks[%d]' % idx
        if not isinstance(rec, dict):
            errs.append('%s 不是映射' % label)
            continue
        for field in REQUIRED_FIELDS:
            if field not in rec or rec[field] is None or rec[field] == '':
                errs.append('%s 缺字段：%s' % (label, field))
        if not isinstance(rec.get('package'), str) or not rec.get('package'):
            errs.append('%s 的 package 必须是非空字符串' % label)
        if not isinstance(rec.get('ecosystem'), str) or not rec.get('ecosystem'):
            errs.append('%s 的 ecosystem 必须是非空字符串' % label)
        vuln = rec.get('vulnerable_version')
        if not isinstance(vuln, str) or not vuln:
            errs.append('%s 的 vulnerable_version 必须是非空字符串' % label)
        if not isinstance(rec.get('statement'), str) or not rec.get('statement'):
            errs.append('%s 的 statement 必须是非空字符串' % label)
        expired = rec.get('expired_at')
        if not isinstance(expired, str) or not DATE_RE.match(expired):
            errs.append('%s 的 expired_at 不是 YYYY-MM-DD：%r' % (label, expired))

        fixed = rec.get('fixed_versions')
        if not isinstance(fixed, list) or not fixed:
            errs.append('%s 的 fixed_versions 必须是非空列表' % label)
            fixed = []
        elif not all(isinstance(v, str) and v for v in fixed):
            errs.append('%s 的 fixed_versions 含空值或非字符串' % label)
        if len(set(fixed)) != len(fixed):
            errs.append('%s 的 fixed_versions 有重复项：%s' % (label, fixed))

        cves = rec.get('cves')
        if not isinstance(cves, list) or not cves:
            errs.append('%s 的 cves 必须是非空列表' % label)
            cves = []
        statements = []
        local_ids = []
        for cve in cves:
            if not isinstance(cve, dict):
                errs.append('%s 的 cves 元素不是映射' % label)
                continue
            cid = cve.get('id')
            stmt = cve.get('statement')
            if not isinstance(cid, str) or not CVE_ID_RE.match(cid):
                errs.append('%s 的 cve id 形态不对（要 CVE-YYYY-NNNN）：%r' % (label, cid))
            elif cid in local_ids:
                errs.append('%s 的 cves 有重复 id：%s' % (label, cid))
            else:
                local_ids.append(cid)
            if cid in seen_ids:
                errs.append('CVE id 重复登记：%s（%s 与 %s）' % (cid, seen_ids[cid], label))
            elif isinstance(cid, str):
                seen_ids[cid] = label
            if not isinstance(stmt, str) or not stmt:
                errs.append('%s 的 cve %r 缺 statement' % (label, cid))
            else:
                statements.append(stmt)
        if isinstance(rec.get('statement'), str) and rec['statement']:
            statements.append(rec['statement'])

        # ── 语义：fixed_versions 与 statement 里写的修复线必须一致 ──
        stated = extract_fix_versions(statements)
        declared = set(v for v in fixed if isinstance(v, str))
        if UNVERSIONED in declared:
            if declared != {UNVERSIONED}:
                errs.append('%s 的 fixed_versions 里 unversioned 不能与版本号混用：%s'
                            % (label, sorted(declared)))
            if stated:
                errs.append('%s 声明 unversioned（无版本级修复线），但 statement 里出现了修复线 %s'
                            % (label, sorted(stated)))
        else:
            missing = sorted(stated - declared)
            extra = sorted(declared - stated)
            if missing:
                errs.append('%s 的 statement 写了修复线 %s，但 fixed_versions 里没有'
                            % (label, missing))
            if extra:
                errs.append('%s 的 fixed_versions 写了 %s，但 statement 里找不到对应修复线'
                            % (label, extra))

        # ── 语义：脆弱版本必须能在 statement 里对上 ──
        if isinstance(vuln, str) and vuln and vuln != 'unknown':
            for part in [p.strip() for p in vuln.split(',') if p.strip()]:
                if not any(part in s for s in statements):
                    errs.append('%s 的 vulnerable_version %r 在本水位 statement 里找不到'
                                % (label, part))
    return errs


def _sort_key(rec):
    """确定性排序：包名 → 脆弱版本 → 首条 CVE id。"""
    cves = rec.get('cves') or []
    first_id = min((c.get('id') or '' for c in cves if isinstance(c, dict)), default='')
    return (rec.get('package') or '', rec.get('vulnerable_version') or '', first_id)


def sorted_records(doc):
    return sorted(doc.get('watermarks') or [], key=_sort_key)


def yaml_double_quote(text):
    esc = text.replace('\\', '\\\\').replace('"', '\\"')
    return '"%s"' % esc


def render(doc):
    """把水位账渲染成 ignore 文件文本（确定性：同账 ⇒ 同输出）。"""
    lines = []
    for line in (doc.get('preamble') or '').splitlines():
        lines.append(line)
    lines.append('vulnerabilities:')
    for rec in sorted_records(doc):
        lines.append('')
        for note in (rec.get('notes') or []):
            lines.append('  ' + note)
        for cve in sorted((c for c in (rec.get('cves') or []) if isinstance(c, dict)),
                          key=lambda c: c.get('id') or ''):
            lines.append('  - id: %s' % cve.get('id'))
            lines.append('    statement: %s' % yaml_double_quote(cve.get('statement') or ''))
            lines.append('    expired_at: %s' % rec.get('expired_at'))
    history = (doc.get('history_notes') or '').rstrip('\n')
    if history.strip():
        lines.append('')
        lines.append('# ==== 历史注记（原文保留；对应条目已不在册，仅供追溯）====')
        lines.extend(history.splitlines())
    return '\n'.join(lines) + '\n'


def generate(ledger_path, ignore_path, check=False, to_stdout=False):
    doc = load_ledger(ledger_path)
    errs = validate_ledger(doc)
    if errs:
        raise LedgerError('水位账不合法：\n  - ' + '\n  - '.join(errs))
    text = render(doc)
    if to_stdout:
        sys.stdout.write(text)
        return 0
    if check:
        if not os.path.isfile(ignore_path):
            print('漂移：豁免文件不存在：%s' % ignore_path, file=sys.stderr)
            return 1
        with io.open(ignore_path, encoding='utf-8') as fh:
            current = fh.read()
        if current != text:
            print('漂移：%s 与水位账派生结果不一致（跑 scripts/gen-trivy-ignore.py 重新生成）'
                  % ignore_path, file=sys.stderr)
            return 1
        return 0
    with io.open(ignore_path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(text)
    print('已生成 %s（%d 条水位记录 / %d 条 CVE）'
          % (ignore_path, len(sorted_records(doc)),
             sum(len(r.get('cves') or []) for r in sorted_records(doc))))
    return 0


# ── 离线自测 ──────────────────────────────────────────────────────────────
def _fixture_ledger():
    return {
        'schema_version': 1,
        'preamble': '# 自测用机制说明\n',
        'history_notes': '# 自测用历史注记\n',
        'watermarks': [
            {'package': 'zeta-pkg', 'ecosystem': 'alpine', 'vulnerable_version': '1.0.0-r0',
             'fixed_versions': ['1.0.1-r0'], 'expired_at': '2026-12-01',
             'statement': 'zeta-pkg 1.0.0-r0 → 1.0.1-r0',
             'notes': ['# ---- zeta 组 ----'],
             'cves': [{'id': 'CVE-2026-22222', 'statement': 'zeta-pkg 1.0.0-r0 → 1.0.1-r0'}]},
            {'package': 'alpha-pkg', 'ecosystem': 'npm', 'vulnerable_version': '0.1.0',
             'fixed_versions': ['0.1.1'], 'expired_at': '2026-12-15',
             'statement': 'alpha-pkg 0.1.0 → 0.1.1',
             'notes': [],
             'cves': [{'id': 'CVE-2026-11111', 'statement': 'alpha-pkg 0.1.0 → 0.1.1'}]},
        ],
    }


def run_self_test(ledger_path, ignore_path):
    passed = failed = 0

    def check(name, expect, got):
        nonlocal passed, failed
        if got == expect:
            passed += 1
            print('  ✓ %s ⇒ %s' % (name, got))
        else:
            failed += 1
            print('  ✗ %s ⇒ %s（期望 %s）' % (name, got, expect))

    print('-- 生成器自测（离线，不碰网络）--')
    doc = _fixture_ledger()
    check('合成账本合法', [], validate_ledger(doc))
    text = render(doc)
    check('渲染确定性（两次渲染一致）', True, render(doc) == text)
    check('按 package 排序（alpha 在 zeta 前）', True,
          text.index('CVE-2026-11111') < text.index('CVE-2026-22222'))
    check('含 vulnerabilities 段', True, '\nvulnerabilities:\n' in text)
    check('notes 写成注释', True, '  # ---- zeta 组 ----\n' in text)
    check('statement 双引号原样', True,
          '    statement: "alpha-pkg 0.1.0 → 0.1.1"\n' in text)
    check('到期日落在条目上', True, '    expired_at: 2026-12-15\n' in text)
    check('历史注记落在末尾', True, text.rstrip().endswith('# 自测用历史注记'))

    broken = _fixture_ledger()
    broken['watermarks'][0]['fixed_versions'] = []
    check('fixed_versions 为空 ⇒ 判非法', True, bool(validate_ledger(broken)))
    broken = _fixture_ledger()
    broken['watermarks'][0]['cves'].append({'id': 'CVE-2026-11111', 'statement': 'x'})
    check('cves 重复 id ⇒ 判非法', True, bool(validate_ledger(broken)))
    broken = _fixture_ledger()
    broken['watermarks'][0]['fixed_versions'] = ['9.9.9-r9']
    check('fixed_versions 与 statement 矛盾 ⇒ 判非法', True, bool(validate_ledger(broken)))
    broken = _fixture_ledger()
    broken['watermarks'][0]['cves'][0]['id'] = 'CVE-bad'
    check('cve id 形态不对 ⇒ 判非法', True, bool(validate_ledger(broken)))

    # 真账本：合法、可渲染、渲染结果与磁盘一致
    try:
        real = load_ledger(ledger_path)
    except LedgerError as exc:
        check('真水位账可解析', 'ok', str(exc))
        real = None
    if real is not None:
        check('真水位账合法', [], validate_ledger(real))
        rendered = render(real)
        check('真账本渲染确定性', True, render(real) == rendered)
        if os.path.isfile(ignore_path):
            with io.open(ignore_path, encoding='utf-8') as fh:
                on_disk = fh.read()
            check('真账本渲染结果与磁盘豁免文件一致', True, on_disk == rendered)

    print('自测结果：通过 %d 项 / 失败 %d 项' % (passed, failed))
    return 0 if failed == 0 else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description='从水位账生成 Trivy 镜像豁免文件（台账 #113）')
    parser.add_argument('--ledger', default=os.path.join(ROOT, LEDGER_REL))
    parser.add_argument('--out', default=os.path.join(ROOT, IGNORE_REL))
    parser.add_argument('--check', action='store_true', help='只比对，不写盘；不一致 rc=1')
    parser.add_argument('--stdout', action='store_true', help='生成到 stdout，不写盘')
    parser.add_argument('--self-test', action='store_true', help='离线自测')
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test(args.ledger, args.out)
    try:
        return generate(args.ledger, args.out, check=args.check, to_stdout=args.stdout)
    except LedgerError as exc:
        print('错误：%s' % exc, file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
