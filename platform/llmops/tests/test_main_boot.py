"""启动路径守卫（回归）：main.py 读取的 Settings 属性必须全部存在。

背景（2026-10-11 实测）：main.py 曾用 `settings.log_level`，而 Settings 字段名是驼峰
`logLevel`（platform/llmops/llmops/config/settings.py:46）⇒ 容器 `python main.py` 启动即
`AttributeError: 'Settings' object has no attribute 'log_level'`，llmops 容器 Exited(1)，
docker-compose `up --wait` 整栈失败、Integration Test 红。

本测试 monkeypatch uvicorn.run 后直接调 main()：能走到断言处即代表启动路径所有
`settings.<attr>` 访问都合法（无需真起服务器）。
"""

from __future__ import annotations

import main as llmops_main  # noqa: E402  （组件根目录在 sys.path 上）


def test_main_boot_resolves_all_settings_attrs(monkeypatch) -> None:
    captured: dict = {}
    monkeypatch.setattr(llmops_main.uvicorn, "run", lambda *a, **k: captured.update(k))

    llmops_main.main()

    assert captured, "uvicorn.run 未被调用：main() 未走到启动调用"
    for key in ("host", "port", "log_level", "reload"):
        assert key in captured, f"main() 未传 {key}"
    assert captured["log_level"]  # 非空字符串
