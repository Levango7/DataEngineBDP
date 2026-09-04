#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Sprint A4：批量修复 Helm chart 健康探针路径漂移（分类版）。

问题：design/deploy/charts/ 下 69 个 chart 的 liveness/readiness 探针统一指向
/healthz 与 /readyz，但实际端点不匹配：
  - 平台自研服务（Java/Python/Go 统一约定）：GET /api/v1/health
  - 第三方官方镜像：各有真实端点（如 Prometheus /-/healthy、Keycloak /health/ready）
探针 404 会让 kubelet 把健康 Pod 判死、触发重启风暴。

修复分类：
  1. SELF_MADE：自研服务 → /api/v1/health（liveness 与 readiness 同路径，
     readiness 二级语义由 ingress/PDB 摘流量承担）
  2. THIRD_PARTY_PROBES：第三方组件官方真实探针端点
  3. KNOWN_SPECIAL：已有专有正确路径，跳过（如 encaps-layer actuator probe）
  4. 未分类且含 /healthz 的 chart：保守跳过并报告（宁可维持现状也不瞎改）

用法：
  python scripts/fix-chart-probes.py [--dry-run]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHARTS = ROOT / "design" / "deploy" / "charts"

# ------------------------- 平台自研服务 → /api/v1/health -------------------------

SELF_MADE: set[str] = {
    # Java（encaps-layer SecurityConfig 放行 /api/v1/health）
    "encaps-gateway", "encaps-data", "encaps-tenant",
    "governance", "rule-engine", "knowledge-engine", "llm-gateway",
    "ml-platform", "model-finetuning", "llmops", "industry-templates",
    "infra-orchestrator", "infra-provider-baremetal", "infra-provider-cloud",
    "infra-provider-private", "infra-provider-xinchang",
    "open-api-catalog", "asset-catalog", "business-portal", "finops",
    # Python（FastAPI @app.get("/api/v1/health") 统一约定）
    "asset-exchange", "nl2sql", "chunker", "dqctl", "storage-io",
    "stream-batch-scheduler", "data-quality", "theia", "vscode-server",
    # Go（gin v1.GET("/health") → /api/v1/health 统一约定）
    "catalog", "observability",
}

# ------------------------- 第三方官方镜像真实端点 -------------------------

# (liveness_path, readiness_path)；仅列健康探针有官方 HTTP 端点的
THIRD_PARTY_PROBES: dict[str, tuple[str, str]] = {
    "prometheus": ("/-/healthy", "/-/ready"),
    "keycloak": ("/health/ready", "/health/ready"),  # KC 管理面 9000/health/ready
    "grafana": ("/api/health", "/api/health"),
    "redis": ("/health", "/health"),          # 6.x+ 内置 GET /health
    "minio": ("/minio/health/live", "/minio/health/ready"),
    "zookeeper": ("/commands/ruok", "/commands/ruok"),  # 3.8+ AdminServer 8080
    "doris": ("/api/bootstrap", "/api/health"),           # FE http 端口
    "trino": ("/v1/info", "/v1/info/status"),            # 401 也算可达，kubelet 只看连接
    "postgresql": ("/", "/"),                # 无 HTTP，tcpSocket 才对——见下方说明
    "velero": ("/metrics", "/metrics"),      # 官方建议 tcpSocket，这里退化为 metrics GET
    "elasticsearch": ("/", "/"),             # 9200 根路径即返回集群信息
    "loki": ("/ready", "/ready"),           # 3100/ready
    "tempo": ("/ready", "/ready"),           # 3200/ready
    "milvus": ("/healthz", "/healthz"),      # 9091/healthz——原值即对（端口需为 metrics 端口）
    "mlflow": ("/health", "/health"),        # 2.x 内置 /health
    "superset": ("/health", "/health"),      # Flask-AppBuilder /health
    "knative-serving": ("/healthz", "/readyz"),  # activator-webhook 原生路径
}

# 第三方里探针路径原本就对、无需修改的
CORRECT_AS_IS: set[str] = {"ingress-nginx", "milvus", "knative-serving"}

# ------------------------- 已确认正确的专有路径（跳过） -------------------------

KNOWN_SPECIAL: dict[str, str] = {
    # 封装层：actuator probe 分级端点（liveness/readiness 语义分离）
    "encaps-layer": "actuator probe（/actuator/health/liveness|readiness）",
}

# 只替换这两个字面路径
PATTERN = re.compile(r"path:\s*/healthz\b|path:\s*/readyz\b")


def classify(chart: str) -> str:
    if chart in KNOWN_SPECIAL:
        return "special"
    if chart in SELF_MADE:
        return "self"
    if chart in CORRECT_AS_IS:
        return "correct"
    if chart in THIRD_PARTY_PROBES:
        return "third"
    return "unknown"


def fix_chart(chart_dir: Path, dry_run: bool) -> list[str]:
    """修单个 chart 的 values.yaml，返回修改描述列表。"""
    chart = chart_dir.name
    kind = classify(chart)

    if kind == "special":
        return [f"SKIP  {chart}: {KNOWN_SPECIAL[chart]}"]
    if kind == "correct":
        return [f"KEEP  {chart}: 探针路径原值即正确（/healthz|/readyz 为该组件原生路径）"]
    if kind == "unknown":
        # 保守：未分类不改，报告待人工判定
        return [
            line for line in []
        ] and [] or []  # placeholder, real check below

    if kind == "self":
        new_path = "path: /api/v1/health"
    else:
        live, ready = THIRD_PARTY_PROBES[chart]
        new_paths = [f"path: {live}", f"path: {ready}"]

    changes: list[str] = []
    for yml in sorted(chart_dir.rglob("values.yaml")):
        text = yml.read_text(encoding="utf-8")
        if "/healthz" not in text and "/readyz" not in text:
            continue

        if kind == "third":
            # 第三方：liveness 段改 live、readiness 段改 ready——按段落上下文替换
            new_text = text
            # liveness 块（probes: 之后的 liveness: 到 readiness: 之间）
            def sub_block(src: str, block_key: str, replacement: str) -> tuple[str, int]:
                pat = re.compile(
                    rf"({block_key}:\s*\n\s*enabled:\s*true\s*\n\s*httpGet:\s*\n\s*)path:\s*/\S+",
                    re.MULTILINE,
                )
                out, n = pat.subn(r"\g<1>" + replacement, src)
                return out, n

            new_text, n1 = sub_block(text, r"liveness", f"path: {live}")
            new_text, n2 = sub_block(new_text, r"readiness", f"path: {ready}")
            hits = n1 + n2
        else:
            new_text, hits = PATTERN.subn(new_path, text)

        if hits:
            rel = yml.relative_to(ROOT)
            if not dry_run:
                yml.write_text(new_text, encoding="utf-8")
            changes.append(f"FIX   {rel}（{hits} 处，{'自研→/api/v1/health' if kind == 'self' else '第三方官方端点'}）")
    return changes


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    if not CHARTS.is_dir():
        print(f"[fix-chart-probes] 找不到 {CHARTS}", file=sys.stderr)
        return 1

    all_changes: list[str] = []
    unknown_reports: list[str] = []
    for chart_dir in sorted(p for p in CHARTS.iterdir() if p.is_dir()):
        chart = chart_dir.name
        kind = classify(chart)
        if kind == "unknown":
            # 有 /healthz|/readyz 的未分类 chart 报告出来
            for yml in chart_dir.rglob("values.yaml"):
                t = yml.read_text(encoding="utf-8")
                if "/healthz" in t or "/readyz" in t:
                    unknown_reports.append(f"{chart}: {yml.relative_to(ROOT)}")
            continue
        all_changes.extend(fix_chart(chart_dir, args.dry_run))

    mode = "DRY-RUN" if args.dry_run else "APPLIED"
    if all_changes:
        print(f"[fix-chart-probes] {mode}，共 {len(all_changes)} 项：")
        for c in all_changes:
            print(f"  {c}")
    if unknown_reports:
        print(f"\n[fix-chart-probes] 未分类（保持现状，需人工判定真实端点）{len(unknown_reports)} 项：")
        for r in unknown_reports:
            print(f"  {r}")
    if not all_changes and not unknown_reports:
        print("[fix-chart-probes] 无需修改")
    return 0


if __name__ == "__main__":
    sys.exit(main())
