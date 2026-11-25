#!/usr/bin/env python3
"""Prepare a disposable 1.0 acceptance runtime from the stopped, passed 0.5 disaster runtime."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile


MARKER = 'StarWorld isolated Paper smoke runtime v1'
OLD_CONFIGS = ('config.yml', 'definitions.yml', 'disaster-response.yml', 'evolution.yml',
               'exports.yml', 'life.yml', 'logistics.yml', 'market.yml')
BASE_NEW_CONFIGS = ('civic-needs.yml', 'public-works.yml')


def sha(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def checked(condition, message):
    if not condition:
        raise ValueError(message)


def read(file):
    return json.loads(file.read_text(encoding='utf-8-sig'))


def descriptor(file):
    with zipfile.ZipFile(file) as jar:
        return jar.read('plugin.yml').decode('utf-8'), set(jar.namelist())


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'runtime', 'jar', 'fixture'):
        cli.add_argument('--' + name, type=Path, required=True)
    cli.add_argument('--port', required=True, type=int)
    args = cli.parse_args()
    source, runtime, jar, fixture = [getattr(args, name).resolve() for name in ('source', 'runtime', 'jar', 'fixture')]
    checked(source != runtime and source not in runtime.parents and runtime not in source.parents,
            'Use a separate runtime outside the original')
    checked(not runtime.exists(), 'Preserve earlier attempts; choose a new runtime directory')
    checked(1024 <= args.port < 65536, 'Use a dedicated test port')
    checked((source / '.starworld-smoke-runtime').read_text().strip() == MARKER, 'Not an isolated source')
    latest = read(source / 'disaster-acceptance/latest.json')
    evidence_dir = Path(latest['directory']).resolve()
    checked(evidence_dir.parent == (source / 'disaster-acceptance').resolve(), 'Evidence is not inside the source')
    evidence_file = evidence_dir / 'evidence.json'
    evidence = read(evidence_file)
    checked(evidence['suite'] == 'real-paper-disaster-governance' and latest['status'] == evidence['status'] == 'passed' and len(evidence['steps']) == 22
            and all(step['status'] == 'passed' for step in evidence['steps']), 'Source disaster run did not pass')
    source_jar = source / 'plugins/StarWorld.jar'
    source_sha = sha(source_jar)
    checked(evidence['pluginJars']['StarWorld.jar'] == source_sha, 'Source JAR changed after acceptance')
    checked(re.search(r'^version:\s*[\'\"]?0\.5\.0[\'\"]?\s*$', descriptor(source_jar)[0], re.M), 'Expected 0.5.0 source')
    target_descriptor, target_files = descriptor(jar)
    target_match = re.search(r'^version:\s*[\'\"]?(1\.\d+\.\d+)[\'\"]?\s*$', target_descriptor, re.M)
    checked(target_match, 'Expected a released 1.x StarWorld JAR')
    target_version = target_match.group(1)
    new_configs=BASE_NEW_CONFIGS+(('climate.yml',) if tuple(map(int,target_version.split('.'))) >= (1,2,0) else ())
    checked(set(new_configs) <= target_files,'Target JAR lacks required additive configuration files')
    checked('dev/starworld/fixture/PublicWorksFixture.class' in descriptor(fixture)[1], 'Expected rebuilt 1.0 Paper fixture')
    server_logs = {}
    for name in ('paper-run-1.log', 'paper-run-2.log'):
        file = evidence_dir / name
        log = file.read_text(encoding='utf-8', errors='replace')
        checked('All dimensions are saved' in log and 'OutOfMemoryError' not in log, f'Source was not cleanly stopped: {name}')
        server_logs[name] = sha(file)
    for wal in (source / 'plugins').rglob('*-wal'):
        checked(wal.stat().st_size == 0, f'Uncheckpointed source database: {wal}')
    properties = (source / 'server.properties').read_text(encoding='utf-8')
    checked('server-ip=127.0.0.1' in properties and 'online-mode=false' in properties, 'Source must be a loopback test world')
    level = re.search(r'^level-name=([a-zA-Z0-9_-]+)\s*$', properties, re.M).group(1)
    properties, replaced = re.subn(r'^server-port=\d+\r?$', f'server-port={args.port}', properties, flags=re.M)
    checked(replaced == 1, 'Missing or ambiguous original port')
    configs = {f'plugins/StarWorld/{name}': sha(source / 'plugins/StarWorld' / name) for name in OLD_CONFIGS}
    for name in new_configs:
        checked(not (source / 'plugins/StarWorld' / name).exists(), f'Source already contains {name}')
    world_files = [source / 'plugins/StarWorld/starworld.db', source / 'plugins/StarCore/starcore.db',
                   source / 'plugins/StarCore.jar', source / 'plugins/StarCore/config.yml']
    for folder in [source / level, source / (level + '_nether'), source / (level + '_the_end')]:
        checked((folder / 'level.dat').is_file(), f'Missing saved world: {folder.name}')
        world_files.extend(file for file in folder.rglob('*') if file.is_file() and file.name != 'session.lock')
    critical = {file.relative_to(source).as_posix(): sha(file) for file in sorted(world_files)}
    final = evidence['steps'][-1]['observed']
    checked(final['clean'], 'Missing source normal shutdown observation')
    player = {'username': 'StarWorldDA', 'uuid': evidence['steps'][0]['observed']['clients'][0],
              'cityId': final['final']['city']['id'], 'nationId': final['final']['city']['nationId']}
    checked(f'{level}/playerdata/{player["uuid"]}.dat' in critical, 'Source player data is missing')
    shutil.copytree(source, runtime, ignore=shutil.ignore_patterns('logs', 'crash-reports', '*-acceptance', '*.log',
                    '*.pid', '.paper-remapped', '*.hprof', '*-wal', '*-shm', 'session.lock', 'PREPARATION.json', 'UNIT-EVIDENCE.json'))
    for relative, digest in {**critical, **configs}.items():
        checked(sha(runtime / relative) == digest, f'Copied input differs: {relative}')
    (runtime / 'server.properties').write_text(properties, encoding='utf-8', newline='\n')
    products = [('StarWorld.jar', jar), ('StarWorldFixture.jar', fixture)]
    for name, file in products:
        shutil.copy2(file, runtime / 'plugins' / name)
        checked(sha(runtime / 'plugins' / name) == sha(file), f'Installed JAR differs: {name}')
    proof = {'schema': 1, 'sourceRuntime': str(source), 'runtime': str(runtime), 'sourceVersion': '0.5.0',
             'targetVersion': target_version, 'sourcePluginSha256': source_sha, 'targetPluginSha256': sha(jar), 'fixtureSha256': sha(fixture),
             'preparationStatus': 'prepared-not-started', 'processStarted': False, 'preparedAt': datetime.now(timezone.utc).isoformat(),
             'sourceEvidence': {'directory': str(evidence_dir), 'jsonSha256': sha(evidence_file), 'status': 'passed', 'steps': 22,
                                'pluginSha256': source_sha, 'finishedAt': evidence['finishedAt'], 'serverLogSha256': server_logs},
             'sourceDatabaseSha256': {'world': critical['plugins/StarWorld/starworld.db'], 'core': critical['plugins/StarCore/starcore.db']},
             'sourceCriticalFiles': critical, 'oldConfigurationSha256': configs, 'newConfigurationNames': list(new_configs), 'testPlayer': player,
             'serverHost': '127.0.0.1', 'serverPort': args.port, 'serverPropertiesSha256': sha(runtime / 'server.properties')}
    (runtime / 'UPGRADE-PROVENANCE.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Prepared {runtime}: {len(critical)} stopped-save fingerprints, {len(configs)} original configuration files, no process started')


if __name__ == '__main__':
    main()
