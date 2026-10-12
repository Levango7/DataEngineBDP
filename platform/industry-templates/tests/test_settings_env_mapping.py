"""环境变量映射回归：INDUSTRY_TEMPLATES_* 变量必须真正被 Settings 读到。

背景（2026-10-11/12 实测，pydantic-settings 2.5.2 与 2.15.0 双版本）：
Settings 字段名是驼峰（deployMode 等），pydantic-settings 只把「字段名（或别名）」大写后拼 env_prefix，
驼峰字段会落成 INDUSTRY_TEMPLATES_DEPLOYMODE，与部署所用的 INDUSTRY_TEMPLATES_DEPLOY_MODE 不匹配 ⇒ 环境变量被**静默忽略**、配置回落默认值。
修法：validation_alias=AliasChoices("INDUSTRY_TEMPLATES_DEPLOY_MODE", "deployMode")。

第三个用例保护另一半语义：显式 alias 曾使「构造参数按字段名传入」被静默忽略
（pydantic-settings 实测行为），而构造参数注入是本组件测试与调用方的既有用法，必须钉住。
"""

from __future__ import annotations

from industry_templates.config.settings import Settings


def test_env_var_is_honored(monkeypatch) -> None:
    monkeypatch.setenv("INDUSTRY_TEMPLATES_DEPLOY_MODE", "mock")
    assert Settings().deployMode == "mock"


def test_constructor_arg_still_works(monkeypatch) -> None:
    monkeypatch.delenv("INDUSTRY_TEMPLATES_DEPLOY_MODE", raising=False)
    assert Settings(deployMode="helm").deployMode == "helm"


def test_env_var_beats_constructor(monkeypatch) -> None:
    """pydantic-settings 语义：环境变量优先于初始化参数。"""
    monkeypatch.setenv("INDUSTRY_TEMPLATES_DEPLOY_MODE", "mock")
    assert Settings(deployMode="helm").deployMode == "mock"
