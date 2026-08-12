"""pytest 共享 fixtures（embedding 模块）."""

from __future__ import annotations

from pathlib import Path
import sys
from unittest.mock import MagicMock

import pytest

# 将 platform/chunker 加入 sys.path
_PKG_ROOT = Path(__file__).resolve().parent.parent.parent
if str(_PKG_ROOT) not in sys.path:
    sys.path.insert(0, str(_PKG_ROOT))

# 导入 chunker.embedding 触发适配器注册（仅一次）
import chunker.embedding  # noqa: E402, F401
from chunker.embedding.openai_adapter import clear_client_cache  # noqa: E402
from chunker.embedding.st_adapter import clear_model_cache  # noqa: E402


@pytest.fixture(autouse=True)
def _isolate_caches():
    """每个测试前后清空模型/客户端缓存（不清空注册表）."""
    clear_model_cache()
    clear_client_cache()
    yield
    clear_model_cache()
    clear_client_cache()


@pytest.fixture
def mock_sentence_transformer():
    """Mock sentence-transformers 模型."""
    import hashlib
    import struct

    class MockSTModel:
        def __init__(self, name, **kwargs):
            self.name = name
            self.tokenizer = MagicMock()
            self.tokenizer.encode = lambda text: list(text.encode("utf-8"))

        def encode(
            self, texts, batch_size=32, show_progress_bar=False, convert_to_numpy=True, normalize_embeddings=False
        ):
            results = []
            for text in texts:
                h = hashlib.sha256(text.encode("utf-8")).digest()
                seed = bytearray()
                counter = 0
                while len(seed) < 1024 * 4:
                    seed.extend(hashlib.sha256(h + counter.to_bytes(4, "big")).digest())
                    counter += 1
                vec = []
                for i in range(1024):
                    val = struct.unpack("f", seed[i * 4 : i * 4 + 4])[0]
                    if val != val:
                        val = 0.0
                    vec.append(float(val))
                results.append(vec)
            return results

    return MockSTModel
