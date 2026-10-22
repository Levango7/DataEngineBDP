#!/usr/bin/env python3
"""Chart env 与 application.yml 默认值一致性门禁（防漂移）。

背景：chart env 与 Spring Boot application.yml 的默认值若不一致，K8s 部署时
chart 会静默覆盖应用默认值，导致本地 jar 行为与 K8s 行为不一致（如
`GOVERNANCE_QUALITY_SOURCE_ENABLED` 被 chart 覆盖为 `"false"` 而应用默认 `true`）。

本脚本固化门禁：
  A. chart env 中出现的 key，若在任一 application.yml 中以 ${KEY:default} 形式声明，
     则 chart 的值必须与 yml 默认值一致；
  B. chart env 中出现的 key，若在任一 application.yml 中未声明默认，仅记录不阻断
    （可能是 chart 专属 env，不属本门禁范围）。

退出码：0 = 干净；1 = 有命中。`--self-test` 先跑合成正反例再跑真判定（同其余门禁）。
"""

from __future__ import annotations

import glob
import os
import re
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHART_ROOT = os.path.join(REPO, "design", "deploy", "charts")
APP_YML_GLOB = os.path.join(REPO, "platform", "*", "src", "main", "resources", "application.yml")

# 全大写带下划线的 env key：SERVER_PORT、GOVERNANCE_QUALITY_SOURCE_ENABLED 等
ENV_LINE = re.compile(r"^\s{2,}([A-Z][A-Z0-9_]*)\s*:\s*\"?([^\"]*)\"?\s*$")
APP_ENV_REF = re.compile(r"\$\{([A-Z][A-Z0-9_]*)(?::([^}]*))?\}")


def split_docs(text: str) -> list:
    """按 `---` 切分成文档（保留行号偏移以便报错定位）。"""
    docs, cur, start = [], [], 1
    for i, line in enumerate(text.split("\n"), 1):
        if re.match(r"^---\s*$", line):
            docs.append((start, cur))
            cur, start = [], i + 1
        else:
            cur.append(line)
    docs.append((start, cur))
    return docs


def extract_chart_envs(values_path: str) -> list[tuple[str, int, str, str]]:
    """从 chart values.yaml 提取 env: 下的 (key, lineno, raw_value)。

    只处理 `env:` 顶级 key 下的直接子项；兼容多个 service 各自的 env 块。
    """
    out = []
    lines = open(values_path, encoding="utf-8", errors="replace").read().split("\n")
    in_env = False
    env_indent = 0
    for i, line in enumerate(lines, 1):
        stripped = line.lstrip()
        if stripped.startswith("#"):
            continue
        if not in_env:
            if re.match(r"^env:\s*$", stripped):
                in_env = True
                env_indent = len(line) - len(stripped)
            continue
        # 已在 env 块内：缩回同级或更浅则退出
        indent = len(line) - len(stripped)
        if indent <= env_indent:
            in_env = False
            if re.match(r"^env:\s*$", stripped):
                in_env = True
                env_indent = indent
            continue
        m = ENV_LINE.match(line)
        if m:
            out.append((m.group(1), i, m.group(2)))
    return out


def extract_app_defaults(app_yml_path: str) -> dict[str, str]:
    """从 application.yml 提取 ${KEY:default} -> {key: default}。"""
    out: dict[str, str] = {}
    text = open(app_yml_path, encoding="utf-8", errors="replace").read()
    for m in APP_ENV_REF.finditer(text):
        key = m.group(1)
        default = m.group(2) if m.group(2) is not None else ""
        # 同一文件内同一 key 若出现多个不同默认，取第一个（不跨文件比对）。
        out.setdefault(key, default)
    return out


def scan() -> list[str]:
    findings: list[str] = []

    # 收集每个 application.yml 的默认值（按文件维度，不跨文件比对：不同服务
    # 合理地各有自己的数据库路径等默认值）。
    app_defaults_by_file: dict[str, dict[str, str]] = {}
    for path in sorted(glob.glob(APP_YML_GLOB)):
        rel = os.path.relpath(path, REPO)
        app_defaults_by_file[rel] = extract_app_defaults(path)

    # 扫描每个 chart 的 values.yaml env，只对比与“同名服务”的 application.yml
    # 默认值；chart env 值为空字符串（留空不注入）时跳过。
    for cdir in sorted(os.listdir(CHART_ROOT)):
        base = os.path.join(CHART_ROOT, cdir)
        if not os.path.isdir(base) or cdir.startswith("_"):
            continue
        values = os.path.join(base, "values.yaml")
        if not os.path.isfile(values):
            continue
        rel = os.path.relpath(values, CHART_ROOT)

        # 同名服务的 application.yml：platform/<svc>/.../application.yml
        svc_defaults: dict[str, str] = {}
        for yml_rel, defaults in app_defaults_by_file.items():
            parts = yml_rel.replace("\\", "/").split("/")
            # platform/<svc>/src/main/resources/application.yml -> svc 名
            if len(parts) >= 2 and parts[0] == "platform":
                svc = parts[1]
                if svc == cdir:
                    svc_defaults = defaults
                    break

        for key, lineno, value in extract_chart_envs(values):
            if value == "":
                # chart 留空表示不注入/由外部注入，不强制与 yml 默认一致
                continue
            if key in svc_defaults:
                expected = svc_defaults[key]
                if value != expected:
                    findings.append(
                        f"{rel}:{lineno} {key} chart 值 '{value}' 与 application.yml 默认值 "
                        f"'{expected}' 不一致（K8s 会静默覆盖应用默认值）"
                    )

    return findings


def self_test() -> int:
    """合成正反例：一致/不一致/未声明 三类情况。"""
    with tempfile.TemporaryDirectory() as td:
        charts = os.path.join(td, "design", "deploy", "charts")
        platform = os.path.join(td, "platform")
        os.makedirs(os.path.join(charts, "good", "templates"))
        os.makedirs(os.path.join(charts, "bad", "templates"))
        os.makedirs(os.path.join(charts, "undeclared", "templates"))
        os.makedirs(os.path.join(platform, "good", "src", "main", "resources"))
        os.makedirs(os.path.join(platform, "bad", "src", "main", "resources"))

        # application.yml：GOVERNANCE_QUALITY_SOURCE_ENABLED 默认 true，SERVER_PORT 默认 18090
        for svc in ("good", "bad"):
            open(os.path.join(platform, svc, "src", "main", "resources", "application.yml"), "w",
                  encoding="utf-8").write(
                "governance:\n"
                "  quality:\n"
                "    source-enabled: ${GOVERNANCE_QUALITY_SOURCE_ENABLED:true}\n"
                "server:\n"
                "  port: ${SERVER_PORT:18090}\n"
            )

        # 正例：chart 值与 yml 默认一致（chart 目录名 = 同名 platform 服务名）
        open(os.path.join(charts, "good", "values.yaml"), "w", encoding="utf-8").write(
            "env:\n"
            "  GOVERNANCE_QUALITY_SOURCE_ENABLED: \"true\"\n"
            "  SERVER_PORT: \"18090\"\n"
        )
        # 反例：chart 值与 yml 默认不一致
        open(os.path.join(charts, "bad", "values.yaml"), "w", encoding="utf-8").write(
            "env:\n"
            "  GOVERNANCE_QUALITY_SOURCE_ENABLED: \"false\"\n"
        )
        # 正例：chart 有 yml 未声明的 key（不阻断，仅记录）
        open(os.path.join(charts, "undeclared", "values.yaml"), "w", encoding="utf-8").write(
            "env:\n"
            "  CHART_ONLY_KEY: \"whatever\"\n"
        )

        global REPO, CHART_ROOT, APP_YML_GLOB
        saved = (REPO, CHART_ROOT, APP_YML_GLOB)
        REPO = td
        CHART_ROOT = charts
        APP_YML_GLOB = os.path.join(td, "platform", "*", "src", "main", "resources", "application.yml")
        try:
            findings = scan()
        finally:
            REPO, CHART_ROOT, APP_YML_GLOB = saved

        text = "\n".join(findings)
        checks = [
            ("一致不误报", "good\\values.yaml" not in text and "good/values.yaml" not in text),
            ("不一致命中", "bad\\values.yaml:2" in text or "bad/values.yaml:2" in text),
            ("未声明不阻断", "undeclared\\values.yaml" not in text and "undeclared/values.yaml" not in text),
        ]
        bad = [n for n, ok in checks if not ok]
        if bad:
            print("FAIL self-test: " + "; ".join(bad))
            for f in findings:
                print("  hit:", f)
            return 1
        print("OK: self-test 通过（一致无命中 / 不一致命中 / 未声明不阻断）")
        return 0


def main() -> int:
    if "--self-test" in sys.argv and self_test() != 0:
        return 1
    if not os.path.isdir(CHART_ROOT):
        print("!! 未找到 %s" % CHART_ROOT)
        return 1
    findings = scan()
    if findings:
        print("FAIL: Chart env 与 application.yml 默认值不一致命中 %d 处：" % len(findings))
        for f in findings:
            print("  " + f)
        print("修法：将 chart values.yaml 中的 env 值统一到 application.yml 默认值，或同步更新两边。")
        return 1
    n_charts = sum(
        1
        for d in os.listdir(CHART_ROOT)
        if os.path.isdir(os.path.join(CHART_ROOT, d)) and not d.startswith("_")
    )
    print(
        "OK: %d 个 Chart env 与 application.yml 默认值一致（无漂移）" % n_charts
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
