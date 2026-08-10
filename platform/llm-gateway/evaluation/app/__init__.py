"""T031 模型评测平台与 A/B 对比。

数据引擎大数据平台 · Phase 2 Batch 2 · T031 模型评测平台

基于 T030 多模态网关（OpenAI 兼容 API），提供：
- 评测任务引擎（FastAPI）：提交/查询/日志/终止
- 标准集支持：MMLU / CMMLU / CEval
- 六指标计算：准确率 / 召回率 / F1 / P95 延迟 / Token 成本 / 幻觉率
- 三模式评测：规则 / 模型（LLM as Judge）/ 人工
- A/B 对比报告：Markdown / HTML，高亮差异指标

模块版本：0.1.0
"""

__version__ = "0.1.0"
__all__ = ["__version__"]
