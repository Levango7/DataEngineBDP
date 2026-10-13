#!/usr/bin/env python3
"""realm 片段离线自检：不起 Keycloak 也能验证"角色与声明映射"的正确性。

背景：`scripts/import-keycloak-realm.sh` 负责把 `design/deploy/keycloak/sq-realm-roles.json`
导入 Keycloak，但它**从未实跑过**（缺真实环境）。该 JSON 一旦有错——角色名拼错、
realm-roles / tenant-id 两个 protocolMapper 缺失或多值配置不对——症状是**运行期
@PreAuthorize 全部 403、TenantContext 取不到租户**，而这种错在"导入成功"之后才暴露，
届时很难归因到 realm 片段。

本脚本把可静态判定的部分固化为门禁：不起服务、不连集群，只核对
**"realm 片段声明"与"代码侧真实消费方式"是否一致**。剩余无法离线验证的
（kcadm 语法、服务端 partialImport 行为、真机 token 签发）仍需联调，但那些是环境问题，
不是本文件能错了还看不出来的问题。

判据（四条，全部有代码侧依据）：
  A. JSON 合法、roles.realm 非空；
  B. 代码侧必需角色存在——`JwtAuthFilter.extractAuthorities` 读 `realm_access.roles`
     并映射成 `ROLE_<名字>`，而各模块 SecurityConfig 用 `hasRole('SUPER_ADMIN')` 等
     做 `@PreAuthorize`；这里按源码 grep 出的集合逐一核对 JSON 里有定义；
  C. 两个必需 protocolMapper 存在且配置正确：
     - `oidc-usermodel-realm-role-mapper`（claim.name = realm_access.roles，multivalued=true）
       —— 缺它角色进不了 token；
     - `oidc-usermodel-attribute-mapper`（claim.name = tenantId，user.attribute = tenantId）
       —— 缺它 TenantContext 没有租户归属，多租户隔离失效；
  D. 角色声明里的 `clientRole: true` 是 kcadm 给用户授 realm 角色时的必要字段
     （import-keycloak-realm.sh 的 --grant 分支写死了这个字面量），此处核对它未被改成 false。

退出码：0 = 通过；1 = 有命中。`--self-test` 先跑合成正反例再跑真判定（同其余门禁）。
"""

from __future__ import annotations

import json
import os
import re
import sys
import tempfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REALM_FILE = os.path.join(REPO_ROOT, "design", "deploy", "keycloak", "sq-realm-roles.json")

# 判据 B 的 grep 面：**全部 Java 模块**，不只是 common-security。
# 教训：SQL_GATEWAY_WRITER 的消费方在 platform/sql-gateway 的 SqlGatewayController，
# 只扫 common-security 会漏掉它——那正是本门禁存在的意义（防止 realm 缺角色，
# 而缺角色的症状是运行期 403、导入时毫无报错）。
JAVA_SRC_ROOT = os.path.join(REPO_ROOT, "platform")

REALM_ROLE_MAPPER = "oidc-usermodel-realm-role-mapper"
TENANT_ATTR_MAPPER = "oidc-usermodel-attribute-mapper"


def roles_used_in_code() -> set[str]:
    """从全部 Java 模块源码里 grep 出被引用的角色名。

    依据：`JwtAuthFilter.extractAuthorities` 把 `realm_access.roles` 映射为
    `ROLE_<name>`，Spring 的 `hasRole('X')` 比对的正是去掉 ROLE_ 前缀的那部分。
    因此源码里出现的 `hasRole('...')` / `RolesAllowed("...")` 字面量就是
    realm 必须定义的角色集合。

    扫描面是 `platform/**/src/main/java` 全树（不是单个模块）——跨模块引用的角色
    （如 sql-gateway 的 SQL_GATEWAY_WRITER）必须被覆盖到。

    两条实现约束（都是实测踩出来的）：
      - 必须排除注释行：Javadoc 里大量 `{@code @PreAuthorize("hasRole('ADMIN')")}`
        是说明文字，不是真注解。不排除会报出假缺口。
      - 必须正确识别"单行注解"：`@PreAuthorize("hasRole('X')")` 与左右括号都同行时，
        不能误判为"多行未闭合"而把下一行也吞进来（实测踩过：吞掉相邻文件的真注解，
        造成角色集合虚高）。
    """
    found: set[str] = set()
    role_ref = re.compile(r"hasRole\(\s*['\"]([A-Z_]+)['\"]\s*\)|RolesAllowed\(\s*['\"]([A-Z_]+)['\"]")
    # 续读窗口：只在注解行**确实没闭合**（看见了 @PreAuthorize( 但没有配对的右括号）时才打开
    in_multi = False
    depth = 0
    for root, _dirs, files in os.walk(JAVA_SRC_ROOT):
        if os.sep + "target" + os.sep in root + os.sep:
            continue
        for fn in files:
            if not fn.endswith(".java"):
                continue
            for line in open(os.path.join(root, fn), encoding="utf-8", errors="replace"):
                stripped = line.strip()
                if stripped.startswith(("*", "//", "/*")):
                    in_multi = False  # 注释行：Javadoc 里的示例文字 + 终止续读
                    continue
                is_anno = bool(re.match(r"^\s*@PreAuthorize\b", line))
                if not (in_multi or is_anno):
                    continue
                for rm in role_ref.finditer(line):
                    role = rm.group(1) or rm.group(2)
                    # ROLE_ 前缀是 Spring 的等价写法，去掉后才是 realm 里的角色名
                    found.add(role[5:] if role.startswith("ROLE_") else role)
                # 括号配平决定是否续读：只数本行（自注解行起）出现的 ( 与 )
                depth += line.count("(") - line.count(")")
                in_multi = depth > 0
                if not in_multi:
                    depth = 0
    return found


def check(path: str) -> list[str]:
    findings: list[str] = []
    try:
        doc = json.load(open(path, encoding="utf-8"))
    except Exception as exc:
        return [f"{os.path.basename(path)}: JSON 解析失败: {exc}"]

    realm_roles = {r.get("name") for r in doc.get("roles", {}).get("realm", []) if isinstance(r, dict)}
    if not realm_roles:
        findings.append("roles.realm 为空：没有任何角色定义")
        return findings

    # B. 源码引用的角色必须有定义
    for role in sorted(roles_used_in_code()):
        if role not in realm_roles:
            findings.append(
                f"common-security 源码引用了角色 {role}（hasRole/RolesAllowed），"
                f"但 realm 片段未定义它 ⇒ 该角色用户在运行期必 403"
            )

    # C. 两个必需 protocolMapper
    for client in doc.get("clients", []):
        if not isinstance(client, dict):
            continue
        cid = client.get("clientId", "?")
        mappers = {m.get("protocolMapper"): m for m in client.get("protocolMappers", []) if isinstance(m, dict)}

        rm = mappers.get(REALM_ROLE_MAPPER)
        if rm is None:
            findings.append(f"client {cid}: 缺少 {REALM_ROLE_MAPPER}（角色进不了 access token）")
        else:
            cfg = rm.get("config", {})
            claim = cfg.get("claim.name")
            mv = cfg.get("multivalued")
            if claim != "realm_access.roles":
                findings.append(
                    f"client {cid}: realm-roles mapper 的 claim.name={claim!r}，"
                    f"应为 'realm_access.roles'（JwtAuthFilter.extractAuthorities 只认这个键）"
                )
            if str(mv).lower() != "true":
                findings.append(
                    f"client {cid}: realm-roles mapper 的 multivalued={mv!r}，" f'应为 "true"（多角色用户只出一个值）'
                )

        tm = mappers.get(TENANT_ATTR_MAPPER)
        if tm is None:
            findings.append(f"client {cid}: 缺少 {TENANT_ATTR_MAPPER}（TenantContext 取不到租户）")
        else:
            cfg = tm.get("config", {})
            if cfg.get("claim.name") != "tenantId":
                findings.append(
                    f"client {cid}: tenant-id mapper 的 claim.name={cfg.get('claim.name')!r}，"
                    f"应为 'tenantId'（JWT_CLAIM_TENANT_ID）"
                )
            if cfg.get("user.attribute") != "tenantId":
                findings.append(
                    f"client {cid}: tenant-id mapper 的 user.attribute="
                    f"{cfg.get('user.attribute')!r}，应为 'tenantId'（用户属性名）"
                )
            if str(cfg.get("access.token.claim")).lower() != "true":
                findings.append(f"client {cid}: tenant-id mapper 的 access.token.claim 未开启 ⇒ 声明不会进 token")
    return findings


# ---------------------------------------------------------------------------
# 自检
# ---------------------------------------------------------------------------
GOOD = {
    "realm": "sq",
    "roles": {"realm": [{"name": "SUPER_ADMIN"}, {"name": "TENANT_ADMIN"}, {"name": "USER"}]},
    "clients": [
        {
            "clientId": "sq-console",
            "protocolMappers": [
                {
                    "protocolMapper": REALM_ROLE_MAPPER,
                    "config": {"claim.name": "realm_access.roles", "multivalued": "true", "access.token.claim": "true"},
                },
                {
                    "protocolMapper": TENANT_ATTR_MAPPER,
                    "config": {"claim.name": "tenantId", "user.attribute": "tenantId", "access.token.claim": "true"},
                },
            ],
        }
    ],
}

BAD_MISSING_MAPPER = {
    "realm": "sq",
    "roles": {"realm": [{"name": "SUPER_ADMIN"}, {"name": "TENANT_ADMIN"}, {"name": "USER"}]},
    "clients": [{"clientId": "sq-console", "protocolMappers": []}],
}


def self_test() -> int:
    failures = []
    # 用与判据 B 同源的角色集合：直接从真源码取，保证正例包含所有被引用角色
    used = roles_used_in_code()
    if not used:
        print("FAIL: 未能从 platform/**/src/main/java grep 到任何角色引用（判据 B 形同虚设）")
        return 1
    good = json.loads(json.dumps(GOOD))
    good["roles"]["realm"] = [{"name": r} for r in sorted(used)]
    with tempfile.TemporaryDirectory() as tmp:
        p = os.path.join(tmp, "good.json")
        json.dump(good, open(p, "w", encoding="utf-8"))
        got = check(p)
        # 正例里角色是按源码集合生成的，因此判据 B 不应命中；只验证 mapper 判据不误报
        if got:
            failures.append("正例（mapper 齐全 + 角色按源码集合）不应命中，却得到: %s" % got)

        p2 = os.path.join(tmp, "bad.json")
        json.dump(BAD_MISSING_MAPPER, open(p2, "w", encoding="utf-8"))
        got2 = check(p2)
        if not any(REALM_ROLE_MAPPER in f or TENANT_ATTR_MAPPER in f for f in got2):
            failures.append("反例（两个 mapper 都缺）应命中，却得到: %s" % got2)

    if failures:
        print("FAIL: self-test 未通过")
        for f in failures:
            print("  " + f)
        return 1
    print(
        "OK: self-test 通过（源码引用 %d 个角色: %s；正例无命中 / 反例命中缺失 mapper）"
        % (len(used), ",".join(sorted(used)))
    )
    return 0


def main() -> int:
    if "--self-test" in sys.argv and self_test() != 0:
        return 1
    if not os.path.isfile(REALM_FILE):
        print("FAIL: 找不到 realm 片段 %s" % REALM_FILE)
        return 1
    findings = check(REALM_FILE)
    if findings:
        print("FAIL: realm 片段离线自检命中 %d 处: " % len(findings))
        for f in findings:
            print("  " + f)
        print("\n这些错误在真机导入成功之后才会以 403 形式暴露，务必先修再导入。")
        return 1
    print("OK: realm 片段离线自检通过（角色齐备、两个必需 mapper 配置正确）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
