from __future__ import annotations

import os
from typing import Any

from mini_agent_flow.llm.base import LLMClientError, LLMConfigurationError


DEEPSEEK_BASE_URL = "https://api.deepseek.com"
DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"
SUPPORTED_DEEPSEEK_MODELS = {"deepseek-v4-flash", "deepseek-v4-pro"}


class DeepSeekLLMError(LLMClientError):
    """DeepSeek API 调用失败时抛出的脱敏异常。"""


class DeepSeekLLM:
    """使用 OpenAI 兼容接口调用 DeepSeek 的同步 LLMClient。"""

    def __init__(
        self,
        model: str = DEFAULT_DEEPSEEK_MODEL,
        timeout_seconds: float = 60,
        max_tokens: int = 1024,
        client: Any | None = None,
    ) -> None:
        """创建客户端；生产调用只从环境变量读取 API Key。"""

        if model not in SUPPORTED_DEEPSEEK_MODELS:
            supported = ", ".join(sorted(SUPPORTED_DEEPSEEK_MODELS))
            raise LLMConfigurationError(f"unsupported DeepSeek model; expected one of: {supported}")
        if timeout_seconds <= 0:
            raise LLMConfigurationError("LLM timeout_seconds must be greater than 0")
        if not 1 <= max_tokens <= 8192:
            raise LLMConfigurationError("LLM max_tokens must be between 1 and 8192")

        self.model = model
        self.max_tokens = max_tokens
        self._client = client or self._create_client(timeout_seconds)

    def generate(self, prompt: str) -> str:
        """调用非流式 Chat Completions，并只返回最终文本。"""

        if not isinstance(prompt, str) or not prompt.strip():
            raise DeepSeekLLMError("LLM prompt must be a non-empty string")

        try:
            response = self._client.chat.completions.create(
                model=self.model,
                messages=[{"role": "user", "content": prompt}],
                stream=False,
                max_tokens=self.max_tokens,
                extra_body={"thinking": {"type": "disabled"}},
            )
            content = response.choices[0].message.content
        except Exception as exc:
            raise DeepSeekLLMError(self._safe_error_message(exc)) from exc

        if not isinstance(content, str) or not content.strip():
            raise DeepSeekLLMError("DeepSeek API returned an empty response")
        return content

    def _create_client(self, timeout_seconds: float) -> Any:
        """延迟导入 OpenAI SDK，并创建不做 SDK 内部重试的客户端。"""

        api_key = os.getenv("DEEPSEEK_API_KEY")
        if not api_key or not api_key.strip():
            raise LLMConfigurationError("DEEPSEEK_API_KEY is not configured")

        try:
            from openai import OpenAI
        except ImportError as exc:
            raise LLMConfigurationError("openai package is not installed") from exc

        return OpenAI(
            api_key=api_key,
            base_url=DEEPSEEK_BASE_URL,
            timeout=timeout_seconds,
            max_retries=0,
        )

    def _safe_error_message(self, exc: Exception) -> str:
        """按 HTTP 状态码返回稳定错误，不复制可能含敏感数据的原异常。"""

        status_code = getattr(exc, "status_code", None)
        messages = {
            400: "DeepSeek API rejected the request format",
            401: "DeepSeek API authentication failed",
            402: "DeepSeek API account balance is insufficient",
            422: "DeepSeek API rejected the request parameters",
            429: "DeepSeek API rate limit was reached",
            500: "DeepSeek API encountered a server error",
            503: "DeepSeek API is temporarily overloaded",
        }
        return messages.get(status_code, "DeepSeek API request failed")
