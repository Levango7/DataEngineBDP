"""示例函数 handler · Python 运行时 · 数据引擎大数据平台 T025。

用户函数约定：暴露 ``handle(event: dict) -> dict`` 入口。
本示例实现一个简单的 echo 函数，供集成测试验证。
"""

from __future__ import annotations

from typing import Any, Dict


def handle(event: Dict[str, Any]) -> Dict[str, Any]:
    """Echo 函数：原样返回 event，附加 runtime 标识。

    Args:
        event: 调用事件（任意 JSON）。

    Returns:
        响应 JSON。
    """
    return {
        "runtime": "python",
        "echo": event,
        "message": "Hello from Python function runtime",
    }
