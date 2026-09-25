#!/usr/bin/env python3
"""数据库迁移覆盖校验（防"生产没人建表"回归）。

背景：本仓 Java 模块生产 profile 统一 `ddl-auto: validate`，但长期 0 迁移框架、
0 份建表 DDL —— 服务连上 PostgreSQL 就会因缺表启动失败。已经接入 Flyway 的模块
必须真的启用它，未接入的模块必须在待补清单里显式登记，避免"新增缺口却无人知晓"。

校验规则：
  1. 含 @Entity 的模块，若有 db/migration/*.sql → 视为已接入：
     a) 其 application-prod.yml 必须启用 flyway（enabled: true），否则报错（基线存在但永不执行）；
     b) 不得仍留在待补清单里。
  2. 含 @Entity 但无 db/migration 的模块 → 必须在 docs/db-migration-backlog.yaml 登记，
     未登记视为新增缺口，报错。
  3. 待补清单里已不存在的模块（目录删除/已接入）→ 报错，要求清理清单。

用法：
    python scripts/check-db-migration-coverage.py            # 校验（CI 用）
    python scripts/check-db-migration-coverage.py --verbose  # 打印逐模块判定

退出码：0=一致；1=存在上述任一违规；2=环境/文件错误。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("ERROR: 需要 PyYAML（pip install pyyaml）", file=sys.stderr)
    sys.exit(2)

REPO_ROOT = Path(__file__).resolve().parents[1]
PLATFORM = REPO_ROOT / "platform"
BACKLOG = REPO_ROOT / "docs" / "db-migration-backlog.yaml"


def jpa_modules() -> dict[str, int]:
    """module 相对路径 → @Entity 类数量（只统计 src/main/java，忽略 target）。"""
    out: dict[str, int] = {}
    for pom in sorted(PLATFORM.glob("**/pom.xml")):
        if "target" in pom.parts:
            continue
        src = pom.parent / "src" / "main" / "java"
        if not src.is_dir():
            continue
        count = 0
        for java in src.rglob("*.java"):
            if "target" in java.parts:
                continue
            try:
                text = java.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            if "@Entity" in text:
                count += 1
        if count:
            rel = pom.parent.relative_to(REPO_ROOT).as_posix()
            out[rel] = count
    return out


def has_migrations(module_rel: str) -> bool:
    mig_dir = REPO_ROOT / module_rel / "src" / "main" / "resources" / "db" / "migration"
    return mig_dir.is_dir() and any(mig_dir.glob("*.sql"))


def flyway_enabled_in_prod(module_rel: str) -> tuple[bool, str]:
    """检查 application-prod.yml 是否启用 flyway。返回 (是否启用, 说明)。"""
    prod = REPO_ROOT / module_rel / "src" / "main" / "resources" / "application-prod.yml"
    if not prod.is_file():
        return False, "缺 application-prod.yml"
    try:
        doc = yaml.safe_load(prod.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as exc:
        return False, f"application-prod.yml 解析失败: {exc}"
    flyway = (doc.get("spring") or {}).get("flyway")
    if not isinstance(flyway, dict):
        return False, "application-prod.yml 无 spring.flyway 配置"
    if flyway.get("enabled") is not True:
        return False, f"spring.flyway.enabled={flyway.get('enabled')!r}（需为 true）"
    if not flyway.get("schemas"):
        return False, "未设置 spring.flyway.schemas（多服务共用 shuqing 库，须各自独立 schema）"
    return True, "OK"


def load_backlog() -> set[str]:
    if not BACKLOG.is_file():
        print(f"ERROR: 缺少待补清单 {BACKLOG}", file=sys.stderr)
        sys.exit(2)
    doc = yaml.safe_load(BACKLOG.read_text(encoding="utf-8")) or {}
    return {str(e["module"]) for e in doc.get("awaiting_migration", []) or []}


def main() -> int:
    parser = argparse.ArgumentParser(description="校验 JPA 模块的建表迁移覆盖")
    parser.add_argument("--verbose", action="store_true", help="打印逐模块判定")
    args = parser.parse_args()

    modules = jpa_modules()
    if not modules:
        print("ERROR: 未扫描到任何含 @Entity 的 Java 模块", file=sys.stderr)
        return 2

    backlog = load_backlog()
    problems: list[str] = []
    migrated: list[str] = []
    gaps: list[str] = []

    for module, entities in sorted(modules.items()):
        covered = has_migrations(module)
        if args.verbose:
            print(f"[scan] {module}: entities={entities} migration={'有' if covered else '无'}")
        if covered:
            migrated.append(module)
            if module in backlog:
                problems.append(f"{module} 已有 db/migration，却仍登记在待补清单中 → 请删除该条目")
            ok, why = flyway_enabled_in_prod(module)
            if not ok:
                problems.append(f"{module} 有基线脚本但生产未正确启用 Flyway：{why}")
        else:
            gaps.append(module)
            if module not in backlog:
                problems.append(
                    f"{module} 含 {entities} 个 @Entity 但无迁移脚本，且未登记在 "
                    f"docs/db-migration-backlog.yaml → 补基线或显式登记缺口")

    for module in sorted(backlog - set(modules)):
        problems.append(f"待补清单中的 {module} 已不存在（无 @Entity 或目录已删）→ 请清理清单")

    print(f"JPA 模块 {len(modules)} 个：已接入迁移 {len(migrated)}，待补 {len(gaps)}")
    if migrated:
        print("  已接入: " + ", ".join(migrated))

    if problems:
        for p in problems:
            print(f"FAIL: {p}")
        return 1

    print("OK: 迁移覆盖与待补清单一致（新增缺口会被本校验拦住）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
