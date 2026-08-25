"""pytest 配置：将项目根目录加入 sys.path。

保证 `from app.xxx import ...` 在 tests 中可用。
"""

from __future__ import annotations

import os
import sys

# 将项目根目录（evaluation/）加入 sys.path
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
