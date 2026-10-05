"""服务入口可启动性回归测试.

锁定的缺陷（实测）：`main.py` 原写 `from ...settings import get_settings`
与 uvicorn 工厂串 `ml_platform.api.app:create_app`，而本模块导出名是 camelCase
（`getSettings` / `createApp`）。`python main.py` 是 Dockerfile 的 CMD，
于是容器启动即 ImportError；因为没有任何测试 import 过 main，CI 一直全绿。
"""

from __future__ import annotations

import importlib


def testMainModuleImports():
    """入口模块必须可导入（原命名错配在这里直接 ImportError）。"""
    main = importlib.import_module("main")
    assert callable(main.main)
    assert callable(main.getSettings)


def testUvicornFactoryStringResolves():
    """APP_FACTORY 字符串必须能解析成可调用对象（uvicorn 在运行期才解析它）。"""
    main = importlib.import_module("main")
    moduleName, attrName = main.APP_FACTORY.split(":")
    module = importlib.import_module(moduleName)
    factory = getattr(module, attrName, None)
    assert callable(factory), f"{main.APP_FACTORY} 解析不到可调用工厂"


def testCreateAppBootsWithMockSettings():
    """生产装配路径（只给 settings、registry 由内部构建）真的能起服务并路由.

    用 TestClient 进入 lifespan 后才断言——FastAPI 的 include_router 在
    `app.routes` 静态列表里不即时可见，直接扫 routes 会假阴。
    """
    from fastapi.testclient import TestClient

    from ml_platform.api.app import createApp
    from ml_platform.config.settings import Settings, resetSettings

    resetSettings()
    app = createApp(
        settings=Settings(
            backendType="mock",
            featureStoreType="mock",
            experimentStoreType="mock",
        )
    )
    with TestClient(app) as client:
        health = client.get("/health")
        assert health.status_code == 200, health.text
        assert health.json()["featureStore"] == "mock"

        job = client.post(
            "/api/v1/training/jobs",
            json={
                "algorithm": "logistic_regression",
                "dataset": "ds-1",
                "outputModelName": "entry-1",
                "features": ["f1", "f2"],
                "params": {"C": 1.0},
            },
        )
        assert job.status_code == 201, job.text

        versions = client.get("/api/v1/models/entry-1/versions")
        assert versions.status_code == 200, versions.text
        assert [v["version"] for v in versions.json()] == ["1"]
