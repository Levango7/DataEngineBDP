#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量推广 NetworkPolicy + ServiceMonitor 模板到所有应用 Chart。

功能：
1. 遍历 design/deploy/charts/ 下所有子目录
2. 跳过 __pycache__、namespace-security、dataenginebdp-umbrella
3. 跳过已有 templates/networkpolicy.yaml 的 Chart（幂等）
4. 对每个 Chart：
   - 读取 Chart.yaml 获取 name 字段作为 helper 前缀
   - 验证 _helpers.tpl 中 define 前缀与 chart name 一致
   - 检查 values.yaml 是否有 service.port（无则警告跳过）
   - 创建 templates/networkpolicy.yaml
   - 创建 templates/servicemonitor.yaml
   - 在 values.yaml 追加 networkPolicy + serviceMonitor 配置段
5. 输出处理统计：成功/跳过/失败

幂等性：
- 已有 networkpolicy.yaml 的 Chart 跳过
- values.yaml 已有 networkPolicy 段的 Chart 不重复追加

所有新文件 UTF-8 编码不带 BOM。
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

import yaml

# 跳过的目录名（已创建 / 非应用 Chart）
SKIP_DIRS: frozenset[str] = frozenset(
    {
        "__pycache__",
        "namespace-security",
        "dataenginebdp-umbrella",
    }
)

# NetworkPolicy 模板（{chart_name} 替换为实际 Chart name）
NETWORKPOLICY_TEMPLATE: str = """{{- if .Values.networkPolicy.enabled }}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "{chart_name}.fullname" . }}
  labels:
    {{- include "{chart_name}.labels" . | nindent 4 }}
spec:
  podSelector:
    matchLabels:
      {{- include "{chart_name}.selectorLabels" . | nindent 6 }}
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - podSelector: {}
      ports:
        - protocol: TCP
          port: {{ .Values.service.port | int }}
  egress:
    - {}
{{- end }}
"""

# ServiceMonitor 模板（{chart_name} 替换为实际 Chart name）
SERVICEMONITOR_TEMPLATE: str = """{{- if .Values.serviceMonitor.enabled }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "{chart_name}.fullname" . }}
  labels:
    {{- include "{chart_name}.labels" . | nindent 4 }}
    {{- with .Values.serviceMonitor.additionalLabels }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  selector:
    matchLabels:
      {{- include "{chart_name}.selectorLabels" . | nindent 6 }}
  endpoints:
    - port: http
      path: {{ .Values.serviceMonitor.path | quote }}
      interval: {{ .Values.serviceMonitor.interval | quote }}
      {{- with .Values.serviceMonitor.scrapeTimeout }}
      scrapeTimeout: {{ . | quote }}
      {{- end }}
{{- end }}
"""

# values.yaml 追加内容（开头空行用于与原内容分隔）
VALUES_APPEND: str = """
# ---- NetworkPolicy（网络隔离，等保三级微隔离要求）----
# 策略：deny-all 入站 + 仅允许同命名空间 Pod 访问服务端口 + 允许所有出站
networkPolicy:
  enabled: false

# ---- ServiceMonitor（Prometheus 指标采集）----
serviceMonitor:
  enabled: false
  path: /metrics
  interval: 30s
  scrapeTimeout: 10s
  additionalLabels: {}
"""


@dataclass
class Stats:
    """处理统计。"""

    success: list[str] = field(default_factory=list)
    # 跳过项：(chart 名, 原因)
    skipped: list[tuple[str, str]] = field(default_factory=list)
    # 失败项：(chart 名, 原因)
    failed: list[tuple[str, str]] = field(default_factory=list)


def read_chart_name(chart_dir: Path) -> str | None:
    """从 Chart.yaml 读取 name 字段。

    Args:
        chart_dir: Chart 根目录。

    Returns:
        chart name 字符串；若 Chart.yaml 不存在或无 name 字段则返回 None。
    """
    chart_yaml_path = chart_dir / "Chart.yaml"
    if not chart_yaml_path.exists():
        return None
    try:
        data = yaml.safe_load(chart_yaml_path.read_text(encoding="utf-8"))
    except yaml.YAMLError:
        return None
    if not isinstance(data, dict):
        return None
    name = data.get("name")
    return str(name) if name else None


# NetworkPolicy/ServiceMonitor 模板引用的必要 helper 后缀
REQUIRED_HELPER_SUFFIXES: tuple[str, ...] = ("fullname", "labels", "selectorLabels")


def verify_required_helpers(chart_dir: Path, chart_name: str) -> bool:
    """验证 _helpers.tpl 中存在模板所需的全部 helper。

    NetworkPolicy/ServiceMonitor 模板引用以下 helper：
    - {chart_name}.fullname
    - {chart_name}.labels
    - {chart_name}.selectorLabels

    Args:
        chart_dir: Chart 根目录。
        chart_name: 期望的 helper 前缀（即 chart name）。

    Returns:
        全部 helper 存在且前缀匹配返回 True，否则 False。
    """
    helpers_path = chart_dir / "templates" / "_helpers.tpl"
    if not helpers_path.exists():
        return False
    content = helpers_path.read_text(encoding="utf-8")
    for suffix in REQUIRED_HELPER_SUFFIXES:
        pattern = rf'define\s+"{re.escape(chart_name)}\.{suffix}"'
        if not re.search(pattern, content):
            return False
    return True


def has_service_port(chart_dir: Path) -> bool:
    """检查 values.yaml 是否有 service.port 字段。

    Args:
        chart_dir: Chart 根目录。

    Returns:
        存在 service.port 返回 True，否则 False。
    """
    values_path = chart_dir / "values.yaml"
    if not values_path.exists():
        return False
    try:
        data = yaml.safe_load(values_path.read_text(encoding="utf-8"))
    except yaml.YAMLError:
        return False
    if not isinstance(data, dict):
        return False
    service = data.get("service")
    if not isinstance(service, dict):
        return False
    return "port" in service


def has_networkpolicy_template(chart_dir: Path) -> bool:
    """检查是否已有 templates/networkpolicy.yaml（幂等判断）。"""
    return (chart_dir / "templates" / "networkpolicy.yaml").exists()


def has_networkpolicy_values(chart_dir: Path) -> bool:
    """检查 values.yaml 是否已有 networkPolicy 段（幂等判断）。"""
    values_path = chart_dir / "values.yaml"
    if not values_path.exists():
        return False
    try:
        data = yaml.safe_load(values_path.read_text(encoding="utf-8"))
    except yaml.YAMLError:
        return False
    return isinstance(data, dict) and "networkPolicy" in data


def create_template_file(
    chart_dir: Path, filename: str, chart_name: str, template: str
) -> None:
    """创建模板文件，用 chart_name 替换占位符。

    Args:
        chart_dir: Chart 根目录。
        filename: 模板文件名（如 networkpolicy.yaml）。
        chart_name: Chart name，用于替换 {chart_name} 占位符。
        template: 模板内容。
    """
    content = template.replace("{chart_name}", chart_name)
    target = chart_dir / "templates" / filename
    target.write_text(content, encoding="utf-8", newline="\n")


def append_values_section(chart_dir: Path) -> None:
    """在 values.yaml 末尾追加 networkPolicy + serviceMonitor 配置段。

    若文件末尾没有换行则先补一个换行；追加内容开头含空行用于分隔。

    Args:
        chart_dir: Chart 根目录。
    """
    values_path = chart_dir / "values.yaml"
    content = values_path.read_text(encoding="utf-8")
    # 确保末尾有换行
    if content and not content.endswith("\n"):
        content += "\n"
    # 追加配置段（VALUES_APPEND 开头有空行）
    content += VALUES_APPEND
    values_path.write_text(content, encoding="utf-8", newline="\n")


def process_chart(chart_dir: Path, stats: Stats) -> None:
    """处理单个 Chart。

    Args:
        chart_dir: Chart 根目录。
        stats: 处理统计对象。
    """
    name = chart_dir.name

    # 1. 跳过名单
    if name in SKIP_DIRS:
        stats.skipped.append((name, "在跳过名单中（非应用 Chart 或已处理）"))
        return

    # 2. 幂等：已有 networkpolicy.yaml 则跳过
    if has_networkpolicy_template(chart_dir):
        stats.skipped.append((name, "已有 networkpolicy.yaml（幂等跳过）"))
        return

    # 3. 读取 Chart.yaml 的 name 字段
    chart_name = read_chart_name(chart_dir)
    if not chart_name:
        stats.failed.append((name, "无法读取 Chart.yaml 的 name 字段"))
        return

    # 4. 验证必要 helper（fullname/labels/selectorLabels）全部存在
    if not verify_required_helpers(chart_dir, chart_name):
        stats.skipped.append(
            (name, f"缺少必要 helper（fullname/labels/selectorLabels），需人工处理")
        )
        return

    # 5. 检查 templates 目录存在
    if not (chart_dir / "templates").is_dir():
        stats.skipped.append((name, "无 templates 目录"))
        return

    # 6. 检查 service.port（结构特殊的 Chart 跳过）
    if not has_service_port(chart_dir):
        stats.skipped.append(
            (name, "values.yaml 无 service.port（结构特殊，需人工处理）")
        )
        return

    # 7. 创建模板 + 追加 values
    try:
        create_template_file(
            chart_dir, "networkpolicy.yaml", chart_name, NETWORKPOLICY_TEMPLATE
        )
        create_template_file(
            chart_dir, "servicemonitor.yaml", chart_name, SERVICEMONITOR_TEMPLATE
        )
        # values.yaml 追加（幂等：已有 networkPolicy 段则不重复追加）
        if not has_networkpolicy_values(chart_dir):
            append_values_section(chart_dir)
        stats.success.append(name)
    except OSError as exc:
        stats.failed.append((name, f"写入失败: {exc}"))


def main() -> int:
    """主入口：遍历所有 Chart 并处理，输出统计。"""
    charts_dir = Path(__file__).resolve().parent
    stats = Stats()

    for chart_dir in sorted(charts_dir.iterdir()):
        if not chart_dir.is_dir():
            continue
        process_chart(chart_dir, stats)

    # 输出统计
    total = len(stats.success) + len(stats.skipped) + len(stats.failed)
    print("\n==== 批量推广 NetworkPolicy + ServiceMonitor 处理统计 ====")
    print(f"扫描 Chart 总数: {total}")
    print(f"成功: {len(stats.success)}")
    print(f"跳过: {len(stats.skipped)}")
    print(f"失败: {len(stats.failed)}")

    if stats.success:
        print("\n---- 成功的 Chart ----")
        for n in stats.success:
            print(f"  {n}")

    if stats.skipped:
        print("\n---- 跳过的 Chart ----")
        for n, reason in stats.skipped:
            print(f"  {n}: {reason}")

    if stats.failed:
        print("\n---- 失败的 Chart ----")
        for n, reason in stats.failed:
            print(f"  {n}: {reason}")

    return 1 if stats.failed else 0


if __name__ == "__main__":
    sys.exit(main())