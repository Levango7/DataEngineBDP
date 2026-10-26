#!/usr/bin/env python3
"""部署配置里引用的自研镜像，必须是本仓真能产出的坐标。

背景（台账 #65）：`design/deploy/values/env/*` 被 ArgoCD 直接引用（`design/deploy/argocd/applications.yaml`），
却把自研镜像钉成 `docker.m.daocloud.io/sq-*`（CI 从不往该宿主推）、`sq-encapsulation`（仓内无此模块）、
`0.1.0`（CI 只打 `latest` 与 40 位 sha）。三者任一都让 `helm install` 必然 ImagePullBackOff，
而既有门禁只查反方向（有 Dockerfile 却没有部署单元，见 `check-deployable-coverage.py`）。

判据——一条"自研镜像引用"指 image.repository 满足任一条：
  ① 宿主是 `harbor.shuqing.io` 或 `ghcr.io/<组织>`；
  ② 末段以 `sq-` 开头（无论宿主）；
  ③ 无宿主且末段等于某个可构建模块名。
自研引用必须同时满足：宿主 = `ghcr.io`、组织 = `levango7`、名字 = `sq-<可构建模块目录名>`、
tag ∈ {`latest`, 40 位十六进制 sha}。第三方镜像（apache/kafka、goharbor/harbor-core…）一律跳过。

已知局限（诚实声明）：`global.imageRegistry` 参与的拼装发生在渲染期，静态判据只看写死了宿主或
带 `sq-` 前缀的引用；裸名（无宿主）且末段不是模块名的，本门禁判不出来。

用法：
  python3 scripts/check-image-reference.py                  # 校验
  python3 scripts/check-image-reference.py --report         # 人读明细
  python3 scripts/check-image-reference.py --write-baseline # 用当前缺口重写基线（棘轮起点）
  python3 scripts/check-image-reference.py --self-test      # 合成夹具双向自测
退出码：0 通过；1 有新缺口或基线过期；2 扫描失效（没找到配置或模块 ⇒ 门禁失明）。
"""

import argparse
import io
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ORG = 'levango7'
GHCR = 'ghcr.io'
OURS_HOSTS = ('harbor.shuqing.io', GHCR + '/' + ORG)
BASELINE_REL = 'docs/deploy-image-reference-baseline.yaml'
SKIP_DIRS = {'.git', 'node_modules', 'target', 'dist', 'build', 'venv', '__pycache__'}
CONFIG_DIRS = (
    os.path.join('design', 'deploy', 'charts'),
    os.path.join('design', 'deploy', 'values'),
)
# 顶层 values 文件：README 教运维 `-f values-base.yaml`，但它不在 CONFIG_DIRS 的子目录里，
# 早先整份文件都在门禁视野之外（台账 #67 的盲区正是这里，不在"裸 image: 形态"）。
CONFIG_FILES = (
    os.path.join('design', 'deploy', 'values-base.yaml'),
)
SHA_RE = re.compile(r'^[0-9a-f]{40}$')
REPO_RE = re.compile(r'^(\s*)repository:\s*"?([^"\n]+?)"?\s*$')
TAG_RE = re.compile(r'^\s*tag:\s*"?([^"\n]+?)"?\s*$')
# 裸 image: 形态，如 `image: "sq-encapsulation:0.1.0"` 或 `image: sq-encapsulation:0.1.0`
# name 与 tag 同行，不用向下查找 tag 行。
IMAGE_RE = re.compile(r'^(\s*)image:\s*"?([^"\n:]+?):([^"\n]+?)"?\s*$')


def buildable_modules(root=ROOT):
    """存在 Dockerfile 的 platform 目录名 ⇒ build.yml 能产出 sq-<该名字>。"""
    out = set()
    base = os.path.join(root, 'platform')
    if not os.path.isdir(base):
        return out
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        if any(f == 'Dockerfile' or f.startswith('Dockerfile.') for f in filenames):
            out.add(os.path.basename(dirpath))
    return out


def _split_host(repo):
    first = repo.split('/')[0]
    if '.' in first or ':' in first:
        return first
    return None


def is_ours(repo, modules):
    host = _split_host(repo)
    last = repo.rstrip('/').split('/')[-1]
    if host and any(repo == h or repo.startswith(h + '/') for h in OURS_HOSTS):
        return True
    if last.startswith('sq-'):
        return True
    if host is None and last in modules:
        return True
    return False


def _problems(repo, tag, modules):
    host = _split_host(repo)
    probs = []
    name = repo.rstrip('/').split('/')[-1]
    if host is None:
        # 无宿主 ⇒ 渲染期由 global.imageRegistry 拼装，静态判据只能验名字
        if not name.startswith('sq-'):
            probs.append('裸名缺 sq- 前缀（build.yml 打的是 sq-<模块目录名>）')
        elif name[3:] not in modules:
            probs.append('仓内没有名为 %s 的可构建模块（无 platform/**/%s/Dockerfile）' % (name[3:], name[3:]))
        return probs
    if host != GHCR:
        probs.append('宿主是 %r，不是 %s（本仓唯一产出方是 build.yml 的 GHCR 推送）' % (host, GHCR))
    elif not repo.startswith(GHCR + '/' + ORG + '/'):
        probs.append('GHCR 组织不是 %s' % ORG)
    if not name.startswith('sq-'):
        probs.append('镜像名缺 sq- 前缀（build.yml 打的是 sq-<模块目录名>）')
    elif name[3:] not in modules:
        probs.append('仓内没有名为 %s 的可构建模块（无 platform/**/%s/Dockerfile）' % (name[3:], name[3:]))
    if tag == '-':
        probs.append('未写 tag（渲染期回落 latest，不可审计）')
    elif tag != 'latest' and not SHA_RE.match(tag):
        probs.append('tag %r 不是 CI 会产出的形态（latest 或 40 位 sha）' % tag)
    return probs


def _scan_file(p, relpath, modules, findings):
    """扫描单个 YAML：repository:+tag 形态与裸 image:name:tag 形态都算。"""
    lines = io.open(p, encoding='utf-8-sig', errors='replace').read().split('\n')
    for i, line in enumerate(lines):
        m = REPO_RE.match(line)
        if m:
            repo = m.group(2).strip()
            if not repo or '$' in repo or '{{' in repo:
                continue
            tag = '-'
            for j in range(i + 1, min(i + 4, len(lines))):
                t = TAG_RE.match(lines[j])
                if t:
                    tag = t.group(1).strip()
                    break
            if not is_ours(repo, modules):
                continue
            probs = _problems(repo, tag, modules)
            if probs:
                findings.append({'file': relpath, 'line': i + 1, 'repository': repo,
                                 'tag': tag, 'reasons': probs})
            continue
        # 裸 image: 形态，name 与 tag 同行（如 `image: "sq-encapsulation:0.1.0"`）
        m2 = IMAGE_RE.match(line)
        if not m2:
            continue
        repo = m2.group(2).strip()
        tag = m2.group(3).strip()
        if not repo or '$' in repo or '{{' in repo:
            continue
        if not is_ours(repo, modules):
            continue
        probs = _problems(repo, tag, modules)
        if probs:
            findings.append({'file': relpath, 'line': i + 1, 'repository': repo,
                             'tag': tag, 'reasons': probs})


def scan_files(root=ROOT):
    """产出 (绝对路径, 相对路径) 的可扫描清单：charts/ + values/ 目录树，外加顶层 values 文件。"""
    out = []
    for rel in CONFIG_DIRS:
        d = os.path.join(root, rel)
        if not os.path.isdir(d):
            continue
        for dirpath, dirnames, filenames in os.walk(d):
            dirnames[:] = [x for x in dirnames if x not in SKIP_DIRS]
            for fn in sorted(filenames):
                if fn.endswith(('.yaml', '.yml')):
                    p = os.path.join(dirpath, fn)
                    out.append((p, os.path.relpath(p, root).replace(os.sep, '/')))
    for rel in CONFIG_FILES:
        p = os.path.join(root, rel)
        if os.path.isfile(p):
            out.append((p, rel.replace(os.sep, '/')))
    return out


def scan(root=ROOT):
    modules = buildable_modules(root)
    findings, scanned = [], 0
    for p, relpath in scan_files(root):
        scanned += 1
        _scan_file(p, relpath, modules, findings)
    return findings, scanned, modules
    return findings, scanned, modules


def read_baseline(root=ROOT):
    path = os.path.join(root, BASELINE_REL)
    if not os.path.isfile(path):
        return []
    out, cur = [], None
    for line in io.open(path, encoding='utf-8', errors='replace'):
        s = line.rstrip()
        if s.startswith('- file:'):
            cur = {'file': s.split(':', 1)[1].strip().strip('"')}
            out.append(cur)
        elif cur is not None and s.strip().startswith('repository:'):
            cur['repository'] = s.split(':', 1)[1].strip().strip('"')
        elif cur is not None and s.strip().startswith('tag:'):
            cur['tag'] = s.split(':', 1)[1].strip().strip('"')
    return [b for b in out if b.get('file') and b.get('repository')]


def write_baseline(findings, root=ROOT):
    head = [
        '# 部署配置里"自研镜像引用"的已知缺口基线（scripts/check-image-reference.py --write-baseline 生成）',
        '# 含义：这些引用指向本仓产不出的坐标（错宿主 / 错模块名 / 错 tag），',
        '#       门禁对它们只登记不判红；新增缺口或本表条目已被修掉都会判红（只减不增）。',
        '# 销账条件：把 repository 改成 ghcr.io/%s/sq-<可构建模块> 且 tag ∈ {latest, 40 位 sha}，' % ORG,
        '#           然后重跑 --write-baseline（或直接删除对应条目）。',
        'known:',
    ]
    body = []
    for f in sorted(findings, key=lambda x: (x['file'], x['repository'])):
        body.append('- file: %s' % f['file'])
        body.append('  repository: "%s"' % f['repository'])
        body.append('  tag: "%s"' % f['tag'])
    with io.open(os.path.join(root, BASELINE_REL), 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(head + body) + '\n')
    return len(findings)


def _key(f):
    return (f['file'], f['repository'], f['tag'])


def verdict(findings, baseline):
    bset = {(b['file'], b['repository'], b.get('tag', '-')) for b in baseline}
    fset = {_key(f) for f in findings}
    return sorted(fset - bset), sorted(bset - fset)


def self_test():
    tmp = os.path.join(ROOT, 'scripts', '.tmp-selftest-image-ref')
    if os.path.isdir(tmp):
        shutil.rmtree(tmp)
    results = []
    try:
        env = os.path.join(tmp, 'design', 'deploy', 'values', 'env', 'dev')
        os.makedirs(env)
        # 夹具自带的可构建模块集合：scan(tmp) 会读 tmp/platform/**/Dockerfile
        pf = os.path.join(tmp, 'platform', 'encaps-layer')
        os.makedirs(pf)
        io.open(os.path.join(pf, 'Dockerfile'), 'w', encoding='utf-8').write('FROM scratch\n')
        io.open(os.path.join(env, 'bad-values.yaml'), 'w', encoding='utf-8').write(
            'image:\n  repository: "docker.m.daocloud.io/sq-encapsulation"\n  tag: "0.1.0"\n'
            '  pullPolicy: IfNotPresent\n')
        io.open(os.path.join(env, 'good-values.yaml'), 'w', encoding='utf-8').write(
            'image:\n  repository: "ghcr.io/levango7/sq-encaps-layer"\n  tag: "latest"\n'
            '  pullPolicy: Always\n')
        io.open(os.path.join(env, 'third-values.yaml'), 'w', encoding='utf-8').write(
            'image:\n  repository: "apache/kafka"\n  tag: "3.8.1"\n')
        io.open(os.path.join(env, 'wrongorg-values.yaml'), 'w', encoding='utf-8').write(
            'image:\n  repository: "ghcr.io/someone-else/sq-encaps-layer"\n  tag: "latest"\n')
        mods = buildable_modules(tmp)
        if 'encaps-layer' not in mods:
            results.append(('可构建模块集合非空且含 encaps-layer', False))
        else:
            f, scanned, _ = scan(tmp)
            got = {_key(x) for x in f}
            badhit = any(k[1] == 'docker.m.daocloud.io/sq-encapsulation' for k in got)
            goodpass = not any(k[0].endswith('good-values.yaml') for k in got)
            thirdpass = not any(k[0].endswith('third-values.yaml') for k in got)
            reasons = [x for x in f if x['file'].endswith('bad-values.yaml')]
            nreasons = len(reasons[0]['reasons']) if reasons else 0
            results.append(('坏引用被抓到（错宿主 + 错模块名 + 错 tag）', badhit))
            results.append(('好引用放行（ghcr + sq-<模块> + latest）', goodpass))
            results.append(('第三方引用放行（apache/kafka）', thirdpass))
            results.append(('一次命中 3 条理由', nreasons >= 3))
            wrongorg = any(k[0].endswith('wrongorg-values.yaml') for k in got)
            results.append(('GHCR 组织写错也判红（守 startswith 分支）', wrongorg))
            new, stale = verdict(f, [{'file': 'design/deploy/values/env/dev/ghost.yaml',
                                      'repository': 'ghcr.io/levango7/sq-x', 'tag': 'latest'}])
            results.append(('基线过期判红', bool(stale)))
            new2, stale2 = verdict(f, [])
            results.append(('无基线时缺口算新增', bool(new2)))
            n, _, _ = scan(tmp)  # sanity: scan 自报命中数
            results.append(('自测确实扫到文件（scanned>0）', scanned > 0 and len(n) > 0))
    finally:
        shutil.rmtree(tmp)
        results.append(('自测零残留', not os.path.isdir(tmp)))
    ok = True
    for name, res in results:
        print('  [%s] %s' % ('PASS' if res else 'FAIL', name))
        ok = ok and bool(res)
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--report', action='store_true')
    ap.add_argument('--self-test', action='store_true')
    ap.add_argument('--write-baseline', action='store_true')
    a = ap.parse_args()
    print('check-image-reference.py 目标仓库：%s' % ROOT)
    if a.self_test:
        return 0 if self_test() else 1
    findings, scanned, modules = scan()
    if not modules:
        print('::error::没找到任何可构建模块（platform/**/Dockerfile 为空）⇒ 门禁失明')
        return 2
    if scanned == 0:
        print('::error::没扫描到任何部署配置文件 ⇒ 门禁失明')
        return 2
    if a.write_baseline:
        n = write_baseline(findings)
        print('已写入基线：%d 条已知缺口 → %s' % (n, BASELINE_REL))
        return 0
    baseline = read_baseline()
    new, stale = verdict(findings, baseline)
    print('扫描 %d 个配置文件；可构建模块 %d 个；自研引用缺口 %d 条；基线在册 %d 条'
          % (scanned, len(modules), len(findings), len(baseline)))
    if a.report:
        for f in findings:
            print('  %s:%d  %s:%s' % (f['file'], f['line'], f['repository'], f['tag']))
            for r in f['reasons']:
                print('      - %s' % r)
    for s in stale:
        print('::error::基线条目已不存在（该引用已被修掉，请从基线删除）：%s %s:%s' % s)
    for k in new:
        print('::error::新增缺口：%s %s:%s ⇒ 自研镜像必须钉在 %s/%s/sq-<模块> + latest/40位sha'
              % (k[0], k[1], k[2], GHCR, ORG))
    if new or stale:
        return 1
    print('✓ 自研镜像引用全部可产出（或已在基线内）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
