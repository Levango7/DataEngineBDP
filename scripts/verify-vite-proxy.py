#!/usr/bin/env python3
"""
Sprint 2.2 L3：vite dev 代理完整性静态校验。

背景（Sprint 2.2 P0 教训）：vite.config.ts 的 proxy 块曾在重建时丢失 `proxy: {`
开括号，导致全部 dev 代理静默失效；另发现 12 个前端调用前缀
（encaps-tenant/encaps-data/encaps-gateway 域）无代理条目——本地 dev 时这些
请求落到 `/api` 兜底（encaps-layer :8080）→ 404 或错误后端，且无任何编译期报警。

校验规则（复用 gen-api-contract.py 的扫描器，保证与契约文档同一真相源）：
  1. proxy 语法护栏：vite.config.ts 必须含 `proxy: {`；`/api` 兜底条目必须存在
     且位于所有细粒度代理之后（防 P0 复发）；
  2. 代理完整性：模拟 vite 最长前缀匹配。对每个前端调用重建完整请求路径
     （baseURL 三形态：默认 /api/v1、engine.ts MV_CONFIG /api、lineage.ts '' 全路径），
     找最长匹配代理键：
       - 无匹配键 → 缺口（dev server 自己 404）；
       - 匹配到 /api 兜底 → 仅当该段后端归属含 encaps-layer 才合法
         （兜底指向 encaps-layer:8080；其余独立进程必须显式分流）；
       - 匹配到显式键 → 覆盖。

退出码：0 = 通过；1 = 存在缺口；2 = 环境错误。
用法：python scripts/verify-vite-proxy.py
"""
from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VITE = ROOT / "frontend" / "vite.config.ts"
CONTRACT = ROOT / "scripts" / "gen-api-contract.py"

# 按路径加载 gen-api-contract.py（文件名含连字符，无法常规 import）
_spec = importlib.util.spec_from_file_location("gen_api_contract", CONTRACT)
if _spec is None or _spec.loader is None:
    print(f"::error::无法加载 {CONTRACT}", file=sys.stderr)
    sys.exit(2)
gen_api_contract = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gen_api_contract)

# vite proxy 条目键提取：'/api/v1/xxx': { ... }（TS 源码正则，非 JSON 解析）
PROXY_KEY_RE = re.compile(r"^\s*'([^']+)':\s*\{", re.MULTILINE)

FALLBACK = "/api"
DEFAULT_BASE = "/api/v1"  # client.ts 默认 baseURL


def longest_proxy_key(path: str, keys: list[str]) -> str | None:
    """模拟 vite 最长前缀匹配：返回能作为 path 前缀的最长代理键。"""
    best: str | None = None
    for k in keys:
        if path == k or path.startswith(k.rstrip("/") + "/") or path == k.rstrip("/"):
            if best is None or len(k) > len(best):
                best = k
    return best


def full_path_for(raw: str, file_text: str) -> str:
    """按调用文件重建完整请求路径（三种 baseURL 形态）。"""
    # lineage.ts 模式：显式 baseURL: ''（全路径自带 /lineage 前缀）
    if ("baseURL: ''" in file_text or 'baseURL: ""' in file_text) and raw.startswith("/lineage"):
        return raw
    # engine.ts MV_CONFIG 模式：baseURL '/api'（物化视图无 /v1）
    if raw.startswith("/materialized-views"):
        return "/api" + raw
    # 默认 client.ts baseURL /api/v1
    return DEFAULT_BASE + raw


def main() -> int:
    if not VITE.is_file():
        print(f"::error::{VITE} 不存在", file=sys.stderr)
        return 2
    text = VITE.read_text(encoding="utf-8", errors="ignore")

    # ---------- 规则 1：proxy 语法护栏 ----------
    syntax_errors: list[str] = []
    if "proxy: {" not in text:
        syntax_errors.append("缺失 `proxy: {` 开括号（Sprint 2.2 P0 同类事故：全部代理静默失效）")

    keys = [m.group(1) for m in PROXY_KEY_RE.finditer(text)]
    if FALLBACK not in keys:
        syntax_errors.append(f"缺失 `{FALLBACK}` 兜底代理条目（未匹配的 /api 请求将直连 404）")
    elif keys and FALLBACK != keys[-1]:
        syntax_errors.append(
            f"`{FALLBACK}` 兜底代理必须位于所有细粒度代理之后（当前在第 {keys.index(FALLBACK) + 1} 位，"
            f"共 {len(keys)} 条）——放前面会吞掉细粒度条目"
        )

    # ---------- 规则 2：代理完整性（模拟 vite 最长前缀匹配） ----------
    java = gen_api_contract.collect_java_prefixes()
    python = gen_api_contract.collect_python_prefixes()
    go = gen_api_contract.collect_go_prefixes()
    prefixes: dict[str, list[str]] = {}
    for src in (java, python, go):
        for k, v in src.items():
            merged = prefixes.setdefault(k, [])
            for mod in v:
                if mod not in merged:
                    merged.append(mod)

    gaps: list[tuple[str, list[str], str]] = []  # (完整路径样例, 后端归属, 原因)
    seen_paths: set[str] = set()
    for ts in sorted((ROOT / "frontend" / "src" / "api").glob("*.ts")):
        if ts.name in ("client.ts", "types.ts"):
            continue
        file_text = ts.read_text(encoding="utf-8", errors="ignore")
        stripped = gen_api_contract.strip_line_comments(file_text)
        consts = {m.group(1): m.group(2) for m in gen_api_contract.CONST_RE.finditer(stripped)}
        for raw in gen_api_contract.resolve_calls(stripped, consts):
            full = full_path_for(raw, file_text)
            if full in seen_paths or not full.startswith("/"):
                continue
            seen_paths.add(full)
            seg = gen_api_contract.first_seg(raw)
            mods = set(gen_api_contract.match_backend(seg, prefixes))
            if not mods:
                continue  # 契约未匹配项由 gen-api-contract.py --check 负责
            key = longest_proxy_key(full, keys)
            if key is None:
                gaps.append((full, sorted(mods), "无任何代理键匹配——dev server 自身 404"))
            elif key == FALLBACK and "encaps-layer" not in mods:
                gaps.append((
                    full, sorted(mods),
                    "落到 /api 兜底（encaps-layer :8080）但后端归属不含 encaps-layer → 错误后端 404",
                ))

    # ---------- 报告 ----------
    for err in syntax_errors:
        print(f"::error::vite.config.ts 语法护栏：{err}")
    for full, mods, reason in gaps:
        print(f"::error::{full}")
        print(f"    后端归属 {mods}；{reason}")
        print(f"    -> 请在 server.proxy 增加对应前缀条目")

    if syntax_errors or gaps:
        print(f"\n::error::共 {len(syntax_errors)} 处语法护栏问题，{len(gaps)} 处代理缺口")
        return 1

    print(f"✓ vite 代理校验通过：{len(keys)} 条代理条目，语法护栏完好，"
          f"{len(seen_paths)} 个前端调用路径全部正确分流")
    return 0


if __name__ == "__main__":
    sys.exit(main())
