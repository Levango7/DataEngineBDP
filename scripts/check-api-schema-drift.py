#!/usr/bin/env python3
"""
Sprint A1：OpenAPI ↔ 前端 TS 类型字段级漂移校验。

路由级契约由 gen-api-contract.py 负责（前端调用路径 ↔ 后端路由是否存在）；
本脚本补上**字段级**契约：后端 springdoc 产出的 OpenAPI schema 字段名 vs
前端手写 TS interface 字段名，两边同名的 schema/interface 若字段集不一致
（一方有一方没有）即视为漂移，CI 阻断。

匹配策略（同名匹配，无人工映射表）：
  1. OpenAPI components.schemas 中名为 XxxEntity 的 schema，去掉 Entity 后缀
     与前端 export interface Xxx 对比；
  2. OpenAPI 直接叫 Xxx 的 schema 与前端 Xxx 对比；
  3. 前端类型有嵌套（User extends Identifiable）时展开父接口字段；
  4. 仅报告"同类型名都存在但字段有差异"的漂移——前端独有的类型（对应
     Go/Python 后端）与后端独有的 schema（内部实体不透出）不在此校验范围。

用法：
  python scripts/check-api-schema-drift.py [openapi.json] [--base-url URL]
    openapi.json     已下载的 OpenAPI 文档；缺省时从 --base-url 拉取
    --base-url       encaps-layer 基地址，默认 http://127.0.0.1:18080

退出码：0=无漂移 / 1=有漂移或拉取失败
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TYPES_TS = ROOT / "frontend" / "src" / "api" / "types.ts"
SNAPSHOT = ROOT / "docs" / "api-contract-openapi.json"

# ------------------------- 前端 TS interface 解析 -------------------------

IFACE_HEAD_RE = re.compile(
    r"export\s+interface\s+(\w+)(?:\s+extends\s+([\w,\s]+?))?\s*\{",
)
FIELD_RE = re.compile(r"^\s*(?:/\*\*.*?\*/\s*)?(\w+)\??:", re.DOTALL)


def _extract_block(text: str, start: int) -> tuple[str, int]:
    """从 start（指向 '{'）提取花括号配对的完整块，返回 (body, 右括号后位置)。"""
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1 : i], i + 1
    raise ValueError(f"花括号不配对 @ {start}")


def parse_ts_interfaces(text: str) -> dict[str, set[str]]:
    """解析 types.ts 中全部 interface：类型名 → 字段集合（含 extends 展开）。"""
    interfaces: dict[str, set[str]] = {}
    pending_extends: dict[str, list[str]] = {}

    pos = 0
    while True:
        m = IFACE_HEAD_RE.search(text, pos)
        if not m:
            break
        name = m.group(1)
        extends = [e.strip() for e in (m.group(2) or "").split(",") if e.strip()]
        try:
            body, pos = _extract_block(text, m.end() - 1)
        except ValueError:
            break
        # 剥离块内注释与字符串字面量后再抓字段（避免嵌套对象字段名误抓为顶层）
        cleaned = re.sub(r"/\*.*?\*/", "", body, flags=re.DOTALL)
        cleaned = re.sub(r"//.*", "", cleaned)
        cleaned = re.sub(r"'[^']*'|\"[^\"]*\"|`[^`]*`", "''", cleaned)
        # 顶层字段：行首缩进 2 空格、无更深缩进（嵌套对象首行就是 4 空格）
        fields = set()
        for line in cleaned.splitlines():
            fm = re.match(r"^  (\w+)\??:", line)
            if fm:
                fields.add(fm.group(1))
        interfaces[name] = fields
        if extends:
            pending_extends[name] = extends

    # 展开 extends（不动点迭代）
    for _ in range(5):
        changed = False
        for name, parents in pending_extends.items():
            merged = set(interfaces[name])
            for p in parents:
                if p in interfaces:
                    before = len(merged)
                    merged |= interfaces[p]
                    if len(merged) != before:
                        changed = True
            interfaces[name] = merged
        if not changed:
            break
    return interfaces


# ------------------------- OpenAPI schema 解析 -------------------------

def openapi_schema_fields(schema: dict) -> set[str] | None:
    """提取 OpenAPI schema 的字段集；array/无 properties 返回 None（不比对）。"""
    if not isinstance(schema, dict):
        return None
    if schema.get("type") == "array":
        return None
    props = schema.get("properties")
    if not isinstance(props, dict) or not props:
        return None
    return set(props.keys())


# ------------------------- 漂移对比 -------------------------

# 同名不同义的已知冲突对，显式豁免（改名字段级语义不符，无修复价值）。
# 命名约定：新增豁免必须写明来源，避免豁免清单变成"掩盖真漂移"的后门。
KNOWN_NAME_CLASHES: dict[str, str] = {
    # 后端 encaps-layer Tenant = K8s namespace 管理域 JPA Entity（id:Long, namespace,
    # quotaProfile）；前端 Tenant = 租户管理页业务实体（code/plan/contact...）。
    # 两者同名不同义，服务不同 API（/api/tenants vs K8s 内部管理），不可比对。
    "Tenant": "backend=K8s namespace entity; frontend=tenant mgmt page entity",
}


def collect_drift(openapi: dict, ts_interfaces: dict[str, set[str]]) -> list[str]:
    schemas = openapi.get("components", {}).get("schemas", {})
    drift: list[str] = []

    # schema 名 → 前端候选 interface 名（去 Entity 后缀 + 原名）
    pairs: dict[str, str] = {}
    for schema_name in schemas:
        cand = schema_name.removesuffix("Entity")
        if cand in ts_interfaces and cand != schema_name:
            pairs[schema_name] = cand
        elif schema_name in ts_interfaces:
            pairs[schema_name] = schema_name

    for schema_name, iface_name in sorted(pairs.items()):
        if schema_name in KNOWN_NAME_CLASHES or iface_name in KNOWN_NAME_CLASHES:
            continue
        api_fields = openapi_schema_fields(schemas[schema_name])
        if api_fields is None:
            continue
        ts_fields = ts_interfaces[iface_name]

        # snake_case 后端字段映射到 camelCase 前端（如 last_login_at → lastLoginAt）
        api_camel = {to_camel(f) for f in api_fields}

        missing_in_ts = api_camel - ts_fields
        missing_in_api = {f for f in ts_fields if to_snake(f) not in api_fields and f not in api_fields}

        if missing_in_ts or missing_in_api:
            drift.append(
                f"schema '{schema_name}' ↔ interface '{iface_name}' 字段漂移:\n"
                f"    后端有前端无: {sorted(missing_in_ts) or '—'}\n"
                f"    前端有后端无: {sorted(missing_in_api) or '—'}"
            )
    return drift


def to_camel(s: str) -> str:
    parts = s.split("_")
    return parts[0] + "".join(p.title() for p in parts[1:])


def to_snake(s: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", s).lower()


# ------------------------- 主流程 -------------------------

def load_openapi(path: str | None, base_url: str, token: str | None) -> dict:
    if path:
        # utf-8-sig 兼容 PowerShell Out-File 写入的 BOM
        with open(path, encoding="utf-8-sig") as f:
            return json.load(f)
    # 无本地文件/快照：从运行中的服务拉取（需 Bearer token，本地用 local-auth 登录获取）
    url = base_url.rstrip("/") + "/v3/api-docs"
    print(f"[check-api-schema-drift] 拉取 OpenAPI: {url}")
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.load(resp)


def main() -> int:
    parser = argparse.ArgumentParser(description="OpenAPI ↔ TS 类型字段漂移校验")
    parser.add_argument("openapi", nargs="?", help="本地 openapi.json 路径（缺省用 docs/api-contract-openapi.json 快照）")
    parser.add_argument("--base-url", default="http://127.0.0.1:18080",
                        help="服务运行中时直接从 --base-url 拉取最新 OpenAPI（本地手动刷新用）")
    parser.add_argument("--token", default=None,
                        help="拉取 OpenAPI 的 Bearer token（服务以生产安全模式运行时必需；"
                             "本地 local-auth 模式可先 POST /api/v1/auth/login 获取）")
    parser.add_argument("--refresh", action="store_true",
                        help="从 --base-url 拉取并回写快照到 docs/api-contract-openapi.json")
    args = parser.parse_args()

    if not TYPES_TS.exists():
        print(f"[check-api-schema-drift] 找不到 {TYPES_TS}", file=sys.stderr)
        return 1

    source = args.openapi or (str(SNAPSHOT) if SNAPSHOT.exists() else None)
    try:
        openapi = load_openapi(source, args.base_url, args.token)
    except Exception as e:  # noqa: BLE001
        print(f"[check-api-schema-drift] OpenAPI 拉取/解析失败: {e}", file=sys.stderr)
        return 1

    if args.refresh:
        SNAPSHOT.write_text(json.dumps(openapi, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")
        print(f"[check-api-schema-drift] 快照已回写: {SNAPSHOT}")

    ts_interfaces = parse_ts_interfaces(TYPES_TS.read_text(encoding="utf-8"))
    drift = collect_drift(openapi, ts_interfaces)

    compared = len(
        {s.removesuffix("Entity") for s in openapi.get("components", {}).get("schemas", {})
         if s.removesuffix("Entity") in ts_interfaces or s in ts_interfaces}
    )
    print(f"[check-api-schema-drift] 比对 {compared} 个同名类型，{len(ts_interfaces)} 个前端 interface")

    if drift:
        print(f"\n[check-api-schema-drift] 发现 {len(drift)} 处字段漂移：\n")
        for d in drift:
            print(f"  - {d}\n")
        return 1
    print("[check-api-schema-drift] ✓ 无字段漂移")
    return 0


if __name__ == "__main__":
    sys.exit(main())
