#!/usr/bin/env python3
"""生成器表 ↔ chart 文件的一致性断言（防止"手滑跑一次 _generate_charts.py"静默回退）。

背景（台账 #65 的②）：`design/deploy/charts/_generate_charts.py` 的 CHARTS 表是 chart values.yaml 的
生成源，但表内值长期落后于文件——实测 12 行不一致，其中 6 处属**回退**：
 - `hive-metastore`/`mlflow` 的表行把有效第三方镜像登记成自研 `sq-*`（再生成会指向不存在的镜像）；
 - `cluster-autoscaler`/`descheduler`/`node-problem-detector` 表行仍指已退役的 `k8s.gcr.io`（文件已是 `registry.k8s.io`）；
 - 镜像 tag 落后一档（cilium v1.15.0 vs 文件 v1.16.0、keda 2.13.0 vs 2.16.0、cluster-autoscaler v1.28.0 vs v1.31.0）。
本脚本把这些"应当相等"的关系固化成门禁：任何一行表值 ≠ 文件值 ⇒ 判红。

豁免口径（诚实声明）：`docs/deploy-image-reference-baseline.yaml` 里登记的 chart 跳过比对——
那批的坐标与模块映射本身待裁（见 #65 的②③遗留），拿去和表比只会制造假红。
比对范围只含 `repository`/`tag`/`pullPolicy` 三个镜像字段；**appVersion 不在内**——96/100 个 chart 的
appVersion 都是平台约定的 "2.0.0"，而表里填的是各组件版本，这条分歧是第二个"再生成会改错东西"的点，
已登记在 #65，未在本脚本里判红（判了会把既有约定全部掀翻）。

用法：
  python3 scripts/check-chart-generator-drift.py            # 校验
  python3 scripts/check-chart-generator-drift.py --report   # 打印逐行比对结果
  python3 scripts/check-chart-generator-drift.py --self-test
退出码：0 一致；1 有漂移；2 判据失效（解析不到表 / 比对手数为 0 ⇒ 门禁失明）。
"""

import argparse
import ast
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GEN_REL = os.path.join('design', 'deploy', 'charts', '_generate_charts.py')
CHARTS_REL = os.path.join('design', 'deploy', 'charts')
BASELINE_REL = os.path.join('docs', 'deploy-image-reference-baseline.yaml')


def prefixes(text):
    out = {}
    for node in ast.parse(text).body:
        if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name):
            if node.targets[0].id in ('IMAGE_PREFIX', 'FIRST_PARTY_PREFIX'):
                out[node.targets[0].id] = ast.literal_eval(node.value)
    return out


def table_rows(text):
    """解析 CHARTS 表 ⇒ {chart: 期望的 image 三字段}（与 gen_values_yaml 的口径一致）。"""
    pre = prefixes(text)
    rows = {}
    for node in ast.parse(text).body:
        if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name) \
                and node.targets[0].id == 'CHARTS':
            for r in ast.literal_eval(node.value):
                name, repo, tag = r[0], r[3], r[4]
                fp = repo.startswith('sq-')
                host = pre.get('FIRST_PARTY_PREFIX', '') if fp else pre.get('IMAGE_PREFIX', '')
                rows[name] = {'repository': '%s/%s' % (host, repo),
                              'tag': 'latest' if fp else tag,
                              'pullPolicy': 'Always' if fp else 'IfNotPresent'}
    return rows


def image_block(text):
    """解析一个 values.yaml 的 image 块（缩进保留，遇顶层键即止）。"""
    m = re.search(r'^image:[ \t]*$', text, re.M)
    if not m:
        return {}
    out = {}
    for ln in text[m.end():].split('\n'):
        if ln and not ln[0].isspace() and not ln.lstrip().startswith('#'):
            break
        km = re.match(r'^\s*(repository|tag|pullPolicy):\s*"?([^"\n]+?)"?\s*$', ln)
        if km:
            out[km.group(1)] = km.group(2).strip()
    return out


def baseline_charts(root=ROOT):
    p = os.path.join(root, BASELINE_REL)
    if not os.path.isfile(p):
        return set()
    out = set()
    for line in io.open(p, encoding='utf-8', errors='replace'):
        if line.startswith('- file:'):
            f = line.split(':', 1)[1].strip().strip('"')
            m = re.match(r'^design/deploy/charts/([^/]+)/values\.yaml$', f)
            if m:
                out.add(m.group(1))
    return out


def compare(rows, blocks, exempt):
    """返回 [(chart, 字段, 表值, 文件值)] 的漂移清单。"""
    drift = []
    for name, exp in sorted(rows.items()):
        if name in exempt:
            continue
        got = blocks.get(name)
        if got is None:
            drift.append((name, '(文件缺失)', '-', 'chart 目录或 image 块不存在'))
            continue
        for k in ('repository', 'tag', 'pullPolicy'):
            if exp.get(k) != got.get(k):
                drift.append((name, k, exp.get(k), got.get(k)))
    return drift


def run(root=ROOT, report=False):
    gpath = os.path.join(root, GEN_REL)
    if not os.path.isfile(gpath):
        print('::error::找不到生成器 %s ⇒ 判据失效' % GEN_REL)
        return 2, []
    rows = table_rows(io.open(gpath, encoding='utf-8-sig').read())
    if not rows:
        print('::error::生成器表解析到 0 行 ⇒ 门禁失明（判据失效）')
        return 2, []
    exempt = baseline_charts(root)
    blocks = {}
    for name in rows:
        v = os.path.join(root, CHARTS_REL, name, 'values.yaml')
        blocks[name] = image_block(io.open(v, encoding='utf-8-sig').read()) if os.path.isfile(v) else None
    drift = compare(rows, blocks, exempt)
    print('生成器表 %d 行；豁免（基线在册）%d 个；参与比对 %d 个；漂移 %d 条'
          % (len(rows), len(exempt & set(rows)), len(rows) - len(exempt & set(rows)), len(drift)))
    if report:
        for c, k, want, got in drift:
            print('  %-24s %-12s 表=%s  文件=%s' % (c, k, want, got))
    if drift:
        for c, k, want, got in drift:
            print('::error::表与文件不一致：%s 的 %s：表=%r 文件=%r'
                  '（要么把表回写成文件值，要么确认再生成后同步文件；不要留着——下次有人跑生成器就静默回退）'
                  % (c, k, want, got))
        return 1, drift
    print('✓ 表与文件在镜像字段上逐行一致（豁免项另见基线）')
    return 0, drift


def self_test():
    """合成样本双向验证判据：一致放行、漂移判红、豁免不参与、表空判失明。"""
    gen = ('IMAGE_PREFIX = "docker.m.daocloud.io"\n'
           'FIRST_PARTY_PREFIX = "ghcr.io/levango7"\n'
           'CHARTS = [\n'
           ' ("good", "d", "1", "sq-good", "9.9", 80, "c", "n", "k", 1, "1", "1", "2", "2"),\n'
           ' ("bad", "d", "1", "k8s.gcr.io/bad", "v1", 80, "c", "n", "k", 1, "1", "1", "2", "2"),\n'
           ' ("exempt", "d", "1", "sq-exempt", "9", 80, "c", "n", "k", 1, "1", "1", "2", "2"),\n'
           ']\n')
    rows = table_rows(gen)
    ok = True
    checks = []
    good = rows['good']
    checks.append(('自研行期望 ghcr + latest + Always',
                   good == {'repository': 'ghcr.io/levango7/sq-good', 'tag': 'latest',
                            'pullPolicy': 'Always'}))
    blocks = {
        'good': dict(good),
        'bad': {'repository': 'docker.m.daocloud.io/registry.k8s.io/bad',
                'tag': 'v1', 'pullPolicy': 'IfNotPresent'},
        'exempt': {'repository': 'docker.m.daocloud.io/sq-exempt',
                   'tag': '0.1.0', 'pullPolicy': 'IfNotPresent'},
    }
    drift = compare(rows, blocks, {'exempt'})
    checks.append(('一致行放行（good 不出现在漂移里）', not any(d[0] == 'good' for d in drift)))
    checks.append(('退役 registry 被判漂移', any(d[0] == 'bad' and d[1] == 'repository' for d in drift)))
    checks.append(('基线在册项被豁免', not any(d[0] == 'exempt' for d in drift)))
    checks.append(('缺文件也判漂移（不静默跳过）',
                   any(d[0] == 'ghost' for d in compare(
                       dict(rows, **{'ghost': {'repository': 'x', 'tag': 'y', 'pullPolicy': 'z'}}),
                       blocks, set()))))
    checks.append(('表为空 ⇒ 判据失效可被上层发现', table_rows('CHARTS = []\n') == {}))
    # 反向：把表值当文件值喂进去，必须判绿（证明不是恒真判红）
    checks.append(('全一致时零漂移', compare({'a': {'repository': 'r', 'tag': 't', 'pullPolicy': 'p'}},
                                            {'a': {'repository': 'r', 'tag': 't', 'pullPolicy': 'p'}}, set()) == []))
    for name, res in checks:
        print('  [%s] %s' % ('PASS' if res else 'FAIL', name))
        ok = ok and bool(res)
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--report', action='store_true')
    ap.add_argument('--self-test', action='store_true')
    a = ap.parse_args()
    print('check-chart-generator-drift.py 目标仓库：%s' % ROOT)
    if a.self_test:
        return 0 if self_test() else 1
    code, _ = run()
    return code


if __name__ == '__main__':
    sys.exit(main())
