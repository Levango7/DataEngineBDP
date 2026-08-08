"""pytest 共享 fixtures（rag 模块）."""

from __future__ import annotations

from pathlib import Path
import sys

import pytest

# 将 platform/chunker 加入 sys.path
_PKG_ROOT = Path(__file__).resolve().parent.parent.parent
if str(_PKG_ROOT) not in sys.path:
    sys.path.insert(0, str(_PKG_ROOT))

from chunker.embedding.openai_adapter import clear_client_cache  # noqa: E402
from chunker.embedding.registry import clear_registry as clear_emb_registry  # noqa: E402
from chunker.embedding.st_adapter import clear_model_cache  # noqa: E402
from chunker.registry import clear_registry as clear_chunker_registry  # noqa: E402


@pytest.fixture(autouse=True)
def _isolate_registries():
    """每个测试前后清空注册表与缓存."""
    clear_emb_registry()
    clear_model_cache()
    clear_client_cache()
    clear_chunker_registry()
    # 重新导入以触发注册
    import chunker.audio_chunker  # noqa: F401
    import chunker.embedding  # noqa: F401
    import chunker.embedding.bge_adapter  # noqa: F401
    import chunker.embedding.m3e_adapter  # noqa: F401
    import chunker.embedding.openai_adapter  # noqa: F401
    import chunker.image_chunker  # noqa: F401
    import chunker.table_chunker  # noqa: F401
    import chunker.text_chunker  # noqa: F401

    yield
    clear_emb_registry()
    clear_model_cache()
    clear_client_cache()
    clear_chunker_registry()


@pytest.fixture
def mock_store():
    """提供 MockVectorStore 实例."""
    from chunker.rag.vector_store import MockVectorStore

    return MockVectorStore()


@pytest.fixture
def mock_adapter():
    """提供 mock embedding 适配器（确定性哈希编码）."""
    import hashlib

    from chunker.embedding.base import EmbeddingAdapter

    class HashAdapter(EmbeddingAdapter):
        def __init__(self, dimension=8):
            super().__init__("hash", dimension=dimension, normalize=False)

        def _load_backend(self):
            return "hash"

        def _encode(self, texts, backend):
            d = self._declared_dim or 8
            results = []
            for text in texts:
                h = hashlib.sha256(text.encode("utf-8")).digest()
                vec = []
                for i in range(d):
                    byte_val = h[i % len(h)]
                    vec.append((byte_val / 255.0) * 2 - 1)  # [-1, 1]
                results.append(vec)
            return results

    return HashAdapter()
