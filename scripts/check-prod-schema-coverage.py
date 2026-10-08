#!/usr/bin/env python3
"""校验「prod profile 下 Flyway 建的表 ⊇ 应用持久单元里的实体表」，缺口只减不增。

背景：prod 走 `application-prod.yml` 的 `ddl-auto: validate` + `flyway.enabled: true`，
表结构完全由本模块 `src/main/resources/db/migration/**` 决定。而 Hibernate 的持久单元
是按**应用扫描的包**收集的，不是按"本模块目录"——于是出现这类静默炸弹：
`encaps-tenant` 的 classpath 带 `encaps-layer`，两者同根包 `com.levango7.dataenginebdp.encaps`，
所以 encaps-layer 的 20 张实体表进了 encaps-tenant 的持久单元，而它的迁移只建 2 张
⇒ 启动即 `SchemaManagementException: Schema validation: missing table [api_definition]`
（本机真 jar + 空 PG 实测，见台账 #63）。这类问题在 CI 里今天**完全没有覆盖位**：
`FLYWAY_ENABLED` 与 `SPRING_PROFILES_ACTIVE` 在 workflows/compose/Dockerfile 里零命中，
所有 PG 腿都跑默认 profile（`ddl-auto=update`、Flyway 关）。

判定口径：
  持久单元表 = 扫描根（@SpringBootApplication 所在包 + @EntityScan/@ComponentScan 的
               basePackages/scanBasePackages）覆盖到的 @Entity 所映射的表，
               来源含本模块与 pom 里引用的内部模块（同包时被扫是必然的）。
  建表集合   = 本模块迁移目录里所有 `create table X`。
  缺口       = 持久单元表 - 建表集合。

退出码：
  0 = 缺口集合与 baseline 完全一致（既不新增，也不留"已补好却还挂在名单上"的过期项）
  1 = 出现新缺口，或 baseline 里有已闭环未销账的条目
  2 = 判定前提不成立（没找到任何带迁移的模块 / 解析异常），防"空集合冒充通过"
用法：python3 scripts/check-prod-schema-coverage.py [--report] [--write-baseline] [--self-test]
"""

from __future__ import annotations

import glob
import io
import os
import re
import shutil
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASELINE = os.path.join("docs", "prod-schema-coverage-baseline.yaml")


def _norm(p: str) -> str:
    return p.replace("\\", "/")


def _snake(name: str) -> str:
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name).lower()


def _package_of(text: str) -> str:
    m = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
    return m.group(1) if m else ""


def table_of_entity(text: str):
    """@Entity 文件的表名；@Table 常跨行，必须整文件 DOTALL 取。

    跨行这点是实测教训：只按单行匹配会把 `invite_codes`/`user_registrations` 这类
    已建表的实体误报成缺表（探针第一版就是这么错的）。
    """
    if not re.search(r"^\s*@Entity\b", text, re.M):
        return None
    m = re.search(
        r"@Table\s*\((.*?)\)\s*(?:@\w+[^\n]*\n*)*"
        r"(?:public\s+|final\s+|abstract\s*)*(?:class|interface)\s+(\w+)",
        text,
        re.S,
    )
    if m:
        nm = re.search(r'name\s*=\s*"([^"]+)"', m.group(1), re.S)
        return nm.group(1).lower() if nm else _snake(m.group(2))
    c = re.search(r"^\s*(?:public\s+|final\s+|abstract\s*)*(?:class|interface)\s+(\w+)", text, re.M)
    return _snake(c.group(1)) if c else None


def scan_roots(module: str) -> set:
    """应用真正扫描的包：@SpringBootApplication 所在包 + 显式 basePackages。"""
    roots = set()
    for f in glob.glob(os.path.join(module, "src/main/java/**/*.java"), recursive=True):
        text = _read(f)
        if "@SpringBootApplication" in text:
            roots.add(_package_of(text))
        for m in re.finditer(
            r"@(?:EntityScan|ComponentScan|SpringBootApplication)\s*\(([^)]*)\)", text, re.S
        ):
            for key in ("basePackages", "scanBasePackages", "value"):
                for b in re.finditer(key + r"\s*=\s*\{?\s*\"([^\"]+)\"", m.group(1)):
                    roots.add(b.group(1))
    return {r for r in roots if r}


def _read(path: str) -> str:
    return io.open(path, encoding="utf-8", errors="replace").read()


def entity_tables(src_root: str, roots: set) -> set:
    out = set()
    if not os.path.isdir(src_root):
        return out
    for f in glob.glob(os.path.join(src_root, "**/*.java"), recursive=True):
        text = _read(f)
        pkg = _package_of(text)
        if roots and not any(pkg == r or pkg.startswith(r + ".") for r in roots):
            continue
        t = table_of_entity(text)
        if t:
            out.add(t)
    return out


def migration_tables(module: str) -> set:
    out = set()
    for f in glob.glob(
        os.path.join(module, "src/main/resources/db/migration/**/*.sql"), recursive=True
    ):
        for m in re.finditer(r"create\s+table\s+(?:if\s+not\s+exists\s+)?([A-Za-z0-9_]+)", _read(f), re.I):
            out.add(m.group(1).lower())
    return out


def modules_with_migrations(repo: str) -> list:
    found = set()
    for p in glob.glob(os.path.join(repo, "platform/**/src/main/resources/db/migration/**/*.sql"), recursive=True):
        d = _norm(os.path.dirname(p))
        found.add(re.sub(r"/src/main/resources/.*", "", d))
    return sorted(found)


def artifact_index(repo: str) -> dict:
    idx = {}
    for pom in glob.glob(os.path.join(repo, "platform/**/pom.xml"), recursive=True):
        text = _read(pom)
        body = text[text.find("</parent>") + 1:] if "<parent>" in text else text
        m = re.search(r"<artifactId>([^<]+)</artifactId>", body)
        if m:
            idx[_norm(os.path.dirname(pom))] = m.group(1)
    return idx


def classify(repo: str) -> dict:
    """返回 {模块相对路径: [缺表...]}。"""
    mods = modules_with_migrations(repo)
    idx = artifact_index(repo)
    gaps = {}
    for m in mods:
        roots = scan_roots(m)
        if not roots:
            continue  # 纯库模块（无 @SpringBootApplication）不建独立持久单元
        tables = entity_tables(os.path.join(m, "src/main/java"), roots)
        pom = os.path.join(m, "pom.xml")
        if os.path.exists(pom):
            text = _read(pom)
            own_name = idx.get(_norm(m))
            for d in set(re.findall(r"<artifactId>([^<]+)</artifactId>", text)):
                if d == own_name:
                    continue
                for dd, da in idx.items():
                    if da == d and dd != m:
                        tables |= entity_tables(os.path.join(dd, "src/main/java"), roots)
        missing = sorted(tables - migration_tables(m))
        rel = _norm(m)[len(_norm(repo)) + 1:]
        gaps[rel] = missing
    return gaps


def read_baseline() -> dict:
    path = os.path.join(REPO, BASELINE)
    if not os.path.exists(path):
        return {}
    cur, out = None, {}
    for line in _read(path).split("\n"):
        m = re.match(r"^- module: (.+)$", line.strip())
        if m:
            cur = m.group(1).strip().strip('"')
            out[cur] = []
            continue
        m = re.match(r"^-\s+(.+)$", line.strip())
        if m and cur:
            out[cur].append(m.group(1).strip().strip('"'))
    return out


def write_baseline(gaps: dict) -> None:
    lines = [
        "# prod profile（Flyway + ddl-auto=validate）下的建表缺口基线",
        "# 由 scripts/check-prod-schema-coverage.py --write-baseline 生成；只减不增",
        "# 每条 = 某模块的持久单元里有、但本模块迁移没建的表",
    ]
    for mod in sorted(gaps):
        if not gaps[mod]:
            continue
        lines.append(f"- module: {mod}")
        for t in gaps[mod]:
            lines.append(f"  - {t}")
    io.open(os.path.join(REPO, BASELINE), "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")


# --------------------------------------------------------------------------- self-test
def _mk(root, rel, content):
    p = os.path.join(root, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    io.open(p, "w", encoding="utf-8", newline="\n").write(content)


def self_test() -> int:  # pylint: disable=too-many-return-statements
    """正反两向合成就绪：①新缺口必须被抓到 ②已补好却留在基线必须被抓到 ③解析器不假阳性。

    用临时目录而不是改产品码，跑完即删并回证零残留。
    """
    tmp = os.path.join(REPO, "scripts", ".tmp-selftest-prod-schema")
    shutil.rmtree(tmp, ignore_errors=True)
    app_a = os.path.join(tmp, "platform", "svc-a")
    app_b = os.path.join(tmp, "platform", "svc-b")
    try:
        # svc-a：实体 FooBar 建了表，无缺口；跨行 @Table 必须解析成 invite_codes
        _mk(app_a, "src/main/java/com/x/svcapp/App.java",
            "package com.x.svcapp;\nimport org.springframework.boot.autoconfigure.SpringBootApplication;\n@SpringBootApplication\npublic class App {}\n")
        _mk(app_a, "src/main/java/com/x/svcapp/InviteCode.java",
            "package com.x.svcapp;\nimport jakarta.persistence.*;\n@Entity\n@Table(\n        name = \"invite_codes\",\n        indexes = {})\npublic class InviteCode {}\n")
        _mk(app_a, "src/main/resources/db/migration/svc-a/V1__x.sql",
            "create table invite_codes (id bigint primary key);\n")
        # svc-b：实体带 api_definition，但迁移只建 own_table ⇒ 必须报缺表
        _mk(app_b, "src/main/java/com/x/svcapp/App.java",
            "package com.x.svcapp;\n@SpringBootApplication\npublic class App {}\n")
        _mk(app_b, "src/main/java/com/x/svcapp/ApiDefinition.java",
            "package com.x.svcapp;\n@Entity\n@Table(name = \"api_definition\")\npublic class ApiDefinition {}\n")
        _mk(app_b, "src/main/resources/db/migration/svc-b/V1__y.sql",
            "create table own_table (id bigint primary key);\n")

        gaps = classify(tmp)
        ok = True
        if gaps.get("platform/svc-a"):
            print(f"  [FAIL] 自检①：无缺口模块被误报 {gaps['platform/svc-a']}")
            ok = False
        else:
            print("  [ok] 自检①：跨行 @Table 正确解析为 invite_codes，无缺口未误报")
        if gaps.get("platform/svc-b") != ["api_definition"]:
            print(f"  [FAIL] 自检②：应报出 api_definition，实得 {gaps.get('platform/svc-b')}")
            ok = False
        else:
            print("  [ok] 自检②：缺表被准确抓到（= 本机实测的 encaps-tenant 形态）")
        # 基线判据：缺口 vs 基线三种错配都要判红
        if _verdict({"m": ["a"]}, {})["new"]:
            print("  [ok] 自检③：基线为空而实得有缺口 ⇒ 判红")
        else:
            print("  [FAIL] 自检③：新缺口没被判红")
            ok = False
        if _verdict({}, {"m": ["a"]})["stale"]:
            print("  [ok] 自检④：已闭环但基线未销账 ⇒ 判红")
        else:
            print("  [FAIL] 自检④：过期基线条目没被判红")
            ok = False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    if os.path.exists(tmp):
        print("  [FAIL] 自检残留未清:", tmp)
        return 1
    print("  [ok] 临时目录已清空，零残留")
    return 0 if ok else 1


def _verdict(gaps: dict, baseline: dict) -> dict:
    new, stale = {}, {}
    for mod, tables in gaps.items():
        extra = sorted(set(tables) - set(baseline.get(mod, [])))
        if extra:
            new[mod] = extra
    for mod, tables in baseline.items():
        gone = sorted(set(tables) - set(gaps.get(mod, [])))
        if gone:
            stale[mod] = gone
    return {"new": new, "stale": stale}


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    gaps = classify(REPO)
    if not gaps:
        print("FAIL: 没找到任何带迁移的 JPA 模块 ⇒ 判据失效，拒绝当作通过")
        return 2
    total_tables = sum(len(v) for v in gaps.values())
    if "--report" in sys.argv:
        for mod in sorted(gaps):
            print(f"{mod}: {len(gaps[mod])} 缺表" + (f" :: {','.join(gaps[mod])}" if gaps[mod] else ""))
        print(f"模块 {len(gaps)} 个，缺表合计 {total_tables}")
        return 0
    if "--write-baseline" in sys.argv:
        write_baseline(gaps)
        print(f"已写 {BASELINE}：{sum(1 for v in gaps.values() if v)} 个模块 / {total_tables} 条缺口")
        return 0

    v = _verdict(gaps, read_baseline())
    if v["new"]:
        print("FAIL: 出现新的建表缺口（prod+validate 会启动失败）：")
        for mod in sorted(v["new"]):
            print(f"  {mod}: {', '.join(v['new'][mod])}")
        print("  修法：给该模块补迁移脚本建表，或把实体移出扫描根；不要往 baseline 里塞")
        return 1
    if v["stale"]:
        print("FAIL: baseline 里有已闭环未销账的条目：")
        for mod in sorted(v["stale"]):
            print(f"  {mod}: {', '.join(v['stale'][mod])}")
        print("  修法：--write-baseline 重新生成（销账要及时，否则门禁会失去约束力）")
        return 1
    print(f"OK: {len(gaps)} 个带迁移模块的建表覆盖与基线一致（当前缺口 {total_tables} 条，均已在案）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
