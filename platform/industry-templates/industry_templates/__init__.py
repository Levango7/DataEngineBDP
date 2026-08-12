"""Industry Templates Platform - L5.3 行业应用模板平台.

提供面向外部客户的预置分析模板（金融风控、零售画像、制造质检等），
让客户"开箱即用"而非从零搭建数据模型、作业流与仪表盘。

核心能力：
    - TemplateEngine：模板解析 + 参数注入 + 一键部署
    - 行业模板库：金融风控评分卡 / 零售用户画像 / 制造产线质检
    - 模板生命周期：dev → review → catalog → install → instantiate → running

对齐设计文档：
    design/详细设计/多平台多租户大数据平台_行业应用模板详细设计_v0.1.md (L5.3)
    design/工程交付计划_缺口补全_v1.0.md (P4-T1)
"""

__version__ = "0.1.0"
__all__ = ["__version__"]
