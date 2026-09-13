from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from mini_agent_flow.llm.base import LLMConfigurationError
from mini_agent_flow.llm.deepseek import DeepSeekLLM, DeepSeekLLMError


class FakeCompletions:
    """记录请求并返回指定响应或异常。"""

    def __init__(self, content: Any = "真实模型响应", error: Exception | None = None) -> None:
        self.content = content
        self.error = error
        self.requests: list[dict[str, Any]] = []

    def create(self, **kwargs: Any) -> Any:
        self.requests.append(kwargs)
        if self.error:
            raise self.error
        message = SimpleNamespace(content=self.content)
        return SimpleNamespace(choices=[SimpleNamespace(message=message)])


def create_fake_client(completions: FakeCompletions) -> Any:
    """构造与 OpenAI SDK 调用形态一致的最小假客户端。"""

    return SimpleNamespace(chat=SimpleNamespace(completions=completions))


def test_deepseek_llm_uses_openai_compatible_request() -> None:
    """Client 应使用 V4、非流式请求并显式关闭思考模式。"""

    completions = FakeCompletions()
    llm = DeepSeekLLM(client=create_fake_client(completions))

    result = llm.generate("分析这个错误")

    assert result == "真实模型响应"
    assert completions.requests == [
        {
            "model": "deepseek-v4-flash",
            "messages": [{"role": "user", "content": "分析这个错误"}],
            "stream": False,
            "max_tokens": 1024,
            "extra_body": {"thinking": {"type": "disabled"}},
        }
    ]


def test_deepseek_llm_requires_environment_key(monkeypatch: pytest.MonkeyPatch) -> None:
    """生产 Client 不允许在缺少环境变量时发起请求。"""

    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)

    with pytest.raises(LLMConfigurationError, match="not configured"):
        DeepSeekLLM()


def test_deepseek_llm_rejects_unknown_model() -> None:
    """未知模型名应在网络请求之前被拒绝。"""

    with pytest.raises(LLMConfigurationError, match="unsupported DeepSeek model"):
        DeepSeekLLM(model="deepseek-chat", client=create_fake_client(FakeCompletions()))


def test_deepseek_llm_rejects_empty_response() -> None:
    """API 返回空 content 时不能把它当作成功节点输出。"""

    llm = DeepSeekLLM(client=create_fake_client(FakeCompletions(content=None)))

    with pytest.raises(DeepSeekLLMError, match="empty response"):
        llm.generate("hello")


def test_deepseek_llm_sanitizes_api_errors() -> None:
    """错误信息不得复制可能包含请求头或密钥的 SDK 原异常。"""

    error = RuntimeError("sensitive request headers")
    error.status_code = 401  # type: ignore[attr-defined]
    llm = DeepSeekLLM(client=create_fake_client(FakeCompletions(error=error)))

    with pytest.raises(DeepSeekLLMError, match="authentication failed") as caught:
        llm.generate("hello")

    assert "sensitive" not in str(caught.value)
