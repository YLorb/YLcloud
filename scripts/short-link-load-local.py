"""Seed synthetic fixtures and load a dedicated local full-backend instance.

Requires the disposable ylcloud-short-link-load-mysql container (short_link_test schema),
the backend at 127.0.0.1:18090, and its loopback-only metrics port 19081.
Only synthetic test data is inserted; no production credentials or cookies are saved.
Run after packaging the backend and starting the isolated containers described in the test report.
"""
import argparse
import http.client
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def request(method, path, data=None, cookie="", port=18090):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
    try:
        headers = {"Content-Type": "application/json", "X-YLCloud-Request": "1"}
        if cookie:
            headers["Cookie"] = cookie
        connection.request(method, path, body=None if data is None else json.dumps(data), headers=headers)
        response = connection.getresponse()
        body = response.read()
        return response.status, dict(response.getheaders()), json.loads(body) if body else None
    finally:
        connection.close()


def checked(method, path, data=None, cookie=""):
    status, headers, body = request(method, path, data, cookie)
    if status != 200 or body.get("code") != 200:
        raise RuntimeError(f"Fixture request failed: {method} {path} HTTP {status}: {body.get('message')}")
    return headers, body.get("data")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--concurrency", default="1,8,32,64")
    parser.add_argument("--seconds", type=int, default=10)
    parser.add_argument("--output-dir", default="output/short-link-load")
    args = parser.parse_args()
    output = ROOT / args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    suffix = uuid.uuid4().hex[:10]
    username, password = "shortload_" + suffix, "test-only-" + uuid.uuid4().hex
    checked("POST", "/api/sign", {"username": username, "nickname": "Short link load fixture", "password": password})
    headers, _ = checked("POST", "/api/login", {"username": username, "password": password})
    cookie = next(value for key, value in headers.items() if key.lower() == "set-cookie").split(";", 1)[0]
    _, current = checked("GET", "/api/session", cookie=cookie)
    user_id = int(current["id"])
    root_id = int(subprocess.check_output(["docker", "exec", "-e", "MYSQL_PWD=short-link-test-only",
                    "ylcloud-short-link-load-mysql", "mysql", "-N", "-uroot", "short_link_test", "-e",
                    f"SELECT root_id FROM users WHERE user_id={user_id}"], text=True).strip())
    file_id = int(time.time() * 1000)
    file_uuid = "shortload-" + suffix
    sql = (f"INSERT INTO file_info(file_uuid,name,type,size) VALUES('{file_uuid}','load.png','image/png',16);"
           f"INSERT INTO user_file(id,file_name,file_uuid,is_dir,user_id,parent_id) VALUES({file_id},'load.png','{file_uuid}',0,{user_id},{root_id}),"
           f"({file_id + 1},'load-folder',NULL,1,{user_id},{root_id});")
    subprocess.run(["docker", "exec", "-e", "MYSQL_PWD=short-link-test-only", "ylcloud-short-link-load-mysql",
                    "mysql", "-uroot", "short_link_test", "-e", sql], check=True, capture_output=True)
    _, preview = checked("GET", f"/api/file/preview/{file_uuid}?parentId={root_id}", cookie=cookie)
    _, share = checked("POST", "/api/share-links", {"sourceType": "PERSONAL", "sourceId": file_id + 1,
                         "conditions": {"expiryMode": "PERMANENT", "passwordEnabled": False, "forceDownload": False}}, cookie)
    paths = {"preview": "/api/preview-links/" + preview["previewUrl"].split("/")[-1],
             "share": "/api/public/share-links/" + share["shortCode"] + "/open"}
    env = dict(os.environ, SHORT_LINK_LOAD_COOKIE=cookie)
    for mode, path in paths.items():
        subprocess.run([sys.executable, str(ROOT / "scripts/short-link-load.py"), "--base-url", "http://127.0.0.1:18090",
                        "--path", path, "--mode", mode, "--concurrency", args.concurrency, "--seconds", str(args.seconds),
                        "--output", str(output / (mode + ".json"))], env=env, check=True)
    time.sleep(2)
    metrics = {}
    names = ["short.link.cache", "short.link.resolve.database", "short.link.resolve.overloaded",
             "short.link.log.queued", "short.link.log.written", "short.link.log.dropped", "short.link.generation",
             "short.link.node.lease.active", "hikaricp.connections.active", "process.cpu.usage", "jvm.memory.used"]
    for name in names:
        status, _, body = request("GET", "/actuator/metrics/" + name, port=19081)
        if status == 200:
            metrics[name] = body
    for tag in ("hit", "miss", "error"):
        status, _, body = request("GET", "/actuator/metrics/short.link.cache?tag=result:" + tag, port=19081)
        if status == 200:
            metrics["cache." + tag] = body
    (output / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    (output / "fixture.json").write_text(json.dumps({"paths": paths, "source": "synthetic local data",
                "boundary": "Full backend with real session authorization, MySQL, Redis and async access logs; gateway and file transfer excluded"}, indent=2), encoding="utf-8")
    print(f"Fixtures and reports: {output.resolve()}")


if __name__ == "__main__":
    main()
