"""LLMOps Platform - L4.5.3 智能数据层大模型运营平台.

提供模型注册、训练/微调、部署、监控一体化能力。
采用 Mock + 接口抽象策略：定义 ModelStore/Trainer/Deployer/Monitor 接口 +
Mock 实现，真实 MLflow 通过配置注入。

对齐设计文档：
    design/详细设计/多平台多租户大数据平台_智能数据层详细设计_v0.1.md (L4.5.5)
    design/工程交付计划_缺口补全_v1.0.md (P3-T4)
"""

__version__ = "0.1.0"
__all__ = ["__version__"]
