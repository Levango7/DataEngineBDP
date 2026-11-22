#!/usr/bin/env python3
"""MFA realm 片段合并器（台账 #131）：把 MFA 片段并进现有 realm，产出最小更新体。

背景：Keycloak 的 MFA 策略是 **realm 级属性**（otpPolicy / webAuthnPolicy），
而 `requiredActions` 是 realm 的**列表型**属性——直接整表覆盖会把 realm 已配置的其他
required action（如 UPDATE_PASSWORD / UPDATE_PROFILE）一并抹掉；而 partialImport
又不支持这些资源类型（`scripts/import-keycloak-realm.sh` 的注释已记录：
`usersProfile` 写在 realm JSON 里会被静默忽略）。

本模块把两件事拆开：
  ① merge(current_realm, fragment) → **最小更新体**（只含 MFA 相关字段 + 合并后的
     requiredActions），供 `kcadm update realms/<realm> -b <体>` 使用；
  ② 只发字段、不发整个 realm —— GET 回来的 realm 里含 id / publicKey 等只读字段，
     原样 PUT 回去有被服务端拒绝的风险。

用法：
  python3 scripts/keycloak-mfa-merge.py current-realm.json mfa-fragment.json   # 输出更新体
  python3 scripts/keycloak-mfa-merge.py --self-test                            # 离线自检

退出码：0 成功；1 输入不合法；2 自检失败。
"""

from __future__ import annotations

import copy
import json
import os
import sys

# 片段里允许出现的顶层键（其余一律拒绝，避免把整份 realm 当片段塞进来）
ALLOWED_KEYS = {
    "otpPolicy",
    "webAuthnPolicy",
    "requiredActions",
    "browserFlow",
    "authenticationFlows",
}

# realm 里禁用的键：`usersProfile` 不是 partialImport 支持的资源类型，
# 写了会被静默忽略（见 scripts/check-keycloak-realm-fragment.py 判据 D）。
FORBIDDEN_KEYS = {"usersProfile"}


def validate_fragment(fragment: dict) -> list:
    """校验 MFA 片段结构；返回问题清单（空 = 合法）。"""
    problems = []
    if not isinstance(fragment, dict):
        return ["MFA 片段必须是 JSON 对象"]
    unknown = set(fragment) - ALLOWED_KEYS
    if unknown:
        problems.append("MFA 片段含未知键：%s（允许：%s）" % (sorted(unknown), sorted(ALLOWED_KEYS)))
    forbidden = set(fragment) & FORBIDDEN_KEYS
    if forbidden:
        problems.append("MFA 片段含禁用键：%s（该键会被服务端静默忽略）" % sorted(forbidden))
    otp = fragment.get("otpPolicy")
    if not isinstance(otp, dict):
        problems.append("otpPolicy 缺失或不是对象")
    else:
        if otp.get("type") != "totp":
            problems.append("otpPolicy.type 应为 totp，实际 %r" % otp.get("type"))
        for key in ("algorithm", "digits", "period"):
            if key not in otp:
                problems.append("otpPolicy.%s 缺失" % key)
    web = fragment.get("webAuthnPolicy")
    if web is not None and not isinstance(web, dict):
        problems.append("webAuthnPolicy 不是对象")
    actions = fragment.get("requiredActions")
    if not isinstance(actions, list) or not actions:
        problems.append("requiredActions 必须是非空数组")
    else:
        for act in actions:
            if not isinstance(act, dict) or not act.get("id"):
                problems.append("requiredActions 条目缺少 id")
    return problems


def merge(current_realm: dict, fragment: dict) -> dict:
    """把 MFA 片段并进 realm，产出**最小更新体**。

    · otpPolicy / webAuthnPolicy：片段为准，整体替换；
    · requiredActions：按 id 合并——realm 已有的条目保留，片段里的同 id 条目覆盖；
    · browserFlow / authenticationFlows：片段给了才写（未验证的登录流默认不带）。
    """
    out = {}
    if isinstance(current_realm, dict):
        for key in ("otpPolicy", "webAuthnPolicy"):
            if key in fragment:
                out[key] = copy.deepcopy(fragment[key])
            elif key in current_realm:
                out[key] = copy.deepcopy(current_realm[key])

        existing = current_realm.get("requiredActions")
        merged = []
        if isinstance(existing, list):
            for act in existing:
                if isinstance(act, dict):
                    merged.append(copy.deepcopy(act))
        for act in fragment.get("requiredActions", []) or []:
            if not isinstance(act, dict) or not act.get("id"):
                continue
            replaced = False
            for idx, cur in enumerate(merged):
                if cur.get("id") == act["id"]:
                    merged[idx] = copy.deepcopy(act)
                    replaced = True
                    break
            if not replaced:
                merged.append(copy.deepcopy(act))
        out["requiredActions"] = merged

        for key in ("browserFlow", "authenticationFlows"):
            if key in fragment:
                out[key] = copy.deepcopy(fragment[key])
            elif key in current_realm:
                out[key] = copy.deepcopy(current_realm[key])
    return out


def _self_test() -> int:
    failures = []

    fragment = {
        "otpPolicy": {"type": "totp", "algorithm": "HmacSHA1", "digits": 6, "period": 30, "lookAheadWindow": 1},
        "webAuthnPolicy": {"enabled": False, "attestationType": "none",
                            "userVerificationRequirement": "preferred", "rpName": "数擎云核"},
        "requiredActions": [{"id": "CONFIGURE_TOTP", "name": "Configure OTP",
                              "enabled": True, "defaultAction": True}],
    }
    current = {
        "realm": "sq",
        "id": "abc",
        "publicKey": "-----BEGIN PUBLIC KEY-----",
        "requiredActions": [
            {"id": "UPDATE_PASSWORD", "name": "Update Password", "enabled": True, "defaultAction": False},
            {"id": "CONFIGURE_TOTP", "name": "旧名", "enabled": False, "defaultAction": False},
        ],
    }

    got = merge(current, fragment)
    ids = [a.get("id") for a in got.get("requiredActions", [])]
    if "UPDATE_PASSWORD" not in ids:
        failures.append("合并后丢了 realm 原有的 required action（UPDATE_PASSWORD）")
    if got["requiredActions"][ids.index("CONFIGURE_TOTP")].get("defaultAction") is not True:
        failures.append("CONFIGURE_TOTP 未被片段覆盖为 defaultAction=true")
    if got["otpPolicy"]["type"] != "totp":
        failures.append("otpPolicy 未取片段值")
    if "id" in got or "publicKey" in got or "realm" in got:
        failures.append("更新体里混入了只读字段（id/publicKey/realm），PUT 有被拒风险")
    if "browserFlow" in got:
        failures.append("片段未给 browserFlow 时不应凭空写入")

    if validate_fragment(fragment):
        failures.append("合法片段被判不合法：%s" % validate_fragment(fragment))
    for bad, why in (
        ({"otpPolicy": {"type": "sms"}, "requiredActions": []}, "otpPolicy.type 非 totp"),
        ({"otpPolicy": {"type": "totp"}, "requiredActions": [{"id": "X"}], "usersProfile": {}}, "含 usersProfile"),
        ({"otpPolicy": {"type": "totp"}, "requiredActions": [{"id": "X"}], "bogus": 1}, "含未知键"),
    ):
        if not validate_fragment(bad):
            failures.append("非法片段未被拦下（%s）" % why)

    # 反向：片段为空 requiredActions 时不应崩，且结果保持 realm 原样
    empty = merge(current, {"otpPolicy": fragment["otpPolicy"], "requiredActions": []})
    if [a.get("id") for a in empty.get("requiredActions", [])] != ["UPDATE_PASSWORD", "CONFIGURE_TOTP"]:
        failures.append("片段 requiredActions 为空时未保留 realm 原列表")

    if failures:
        print("FAIL self-test:")
        for f in failures:
            print("  -", f)
        return 2
    print("OK self-test: MFA 片段合并（保留原 requiredActions / 覆盖同 id / 只输出最小更新体 / 非法片段可拦）")
    return 0


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if "--self-test" in argv:
        return _self_test()
    if len(argv) != 2:
        print(__doc__)
        return 1
    current_path, fragment_path = argv
    try:
        current = json.load(open(current_path, encoding="utf-8"))
    except Exception as exc:
        print("读取 realm JSON 失败：%s" % exc, file=sys.stderr)
        return 1
    try:
        fragment = json.load(open(fragment_path, encoding="utf-8"))
    except Exception as exc:
        print("读取 MFA 片段失败：%s" % exc, file=sys.stderr)
        return 1
    problems = validate_fragment(fragment)
    if problems:
        for p in problems:
            print("MFA 片段不合法：%s" % p, file=sys.stderr)
        return 1
    print(json.dumps(merge(current, fragment), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
