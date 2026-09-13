from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Protocol


class SecretProvider(Protocol):
    def get_secret(self, name: str) -> str: ...


@dataclass(frozen=True)
class MappingSecretProvider:
    """显式注入的 Secret 来源；不会把父进程完整环境复制给 Worker。"""

    values: Mapping[str, str]

    def get_secret(self, name: str) -> str:
        try:
            return self.values[name]
        except KeyError as exc:
            raise KeyError(f"required secret is unavailable: {name}") from exc
