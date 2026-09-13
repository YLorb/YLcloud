from __future__ import annotations

from typing import Any, Protocol


class LLMClientError(RuntimeError):
    """LLM 客户端配置或调用失败时使用的脱敏基础异常。"""


class LLMConfigurationError(LLMClientError):
    """LLM Provider、模型或凭证配置非法。"""


class LLMClient(Protocol):
    """Executor 依赖的最小 LLM 接口。

    顺序执行器只需要调用 generate(prompt)。真实模型、MockLLM、本地模型
    都可以实现这个协议，从而避免 Executor 绑定具体模型供应商。
    """

    def generate(self, prompt: str) -> Any:
        """根据 prompt 生成结果。"""
