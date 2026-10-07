#!/usr/bin/env python3
"""Spring 组件扫描覆盖校验：防止带 stereotype 的类落在扫描根之外（运行期静默 404）。

背景：rule-engine 的启动类在 `com.levango7.dataenginebdp.ruleengine`，而编排引擎整棵
包树在 `com.levango7.dataenginebdp.rule.engine.orchestrator`（`rule.engine` ≠ `ruleengine`）。
Spring Boot 默认只扫启动类所在包及其子包，于是 `@RestController OrchestratorController`
从未注册，`GET /api/v1/orchestrator/dags` 在真实进程里 404——而该模块 429 个单元测试
（手工 new 出来的 POJO）全绿，完全看不见这件事。

判定口径：对每个含 @SpringBootApplication 的模块，取其有效扫描根
（显式 scanBasePackages / @ComponentScan，否则为启动类所在包），模块内任何带
Spring stereotype 却不在任一根之下的类即为缺口。经 AutoConfiguration.imports
注册的类不算缺口（它们由自动配置装配，不受组件扫描影响）。

用法：
    python scripts/check-spring-component-scan.py            # 校验（默认）
    python scripts/check-spring-component-scan.py --report   # 只列出现状，不失败
"""

from __future__ import annotations

import argparse
import io
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

STEREO_RE = re.compile(
    r"@(RestController|Controller|Service|Repository|Component|Configuration"
    r"|ControllerAdvice|Aspect)\b"
)
PKG_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
# 只有这两个来源能改变组件扫描根。@EntityScan / @EnableJpaRepositories / @MapperScan
# 也用 basePackages 这个属性名，但它们管的是实体与仓储发现，不是 Bean 扫描——
# 把它们算进来会把 lineage-analyzer 这类正常模块误报成缺口（实测踩过）。
SB_APP_BLOCK_RE = re.compile(r"@SpringBootApplication\s*(\([^)]*\))?", re.S)
COMPONENT_SCAN_RE = re.compile(r"@ComponentScan\s*(\(([^)]*)\))?", re.S)
QUOTED_RE = re.compile(r"\"([\w.]+)\"")


def read(path: Path) -> str:
    return io.open(path, encoding="utf-8", errors="replace").read()


def module_of(java: Path) -> Path:
    """.../platform/<mod>/src/main/java/... → .../platform/<mod>"""
    parts = list(java.parts)
    for i in range(len(parts) - 2):
        if parts[i] == "src" and parts[i + 1] == "main" and parts[i + 2] == "java":
            return Path(*parts[:i])
    return java.parent


def autoconfig_classes(module: Path) -> set[str]:
    """该模块里由 AutoConfiguration.imports 装配的类全名（不受组件扫描约束）。"""
    found: set[str] = set()
    base = module / "src" / "main" / "resources" / "META-INF" / "spring"
    if not base.is_dir():
        return found
    for f in base.glob("*.imports"):
        for line in read(f).splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                found.add(line)
    return found


def scan_roots(app_java: Path) -> tuple[str, list[str]]:
    src = read(app_java)
    pkg_match = PKG_RE.search(src)
    app_pkg = pkg_match.group(1) if pkg_match else ""
    explicit: list[str] = []
    for m in SB_APP_BLOCK_RE.finditer(src):
        block = m.group(1) or ""
        if "scanBasePackages" in block:
            explicit += QUOTED_RE.findall(block.split("scanBasePackages", 1)[1])
    for m in COMPONENT_SCAN_RE.finditer(src):
        if m.group(2):
            explicit += QUOTED_RE.findall(m.group(2))
    roots = sorted(set(explicit))
    return app_pkg, (roots or [app_pkg])


def is_covered(pkg: str, roots: list[str]) -> bool:
    return any(pkg == r or pkg.startswith(r + ".") for r in roots)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true", help="只列现状，不作为门禁失败")
    args = ap.parse_args()

    apps: list[Path] = []
    for java in sorted(REPO_ROOT.rglob("*.java")):
        sp = str(java).replace("\\", "/")
        if "/src/main/java/" not in sp:
            continue
        if "/target/" in sp or "/node_modules/" in sp:
            continue
        if "@SpringBootApplication" in read(java):
            apps.append(java)

    findings: list[tuple[str, str, list[str], list[str]]] = []
    for app in apps:
        module = module_of(app)
        app_pkg, roots = scan_roots(app)
        exempt = autoconfig_classes(module)
        outside: dict[str, list[str]] = {}
        for java in sorted(module.glob("src/main/java/**/*.java")):
            src = read(java)
            if not STEREO_RE.search(src):
                continue
            m = PKG_RE.search(src)
            if not m:
                continue
            pkg = m.group(1)
            fqcn = pkg + "." + java.stem
            if fqcn in exempt or is_covered(pkg, roots):
                continue
            outside.setdefault(pkg, []).append(java.stem)
        if outside:
            classes = [c for ps in outside.values() for c in ps]
            findings.append((str(module.relative_to(REPO_ROOT)), app_pkg, roots, classes))

    print(f"Spring 组件扫描覆盖校验：检出 @SpringBootApplication 模块 {len(apps)} 个")
    if not findings:
        print("OK  所有带 stereotype 的类都落在各自启动类的扫描根内")
        return 0
    print(f"FAIL  {len(findings)} 个模块存在扫描根之外的 Bean（运行期不会注册）：")
    for module, app_pkg, roots, classes in findings:
        print(f"  - {module}  启动类包={app_pkg}  有效根={roots}")
        print(f"      之外且带 stereotype 的类 {len(classes)} 个: {', '.join(classes[:8])}"
              f"{' ...' if len(classes) > 8 else ''}")
    print("修法：在启动类上列出这些包，例如 "
          "@SpringBootApplication(scanBasePackages = {\"<原包>\", \"<被漏掉的包>\"})")
    return 0 if args.report else 1


if __name__ == "__main__":
    sys.exit(main())
