from __future__ import annotations

import argparse
import hashlib
import os
import shutil
from pathlib import Path


SCHEMA_FILES = (
    "workflow.schema.json",
    "workflow-v2.schema.json",
    "workflow-run-contracts.schema.json",
    "service-jwt-claims.schema.json",
    "intent-plan.schema.json",
)
EXAMPLE_FILES = (
    "workflow-run-create.valid.json",
    "workflow-result.valid.json",
    "workflow-callback.valid.json",
    "workflow-delivery-ack.valid.json",
    "intent-plan-request.valid.json",
    "intent-plan-response.valid.json",
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _default_source() -> Path | None:
    configured = os.getenv("YLCLOUD_SCHEMA_ROOT")
    if configured:
        return Path(configured).expanduser().resolve()
    repository = Path(__file__).resolve().parents[1]
    candidates = (
        repository.parent / "ylcloud" / "schemas",
        repository.parent / "javaproj" / "ylcloud" / "schemas",
    )
    return next((candidate.resolve() for candidate in candidates if candidate.is_dir()), None)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="同步或校验由 YLcloud 权威目录生成的 Workflow Schema 快照。"
    )
    parser.add_argument("--source", type=Path, default=_default_source())
    parser.add_argument(
        "--target",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "schemas",
    )
    parser.add_argument(
        "--example-target",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "contract-examples",
    )
    parser.add_argument("--sync", action="store_true")
    args = parser.parse_args()

    if args.source is None or not args.source.is_dir():
        parser.error("找不到 ylcloud/schemas；请通过 --source 或 YLCLOUD_SCHEMA_ROOT 指定")

    failures: list[str] = []
    if args.sync:
        args.target.mkdir(parents=True, exist_ok=True)
        args.example_target.mkdir(parents=True, exist_ok=True)

    groups = (
        ("Schema", args.source, args.target, SCHEMA_FILES),
        ("契约样例", args.source / "examples", args.example_target, EXAMPLE_FILES),
    )
    for label, source_root, target_root, names in groups:
        for name in names:
            source = source_root / name
            target = target_root / name
            if not source.is_file():
                failures.append(f"权威{label}缺失: {source}")
                continue
            if source.is_symlink() or target.is_symlink():
                failures.append(f"拒绝符号链接{label}: {name}")
                continue
            if args.sync:
                # 只复制固定白名单文件，绝不接受来自契约内容的目标路径。
                shutil.copyfile(source, target)
                print(f"synced {name} {_sha256(source)}")
                continue
            if not target.is_file():
                failures.append(f"生成{label}缺失: {target}")
                continue
            if _sha256(source) != _sha256(target):
                failures.append(f"{label} 漂移: {name}")

    if failures:
        for failure in failures:
            print(failure)
        return 1
    print("YLcloud Schema 与契约样例快照均与权威源一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
