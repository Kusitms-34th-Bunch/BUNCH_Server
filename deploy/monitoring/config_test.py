#!/usr/bin/env python3
"""Validate the rendered monitoring contract without emitting interpolated secrets."""
import argparse
from decimal import Decimal
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
# Never load the app's .env or inherit deployment overrides from the shell.
COMPOSE_ENV = {key: value for key, value in os.environ.items() if key in {
    'PATH', 'HOME', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG',
    'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH', 'XDG_RUNTIME_DIR',
}}


def render(env_file):
    return subprocess.run([
        'docker', 'compose', '--env-file', str(env_file), '-f', str(ROOT / 'compose.yaml'),
        'config', '--format', 'json',
    ], env=COMPOSE_ENV, capture_output=True, text=True, timeout=30)

def compose_config(env_file):
    result = render(env_file)
    if result.returncode:
        raise AssertionError('Monitoring Compose rejected its environment (details withheld)')
    return json.loads(result.stdout)


def validate(config):
    assert config['name'] == 'bunch', 'Keep the existing bunch project to prevent duplicate volume writers'
    services = config['services']
    assert set(services) == {'alloy', 'loki', 'prometheus', 'grafana', 'monitoring-cloudflared'}
    assert sum(Decimal(str(s['cpus'])) for s in services.values()) <= Decimal('0.8')
    assert sum(int(s['mem_limit']) for s in services.values()) <= 1152 * 1024 * 1024
    for name, service in services.items():
        assert not service.get('ports'), 'Monitoring ports must not be published'
        assert not service.get('env_file'), 'Do not pass a whole secret file to a service'
        assert re.fullmatch(r'.+(?::v?\d+\.\d+\.\d+(?:[.-][\w.-]+)?|@sha256:[0-9a-f]{64})',
                            service['image']), 'Images must use exact versions or digests'
        actual = set(service.get('networks', {}))
        expected = {'prometheus': {'default', 'dev_metrics', 'prod_metrics'},
                    'grafana': {'default'},
                    'monitoring-cloudflared': {'default'}}.get(name, {'default'})
        assert actual == expected, 'Unexpected monitoring network membership: ' + name
        environment = set(service.get('environment', {}))
        if name == 'grafana':
            assert all(key.startswith('GF_') or key == 'DISCORD_WEBHOOK_URL' for key in environment)
        else:
            assert environment == ({'TUNNEL_TOKEN'} if name == 'monitoring-cloudflared' else set())
        if name != 'alloy':
            assert all(v.get('source') != '/var/run/docker.sock' for v in service.get('volumes', []))
    for volume in config['volumes'].values():
        assert volume['external'] is True and volume['name'], 'Existing volume name is mandatory'
    assert len(config['volumes']) == 4
    volume_names = {volume['name'] for volume in config['volumes'].values()}
    assert len(volume_names) == 4, 'Each monitoring service needs its own existing volume'
    protected_volumes = {'bunch-pgdata', 'bunch-redis-data',
                         'bunch-prod-pgdata', 'bunch-prod-redis-data'}
    assert not volume_names & protected_volumes, 'Never mount an app DB/Redis volume into monitoring'
    for network, external_name in (('dev_metrics', 'bunch-dev-metrics'),
                                   ('prod_metrics', 'bunch-prod-metrics')):
        assert config['networks'][network]['external'] is True
        assert config['networks'][network]['name'] == external_name
    assert config['networks']['default']['external'] is True
    assert config['networks']['default']['name'] == 'bunch-monitoring', 'Never join the dev DB/Redis network'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / '.env.example')
    args = parser.parse_args()
    validate(compose_config(args.env_file))
    if args.env_file.resolve() == (ROOT / '.env.example').resolve():
        example = args.env_file.read_text()
        mandatory = ['MONITORING_PROJECT', 'MONITORING_NETWORK', 'ALLOY_VOLUME', 'LOKI_VOLUME',
                     'PROMETHEUS_VOLUME', 'GRAFANA_VOLUME', 'MONITORING_TUNNEL_IMAGE',
                     'MONITORING_TUNNEL_TOKEN', 'GF_SECURITY_ADMIN_PASSWORD',
                     'GF_SERVER_ROOT_URL', 'DISCORD_WEBHOOK_URL']
        with tempfile.TemporaryDirectory(prefix='.config-test-', dir=ROOT) as directory:
            fixture = Path(directory) / 'example.env'
            for key in mandatory:
                fixture.write_text(re.sub(r'^' + key + r'=.*$', '', example, flags=re.MULTILINE))
                assert render(fixture).returncode != 0, 'Missing required input accepted: ' + key
            mutations = [
                ('MONITORING_NETWORK', 'bunch_default'),
                ('MONITORING_PROJECT', 'bunch-other'),
                ('LOKI_VOLUME', 'bunch_alloydata'),
            ]
            mutations += [(key, protected) for key in
                          ('ALLOY_VOLUME', 'LOKI_VOLUME', 'PROMETHEUS_VOLUME', 'GRAFANA_VOLUME')
                          for protected in ('bunch-pgdata', 'bunch-redis-data',
                                            'bunch-prod-pgdata', 'bunch-prod-redis-data')]
            for key, value in mutations:
                fixture.write_text(re.sub(r'^' + key + r'=.*$', key + '=' + value,
                                          example, flags=re.MULTILINE))
                rejected = False
                try:
                    validate(compose_config(fixture))
                except AssertionError:
                    rejected = True
                assert rejected, 'Unsafe project, network or volume input was accepted'
        print('PASS 11 missing inputs + 19 unsafe project/network/volume cases rejected')
    print('PASS Compose: external volumes, network isolation, scoped secrets, pinned images, 0.8CPU/1152MiB')


if __name__ == '__main__':
    try:
        main()
    except (AssertionError, OSError, subprocess.SubprocessError) as error:
        print('FAIL: ' + (str(error) if isinstance(error, AssertionError) else type(error).__name__))
        raise SystemExit(1)
