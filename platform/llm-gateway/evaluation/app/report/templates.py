"""报告模板（Markdown / HTML）。

提供 A/B 对比报告的模板渲染：
- Markdown 模板：表格 + 高亮（加粗 + ⚠️ 标记）
- HTML 模板：表格 + 高亮（背景色 + ⚠️ 标记）

高亮规则：差异绝对值 > threshold 时高亮该行。
"""

from __future__ import annotations

from app.models import ABReport, JobInfo, MetricDiff, MetricsBundle


class ReportTemplates:
    """报告模板渲染器。"""

    # 指标中文名映射
    METRIC_CN: dict[str, str] = {
        "accuracy": "准确率",
        "recall": "召回率",
        "f1": "F1",
        "latency_p95": "P95 延迟(ms)",
        "cost": "Token 成本(元)",
        "hallucination": "幻觉率",
    }

    # 指标是否为"越高越好"
    HIGHER_BETTER: dict[str, bool] = {
        "accuracy": True,
        "recall": True,
        "f1": True,
        "latency_p95": False,
        "cost": False,
        "hallucination": False,
    }

    @classmethod
    def render_markdown(cls, report: ABReport) -> str:
        """渲染 Markdown 报告。"""
        lines: list[str] = []
        lines.append(f"# 模型 A/B 对比报告")
        lines.append("")
        lines.append(f"- **模型 A**：{report.model_a}（任务 {report.job_a}）")
        lines.append(f"- **模型 B**：{report.model_b}（任务 {report.job_b}）")
        lines.append(f"- **数据集**：{report.dataset}")
        lines.append(f"- **生成时间**：{report.generated_at.isoformat()}")
        lines.append("")

        # 指标对比表
        lines.append("## 指标对比")
        lines.append("")
        lines.append("| 指标 | 模型 A | 模型 B | 差异 | 差异率 | 更优 |")
        lines.append("| --- | --- | --- | --- | --- | --- |")
        for diff in report.diffs:
            name_cn = cls.METRIC_CN.get(diff.name, diff.name)
            # 高亮：加粗 + ⚠️
            highlight = "⚠️ " if diff.highlighted else ""
            better_cn = {"a": "A", "b": "B", "tie": "持平"}.get(diff.better, "-")
            lines.append(
                f"| {highlight}**{name_cn}** | "
                f"{diff.value_a:.4f} | {diff.value_b:.4f} | "
                f"{diff.diff:+.4f} | {diff.diff_percent:+.2f}% | "
                f"{better_cn} |"
            )
        lines.append("")

        # 差异高亮摘要
        highlighted = [d for d in report.diffs if d.highlighted]
        lines.append("## 差异高亮")
        lines.append("")
        if highlighted:
            lines.append(f"共 **{len(highlighted)}** 个指标差异超过阈值：")
            lines.append("")
            for diff in highlighted:
                name_cn = cls.METRIC_CN.get(diff.name, diff.name)
                better_cn = {"a": "A", "b": "B", "tie": "持平"}.get(
                    diff.better, "-"
                )
                lines.append(
                    f"- ⚠️ **{name_cn}**：A={diff.value_a:.4f}, "
                    f"B={diff.value_b:.4f}, 差异={diff.diff:+.4f} "
                    f"({diff.diff_percent:+.2f}%)，更优：{better_cn}"
                )
        else:
            lines.append("所有指标差异均在阈值范围内，两模型表现接近。")
        lines.append("")

        # 总结
        lines.append("## 总结")
        lines.append("")
        lines.append(report.summary)
        lines.append("")

        return "\n".join(lines)

    @classmethod
    def render_html(cls, report: ABReport) -> str:
        """渲染 HTML 报告。"""
        rows_html: list[str] = []
        for diff in report.diffs:
            name_cn = cls.METRIC_CN.get(diff.name, diff.name)
            better_cn = {"a": "A", "b": "B", "tie": "持平"}.get(diff.better, "-")
            # 高亮行：背景色
            row_class = "highlight" if diff.highlighted else ""
            highlight_icon = "⚠️ " if diff.highlighted else ""
            rows_html.append(
                f'<tr class="{row_class}">'
                f"<td>{highlight_icon}<strong>{name_cn}</strong></td>"
                f"<td>{diff.value_a:.4f}</td>"
                f"<td>{diff.value_b:.4f}</td>"
                f"<td>{diff.diff:+.4f}</td>"
                f"<td>{diff.diff_percent:+.2f}%</td>"
                f"<td>{better_cn}</td>"
                f"</tr>"
            )

        # 高亮摘要
        highlighted = [d for d in report.diffs if d.highlighted]
        if highlighted:
            highlight_items = "\n".join(
                f"<li>⚠️ <strong>{cls.METRIC_CN.get(d.name, d.name)}</strong>："
                f"A={d.value_a:.4f}, B={d.value_b:.4f}, "
                f"差异={d.diff:+.4f} ({d.diff_percent:+.2f}%)</li>"
                for d in highlighted
            )
            highlight_section = (
                f"<h2>差异高亮</h2>"
                f"<p>共 <strong>{len(highlighted)}</strong> 个指标差异超过阈值：</p>"
                f"<ul>{highlight_items}</ul>"
            )
        else:
            highlight_section = (
                "<h2>差异高亮</h2>"
                "<p>所有指标差异均在阈值范围内，两模型表现接近。</p>"
            )

        html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>模型 A/B 对比报告</title>
    <style>
        body {{ font-family: -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; margin: 40px; color: #333; }}
        h1 {{ color: #1a1a1a; border-bottom: 2px solid #409eff; padding-bottom: 10px; }}
        h2 {{ color: #2c3e50; margin-top: 30px; }}
        table {{ border-collapse: collapse; width: 100%; margin: 20px 0; }}
        th, td {{ border: 1px solid #ddd; padding: 10px 14px; text-align: center; }}
        th {{ background-color: #f5f7fa; font-weight: 600; }}
        tr.highlight {{ background-color: #fef0f0; }}
        tr.highlight td {{ color: #f56c6c; }}
        .meta {{ background: #f5f7fa; padding: 16px; border-radius: 4px; margin: 16px 0; }}
        .meta p {{ margin: 6px 0; }}
        ul {{ line-height: 1.8; }}
    </style>
</head>
<body>
    <h1>模型 A/B 对比报告</h1>
    <div class="meta">
        <p><strong>模型 A</strong>：{report.model_a}（任务 {report.job_a}）</p>
        <p><strong>模型 B</strong>：{report.model_b}（任务 {report.job_b}）</p>
        <p><strong>数据集</strong>：{report.dataset}</p>
        <p><strong>生成时间</strong>：{report.generated_at.isoformat()}</p>
    </div>
    <h2>指标对比</h2>
    <table>
        <thead>
            <tr>
                <th>指标</th>
                <th>模型 A</th>
                <th>模型 B</th>
                <th>差异</th>
                <th>差异率</th>
                <th>更优</th>
            </tr>
        </thead>
        <tbody>
            {"".join(rows_html)}
        </tbody>
    </table>
    {highlight_section}
    <h2>总结</h2>
    <p>{report.summary}</p>
</body>
</html>"""
        return html