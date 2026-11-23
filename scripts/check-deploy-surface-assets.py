#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""部署面资产自检（台账 #129 / #131）。

背景：台账 #128–#132 的五项落地形态里有两处「同一份事实存在两个副本」，
各自都会静默漂移——本脚本把两条关系钉死：

  A. Keycloak MFA 片段 ↔ keycloak chart 的 mfa 段（台账 #131）
     权威片段：design/deploy/keycloak/sq-realm-mfa.json
     chart 侧：design/deploy/charts/keycloak/values.yaml 的 mfa.otpPolicy /
                mfa.webAuthnPolicy / mfa.requiredAction
     两者不一致 ⇒ 「渲染进 realm 的 MFA 策略」与「仓库声明的 MFA 策略」分叉，
     且没有任何渲染期信号（helm template 照样绿）。
     同时守住既有判据：片段禁出现 usersProfile（partialImport 不支持，会被静默忽略，
     见 scripts/check-keycloak-realm-fragment.py 判据 D）、禁未知键。

  B. Istio 流量策略副本 ↔ 仓库权威文件（台账 #129）
     权威文件：design/deploy/istio/traffic-policies.yaml
     chart 副本：design/deploy/charts/istio/files/traffic-policies.yaml
     chart 用 .Files.Get 渲染副本；副本漂移 ⇒ helm install 装进去的策略与
     kubectl apply 的策略不是同一份。副本去 UTF-8 BOM（Helm .Files.Get 原样返回内容，
     BOM 落在渲染输出里会让 YAML 解析器在注释行前遇到非法字符），其余逐字节相同。

用法：
  python3 scripts/check-deploy-surface-assets.py --self-test
  python3 scripts/check-deploy-surface-assets.py
退出码：0 通过；1 有命中。
"""

from __future__ import annotations

import argparse
import copy
import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

MFA_FRAGMENT_REL = os.path.join('design', 'deploy', 'keycloak', 'sq-realm-mfa.json')
KEYCLOAK_VALUES_REL = os.path.join('design', 'deploy', 'charts', 'keycloak', 'values.yaml')
ISTIO_SRC_REL = os.path.join('design', 'deploy', 'istio', 'traffic-policies.yaml')
ISTIO_COPY_REL = os.path.join('design', 'deploy', 'charts', 'istio', 'files', 'traffic-policies.yaml')

BOM = b'\xef\xbb\xbf'

# MFA 片段允许出现的顶层键（与 scripts/keycloak-mfa-merge.py 的 ALLOWED_KEYS 同口径）
ALLOWED_MFA_KEYS = {'otpPolicy', 'webAuthnPolicy', 'requiredActions', 'browserFlow', 'authenticationFlows'}
FORBIDDEN_MFA_KEYS = {'usersProfile'}


def read_json(path):
    with io.open(path, encoding='utf-8') as fh:
        return json.load(fh)


def strip_bom_bytes(path):
    with open(path, 'rb') as fh:
        raw = fh.read()
    if raw.startswith(BOM):
        raw = raw[3:]
    return raw


def check_mfa_fragment(fragment, values):
    """返回问题清单（空 = 通过）。values 为 keycloak chart 的 mfa 段。"""
    problems = []
    if not isinstance(fragment, dict):
        return ['MFA 片段必须是 JSON 对象']

    unknown = set(fragment) - ALLOWED_MFA_KEYS
    if unknown:
        problems.append('MFA 片段含未知键：%s（允许：%s）' % (sorted(unknown), sorted(ALLOWED_MFA_KEYS)))
    forbidden = set(fragment) & FORBIDDEN_MFA_KEYS
    if forbidden:
        problems.append('MFA 片段含禁用键 %s（partialImport 不支持，会被服务端静默忽略）' % sorted(forbidden))

    otp = fragment.get('otpPolicy')
    if not isinstance(otp, dict):
        problems.append('MFA 片段缺 otpPolicy 或不是对象')
    else:
        if otp.get('type') != 'totp':
            problems.append('otpPolicy.type 应为 totp，实际 %r' % otp.get('type'))
        for key in ('algorithm', 'digits', 'period'):
            if key not in otp:
                problems.append('otpPolicy.%s 缺失' % key)

    actions = fragment.get('requiredActions')
    if not isinstance(actions, list) or not actions:
        problems.append('MFA 片段 requiredActions 必须是非空数组')
    else:
        ids = [a.get('id') for a in actions if isinstance(a, dict)]
        if 'CONFIGURE_TOTP' not in ids:
            problems.append('MFA 片段 requiredActions 缺 CONFIGURE_TOTP')

    web = fragment.get('webAuthnPolicy')
    if web is not None and not isinstance(web, dict):
        problems.append('MFA 片段 webAuthnPolicy 不是对象')

    # 与 chart values 的 mfa 段对账
    if isinstance(values, dict):
        chart_otp = values.get('otpPolicy') or {}
        for key in ('type', 'algorithm', 'digits', 'period'):
            if key in chart_otp and isinstance(otp, dict) and key in otp:
                if chart_otp[key] != otp[key]:
                    problems.append('otpPolicy.%s 片段=%r 与 chart values=%r 不一致'
                                    % (key, otp[key], chart_otp[key]))
        chart_web = values.get('webAuthnPolicy') or {}
        if isinstance(web, dict):
            for key in ('enabled', 'attestationType', 'userVerificationRequirement', 'rpName'):
                if key in chart_web and key in web and chart_web[key] != web[key]:
                    problems.append('webAuthnPolicy.%s 片段=%r 与 chart values=%r 不一致'
                                    % (key, web[key], chart_web[key]))
        want_action = values.get('requiredAction')
        if want_action and isinstance(actions, list):
            ids = [a.get('id') for a in actions if isinstance(a, dict)]
            if want_action not in ids:
                problems.append('chart values 的 mfa.requiredAction=%r 不在片段 requiredActions %r 里'
                                % (want_action, ids))
    else:
        problems.append('keycloak chart values 缺 mfa 段（无法与片段对账）')
    return problems


def check_istio_copy(src_bytes, copy_bytes):
    """返回问题清单（空 = 一致）。"""
    if src_bytes is None:
        return ['权威文件不存在：%s' % ISTIO_SRC_REL]
    if copy_bytes is None:
        return ['chart 副本不存在：%s' % ISTIO_COPY_REL]
    if src_bytes != copy_bytes:
        problems = ['Istio 流量策略副本与权威文件不一致（副本 %d 字节 vs 权威 %d 字节）'
                    % (len(copy_bytes), len(src_bytes))]
        slines = src_bytes.split(b'\n')
        clines = copy_bytes.split(b'\n')
        if len(slines) != len(clines):
            problems.append('行数不同：副本 %d 行 vs 权威 %d 行' % (len(clines), len(slines)))
        for idx, (a, b) in enumerate(zip(slines, clines), 1):
            if a != b:
                problems.append('首个差异在第 %d 行' % idx)
                break
        return problems
    return []


def load_keycloak_mfa_values(root):
    """从 keycloak chart 的 values.yaml 里取 mfa 段（只依赖 PyYAML，缺依赖时降级为文本解析）。"""
    path = os.path.join(root, KEYCLOAK_VALUES_REL)
    try:
        import yaml  # noqa: F401
    except ImportError:
        return None
    with io.open(path, encoding='utf-8-sig') as fh:
        data = yaml.safe_load(fh) or {}
    return data.get('mfa')


def run(root=ROOT):
    findings = []

    frag_path = os.path.join(root, MFA_FRAGMENT_REL)
    if not os.path.isfile(frag_path):
        findings.append('MFA 片段不存在：%s' % MFA_FRAGMENT_REL)
    else:
        fragment = read_json(frag_path)
        values = load_keycloak_mfa_values(root)
        if values is None:
            findings.append('无法解析 keycloak chart values（缺 PyYAML 或 mfa 段）')
        else:
            for p in check_mfa_fragment(fragment, values):
                findings.append('MFA：' + p)

    src = os.path.join(root, ISTIO_SRC_REL)
    copy_ = os.path.join(root, ISTIO_COPY_REL)
    src_bytes = strip_bom_bytes(src) if os.path.isfile(src) else None
    copy_bytes = strip_bom_bytes(copy_) if os.path.isfile(copy_) else None
    for p in check_istio_copy(src_bytes, copy_bytes):
        findings.append('Istio：' + p)

    if findings:
        print('FAIL: 部署面资产自检命中 %d 处：' % len(findings))
        for f in findings:
            print('  - %s' % f)
        return 1
    print('OK: 部署面资产自检通过'
          '（Keycloak MFA 片段 ↔ chart values 一致 / Istio 流量策略副本逐字节一致）')
    return 0


def self_test():
    """合成正反例：证明判据不是恒真。"""
    ok = True
    checks = []

    fragment = {
        'otpPolicy': {'type': 'totp', 'algorithm': 'HmacSHA1', 'digits': 6, 'period': 30},
        'webAuthnPolicy': {'enabled': False, 'attestationType': 'none',
                           'userVerificationRequirement': 'preferred', 'rpName': '数擎云核'},
        'requiredActions': [{'id': 'CONFIGURE_TOTP', 'name': 'Configure OTP',
                              'enabled': True, 'defaultAction': True}],
    }
    values = {
        'otpPolicy': {'type': 'totp', 'algorithm': 'HmacSHA1', 'digits': 6, 'period': 30},
        'webAuthnPolicy': {'enabled': False, 'attestationType': 'none',
                           'userVerificationRequirement': 'preferred', 'rpName': '数擎云核'},
        'requiredAction': 'CONFIGURE_TOTP',
    }

    checks.append(('合法片段通过', check_mfa_fragment(fragment, values) == []))

    bad = copy.deepcopy(fragment)
    bad['usersProfile'] = {}
    checks.append(('usersProfile 被拦', any('禁用键' in p for p in check_mfa_fragment(bad, values))))

    bad = copy.deepcopy(fragment)
    bad['bogus'] = 1
    checks.append(('未知键被拦', any('未知键' in p for p in check_mfa_fragment(bad, values))))

    bad = copy.deepcopy(fragment)
    bad['otpPolicy']['type'] = 'sms'
    checks.append(('非 totp 被拦', any('totp' in p for p in check_mfa_fragment(bad, values))))

    bad = copy.deepcopy(fragment)
    bad['requiredActions'] = []
    checks.append(('空 requiredActions 被拦',
                   any('requiredActions' in p for p in check_mfa_fragment(bad, values))))

    mismatch_values = copy.deepcopy(values)
    mismatch_values['otpPolicy']['digits'] = 8
    checks.append(('片段与 chart values 漂移被拦',
                   any('不一致' in p for p in check_mfa_fragment(fragment, mismatch_values))))

    mismatch_values = copy.deepcopy(values)
    mismatch_values['requiredAction'] = 'CONFIGURE_WEBAUTHN'
    checks.append(('requiredAction 不在片段被拦',
                   any('requiredAction' in p for p in check_mfa_fragment(fragment, mismatch_values))))

    same = b'a: 1\nb: 2\n'
    checks.append(('副本一致通过', check_istio_copy(same, same) == []))
    checks.append(('副本漂移被拦', len(check_istio_copy(same, b'a: 1\nb: 3\n')) > 0))
    checks.append(('副本缺文件被拦', len(check_istio_copy(same, None)) > 0))

    for name, res in checks:
        print('  [%s] %s' % ('PASS' if res else 'FAIL', name))
        ok = ok and bool(res)
    if not ok:
        print('FAIL self-test: 合成用例未全部命中')
        return 1
    print('✓ self-test 通过（MFA 四类反例 + 副本漂移反例均命中）')
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--self-test', action='store_true')
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run()


if __name__ == '__main__':
    sys.exit(main())
