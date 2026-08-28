"""微调框架适配器工厂.

根据 FinetuneFramework 枚举实例化对应适配器，
业务层通过工厂获取适配器，实现框架可插拔。
"""

from __future__ import annotations

from app.adapters.base import BaseAdapter
from app.adapters.deepspeed_adapter import DeepSpeedAdapter
from app.adapters.llama_factory_adapter import LlamaFactoryAdapter
from app.adapters.peft_adapter import PEFTAdapter
from app.models.finetune_config import FinetuneFramework

# 框架枚举 → 适配器类映射
_ADAPTER_REGISTRY: dict[FinetuneFramework, type[BaseAdapter]] = {
    FinetuneFramework.LLAMA_FACTORY: LlamaFactoryAdapter,
    FinetuneFramework.PEFT: PEFTAdapter,
    FinetuneFramework.DEEPSPEED: DeepSpeedAdapter,
}


def get_adapter(
    framework: FinetuneFramework,
    workDir: str = "/tmp/finetune",
    mockMode: bool = False,
) -> BaseAdapter:
    """根据框架枚举获取适配器实例.

    Args:
        framework: 微调框架枚举。
        workDir: 工作目录。
        mockMode: 是否 Mock 模式。

    Returns:
        适配器实例。

    Raises:
        ValueError: 不支持的框架。
    """
    cls = _ADAPTER_REGISTRY.get(framework)
    if cls is None:
        raise ValueError(f"不支持的微调框架: {framework}")
    return cls(workDir=workDir, mockMode=mockMode)


def list_adapters() -> list[str]:
    """列出所有已注册适配器名称."""
    return [a.name for a in _ADAPTER_REGISTRY.values()]
