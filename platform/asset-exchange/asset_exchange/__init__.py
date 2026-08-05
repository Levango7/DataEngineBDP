"""Asset Exchange Platform - L5.6 数据资产流通平台.

提供数据资产上架、订阅、审批、交付、计费一体化能力。
采用 Mock + 接口抽象策略：定义 AssetRepository/SubscriptionRepository/DeliveryRepository/
BillingRepository 接口 + Mock 内存实现，真实后端通过配置注入。

对齐设计文档：
    design/详细设计/多平台多租户大数据平台_数据资产流通详细设计_v0.1.md (L5.6)
    design/工程交付计划_缺口补全_v1.0.md (P4-T4)
"""

__version__ = "0.1.0"
__all__ = ["__version__"]