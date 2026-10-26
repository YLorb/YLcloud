"""Bounded HTTP concurrency sweep. Counts successful resolutions, not just fast error responses.

Example: python scripts/short-link-load.py --base-url http://127.0.0.1:18090
  --path /api/preview-links/CODE --mode preview --concurrency 1,8,32,64 --seconds 10
Set SHORT_LINK_LOAD_COOKIE to an authorized browser session cookie (never saved in reports).
This is a closed-loop, single-machine observation, not a production capacity guarantee.
"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import http.client
import ipaddress
import json
import math
import os
from pathlib import Path
import platform
import threading
import time
from urllib.parse import urlsplit


def percentile(values, fraction):
    return round(values[max(0, math.ceil(len(values) * fraction) - 1)], 3) if values else None


def accepted(mode, status, headers, body):
    if mode == "preview":
        return status == 302 and headers.get("location", "").startswith("/api/")
    if mode == "share":
        try:
            payload = json.loads(body)
            return status == 200 and payload.get("code") == 200 and payload.get("data", {}).get("state") == "READY"
        except (ValueError, AttributeError, TypeError):
            return False
    return status == 502


def sweep(base, path, mode, concurrency, seconds, timeout, cookie):
    barrier = threading.Barrier(concurrency + 1)
    deadline = [0.0]

    def worker():
        statuses, latency, successful_latency = Counter(), [], []
        success, attempted = 0, 0
        connection = None
        barrier.wait()
        while time.perf_counter() < deadline[0]:
            before = time.perf_counter()
            attempted += 1
            try:
                if connection is None:
                    connection_type = http.client.HTTPSConnection if base.scheme == "https" else http.client.HTTPConnection
                    connection = connection_type(base.hostname, base.port, timeout=timeout)
                headers = {"Accept": "application/json", "Connection": "keep-alive"}
                if cookie:
                    headers["Cookie"] = cookie
                connection.request("POST" if mode == "share" else "GET", path, body=b"" if mode == "share" else None, headers=headers)
                response = connection.getresponse()
                body = response.read(1024 * 1024 + 1)
                response_headers = {k.lower(): v for k, v in response.getheaders()}
                good = len(body) <= 1024 * 1024 and accepted(mode, response.status, response_headers, body)
                statuses[str(response.status)] += 1
                if good:
                    success += 1
                    successful_latency.append((time.perf_counter() - before) * 1000)
                if response.will_close or len(body) > 1024 * 1024:
                    connection.close()
                    connection = None
            except (OSError, http.client.HTTPException):
                statuses["transport_error"] += 1
                if connection:
                    connection.close()
                connection = None
            latency.append((time.perf_counter() - before) * 1000)
        if connection:
            connection.close()
        return attempted, success, statuses, latency, successful_latency

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(worker) for _ in range(concurrency)]
        started = time.perf_counter()
        deadline[0] = started + seconds
        barrier.wait()
        results = [future.result() for future in futures]
        elapsed = time.perf_counter() - started
    statuses, latencies, successes_latency = Counter(), [], []
    attempted = sum(row[0] for row in results)
    success = sum(row[1] for row in results)
    for _, _, counts, latency, good_latency in results:
        statuses.update(counts)
        latencies.extend(latency)
        successes_latency.extend(good_latency)
    latencies.sort()
    successes_latency.sort()
    return {"concurrency": concurrency, "elapsed_seconds": round(elapsed, 3), "requests": attempted,
            "successful_requests": success, "success_rps": round(success / elapsed, 2),
            "total_rps": round(attempted / elapsed, 2), "failure_ratio": round(1 - success / attempted, 5) if attempted else 1,
            "status_counts": dict(statuses), "p50_ms": percentile(latencies, .5),
            "p95_ms": percentile(latencies, .95), "p99_ms": percentile(latencies, .99),
            "success_p95_ms": percentile(successes_latency, .95)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--path", required=True)
    parser.add_argument("--mode", choices=["preview", "share", "missing"], default="preview")
    parser.add_argument("--concurrency", default="1,8,32,64")
    parser.add_argument("--seconds", type=int, default=10)
    parser.add_argument("--warmup-seconds", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=3)
    parser.add_argument("--max-p95-ms", type=float, default=500)
    parser.add_argument("--max-failure-ratio", type=float, default=.01)
    parser.add_argument("--allow-remote", action="store_true")
    parser.add_argument("--output", default="output/short-link-load.json")
    args = parser.parse_args()
    base = urlsplit(args.base_url)
    if base.scheme not in ("http", "https") or not base.hostname or base.username or base.password or base.query:
        parser.error("base-url must be an HTTP origin without credentials")
    try:
        local = base.hostname == "localhost" or ipaddress.ip_address(base.hostname).is_loopback
    except ValueError:
        local = False
    if not local and not args.allow_remote:
        parser.error("remote load requires explicit --allow-remote; use a dedicated test environment")
    levels = [int(value) for value in args.concurrency.split(",")]
    if not levels or not all(1 <= value <= 256 for value in levels) or not 1 <= args.seconds <= 120:
        parser.error("concurrency must be 1..256; seconds must be 1..120")
    if not 0 <= args.warmup_seconds <= 30 or not .1 <= args.timeout <= 30:
        parser.error("warmup must be 0..30 seconds; timeout must be .1..30 seconds")
    if not args.path.startswith(("/p/", "/api/preview-links/", "/api/public/share-links/")) or "?" in args.path:
        parser.error("path must be a short-link endpoint without query credentials")
    cookie = os.environ.get("SHORT_LINK_LOAD_COOKIE", "")
    if args.warmup_seconds:
        sweep(base, args.path, args.mode, 1, args.warmup_seconds, args.timeout, cookie)
    rows = []
    for concurrency in levels:
        row = sweep(base, args.path, args.mode, concurrency, args.seconds, args.timeout, cookie)
        rows.append(row)
        print(json.dumps(row), flush=True)
    eligible = [row for row in rows if row["successful_requests"] and row["failure_ratio"] <= args.max_failure_ratio
                and row["p95_ms"] <= args.max_p95_ms]
    report = {"base_url": args.base_url, "path": args.path, "mode": args.mode,
              "generator": {"python": platform.python_version(), "platform": platform.platform(), "cpu_count": os.cpu_count()},
              "scope": "closed-loop HTTP; redirects are not followed; preview results measure resolution, not file transfer",
              "criteria": {"max_p95_ms": args.max_p95_ms, "max_failure_ratio": args.max_failure_ratio},
              "stages": rows,
              "observed_peak_success_rps": max((row["success_rps"] for row in eligible), default=None) if args.mode != "missing" else None,
              "limitation": "Single generator and server may share CPU. This is not a proven production maximum."}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Report: {output.resolve()}")


if __name__ == "__main__":
    main()
