#!/usr/bin/env python3
"""漂移门禁：水位账 → Trivy 镜像豁免文件必须逐字一致（台账 #113）。

豁免的意图记在 `docs/trivy-image-watermark.yaml`（包 + 版本水位），
`docs/trivy-image-ignore.yaml` 是它的**派生结果**（由 scripts/gen-trivy-ignore.py 生成）。
本门禁保证两者不脱节：改了水位忘了重新生成 ⇒ 判红；手改了生成物 ⇒ 判红。

退出码
------
  0  一致（生成物 == 水位账派生结果）
  1  漂移（id 多 / 少、statement 变了、到期日变了、或字节不一致）
  2  水位账本身不合法（解析失败 / 字段缺失 / 语义矛盾）⇒ 门禁失明，不放行

用法：
  python3 scripts/check-trivy-ignore-drift.py             # 真判定
  python3 scripts/check-trivy-ignore-drift.py --self-test # 离线双向夹具（不碰网络）
"""
import argparse
import importlib.util
import io
import os
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEDGER_REL = os.path.join('docs', 'trivy-image-watermark.yaml')
IGNORE_REL = os.path.join('docs', 'trivy-image-ignore.yaml')


def _load_generator():
    """按路径加载 scripts/gen-trivy-ignore.py（文件名带连字符，不能直接 import）。"""
    path = os.path.join(ROOT, 'scripts', 'gen-trivy-ignore.py')
    spec = importlib.util.spec_from_file_location('gen_trivy_ignore', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


GEN = _load_generator()


def parse_entries(text):
    """解析 ignore 文件文本 ⇒ {id: {'statement':…, 'expired_at':…}}；解析失败返回 None。"""
    try:
        import yaml
    except ImportError:  # pragma: no cover
        return None
    try:
        doc = yaml.safe_load(text)
    except Exception:
        return None
    if not isinstance(doc, dict):
        return None
    out = {}
    for item in doc.get('vulnerabilities') or []:
        if not isinstance(item, dict):
            return None
        cid = item.get('id')
        if not isinstance(cid, str):
            return None
        out[cid] = {
            'statement': item.get('statement'),
            'expired_at': str(item.get('expired_at')),
        }
    return out


def check_drift(ledger_path, ignore_path):
    """返回 (rc, 报告行列表)。"""
    try:
        doc = GEN.load_ledger(ledger_path)
    except GEN.LedgerError as exc:
        return 2, ['水位账不可用：%s' % exc]
    errs = GEN.validate_ledger(doc)
    if errs:
        report = ['水位账不合法（语义/字段层）：']
        report.extend('  - %s' % e for e in errs)
        return 2, report

    expected = GEN.render(doc)
    if not os.path.isfile(ignore_path):
        return 1, ['漂移：豁免文件不存在：%s' % ignore_path]
    with io.open(ignore_path, encoding='utf-8') as fh:
        actual = fh.read()
    if actual == expected:
        count = sum(len(r.get('cves') or []) for r in GEN.sorted_records(doc))
        return 0, ['水位账与豁免文件逐字一致（%d 条水位记录 / %d 条 CVE）'
                   % (len(GEN.sorted_records(doc)), count)]

    report = ['漂移：%s 与水位账派生结果不一致（改水位后请跑 scripts/gen-trivy-ignore.py）'
              % ignore_path]
    exp = parse_entries(expected)
    act = parse_entries(actual)
    if exp is None:
        report.append('  生成结果解析失败（生成器有 bug，请报 issue）')
    if act is None:
        report.append('  磁盘上的豁免文件不是合法 YAML（手改坏了？）')
    if exp is not None and act is not None:
        only_exp = sorted(set(exp) - set(act))
        only_act = sorted(set(act) - set(exp))
        if only_exp:
            report.append('  生成物有、磁盘上没有的 id（多出 %d 条）：%s'
                          % (len(only_exp), ', '.join(only_exp)))
        if only_act:
            report.append('  磁盘上有、生成物没有的 id（缺少 %d 条）：%s'
                          % (len(only_act), ', '.join(only_act)))
        for cid in sorted(set(exp) & set(act)):
            if exp[cid]['statement'] != act[cid]['statement']:
                report.append('  %s 的 statement 变了：' % cid)
                report.append('    生成物：%s' % exp[cid]['statement'])
                report.append('    磁盘上：%s' % act[cid]['statement'])
            if exp[cid]['expired_at'] != act[cid]['expired_at']:
                report.append('  %s 的到期日变了：生成物 %s / 磁盘上 %s'
                              % (cid, exp[cid]['expired_at'], act[cid]['expired_at']))
        if not (only_exp or only_act):
            report.append('  id 集合一致 ⇒ 差异在 statement / 到期日 / 排版上')
    report.append('  （字节级差异：生成物 %d 字节 / 磁盘上 %d 字节）'
                  % (len(expected.encode('utf-8')), len(actual.encode('utf-8'))))
    return 1, report


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

    print('-- 漂移门禁自测（离线双向夹具，不碰网络）--')
    tmp = tempfile.mkdtemp(prefix='trivy-drift-selftest.')
    try:
        led = os.path.join(tmp, 'trivy-image-watermark.yaml')
        ign = os.path.join(tmp, 'trivy-image-ignore.yaml')
        shutil.copyfile(ledger_path, led)
        shutil.copyfile(ignore_path, ign)

        rc, report = check_drift(led, ign)
        check('真账本 + 真生成物 ⇒ 绿（rc=0）', 0, rc)
        if rc != 0:
            for line in report:
                print('      %s' % line)

        # ① 改水位（只动 fixed_versions，不动 statement）⇒ 必须判红（语义层 rc=2）
        with io.open(led, encoding='utf-8') as fh:
            ledger_text = fh.read()
        mutated = ledger_text.replace("      - '2.10.0-r0'\n", "      - '2.9.9-r9'\n", 1)
        check('变异①真的改到了水位账的 fixed_versions', True, mutated != ledger_text)
        with io.open(led, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(mutated)
        rc, report = check_drift(led, ign)
        check('变异①改水位 ⇒ 判红（语义层 rc=2）', 2, rc)
        print('      rc=%s ｜ %s' % (rc, report[0] if report else ''))
        for line in report[1:3]:
            print('      %s' % line)

        # ①b 删掉某条水位的 fixed_versions ⇒ 同样判红
        mutated_del = ledger_text.replace("      - '2.10.0-r0'\n", '', 1)
        with io.open(led, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(mutated_del)
        rc, report = check_drift(led, ign)
        check('变异①b删掉 fixed_versions ⇒ 判红（rc≠0）', True, rc != 0)
        print('      rc=%s ｜ %s' % (rc, report[1] if len(report) > 1 else ''))

        # ② 改生成物（手改一条 id）⇒ 必须判红
        with io.open(led, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(ledger_text)
        with io.open(ign, encoding='utf-8') as fh:
            ignore_text = fh.read()
        mutated_ignore = ignore_text.replace('- id: CVE-2026-117610',
                                             '- id: CVE-2026-117611', 1)
        check('变异②真的改到了生成物', True, mutated_ignore != ignore_text)
        with io.open(ign, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(mutated_ignore)
        rc, report = check_drift(led, ign)
        check('变异②改生成物 ⇒ 判红（rc=1）', 1, rc)
        print('      rc=%s ｜ %s' % (rc, report[0] if report else ''))
        for line in report[1:3]:
            print('      %s' % line)

        # ③ 改回 ⇒ 必须绿
        with io.open(ign, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(ignore_text)
        rc, _ = check_drift(led, ign)
        check('改回 ⇒ 绿（rc=0）', 0, rc)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print('自测结果：通过 %d 项 / 失败 %d 项' % (passed, failed))
    return 0 if failed == 0 else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description='Trivy 镜像豁免水位账漂移门禁（台账 #113）')
    parser.add_argument('--ledger', default=os.path.join(ROOT, LEDGER_REL))
    parser.add_argument('--ignore', default=os.path.join(ROOT, IGNORE_REL))
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test(args.ledger, args.ignore)

    rc, report = check_drift(args.ledger, args.ignore)
    for line in report:
        stream = sys.stdout if rc == 0 else sys.stderr
        print(line, file=stream)
    return rc


if __name__ == '__main__':
    raise SystemExit(main())
