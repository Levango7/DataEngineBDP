#!/usr/bin/env python3
"""Dockerfile base 镜像源结构门禁（台账 #72 的防回退那一环）。

守的是四条规则，每条都对应这轮真踩过的形态：
  R1 不许把加速器宿主硬写进 FROM（`FROM docker.m.daocloud.io/…`）——那样红的时候没有旋钮可切；
  R2 用了 `${BASE_REGISTRY}` 的文件必须声明 `ARG BASE_REGISTRY=<纯宿主>`，且**声明在第一条
     使用该变量的 FROM 之前**（Dockerfile 里 ARG 晚于 FROM 就是空字符串，构建期才炸）；
     默认值必须是纯宿主（不带 `/library`），因为 Docker Hub 官方镜像的规范路径是 `library/*`，
     宿主级语义才能整体切换（`docker.io` 同理成立）；
  R3 经 `${BASE_REGISTRY}` 拉的 Docker Hub 官方镜像必须写全 `library/`；
  R4 非 Docker Hub 宿主与本地构建产物走显式白名单，且白名单必须"被用到"——
     白名单里有没人用的条目也算门禁过期（判红），防止例外越积越多。

直写 Docker Hub 短名（`FROM python:3.11-slim`）**是允许的**：那 20 个文件本来就没挂加速器，
把它们也改钉镜像源等于把更多腿绑到同一个单点上，方向反了（#72 里写明了这个取舍）。

退出码：0 通过；1 有违规；2 扫描失效（找不到任何 Dockerfile ⇒ 门禁失明，绝不静默全绿）。
用法：
  python3 scripts/check-dockerfile-base-registry.py
  python3 scripts/check-dockerfile-base-registry.py --self-test
"""
import argparse
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ACCEL_HOSTS = ('docker.m.daocloud.io',)
OFFICIAL = {'maven', 'eclipse-temurin', 'golang', 'alpine', 'python', 'node', 'nginx',
            'busybox', 'ubuntu', 'redis', 'postgres', 'curlimages/curl'}
# 非 Docker Hub 的合法宿主（前缀匹配）与本地构建产物/集群镜像。
# 只列**真被用到**的：R4 会把无人使用的条目判红，所以这条例外表不会随时间堆成垃圾场。
EXTERNAL_HOST_PREFIXES = ('gcr.io/', 'kindest/')
LOCAL_ARTIFACT_RE = re.compile(r'^spark-cluster-[a-z0-9-]+:')

# 自检用的目标仓库（默认是真实仓库；--self-test 会换成合成件目录）
TARGET = [ROOT]


def _repo():
    return TARGET[0]


def dockerfiles(root):
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in
                       {'.git', 'node_modules', 'target', 'dist', 'build', 'venv',
                        '.venv', '__pycache__', 'htmlcov', 'site-packages'}]
        for fn in filenames:
            if fn == 'Dockerfile' or fn.startswith('Dockerfile.'):
                p = os.path.join(dirpath, fn)
                if os.path.isfile(p):
                    found.append(os.path.relpath(p, root).replace(os.sep, '/'))
    return sorted(found)


def check_file(root, rel):
    """返回该文件的违规列表 [(规则, 说明)]。"""
    txt = io.open(os.path.join(root, rel), encoding='utf-8', errors='replace').read()
    lines = txt.splitlines()
    bad = []

    for i, ln in enumerate(lines, 1):
        m = re.match(r'\s*FROM\s+(\S+)', ln)
        if not m:
            continue
        ref = m.group(1)
        # R1 硬写加速器宿主
        for host in ACCEL_HOSTS:
            if ref.startswith(host + '/'):
                bad.append(('R1', '第 %d 行把 %s 硬写进 FROM，应改为 ${BASE_REGISTRY}/…' % (i, host)))
        if ref.startswith('${BASE_REGISTRY}/') or ref.startswith('$BASE_REGISTRY/'):
            tail = ref.split('${BASE_REGISTRY}/')[-1].split('$BASE_REGISTRY/')[-1]
            # R3 官方镜像必须带 library/
            base_name = tail.split(':')[0].split('@')[0]
            if base_name in OFFICIAL:
                bad.append(('R3', '第 %d 行 `${BASE_REGISTRY}/%s` 缺 `library/`，'
                                  '宿主级 BASE_REGISTRY 下官方镜像的规范路径是 library/*' % (i, tail)))
            # R2 ARG 必须存在且早于本条 FROM
            decl = [(j, l) for j, l in enumerate(lines, 1)
                    if re.match(r'\s*ARG\s+BASE_REGISTRY=', l)]
            if not decl:
                bad.append(('R2', '第 %d 行用了 ${BASE_REGISTRY} 但整个文件没有 ARG BASE_REGISTRY 声明' % i))
            else:
                j, line = decl[0]
                if j > i:
                    bad.append(('R2', 'ARG BASE_REGISTRY 在第 %d 行、晚于第 %d 行的 FROM ⇒ Docker 会展开成空串' % (j, i)))
                val = line.split('=', 1)[1].strip()
                if '/' in val:
                    bad.append(('R2', 'ARG BASE_REGISTRY 默认值 "%s" 带了路径；应只写宿主，'
                                      '路径交给 FROM 写全' % val))

    # R4 白名单有效性：文件里出现的外部宿主/本地产物，必须能在白名单里找到
    for ln in lines:
        m = re.match(r'\s*FROM\s+(\S+)', ln)
        if not m:
            continue
        ref = m.group(1)
        if (ref.startswith('${BASE_REGISTRY}') or ref.startswith('$BASE_REGISTRY')
                or any(ref.startswith(h + '/') for h in ACCEL_HOSTS)):
            continue
        if ref.startswith(EXTERNAL_HOST_PREFIXES) or LOCAL_ARTIFACT_RE.match(ref):
            continue
        if '/' not in ref and ':' in ref:
            continue          # Docker Hub 短名（直连，允许）
        if '/' in ref and ref.split('/')[0] not in ('library',) and '.' not in ref.split('/')[0]:
            continue          # Docker Hub 命名空间镜像（minio/minio 等），直连允许
        bad.append(('R4', 'FROM "%s" 既不走 BASE_REGISTRY 也不在例外表里，需要显式归类' % ref))
    return bad


def used_exceptions(root, files):
    """白名单里哪些条目真被用到了（没被用到的 ⇒ 过期，判红）。"""
    hits = set()
    for rel in files:
        txt = io.open(os.path.join(root, rel), encoding='utf-8', errors='replace').read()
        for m in re.finditer(r'^FROM\s+(\S+)', txt, re.M):
            ref = m.group(1)
            for p in EXTERNAL_HOST_PREFIXES:
                if ref.startswith(p):
                    hits.add(p)
            if LOCAL_ARTIFACT_RE.match(ref):
                hits.add('spark-cluster-*')
    return hits


def check_compose(root):
    """R5：凡带 build 的 compose 服务，必须把 BASE_REGISTRY 传进去。

    否则选源只覆盖 direct buildx/docker build 那半边，compose 起的栈（Integration Test、
    Playwright 这两条这轮真红过的腿）仍会吊死在 ARG 默认值的单点上。
    """
    bad = []
    n_blocks = 0
    n_files = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in
                       {'.git', 'node_modules', 'target', 'dist', 'build', 'venv',
                        '.venv', '__pycache__', 'htmlcov', 'site-packages'}]
        for fn in filenames:
            if not (re.search(r'compose.*\.ya?ml$', fn, re.I)):
                continue
            p = os.path.join(dirpath, fn)
            try:
                import yaml
                data = yaml.safe_load(io.open(p, encoding='utf-8', errors='replace'))
            except Exception as exc:  # noqa: BLE001 解析失败必须说出来，不能跳过
                bad.append((os.path.relpath(p, root).replace(os.sep, '/'), 'R5',
                            'compose 解析失败（%s）——门禁不能对读不懂的文件放行' % str(exc)[:60]))
                continue
            svcs = (data or {}).get('services') or {}
            if not svcs:
                continue
            n_files += 1
            for name, spec in svcs.items():
                b = (spec or {}).get('build')
                if not b:
                    continue
                n_blocks += 1
                rel = os.path.relpath(p, root).replace(os.sep, '/')
                if isinstance(b, str):
                    bad.append((rel, 'R5',
                                '服务 %s 用 `build: <路径>` 简写形态，无处放 build args ⇒ '
                                '选源切不到它；请改成 mapping 并加 args' % name))
                    continue
                args = b.get('args')
                keys = set()
                if isinstance(args, list):
                    for a in args:
                        if isinstance(a, str):
                            keys.add(a.split('=', 1)[0].strip())
                        elif isinstance(a, dict):
                            keys.update(a.keys())
                elif isinstance(args, dict):
                    keys.update(args.keys())
                if 'BASE_REGISTRY' not in keys:
                    bad.append((rel, 'R5',
                                '服务 %s 的 build 未传 BASE_REGISTRY ⇒ compose 侧仍在吃 ARG 默认值，'
                                '加速器红时切不过去' % name))
    return bad, n_blocks, n_files


def run():
    root = _repo()
    files = dockerfiles(root)
    if not files:
        print('::error::扫描失效：%s 下没找到任何 Dockerfile（门禁失明，不放行）' % root)
        return 2
    viol = []
    for rel in files:
        for rule, msg in check_file(root, rel):
            viol.append((rel, rule, msg))
    stale = sorted((set(EXTERNAL_HOST_PREFIXES) | {'spark-cluster-*'}) - used_exceptions(root, files))
    if stale:
        viol.append(('<例外表>', 'R4', '白名单条目无人使用，请删除：%s' % ', '.join(stale)))
    c_bad, c_blocks, c_files = check_compose(root)
    viol.extend(c_bad)
    if c_files == 0:
        print('::error::扫描失效：一个 compose 文件都没解析出 services（门禁失明，不放行）')
        return 2
    n_bare = sum(1 for rel in files
                 for m in re.finditer(r'^FROM\s+([a-z0-9][\w.-]*:\S+)',
                                      io.open(os.path.join(root, rel), encoding='utf-8',
                                              errors='replace').read(), re.M))
    print('check-dockerfile-base-registry.py 目标仓库：%s' % root)
    print('  Dockerfile %d 个；FROM 检查完成；直连 Docker Hub 短名 %d 条（允许，但不受选源切换影响）'
          % (len(files), n_bare))
    print('  compose %d 个文件 / %d 个 build 服务已纳入 R5 检查' % (c_files, c_blocks))
    if viol:
        for rel, rule, msg in viol:
            print('  [违规][%s] %s: %s' % (rule, rel, msg))
        print('  ⇒ 共 %d 条' % len(viol))
        return 1
    print('  ✓ base 镜像源形态合规（无硬编码加速器宿主、ARG 语义统一、例外表无过期、compose build args 齐全）')
    return 0


def self_test():
    """合成正反例：每条规则都要"坏形态被抓、好形态不误报"，且证明扫的确实是合成件。"""
    import tempfile
    tmp = tempfile.mkdtemp(prefix='base-reg-selftest-')
    ok = True
    try:
        def put(rel, text):
            p = os.path.join(tmp, rel)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            io.open(p, 'w', encoding='utf-8', newline='').write(text)

        put('good/Dockerfile', 'ARG BASE_REGISTRY=docker.m.daocloud.io\n'
                               'FROM ${BASE_REGISTRY}/library/golang:1.26-alpine AS builder\n'
                               'FROM ${BASE_REGISTRY}/library/alpine:3.19 AS runtime\n')
        put('r1/Dockerfile', 'FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 AS b\n'
                             'FROM ${BASE_REGISTRY}/library/eclipse-temurin:17-jre\n')
        put('r2-late/Dockerfile', 'FROM ${BASE_REGISTRY}/library/python:3.11-slim\n'
                                  'ARG BASE_REGISTRY=docker.m.daocloud.io\n')
        put('r2-path/Dockerfile', 'ARG BASE_REGISTRY=docker.m.daocloud.io/library\n'
                                  'FROM ${BASE_REGISTRY}/library/python:3.11-slim\n')
        put('r2-nodecl/Dockerfile', 'FROM ${BASE_REGISTRY}/library/python:3.11-slim\n')
        put('r3/Dockerfile', 'ARG BASE_REGISTRY=docker.m.daocloud.io\n'
                             'FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-17\n')
        put('ext/Dockerfile', 'FROM gcr.io/distroless/static-debian12:nonroot\n')
        put('bare/Dockerfile', 'FROM python:3.11-slim AS base\n')
        # R5 的合成件：compose 的 build 有没有传 BASE_REGISTRY
        put('c-missing/docker-compose.yml',
            'services:\n  svc:\n    build:\n      context: .\n      dockerfile: Dockerfile\n')
        put('c-ok/docker-compose.yml',
            'x-base-args: &base-args\n  - BASE_REGISTRY=${BASE_REGISTRY:-docker.m.daocloud.io}\n'
            'services:\n  svc:\n    build:\n      context: .\n      args: *base-args\n')
        put('c-shorthand/docker-compose.yml',
            'services:\n  svc:\n    build: ./ctx\n')

        def rules(root):
            out = []
            for rel in dockerfiles(root):
                for rule, _msg in check_file(root, rel):
                    out.append((rel.split('/')[0], rule))
            return sorted(out)

        expect = {
            'R1 硬写加速器要被抓': ('r1', 'R1'),
            'R2 ARG 晚于 FROM 要被抓': ('r2-late', 'R2'),
            'R2 默认值带路径要被抓': ('r2-path', 'R2'),
            'R2 没有 ARG 声明要被抓': ('r2-nodecl', 'R2'),
            'R3 官方镜像缺 library/ 要被抓': ('r3', 'R3'),
        }
        got = rules(tmp)
        for name, pair in expect.items():
            hit = pair in got
            print('  [%s] %s' % ('PASS' if hit else 'FAIL', name))
            ok = ok and hit
        for name, pair in [('R2 正例不误报', ('good', 'R2')),
                           ('R3 正例不误报', ('good', 'R3')),
                           ('外部宿主 gcr.io 不误报', ('ext', 'R4')),
                           ('Docker Hub 短名不误报', ('bare', 'R4'))]:
            miss = pair not in got
            print('  [%s] %s' % ('PASS' if miss else 'FAIL', name))
            ok = ok and miss
        # 合成件必须真被扫到（否则上面全是假绿）
        # R5：compose 侧三种形态各判各的
        c_findings, c_blocks, c_files = check_compose(tmp)
        cset = {rel.split('/')[0] for rel, _rule, _m in c_findings}
        for name, want in [('R5 build 缺 BASE_REGISTRY 要被抓', 'c-missing'),
                           ('R5 `build: ./ctx` 简写形态要被抓', 'c-shorthand')]:
            hit = want in cset
            print('  [%s] %s' % ('PASS' if hit else 'FAIL', name))
            ok = ok and hit
        good_ok = 'c-ok' not in cset
        print('  [%s] R5 锚点引用形态不误报' % ('PASS' if good_ok else 'FAIL'))
        ok = ok and good_ok
        cnt_ok = (c_blocks == 3 and c_files == 3)
        print('  [%s] compose build 块计数正确（%d 块 / %d 文件，期望 3/3）'
              % ('PASS' if cnt_ok else 'FAIL', c_blocks, c_files))
        ok = ok and cnt_ok
        n = len(dockerfiles(tmp))
        print('  [%s] 合成夹具真的被扫到（%d 个 Dockerfile）' % ('PASS' if n == 8 else 'FAIL', n))
        ok = ok and n == 8
        # 扫描集为空必须判红
        empty = os.path.join(tmp, 'empty-dir')
        os.makedirs(empty, exist_ok=True)
        TARGET[0] = empty
        rc_empty = run()
        print('  [%s] 空 Dockerfile 集合判红（rc=%d，期望 2）' % ('PASS' if rc_empty == 2 else 'FAIL', rc_empty))
        ok = ok and rc_empty == 2
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)
    print('SELFTEST target=%s（合成夹具，非产品文件）' % tmp)
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--self-test', action='store_true')
    a = ap.parse_args()
    if a.self_test:
        sys.exit(self_test())
    sys.exit(run())


if __name__ == '__main__':
    main()
