"""pytest 共享 fixtures."""

from __future__ import annotations

from pathlib import Path
import sys

import pytest

# 将 platform/chunker 加入 sys.path，便于 `import chunker`
_PKG_ROOT = Path(__file__).resolve().parent.parent
if str(_PKG_ROOT) not in sys.path:
    sys.path.insert(0, str(_PKG_ROOT))

from chunker.registry import clear_registry  # noqa: E402


@pytest.fixture(autouse=True)
def _isolate_registry():
    """每个测试前后清空注册表，避免测试间相互污染."""
    clear_registry()
    yield
    clear_registry()
