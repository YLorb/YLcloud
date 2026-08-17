from __future__ import annotations

import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile

request = json.loads(Path("/sandbox/request/request.json").read_text(encoding="utf-8"))
raw = base64.b64decode(request["contentBase64"], validate=True)
reasons: list[str] = []
file_name = str(request["fileName"])
if any(ord(char) < 32 for char in file_name) or "/" in file_name or "\\" in file_name: reasons.append("UNSAFE_FILE_NAME")
if len(raw) != int(request["size"]): reasons.append("SIZE_MISMATCH")
if hashlib.sha256(raw).hexdigest() != request["sha256"]: reasons.append("HASH_MISMATCH")
if raw.startswith((b"MZ", b"\x7fELF")): reasons.append("EXECUTABLE_CONTENT")
lowered = raw.lower()
if b"/javascript" in lowered or b"/launch" in lowered or b"/embeddedfile" in lowered:
    reasons.append("ACTIVE_PDF_CONTENT")
input_file = Path("/tmp/input.bin")
input_file.write_bytes(raw)
if zipfile.is_zipfile(input_file):
    try:
        with zipfile.ZipFile(input_file) as archive:
            total_uncompressed = 0
            for entry in archive.infolist():
                path = PurePosixPath(entry.filename.replace("\\", "/"))
                if path.is_absolute() or ".." in path.parts: reasons.append("ARCHIVE_PATH_TRAVERSAL"); break
                if entry.flag_bits & 0x1: reasons.append("ENCRYPTED_ARCHIVE"); break
                entry_name = entry.filename.lower()
                if entry_name.endswith(("vbaproject.bin", ".exe", ".dll", ".js", ".vbs", ".ps1", ".bat", ".cmd", ".sh")):
                    reasons.append("ACTIVE_ARCHIVE_CONTENT"); break
                total_uncompressed += entry.file_size
                if total_uncompressed > 1024 * 1024 * 1024 or (len(raw) and total_uncompressed > len(raw) * 200):
                    reasons.append("ARCHIVE_EXPANSION_LIMIT"); break
    except (OSError, zipfile.BadZipFile): reasons.append("INVALID_ARCHIVE")
Path("/sandbox/output/result.json").write_text(json.dumps({"safe": not reasons, "reasons": reasons}), encoding="utf-8")
