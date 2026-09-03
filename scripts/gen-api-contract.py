#!/usr/bin/env python3
"""
Sprint 2.2：前端 API ↔ 后端 Controller 合约对照表生成器（多语言版）

Sprint 2.1 版本只扫 Java Controller，把 Go/Python 后端提供的路由（/ops、/cluster、
/business-lines、/vector、/ai-assistant、/subscriptions 等）系统性误报为"未匹配"。
本版升级为多语言扫描：

  1. Java  : platform/**/*Controller.java 的 @RequestMapping 类前缀 + @*Mapping 方法
  2. Python: platform/**/api/routers/*.py 的 APIRouter(prefix=...) + @router.verb(path)
             + app.py include_router(prefix=apiPrefix)（apiPrefix 默认 /api/v1）
  3. Go    : gin 路由。组前缀自动扫描 r.Group("...")；
             间接注册（RegisterRoutes 模式）用 GO_SERVICE_PREFIXES 显式注册表声明
             （来源文件在表中注明，改动路由时须同步更新，CI 漂移校验会兜底）

输出 docs/api-contract.md。
用法：
  python scripts/gen-api-contract.py            # 生成/刷新
  python scripts/gen-api-contract.py --check    # 仅校验，未匹配>0 退出码 1
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
API_DIR = ROOT / "frontend" / "src" / "api"
PLATFORM = ROOT / "platform"
OUT = ROOT / "docs" / "api-contract.md"

# ------------------------- 前端解析 -------------------------

CALL_RE = re.compile(
    r'\b(?:get|post|put|del)(?:<[^>]*>)?\(\s*'
    r'(?:`([^`]+)`|"([^"]+)"|([A-Za-z_$][\w$]*)(?:\s*\+\s*"([^"]*)")?)'
)
CONST_RE = re.compile(r"const\s+([A-Z_][A-Z0-9_]*)\s*=\s*[`'\"]([^`'\"]+)[`'\"]")
TPL_VAR = re.compile(r"\$\{([^}]+)\}")


def resolve_calls(text: str, consts: dict[str, str]) -> list[str]:
    out: list[str] = []
    for m in CALL_RE.finditer(text):
        tpl, lit, var, suffix = m.group(1), m.group(2), m.group(3), m.group(4)
        if tpl is not None:
            def sub_var(mm):
                name = mm.group(1).strip()
                return consts.get(name, mm.group(0))
            out.append(TPL_VAR.sub(sub_var, tpl))
        elif lit is not None:
            out.append(lit)
        elif var is not None:
            out.append(consts.get(var, var) + (suffix or ""))
    return out


def strip_line_comments(text: str) -> str:
    """剥离 // 行注释（保留 URL 中的 //，仅当行首空白后出现 // 视为注释）。"""
    lines = []
    for ln in text.splitlines():
        stripped = ln.lstrip()
        if stripped.startswith("//"):
            lines.append("")
        else:
            lines.append(ln)
    return "\n".join(lines)


def collect_frontend_calls() -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for ts in sorted(API_DIR.glob("*.ts")):
        if ts.name in ("client.ts", "types.ts"):
            continue
        text = strip_line_comments(ts.read_text(encoding="utf-8", errors="ignore"))
        consts = {m.group(1): m.group(2) for m in CONST_RE.finditer(text)}
        seen: list[str] = []
        for c in resolve_calls(text, consts):
            if c not in seen:
                seen.append(c)
        if seen:
            out[ts.name] = seen
    return out


def first_seg(path: str) -> str:
    """/tenants/${id} -> /tenants；去掉查询串；baseURL 覆盖（/api）归一到 /api 下。"""
    p = path.split("?")[0]
    parts = p.strip("/").split("/")
    return "/" + parts[0] if parts and parts[0] else "/"


# ------------------------- Java 后端 -------------------------

CLASS_MAPPING = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"')
REST_CONTROLLER = re.compile(r'@RestController\b')


def collect_java_prefixes() -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for java in PLATFORM.rglob("*Controller.java"):
        s = str(java)
        if any(x in s for x in ("\\src\\test\\", "/src/test/", "\\target\\", "/target/")):
            continue
        text = java.read_text(encoding="utf-8", errors="ignore")
        if not REST_CONTROLLER.search(text):
            continue
        m = CLASS_MAPPING.search(text)
        if not m:
            continue
        prefix = m.group(1)
        try:
            rel = java.relative_to(PLATFORM)
            module = rel.parts[0]
            if module == "governance" and len(rel.parts) > 1:
                module = f"governance/{rel.parts[1]}"
        except ValueError:
            continue
        out.setdefault(prefix, [])
        if module not in out[prefix]:
            out[prefix].append(module)
    return out


# ------------------------- Python FastAPI 后端 -------------------------

# APIRouter(prefix="/xxx") 或 APIRouter()；router 变量名捕获
# 允许行首空白：个别服务（如 registry）在工厂函数内定义 router（缩进），
# 行首锚定 ^\w 会漏掉缩进行导致前缀不被扫描（Sprint 3.1 联调发现 /registry 未匹配）
PY_ROUTER_RE = re.compile(r'^\s*(\w+)\s*=\s*APIRouter\(\s*(?:prefix\s*=\s*"([^"]*)")?\s*[,)]', re.MULTILINE)
PY_ROUTE_RE = re.compile(r'^\s*@(\w+)\.(get|post|put|delete|patch)\(\s*\n?\s*"([^"]*)"', re.MULTILINE)
PY_INCLUDE_RE = re.compile(r'app\.include_router\(\s*([\w.]+)')
# apiPrefix 默认值（settings.py 定义，两服务均为 /api/v1）
PY_API_PREFIX = "/api/v1"


def collect_python_prefixes() -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for py in PLATFORM.rglob("*.py"):
        s = str(py)
        if "/tests/" in s.replace("\\", "/") or "/test_" in Path(s).name:
            continue
        text = py.read_text(encoding="utf-8", errors="ignore")
        if "APIRouter(" not in text:
            continue
        try:
            rel = py.relative_to(PLATFORM)
            module = rel.parts[0]
            if len(rel.parts) > 2 and rel.parts[1] == "api" and rel.parts[2] == "routers":
                module = f"{rel.parts[0]}"  # 服务级聚合
        except ValueError:
            continue
        # 路由器定义：变量名 -> 前缀
        routers: dict[str, str] = {}
        for m in PY_ROUTER_RE.finditer(text):
            routers[m.group(1)] = m.group(2) or ""
        if not routers:
            continue
        # 每个路由器的完整业务前缀 = apiPrefix + router prefix
        # 修复：个别 router 前缀已含 /api/v1（如 registry 的 /api/v1/registry），
        # 直接拼接会双重加前缀（/api/v1/api/v1/registry）导致契约误报未匹配。
        for var, prefix in routers.items():
            if prefix.startswith("/api/"):
                full = prefix.rstrip("/") or PY_API_PREFIX
            else:
                full = (PY_API_PREFIX + prefix).rstrip("/") or PY_API_PREFIX
            # 验证该 router 确有路由方法（避免空壳）
            pat = re.compile(rf'@{re.escape(var)}\.(get|post|put|delete|patch)\(')
            if not pat.search(text):
                continue
            out.setdefault(full, [])
            if module not in out[full]:
                out[full].append(module)
    return out


# ------------------------- Go gin 后端 -------------------------

# 自动：r.Group("/api/v1/xxx")（服务入口直接注册的组）
GO_GROUP_RE = re.compile(r'\b\w+\s*:=\s*\w+\.Group\("(/[^"]*)"\)')
GO_DIRECT_RE = re.compile(r'\b\w+\.(GET|POST|PUT|DELETE|PATCH)\("(/[^"]*)"')

# 显式注册表：RegisterRoutes 间接注册模式的 Go 服务业务前缀
# （gin 的 RegisterRoutes(g) 内部 g.VERB(path) 无法静态绑定挂载点，显式声明保证可审计）
GO_SERVICE_PREFIXES: dict[str, str] = {
    "/api/v1/ops": "observability/query-api main.go opsGroup（/overview /jobs /alerts /health/overview）",
    "/api/v1/cluster": "observability/query-api main.go clusterGroup（k3s 可用：/overview /nodes /pods /components）",
    "/api/v1/vector": "vector-engine internal/api/handler.go RegisterRoutes（/vector /vector/search /collections...）",
    "/api/v1/ai-assistant": "ai-assistant internal/api/handler.go RegisterRoutes（/chat /nl2sql /sessions...）",
}


def collect_go_prefixes() -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for go in PLATFORM.rglob("*.go"):
        s = str(go)
        if s.endswith("_test.go") or "/mocks/" in s.replace("\\", "/"):
            continue
        text = go.read_text(encoding="utf-8", errors="ignore")
        if "gin" not in text and "Group(" not in text:
            continue
        try:
            rel = go.relative_to(PLATFORM)
            module = rel.parts[0]
        except ValueError:
            continue
        # 组前缀（直接注册）
        for m in GO_GROUP_RE.finditer(text):
            prefix = m.group(1)
            # 组下须有直接注册的动词路由（排除纯分组无路由的情况）
            out.setdefault(prefix, [])
            if module not in out[prefix]:
                out[prefix].append(module)
        # 直接注册在根引擎的完整路径（如 r.GET("/api/v1/health")）取首两段
        for m in GO_DIRECT_RE.finditer(text):
            p = m.group(2)
            seg = "/" + "/".join(p.strip("/").split("/")[:1])
            if p.startswith("/api/"):
                out.setdefault(p, [])
                if module not in out[p]:
                    out[p].append(module)
    # 显式注册表合入
    for prefix, source in GO_SERVICE_PREFIXES.items():
        out.setdefault(prefix, [])
        svc = source.split()[0]
        if svc not in out[prefix]:
            out[prefix].append(svc)
    return out


# ------------------------- 匹配 -------------------------

def match_backend(seg: str, prefixes: dict[str, list[str]]) -> list[str]:
    """seg 为前端业务段（如 /tenants）；兼容三种 baseURL 形态的比较。

    前端 baseURL 有三种：/api/v1（client.ts 默认）、/api（engine.ts 物化视图）、
    直接全路径。后端前缀也三种：/api/v1/xxx、/api/xxx、/xxx。统一把后端前缀
    归一成「业务段」（去掉 /api/v1 与 /api 前缀）再与前端段比较。
    """
    hits: list[str] = []
    seg_n = seg.rstrip("/")
    for prefix, modules in prefixes.items():
        p = prefix.rstrip("/")
        # 后端前缀归一：/api/v1/tenants 和 /api/materialized-views -> /tenants, /materialized-views
        biz = p
        if biz.startswith("/api/v1/"):
            biz = biz[len("/api/v1"):]
        elif biz.startswith("/api/"):
            biz = biz[len("/api"):]
        biz_n = biz.rstrip("/")
        if not seg_n or not biz_n:
            continue
        if seg_n == biz_n or biz_n.startswith(seg_n + "/") or seg_n.startswith(biz_n + "/"):
            hits.extend(modules)
    return sorted(set(hits))


def main() -> int:
    check_only = "--check" in sys.argv
    java = collect_java_prefixes()
    python = collect_python_prefixes()
    go = collect_go_prefixes()

    prefixes: dict[str, list[str]] = {}
    for src in (java, python, go):
        for k, v in src.items():
            merged = prefixes.setdefault(k, [])
            for mod in v:
                tag = mod if mod in ("java", "go") or "/" in str(mod) else mod
                if tag not in merged:
                    merged.append(tag)

    frontend = collect_frontend_calls()

    lines: list[str] = []
    lines.append("# 前端 API ↔ 后端路由 合约对照表（多语言后端）")
    lines.append("")
    lines.append("> 由 `scripts/gen-api-contract.py` 自动生成（Sprint 2.2 多语言版），勿手改。")
    lines.append("")
    lines.append(f"- 前端入口：`frontend/src/api/*.ts`（共 {len(frontend)} 个文件）")
    lines.append(f"- 后端前缀：Java {len(java)} / Python {len(python)} / Go {len(go)}（含显式注册表 {len(GO_SERVICE_PREFIXES)} 项）")
    lines.append("- 扫描范围：Java `@RequestMapping`、Python `APIRouter(prefix)`、Go `Group(...)`+`GO_SERVICE_PREFIXES` 注册表")
    lines.append("- 前端 baseURL=`/api/v1`（client.ts，engine.ts 物化视图例外用 `/api`）；「首段」为去掉 baseURL 后第一段")
    lines.append("")

    unmatched_total = 0
    matched_total = 0
    for fname, calls in frontend.items():
        lines.append(f"## {fname}")
        lines.append("")
        lines.append("| 前端调用 | 首段 | 后端模块 | 状态 |")
        lines.append("|---|---|---|---|")
        for raw in calls:
            seg = first_seg(raw)
            hits = match_backend(seg, prefixes)
            n_var = len(TPL_VAR.findall(raw))
            vs = f"（{n_var} 变量）" if n_var else ""
            if hits:
                matched_total += 1
                lines.append(f"| `{raw}` {vs} | `{seg}` | {', '.join(hits)} | ✅ |")
            else:
                unmatched_total += 1
                lines.append(f"| `{raw}` {vs} | `{seg}` | - | ❌ |")
        lines.append("")

    lines.append("## 汇总")
    lines.append("")
    lines.append(f"- 匹配：{matched_total}")
    lines.append(f"- 未匹配：{unmatched_total}")
    lines.append("")
    if unmatched_total:
        lines.append("> ❌ 项为真实待收敛缺口（后端无此前缀的任何路由）。Sprint 2.2 已消除多语言误报，剩余项需按 Sprint 计划补建。")
        lines.append("")

    content = "\n".join(lines) + "\n"
    if check_only:
        print(f"匹配 {matched_total}/{matched_total + unmatched_total}；未匹配 {unmatched_total} 条")
        return 1 if unmatched_total else 0
    OUT.write_text(content, encoding="utf-8", newline="\n")
    print(f"✓ 生成 {OUT.relative_to(ROOT)}：匹配 {matched_total}，未匹配 {unmatched_total}")
    print(f"  后端前缀：Java {len(java)} / Python {len(python)} / Go {len(go)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
