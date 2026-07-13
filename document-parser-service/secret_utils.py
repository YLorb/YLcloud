import os
from pathlib import Path


def read_secret(*names: str) -> str | None:
    """Read NAME_FILE first, then NAME, returning the first non-empty value."""
    for name in names:
        file_name = os.getenv(f"{name}_FILE")
        if file_name:
            try:
                value = Path(file_name).read_text(encoding="utf-8").strip()
            except OSError as exc:
                raise RuntimeError(f"Unable to read secret file for {name}") from exc
            if value:
                return value
        value = os.getenv(name)
        if value and value.strip():
            return value.strip()
    return None
