#!/usr/bin/env python3
"""Dockerfile base 镜像源结构门禁（台账 #72 的防回退那一环）。

守的是八条规则（R1–R8），每条都对应这轮真踩过的形态：
  R1 不许把加速器宿主硬写进 FROM（`FROM docker.m.daocloud.io/…`）——那样红的时候没有旋钮可切；
  R2 用了 `${BASE_REGISTRY}` 的文件必须声明 `ARG BASE_REGISTRY=<纯宿主>`，且**声明在第一条
     使用该变量的 FROM 之前**（Dockerfile 里 ARG 晚于 FROM 就是空字符串，构建期才炸）；
     默认值必须是纯宿主（不带 `/library`），因为 Docker Hub 官方镜像的规范路径是 `library/*`，
     宿主级语义才能整体切换（`docker.io` 同理成立）；
  R3 经 `${BASE_REGISTRY}` 拉的 Docker Hub 官方镜像必须写全 `library/`；
  R4 非 Docker Hub 宿主与本地构建产物走显式白名单，且白名单必须"被用到"——
     白名单里有没人用的条目也算门禁过期（判红），防止例外越积越多。
  R6 **禁直连 Docker Hub 的 base**（`FROM python:3.11-slim`、`FROM bitnami/kafka:3.7` 这类）：
     这类写法绕开选源，Hub 侧限流或网络抖动时切不到加速器上。

R6 是 #72 的收尾一刀，**收回本文件原先"直写短名是允许的"这个取舍**：当时顾虑是"把更多腿绑到
同一个单点"；实测下来短名形态本身就是第二个单点（直连 `registry-1.docker.io`，匿名拉取还叠一层
限流），而 `${BASE_REGISTRY}` 的末端候选就是 `docker.io`——改完之后两条腿都在，红一个换另一个。
唯一保留的例外是 `scratch`（Docker 内置基座，不联网）。

R7/R8 管的是"选源器有没有被所有构建腿接上"这一半（Dockerfile 写得再规范，腿不传参也白搭）：
2026-10-21 实测 `Build & Push Images` 连续红，根因是两条 `build-push-action` 前端腿没有
`build-args`，加上 13 处 `${BASE_REGISTRY:-docker.m.daocloud.io}` 兜底选回了刚 DNS 失败的加速器。

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
# 扫描时统一跳过的目录（构建产物与依赖树，里面没有本仓的构建调用点）
SKIP_DIRS = {'.git', 'node_modules', 'target', 'dist', 'build', 'venv',
             '.venv', '__pycache__', 'htmlcov', 'site-packages'}

# Docker 内置基座：根本不联网解析，不能也不需要挂任何宿主
NO_PULL_BASES = {'scratch'}

# R7/R8 用的形态
BUILD_PUSH_ACTION = 'docker/build-push-action'
BUILD_LEG_RE = re.compile(r'docker\s+(?:buildx\s+build|build)\b')
BUILD_ARG_RE = re.compile(r'--build-arg[\s=]+["\']?BASE_REGISTRY\b')
FALLBACK_RE = re.compile(r'\$\{\s*((?:\w*_)?BASE_REGISTRY|TRIVY_BASE_HOST)\s*:-\s*([^}]+)\}')
# 明确豁免的构建腿（(工作流相对路径, job/step 名)）——留空即"所有腿都要参选源"。
# 加条目必须写清理由，且 R4 那条例外表的规矩同样适用：无人使用的豁免也算过期。
LEG_EXEMPT = set()

# R9：`docker compose … up` 顺带构建的那些腿必须走 scripts/compose-retry.sh。
# 判据范围是同一个 run 块（与 R7 对 docker build 的口径一致）：一块里多条 up 只要接了一次就算过，
# 因为 ci.yml 的第 1 次尝试故意保留盲重试（它还覆盖健康检查超时这类非传输形态），第 2 次才走包装器。
COMPOSE_UP_RE = re.compile(r'docker\s+compose\b.*?\bup\b')
COMPOSE_WRAPPER = 'compose-retry.sh'
# 加豁免条目必须写清理由，且同样受 R4「无人使用的豁免也算过期」约束。
COMPOSE_LEG_EXEMPT = {
    # local-e2e 的 infra 栈：只在 workflow_dispatch 且 run_infra=true 时跑，拉的是 trino 等公共
    # 镜像，不读 BASE_REGISTRY 选源 ⇒ 换源重试对它没有意义（同参重试价值也低，先不纳入）。
    ('.github/workflows/local-e2e.yml', 'infra-integration/Start Docker Compose infra'),
}

# 工作流之外的 shell 脚本构建腿豁免（相对路径 → 理由）。同样受"没人用就判红"约束。
SCRIPT_EXEMPT = {
    'ske/node-image/build.sh': '其 Dockerfile 的 FROM 是 kindest/node:v1.30.0（外部宿主，'
                               '见 R4 例外表），根本不从 Docker Hub 拉 base ⇒ 无源可选',
}


def _build_args_text(step):
    """把 build-push-action 的 with.build-args 归一成一段文本（str / mapping / list 都吃）。"""
    with_map = step.get('with')
    if not isinstance(with_map, dict):
        return ''
    raw = with_map.get('build-args')
    if raw is None:
        return ''
    if isinstance(raw, str):
        return raw
    if isinstance(raw, dict):
        return '\n'.join('%s=%s' % (k, v) for k, v in raw.items())
    if isinstance(raw, list):
        return '\n'.join(str(a) for a in raw)
    return str(raw)

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
        if ref in NO_PULL_BASES:
            continue              # scratch 之类别名：Docker 内置，根本不联网解析
        if ref.startswith(EXTERNAL_HOST_PREFIXES) or LOCAL_ARTIFACT_RE.match(ref):
            continue
        bad.append(('R6', 'FROM "%s" 直连 Docker Hub、不参选源 ⇒ 加速器或 Hub 一侧出问题时这条切不过去；'
                          '请改成 ARG BASE_REGISTRY + `FROM ${BASE_REGISTRY}/library/…` 形态'
                          '（本地构建产物请让名字匹配 spark-cluster-* 例外表）' % ref))
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


def check_workflows(root):
    """R7/R8：工作流里的每条镜像构建腿必须参选源，且兜底不许选回加速器宿主。

    R7 判两种腿：`uses: docker/build-push-action`（看 with.build-args 里有没有
    BASE_REGISTRY）与 run 块里的 `docker build` / `docker buildx build`（看同块有没有
    `--build-arg BASE_REGISTRY`）。同块近似是故意的——build 与参数常跨续行，逐行匹配会漏。
    compose 腿（`docker compose up --build`）的 **build args 有没有传 BASE_REGISTRY** 由 R5 管；
    它**有没有接传输类兜底**由 R9 管（同在这个函数里判）——2026-10-23 实测：R5 全绿的仓里，
    compose 腿照样被一次 TLS 抖动打红三条（Playwright E2E 与 ZAP 两条腿），因为 R5 只管参数、
    不管「挂了怎么办」。

    R8 防的是 2026-10-21 实测到的形态：选源器 fail-soft 的落点是上游 `docker.io`，
    工作流兜底却写死 `docker.m.daocloud.io`——即"刚被证明不可达的那个宿主"（那条 CI 的
    报错是 DNS `no such host`）。同一天两条 build-push-action 前端腿干脆没接选源器，
    直接吃 Dockerfile 的 ARG 默认值，于是 R7/R8 是同一个洞的两面。
    """
    wf_dir = os.path.join(root, '.github', 'workflows')
    files = sorted(fn for fn in os.listdir(wf_dir)
                   if fn.endswith(('.yml', '.yaml'))) if os.path.isdir(wf_dir) else []
    bad = []
    n_legs = 0
    used_exempt = set()
    n_compose = 0
    used_cexempt = set()
    for fn in files:
        rel = '.github/workflows/' + fn
        txt = io.open(os.path.join(wf_dir, fn), encoding='utf-8', errors='replace').read()
        for i, ln in enumerate(txt.splitlines(), 1):
            m = FALLBACK_RE.search(ln)
            if m and m.group(2).strip() in ACCEL_HOSTS:
                bad.append((rel, 'R8', '第 %d 行 `%s` 的兜底取了加速器宿主 %s；选源器全部候选不通时'
                                  '落到上游 docker.io，兜底应跟它一致，不能选回刚被证明不可达的加速器'
                                  % (i, m.group(0), m.group(2).strip())))
        try:
            import yaml
            data = yaml.safe_load(txt)
        except Exception as exc:  # noqa: BLE001 解析失败必须说出来，不能跳过
            bad.append((rel, 'R7', '工作流解析失败（%s）——门禁不能对读不懂的文件放行' % str(exc)[:60]))
            continue
        jobs = (data or {}).get('jobs') or {}
        for jname, job in jobs.items():
            for st in (job or {}).get('steps') or []:
                if not isinstance(st, dict):
                    continue
                sname = st.get('name') or '(无名步骤)'
                uses = str(st.get('uses') or '')
                run_blk = str(st.get('run') or '')
                where = '%s/%s' % (jname, sname)
                if BUILD_PUSH_ACTION in uses:
                    n_legs += 1
                    exempted = (rel, where) in LEG_EXEMPT
                    if 'BASE_REGISTRY' not in _build_args_text(st):
                        if exempted:
                            used_exempt.add((rel, where))
                        else:
                            bad.append((rel, 'R7', '%s：build-push-action 没传 BASE_REGISTRY ⇒ 这条腿吃 '
                                              'Dockerfile 的 ARG 默认宿主，选源器切不到它' % where))
                n_build = len(BUILD_LEG_RE.findall(run_blk))
                n_legs += n_build
                if n_build and not BUILD_ARG_RE.search(run_blk):
                    if (rel, where) in LEG_EXEMPT:
                        used_exempt.add((rel, where))
                    else:
                        bad.append((rel, 'R7', '%s：run 块里 %d 条 docker build 都没带 '
                                          '--build-arg BASE_REGISTRY ⇒ 同上' % (where, n_build)))
                # R9：compose up 腿要接传输类兜底
                n_c = len(COMPOSE_UP_RE.findall(run_blk))
                n_compose += n_c
                if n_c and COMPOSE_WRAPPER not in run_blk:
                    if (rel, where) in COMPOSE_LEG_EXEMPT:
                        used_cexempt.add((rel, where))
                    else:
                        bad.append((rel, 'R9', '%s：run 块里 %d 条 `docker compose … up` 没走 %s ⇒ '
                                          'compose 顺带构建的镜像一旦碰上基座源的传输类抖动就直接判红，'
                                          '而退出码只会说 exit 1，读不出是代码红还是源红（台账 #93）'
                                          % (where, n_c, COMPOSE_WRAPPER)))
    stale_exempt = sorted(LEG_EXEMPT - used_exempt)
    for rel, where in stale_exempt:
        bad.append((rel, 'R4', '豁免条目 %s 无人使用（该腿已参选源或被删了），请从 LEG_EXEMPT 移除' % where))
    stale_c = sorted(COMPOSE_LEG_EXEMPT - used_cexempt)
    for rel, where in stale_c:
        bad.append((rel, 'R4', 'compose 腿豁免条目 %s 无人使用（该腿已接兜底或被删了），'
                               '请从 COMPOSE_LEG_EXEMPT 移除' % where))
    return bad, n_legs, len(files), used_exempt, n_compose, used_cexempt


def check_build_scripts(root):
    """R7 的另一半：仓库里的 shell 脚本手工构建镜像时也必须参选源。

    工作流不是唯一的构建入口。2026-10-21 现查：`tests/deploy/build-amd64.sh` 与
    `build-multiarch.sh` 各跑一条 `docker buildx build -f Dockerfile.multiarch`，
    而那份 Dockerfile 的 FROM 走 `${BASE_REGISTRY}` ⇒ 这两条手工腿一直吃的是 ARG 默认宿主
    （加速器），并且脚本里还把仓库路径写死成某台机器的绝对位置。R7 只看工作流的话，
    这一类就永远在门禁盲区里。

    判据按脚本整份文本：出现 `docker build`/`docker buildx build` 却没在任何地方提
    `BASE_REGISTRY` ⇒ 违规；不联网拉 Docker Hub 基座的脚本走 `SCRIPT_EXEMPT` 显式豁免
    （同样"无人使用即判过期"）。
    """
    bad = []
    n_scripts = 0
    used = set()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in sorted(filenames):
            if not fn.endswith('.sh'):
                continue
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, root).replace(os.sep, '/')
            txt = io.open(p, encoding='utf-8', errors='replace').read()
            n_build = len(BUILD_LEG_RE.findall(txt))
            if not n_build:
                continue
            n_scripts += 1
            if rel in SCRIPT_EXEMPT:
                used.add(rel)
                continue
            if 'BASE_REGISTRY' not in txt:
                bad.append((rel, 'R7', '脚本里有 %d 条 docker build 却全程不提 BASE_REGISTRY ⇒ '
                                  '这条手工腿吃 Dockerfile 的 ARG 默认宿主；不联网拉 Hub 基座的话'
                                  '请进 SCRIPT_EXEMPT 并写清理由' % n_build))
    stale = sorted(set(SCRIPT_EXEMPT) - used)
    for rel in stale:
        bad.append((rel, 'R4', '脚本豁免条目无人使用（该脚本已参选源或已不存在），请从 SCRIPT_EXEMPT 移除'))
    return bad, n_scripts, used


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
    w_bad, n_legs, n_wf, _used, n_compose, _cused = check_workflows(root)
    viol.extend(w_bad)
    if n_wf == 0 or n_legs == 0:
        print('::error::扫描失效：工作流 %d 个 / 构建腿 %d 条（R7 失明，不放行）' % (n_wf, n_legs))
        return 2
    if n_compose == 0:
        print('::error::扫描失效：工作流里一条 `docker compose … up` 都没发现（R9 失明，不放行）'
              '——本仓有 Integration Test / Playwright / ZAP 几条腿靠它起栈，发现集为空只可能是判据坏了')
        return 2
    s_bad, n_sh, _sused = check_build_scripts(root)
    viol.extend(s_bad)
    if n_sh == 0:
        print('::error::扫描失效：没扫到任何含 docker build 的 shell 脚本（R7 脚本半边失明，不放行）')
        return 2
    n_sw = sum(1 for rel in files
               for m in re.finditer(r'^FROM\s+\$\{?BASE_REGISTRY\}?/\S+',
                                    io.open(os.path.join(root, rel), encoding='utf-8',
                                            errors='replace').read(), re.M))
    print('check-dockerfile-base-registry.py 目标仓库：%s' % root)
    print('  Dockerfile %d 个；参选源 FROM %d 条（走 ${BASE_REGISTRY}）；'
          '其余只允许例外表里的外部宿主与本地构建产物（R6 拦直连短名）'
          % (len(files), n_sw))
    print('  compose %d 个文件 / %d 个 build 服务已纳入 R5 检查' % (c_files, c_blocks))
    print('  工作流 %d 个 / 镜像构建腿 %d 条已纳入 R7 检查（豁免 %d 条）'
          % (n_wf, n_legs, len(LEG_EXEMPT)))
    print('  compose up 腿 %d 条已纳入 R9 检查（豁免 %d 条，兜底=%s）'
          % (n_compose, len(COMPOSE_LEG_EXEMPT), COMPOSE_WRAPPER))
    print('  含 docker build 的 shell 脚本 %d 个已纳入 R7 脚本半边检查（豁免 %d 个）'
          % (n_sh, len(SCRIPT_EXEMPT)))
    if viol:
        for rel, rule, msg in viol:
            print('  [违规][%s] %s: %s' % (rule, rel, msg))
        print('  ⇒ 共 %d 条' % len(viol))
        return 1
    print('  ✓ base 镜像源形态合规（无硬编码加速器宿主、ARG 语义统一、例外表无过期、'
          'compose build args 齐全、所有构建腿参选源且兜底不选回加速器）')
    return 0


def self_test():
    """合成正反例：每条规则都要"坏形态被抓、好形态不误报"，且证明扫的确实是合成件。"""
    import tempfile
    tmp = tempfile.mkdtemp(prefix='base-reg-selftest-')
    ok = True
    # 真实仓的豁免条目在合成树里必然对不上号，会污染 R4/计数断言；合成期先清空。
    global COMPOSE_LEG_EXEMPT
    COMPOSE_LEG_EXEMPT = set()
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
        put('namespaced/Dockerfile', 'FROM bitnami/kafka:3.7\n')
        put('nopull/Dockerfile', 'FROM scratch\nCOPY bin /app\n')
        put('localart/Dockerfile', 'FROM spark-cluster-spark-master:latest\n')
        # R5 的合成件：compose 的 build 有没有传 BASE_REGISTRY
        put('c-missing/docker-compose.yml',
            'services:\n  svc:\n    build:\n      context: .\n      dockerfile: Dockerfile\n')
        put('c-ok/docker-compose.yml',
            'x-base-args: &base-args\n  - BASE_REGISTRY=${BASE_REGISTRY:-docker.m.daocloud.io}\n'
            'services:\n  svc:\n    build:\n      context: .\n      args: *base-args\n')
        put('c-shorthand/docker-compose.yml',
            'services:\n  svc:\n    build: ./ctx\n')
        # R7/R8 的合成件：工作流构建腿接没接选源、兜底选的是哪个宿主
        put('.github/workflows/leg-ok.yml',
            'name: ok\njobs:\n  b:\n    steps:\n'
            '      - name: 前端腿\n        uses: docker/build-push-action@v7\n'
            '        with:\n          build-args: |\n'
            '            BASE_REGISTRY=${{ steps.p.outputs.base_registry }}\n'
            '      - name: 后端腿\n        run: |\n'
            '          BASE_REGISTRY="$(bash scripts/pick-base-registry.sh || true)"\n'
            '          BASE_REGISTRY="${BASE_REGISTRY:-docker.io}"\n'
            '          docker buildx build --build-arg "BASE_REGISTRY=$BASE_REGISTRY" -t x .\n')
        put('.github/workflows/leg-bad.yml',
            'name: bad\njobs:\n  b:\n    steps:\n'
            '      - name: 前端漏参\n        uses: docker/build-push-action@v7\n'
            '        with:\n          context: frontend/\n'
            '      - name: 后端漏参\n        run: |\n'
            '          BASE_REGISTRY="${BASE_REGISTRY:-docker.m.daocloud.io}"\n'
            '          docker build -t y .\n')
        # R9 的合成件：compose up 腿接没接传输类兜底（走包装器 / 裸跑 / 显式豁免各一条）
        put('.github/workflows/leg-compose.yml',
            'name: compose\njobs:\n  c:\n    steps:\n'
            '      - name: 裸 compose 腿\n        run: |\n'
            '          docker compose -f x.yml up -d --build\n'
            '      - name: 包装 compose 腿\n        run: |\n'
            '          bash scripts/compose-retry.sh docker compose -f x.yml up -d --build\n'
            '  d:\n    steps:\n'
            '      - name: 豁免 compose 腿\n        run: |\n'
            '          docker compose up -d\n')

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
            'R6 直连 Docker Hub 短名要被抓': ('bare', 'R6'),
            'R6 直连 Hub 命名空间形态要被抓': ('namespaced', 'R6'),
        }
        got = rules(tmp)
        for name, pair in expect.items():
            hit = pair in got
            print('  [%s] %s' % ('PASS' if hit else 'FAIL', name))
            ok = ok and hit
        for name, pair in [('R2 正例不误报', ('good', 'R2')),
                           ('R3 正例不误报', ('good', 'R3')),
                           ('R6 走 ${BASE_REGISTRY} 的正例不误报', ('good', 'R6')),
                           ('外部宿主 gcr.io 不误报', ('ext', 'R6')),
                           ('scratch 内置基座不误报', ('nopull', 'R6')),
                           ('本地构建产物不误报', ('localart', 'R6'))]:
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
        print('  [%s] 合成夹具真的被扫到（%d 个 Dockerfile，期望 11）' % ('PASS' if n == 11 else 'FAIL', n))
        ok = ok and n == 11
        # R7/R8：工作流侧
        w_findings, w_legs, w_files, _we, w_nc, w_cused = check_workflows(tmp)
        wset = {(rel.split('/')[-1], rule) for rel, rule, _m in w_findings}
        for name, want in [('R7 build-push-action 漏传 BASE_REGISTRY 要被抓', ('leg-bad.yml', 'R7')),
                           ('R8 兜底选回加速器宿主要被抓', ('leg-bad.yml', 'R8'))]:
            hit = want in wset
            print('  [%s] %s' % ('PASS' if hit else 'FAIL', name))
            ok = ok and hit
        clean_ok = not any(rel.endswith('leg-ok.yml') for rel, _rule, _m in w_findings)
        print('  [%s] R7/R8 正例不误报' % ('PASS' if clean_ok else 'FAIL'))
        ok = ok and clean_ok
        rules_hit = {r for _rel, r, _m in w_findings}
        two_rules = {'R7', 'R8'} <= rules_hit
        print('  [%s] R7 与 R8 是两条独立判据（抓到 %s）'
              % ('PASS' if two_rules else 'FAIL', sorted(rules_hit)))
        ok = ok and two_rules
        legs_ok = (w_legs == 4 and w_files == 3)
        print('  [%s] 构建腿计数正确（%d 条 / %d 文件，期望 4/3）'
              % ('PASS' if legs_ok else 'FAIL', w_legs, w_files))
        ok = ok and legs_ok
        # R9：compose up 腿必须走包装器，否则进带理由的豁免表
        r9_msgs = [m for _rel, r, m in w_findings if r == 'R9']
        r9_bare = any('裸 compose 腿' in m for m in r9_msgs)
        r9_wrapped_quiet = not any('包装 compose 腿' in m for m in r9_msgs)
        print('  [%s] R9 裸 `docker compose … up` 要被抓 / 走包装器的不误报'
              % ('PASS' if r9_bare and r9_wrapped_quiet else 'FAIL'))
        ok = ok and r9_bare and r9_wrapped_quiet
        nc_ok = (w_nc == 3)
        print('  [%s] compose up 腿计数正确（%d 条，期望 3）'
              % ('PASS' if nc_ok else 'FAIL', w_nc))
        ok = ok and nc_ok
        exempt_c = ('.github/workflows/leg-compose.yml', 'd/豁免 compose 腿')
        COMPOSE_LEG_EXEMPT = {exempt_c}
        ce_findings, _l4, _f4, _u4, _n4, c_used = check_workflows(tmp)
        c_quiet = not any('豁免 compose 腿' in m for _rel, _r, m in ce_findings)
        c_used_ok = exempt_c in c_used
        print('  [%s] compose 腿豁免生效且被计为"已使用"'
              % ('PASS' if c_quiet and c_used_ok else 'FAIL'))
        ok = ok and c_quiet and c_used_ok
        COMPOSE_LEG_EXEMPT = {('.github/workflows/leg-compose.yml', '不存在的 compose 腿')}
        cs_findings, _l5, _f5, _u5, _n5, _cu5 = check_workflows(tmp)
        cs_hit = any(r == 'R4' and 'compose 腿豁免' in m for _rel, r, m in cs_findings)
        print('  [%s] 无人使用的 compose 腿豁免被判过期（R4）'
              % ('PASS' if cs_hit else 'FAIL'))
        ok = ok and cs_hit
        COMPOSE_LEG_EXEMPT = set()
        # 豁免必须"被用到"：塞一条对不上号的 LEG_EXEMPT ⇒ 必须按 R4 说过期
        global LEG_EXEMPT
        LEG_EXEMPT = {('.github/workflows/leg-bad.yml', 'b/不存在的腿')}
        stale_findings, _l2, _f2, _u2, _n6, _cu6 = check_workflows(tmp)
        stale_hit = any(rule == 'R4' for _rel, rule, _m in stale_findings)
        print('  [%s] 无人使用的豁免条目被判过期（R4）' % ('PASS' if stale_hit else 'FAIL'))
        ok = ok and stale_hit
        LEG_EXEMPT = {('.github/workflows/leg-bad.yml', 'b/前端漏参')}
        ex_findings, _l3, _f3, ex_used, _n7, _cu7 = check_workflows(tmp)
        bpa_quiet = not any('前端漏参' in m for _rel, _r, m in ex_findings)
        consumed = ('.github/workflows/leg-bad.yml', 'b/前端漏参') in ex_used
        print('  [%s] 豁免生效且被计为"已使用"' % ('PASS' if bpa_quiet and consumed else 'FAIL'))
        ok = ok and bpa_quiet and consumed
        LEG_EXEMPT = set()
        # R7 的脚本半边：仓库里工作流之外的手工构建腿
        put('sh-ok/build.sh', '#!/bin/bash\nBASE_REGISTRY="$(bash scripts/pick-base-registry.sh)"\n'
                              'docker build --build-arg "BASE_REGISTRY=$BASE_REGISTRY" -t x .\n')
        put('sh-bad/build.sh', '#!/bin/bash\ndocker build -t y .\n')
        s_findings, n_sh, _su = check_build_scripts(tmp)
        sset = {rel.split('/')[0] for rel, rule, _m in s_findings}
        hit_bad = 'sh-bad' in sset
        print('  [%s] R7 脚本半边：手工 docker build 不传 BASE_REGISTRY 要被抓'
              % ('PASS' if hit_bad else 'FAIL'))
        ok_ok = 'sh-ok' not in sset
        print('  [%s] R7 脚本半边正例不误报' % ('PASS' if ok_ok else 'FAIL'))
        sh_cnt_ok = (n_sh == 2)
        print('  [%s] 手工构建脚本计数正确（%d 个，期望 2）' % ('PASS' if sh_cnt_ok else 'FAIL', n_sh))
        ok = ok and hit_bad and ok_ok and sh_cnt_ok
        global SCRIPT_EXEMPT
        SCRIPT_EXEMPT = {'sh-nope/build.sh': '合成：指向不存在的脚本'}
        st_findings, _n2, _u2 = check_build_scripts(tmp)
        stale2 = any(rule == 'R4' for _rel, rule, _m in st_findings)
        print('  [%s] 脚本豁免未被使用同样判过期（R4）' % ('PASS' if stale2 else 'FAIL'))
        SCRIPT_EXEMPT = {'sh-bad/build.sh': '合成：kindest 基座，不联网拉 Hub'}
        ex2_findings, _n3, used3 = check_build_scripts(tmp)
        quiet = not any('sh-bad' in rel for rel, _r, _m in ex2_findings)
        used_ok = 'sh-bad/build.sh' in used3
        print('  [%s] 脚本豁免生效且被计为"已使用"' % ('PASS' if quiet and used_ok else 'FAIL'))
        SCRIPT_EXEMPT = {}
        ok = ok and stale2 and quiet and used_ok
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
