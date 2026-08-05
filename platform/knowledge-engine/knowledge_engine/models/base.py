"""模型基础工具."""
from __future__ import annotations

from datetime import datetime, timezone


def utc_now() -> datetime:
    """返回当前 UTC 时间（带 tzinfo）."""
    return datetime.now(timezone.utc)