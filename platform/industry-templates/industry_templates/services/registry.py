"""服务注册表 - 构建并注入 TemplateEngine."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from industry_templates.config.settings import Settings, get_settings
from industry_templates.services.template_engine import TemplateEngine
from industry_templates.templates import get_builtin_templates


@dataclass
class ServiceRegistry:
    """服务注册表."""

    settings: Settings
    engine: TemplateEngine


def build_services(
    settings: Optional[Settings] = None,
    engine: Optional[TemplateEngine] = None,
) -> ServiceRegistry:
    """构建服务注册表.

    Args:
        settings: 配置，不传则使用全局单例
        engine: 模板引擎，不传则构建内置模板库

    Returns:
        ServiceRegistry 实例
    """
    if settings is None:
        settings = get_settings()
    if engine is None:
        engine = TemplateEngine(get_builtin_templates())
    return ServiceRegistry(settings=settings, engine=engine)


__all__ = ["ServiceRegistry", "build_services"]