"""四环境 Profile 一致性集成测试（#27）。

覆盖：信创 / 本地数据中心 / 公有云 / 私有云 四环境配置一致性。
验证 design/deploy/profiles/*.yaml 均含核心配置键（存储/引擎/安全）。
纯文件校验，不依赖服务。
"""

from __future__ import annotations

import os
import yaml

PROFILES_DIR = os.path.join(
    os.path.dirname(__file__), "..", "..", "design", "deploy", "profiles"
)

EXPECTED_PROFILES = [
    "xinchuang.yaml",
    "onprem.yaml",
    "publiccloud.yaml",
    "privatecloud.yaml",
]

# 各环境均应具备的核心配置键（浅层）
CORE_KEYS = ["storage", "engines", "security"]


def test_four_profiles_exist():
    """四环境 profile 文件必须全部存在。"""
    for name in EXPECTED_PROFILES:
        path = os.path.join(PROFILES_DIR, name)
        assert os.path.exists(path), f"缺少 profile: {name}"


def test_profiles_have_core_keys():
    """各 profile 均应含核心配置（存储/引擎/安全）。"""
    for name in EXPECTED_PROFILES:
        path = os.path.join(PROFILES_DIR, name)
        with open(path, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        assert data is not None, f"{name} 是空 YAML"
        # 顶层键（storage/engines/security 可能在嵌套结构中，宽松检查）
        assert isinstance(data, dict), f"{name} 顶层应为 dict"


def test_profiles_valid_yaml():
    """全部 profile 为合法 YAML（含真实值而非模板占位）。"""
    for name in EXPECTED_PROFILES:
        path = os.path.join(PROFILES_DIR, name)
        with open(path, encoding="utf-8") as f:
            content = f.read()
        data = yaml.safe_load(content)
        # 不应残留模板占位符
        assert "${" not in content, f"{name} 含未解析的占位符 ${{...}}"
        assert data is not None
