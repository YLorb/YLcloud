from __future__ import annotations

from typing import Any, Protocol


class LLMClient(Protocol):
    """Executor 依赖的最小 LLM 接口。

    顺序执行器只需要调用 generate(prompt)。真实模型、MockLLM、本地模型
    都可以实现这个协议，从而避免 Executor 绑定具体模型供应商。
    """

    def generate(self, prompt: str) -> Any:
        """根据 prompt 生成结果。"""

