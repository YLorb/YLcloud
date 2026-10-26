"""Checks the real missing-code policy on the dedicated local load fixture. Run AFTER load tests."""
import argparse
import http.client
import json
from pathlib import Path


def post(path, headers=None):
    connection = http.client.HTTPConnection("127.0.0.1", 18090, timeout=5)
    try:
        connection.request("POST", path, body=b"", headers=headers or {})
        response = connection.getresponse()
        result = {"status": response.status, "retry_after": response.getheader("Retry-After")}
        response.read()
        return result
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", default="output/short-link-load-final/fixture.json")
    args = parser.parse_args()
    fixture_path = Path(args.fixture)
    fixture = json.loads(fixture_path.read_text())
    missing = [post("/api/public/share-links/Missing999/open") for _ in range(10)]
    assert all(row["status"] == 502 for row in missing), missing
    assert all(row["retry_after"] is None for row in missing[:9]), missing
    assert int(missing[9]["retry_after"]) > 0, missing
    valid_after_ban = post(fixture["paths"]["share"])
    forged_forwarded_ip = post(fixture["paths"]["share"], {"X-Forwarded-For": "198.51.100.100"})
    assert valid_after_ban["status"] == 502 and forged_forwarded_ip["status"] == 502
    report = {"missing_requests": missing, "valid_code_after_ban": valid_after_ban,
              "untrusted_forwarded_header_after_ban": forged_forwarded_ip,
              "result": "PASS", "scope": "only the dedicated local short-link access endpoints"}
    (fixture_path.parent / "http-policy.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("PASS: missing requests reach threshold; same IP is blocked; forged forwarding header cannot bypass it")


if __name__ == "__main__":
    main()
