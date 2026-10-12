#!/usr/bin/env python3
"""Python 服务环境变量绑定一致性校验（env 名 ↔ Settings 绑定面）。

背景：2026-10-12 连出两批同型缺陷（PR #447 八服务 / #452 chunker）——
pydantic-settings 只把「字段名或别名」大写后拼 `env_prefix`，驼峰字段会落成
`CHUNKER_LOGLEVEL` 这类名字；而项目文档与部署配置写的是下划线规范名
（`CHUNKER_LOG_LEVEL`）。结果是**规范名静默不生效**、只有畸形拼写才绑得上，
且症状（配置回落默认值）极难从 CI 红里看出来。

本脚本把这类问题的两个可静态判定的面固化为门禁：

  A. **部署侧**（k3s manifests / 集成 compose）：带某服务前缀的 env 名，
     必须是该服务 Settings 能绑定到的名字（字段名大写 / validation_alias 候选）。
     命中即说明该 env 被**静默忽略**。

  B. **Settings 侧**：驼峰字段必须显式声明含前缀的 `validation_alias`
     （`AliasChoices` 或字符串），否则规范名绑不上。
     注意：本判定只看「是否有显式 alias」，不猜文档意图 —— 因此对
     `PREFIX_FOO_BAR` 这类拼写**不做**误报式推断。

退出码：0 = 干净；1 = 有命中。`--self-test` 先跑合成正反例再跑真判定（同其余门禁）。
"""

from __future__ import annotations

import glob
import os
import re
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 扫描面：Settings 定义（每个 Python 服务一个）
SETTINGS_GLOBS = [
    os.path.join(REPO, "platform", "*", "**", "settings.py"),
    os.path.join(REPO, "platform", "*", "config.py"),
]
# 扫描面：部署侧声明 env 的文件
DEPLOY_FILES = [
    os.path.join(REPO, "tests", "integration", "docker-compose.yml"),
]
DEPLOY_GLOBS = [
    os.path.join(REPO, "deploy", "k3s", "manifests", "*.yaml"),
]

PREFIX_RE = re.compile(r'env_prefix\s*=\s*"([A-Z0-9_]+)"')
ALIAS_CHOICES_RE = re.compile(r"validation_alias\s*=\s*AliasChoices\(([^)]*)\)")
ALIAS_STR_RE = re.compile(r'validation_alias\s*=\s*"([^"]+)"')
FIELD_RE = re.compile(r"^\s{4}([a-zA-Z_]\w*)\s*:", re.M)
# 部署侧：
#   k3s manifest:  "- name: VAR" / "name: VAR"
#   compose:       "-- VAR=...", "VAR=..."（列表式）与 "VAR: ..."（映射式）
DEPLOY_NAME_RES = [
    re.compile(r"^\s*-\s*name:\s*([A-Za-z0-9_]+)\s*$"),  # k8s env 列表项
    re.compile(r"^\s*-\s*([A-Z][A-Z0-9_]{2,})=[^=]*$"),  # compose 列表 "VAR=value"
    re.compile(r"^\s*([A-Z][A-Z0-9_]{2,}):\s"),  # compose 映射 "VAR: value"
]


def load_settings() -> list[tuple[str, str, set[str], list[tuple[str, str]]]]:
    """返回 [(service, prefix, bindable_names, camel_without_alias)]。"""
    out = []
    seen = set()
    files = []
    for pattern in SETTINGS_GLOBS:
        files.extend(sorted(glob.glob(pattern, recursive=True)))
    for path in files:
        if not os.path.isfile(path):
            continue
        # 排除测试/夹具文件：它们只是**引用**环境变量名，不是 Settings 定义
        # （误纳入会产生整屏误报，实测过一次）。只按目录段 tests/ 与 test_ 前缀排除，
        # 不要用宽泛子串匹配（临时目录名跑 self-test 时会被连坐）。
        norm = path.replace("\\", "/")
        if "/tests/" in norm or os.path.basename(path).startswith("test_"):
            continue
        text = open(path, encoding="utf-8", errors="replace").read()
        if "BaseSettings" not in text:
            continue
        m = PREFIX_RE.search(text)
        if not m:
            continue
        prefix = m.group(1)
        service = os.path.relpath(os.path.dirname(path), REPO)
        if (service, prefix) in seen:
            continue
        seen.add((service, prefix))

        alias_candidates: set[str] = set()
        for block in ALIAS_CHOICES_RE.findall(text):
            alias_candidates.update(q.upper() for q in re.findall(r'"([^"]+)"', block))
        alias_candidates.update(q.upper() for q in ALIAS_STR_RE.findall(text))

        fields = FIELD_RE.findall(text)
        bindable = set(alias_candidates)
        camel_without_alias: list[tuple[str, str]] = []
        for f in fields:
            bindable.add(prefix + f.upper())
            is_camel = bool(re.search(r"[a-z][A-Z]", f))
            if not is_camel:
                continue
            # 「已声明」判据：该字段有**任何以本前缀开头的 alias 候选**即可。
            # 不要要求 alias 列表包含「字段名大写」——显式 alias 一旦声明，
            # 字段名大写那条**本来就不再被绑定**（pydantic-settings 语义），
            # 用后者做判据会对每个正确声明 alias 的字段误报（实测踩过）。
            if any(cand.startswith(prefix) for cand in alias_candidates):
                continue
            # 复杂字段（嵌套模型/列表/字典，多为 default_factory）不做此判定：
            # pydantic-settings 对其走嵌套解析（如 PREFIX__FIELD__SUB），与标量别名语义不同。
            decl = _field_declaration(text, f)
            if "default_factory" in decl or "Dict[" in decl or "List[" in decl or "dict[" in decl:
                continue
            camel_without_alias.append((f, prefix + f.upper()))
        out.append((service, prefix, bindable, camel_without_alias))
    return out


def _field_declaration(text: str, field: str) -> str:
    """抓取某个字段声明附近的一小段文本（用于判断是否复杂类型）。"""
    m = re.search(r"^\s{4}" + re.escape(field) + r"\s*:.*$", text, re.M)
    return m.group(0) if m else ""


def deploy_env_names() -> list[tuple[str, str]]:
    """返回 [(文件相对路径, env 名)]——部署侧声明的所有环境变量名。"""
    files = [p for p in DEPLOY_FILES if os.path.isfile(p)]
    for pattern in DEPLOY_GLOBS:
        files.extend(sorted(p for p in glob.glob(pattern) if os.path.isfile(p)))
    found = []
    for path in files:
        rel = os.path.relpath(path, REPO)
        for lineno, line in enumerate(open(path, encoding="utf-8", errors="replace"), 1):
            if line.lstrip().startswith("#"):
                continue
            for rx in DEPLOY_NAME_RES:
                m = rx.match(line)
                if m:
                    found.append((f"{rel}:{lineno}", m.group(1)))
                    break
    return found


def scan(root: str | None = None) -> list[str]:
    settings = load_settings()
    findings: list[str] = []

    # B. Settings 侧：驼峰字段无显式 alias
    for service, prefix, _bindable, camel in settings:
        for field, wrong in camel:
            findings.append(
                f"{service}: 驼峰字段 `{field}` 未声明含前缀的 validation_alias —— "
                f"规范名 {prefix}{_snake(field)} 绑不上（当前只会认 {wrong}）"
            )

    # A. 部署侧：带前缀的 env 名必须是该服务的 bindable 之一
    by_prefix = {prefix: (service, bindable) for (service, prefix, bindable, _camel) in settings}
    for where, name in deploy_env_names():
        for prefix, (svc, bindable) in by_prefix.items():
            if not name.startswith(prefix):
                continue
            if name in bindable:
                continue
            sample = ", ".join(sorted(bindable)[:6])
            findings.append(
                f"{where}: {name} 与该服务 Settings 的绑定面不匹配" f"（{svc} 只认：{sample}…）——该 env 会被静默忽略"
            )
            break
    return findings


def _snake(name: str) -> str:
    out = []
    for i, ch in enumerate(name):
        if ch.isupper() and i and not name[i - 1].isupper():
            out.append("_")
        out.append(ch.upper())
    return "".join(out)


# ---------------------------------------------------------------------------
# 自检：合成正反例
# ---------------------------------------------------------------------------
GOOD_SETTINGS = """
from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DEMO_", case_sensitive=False)

    host: str = Field(default="0.0.0.0")
    logLevel: str = Field(
        default="info",
        validation_alias=AliasChoices("DEMO_LOG_LEVEL", "DEMO_LOGLEVEL", "logLevel"),
    )
"""

BAD_SETTINGS = """
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DEMO_", case_sensitive=False)

    logLevel: str = Field(default="info")
"""

GOOD_DEPLOY = """
        env:
        - name: DEMO_LOG_LEVEL
          value: debug
        - name: DEMO_HOST
          value: 0.0.0.0
"""

BAD_DEPLOY = """
        env:
        - name: DEMO_LOGLEVEL
          value: debug
"""

# 反例 2 用：部署写「既非别名候选、也非字段名拼法」的第三种变体（纯发明拼写）
# ——注意：**无下划线拼写（DEMO_LOGLEVEL）不算缺陷**，因为 pydantic-settings 始终保留
# 「字段名大写」这条路（logLevel → DEMO_LOGLEVEL），实测确认过；真正的静默忽略是
# 第三种拼法（如 DEMO_LOG_LEVEL2 / DEMO_LOG_LEVL）既不在别名里、也不等于字段名拼法。
CANONICAL_ONLY_SETTINGS = """
from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DEMO_", case_sensitive=False)

    host: str = Field(default="0.0.0.0")
    logLevel: str = Field(
        default="info",
        validation_alias=AliasChoices("DEMO_LOG_LEVEL", "logLevel"),
    )
"""

TYPO_DEPLOY = """
        env:
        - name: DEMO_LOG_LEVL
          value: debug
"""


def self_test() -> int:
    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        svc_dir = os.path.join(tmp, "platform", "demo")
        os.makedirs(svc_dir)
        deploy_dir = os.path.join(tmp, "deploy", "k3s", "manifests")
        os.makedirs(deploy_dir)
        compose_dir = os.path.join(tmp, "tests", "integration")
        os.makedirs(compose_dir)

        def run(settings_text: str, deploy_text: str) -> list[str]:
            open(os.path.join(svc_dir, "settings.py"), "w", encoding="utf-8").write(settings_text)
            open(os.path.join(deploy_dir, "demo.yaml"), "w", encoding="utf-8").write(deploy_text)
            open(os.path.join(compose_dir, "docker-compose.yml"), "w", encoding="utf-8").write("services: {}\n")
            global REPO, SETTINGS_GLOBS, DEPLOY_FILES, DEPLOY_GLOBS
            saved = (REPO, SETTINGS_GLOBS, DEPLOY_FILES, DEPLOY_GLOBS)
            REPO = tmp
            SETTINGS_GLOBS = [os.path.join(tmp, "platform", "*", "**", "settings.py")]
            DEPLOY_FILES = [os.path.join(tmp, "tests", "integration", "docker-compose.yml")]
            DEPLOY_GLOBS = [os.path.join(tmp, "deploy", "k3s", "manifests", "*.yaml")]
            try:
                return scan()
            finally:
                REPO, SETTINGS_GLOBS, DEPLOY_FILES, DEPLOY_GLOBS = saved

        # 正例：显式 alias + 规范名部署 ⇒ 无命中
        got = run(GOOD_SETTINGS, GOOD_DEPLOY)
        if got:
            failures.append("正例（显式 alias + 规范名）不应有命中，却得到：%s" % got)
        # 反例 1：驼峰字段无 alias ⇒ B 命中
        got = run(BAD_SETTINGS, GOOD_DEPLOY)
        if not any("未声明含前缀的 validation_alias" in f for f in got):
            failures.append("反例 1（驼峰字段无 alias）应命中 Settings 侧，却得到：%s" % got)
        # 反例 2：部署写了「既非别名也非字段名拼法」的第三种拼写 ⇒ A 命中
        got = run(CANONICAL_ONLY_SETTINGS, TYPO_DEPLOY)
        if not any("绑定面不匹配" in f for f in got):
            failures.append("反例 2（部署写第三种拼写）应命中部署侧，却得到：%s" % got)

    if failures:
        print("FAIL: self-test 未通过")
        for f in failures:
            print("  " + f)
        return 1
    print("OK: self-test 通过（正例无命中 / 反例两条各命中预期面）")
    return 0


def main() -> int:
    if "--self-test" in sys.argv and self_test() != 0:
        return 1
    findings = scan()
    if findings:
        print("FAIL: Python env 绑定一致性命中 %d 处：" % len(findings))
        for f in findings:
            print("  " + f)
        print('\n修法：给驼峰字段加 validation_alias=AliasChoices("<PREFIX>_<SNAKE>", "<历史名>", "<字段名>")；')
        print("      并把部署侧写法统一到 <PREFIX>_<SNAKE>。参见 PR #447 / #452。")
        return 1
    print("OK: Python env 绑定一致性校验通过（部署侧 env 名均在绑定面内、驼峰字段均有显式 alias）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
