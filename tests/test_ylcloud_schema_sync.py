from __future__ import annotations

import subprocess
import sys
from pathlib import Path


SCRIPT = Path("scripts/check_ylcloud_schemas.py").resolve()
SCHEMAS = (
    "workflow.schema.json",
    "workflow-v2.schema.json",
    "workflow-run-contracts.schema.json",
    "service-jwt-claims.schema.json",
    "intent-plan.schema.json",
)
EXAMPLES = (
    "workflow-run-create.valid.json",
    "workflow-result.valid.json",
    "workflow-callback.valid.json",
    "workflow-delivery-ack.valid.json",
    "intent-plan-request.valid.json",
    "intent-plan-response.valid.json",
)


def _run(source: Path, target: Path, example_target: Path, *, sync: bool) -> subprocess.CompletedProcess[str]:
    command = [
        sys.executable,
        str(SCRIPT),
        "--source",
        str(source),
        "--target",
        str(target),
        "--example-target",
        str(example_target),
    ]
    if sync:
        command.append("--sync")
    return subprocess.run(command, text=True, capture_output=True, check=False)


def test_sync_copies_allowlisted_contracts_and_check_detects_drift(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source_examples = source / "examples"
    target = tmp_path / "target"
    target_examples = tmp_path / "target-examples"
    source_examples.mkdir(parents=True)

    for name in SCHEMAS:
        (source / name).write_text(f'{{"name":"{name}"}}\n', encoding="utf-8")
    for name in EXAMPLES:
        (source_examples / name).write_text(f'{{"name":"{name}"}}\n', encoding="utf-8")

    synced = _run(source, target, target_examples, sync=True)
    assert synced.returncode == 0, synced.stdout + synced.stderr
    checked = _run(source, target, target_examples, sync=False)
    assert checked.returncode == 0, checked.stdout + checked.stderr

    (target / SCHEMAS[0]).write_text("{}\n", encoding="utf-8")
    drifted = _run(source, target, target_examples, sync=False)
    assert drifted.returncode == 1
    assert f"Schema 漂移: {SCHEMAS[0]}" in drifted.stdout


def test_check_mode_does_not_create_missing_targets(tmp_path: Path) -> None:
    source = tmp_path / "source"
    (source / "examples").mkdir(parents=True)
    for name in SCHEMAS:
        (source / name).write_text("{}\n", encoding="utf-8")
    for name in EXAMPLES:
        (source / "examples" / name).write_text("{}\n", encoding="utf-8")

    target = tmp_path / "missing-target"
    example_target = tmp_path / "missing-examples"
    result = _run(source, target, example_target, sync=False)

    assert result.returncode == 1
    assert not target.exists()
    assert not example_target.exists()
