"""主问数链路真实 Provider 内容级验证（#129 Mock 替换首刀）。

验证链路：nl2sql http 模式真实客户端代码路径（platform/nl2sql/sql_generator.py
OpenAiHttpSqlGenerator._chatCompletion）→ llm-gateway POST /v1/chat/completions →
真实 Provider（首刀为百炼 DashScope qianwen 适配器）。

前提（由运行方提供，凭据只走环境变量、零落库）：
  - llm-gateway 以真实 Provider 模式运行，例：
      LLM_GATEWAY_MOCK_MODE=false LLM_GATEWAY_PROVIDERS=qianwen \
      QIANWEN_API_KEY=<百炼 key>   # compose 经 DASHSCOPE_API_KEY 注入
  - VERIFY_GATEWAY_URL 指向该网关（默认 http://localhost:18085，即 compose 映射；
    本机原生运行网关时用 http://localhost:8084）

运行：
  pytest tests/integration/e2e/verify_real_provider_chain.py -v -s

跳过语义：DASHSCOPE_API_KEY 未注入，或 nl2sql 运行依赖不可用时，整文件 skip
（CI 与无凭据机零影响；本文件不替代现有 mock 基线单测）。

诚实失败设计：网关侧 key 无效/上游拒鉴时，_chatCompletion 抛 LlmCallError，
本脚本如实失败，绝不静默降级 mock、绝不伪造成功。

变异反证（手动腿，不在本文件内）：以错误 QIANWEN_API_KEY 启动同一网关后，
本验证的同一请求应当失败且错误带鉴权语义（非 mock 回声）；该腿已在 2026-11-10
首刀本机执行并留痕于板面 DONE 行。
"""

from __future__ import annotations

import asyncio
import os
import sys

# httpx 在 AsyncClient 构造期解析 NO_PROXY；带方括号的 IPv6 条目（如 [::1]）
# 会被拼成 all://*[::1]，触发 InvalidURL: Invalid port ':1]'（httpx 已知缺陷）。
# 本机环境常带此条目，导入业务代码前剔除，保证验证脚本在任意机器可跑。
for _np_key in ("NO_PROXY", "no_proxy"):
    _np_val = os.environ.get(_np_key, "")
    if _np_val:
        os.environ[_np_key] = ",".join(
            p for p in _np_val.split(",") if not (p.strip().startswith("[") and "]" in p)
        )

import pytest

MARKER = "BDP-REAL-PROVIDER-CHECK"

GATEWAY_URL = os.environ.get("VERIFY_GATEWAY_URL", "http://localhost:18085").rstrip("/")
LLM_MODEL = os.environ.get("VERIFY_LLM_MODEL", "qwen-plus")

NL2SQL_DIR = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..", "platform", "nl2sql")
)

sys.path.insert(0, NL2SQL_DIR)
try:
    from config.settings import Settings
    from sql_generator import OpenAiHttpSqlGenerator
except Exception as exc:  # noqa: BLE001 - 依赖缺失即跳过，不红 CI
    IMPORT_ERROR = str(exc)
else:
    IMPORT_ERROR = None

pytestmark = [
    pytest.mark.skipif(
        not os.environ.get("DASHSCOPE_API_KEY"),
        reason="DASHSCOPE_API_KEY 未注入：真实 Provider 内容级验证跳过（凭据只走 env，零落库）",
    ),
    pytest.mark.skipif(
        IMPORT_ERROR is not None,
        reason=f"nl2sql 运行依赖不可用：{IMPORT_ERROR}",
    ),
]


def test_nl2sql_http_mode_real_provider_content():
    """nl2sql 真实 http 客户端 → 网关 → DashScope：回复含标记串、非 mock 回声。"""
    settings = Settings(
        llmMode="http",
        llmGatewayUrl=GATEWAY_URL,
        llmApiKey="local-dev-bearer",  # 网关 JWT_DEV_MODE 下不做入站鉴权；占位非空即可
        llmModel=LLM_MODEL,
        llmTimeout=60.0,
    )
    assert settings.isHttpLlm

    gen = OpenAiHttpSqlGenerator(settings, None)

    async def call_and_close() -> str:
        # 同一事件循环内完成调用与关闭；两个 asyncio.run 共用一个
        # httpx.AsyncClient 会在 aclose 时报 "Event loop is closed"。
        try:
            return await gen._chatCompletion(
                "你是一个严格按指令输出的助手。",
                f"请原样输出标记串 {MARKER}，不要输出其他内容。",
            )
        finally:
            await gen.aclose()

    content = asyncio.run(call_and_close())

    print(f"\n[verify_real_provider_chain] 模型={LLM_MODEL} 网关={GATEWAY_URL}")
    print(f"[verify_real_provider_chain] 回复前 120 字: {content[:120]!r}")

    assert isinstance(content, str) and content.strip(), "回复为空"
    # 反 mock 判别式：mock 回复恒为 "[mock:<模型>] echo: <用户原文摘要>"。
    assert "[mock:" not in content, "回复命中 mock 格式签名，链路未走真实 Provider"
    assert "请原样输出标记串" not in content, "回复是用户原文回声（mock 行为），非模型生成"
    # 内容级正证：模型真实遵循了指令，输出仅凭 mock 回声无法构造的标记串。
    assert MARKER in content, "回复不含内容标记串，未证明真实 Provider 内容"
