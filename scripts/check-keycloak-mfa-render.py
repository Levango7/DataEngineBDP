#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Keycloak MFA 片段**渲染产物**门禁（台账 #131）。

背景：既有门禁 `scripts/check-deploy-surface-assets.py` 只对账两份**源**文件——
  design/deploy/keycloak/sq-realm-mfa.json ↔ design/deploy/charts/keycloak/values.yaml
——**不渲染模板** ⇒ 模板层的产物形态缺陷在该门禁下结构性不可见。

真实踩到过的缺陷（2026-11-22，verifier 审阅台账 #128–#132 部署面批次时实测）：
  design/deploy/charts/keycloak/templates/realm-mfa-fragment.yaml 原写法为
      {{ include "keycloak.mfaRealm" $ | toPrettyJson | indent 4 }}
  而 keycloak.mfaRealm 产出的是 **YAML 文本**，toPrettyJson 直接作用在字符串上
  ⇒ 渲染产物是「JSON 字符串包着 YAML 文本」，json.loads 出来是 str 而不是 dict，
  喂给仓内唯一的消费脚本 scripts/keycloak-mfa-merge.py 直接 rc=1。
  而 helm lint / helm template 全绿（模板语法没错，错的是产物形态）。

判据（A–D）：
  A. 渲染产物里能定位到 MFA 片段 ConfigMap 的 data.<fileName> 值；
  B. 该值 json.loads 之后必须是 **JSON 对象**（dict）—— 正面拦住上面那个缺陷；
  C. 该对象必须通过 scripts/keycloak-mfa-merge.py 的 validate_fragment（零问题）；
  D. requiredActions[].defaultAction 必须是布尔（Keycloak 语义是布尔；权威片段
     design/deploy/keycloak/sq-realm-mfa.json 也是 true）—— 字符串是错值。

用法：
  python3 scripts/check-keycloak-mfa-render.py --self-test
  python3 scripts/check-keycloak-mfa-render.py --rendered <helm template 输出文件>

退出码：0 通过；1 有命中；2 自测失败；3 用法错误。
"""

from __future__ import annotations

import argparse
import importlib.util
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MERGE_SCRIPT = os.path.join(ROOT, "scripts", "keycloak-mfa-merge.py")
KEYCLOAK_VALUES = os.path.join(ROOT, "design", "deploy", "charts", "keycloak", "values.yaml")
DEFAULT_FRAGMENT_KEY = "sq-realm-mfa.json"


def read_text(path):
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def load_merge_module():
    """加载 scripts/keycloak-mfa-merge.py（文件名带连字符 ⇒ 走 importlib）。"""
    spec = importlib.util.spec_from_file_location("keycloak_mfa_merge", MERGE_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError("无法加载 %s" % MERGE_SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def declared_fragment_key(values_path=KEYCLOAK_VALUES):
    """从 chart values 里取 mfa.fragment.fileName。

    刻意**不依赖 YAML 库**：CI 的 helm-lint job 没有 pip 安装 PyYAML 的既有事实，
    门禁脚本不该给 job 引入隐式依赖。行扫描即可（mfa: 块 → fileName: 行）。
    """
    try:
        text = read_text(values_path)
    except OSError:
        return DEFAULT_FRAGMENT_KEY
    lines = text.splitlines()
    start = None
    for i, ln in enumerate(lines):
        if re.match(r"^mfa:\s*$", ln):
            start = i
            break
    if start is None:
        return DEFAULT_FRAGMENT_KEY
    block = []
    for ln in lines[start + 1:]:
        if ln.strip() and not ln.strip().startswith("#") and not ln[:1].isspace():
            break
        block.append(ln)
    for ln in block:
        m = re.match(r"^\s+fileName:\s*\"?([^\"\s]+)\"?\s*$", ln)
        if m:
            return m.group(1)
    return DEFAULT_FRAGMENT_KEY


def _block_body(lines, start, parent_indent):
    """收集 YAML 块标量的正文（相对 parent_indent 更深的行），并按块缩进去缩进。"""
    body = []
    block_indent = None
    for ln in lines[start:]:
        if not ln.strip():
            body.append("")
            continue
        indent = len(ln) - len(ln.lstrip())
        if indent <= parent_indent:
            break
        if block_indent is None:
            block_indent = indent
        body.append(ln[block_indent:] if len(ln) >= block_indent else ln.strip())
    return body, block_indent


def extract_data_value(text, key):
    """从渲染文本里取出 data.<key> 的值。返回 (value, err)，二者恰有一个非 None。"""
    lines = text.splitlines()
    pat = re.compile(r"^(\s*)" + re.escape(key) + r":(?:\s+(.*))?$")
    for i, ln in enumerate(lines):
        m = pat.match(ln)
        if not m:
            continue
        indent = len(m.group(1))
        rest = (m.group(2) or "").strip()
        if not rest:
            return None, "渲染产物里 %s 的值为空" % key
        if rest[0] in "|>":
            body, block_indent = _block_body(lines, i + 1, indent)
            if block_indent is None:
                return None, "渲染产物里 %s 的块标量为空" % key
            if rest[0] == ">":
                return None, ("渲染产物里 %s 使用折叠标量（>），本门禁只支持字面量标量（|）"
                              % key)
            return "\n".join(body).rstrip("\n"), None
        if rest[0] in ("'", '"') and len(rest) >= 2 and rest[-1] == rest[0]:
            rest = rest[1:-1]
        return rest, None
    return None, "渲染产物里找不到 data.%s" % key


def check_rendered(text, fragment_key, merge_mod):
    """返回问题清单（空 = 通过）。"""
    value, err = extract_data_value(text, fragment_key)
    if err:
        return [err]
    try:
        parsed = json.loads(value)
    except Exception as exc:
        return ["MFA 片段不是合法 JSON：%s" % exc]
    if not isinstance(parsed, dict):
        return ["MFA 片段渲染产物是 JSON %s 而非对象（dict）——模板须写 "
                "fromYaml | toPrettyJson；直接 toPrettyJson 会把 YAML 文本再序列化一层"
                % type(parsed).__name__]
    problems = []
    for p in merge_mod.validate_fragment(parsed):
        problems.append("MFA 片段不合法：%s" % p)
    for act in parsed.get("requiredActions") or []:
        if not isinstance(act, dict):
            continue
        if not isinstance(act.get("defaultAction"), bool):
            problems.append("requiredActions[%s].defaultAction 必须是布尔，实际 %r"
                            % (act.get("id"), act.get("defaultAction")))
    return problems


GOOD_FRAGMENT = {
    "otpPolicy": {"type": "totp", "algorithm": "HmacSHA1", "digits": 6,
                  "period": 30, "lookAheadWindow": 1},
    "webAuthnPolicy": {"enabled": False, "attestationType": "none",
                       "userVerificationRequirement": "preferred", "rpName": "数擎云核"},
    "requiredActions": [{"id": "CONFIGURE_TOTP", "name": "Configure OTP",
                         "enabled": True, "defaultAction": True}],
}


def _render_block(value_text, key=DEFAULT_FRAGMENT_KEY, key_indent=2):
    """把一段文本包成 ConfigMap 的 data.<key>: |- 字面量块。"""
    pad = " " * key_indent
    body = "\n".join(pad + "  " + l for l in value_text.splitlines())
    return ("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: keycloak-mfa-fragment\n"
            "data:\n%s%s: |-\n%s\n" % (pad, key, body))


def _self_test():
    merge_mod = load_merge_module()
    failures = []

    good = _render_block(json.dumps(GOOD_FRAGMENT, ensure_ascii=False, indent=2))
    got = check_rendered(good, DEFAULT_FRAGMENT_KEY, merge_mod)
    if got:
        failures.append("合法渲染产物被判不合法：%s" % got)

    # 反例 1：F1 真实缺陷形态——JSON 字符串包着 YAML 文本
    bug = _render_block(json.dumps(
        'otpPolicy:\n  type: "totp"\nrequiredActions: []', ensure_ascii=False))
    if not check_rendered(bug, DEFAULT_FRAGMENT_KEY, merge_mod):
        failures.append("F1 缺陷形态（JSON 字符串包 YAML 文本）未被判出")

    # 反例 2：defaultAction 是字符串而非布尔
    bad_default = dict(GOOD_FRAGMENT)
    bad_default["requiredActions"] = [dict(GOOD_FRAGMENT["requiredActions"][0],
                                          defaultAction="CONFIGURE_TOTP")]
    if not check_rendered(_render_block(json.dumps(bad_default, ensure_ascii=False)),
                          DEFAULT_FRAGMENT_KEY, merge_mod):
        failures.append("defaultAction 字符串形态未被判出")

    # 反例 3：缺 otpPolicy
    bad_missing = {"requiredActions": GOOD_FRAGMENT["requiredActions"]}
    if not check_rendered(_render_block(json.dumps(bad_missing, ensure_ascii=False)),
                          DEFAULT_FRAGMENT_KEY, merge_mod):
        failures.append("缺 otpPolicy 的片段未被判出")

    # 反例 4：产物里没有片段键
    no_key = "apiVersion: v1\nkind: ConfigMap\ndata:\n  other.json: |-\n    {}\n"
    if not check_rendered(no_key, DEFAULT_FRAGMENT_KEY, merge_mod):
        failures.append("缺片段键的渲染产物未被判出")

    # 正对照 2：行内标量形态也应能取出并判合法
    inline = "data:\n  %s: '%s'\n" % (DEFAULT_FRAGMENT_KEY,
                                      json.dumps(GOOD_FRAGMENT, ensure_ascii=False))
    got_inline = check_rendered(inline, DEFAULT_FRAGMENT_KEY, merge_mod)
    if got_inline:
        failures.append("行内标量形态被判不合法：%s" % got_inline)

    if failures:
        print("FAIL self-test:")
        for f in failures:
            print("  -", f)
        return 2
    print("OK self-test: MFA 片段渲染产物门禁"
          "（对象形态 / 字符串形态 / defaultAction 布尔 / 缺键 / 行内标量）")
    return 0


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if "--self-test" in argv:
        return _self_test()

    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--rendered", default=None)
    parser.add_argument("--fragment-key", default=None)
    try:
        args = parser.parse_args([a for a in argv if a not in ("--self-test",)])
    except SystemExit:
        print(__doc__, file=sys.stderr)
        return 3
    if not args.rendered:
        print(__doc__, file=sys.stderr)
        return 3

    key = args.fragment_key or declared_fragment_key()
    try:
        text = read_text(args.rendered)
    except OSError as exc:
        print("读取渲染产物失败：%s" % exc, file=sys.stderr)
        return 1
    try:
        merge_mod = load_merge_module()
    except Exception as exc:
        print("加载合并器失败：%s" % exc, file=sys.stderr)
        return 1

    problems = check_rendered(text, key, merge_mod)
    if problems:
        print("FAIL: Keycloak MFA 片段渲染产物门禁命中 %d 处：" % len(problems))
        for p in problems:
            print("  -", p)
        return 1
    print("OK: Keycloak MFA 片段渲染产物是合法 JSON 对象（片段键 %s）" % key)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
