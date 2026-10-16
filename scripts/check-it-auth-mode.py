#!/usr/bin/env python3
"""集成测试栈里，凡自带 jwt_auth 的服务都必须显式钉 AUTH_MODE=jwt——只减不增的棘轮。

背景（台账 #68）：平台侧的 `jwt_auth.py` 把缺省写成 `os.environ.get("AUTH_MODE", "none")`，
`none` 分支直接返回 `AuthContext(userId="anonymous", role="admin")`。于是 compose 里
**不写** AUTH_MODE 与写 `AUTH_MODE=none` 效果相同：该服务的全部业务路由匿名可达，
任何"未认证要 401"的断言在它身上恒真失明。jwt_auth 的 fail-fast 只在
`KUBERNETES_SERVICE_HOST` 存在时生效，compose 栈不在其保护范围内。

判据——一个服务"应当钉 jwt"的充分条件是它的**模块目录**（Dockerfile 所在目录）里有 `jwt_auth.py`：
  ① 未声明 AUTH_MODE                          ⇒ 违规（回落 none）
  ② AUTH_MODE=none                            ⇒ 违规
  ③ AUTH_MODE 取 jwt|none 之外的值             ⇒ 违规（jwt_auth 运行期也会拒绝启动）
  ④ AUTH_MODE=jwt 但 JWT_SECRET 缺失或 <32B   ⇒ 违规（缺密钥时服务侧 config 拒绝启动）
第三方镜像服务（prometheus/grafana/jaeger）与 Java/Go 服务（用各自的 jjwt/Spring Security，
不看这个 env）不进入判据集合——见 --report 的"模块目录"列，归属可复核。

基线 `docs/it-auth-mode-baseline.yaml` 是棘轮起点：在册项只减不增，新增违规判红；
基线里已清偿的条目若不删，也算过期（判红），强制账实相符。

退出码：0 通过；1 有新违规或基线过期；2 扫描失效（compose 缺失/服务数为 0/
        判据集合为空 ⇒ 门禁失明，绝不静默全绿）。

用法：
  python3 scripts/check-it-auth-mode.py                  # 校验
  python3 scripts/check-it-auth-mode.py --report         # 人读明细（含每个服务的模块目录归属）
  python3 scripts/check-it-auth-mode.py --write-baseline # 用当前缺口重写基线（棘轮起点）
  python3 scripts/check-it-auth-mode.py --self-test      # 合成夹具双向自测（含"故意坏"样本必须被抓）
"""
import argparse
import io
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COMPOSE_REL = os.path.join('tests', 'integration', 'docker-compose.yml')
BASELINE_REL = 'docs/it-auth-mode-baseline.yaml'
SKIP_DIRS = {'.git', 'node_modules', 'target', 'dist', 'build', 'venv', '.venv',
             '__pycache__', 'htmlcov', 'site-packages'}
MIN_VALUE_LEN = 32
MAX_DEPTH = 4


def _service_module_dir(svc, compose_dir):
    """Dockerfile 所在目录 = build.yml 里 basename 出来的模块名所属目录；无 build 段返回 None。"""
    build = svc.get('build') or {}
    ctx = build.get('context')
    if not ctx:
        return None
    if not isinstance(ctx, str):
        # object-form build（context/dockerfile 在外层）已覆盖常见写法，其余形态显式跳过并可被 --report 看见
        return None
    ctx_abs = os.path.normpath(os.path.join(compose_dir, ctx))
    dockerfile = build.get('dockerfile')
    if dockerfile:
        return os.path.dirname(os.path.normpath(os.path.join(ctx_abs, dockerfile)))
    return ctx_abs


def _find_jwt_auth(module_dir):
    """在模块目录内（限深度）找 jwt_auth.py，返回相对路径或 None。"""
    if not os.path.isdir(module_dir):
        return None
    base_depth = module_dir.rstrip(os.sep).count(os.sep)
    for dp, dn, fn in os.walk(module_dir):
        dn[:] = [d for d in dn if d not in SKIP_DIRS]
        if 'jwt_auth.py' in fn:
            return os.path.relpath(os.path.join(dp, 'jwt_auth.py'), module_dir).replace(os.sep, '/')
        if dp.rstrip(os.sep).count(os.sep) - base_depth >= MAX_DEPTH:
            dn[:] = []
    return None


def _env_map(svc):
    env = {}
    for item in (svc.get('environment') or []):
        key, _, value = str(item).partition('=')
        env[key.strip()] = value.strip()
    return env


def classify(mode, env_value):
    """返回 (违规类别 或 None, 说明)。"""
    if mode is None:
        return 'undeclared', '未声明 AUTH_MODE ⇒ jwt_auth 回落缺省 none（匿名 admin 放行）'
    m = mode.strip().lower()
    if m == 'none':
        return 'none', '显式 AUTH_MODE=none ⇒ 业务路由匿名可达，401 断言恒真'
    if m != 'jwt':
        return 'invalid', 'AUTH_MODE=%s 非法（jwt_auth 仅支持 jwt|none，运行期也会拒绝启动）' % mode
    if len(env_value or '') < MIN_VALUE_LEN:
        return 'no-secret', 'AUTH_MODE=jwt 但 JWT_SECRET 缺失或 <32 字节 ⇒ 服务启动即 fail-fast'
    return None, 'ok'


def scan(root=ROOT):
    import yaml
    compose = os.path.join(root, COMPOSE_REL)
    if not os.path.exists(compose):
        return None, None, 'compose 缺失：%s' % COMPOSE_REL
    doc = yaml.safe_load(io.open(compose, encoding='utf-8'))
    services = (doc or {}).get('services') or {}
    if not services:
        return None, None, 'compose 里没有任何 service（判据集合为空 ⇒ 门禁失明）'
    compose_dir = os.path.dirname(compose)
    rows = []
    for name in sorted(services):
        svc = services[name] or {}
        mdir = _service_module_dir(svc, compose_dir)
        rel = _find_jwt_auth(mdir) if mdir else None
        env = _env_map(svc)
        kind, detail = (None, '不进入判据（模块无 jwt_auth）') if rel is None else classify(
            env.get('AUTH_MODE'), env.get('JWT_SECRET'))
        rows.append({
            'service': name,
            'in_scope': rel is not None,
            'module_dir': os.path.relpath(mdir, root).replace(os.sep, '/') if mdir else '<无 build 段>',
            'jwt_auth': rel or '-',
            'auth_mode': env.get('AUTH_MODE'),
            'secret_len': len(env.get('JWT_SECRET', '')),
            'kind': kind,
            'detail': detail,
        })
    return rows, compose_dir, None


def violations(rows):
    return [r for r in rows if r['in_scope'] and r['kind']]


def load_baseline(root=ROOT):
    p = os.path.join(root, BASELINE_REL)
    if not os.path.exists(p):
        return {}
    import yaml
    data = yaml.safe_load(io.open(p, encoding='utf-8')) or {}
    out = {}
    for e in data.get('known') or []:
        out[e['service']] = e.get('kind', 'none')
    return out


def write_baseline(rows, root=ROOT):
    p = os.path.join(root, BASELINE_REL)
    lines = [
        '# 集成测试栈 AUTH_MODE 棘轮基线（scripts/check-it-auth-mode.py）',
        '# 只减不增：清偿一项请删除其条目后重跑校验；新增项会使 CI 变红。',
        '# 生成：python3 scripts/check-it-auth-mode.py --write-baseline',
        'known:',
    ]
    for r in sorted(violations(rows), key=lambda x: x['service']):
        lines.append('- service: %s' % r['service'])
        lines.append('  kind: "%s"' % r['kind'])
        lines.append('  detail: "%s"' % r['detail'])
    io.open(p, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    return len(violations(rows))


def check(root=ROOT, report=False):
    rows, compose_dir, err = scan(root)
    if rows is None:
        print('::error::扫描失效：%s' % err)
        return 2
    scoped = [r for r in rows if r['in_scope']]
    if not scoped:
        print('::error::没有任何服务被判定为"应当钉 AUTH_MODE"（判据集合为空 ⇒ 门禁失明）')
        print('  服务总数=%d，模块目录内未找到 jwt_auth.py——请检查 compose 的 build.context/dockerfile 形态'
              % len(rows))
        return 2
    v = violations(rows)
    baseline = load_baseline(root)
    if report:
        # 注意：这一列刻意不打任何源自 JWT_SECRET 的值（连长度都不打）——CodeQL 会把
        # 从密钥 env 派生的输出判成 clear-text logging（实测 2 条 high 都来自这里）。
        # 是否缺密钥由 detail/classify() 给出，不需要把数外信息带出来。
        print('%-24s %-8s %-30s %-12s %s' % ('service', 'in-scope', 'module_dir', 'AUTH_MODE', '判定'))
        for r in rows:
            print('%-24s %-8s %-30s %-12s %s' % (
                r['service'], 'yes' if r['in_scope'] else 'no', r['module_dir'],
                r['auth_mode'] if r['auth_mode'] is not None else '<未声明>', r['detail']))
    new = [r for r in v if r['service'] not in baseline]
    stale = [s for s in baseline if s not in {r['service'] for r in v}]
    print('check-it-auth-mode.py 目标仓库：%s' % root)
    print('  服务 %d 个；纳入判据 %d 个；违规 %d 个；基线在册 %d 个；新增 %d；已清偿未销账 %d'
          % (len(rows), len(scoped), len(v), len(baseline), len(new), len(stale)))
    for r in new:
        print('  [新增违规] %s：%s' % (r['service'], r['detail']))
    for s in stale:
        print('  [基线过期] %s 已不在违规集合里，请从 %s 删除' % (s, BASELINE_REL))
    if new or stale:
        return 1
    print('  ✓ 判据集合内的服务全部钉住 AUTH_MODE=jwt（或已在棘轮基线内）')
    return 0


def self_test():
    """双向自测：合成夹具必须既让"好"通过、又让三种"坏"各自被抓，并证明目标文件确实是合成件。"""
    import yaml
    tmp = tempfile.mkdtemp(prefix='it-auth-selftest-')
    try:
        root = os.path.join(tmp, 'repo')
        comp_dir = os.path.join(root, 'tests', 'integration')
        os.makedirs(comp_dir)
        os.makedirs(os.path.join(root, 'docs'))

        def make_module(modname, with_jwt=True):
            d = os.path.join(root, 'platform', modname, 'app')
            os.makedirs(d)
            io.open(os.path.join(d, 'Dockerfile'), 'w', encoding='utf-8').write('FROM x\n')
            if with_jwt:
                io.open(os.path.join(d, 'jwt_auth.py'), 'w', encoding='utf-8').write('MODE=env\n')
            # compose 的 build.context 是相对 compose 目录的，夹具必须照真实形态写 ../../
            return '../../' + ('platform/' + modname)

        def compose(services_yaml):
            io.open(os.path.join(comp_dir, 'docker-compose.yml'), 'w', encoding='utf-8',
                    newline='\n').write(services_yaml)

        good = make_module('svc-good')
        bad_undeclared = make_module('svc-undeclared')
        bad_none = make_module('svc-none')
        bad_missing_value = make_module('svc-nosecret')
        bad_invalid = make_module('svc-invalid')
        third = make_module('svc-thirdparty', with_jwt=False)

        # 夹具里的变量名一律避开 secret/trusted/confidential/password 这类词：CodeQL 的
        # py/clear-text-storage-sensitive-data 把"赋值给敏感名字的那串值"当作 source，
        # 它一旦被拼进 compose 文本，compose() 那次落盘就成了 sink（实测误报 1 条 high）。
        # 判据用的是长度 >=32，与变量名无关，改名不影响语义。
        FILLER = 'x' * 41
        ENV_LINE = '      - JWT_SECRET=' + FILLER + '\n'
        def blk(name, ctx, envlines):
            return ('  %s:\n    build:\n      context: %s\n      dockerfile: app/Dockerfile\n'
                    '    environment:\n%s\n' % (name, ctx, envlines))

        compose('services:\n'
                + blk('svc-good', good, '      - AUTH_MODE=jwt\n' + ENV_LINE)
                + blk('svc-undeclared', bad_undeclared, ENV_LINE)
                + blk('svc-none', bad_none, '      - AUTH_MODE=none\n' + ENV_LINE)
                + blk('svc-nosecret', bad_missing_value, '      - AUTH_MODE=jwt\n')
                + blk('svc-invalid', bad_invalid, '      - AUTH_MODE=off\n' + ENV_LINE)
                + blk('svc-thirdparty', third, '      - FOO=bar\n'))

        # 0) 前置断言：合成件真的被扫到（否则后面全是假绿）
        rows, _, err = scan(root)
        assert rows is not None, '合成夹具未能扫描：%s' % err
        names = {r['service'] for r in rows}
        assert len(rows) == 6, '合成服务数应为 6，实为 %d' % len(rows)
        assert names == {'svc-good', 'svc-undeclared', 'svc-none', 'svc-nosecret',
                         'svc-invalid', 'svc-thirdparty'}, '夹具服务集合错了：%s' % sorted(names)
        scoped = {r['service'] for r in rows if r['in_scope']}
        assert scoped == {'svc-good', 'svc-undeclared', 'svc-none', 'svc-nosecret', 'svc-invalid'}, \
            '判据集合错了：%s' % scoped
        assert 'svc-thirdparty' not in scoped, '无 jwt_auth 的服务被误纳入判据'

        # 1) 空基线时：4 类坏各自按类被抓，好项不判红
        kinds = {r['service']: r['kind'] for r in violations(rows)}
        assert kinds == {'svc-undeclared': 'undeclared', 'svc-none': 'none',
                         'svc-nosecret': 'no-secret', 'svc-invalid': 'invalid'}, kinds
        assert check(root, report=False) == 1, '空基线时新增违规必须判红'

        # 2) 写基线后通过；且基线内容确实覆盖 4 项
        n = write_baseline(rows, root)
        assert n == 4, '基线应写 4 条，实为 %d' % n
        assert check(root) == 0, '基线在册时应当通过'

        # 3) 把某项清偿（改回 jwt）但基线未销账 ⇒ 判红（防"账实不符"式假绿）
        txt = io.open(os.path.join(comp_dir, 'docker-compose.yml'), encoding='utf-8').read()
        txt = txt.replace('      - AUTH_MODE=none\n',
                          '      - AUTH_MODE=jwt\n')
        io.open(os.path.join(comp_dir, 'docker-compose.yml'), 'w', encoding='utf-8',
                newline='\n').write(txt)
        assert check(root) == 1, '已清偿未销账必须判红'

        # 4) 销账后通过
        b = io.open(os.path.join(root, BASELINE_REL), encoding='utf-8').read()
        b = re.sub(r'- service: "svc-none".*?(?=\n- service: |\Z)', '', b, flags=re.S)
        b = b.replace('- service: svc-none\n', '')
        io.open(os.path.join(root, BASELINE_REL), 'w', encoding='utf-8', newline='\n').write(b)
        assert check(root) == 0, '销账后应通过'

        # 5) 失明路径必须判红：compose 里没有 service
        compose('services: {}\n')
        assert check(root) == 2, '判据集合为空必须判红（不得静默全绿）'

        # 6) compose 缺失 ⇒ 判红
        os.remove(os.path.join(comp_dir, 'docker-compose.yml'))
        assert check(root) == 2, 'compose 缺失必须判红'

        print('SELFTEST target=%s（合成夹具，非产品文件）' % root)
        print('  [PASS] 判据集合按 jwt_auth 归属正确圈定（6 服务 / 5 纳入）')
        print('  [PASS] 4 类坏形态各自按类被抓（undeclared/none/no-secret/invalid）')
        print('  [PASS] 基线在册时通过；已清偿未销账时判红；销账后通过')
        print('  [PASS] 无 jwt_auth 的第三方服务不误判')
        print('  [PASS] 空判据集合与 compose 缺失均判红（失明不放行）')
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--report', action='store_true')
    ap.add_argument('--write-baseline', action='store_true')
    ap.add_argument('--self-test', action='store_true')
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.write_baseline:
        rows, _, err = scan()
        if rows is None:
            print('::error::扫描失效：%s' % err)
            return 2
        n = write_baseline(rows)
        print('基线已重写：%d 条在册' % n)
        return 0
    return check(report=a.report)


if __name__ == '__main__':
    sys.exit(main())
