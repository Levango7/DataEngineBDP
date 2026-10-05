"""Settings 环境变量绑定回归测试.

锁定的缺陷：本模块文档与 deploy/manifests 用 `ML_<UPPER_SNAKE>` 命名，而
Settings 字段是 camelCase。pydantic-settings 默认按字段名匹配环境变量，
于是这些变量在修复前一个都不生效（配置静默回落默认值 sklearn/redis/mlflow）。
"""

from __future__ import annotations

import pytest

from ml_platform.config.settings import Settings, getSettings, resetSettings


@pytest.fixture
def _cleanSettingsCache():
    """用例前后都清一次配置缓存，避免 lru_cache 把上次的 env 结果带过来."""
    resetSettings()
    yield
    resetSettings()


def testDocumentedEnvNamesBind(monkeypatch, _cleanSettingsCache):
    """文档声明的 ML_* 变量必须真的进入配置."""
    monkeypatch.setenv("ML_BACKEND_TYPE", "mock")
    monkeypatch.setenv("ML_FEATURE_STORE_TYPE", "mock")
    monkeypatch.setenv("ML_EXPERIMENT_STORE_TYPE", "mock")
    monkeypatch.setenv("ML_LOG_LEVEL", "warning")
    monkeypatch.setenv("ML_PORT", "19191")
    monkeypatch.setenv("ML_MLFLOW_ENABLED", "true")

    s = getSettings()

    assert s.backendType == "mock"
    assert s.featureStoreType == "mock"
    assert s.experimentStoreType == "mock"
    assert s.logLevel == "warning"
    assert s.port == 19191
    assert s.mlflowEnabled is True


def testEnvNameWithoutUnderscoreStillBinds(monkeypatch, _cleanSettingsCache):
    """单词型变量（ML_HOST）保持原有绑定能力."""
    monkeypatch.setenv("ML_HOST", "10.1.2.3")
    assert getSettings().host == "10.1.2.3"


def testUnknownMlPrefixVarsIgnored(monkeypatch, _cleanSettingsCache):
    """未识别的 ML_* 变量不得变成字段错误（原 extra=ignore 口径）."""
    monkeypatch.setenv("ML_NOT_A_FIELD", "whatever")
    monkeypatch.setenv("ML_BACKEND_TYPE", "mock")
    assert getSettings().backendType == "mock"


def testAiModeStillOverridesBackendType(monkeypatch, _cleanSettingsCache):
    """P-02 优先级不能被破坏：AI_MODE=mock 覆盖 ML_BACKEND_TYPE=sklearn."""
    monkeypatch.setenv("ML_BACKEND_TYPE", "sklearn")
    monkeypatch.setenv("AI_MODE", "mock")
    assert getSettings().backendType == "mock"


def testConstructorKwargsStillWork():
    """按字段名直接构造仍可用（tests/conftest.py 依赖这条路）。"""
    assert Settings(backendType="mock").backendType == "mock"
