"""版本化管理模块.

提供 Adapter 权重版本化、评测报告版本化、模型仓库集成。
"""

from app.versioning.adapter_registry import AdapterRegistry
from app.versioning.model_repository import ModelRepository
from app.versioning.report_registry import ReportRegistry

__all__ = [
    "AdapterRegistry",
    "ReportRegistry",
    "ModelRepository",
]
