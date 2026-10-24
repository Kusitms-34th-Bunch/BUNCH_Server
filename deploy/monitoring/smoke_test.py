#!/usr/bin/env python3
"""Real monitoring smoke test (~5 minutes). Requires Python 3 and Docker Compose.

Uses production provisioning/timings and isolated storage; only the Prometheus
scrape target is replaced. Discord requests terminate at a local HTTP stub.
Alloy collection and the application's metrics/log production are tested separately.
"""

import base64
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import ProxyHandler, Request, build_opener
import uuid


ROOT = Path(__file__).resolve().parent
HTTP = build_opener(ProxyHandler({}))


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def matches(actual, expected):
    # Grafana adds query defaults when loading file provisioning.
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(
            key in actual and matches(actual[key], value) for key, value in expected.items())
    if isinstance(expected, list):
        return isinstance(actual, list) and len(actual) == len(expected) and all(
            matches(left, right) for left, right in zip(actual, expected))
    return actual == expected


def wait_for(description, check, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (HTTPError, URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(1)
    raise AssertionError("Timed out: " + description)


def request(url, payload=None, auth=None):
    headers = {}
    if payload is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(payload).encode()
    if auth:
        headers["Authorization"] = "Basic " + base64.b64encode(auth.encode()).decode()
    with HTTP.open(Request(url, data=payload, headers=headers), timeout=5) as response:
        body = response.read()
    return json.loads(body) if body and body[:1] in (b"{", b"[") else body.decode()


class Stub(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        with self.server.lock:
            healthy = self.server.metrics_up
        if self.path != "/actuator/prometheus":
            self.send_error(404)
            return
        body = b"# TYPE smoke_requests_total counter\nsmoke_requests_total 0\n"
        self.send_response(200 if healthy else 503)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path.split("?", 1)[0] != "/api/webhooks/smoke/local":
            self.send_error(404)
            return
        payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        with self.server.lock:
            status = self.server.discord_status
            self.server.deliveries.append((time.monotonic(), status, payload))
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()


def docker(*args, env=None):
    result = subprocess.run(
        ["docker", *args], capture_output=True, text=True, timeout=180, env=env,
    )
    # Never echo command arguments, Docker output, webhook bodies or credentials.
    require(result.returncode == 0, "Docker operation failed: " + args[0])
    return result.stdout.strip()


def main():
    run_id = "bunch-smoke-" + uuid.uuid4().hex[:12]
    label = "bunch.monitoring.smoke=" + run_id
    containers, volumes = [], []
    network_created = False
    stub = ThreadingHTTPServer(("0.0.0.0", 0), Stub)
    stub.lock = threading.Lock()
    stub.metrics_up = True
    stub.discord_status = 204
    stub.deliveries = []
    threading.Thread(target=stub.serve_forever, daemon=True).start()

    # Preserve Docker connectivity, but never let shell variables override the
    # env file (especially image references or notification settings).
    compose_env = {key: value for key, value in os.environ.items() if key in {
        "PATH", "HOME", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG",
        "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "XDG_RUNTIME_DIR",
    }}
    try:
        with tempfile.TemporaryDirectory(prefix=run_id + "-") as directory:
            fixture = Path(directory)
            fixture.chmod(0o755)
            shutil.copytree(ROOT / "grafana", fixture / "grafana")
            alerting = fixture / "grafana/provisioning/alerting"
            require({path.name for path in alerting.iterdir()} == {"rules.json", "notifications.yaml"},
                    "Unexpected alert provisioning file; review notification destinations first")
            notification_urls = re.findall(
                r"^\s*url:\s*(.*?)\s*$", (alerting / "notifications.yaml").read_text(), re.MULTILINE,
            )
            require(notification_urls == ["${DISCORD_WEBHOOK_URL}"] * 2,
                    "Both Discord destinations must use the local-only injected webhook")
            shutil.copyfile(ROOT / "loki.yaml", fixture / "loki.yaml")
            target = "host.docker.internal:" + str(stub.server_port)
            prometheus, replacements = re.subn(
                r"(?<![\w.-])app:8081(?![\w.-])", target,
                (ROOT / "prometheus.yaml").read_text(),
            )
            require(replacements == 1, "Expected exactly one production scrape target app:8081")
            (fixture / "prometheus.yaml").write_text(prometheus)
            production = json.loads((fixture / "grafana/provisioning/alerting/rules.json").read_text())
            require(len(production["groups"]) == 1, "Expected one production alert group")
            group = production["groups"][0]
            rules = {rule["uid"]: rule for rule in group["rules"]}
            require(set(rules) == {"bunch-http-5xx", "bunch-app-down"}, "Unexpected rule UIDs")
            require(group["interval"] == "10s", "Production evaluation must be 10s")
            require(rules["bunch-http-5xx"]["for"] == "0s", "5xx must fire immediately")
            require(rules["bunch-app-down"]["for"] == "1m", "App down must require 1m")
            expr = rules["bunch-http-5xx"]["data"][0]["model"]["expr"]
            require("[1m]" in expr and "vector(0)" in expr, "Production 5xx window/fallback changed")

            images = {}
            for service in ("loki", "prometheus", "grafana"):
                images[service] = docker(
                    "compose", "--project-name", run_id, "--env-file", str(ROOT.parent / ".env"),
                    "-f", str(ROOT.parent / "compose.yaml"), "config", "--images", service,
                    env=compose_env,
                )
                require(re.fullmatch(r"[\w./-]+:[\w.-]+", images[service]) is not None
                        and not images[service].endswith(":latest"), "Monitoring images must use fixed tags")

            docker("network", "create", "--label", label, run_id)
            network_created = True
            urls = {}

            def start(service, port, mounts, arguments=(), environment=()):
                name = run_id + "-" + service
                volume = name + "-data"
                docker("volume", "create", "--label", label, volume)
                volumes.append(volume)
                storage = {"loki": "/loki", "prometheus": "/prometheus", "grafana": "/var/lib/grafana"}
                args = ["run", "-d", "--name", name, "--label", label, "--network", run_id,
                        "--network-alias", service, "--add-host", "host.docker.internal:host-gateway",
                        "-p", "127.0.0.1::" + str(port), "-v", volume + ":" + storage[service]]
                for source, destination in mounts:
                    args += ["-v", str(fixture / source) + ":" + destination + ":ro"]
                for value in environment:
                    args += ["-e", value]
                containers.append(name)  # Also clean up if Docker times out after creation.
                docker(*args, images[service], *arguments)
                binding = docker("port", name, str(port) + "/tcp")
                require(binding.startswith("127.0.0.1:"), "Test ports must bind localhost only")
                urls[service] = "http://" + binding

            print("Starting isolated production Loki, Prometheus and Grafana...", flush=True)
            start("loki", 3100, [("loki.yaml", "/etc/loki/config.yaml")],
                  ["-config.file=/etc/loki/config.yaml"])
            start("prometheus", 9090, [("prometheus.yaml", "/etc/prometheus/prometheus.yaml")],
                  ["--config.file=/etc/prometheus/prometheus.yaml"])
            wait_for("Loki ready", lambda: request(urls["loki"] + "/ready"), 120)
            wait_for("Prometheus ready", lambda: request(urls["prometheus"] + "/-/ready"))
            password = uuid.uuid4().hex
            webhook = "http://" + target + "/api/webhooks/smoke/local"
            start("grafana", 3000, [
                ("grafana/provisioning", "/etc/grafana/provisioning"),
                ("grafana/dashboards", "/var/lib/grafana/dashboards"),
            ], environment=[
                "GF_SECURITY_ADMIN_USER=smoke", "GF_SECURITY_ADMIN_PASSWORD=" + password,
                "GF_SERVER_ROOT_URL=http://localhost:3000/", "MONITORING_ENVIRONMENT=smoke",
                "DISCORD_WEBHOOK_URL=" + webhook, "GF_USERS_ALLOW_SIGN_UP=false",
                "GF_AUTH_ANONYMOUS_ENABLED=false", "GF_ANALYTICS_REPORTING_ENABLED=false",
                "GF_ANALYTICS_CHECK_FOR_UPDATES=false", "GF_UNIFIED_ALERTING_MIN_INTERVAL=10s",
            ])

            def grafana(path):
                return request(urls["grafana"] + path, auth="smoke:" + password)

            def prom_value():
                result = request(urls["prometheus"] + "/api/v1/query?" + urlencode({"query": 'up{job="bunch"}'}))
                values = result["data"]["result"]
                return float(values[0]["value"][1]) if values else None

            def log_count():
                result = request(urls["loki"] + "/loki/api/v1/query?" + urlencode({"query": expr}))
                return float(result["data"]["result"][0]["value"][1])

            def push(*lines):
                now = time.time_ns()
                request(urls["loki"] + "/loki/api/v1/push", {"streams": [{
                    "stream": {"service": "bunch", "environment": "smoke"},
                    "values": [[str(now + index), line] for index, line in enumerate(lines)],
                }]})

            def status(uid):
                groups = grafana("/api/prometheus/grafana/api/v1/rules")["data"]["groups"]
                for loaded_group in groups:
                    for rule in loaded_group["rules"]:
                        if rule["name"] == rules[uid]["title"]:
                            require(loaded_group["interval"] == 10, "Loaded evaluation interval changed")
                            require(rule.get("health") == "ok", "Rule evaluation unhealthy: " + uid)
                            return rule
                raise AssertionError("Evaluated rule missing: " + uid)

            def deliveries(title=None, successful=None):
                with stub.lock:
                    result = list(stub.deliveries)
                return [item for item in result
                        if (title is None or title in json.dumps(item[2], ensure_ascii=False))
                        and (successful is None or (item[1] == 204) == successful)]

            def observe_normal(seconds):
                deadline = time.monotonic() + seconds
                evaluations = set()
                while time.monotonic() < deadline:
                    for uid in rules:
                        current = status(uid)
                        require(current["state"] == "inactive", "Unexpected alert state: " + uid)
                    evaluations.add(status("bunch-http-5xx")["lastEvaluation"])
                    require(not deliveries(), "Unexpected Discord delivery during normal/no traffic")
                    time.sleep(2)
                require(len(evaluations) >= 3, "Must observe at least two 10s evaluation transitions")

            wait_for("Grafana ready", lambda: grafana("/api/health"), 120)
            wait_for("healthy real Prometheus scrape", lambda: prom_value() == 1)
            loaded = grafana("/api/v1/provisioning/alert-rules")
            require({rule["uid"] for rule in loaded} == set(rules), "Loaded rule UIDs differ")
            for rule in loaded:
                source = rules[rule["uid"]]
                for key in ("condition", "for", "noDataState", "execErrState", "data"):
                    require(matches(rule[key], source[key]), "Loaded rule differs: " + rule["uid"] + "/" + key)
            contacts = grafana("/api/v1/provisioning/contact-points")
            contacts = {contact["name"]: contact for contact in contacts}
            require(set(contacts) == {"bunch-5xx", "bunch-app-down"}, "Unexpected contact points")
            for name, disable_resolve in (("bunch-5xx", True), ("bunch-app-down", False)):
                require(contacts[name]["type"] == "discord"
                        and contacts[name]["disableResolveMessage"] is disable_resolve,
                        "Discord resolve configuration differs: " + name)
            policy = grafana("/api/v1/provisioning/policies")
            require(policy["group_wait"] == "0s" and policy["group_interval"] == "10s"
                    and policy["repeat_interval"] == "5m", "Production notification timing differs")
            require(policy["group_by"] == ["service", "environment", "alertname"], "Grouping differs")
            for kind, name in (("http5xx", "bunch-5xx"), ("appdown", "bunch-app-down")):
                require(any(route["receiver"] == name
                            and ["monitoring_kind", "=", kind] in route.get("object_matchers", [])
                            for route in policy["routes"]), "Missing policy route: " + kind)
            for uid in ("loki", "prometheus"):
                require(grafana("/api/datasources/uid/" + uid)["uid"] == uid, "Datasource UID differs")
            require(grafana("/api/dashboards/uid/bunch")["dashboard"]["uid"] == "bunch", "Dashboard missing")
            print("PASS loaded production rules, contacts, policies, datasources and dashboard", flush=True)

            wait_for("initial normal evaluations", lambda: all(status(uid)["state"] == "inactive" for uid in rules))
            observe_normal(24)
            push("INFO [REQ END] GET /smoke status=200 duration=1ms",
                 "WARN [REQ END] GET /smoke status=404 duration=1ms",
                 "WARN [REQ END] GET /smoke status=429 duration=1ms",
                 "ERROR unrelated status=500", "INFO [REQ START] GET /smoke status=500",
                 "INFO [REQ END] GET /smoke status=5000")
            require(log_count() == 0, "Normal/4xx/non-request logs counted as 5xx")
            observe_normal(24)
            print("PASS no traffic, normal, 4xx and unrelated ERROR logs produce no alerts", flush=True)

            first = time.monotonic()
            push("WARN [REQ END] GET /smoke status=503 duration=1ms")
            wait_for("first single 5xx visible in Loki", lambda: log_count() == 1, 15)
            title = rules["bunch-http-5xx"]["title"]
            sent = wait_for("single first 5xx Discord delivery within a few evaluations",
                            lambda: deliveries(title, True), 35)
            require(len(sent) == 1 and sent[0][0] - first < 35, "Single 5xx delivery missing/duplicated")
            message = json.dumps(sent[0][2], ensure_ascii=False)
            require("firing" in message and "최근 1분 5xx 건수: 1" in message
                    and "smoke" in message and "/d/bunch/bunch" in message,
                    "First 5xx notification content differs")
            push("ERROR [REQ END] GET /smoke status=500 duration=1ms")
            wait_for("two actual Loki failures", lambda: log_count() == 2, 10)
            evaluations = set()
            deadline = time.monotonic() + 24
            while time.monotonic() < deadline:
                current = status("bunch-http-5xx")
                require(current["state"] == "firing", "5xx should remain firing")
                evaluations.add(current["lastEvaluation"])
                require(len(deliveries(title)) == 1, "Count 1 -> 2 resent every evaluation")
                time.sleep(2)
            require(len(evaluations) >= 3, "Duplicate suppression needs >=2 evaluation transitions")
            print("PASS first single 5xx delivered; count 1 -> 2 suppressed across >=2 evaluations", flush=True)
            wait_for("5xx window expired", lambda: log_count() == 0, 80)
            wait_for("5xx returned normal", lambda: status("bunch-http-5xx")["state"] == "inactive", 30)
            time.sleep(12)
            require(len(deliveries(title)) == 1, "5xx sent an unwanted resolved message")
            print("PASS quiet 5xx recovery sends no resolved notification", flush=True)

            # A real failed scrape drives the production 1m rule. Fail delivery
            # locally, then recover the receiver and finally the scrape target.
            with stub.lock:
                stub.metrics_up = False
                stub.discord_status = 500
            wait_for("real Prometheus up=0", lambda: prom_value() == 0, 35)
            down_title = rules["bunch-app-down"]["title"]
            wait_for("app-down pending", lambda: status("bunch-app-down")["state"] == "pending", 25)
            pending_since = time.monotonic()
            while time.monotonic() - pending_since < 50:
                require(not deliveries(down_title), "App down sent before its 1m pending period")
                time.sleep(2)
            failed = wait_for("local Discord HTTP 500 failure", lambda: deliveries(down_title, False), 55)
            require(failed[0][0] - pending_since >= 55, "App down did not preserve production 1m pending")
            require(status("bunch-app-down")["state"] == "firing", "Send failure changed evaluation state")
            previous_evaluation = status("bunch-app-down")["lastEvaluation"]
            wait_for("evaluation continues despite Discord failure",
                     lambda: status("bunch-app-down")["lastEvaluation"] != previous_evaluation, 20)
            require(not deliveries(down_title, True), "Stub failure was incorrectly treated as success")
            with stub.lock:
                stub.discord_status = 204
            wait_for("Discord delivery retries after local receiver recovers",
                     lambda: deliveries(down_title, True), 100)
            print("PASS actual up=0, 1m pending, Discord HTTP 500, continued evaluation and retry", flush=True)
            with stub.lock:
                stub.metrics_up = True
            wait_for("real Prometheus up=1 recovery", lambda: prom_value() == 1, 35)
            wait_for("app-down resolved delivery", lambda: any(
                "resolved" in json.dumps(item[2], ensure_ascii=False)
                for item in deliveries(down_title, True)), 45)
            require(status("bunch-app-down")["state"] == "inactive", "App-down failed to recover")
            require(len(deliveries(title)) == 1, "Unexpected late 5xx resolved/repeated delivery")
            print("PASS app-down recovery sends resolved; monitoring smoke test passed", flush=True)
    finally:
        # Exact generated names only: no compose down, prune, or shared volumes.
        cleanup_failed = False
        cleanup_commands = [["docker", "rm", "-f", "-v", name] for name in reversed(containers)]
        cleanup_commands += [["docker", "volume", "rm", volume] for volume in reversed(volumes)]
        if network_created:
            cleanup_commands.append(["docker", "network", "rm", run_id])
        for command in cleanup_commands:
            try:
                result = subprocess.run(command, capture_output=True, timeout=30)
                cleanup_failed |= result.returncode != 0
            except (OSError, subprocess.SubprocessError):
                cleanup_failed = True
        stub.shutdown()
        stub.server_close()
        require(not cleanup_failed, "Failed to clean up own Docker resources; prefix: " + run_id)


if __name__ == "__main__":
    def interrupted(*_):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    try:
        main()
    except (AssertionError, OSError, subprocess.SubprocessError, KeyboardInterrupt) as error:
        # Assertion messages are controlled; network/Docker exceptions can include
        # sensitive URLs, so report only their type.
        message = str(error) if isinstance(error, AssertionError) else type(error).__name__
        print("FAIL: " + message, flush=True)
        raise SystemExit(1)
