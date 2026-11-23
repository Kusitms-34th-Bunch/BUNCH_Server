"""Release safety checks with fake Docker/SSH; no server or persistent Docker resources."""
import io
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
SHA = "a" * 40
DIGEST = "ghcr.io/kusitms-34th-bunch/bunch_server@sha256:" + "b" * 64
INVALID_IMAGES = ("", DIGEST[:-1], DIGEST.replace("bunch_server@", "other@"),
                  DIGEST.split("@", 1)[0] + ":latest", DIGEST[:-64] + "B" * 64,
                  DIGEST + "; touch invalid", DIGEST + "\n")


class ReleaseSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / "deploy/tests")
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.envdir = self.home / "dev"
        self.stage = self.envdir / f".incoming-{SHA}"
        self.stage.mkdir(parents=True)
        self.bin = self.home / "bin"
        self.bin.mkdir()
        self.log = self.home / "events"
        self.env = dict(os.environ, HOME=str(self.home), PATH=f"{self.bin}:{os.environ['PATH']}",
                        EVENTS=str(self.log), DIGEST=DIGEST, EXPECTED_APP_IMAGE=DIGEST)
        self.script("docker", '''import json, os, sys
args = sys.argv[1:]
with open(os.environ["EVENTS"], "a") as log: log.write("docker " + " ".join(args) + "\\n")
if args[:2] == ["image", "inspect"]: print(os.environ["DIGEST"])
if "up" in args and os.environ.get("UP_FAIL"): sys.exit(1)
if "pull" in args and os.environ.get("PULL_FAIL"): sys.exit(1)
if "stop" in args and os.environ.get("STOP_FAIL"): sys.exit(1)
if os.environ.get("MONITORING_CONFIG") and any(op in args for op in ("pull", "stop", "up")):
    from pathlib import Path
    directory = Path(args[args.index("--project-directory") + 1])
    expected = "candidate compose\\n" if "up" in args else "old compose\\n"
    assert (directory / "compose.yaml").read_text() == expected

if "ps" in args: print("candidate-container")
if "config" in args and "json" in args: print(os.environ["MONITORING_CONFIG"])
''')
        self.script("python3", '''import os, subprocess, sys
if sys.argv[1] == "-" and len(sys.argv) == 3:
    sys.stdin.read()
    with open(os.environ["EVENTS"], "a") as log: log.write("health\\n")
    sys.exit(int(os.environ.get("HEALTH_FAIL", "0")))
sys.exit(subprocess.call(["REAL_PYTHON", *sys.argv[1:]]))
'''.replace("REAL_PYTHON", sys.executable))
        # Linux flock is not available on all developer machines; the test double only grants the lock.
        self.script("flock", "import sys\nsys.exit(0)\n")
        shutil.copy(ROOT / "deploy/release.sh", self.stage / "release.sh")
        (self.stage / "environment_check.py").write_text('''import os, sys
with open(os.environ["EVENTS"], "a") as log: log.write("validate " + " ".join(sys.argv[1:]) + "\\n")
sys.exit(int(os.environ.get("VALIDATOR_FAIL", "0")))
''')
        (self.stage / "compose.yaml").write_text("candidate compose\n")
        (self.envdir / "compose.yaml").write_text("old compose\n")
        (self.envdir / ".env").write_text("DO_NOT_EXECUTE=$(touch invalid)\n")
        (self.envdir / ".release.env").write_text("APP_IMAGE=old-digest\n")
        (self.envdir / ".deployment-ready").write_text("bunch-environment-v1:dev\n")

    def script(self, name, body):
        path = self.bin / name
        path.write_text(f"#!{sys.executable}\n" + body)
        path.chmod(0o700)

    def release(self, *extra):
        return subprocess.run(["bash", str(self.stage / "release.sh"), self.envdir.name, SHA, *extra],
                              env=self.env, capture_output=True, text=True)

    def events(self):
        return self.log.read_text() if self.log.exists() else ""

    def assert_live_preserved(self):
        self.assertEqual((self.envdir / "compose.yaml").read_text(), "old compose\n")
        self.assertEqual((self.envdir / ".release.env").read_text(), "APP_IMAGE=old-digest\n")

    def change_environment(self, environment):
        new = self.home / environment
        self.envdir.rename(new)
        self.envdir = new
        self.stage = new / f".incoming-{SHA}"
        marker = "bunch-monitoring-v1" if environment == "monitoring" else f"bunch-environment-v1:{environment}"
        (new / ".deployment-ready").write_text(marker + "\n")

    def monitoring_candidate(self):
        self.change_environment("monitoring")
        self.env.pop("EXPECTED_APP_IMAGE", None)
        for filename in ("config.alloy", "loki.yaml", "prometheus.yaml"):
            (self.stage / filename).write_text("candidate config\n")
            (self.envdir / filename).write_text("old config\n")
        for dirname in ("provisioning", "dashboards"):
            (self.stage / "grafana" / dirname).mkdir(parents=True)
        (self.envdir / "grafana").mkdir()
        (self.envdir / "grafana" / "stale.json").write_text("old config\n")
        shutil.copy(ROOT / "deploy/monitoring/config_test.py", self.stage / "config_test.py")
        self.monitoring_model = {
            "name": "bunch",
            "services": {
                name: {
                    "cpus": "0.1", "mem_limit": 64 * 1024 * 1024,
                    "image": "example/service:1.2.3",
                    "networks": dict.fromkeys(["default", "dev_metrics", "prod_metrics"]
                                              if name == "prometheus" else ["default"], {}),
                    "environment": ({"TUNNEL_TOKEN": "inert"} if name == "monitoring-cloudflared"
                                    else {"GF_SECURITY_ADMIN_PASSWORD": "inert", "DISCORD_WEBHOOK_URL": "inert"}
                                    if name == "grafana" else {}),
                }
                for name in ["alloy", "loki", "prometheus", "grafana", "monitoring-cloudflared"]
            },
            "volumes": {key: {"external": True, "name": "bunch_" + key}
                        for key in ["alloydata", "lokidata", "prometheusdata", "grafanadata"]},
            "networks": {key: {"external": True, "name": name} for key, name in [
                ("default", "bunch-monitoring"), ("dev_metrics", "bunch-dev-metrics"),
                ("prod_metrics", "bunch-prod-metrics")]},
        }
        self.env["MONITORING_CONFIG"] = json.dumps(self.monitoring_model)

    def test_prod_uses_supplied_digest_and_its_own_release_record(self):
        self.change_environment("prod")
        result = self.release()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"docker pull {DIGEST}\n", self.events())
        self.assertNotIn("docker image inspect", self.events())
        self.assertNotIn("bunch_server:prod-", self.events())
        self.assertNotIn("bunch_server:dev-", self.events())
        self.assertEqual((self.envdir / ".release.env").read_text(), f"APP_IMAGE={DIGEST}\n")

    def test_retagged_image_cannot_replace_expected_digest(self):
        # A tag lookup would now return a different registry digest.
        self.env["DIGEST"] = DIGEST[:-64] + "c" * 64
        result = self.release()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"docker pull {DIGEST}\n", self.events())
        self.assertIn(f"--image {DIGEST} --ready --resources", self.events())
        self.assertNotIn("docker image inspect", self.events())
        self.assertNotIn(self.env["DIGEST"], self.events())
        self.assertEqual((self.envdir / ".release.env").read_text(), f"APP_IMAGE={DIGEST}\n")

    def test_invalid_expected_digest_rejected_before_docker(self):
        for image in INVALID_IMAGES:
            with self.subTest(image=image):
                self.env["EXPECTED_APP_IMAGE"] = image
                self.assertNotEqual(self.release().returncode, 0)
                self.assertEqual(self.events(), "")
                self.assert_live_preserved()
        self.env.pop("EXPECTED_APP_IMAGE", None)
        self.assertNotEqual(self.release().returncode, 0)
        self.assertEqual(self.events(), "")

    def test_monitoring_check_preserves_live_files(self):
        self.monitoring_candidate()
        result = self.release("--check")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_live_preserved()
        self.assertNotIn(" up ", self.events())
        self.assertIn("volume inspect bunch_alloydata", self.events())
        self.assertIn("network inspect bunch-monitoring", self.events())

    def test_monitoring_updates_only_four_services_and_backs_up_configs(self):
        self.monitoring_candidate()
        result = self.release()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("up -d --no-deps --force-recreate --wait --wait-timeout 120 alloy loki prometheus grafana", self.events())
        self.assertNotIn("monitoring-cloudflared", self.events())
        self.assertNotIn("bunch_server", self.events())
        self.assertLess(self.events().index(" pull "), self.events().index(" stop "))
        self.assertLess(self.events().index(" stop "), self.events().index(" up "))
        self.assertFalse((self.envdir / "grafana" / "stale.json").exists())
        backups = list((self.envdir / ".backups").iterdir())
        self.assertEqual((backups[0] / "grafana" / "stale.json").read_text(), "old config\n")
        self.assertEqual((backups[0] / "config.alloy").read_text(), "old config\n")
        self.assertFalse((backups[0] / ".env").exists())

    def test_monitoring_rejects_unexpected_service_before_live_changes(self):
        self.monitoring_candidate()
        self.monitoring_model["services"]["app"] = self.monitoring_model["services"]["alloy"]
        self.env["MONITORING_CONFIG"] = json.dumps(self.monitoring_model)
        result = self.release()
        self.assertNotEqual(result.returncode, 0)
        self.assert_live_preserved()
        self.assertNotIn(" pull ", self.events())
        self.assertNotIn(" stop ", self.events())

    def test_monitoring_rejects_wrong_project_and_storage_before_live_changes(self):
        self.monitoring_candidate()
        baseline = json.dumps(self.monitoring_model)
        for mutation in ("project", "duplicate-volume", "database-volume", "extra-network"):
            with self.subTest(mutation=mutation):
                model = json.loads(baseline)
                if mutation == "project":
                    model["name"] = "other-project"
                elif mutation == "duplicate-volume":
                    model["volumes"]["lokidata"]["name"] = model["volumes"]["alloydata"]["name"]
                elif mutation == "database-volume":
                    model["volumes"]["lokidata"]["name"] = "bunch-pgdata"
                else:
                    model["networks"]["app"] = {"external": True, "name": "bunch_default"}
                self.env["MONITORING_CONFIG"] = json.dumps(model)
                self.log.unlink(missing_ok=True)
                self.assertNotEqual(self.release().returncode, 0)
                self.assert_live_preserved()
                self.assertNotIn(" pull ", self.events())
                self.assertNotIn(" stop ", self.events())

    def test_monitoring_pull_failure_keeps_services_and_files_intact(self):
        self.monitoring_candidate()
        self.env["PULL_FAIL"] = "1"
        self.assertNotEqual(self.release().returncode, 0)
        self.assert_live_preserved()
        self.assertNotIn(" stop ", self.events())
        self.assertNotIn(" up ", self.events())

    def test_monitoring_stop_failure_does_not_overwrite_live_files(self):
        self.monitoring_candidate()
        self.env["STOP_FAIL"] = "1"
        self.assertNotEqual(self.release().returncode, 0)
        self.assert_live_preserved()
        self.assertNotIn(" up ", self.events())

    def test_missing_marker_rejects_before_docker(self):
        (self.envdir / ".deployment-ready").unlink()
        self.assertNotEqual(self.release().returncode, 0)
        self.assertEqual(self.events(), "")
        self.assert_live_preserved()

    def test_wrong_environment_marker_rejects(self):
        (self.envdir / ".deployment-ready").write_text("bunch-environment-v1:prod\n")
        self.assertNotEqual(self.release().returncode, 0)
        self.assertEqual(self.events(), "")

    def test_validation_failure_never_starts_or_replaces(self):
        self.env["VALIDATOR_FAIL"] = "1"
        self.assertNotEqual(self.release().returncode, 0)
        self.assertNotIn(" up ", self.events())
        self.assert_live_preserved()

    def test_check_uses_expected_digest_and_preserves_live_files(self):
        result = self.release("--check")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"--image {DIGEST} --ready --resources", self.events())
        self.assertNotIn(" up ", self.events())
        self.assertFalse((self.envdir / ".backups").exists())
        self.assert_live_preserved()

    def test_failed_start_preserves_live_files_and_backup(self):
        self.env["UP_FAIL"] = "1"
        self.assertNotEqual(self.release().returncode, 0)
        self.assert_live_preserved()
        backups = list((self.envdir / ".backups").iterdir())
        self.assertEqual(len(backups), 1)
        self.assertEqual((backups[0] / ".release.env").read_text(), "APP_IMAGE=old-digest\n")
        self.assertFalse((backups[0] / ".env").exists())

    def test_failed_health_preserves_release_record(self):
        self.env["HEALTH_FAIL"] = "1"
        self.assertNotEqual(self.release().returncode, 0)
        self.assertIn("health\n", self.events())
        self.assert_live_preserved()

    def test_success_records_digest_only_after_start_and_health(self):
        result = self.release()
        self.assertEqual(result.returncode, 0, result.stderr)
        events = self.events()
        self.assertIn("up -d --no-deps --wait --wait-timeout 120 app", events)
        self.assertLess(events.index("validate "), events.index(" up "))
        self.assertLess(events.index(" up "), events.index("health\n"))
        self.assertEqual((self.envdir / ".release.env").read_text(), f"APP_IMAGE={DIGEST}\n")
        self.assertEqual((self.envdir / "compose.yaml").read_text(), "candidate compose\n")
        self.assertFalse((ROOT / "invalid").exists())
        self.assertNotIn("latest", events)

    def test_transport_stops_before_upload_when_marker_missing(self):
        self.script("sshpass", '''import os, sys
with open(os.environ["EVENTS"], "a") as log: log.write(" ".join(sys.argv[1:]) + "\\n")
sys.exit(1)
''')
        self.env.update(SSH_HOST="server.invalid", SSH_USER="bunch", SSH_PASSWORD="test-password",
                        GITHUB_TOKEN="test-token", GITHUB_ACTOR="bunch")
        result = subprocess.run(["bash", "deploy/ci-deploy.sh", "dev", SHA], cwd=ROOT,
                                env=self.env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        events = self.events()
        self.assertIn(".deployment-ready", events)
        self.assertNotIn("mkdir", events)
        self.assertNotIn("scp", events)
        self.assertNotIn("test-password", events)
        self.assertNotIn("test-token", events)

    def test_transport_allowlists_uploads_and_checks_marker_first(self):
        self.script("sshpass", '''import os, sys
with open(os.environ["EVENTS"], "a") as log: log.write(" ".join(sys.argv[1:]) + "\\n")
''')
        self.env.update(SSH_HOST="server.invalid", SSH_USER="bunch", SSH_PASSWORD="test-password",
                        GITHUB_TOKEN="test-token", GITHUB_ACTOR="bunch")
        for environment in ("dev", "monitoring"):
            if environment == "monitoring":
                self.env.pop("EXPECTED_APP_IMAGE", None)
            self.log.unlink(missing_ok=True)
            result = subprocess.run(["bash", "deploy/ci-deploy.sh", environment, SHA], cwd=ROOT,
                                    env=self.env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            events = self.events()
            self.assertIn(".deployment-ready", events.splitlines()[0])
            self.assertNotIn("mkdir", events.splitlines()[0])
            self.assertNotIn(".env", "\n".join(line for line in events.splitlines() if " scp " in line))
            self.assertNotIn("test-token", events)
            self.assertNotIn("test-password", events)
            if environment == "dev":
                self.assertIn(f"EXPECTED_APP_IMAGE='{DIGEST}'", events)
            else:
                self.assertNotIn("EXPECTED_APP_IMAGE=", events)

    def test_transport_rejects_invalid_expected_digest_before_ssh(self):
        self.script("sshpass", 'import sys\nsys.exit("SSH must not be called")\n')
        self.env.update(SSH_HOST="server.invalid", SSH_USER="bunch", SSH_PASSWORD="test-password",
                        GITHUB_TOKEN="test-token", GITHUB_ACTOR="bunch")
        for image in INVALID_IMAGES:
            with self.subTest(image=image):
                self.env["EXPECTED_APP_IMAGE"] = image
                result = subprocess.run(["bash", "deploy/ci-deploy.sh", "dev", SHA], cwd=ROOT,
                                        env=self.env, capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("expected application digest", result.stderr.lower())
                self.assertEqual(self.events(), "")
        self.env.pop("EXPECTED_APP_IMAGE", None)
        result = subprocess.run(["bash", "deploy/ci-deploy.sh", "dev", SHA], cwd=ROOT,
                                env=self.env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected application digest", result.stderr.lower())


class WorkflowAdmissionTest(unittest.TestCase):
    def workflow(self):
        return (ROOT / ".github/workflows/ci.yml").read_text()

    def admission(self, ref, path, dev="", prod="", monitoring="", event="push"):
        script = self.workflow().split("      - name: Check build and deployment changes", 1)[1]
        script = textwrap.dedent(script.split("        run: |\n", 1)[1].split("\n  build:\n", 1)[0])
        with tempfile.TemporaryDirectory(dir=ROOT / "deploy/tests") as directory:
            directory = Path(directory)
            git = directory / "git"
            git.write_text(f"#!{sys.executable}\nimport os,sys\n"
                           'if sys.argv[1] == "diff": sys.stdout.buffer.write(os.environ["CHANGED_PATH"].encode() + bytes([0]))\n'
                           'else: print("b" * 40)\n')
            git.chmod(0o700)
            output = directory / "outputs"
            env = dict(os.environ, PATH=f"{directory}:{os.environ['PATH']}", BASE="b" * 40,
                       HEAD=SHA, EVENT_NAME=event, REF=ref, CHANGED_PATH=path, GITHUB_OUTPUT=str(output),
                       REPO_DEPLOY_DEV_ENABLED=dev, REPO_DEPLOY_PROD_ENABLED=prod,
                       REPO_MONITORING_DEPLOY_ENABLED=monitoring)
            result = subprocess.run(["bash", "-c", script], cwd=ROOT, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            return dict(line.split("=", 1) for line in output.read_text().splitlines())

    def publish_script(self):
        step = self.workflow().split("      - name: Build and push image\n", 1)[1]
        return textwrap.dedent(step.split("        run: |\n", 1)[1].split("\n  deploy:\n", 1)[0])

    def run_publish(self, digest="sha256:" + "b" * 64, push_failure=False):
        with tempfile.TemporaryDirectory(dir=ROOT / "deploy/tests") as directory:
            directory = Path(directory)
            docker = directory / "docker"
            docker.write_text(f"#!{sys.executable}\n" + 'import os,sys\n'
                              'if sys.argv[1] == "push":\n'
                              '    assert sys.argv[2:] == ["--quiet", "ghcr.io/kusitms-34th-bunch/bunch_server:dev-" + "a" * 40]\n'
                              '    print(os.environ["PUSH_DIGEST"])\n'
                              '    sys.exit(int(os.environ["PUSH_FAILURE"]))\n'
                              'assert sys.argv[1] == "build", "Do not reinterpret the tag after push"\n')
            docker.chmod(0o700)
            output = directory / "outputs"
            env = dict(os.environ, PATH=f"{directory}:{os.environ['PATH']}", GITHUB_OUTPUT=str(output),
                       GITHUB_SHA=SHA, GITHUB_REPOSITORY="Kusitms-34th-Bunch/BUNCH_Server",
                       ENVIRONMENT="dev", PUSH_DIGEST=digest, PUSH_FAILURE=str(int(push_failure)))
            result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", self.publish_script()],
                                    cwd=directory, env=env, capture_output=True, text=True)
            return result, output.read_text() if output.exists() else ""

    def test_publish_exports_the_digest_returned_by_push(self):
        result, output = self.run_publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output, f"app_image={DIGEST}\n")

    def test_failed_push_does_not_export_a_deployable_digest(self):
        result, output = self.run_publish(push_failure=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output, "")

    def test_invalid_push_digest_does_not_export_an_image(self):
        for digest in ("", "sha256:bad", "sha256:" + "b" * 63, "sha256:" + "B" * 64):
            with self.subTest(digest=digest):
                result, output = self.run_publish(digest=digest)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(output, "")

    def test_published_digest_is_forwarded_to_deploy(self):
        workflow = self.workflow()
        self.assertIn("app_image: ${{ steps.push.outputs.app_image }}", workflow)
        self.assertIn("EXPECTED_APP_IMAGE: ${{ needs.publish.outputs.app_image }}", workflow)
        self.assertIn("      - name: Build and push image\n        id: push\n", workflow)

    def r2_test_step(self):
        step = self.workflow().split("      - name: Test R2 migration tool when present\n", 1)[1]
        return textwrap.dedent(step.split("        run: |\n", 1)[1].split("\n      - ", 1)[0])

    def run_r2_test_step(self, tool=None):
        with tempfile.TemporaryDirectory(dir=ROOT / "deploy/tests") as directory:
            directory = Path(directory)
            if tool is not None:
                script = directory / "scripts/r2/run.sh"
                script.parent.mkdir(parents=True)
                script.write_text(tool)
            return subprocess.run(["bash", "-e", "-o", "pipefail", "-c", self.r2_test_step()],
                                  cwd=directory, capture_output=True, text=True)

    def test_r2_tool_changes_require_build_without_monitoring_or_pr_deploy(self):
        for path in ("scripts/r2/R2ImageMigration.java", "scripts/r2/run.sh", "scripts/r2/prepare.sql"):
            with self.subTest(path=path):
                plan = self.admission("refs/heads/dev", path, dev="true", prod="true",
                                      monitoring="true", event="pull_request")
                self.assertEqual(plan["app"], "true")
                self.assertEqual(plan["monitoring"], "false")
                self.assertEqual(plan["deploy_app"], "false")
                self.assertEqual(plan["deploy_monitoring"], "false")

    def test_missing_r2_tool_does_not_fail_checkout(self):
        result = self.run_r2_test_step()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")

    def test_present_r2_tool_receives_test_argument(self):
        result = self.run_r2_test_step('test "$#" -eq 1 && test "$1" = test || exit 19\nprintf "r2-tests-ran\\n"\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "r2-tests-ran\n")

    def test_r2_tool_failure_fails_ci(self):
        result = self.run_r2_test_step('exit 23\n')
        self.assertEqual(result.returncode, 23, result.stderr)

    def test_both_concurrency_groups_keep_pending_runs(self):
        groups = re.findall(r"(?m)^( *)concurrency:\n\1  group: ([^\n]+)\n"
                            r"\1  cancel-in-progress: false\n\1  queue: max$", self.workflow())
        self.assertEqual({group for _, group in groups}, {"bunch-${{ github.ref }}", "bunch-server-deploy"})
        self.assertEqual(len(groups), 2)
        self.assertNotIn("  deploy-app:", self.workflow())
        self.assertNotIn("  deploy-monitoring:", self.workflow())
        self.assertIn("always() && !cancelled()", self.workflow())

    def test_unset_repository_flags_disable_all_deployment(self):
        plan = self.admission("refs/heads/main", "deploy/release.sh")
        self.assertEqual(plan["deploy_app"], "false")
        self.assertEqual(plan["deploy_monitoring"], "false")

    def test_dev_only_admits_its_enabled_app(self):
        plan = self.admission("refs/heads/dev", "deploy/release.sh", dev="true", prod="true", monitoring="true")
        self.assertEqual(plan["deploy_app"], "true")
        self.assertEqual(plan["deploy_monitoring"], "false")

    def test_main_admits_enabled_app_and_monitoring_together(self):
        plan = self.admission("refs/heads/main", "deploy/release.sh", prod="true", monitoring="true")
        self.assertEqual(plan["deploy_app"], "true")
        self.assertEqual(plan["deploy_monitoring"], "true")

    def test_monitoring_only_change_needs_no_app_publish(self):
        plan = self.admission("refs/heads/main", "deploy/monitoring/prometheus.yaml", prod="true", monitoring="true")
        self.assertEqual(plan["app"], "false")
        self.assertEqual(plan["deploy_app"], "false")
        self.assertEqual(plan["deploy_monitoring"], "true")

    def test_docs_and_pull_requests_never_deploy(self):
        for path, event in (("deploy/monitoring/README.md", "push"), ("deploy/release.sh", "pull_request")):
            plan = self.admission("refs/heads/main", path, prod="true", monitoring="true", event=event)
            self.assertEqual(plan["deploy_app"], "false")
            self.assertEqual(plan["deploy_monitoring"], "false")


class HealthProbeTest(unittest.TestCase):
    def health_code(self):
        script = (ROOT / "deploy/release.sh").read_text()
        return script.split('python3 - "$container" <<\'PY\'\n', 1)[1].split("\nPY", 1)[0]

    def run_probe(self, running=True, status="UP"):
        state = {"running": running, "networks": {"app": {"IPAddress": "172.18.0.10"}}}
        response = io.BytesIO(json.dumps({"status": status}).encode())
        response.status = 200
        client = Mock()
        client.open.return_value = response
        # First two reads enter the loop; the third ends it after a non-UP response.
        with patch("sys.argv", ["-", "candidate-container"]), \
             patch("subprocess.run", return_value=Mock(stdout=json.dumps(state))) as inspect, \
             patch("urllib.request.build_opener", return_value=client), \
             patch("time.monotonic", side_effect=[0, 0, 121]), \
             patch("time.sleep"):
            with self.assertRaises(SystemExit) as result:
                exec(compile(self.health_code(), "release-health", "exec"), {})
        command = inspect.call_args.args[0]
        self.assertEqual(command[:3], ["docker", "inspect", "--format"])
        self.assertNotIn("Config", command[3])
        self.assertEqual(command[-1], "candidate-container")
        return result.exception.code, client

    def test_up_on_management_endpoint_is_required(self):
        code, client = self.run_probe()
        self.assertEqual(code, 0)
        client.open.assert_called_once_with("http://172.18.0.10:8081/actuator/health", timeout=3)

    def test_http_success_with_down_body_fails(self):
        code, _ = self.run_probe(status="DOWN")
        self.assertIn("health verification failed", code)

    def test_stopped_app_fails_without_http_probe(self):
        code, client = self.run_probe(running=False)
        self.assertIn("Application stopped", code)
        client.open.assert_not_called()


if __name__ == "__main__":
    unittest.main()
