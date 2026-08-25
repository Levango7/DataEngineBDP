#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证所有 Helm Chart 的结构与 YAML 语法。
1. 检查每个 Chart 目录结构完整性
2. 验证 Chart.yaml / values.yaml 的 YAML 语法
3. 检查模板文件存在性与 Go template 语法基本完整（{{ }} 配对）
"""

import os
import sys
import re

try:
    import yaml
except ImportError:
    print("❌ 需要 PyYAML，请执行: pip install pyyaml")
    sys.exit(1)

CHARTS_DIR = os.path.dirname(os.path.abspath(__file__))

# 必须存在的模板文件（验收标准最低要求）
REQUIRED_TEMPLATES = [
    "_helpers.tpl",
    "deployment.yaml",
    "service.yaml",
]
# 可选模板文件（"如需要"，已有 Chart 可能缺失）
OPTIONAL_TEMPLATES = [
    "configmap.yaml",
    "ingress.yaml",
    "hpa.yaml",
    "pdb.yaml",
    "NOTES.txt",
]


def validate_yaml_file(filepath):
    """验证 YAML 文件语法"""
    try:
        with open(filepath, "r", encoding="utf-8") as f:
            yaml.safe_load(f)
        return True, ""
    except yaml.YAMLError as e:
        return False, str(e)
    except Exception as e:
        return False, str(e)


def validate_template_braces(filepath):
    """检查 Go template {{ }} 花括号配对（基本检查）"""
    try:
        with open(filepath, "r", encoding="utf-8") as f:
            content = f.read()
        # 统计 {{ 和 }} 的数量
        open_count = content.count("{{")
        close_count = content.count("}}")
        if open_count != close_count:
            return False, "花括号不配对: open={{ count={}, close=}} count={}".format(open_count, close_count)
        # 检查是否有单花括号（可能是f-string破坏的残留）
        # 排除 {{ 和 }} 后，检查是否还有孤立的 { 或 }
        temp = content.replace("{{", "").replace("}}", "")
        # 允许 YAML 中的 {} 空字典，但不允许 { include 这种
        if re.search(r'\{[^}\s]*include', temp):
            return False, "检测到单花括号 {include，可能是 f-string 破坏残留"
        return True, ""
    except Exception as e:
        return False, str(e)


def validate_chart(chart_dir, chart_name):
    """验证单个 Chart"""
    errors = []
    warnings = []

    # 1. 检查 Chart.yaml
    chart_yaml_path = os.path.join(chart_dir, "Chart.yaml")
    if not os.path.exists(chart_yaml_path):
        errors.append(f"缺失 Chart.yaml")
    else:
        ok, msg = validate_yaml_file(chart_yaml_path)
        if not ok:
            errors.append(f"Chart.yaml YAML 语法错误: {msg}")
        else:
            # 检查必要字段
            with open(chart_yaml_path, "r", encoding="utf-8") as f:
                chart_data = yaml.safe_load(f)
            for field in ["apiVersion", "name", "description", "type", "version", "appVersion"]:
                if field not in chart_data:
                    warnings.append(f"Chart.yaml 缺少字段: {field}")
            if chart_data.get("name") != chart_name:
                errors.append(f"Chart.yaml name={chart_data.get('name')} 与目录名 {chart_name} 不一致")

    # 2. 检查 values.yaml
    values_path = os.path.join(chart_dir, "values.yaml")
    if not os.path.exists(values_path):
        errors.append(f"缺失 values.yaml")
    else:
        ok, msg = validate_yaml_file(values_path)
        if not ok:
            errors.append(f"values.yaml YAML 语法错误: {msg}")
        else:
            with open(values_path, "r", encoding="utf-8") as f:
                values_data = yaml.safe_load(f)
            # 检查必要配置项
            for key in ["image", "replicaCount", "service", "resources"]:
                if key not in values_data:
                    warnings.append(f"values.yaml 缺少配置项: {key}")
            if "image" in values_data:
                img = values_data["image"]
                for key in ["repository", "tag", "pullPolicy"]:
                    if key not in img:
                        warnings.append(f"values.yaml image 缺少: {key}")

    # 2.1 幽灵镜像检测：sq-* 引用需有对应 platform 组件 Dockerfile（6.1 修复）
    values_path = os.path.join(chart_dir, "values.yaml")
    if os.path.exists(values_path):
        try:
            with open(values_path, "r", encoding="utf-8") as f:
                values_data = yaml.safe_load(f)
            img_repo = (values_data.get("image") or {}).get("repository", "")
            if img_repo.startswith("sq-"):
                # 真实自研镜像名：platform 下含 Dockerfile 的目录名（sq-{dir}）
                platform_root = os.path.abspath(os.path.join(chart_dir, "..", "..", "..", "..", "platform"))
                real_names = set()
                if os.path.isdir(platform_root):
                    for root, dirs, files in os.walk(platform_root):
                        if "Dockerfile" in files:
                            mod = os.path.basename(root)
                            real_names.add(f"sq-{mod}")
                            # 嵌套模块（如 governance/metadata-collector）取两级名
                            parent = os.path.basename(os.path.dirname(root))
                            if parent not in ("src", "docker", "deploy", "platform", "api", "cmd"):
                                real_names.add(f"sq-{parent}-{mod}")
                if img_repo not in real_names:
                    warnings.append(
                        f"幽灵镜像: {img_repo} 无对应 platform 组件 Dockerfile（可能是第三方组件误用 sq- 前缀，应改官方镜像）"
                    )
        except Exception as e:
            warnings.append(f"幽灵镜像检测异常: {e}")

    # 3. 检查 templates 目录
    templates_dir = os.path.join(chart_dir, "templates")
    # Umbrella Chart（仅聚合依赖，无自身模板）合法豁免
    has_dependencies = False
    try:
        with open(os.path.join(chart_dir, "Chart.yaml"), "r", encoding="utf-8") as f:
            chart_meta = yaml.safe_load(f) or {}
        has_dependencies = bool(chart_meta.get("dependencies"))
    except Exception:
        pass
    if not os.path.isdir(templates_dir):
        if has_dependencies:
            warnings.append("Umbrella Chart（dependencies 聚合），无 templates/ 目录")
        else:
            errors.append(f"缺失 templates/ 目录")
    else:
        # Job 型 Chart（如 finance-template 用 import-job/verification-job）豁免 deployment/service
        is_job_chart = os.path.exists(os.path.join(templates_dir, "import-job.yaml")) or \
                       os.path.exists(os.path.join(templates_dir, "verification-job.yaml"))
        # 手写型 Chart：只要存在任意可渲染模板（除 NOTES.txt 外的 .yaml/.tpl）
        # 即视为自包含实现（如 apollo 多服务、chaos-mesh、iceberg-compaction CronJob），
        # 不强制要求固定文件名的 deployment.yaml/service.yaml
        renderable = [
            fn for fn in os.listdir(templates_dir)
            if (fn.endswith(".yaml") or fn.endswith(".tpl")) and fn != "NOTES.txt"
        ]
        if has_dependencies and not renderable:
            warnings.append("Umbrella Chart（dependencies 聚合），跳过模板文件检查")
            return errors, warnings
        is_freeform_chart = bool(renderable) and not (
            os.path.exists(os.path.join(templates_dir, "deployment.yaml"))
            and os.path.exists(os.path.join(templates_dir, "service.yaml"))
        )
        # 必需模板
        for tpl in REQUIRED_TEMPLATES:
            if is_job_chart and tpl in ("deployment.yaml", "service.yaml"):
                continue  # Job 型 chart 无需 Deployment/Service
            if is_freeform_chart and tpl in ("deployment.yaml", "service.yaml"):
                continue  # 手写型 chart 有等价可渲染模板即可
            tpl_path = os.path.join(templates_dir, tpl)
            if not os.path.exists(tpl_path):
                errors.append(f"缺失必需模板 templates/{tpl}")
            else:
                if tpl.endswith(".yaml") or tpl.endswith(".tpl") or tpl == "NOTES.txt":
                    ok, msg = validate_template_braces(tpl_path)
                    if not ok:
                        errors.append(f"templates/{tpl} 语法错误: {msg}")
        # 可选模板（缺失仅警告）
        for tpl in OPTIONAL_TEMPLATES:
            tpl_path = os.path.join(templates_dir, tpl)
            if not os.path.exists(tpl_path):
                warnings.append(f"缺失可选模板 templates/{tpl}")
            else:
                if tpl.endswith(".yaml") or tpl.endswith(".tpl") or tpl == "NOTES.txt":
                    ok, msg = validate_template_braces(tpl_path)
                    if not ok:
                        errors.append(f"templates/{tpl} 语法错误: {msg}")
        # 所有可渲染模板统一做括号配对校验（覆盖手写型 Chart 的每个模板）
        for fn in renderable:
            ok, msg = validate_template_braces(os.path.join(templates_dir, fn))
            if not ok:
                errors.append(f"templates/{fn} 语法错误: {msg}")

    return errors, warnings


def main():
    # 获取所有 Chart 目录（排除脚本文件）
    all_charts = []
    for entry in sorted(os.listdir(CHARTS_DIR)):
        full_path = os.path.join(CHARTS_DIR, entry)
        if os.path.isdir(full_path):
            all_charts.append(entry)

    print(f"{'=' * 80}")
    print(f"Helm Chart 验证报告")
    print(f"{'=' * 80}")
    print(f"Chart 目录: {CHARTS_DIR}")
    print(f"Chart 总数: {len(all_charts)}")
    print(f"{'=' * 80}")

    total_errors = 0
    total_warnings = 0
    passed_charts = []

    for chart_name in all_charts:
        chart_dir = os.path.join(CHARTS_DIR, chart_name)
        errors, warnings = validate_chart(chart_dir, chart_name)

        if errors:
            total_errors += len(errors)
            print(f"\n❌ {chart_name}")
            for e in errors:
                print(f"   ERROR: {e}")
            for w in warnings:
                print(f"   WARN:  {w}")
                total_warnings += 1
        else:
            passed_charts.append(chart_name)
            if warnings:
                total_warnings += len(warnings)
                print(f"\n⚠️  {chart_name} (通过，但有警告)")
                for w in warnings:
                    print(f"   WARN:  {w}")
            else:
                print(f"✅ {chart_name}")

    print(f"\n{'=' * 80}")
    print(f"验证结果汇总")
    print(f"{'=' * 80}")
    print(f"Chart 总数:       {len(all_charts)}")
    print(f"通过:             {len(passed_charts)}")
    print(f"错误总数:         {total_errors}")
    print(f"警告总数:         {total_warnings}")
    print(f"{'=' * 80}")

    if total_errors == 0:
        print("✅ 所有 Chart 验证通过！")
        return 0
    else:
        print(f"❌ 有 {total_errors} 个错误需要修复！")
        return 1


if __name__ == "__main__":
    sys.exit(main())