#!/usr/bin/env python3
"""Validate a candidate without printing resolved environment values."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


class InvalidEnvironment(Exception):
    pass


def require(condition, message):
    if not condition:
        raise InvalidEnvironment(message)


def docker(*args):
    result = subprocess.run(['docker', *args], capture_output=True, text=True)
    # Docker stderr may include interpolated values. Only report controlled messages.
    require(result.returncode == 0, 'Docker validation failed; inspect configuration privately.')
    return result.stdout


def configuration(directory, compose, image=None):
    command = ['compose', '--project-directory', str(directory), '--env-file', str(directory / '.env')]
    if (directory / '.release.env').exists():
        command += ['--env-file', str(directory / '.release.env')]
    command += ['-f', str(compose), 'config', '--format', 'json']
    environ = os.environ.copy()
    if image:
        environ['APP_IMAGE'] = image
    result = subprocess.run(['docker', *command], capture_output=True, text=True, env=environ)
    require(result.returncode == 0, 'Compose validation failed; no resolved values were printed.')
    return json.loads(result.stdout)


def digest_image(value):
    return bool(re.fullmatch(r'[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}', value or '')) and not value.endswith('0' * 64)


def validate(model, environment, allow_examples=False):
    require(environment in ('dev', 'prod'), 'Unknown environment.')
    services = model['services']
    require(set(services) == {'app', 'postgres', 'redis', 'cloudflared'}, 'Unexpected application services.')
    require(model['name'] == ('bunch' if environment == 'dev' else 'bunch-prod'), 'Compose project mismatch.')
    expected = {'pgdata': 'bunch-pgdata' if environment == 'dev' else 'bunch-prod-pgdata',
                'redis_data': 'bunch-redis-data' if environment == 'dev' else 'bunch-prod-redis-data'}
    for key, name in expected.items():
        volume = model['volumes'][key]
        require(volume.get('external') is True and volume['name'] == name, 'Volume identity or external flag mismatch.')
    require(model['networks']['default'].get('external') is True, 'Application network must already exist.')
    require(model['networks']['default']['name'] == ('bunch_default' if environment == 'dev' else 'bunch-prod-default'),
            'Application network identity mismatch.')
    require(model['networks']['metrics'].get('external') is True
            and model['networks']['metrics']['name'] == f'bunch-{environment}-metrics', 'Metrics network mismatch.')
    for name, service in services.items():
        require(not service.get('ports'), 'Host ports must stay private.')
        require(not service.get('env_file'), 'Pass only the required variables to each service.')
        require(digest_image(service.get('image')) or (allow_examples and service.get('image', '').endswith('0' * 64)),
                'Pin every image to a non-placeholder digest.')
        require(float(service.get('cpus', 0)) > 0 and int(service.get('mem_limit', 0)) > 0, 'Resource limits are required.')
        require(not service.get('privileged') and not service.get('network_mode'), 'Unsafe service configuration.')
        if name != 'app':
            require(set(service.get('networks', {})) == {'default'}, 'Storage/Tunnel must stay on its own app network.')
    require(sum(float(s['cpus']) for s in services.values()) <= (0.91 if environment == 'dev' else 1.86),
            'CPU budget exceeds the environment allocation.')
    require(sum(int(s['mem_limit']) for s in services.values()) <= (1856 if environment == 'dev' else 3456) * 1024**2,
            'Memory budget exceeds the environment allocation.')
    app = services['app']
    require(app['labels'].get('kr.bunch.environment') == environment, 'Application environment label mismatch.')
    require(set(app['networks']) == {'default', 'metrics'}
            and f'{environment}-app' in app['networks']['metrics'].get('aliases', []), 'Application metrics alias mismatch.')
    env = app['environment']
    require(env['SPRING_PROFILES_ACTIVE'] == environment, 'Spring profile mismatch.')
    require(env['DB_URL'] == 'jdbc:postgresql://postgres:5432/bunch' and env['DB_USERNAME'] == 'bunch', 'Database boundary mismatch.')
    require(env['REDIS_HOST'] == 'redis' and str(env['REDIS_PORT']) == '6379', 'Redis boundary mismatch.')
    require(str(env['SERVER_PORT']) == '8080' and str(env['MANAGEMENT_SERVER_PORT']) == '8081', 'Application port mismatch.')
    require(not {'TUNNEL_TOKEN', 'POSTGRES_PASSWORD', 'DISCORD_WEBHOOK_URL'} & env.keys(), 'Unexpected app credentials.')
    require(set(services['cloudflared']['environment']) == {'TUNNEL_TOKEN'}, 'Unexpected Tunnel credentials.')
    require(set(services['postgres']['environment']) == {'POSTGRES_USER', 'POSTGRES_DB', 'POSTGRES_PASSWORD'},
            'Unexpected PostgreSQL credentials.')
    bucket = env.get('R2_BUCKET')
    require(not bucket or bucket == f'bunch-{environment}', 'R2 bucket mismatch.')
    if env.get('R2_ENDPOINT'):
        endpoint = env['R2_ENDPOINT']
        require(bool(re.fullmatch(r'https://[a-zA-Z0-9-]+\.r2\.cloudflarestorage\.com/?', endpoint)),
                'R2 endpoint must contain only the account origin, without a bucket.')
    if not allow_examples:
        for value in [env['DB_PASSWORD'], env['JWT_SECRET'], services['postgres']['environment']['POSTGRES_PASSWORD'],
                      services['cloudflared']['environment']['TUNNEL_TOKEN']]:
            require(bool(value) and 'CHANGE' not in value, 'Replace placeholder credentials before deployment.')
    return model


def check_resources(model):
    for volume in model['volumes'].values():
        docker('volume', 'inspect', volume['name'])
    for network in model['networks'].values():
        docker('network', 'inspect', network['name'])


def check_ready(directory, environment):
    require(directory == Path.home() / environment, 'Deployment directory must be the exact environment home path.')
    marker = directory / '.deployment-ready'
    require(marker.is_file() and marker.read_text().strip() == f'bunch-environment-v1:{environment}',
            'Environment readiness marker is missing or mismatched.')


def check_activation(model, environment, directory):
    app_env = model['services']['app']['environment']
    for key in ('R2_ENDPOINT', 'R2_BUCKET', 'R2_ACCESS_KEY_ID', 'R2_SECRET_ACCESS_KEY'):
        require(bool(app_env.get(key)) and 'CHANGE' not in app_env[key], 'Complete the R2 rollout before activating automatic deployment.')


def check_peer_credentials(model, environment, directory):
    # Compare before the first production startup, not only after admission markers exist.
    peer_environment = 'prod' if environment == 'dev' else 'dev'
    peer_directory = directory.parent / peer_environment
    if not (peer_directory / '.env').is_file():
        require(environment == 'dev', 'Development configuration is required before production startup.')
        return
    peer = configuration(peer_directory, peer_directory / 'compose.yaml')
    require(peer['name'] == ('bunch' if peer_environment == 'dev' else 'bunch-prod'), 'Peer environment project mismatch.')
    app_env = model['services']['app']['environment']
    peer_env = peer['services']['app']['environment']
    pairs = [(app_env.get(key), peer_env.get(key))
             for key in ('JWT_SECRET', 'DB_PASSWORD', 'R2_ACCESS_KEY_ID', 'R2_SECRET_ACCESS_KEY')]
    pairs += [(model['services'][service]['environment'][key], peer['services'][service]['environment'][key])
              for service, key in (('postgres', 'POSTGRES_PASSWORD'), ('cloudflared', 'TUNNEL_TOKEN'))]
    for value, peer_value in pairs:
        if value and peer_value and 'CHANGE' not in value and 'CHANGE' not in peer_value:
            require(value != peer_value, 'Environment credentials must be independent before startup.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('environment', choices=['dev', 'prod'])
    parser.add_argument('directory', type=Path)
    parser.add_argument('--compose', type=Path)
    parser.add_argument('--image')
    parser.add_argument('--ready', action='store_true')
    parser.add_argument('--resources', action='store_true')
    args = parser.parse_args()
    directory = args.directory.resolve()
    if args.ready:
        check_ready(directory, args.environment)
    model = validate(configuration(directory, args.compose or directory / 'compose.yaml', args.image), args.environment)
    check_peer_credentials(model, args.environment, directory)
    if args.ready:
        check_activation(model, args.environment, directory)
    if args.resources:
        check_resources(model)
    print('Environment configuration validated; no resolved values were printed.')


if __name__ == '__main__':
    try:
        main()
    except (InvalidEnvironment, KeyError, ValueError, OSError) as error:
        print(str(error) if isinstance(error, InvalidEnvironment) else 'Environment validation failed.', file=sys.stderr)
        sys.exit(1)
