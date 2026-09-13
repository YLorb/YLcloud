from __future__ import annotations

from typing import Any


class MockLLM:
    """本地假 LLM。

    MockLLM 不联网、不需要 API key，只提供稳定输出，方便先验证 Workflow
    Engine 的执行链路和测试行为。
    """

    def generate(self, prompt: str) -> Any:
        """根据 prompt 返回可预测的 mock 结果。"""

        if "搜索关键词" in prompt or "关键词" in prompt:
            return ["AI Agent", "Workflow Engine", "Tool Calling"]
        return f"Mock response: {prompt}"

