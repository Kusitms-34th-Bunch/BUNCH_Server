#!/usr/bin/env python3
"""Read-only inventory by default; --apply stages files, never starts/stops containers.

Run the reviewed deploy directory on the server as bunch. No legacy .env is read,
copied or printed. Database/Redis backup and restore testing are operator gates.
"""
import argparse
import json
import os
import pwd
from pathlib import Path
import shutil
import subprocess
import sys


class PreparationError(Exception):
    pass


def require(value, message):
    if not value:
        raise PreparationError(message)


def docker(*args):
    result = subprocess.run(['docker', *args], capture_output=True, text=True)
    require(result.returncode == 0, 'Docker inventory failed; no container environment was printed.')
    return result.stdout.strip()


def inventory():
    # Never request Config.Env, Config.Cmd, full inspect or a rendered Compose model.
    result = {}
    for service in ('app', 'postgres', 'redis', 'cloudflared', 'alloy', 'loki', 'prometheus', 'grafana'):
        ids = docker('ps', '-q', '--filter', 'label=com.docker.compose.project=bunch',
                     '--filter', f'label=com.docker.compose.service={service}').splitlines()
        require(len(ids) == 1, f'Expected exactly one running legacy {service} container.')
        data = json.loads(docker('inspect', '--format',
                         '{"image":{{json .Image}},"mounts":{{json .Mounts}},"networks":{{json .NetworkSettings.Networks}}}', ids[0]))
        digests = json.loads(docker('image', 'inspect', '--format', '{{json .RepoDigests}}', data['image']))
        require(bool(digests), f'No repository digest available for {service}; pin it before migration.')
        result[service] = {
            'container': ids[0], 'image': digests[0],
            'volumes': {m['Destination']: m['Name'] for m in data['mounts'] if m['Type'] == 'volume'},
            'binds': {m['Destination']: m['Source'] for m in data['mounts'] if m['Type'] == 'bind'},
            'networks': sorted(data['networks']),
        }
    require(result['postgres']['volumes'].get('/var/lib/postgresql/data') == 'bunch-pgdata', 'Legacy PostgreSQL volume differs; stop.')
    require(result['redis']['volumes'].get('/data') == 'bunch-redis-data', 'Legacy Redis volume differs; stop.')
    for service in ('app', 'postgres', 'redis', 'cloudflared'):
        require('bunch_default' in result[service]['networks'], f'Legacy {service} network differs; stop.')
    for service, destination in [('alloy', '/var/lib/alloy/data'), ('loki', '/loki'),
                                 ('prometheus', '/prometheus'), ('grafana', '/var/lib/grafana')]:
        require(result[service]['volumes'].get(destination), f'Existing {service} data volume not identified; stop.')
    return result


def stage(source, destination):
    require(source.is_file(), 'Reviewed deployment source is incomplete.')
    require(not destination.exists() and not destination.is_symlink(), 'Target configuration already exists; do not overwrite it.')
    shutil.copyfile(source, destination)
    destination.chmod(0o600)


def prepare(root, source, backup, observed, apply=False):
    require((root / 'compose.yaml').is_file(), 'Expected legacy home Compose file.')
    for directory in (root, root / 'dev', root / 'prod', root / 'monitoring'):
        require(not directory.is_symlink(), 'Refuse symlinked environment directories.')
    outputs = [
        (source / 'compose.yaml', root / 'dev/compose.yaml'),
        (source / 'compose.yaml', root / 'prod/compose.yaml'),
        (source / '.env.example', root / 'dev/.env.example'),
        (source / 'environments/.env.example', root / 'prod/.env.example'),
        (source / 'environment_check.py', root / 'dev/environment_check.py'),
        (source / 'environment_check.py', root / 'prod/environment_check.py'),
        (source / 'monitoring/compose.yaml', root / 'monitoring/compose.yaml'),
        (source / 'monitoring/.env.example', root / 'monitoring/.env.example'),
    ]
    for src, dst in outputs:
        require(src.is_file(), 'Reviewed deployment source is incomplete.')
        require(not dst.exists() and not dst.is_symlink(), 'Target configuration already exists; inspect partial preparation privately.')
    inventory_path = root / 'environment-inventory.json'
    require(not inventory_path.exists() and not inventory_path.is_symlink(),
            'Inventory already exists; inspect partial preparation privately.')
    dictionary_link = root / 'dev/.legacy-profanity'
    require(not dictionary_link.exists() and not dictionary_link.is_symlink(), 'Legacy dictionary link already exists.')
    if apply:
        require(backup is not None and backup.resolve() != root, 'Provide an external backup directory.')
        require(backup.is_dir(), 'Backup directory is missing.')
        # The operator creates this marker ONLY after a verified recovery rehearsal.
        require((backup / 'restore-tested').is_file(), 'Backup restore rehearsal marker is missing.')
        for name in ('postgres.dump', 'redis-backup.tar', 'legacy-compose.yaml'):
            require((backup / name).is_file() and (backup / name).stat().st_size > 0, 'Required nonempty backup file missing.')
    print('Legacy project bunch and development data volumes verified.')
    print('Preserve all current containers, volumes, networks and legacy home files.')
    print('Stage reviewed Compose/validator/example files into ~/dev, ~/prod, ~/monitoring.')
    print('No secrets are copied; no readiness marker is created; no containers are changed.')
    if not apply:
        print('DRY RUN: no files or Docker resources changed. Re-run --apply only after backup restore rehearsal.')
        return
    for directory in ('dev', 'prod', 'monitoring'):
        (root / directory).mkdir(mode=0o700, exist_ok=True)
    for src, dst in outputs:
        stage(src, dst)
    # Retain the exact old dictionary location instead of copying/deleting it.
    dictionary = observed['app']['binds'].get('/app/profanity')
    if dictionary:
        (root / 'dev/.legacy-profanity').symlink_to(dictionary, target_is_directory=True)
    path = root / 'environment-inventory.json'
    with path.open('x') as output:
        json.dump(observed, output, indent=2)
        output.write('\n')
    path.chmod(0o600)
    print('Staged files. Populate separate .env files privately, preserve dictionary mount, then follow the runbook.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup', type=Path)
    args = parser.parse_args()
    require(pwd.getpwuid(os.getuid()).pw_name == 'bunch', 'Run as the bunch account.')
    root = Path.home()
    prepare(root, Path(__file__).resolve().parent, args.backup, inventory(), args.apply)


if __name__ == '__main__':
    try:
        main()
    except (PreparationError, OSError, ValueError, KeyError) as error:
        print(str(error) if isinstance(error, PreparationError) else 'Preparation failed; inspect staged files before retrying.', file=sys.stderr)
        sys.exit(1)
