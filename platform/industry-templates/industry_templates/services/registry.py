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
        engine: 模板引擎，不传则根据 settings 构建（含 deployMode / helm 配置）

    Returns:
        ServiceRegistry 实例
    """
    if settings is None:
        settings = get_settings()
    if engine is None:
        engine = _build_engine(settings)
    return ServiceRegistry(settings=settings, engine=engine)


def _build_engine(settings: Settings) -> TemplateEngine:
    """根据 settings 构建 TemplateEngine.

    - deployMode=helm 时注入 HelmExecutor（携带 helmBin/kubeconfig/timeout）
    - deployMode=mock 时不构建 HelmExecutor（惰性构建也只在 helm 模式触发）
    """
    helm_executor = None
    if settings.isHelm:
        from industry_templates.services.helm_executor import HelmExecutor

        helm_executor = HelmExecutor(
            helmBin=settings.helmBin,
            kubeconfig=settings.helmKubeconfig or None,
            timeout=settings.helmTimeout,
        )
    return TemplateEngine(
        templates=get_builtin_templates(),
        deployMode=settings.deployMode,
        helmExecutor=helm_executor,
        chartBase=settings.chartBase,
    )


__all__ = ["ServiceRegistry", "build_services"]
