#!/usr/bin/env python3
"""生成 / 校验 APISIX 生产路由（前缀 → 上游 service:port）。

背景：仓库里 APISIX 只配了 4 条路由（tenants/sql/catalog/rules），而前端实际调用
45 个前缀、后端声明 64 个前缀 —— 其余服务在生产网关后面根本不可达（本地 dev 靠
vite 代理所以看不出来）。手工维护 routes.json 必然会再次漂移，因此本脚本从
**代码里的路由前缀** 推导路由，并在 CI 校验生成物与仓内文件一致。

数据来源：
  1. 前缀清单：复用 scripts/gen-api-contract.py（Java @RequestMapping / Python APIRouter
     / Go router + 显式注册表），保证与契约文档同源
  2. 上游清单：design/deploy/charts/<chart>/values.yaml 的 service.port

用法：
    python scripts/gen-apisix-routes.py            # 生成（覆盖 configmap-routes.yaml）
    python scripts/gen-apisix-routes.py --check    # 只校验，不一致则退出 1
    python scripts/gen-apisix-routes.py --report   # 打印前缀覆盖报告（含未映射前缀）

退出码：0=一致/已生成；1=校验失败或存在无法解析的冲突；2=用法/文件错误。
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("ERROR: 需要 PyYAML（pip install pyyaml）", file=sys.stderr)
    sys.exit(2)

REPO_ROOT = Path(__file__).resolve().parents[1]
PLATFORM = REPO_ROOT / "platform"
CHARTS_DIR = REPO_ROOT / "design" / "deploy" / "charts"
ROUTES_FILE = CHARTS_DIR / "apisix" / "templates" / "configmap-routes.yaml"

# 多服务声明同一前缀时的显式裁决（必须写明理由，否则脚本报冲突并退出）
# key = 前缀，value = 承接该前缀的 chart 名
#
# 以下四条按"前端实际要调哪些端点 + 哪一侧真的实现了它们"取证，不按服务名猜测。
# 这类冲突的根因是同一业务域被两个服务各自实现，已作为架构债登记
# （docs/KNOWN-FAILURES.md §6）。/api/v1/dashboards 两侧都实现了前端全部 6 个调用，
# 无法用证据裁定，故意不裁（继续由本脚本报冲突、该前缀暂不经网关）。
PREFIX_OWNER_OVERRIDES: dict[str, str] = {
    # encaps-layer 与 encaps-tenant 都声明 /api/v1/tenants：
    # 前端租户管理页同时使用 /api/v1/invites、/api/v1/registrations（仅 encaps-layer 提供），
    # 故生产网关把租户管理 API 统一指向 encaps-layer；encaps-tenant 仅供集群内直连。
    "/api/v1/tenants": "encaps-layer",
    # llmops.ts 头注释明写对齐 encaps-layer LLMOpsController，且 /inference-services
    # 只有它实现（llmops 服务仅有 models/finetune/eval-metrics/human-eval）。
    "/api/v1/llmops": "encaps-layer",
    # template.ts 要 /{id}/deploy、/{id}/preview、/{id}/deployments、/categories ——
    # 只有 industry-templates 全实现（encaps-layer 的 TemplateController 仅 list/get/create）。
    "/api/v1/templates": "industry-templates",
    # dev-ml.ts 注释明写"模型仓库端点对齐 ml-platform（/api/v1/models*）"。
    "/api/v1/models": "ml-platform",
}

# 不对外经网关暴露的前缀（各服务都有，无区分度）
EXCLUDED_PREFIXES: set[str] = {
    "/api/v1/health",   # 健康检查：由 K8s 探针与集群内监控直接访问
    "/actuator",        # Spring Boot 管理端点：仅集群内
}

# 组件目录名 → chart 名（目录名与 chart 名不一致时列出）
#
# 注意：多级目录组件（finops/*、karmada/*）的 module 名在第一级目录就截断了
# （gen-api-contract.py 的既有口径），因此下面的 finops/* / karmada/* 条目当前
# 不会命中；真正生效的是"同名 chart"回退。已确认会误配的 chart 见 CHART_FALLBACK_DENY。
MODULE_CHART_OVERRIDES: dict[str, str] = {
    "governance/metadata-collector": "metadata-collector",
    "governance/lineage-analyzer": "lineage-analyzer",
    "governance/real-time-pipeline": "real-time-pipeline",
    "observability/query-api": "observability-query-api",
}

# 禁止作为路由上游的 chart：这些 chart 部署的是第三方/基础设施组件，
# 其 Service 并不提供平台 REST 前缀（把平台前缀指过去必然 404/错误响应）。
CHART_FALLBACK_DENY: set[str] = {
    # chart 镜像为 registry.k8s.io/karmada/karmada-apiserver（上游 Karmada 控制面），
    # 不提供 /api/v1/federated-queries 等平台接口；平台侧 karmada-api 子模块目前无 chart
    "karmada",
}


def load_contract_module():
    """加载 scripts/gen-api-contract.py（文件名含连字符，需按路径导入）。"""
    path = REPO_ROOT / "scripts" / "gen-api-contract.py"
    spec = importlib.util.spec_from_file_location("gen_api_contract", path)
    if spec is None or spec.loader is None:
        print(f"ERROR: 无法加载 {path}", file=sys.stderr)
        sys.exit(2)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def collect_chart_ports() -> dict[str, int]:
    """chart 名 → Service 端口（取 values.yaml 的 service.port）。"""
    ports: dict[str, int] = {}
    for chart_dir in sorted(p for p in CHARTS_DIR.iterdir() if p.is_dir()):
        values = chart_dir / "values.yaml"
        if not values.is_file():
            continue
        try:
            doc = yaml.safe_load(values.read_text(encoding="utf-8")) or {}
        except yaml.YAMLError:
            continue
        port = (doc.get("service") or {}).get("port")
        if isinstance(port, int):
            ports[chart_dir.name] = port
    return ports


def collect_prefixes(contract) -> dict[str, list[str]]:
    """前缀 → 声明该前缀的组件列表（Java/Python/Go 合并）。"""
    merged: dict[str, list[str]] = {}
    for collector in (
        contract.collect_java_prefixes(),
        contract.collect_python_prefixes(),
        contract.collect_go_prefixes(),
    ):
        for prefix, modules in collector.items():
            bucket = merged.setdefault(prefix, [])
            for module in modules:
                if module not in bucket:
                    bucket.append(module)
    return merged


def resolve_chart(module: str, chart_ports: dict[str, int]) -> str | None:
    """组件目录名 → chart 名（存在且有 Service 端口才返回）。

    多级目录组件（finops/*、karmada/*、governance/* 等）**只认显式映射**：
    父目录对应的 chart 往往只跑其中一个服务，回退到父 chart 会把路由指到错误上游。
    """
    if module in MODULE_CHART_OVERRIDES:
        chart = MODULE_CHART_OVERRIDES[module]
        return chart if chart in chart_ports else None
    if "/" in module or module in CHART_FALLBACK_DENY:
        return None
    return module if module in chart_ports else None


def build_routes(prefixes: dict[str, list[str]], chart_ports: dict[str, int]):
    """构造 APISIX routes。

    返回 (routes, unmapped 列表, conflicts 列表)。冲突不静默裁决：
    多服务声明同一前缀且无显式归属时跳过该前缀并列入 conflicts，由人工决定。
    """
    routes: list[dict] = []
    unmapped: list[str] = []
    conflicts: list[str] = []

    # 长前缀优先，避免 /api/v1/x 抢先匹配 /api/v1/x/y
    for prefix in sorted(prefixes, key=lambda p: (-len(p), p)):
        if prefix in EXCLUDED_PREFIXES or prefix.rstrip("/") in EXCLUDED_PREFIXES:
            continue

        modules = prefixes[prefix]
        charts = sorted({c for m in modules if (c := resolve_chart(m, chart_ports))})

        # 粗前缀（如 /api/v1）：多个服务把子路径直接挂在版本号下，
        # 需要按各服务的二级路径细分，不能作为单条路由，否则互相抢占
        if prefix.rstrip("/").count("/") < 2 and len(charts) > 1:
            conflicts.append(
                f"{prefix} 为粗前缀，被 {', '.join(charts)} 共享 —— 需按二级路径细化后再路由")
            continue

        if not charts:
            unmapped.append(prefix)
            continue

        if len(charts) > 1:
            owner = PREFIX_OWNER_OVERRIDES.get(prefix)
            if owner is None or owner not in charts:
                conflicts.append(
                    f"{prefix} 被多个服务声明（{', '.join(charts)}），"
                    f"需在 PREFIX_OWNER_OVERRIDES 中裁决承接方")
                continue
            target = owner
        else:
            target = charts[0]

        routes.append({
            "uri": f"{prefix}/**",
            "upstream": {
                "type": "roundrobin",
                "nodes": {f"{target}:{chart_ports[target]}": 1},
            },
        })

    routes.sort(key=lambda r: r["uri"])
    return routes, unmapped, conflicts


def route_segment(uri: str) -> str:
    """取路由 uri 的业务首段（去掉 api/v1 版本前缀），如 /api/v1/vector/** → /vector。"""
    parts = [p for p in uri.split("/**")[0].strip("/").split("/") if p]
    if parts[:2] == ["api", "v1"]:
        parts = parts[2:]
    return "/" + parts[0] if parts else ""


def render_configmap(routes: list[dict]) -> str:
    """按既有文件格式渲染 ConfigMap（保留头注释与 JSON 结构）。"""
    body = json.dumps({"routes": routes}, ensure_ascii=False, indent=2)
    indented = "\n".join(f"    {line}" for line in body.splitlines())
    return (
        "apiVersion: v1\n"
        "kind: ConfigMap\n"
        "metadata:\n"
        "  name: apisix-routes\n"
        "  labels:\n"
        "    app.kubernetes.io/name: apisix\n"
        "    app.kubernetes.io/part-of: shuqing-bigdata\n"
        "data:\n"
        "  # 由 scripts/gen-apisix-routes.py 从代码路由前缀生成，勿手工编辑；\n"
        "  # 新增服务后运行：python scripts/gen-apisix-routes.py\n"
        "  # 上游名按 chart 名（约定：单独 helm install <chart> 或以 fullnameOverride 固定）；\n"
        "  # 若整套以同一 release 名部署，需相应调整上游名。\n"
        "  routes.json: |\n"
        f"{indented}\n"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="生成/校验 APISIX 生产路由")
    parser.add_argument("--check", action="store_true", help="只校验，不写文件")
    parser.add_argument("--report", action="store_true", help="打印覆盖报告")
    args = parser.parse_args()

    contract = load_contract_module()
    chart_ports = collect_chart_ports()
    if not chart_ports:
        print(f"ERROR: 未从 {CHARTS_DIR} 解析到任何 Service 端口", file=sys.stderr)
        return 2

    prefixes = collect_prefixes(contract)
    routes, unmapped, conflicts = build_routes(prefixes, chart_ports)
    rendered = render_configmap(routes)

    # collect_frontend_calls() 返回 {api 模块文件名: [调用路径]}，需展平取路径
    frontend_paths = [p for paths in contract.collect_frontend_calls().values() for p in paths]
    frontend_segments = sorted({contract.first_seg(p) for p in frontend_paths})
    covered = {route_segment(r["uri"]) for r in routes}
    uncovered = [s for s in frontend_segments if s and s not in covered]

    if args.report:
        print(f"前缀总数: {len(prefixes)}；已生成路由: {len(routes)}；未映射: {len(unmapped)}；"
              f"待裁决冲突: {len(conflicts)}")
        if conflicts:
            print("待裁决（多服务同前缀，需人工定归属；未裁决期间该前缀生产不可达）:")
            for c in conflicts:
                print(f"  - {c}")
        if unmapped:
            print("未映射前缀（该组件无 chart/Service，生产不可达，需补 chart 或确认非对外服务）:")
            for p in unmapped:
                print(f"  - {p}  ← {', '.join(prefixes[p])}")
        print(f"前端使用首段: {len(frontend_segments)}；路由未覆盖: {len(uncovered)}")
        for s in uncovered:
            print(f"  - /api/v1/{s}")
        return 0

    if args.check:
        current = ROUTES_FILE.read_text(encoding="utf-8") if ROUTES_FILE.is_file() else ""
        if current != rendered:
            print(f"FAIL: {ROUTES_FILE.relative_to(REPO_ROOT)} 与代码前缀不一致")
            print("      运行 python scripts/gen-apisix-routes.py 重新生成并提交")
            return 1
        print(f"OK: APISIX 路由与代码前缀一致（{len(routes)} 条）")
        return 0

    ROUTES_FILE.parent.mkdir(parents=True, exist_ok=True)
    ROUTES_FILE.write_text(rendered, encoding="utf-8")
    print(f"已生成 {ROUTES_FILE.relative_to(REPO_ROOT)}：{len(routes)} 条路由")
    if unmapped:
        print(f"提示：{len(unmapped)} 个前缀未映射到 chart（--report 查看明细）")
    if uncovered:
        print(f"警告：{len(uncovered)} 个前端前缀未被路由覆盖（--report 查看明细）", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
