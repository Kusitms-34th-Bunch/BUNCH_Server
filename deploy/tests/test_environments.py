import json
import os
from pathlib import Path
import subprocess
import unittest

DEPLOY = Path(__file__).resolve().parents[1]


def compose_model(directory, example):
    # CI exports Spring and database variables for its Gradle tests.
    docker_environment = {
        key: value for key, value in os.environ.items()
        if key in {"PATH", "HOME", "XDG_RUNTIME_DIR"} or key.startswith("DOCKER_")
    }
    result = subprocess.run(
        ["docker", "compose", "--env-file", str(example), "-f", str(directory / "compose.yaml"),
         "config", "--format", "json"],
        env=docker_environment, text=True, capture_output=True, check=True,
    )
    return json.loads(result.stdout)


class EnvironmentTest(unittest.TestCase):
    def test_development_takes_old_data_and_production_keeps_old_route(self):
        configurations = [
            (DEPLOY / ".env.example", "dev", "bunch-dev", "bunch-dev-default",
             "bunch-pgdata", "bunch-redis-data"),
            (DEPLOY / "environments/.env.example", "prod", "bunch", "bunch_default",
             "bunch-prod-pgdata", "bunch-prod-redis-data"),
        ]
        for example, profile, project, network, pgdata, redis_data in configurations:
            with self.subTest(profile=profile):
                model = compose_model(DEPLOY, example)
                self.assertEqual(model["name"], project)
                self.assertEqual(set(model["networks"]), {"default"})
                self.assertEqual(model["networks"]["default"]["name"], network)
                self.assertIs(model["networks"]["default"]["external"], True)
                self.assertEqual(model["volumes"], {
                    "pgdata": {"name": pgdata, "external": True},
                    "redis_data": {"name": redis_data, "external": True},
                })
                services = model["services"]
                self.assertEqual(set(services), {"app", "postgres", "redis", "cloudflared"})
                for service in services.values():
                    self.assertFalse(service.get("ports"))
                    self.assertEqual(set(service["networks"]), {"default"})
                app = services["app"]["environment"]
                self.assertEqual(app["SPRING_PROFILES_ACTIVE"], profile)
                self.assertEqual(app["R2_BUCKET"], f"bunch-{profile}")
                self.assertTrue(all(key in app for key in (
                    "R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY",
                )))
                self.assertFalse(any(key.startswith("CLOUDINARY_") for key in app))
                self.assertEqual(set(services["cloudflared"]["environment"]), {"TUNNEL_TOKEN"})

    def test_monitoring_reuses_existing_production_network_and_volumes(self):
        directory = DEPLOY / "monitoring"
        model = compose_model(directory, directory / ".env.example")
        self.assertEqual(model["name"], "bunch")
        self.assertEqual(set(model["networks"]), {"default"})
        self.assertEqual(model["networks"]["default"]["name"], "bunch_default")
        self.assertIs(model["networks"]["default"]["external"], True)
        self.assertEqual(set(model["services"]), {"alloy", "loki", "prometheus", "grafana"})
        self.assertEqual(model["volumes"], {
            volume: {"name": f"bunch_{volume}", "external": True}
            for volume in ("alloydata", "lokidata", "prometheusdata", "grafanadata")
        })
        for service in model["services"].values():
            self.assertFalse(service.get("ports"))
            self.assertEqual(set(service["networks"]), {"default"})
            self.assertFalse(any(
                key.startswith(("DB_", "R2_", "JWT_")) for key in service.get("environment", {})
            ))
