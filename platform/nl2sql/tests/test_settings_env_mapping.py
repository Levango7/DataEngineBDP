"""环境变量映射回归：NL2SQL_* 变量必须真正被 Settings 读到。

背景（2026-10-11/12 实测，pydantic-settings 2.5.2 与 2.15.0 双版本）：
Settings 字段名是驼峰（defaultEngine 等），pydantic-settings 只把「字段名（或别名）」大写后拼 env_prefix，
驼峰字段会落成 NL2SQL_DEFAULTENGINE，与部署所用的 NL2SQL_DEFAULT_ENGINE 不匹配 ⇒ 环境变量被**静默忽略**、配置回落默认值。
修法：validation_alias=AliasChoices("NL2SQL_DEFAULT_ENGINE", "defaultEngine")。

第二个用例保护另一半语义：显式 alias 曾使「构造参数按字段名传入」被静默忽略
（pydantic-settings 实测行为），而构造参数注入是本组件测试与调用方的既有用法，必须钉住。
"""

from __future__ import annotations

from config.settings import Settings


def test_env_var_is_honored(monkeypatch) -> None:
    monkeypatch.setenv("NL2SQL_DEFAULT_ENGINE", "doris")
    assert Settings().defaultEngine == "doris"


def test_constructor_arg_still_works(monkeypatch) -> None:
    monkeypatch.delenv("NL2SQL_DEFAULT_ENGINE", raising=False)
    assert Settings(defaultEngine="trino").defaultEngine == "trino"


def test_both_sources_bind_independently(monkeypatch) -> None:
    """两个来源各自都能绑定（不对"谁优先"断言）。

    注意：`初始化参数 vs 环境变量` 的优先顺序在 pydantic-settings 版本间**相反**
    （实测 2.5.2 环境变量赢、2.15.0 初始化参数赢；CI 用 2.15.0），故本测试只断言
    "两条入口都能把值送进字段"，不锁定优先级——否则会在另一版本环境下假红。
    """
    monkeypatch.delenv("NL2SQL_DEFAULT_ENGINE", raising=False)
    assert Settings(defaultEngine="trino").defaultEngine == "trino"  # 构造参数入口
    monkeypatch.setenv("NL2SQL_DEFAULT_ENGINE", "doris")
    assert Settings().defaultEngine == "doris"  # 环境变量入口
