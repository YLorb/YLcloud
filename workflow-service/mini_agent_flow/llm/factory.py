from __future__ import annotations

from enum import Enum
from pathlib import Path

from dotenv import load_dotenv

from mini_agent_flow.llm.base import LLMClient, LLMConfigurationError
from mini_agent_flow.llm.deepseek import DEFAULT_DEEPSEEK_MODEL, DeepSeekLLM
from mini_agent_flow.llm.mock import MockLLM


class LLMProvider(str, Enum):
    """CLI 当前允许选择的 LLM Provider。"""

    mock = "mock"
    deepseek = "deepseek"


def create_llm(
    provider: LLMProvider,
    model: str = DEFAULT_DEEPSEEK_MODEL,
) -> LLMClient:
    """根据 Provider 创建 LLMClient，默认行为仍保持离线可复现。"""

    if provider == LLMProvider.mock:
        return MockLLM()
    if provider == LLMProvider.deepseek:
        load_dotenv(dotenv_path=Path.cwd() / ".env.local", override=False)
        return DeepSeekLLM(model=model)
    raise LLMConfigurationError(f"unsupported LLM provider: {provider}")
