import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

CHECK = load('environment_check', ROOT / 'environment_check.py')
PREPARE = load('prepare_environments', ROOT / 'prepare-environments.py')


def example_configuration(example):
    compose_variables = set(re.findall(r'\$\{([A-Za-z_][A-Za-z0-9_]*)', (ROOT / 'compose.yaml').read_text()))
    environment = {key: value for key, value in os.environ.items() if key not in compose_variables}
    result = subprocess.run(['docker', 'compose', '--env-file', str(example), '-f', str(ROOT / 'compose.yaml'),
                             'config', '--format', 'json'], check=True, capture_output=True, text=True,
                            env=environment)
    return json.loads(result.stdout)


class EnvironmentContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.models = {}
        for env, example in [('dev', ROOT / '.env.example'), ('prod', ROOT / 'environments/.env.example')]:
            cls.models[env] = example_configuration(example)

    def test_examples_are_independent_of_ci_application_environment(self):
        with patch.dict(os.environ, {'SPRING_PROFILES_ACTIVE': 'test',
                                     'DB_URL': 'jdbc:postgresql://localhost:5432/bunch_test',
                                     'DB_USERNAME': 'ci-user', 'DB_PASSWORD': 'ci-password',
                                     'R2_BUCKET': 'unrelated-ci-bucket'}):
            for environment, example in [('dev', ROOT / '.env.example'),
                                         ('prod', ROOT / 'environments/.env.example')]:
                CHECK.validate(example_configuration(example), environment, allow_examples=True)

    def test_example_environment_boundaries(self):
        for env, model in self.models.items():
            CHECK.validate(model, env, allow_examples=True)
        dev, prod = (self.models[e] for e in ('dev', 'prod'))
        self.assertNotEqual(dev['name'], prod['name'])
        self.assertTrue({v['name'] for v in dev['volumes'].values()}.isdisjoint(v['name'] for v in prod['volumes'].values()))
        self.assertTrue({n['name'] for n in dev['networks'].values()}.isdisjoint(n['name'] for n in prod['networks'].values()))

    def test_placeholder_digest_cannot_be_deployed(self):
        with self.assertRaisesRegex(CHECK.InvalidEnvironment, 'digest'):
            CHECK.validate(self.models['dev'], 'dev')

    def reject(self, change, message):
        candidate = copy.deepcopy(self.models['dev'])
        change(candidate)
        with self.assertRaisesRegex(CHECK.InvalidEnvironment, message):
            CHECK.validate(candidate, 'dev', allow_examples=True)

    def test_dev_cannot_use_production_volume(self):
        self.reject(lambda m: m['volumes']['pgdata'].update(name='bunch-prod-pgdata'), 'Volume')

    def test_missing_volume_is_not_implicitly_created(self):
        self.reject(lambda m: m['volumes']['pgdata'].update(external=False), 'external')

    def test_storage_must_not_join_metrics_network(self):
        self.reject(lambda m: m['services']['redis']['networks'].update(metrics={}), 'own app network')

    def test_no_public_management_or_storage_port(self):
        self.reject(lambda m: m['services']['app'].update(ports=[{'target': 8081, 'published': '8081'}]), 'private')

    def test_profile_and_bucket_must_match(self):
        self.reject(lambda m: m['services']['app']['environment'].update(SPRING_PROFILES_ACTIVE='prod'), 'profile')
        self.reject(lambda m: m['services']['app']['environment'].update(R2_BUCKET='bunch-prod'), 'bucket')
        self.reject(lambda m: m['services']['app']['environment'].update(R2_ENDPOINT='https://example.r2.cloudflarestorage.com/bunch-dev'), 'endpoint')

    def test_resource_reserve_is_required(self):
        self.reject(lambda m: m['services']['app'].update(cpus='3'), 'CPU budget')
        self.reject(lambda m: m['services']['app'].update(mem_limit=8 * 1024**3), 'Memory budget')

    def test_app_does_not_receive_admin_or_tunnel_credentials(self):
        self.reject(lambda m: m['services']['app']['environment'].update(TUNNEL_TOKEN='fake'), 'credentials')

    def test_final_runtime_receives_no_cloudinary_credentials(self):
        for key in ('CLOUDINARY_CLOUD_NAME', 'CLOUDINARY_API_KEY', 'CLOUDINARY_API_SECRET'):
            for model in self.models.values():
                self.assertNotIn(key, model['services']['app']['environment'])
            self.reject(lambda m: m['services']['app']['environment'].update({key: 'fake'}), 'credentials')

    def test_activation_requires_r2_and_independent_credentials(self):
        model = copy.deepcopy(self.models['dev'])
        with self.assertRaisesRegex(CHECK.InvalidEnvironment, 'R2 rollout'):
            CHECK.check_activation(model, 'dev', Path('/home/bunch/dev'))
        for key in ('R2_ENDPOINT', 'R2_BUCKET', 'R2_ACCESS_KEY_ID', 'R2_SECRET_ACCESS_KEY'):
            model['services']['app']['environment'][key] = 'fake-dev-value'

    def test_duplicate_tunnel_token_is_rejected_without_readiness_markers(self):
        model = copy.deepcopy(self.models['prod'])
        peer = copy.deepcopy(self.models['dev'])
        model['services']['cloudflared']['environment']['TUNNEL_TOKEN'] = 'same-unsafe-token'
        peer['services']['cloudflared']['environment']['TUNNEL_TOKEN'] = 'same-unsafe-token'
        with tempfile.TemporaryDirectory(dir=ROOT / 'tests') as tmp:
            base = Path(tmp)
            (base / 'dev').mkdir()
            (base / 'dev/.env').write_text('fake fixture')
            with patch.object(CHECK, 'configuration', return_value=peer):
                with self.assertRaisesRegex(CHECK.InvalidEnvironment, 'before startup'):
                    CHECK.check_peer_credentials(model, 'prod', base / 'prod')

    def test_production_requires_existing_development_configuration(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'tests') as tmp:
            with self.assertRaisesRegex(CHECK.InvalidEnvironment, 'before production'):
                CHECK.check_peer_credentials(self.models['prod'], 'prod', Path(tmp) / 'prod')


class PreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / 'tests')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'bunch'
        self.root.mkdir()
        (self.root / 'compose.yaml').write_text('legacy-compose')
        (self.root / '.env').write_text('DO_NOT_READ_OR_COPY=private\n')
        self.observed = {'app': {'binds': {'/app/profanity': str(self.root / 'profanity')}}}

    def prepare(self, apply=False, backup=None):
        with contextlib.redirect_stdout(io.StringIO()):
            PREPARE.prepare(self.root, ROOT, backup, self.observed, apply=apply)

    def test_dry_run_has_no_filesystem_mutations(self):
        before = sorted(str(p) for p in self.root.rglob('*'))
        self.prepare()
        self.assertEqual(before, sorted(str(p) for p in self.root.rglob('*')))

    def test_apply_requires_restore_tested_backup_without_partial_writes(self):
        with self.assertRaisesRegex(PREPARE.PreparationError, 'backup directory'):
            self.prepare(apply=True)
        self.assertFalse((self.root / 'dev').exists())

    def test_staging_preserves_legacy_secrets_and_never_marks_ready(self):
        backup = Path(self.temp.name) / 'backup'
        backup.mkdir()
        for name in ('restore-tested', 'postgres.dump', 'redis-backup.tar', 'legacy-compose.yaml'):
            (backup / name).write_text('verified-fixture')
        self.prepare(True, backup)
        self.assertEqual((self.root / '.env').read_text(), 'DO_NOT_READ_OR_COPY=private\n')
        self.assertEqual((self.root / 'compose.yaml').read_text(), 'legacy-compose')
        self.assertFalse((self.root / 'dev/.env').exists())
        self.assertFalse((self.root / 'prod/.deployment-ready').exists())
        self.assertTrue((self.root / 'dev/.legacy-profanity').is_symlink())
        self.assertEqual((self.root / 'dev/compose.yaml').read_text(), (ROOT / 'compose.yaml').read_text())
        with self.assertRaisesRegex(PREPARE.PreparationError, 'already exists'):
            self.prepare(True, backup)

    def test_dangling_inventory_and_dictionary_links_are_rejected_before_writes(self):
        for relative in ('environment-inventory.json', 'dev/.legacy-profanity'):
            with self.subTest(relative=relative):
                path = self.root / relative
                path.parent.mkdir(exist_ok=True)
                path.symlink_to(self.root / 'missing-target')
                with self.assertRaises(PREPARE.PreparationError):
                    self.prepare()
                self.assertFalse((self.root / 'dev/compose.yaml').exists())
                path.unlink()

    def test_symlinked_target_is_rejected(self):
        other = Path(self.temp.name) / 'other'
        other.mkdir()
        (self.root / 'dev').symlink_to(other, target_is_directory=True)
        with self.assertRaisesRegex(PREPARE.PreparationError, 'symlink'):
            self.prepare()


if __name__ == '__main__':
    unittest.main()
