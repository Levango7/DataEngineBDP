"""环境变量映射回归：LLMOPS_* 变量必须真正被 Settings 读到。

背景（2026-10-11 容器内实测，pydantic-settings 2.15.0）：Settings 的字段名是驼峰
（logLevel / storeType / mlflowUri ...），而 pydantic-settings 只把「字段名（或别名）」
大写后拼 env_prefix，驼峰字段名 logLevel → 期望变量 LLMOPS_LOGLEVEL，与实际部署所用的
LLMOPS_LOG_LEVEL 不匹配 ⇒ 环境变量被**静默忽略**，全部取默认值。

后果（Integration Test 实证）：compose 注入 LLMOPS_STORE_TYPE=mock 无效，registry 走
`_build_mlflow()` → 镜像未装 mlflow → `StoreUnavailableError` → it-llmops Exited(1)
→ docker-compose `up --wait` 整栈失败。

修法：驼峰字段显式给 `validation_alias="LLMOPS_<SNAKE>"`（属性名保持驼峰，调用方不变）。
本测试用 monkeypatch 注入环境变量后新建 Settings 实例（绕过 get_settings 的 lru_cache）。
"""

from __future__ import annotations

from llmops.config.settings import Settings


def test_snake_case_env_vars_are_honored(monkeypatch) -> None:
    """compose 使用的 LLMOPS_<SNAKE_CASE> 变量必须逐个生效。"""
    monkeypatch.setenv("LLMOPS_STORE_TYPE", "mock")
    monkeypatch.setenv("LLMOPS_LOG_LEVEL", "debug")
    monkeypatch.setenv("LLMOPS_MLFLOW_URI", "http://mlflow:5000")
    monkeypatch.setenv("LLMOPS_API_PREFIX", "/api/v2")
    s = Settings()

    assert s.storeType == "mock", "LLMOPS_STORE_TYPE 未生效（容器会走 mlflow 分支）"
    assert s.isMock is True
    assert s.logLevel == "debug", "LLMOPS_LOG_LEVEL 未生效"
    assert s.mlflowUri == "http://mlflow:5000", "LLMOPS_MLFLOW_URI 未生效"
    assert s.mlflowRegistryUri == "", "空值应回退默认"
    assert s.apiPrefix == "/api/v2", "LLMOPS_API_PREFIX 未生效"


def test_defaults_kept_when_env_absent(monkeypatch) -> None:
    """未注入时取默认值（防止别名把默认值读成 None）。"""
    for key in (
        "LLMOPS_STORE_TYPE",
        "LLMOPS_LOG_LEVEL",
        "LLMOPS_MLFLOW_URI",
        "LLMOPS_MLFLOW_REGISTRY_URI",
        "LLMOPS_API_PREFIX",
    ):
        monkeypatch.delenv(key, raising=False)
    s = Settings()

    assert s.storeType == "mlflow"
    assert s.logLevel == "info"
    assert s.mlflowUri == "http://localhost:5000"
    assert s.mlflowRegistryUri == ""
    assert s.apiPrefix == "/api/v1"


def test_ai_mode_still_overrides_store_type(monkeypatch) -> None:
    """P-02：AI_MODE=mock 仍应覆盖 LLMOPS_STORE_TYPE（回归保护）。"""
    monkeypatch.setenv("LLMOPS_STORE_TYPE", "mlflow")
    monkeypatch.setenv("AI_MODE", "mock")
    s = Settings()

    assert s.storeType == "mock"
