"""Real Compose interpolation and Nginx/Docker DNS regression tests; no business data."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]


def docker(*args, env=None):
    return subprocess.check_output(["docker", *args], text=True, env=env, stderr=subprocess.PIPE).strip()


class GatewayEdgeTest(unittest.TestCase):
    def test_cookie_defaults_and_overrides_match_in_both_compose_files(self):
        # Explicit empty env file avoids reading local deployment credentials.
        (ROOT / "output").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="gateway-config-test-", dir=ROOT / "output") as directory:
            self.assertTrue(Path(directory).resolve().is_relative_to((ROOT / "output").resolve()))
            env_file = Path(directory) / "empty.env"
            env_file.write_text("")
            base_env = {k: v for k, v in os.environ.items() if not k.startswith("YLCLOUD_")}
            base_env.update(ACR_REGISTRY="registry.example.invalid", ACR_NAMESPACE="fixture")
            for filename, default in [("docker-compose.yml", "false"), ("docker-compose.hub.yml", "true")]:
                for override in [None, "true", "false"]:
                    with self.subTest(compose=filename, override=override):
                        env = dict(base_env)
                        if override is not None:
                            env["YLCLOUD_SESSION_COOKIE_SECURE"] = override
                        config = json.loads(docker("compose", "--env-file", str(env_file), "-f",
                                                   str(ROOT / "config" / filename), "config", "--format", "json", env=env))
                        expected = default if override is None else override
                        for service in ["gateway", "ylcloud-app"]:
                            self.assertEqual(expected, config["services"][service]["environment"]["YLCLOUD_SESSION_COOKIE_SECURE"])

    def test_nginx_refreshes_gateway_dns_and_preserves_request(self):
        suffix = uuid.uuid4().hex[:12]
        network, gateway, frontend, blocker = [f"gateway-edge-test-{suffix}-{x}" for x in ["net", "upstream", "frontend", "hold"]]
        # Synthetic HTTP upstream echoes the actual URI/method/body received through Nginx.
        server = """from http.server import BaseHTTPRequestHandler,HTTPServer
import json
class Handler(BaseHTTPRequestHandler):
 def do_GET(self):
  body=self.rfile.read(int(self.headers.get('Content-Length',0))).decode()
  data=json.dumps({'path':self.path,'method':self.command,'body':body}).encode()
  self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 do_POST=do_GET
HTTPServer(('0.0.0.0',8088),Handler).serve_forever()
"""
        def upstream():
            docker("run", "-d", "--name", gateway, "--network", network, "--network-alias", "gateway",
                   "python:3.12-slim", "python", "-u", "-c", server)

        def request():
            return json.loads(docker("exec", frontend, "wget", "-q", "-T", "12", "-O", "-",
                                     "--post-data", "fixture-body", "http://127.0.0.1/api/check?name=a%2Bb&other=x%26y"))

        def wait_response():
            deadline = time.monotonic() + 30
            while True:
                try:
                    return request()
                except subprocess.CalledProcessError:
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(1)

        try:
            docker("network", "create", network)
            upstream()
            docker("run", "-d", "--name", frontend, "--network", network,
                   "--mount", f"type=bind,source={ROOT / 'src/cloud-frontend/nginx.conf'},target=/etc/nginx/conf.d/default.conf,readonly",
                   "--mount", f"type=bind,source={ROOT / 'src/cloud-frontend/trusted-proxy.conf'},target=/etc/nginx/trusted-proxy.conf,readonly",
                   "nginx:1.27-alpine")
            docker("exec", frontend, "nginx", "-t")
            expected = {"path": "/api/check?name=a%2Bb&other=x%26y", "method": "POST", "body": "fixture-body"}
            self.assertEqual(expected, wait_response())
            old = json.loads(docker("inspect", gateway))[0]["NetworkSettings"]["Networks"][network]["IPAddress"]
            docker("rm", "-f", gateway)
            # Occupy only the old address in this disposable network, forcing a different new IP.
            docker("run", "-d", "--name", blocker, "--network", network, "--ip", old,
                   "python:3.12-slim", "python", "-c", "import time; time.sleep(120)")
            upstream()
            new = json.loads(docker("inspect", gateway))[0]["NetworkSettings"]["Networks"][network]["IPAddress"]
            self.assertNotEqual(old, new)
            self.assertEqual(expected, wait_response())  # Frontend is never reloaded or recreated.
        finally:
            for name in [frontend, gateway, blocker]:
                subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["docker", "network", "rm", network], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    unittest.main(verbosity=2)
