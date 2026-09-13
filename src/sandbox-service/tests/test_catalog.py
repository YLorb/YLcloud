import json

from sandbox_service.catalog import ToolCatalog


def test_catalog_accepts_utf8_bom_from_windows_tooling(tmp_path) -> None:
    path = tmp_path / "catalog.json"
    value = [{"name": "tool", "version": "1", "image": "sha256:" + "a" * 64,
              "entrypoint": ["/tool"], "input_schema": {}, "output_schema": {}}]
    path.write_text(json.dumps(value), encoding="utf-8-sig")
    assert ToolCatalog.from_json_file(path).get("tool", "1").name == "tool"
