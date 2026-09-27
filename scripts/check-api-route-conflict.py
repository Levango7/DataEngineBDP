#!/usr/bin/env python3
"""
Sprint 2.1：API 路由冲突静态扫描器

扫描 platform/ 下所有 Java Controller，提取类级 @RequestMapping 与方法级 @*Mapping，
检测「同一 HTTP 动词 + 同一路径」被 2+ 个不同 Controller 注册的情况
（若这些模块被同一进程加载会直接启动失败/路由歧义）。

退出码：
  0 = 无冲突（含仅剩豁免项）
  1 = 发现冲突
  2 = 环境错误

豁免说明（KNOWN_CROSS_PROCESS_ROUTES）：
  - /api/v1/health：各独立微服务各自注册（不同进程/端口），属预期设计；
  - /api/v1/tenants 系列：encaps-layer(8080) 与 encaps-tenant(8081) 两个独立
    微服务注册同前缀，生产为跨进程部署（不同 JVM，无同 context 冲突）。
    冲突风险已由 encaps-layer 侧 @ConditionalOnProperty(app.tenant.controller.enabled)
    守卫防护（见 TenantControllerRouteTest），列入白名单仅报告不阻断。
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLATFORM = ROOT / "platform"

CLASS_MAPPING = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"')
CLASS_MAPPING_EMPTY = re.compile(r'@RequestMapping\s*\(\s*\)')
METHOD_MAPPING = re.compile(
    r'@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)"|\s*\(\s*\))?'
)
REST_CONTROLLER = re.compile(r'@RestController\b')

# 跨进程同前缀白名单：(verb, path) 精确匹配，仅报告不阻断。
KNOWN_CROSS_PROCESS_ROUTES: set[tuple[str, str]] = {
    # tenants：encaps-layer(8080) vs encaps-tenant(8081) 跨进程复用（Sprint 2.1）
    ("GET", "/api/v1/tenants"),
    ("GET", "/api/v1/tenants/all"),
    ("GET", "/api/v1/tenants/{id}"),
    ("POST", "/api/v1/tenants"),
    ("PUT", "/api/v1/tenants/{id}"),
    ("DELETE", "/api/v1/tenants/{id}"),
    # projects：encaps-layer stub(nightly-e2e) vs encaps-tenant 真实实现（跨进程；
    # stub 已加 @ConditionalOnProperty 守卫，encaps-tenant 侧关闭，Sprint 2.2 L4-0）
    ("GET", "/api/v1/projects"),
    ("GET", "/api/v1/projects/{id}"),
    ("GET", "/api/v1/projects/{id}/datasets"),
    ("GET", "/api/v1/projects/{id}/jobs"),
    ("GET", "/api/v1/projects/{id}/members"),
    ("POST", "/api/v1/projects"),
    ("PUT", "/api/v1/projects/{id}"),
    ("DELETE", "/api/v1/projects/{id}"),
    # search：encaps-layer stub(nightly-e2e) vs encaps-data(8083) 真实实现（跨进程；
    # stub 已加守卫；vite dev 代理 /api/v1/search 指向 encaps-data，前端不经过 stub）
    ("POST", "/api/v1/search"),
    ("POST", "/api/v1/search/export"),
    ("POST", "/api/v1/search/history/clear"),
    ("POST", "/api/v1/search/history/{id}/delete"),
    ("GET", "/api/v1/search/facets"),
    ("GET", "/api/v1/search/history"),
    ("GET", "/api/v1/search/suggest"),
}


def extract_routes(java: Path) -> list[tuple[str, str]]:
    """返回 [(verb, full_path), ...]；非 RestController 返回空。"""
    text = java.read_text(encoding="utf-8", errors="ignore")
    if not REST_CONTROLLER.search(text):
        return []
    m = CLASS_MAPPING.search(text)
    if m:
        prefix = m.group(1).rstrip("/")
    elif CLASS_MAPPING_EMPTY.search(text):
        prefix = ""
    else:
        prefix = None
    if prefix is None:
        return []

    out: list[tuple[str, str]] = []
    for mm in METHOD_MAPPING.finditer(text):
        verb = mm.group(1).upper()
        sub = mm.group(2) or ""
        full = (prefix + "/" + sub) if prefix else (sub or "/")
        full = re.sub(r"/{2,}", "/", full).rstrip("/") or "/"
        out.append((verb, full))
    return out


def main() -> int:
    if not PLATFORM.is_dir():
        print(f"::error::{PLATFORM} not found", file=sys.stderr)
        return 2

    # key = (verb, path) -> [controller file, ...]
    routes: dict[tuple[str, str], list[Path]] = defaultdict(list)
    for java in PLATFORM.rglob("*Controller.java"):
        s = str(java)
        if "\\src\\test\\" in s or "\\target\\" in s or "/src/test/" in s or "/target/" in s:
            continue
        for verb, full in extract_routes(java):
            routes[(verb, full)].append(java)

    conflicts: list[tuple[str, str, list[Path]]] = []
    waived: list[tuple[str, str, list[Path]]] = []
    health_services = 0
    for (verb, path), locs in sorted(routes.items()):
        # 去重同一 Controller 文件（同文件内多方法注册同一路径不算冲突）
        unique = sorted(set(locs))
        if path == "/api/v1/health":
            health_services = len(unique)
            continue
        if len(unique) > 1:
            if (verb, path) in KNOWN_CROSS_PROCESS_ROUTES:
                waived.append((verb, path, unique))
            else:
                conflicts.append((verb, path, unique))

    for verb, path, locs in waived:
        print(f"::warning::豁免（跨进程部署+守卫防护）: [{verb}] {path}")
        for j in locs:
            print(f"    -> {j.relative_to(ROOT)}")

    if conflicts:
        print(f"::error::发现 {len(conflicts)} 处 API 路由冲突（同一动词+路径被 2+ Controller 注册）：")
        for verb, path, locs in conflicts:
            print(f"\n  [{verb:6s}] {path}")
            for j in locs:
                print(f"    -> {j.relative_to(ROOT)}")
        print("\n若为跨进程独立微服务的已知复用，请将 (verb, path) 加入 "
              "KNOWN_CROSS_PROCESS_ROUTES 白名单并注明原因。")
        if health_services:
            print(f"（/api/v1/health 共 {health_services} 个独立微服务注册，多进程部署属预期，不计冲突）")
        return 1

    print(f"✓ API 路由扫描通过：{len(routes)} 个 (verb, path) 端点无未知冲突；"
          f"豁免 {len(waived)} 处跨进程同前缀路由；"
          f"/api/v1/health 由 {health_services} 个独立微服务注册（属预期）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
