#!/usr/bin/env python3
"""Copy a passed, stopped 1.0 life source for current-candidate acceptance.

Preserve the two databases and all world/player files byte for byte. Only the
candidate JAR, server port and the two declared culture caps change. No server is
started and no SQL is written. Failed or unfinished source runs are rejected.
"""

import argparse
from contextlib import closing
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import zipfile


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


inputs = module('paper_inputs', 'prepare-paper-acceptance.py')
packager = module('evidence_packager', 'package-evidence.py')
checked, sha, ordinary = inputs.checked, inputs.sha, inputs.ordinary
EXCLUDED = {'logs', 'crash-reports', '.paper-remapped', '__pycache__', 'session.lock',
            'PREPARATION.json', 'LIFECYCLE-PREPARATION.json', 'UNIT-EVIDENCE.json'}


def caps(data):
    text = data.decode('utf-8')
    for key in ('player-culture-per-period', 'city-culture-per-period'):
        text, count = re.subn(r'(?m)^(\s*' + key + r':\s*)[0-9]+(\s*(?:#.*)?)$',
                             lambda match: match[1] + '20' + match[2], text)
        checked(count == 1, 'Exactly one numeric ' + key + ' is required')
    return text.encode('utf-8')


def source_input(root, evidence_file):
    evidence_file = ordinary(evidence_file)
    evidence = inputs.read_json(evidence_file)
    source = evidence_file.parent.parent.parent
    checked(evidence_file.name == 'evidence.json' and evidence_file.parent.parent.name == 'lifecycle-acceptance'
            and evidence_file.parent.name.startswith('source-'), 'Expected a lifecycle source evidence directory')
    checked(Path(os.path.abspath(evidence.get('runtime', ''))) == source, 'Source evidence runtime differs')
    checked(evidence.get('schema') == 1 and evidence.get('status') == 'passed'
            and evidence.get('suite') == 'real-paper-life-carry' and evidence.get('mode') == 'source'
            and evidence.get('version') == '1.0.0', 'A passed real 1.0 lifecycle source is required')
    checked(not any(key in evidence for key in ('error', 'cleanupError', 'playerCleanupError')),
            'Source retains an execution or cleanup error')
    runner = ordinary(root / 'tools/senluo-fixture/lifecycle-acceptance.cjs')
    helper = ordinary(root / 'tools/paper-client/acceptance.cjs')
    names = packager.stage_names(runner.read_bytes(), str(runner))[:4]
    checked(len(names) == 4 and [row.get('name') for row in evidence.get('steps', [])] == names
            and all(row.get('status') == 'passed' for row in evidence['steps']),
            'Source did not pass every current lifecycle source stage')
    checked(inputs.inside(source, '.starworld-smoke-runtime').read_text().strip() == inputs.MARKER,
            'Source is not an owned isolated runtime')
    after = evidence['after']
    checked(inputs.read_json(evidence_file.parent / 'stopped-source-final.json') == after,
            'Stopped source snapshot differs from evidence')
    for label, folder, filename in (('world', 'StarWorld', 'starworld.db'), ('core', 'StarCore', 'starcore.db')):
        db = inputs.inside(source, f'plugins/{folder}/{filename}')
        wal = Path(str(db) + '-wal')
        checked(not wal.exists() or ordinary(wal).stat().st_size == 0, 'Source has uncheckpointed WAL: ' + str(wal))
        checked(sha(db) == after[label]['sha256'], 'Source database changed after its passed run: ' + label)
        with closing(sqlite3.connect(db.as_uri() + '?mode=ro&immutable=1', uri=True)) as connection:
            checked(connection.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
                    and connection.execute('PRAGMA foreign_key_check').fetchall() == [],
                    'Source database integrity check failed: ' + label)
    proof = evidence['preparation']
    plugin_files = {file.name: sha(ordinary(file)) for file in (source / 'plugins').glob('*.jar')}
    checked(plugin_files == proof['pluginJars'], 'Source plugins changed after acceptance')
    configs = {file.name: sha(ordinary(file)) for file in (source / 'plugins/StarWorld').glob('*.yml')}
    checked(configs == proof['configSha256'] and 'climate.yml' not in configs,
            'Source configurations changed or are already upgraded')
    log = ordinary(evidence_file.parent / 'paper-run-1.log').read_bytes()
    checked(packager.clean_shutdown(log), 'Source lacks a clean Paper shutdown')
    packager.validate_paper_resource_log(log, 'lifecycle source', sha(runner), sha(helper))
    ordinary(evidence_file.parent / 'client-run-1.log')
    rows = after['world']['rows']
    checked(rows.get('life_schema') == [{'id': 1, 'version': 1}]
            and 'credited_culture' not in after['world']['columns']['life_evolution_action'],
            'Source has already migrated from life schema1')
    consumed = {row['id'] for row in rows['life_evolution_action']}
    pending = [row for row in rows['life_action'] if row.get('eligible') == 1 and row['id'] not in consumed]
    checked(consumed and pending == [evidence['oldAction']] and evidence['oldActionId'] == pending[0]['id']
            and pending[0]['culture'] == 25 and pending[0]['kind'] == 'feast' and pending[0]['source'] == 'cookery',
            'Source does not preserve exactly one real unconsumed Cookery25 action')
    return source, evidence


def prepare(args):
    root = Path(os.path.abspath(args.repository_root))
    runtime = Path(os.path.abspath(args.runtime))
    checked(not runtime.exists(), 'Preserve previous attempts; candidate runtime already exists')
    parent = inputs.unlinked(next(directory for directory in runtime.parents if directory.exists()))
    checked(parent.is_dir(), 'Candidate parent must be a directory')
    source, evidence = source_input(root, args.source_evidence)
    checked(source != runtime and source not in runtime.parents and runtime not in source.parents,
            'Source and candidate must use separate trees')
    checked(1024 <= args.port <= 65535, 'Port must be between 1024 and 65535')
    jar = ordinary(args.jar)
    with zipfile.ZipFile(jar) as archive:
        checked(archive.testzip() is None, 'Candidate JAR failed CRC validation')
        match = re.search(r'^version:\s*[\'"]?(1\.[1-9]\d*\.\d+)[\'"]?\s*$',
                          archive.read('plugin.yml').decode('utf-8'), re.M)
        checked(match, 'Expected a released candidate newer than 1.0')
        version = match[1]
    unit = inputs.read_json(root / 'docs/evidence/unit-tests.json')
    checked(unit['version'] == version and unit['artifact_sha256'] == sha(jar)
            and unit['totals']['tests'] > 0
            and all(unit['totals'][key] == 0 for key in ('failures', 'errors', 'skipped')),
            'Candidate requires its own clean unit verification')
    properties = inputs.inside(source, 'server.properties').read_text(encoding='utf-8')
    settings = inputs.properties(properties)
    checked(settings.get('server-ip') == '127.0.0.1' and settings.get('online-mode') == 'false',
            'Source must be an isolated loopback runtime')
    properties = inputs.replace_property(properties, 'server-port', args.port)
    life = caps(inputs.inside(source, 'plugins/StarWorld/life.yml').read_bytes())
    files = []
    for directory, subdirs, names in os.walk(source, followlinks=False):
        subdirs[:] = [name for name in subdirs if name not in EXCLUDED and not name.endswith('-acceptance')]
        for name in subdirs:
            inputs.unlinked(Path(directory) / name)
        for name in names:
            if name in EXCLUDED or name.endswith(('.log', '.hprof', '.pid', '-wal', '-shm')):
                continue
            file = ordinary(Path(directory) / name)
            relative = file.relative_to(source)
            linked = args.link_bootstrap and file.suffix == '.jar' and (relative.parts[0] in ('cache', 'libraries', 'versions') or relative.as_posix() == 'paper.jar')
            files.append((file, relative, sha(file), linked))
    required = sum(file.stat().st_size for file, _, _, linked in files if not linked) + jar.stat().st_size + 1024**3
    command = [str(args.node), '--max-old-space-size=128', '--max-semi-space-size=8',
               str(root / 'tools/senluo-fixture/lifecycle-acceptance.cjs'), '--mode', 'candidate',
               '--java', str(ordinary(args.java)), '--paper', str(ordinary(args.paper)),
               '--runtime', str(runtime), '--python', sys.executable,
               '--client-deps', str(Path(args.client_deps).absolute()),
               '--source-evidence', str(ordinary(args.source_evidence))]
    proof = dict(schema=1, mode='candidate', status='checked-not-created' if args.check_only else 'prepared-not-started',
        runtime=str(runtime), source=str(source), sourceVersion='1.0.0', targetVersion=version,
        createdAt=datetime.now(timezone.utc).isoformat(), processStarted=False, command=command,
        targetPluginSha256=sha(jar), sourceEvidenceSha256=sha(ordinary(args.source_evidence)),
        sourceDatabaseSha256={label: evidence['after'][label]['sha256'] for label in ('world', 'core')},
        sourceFiles=len(files), requiredFreeDiskBytes=required, bootstrapHardlinks=bool(args.link_bootstrap))
    if args.check_only:
        return proof
    # Recheck the authoritative database fingerprints after file enumeration;
    # preparing from a source that restarted during planning must fail closed.
    for label, folder, filename in (('world', 'StarWorld', 'starworld.db'), ('core', 'StarCore', 'starcore.db')):
        checked(sha(source / 'plugins' / folder / filename) == proof['sourceDatabaseSha256'][label],
                'Source database changed during preparation: ' + label)
    checked(shutil.disk_usage(parent).free >= required, 'Candidate copy needs its file sizes plus 1 GiB free disk reserve')
    runtime.mkdir(parents=True, exist_ok=False)
    for file, relative, fingerprint, linked in files:
        target = runtime / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        os.link(file, target) if linked else shutil.copyfile(file, target)
        checked(sha(target) == sha(file) == fingerprint, 'Source changed while copying: ' + str(relative))
    shutil.copyfile(jar, runtime / 'plugins/StarWorld.jar')
    (runtime / 'plugins/StarWorld/life.yml').write_bytes(life)
    (runtime / 'server.properties').write_text(properties, encoding='utf-8')
    checked(sha(runtime / 'plugins/StarWorld.jar') == proof['targetPluginSha256'], 'Copied candidate JAR differs')
    for label, folder, filename in (('world', 'StarWorld', 'starworld.db'), ('core', 'StarCore', 'starcore.db')):
        relative = Path('plugins') / folder / filename
        checked(sha(source / relative) == sha(runtime / relative) == proof['sourceDatabaseSha256'][label],
                'Source or copied database changed during preparation: ' + label)
        for directory in (source, runtime):
            wal = Path(str(directory / relative) + '-wal')
            checked(not wal.exists() or ordinary(wal).stat().st_size == 0,
                    'Database became active during preparation: ' + str(wal))
    (runtime / 'LIFECYCLE-PREPARATION.json').write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    return proof


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--repository-root', type=Path, default=Path(__file__).absolute().parent.parent)
    for name in ('source-evidence', 'runtime', 'jar', 'java', 'paper', 'client-deps'):
        cli.add_argument('--' + name, type=Path, required=True)
    cli.add_argument('--port', type=int, required=True)
    cli.add_argument('--node', type=Path, default=Path('node'))
    cli.add_argument('--link-bootstrap', action='store_true')
    cli.add_argument('--check-only', action='store_true')
    try:
        print(json.dumps(prepare(cli.parse_args()), indent=2))
    except (ValueError, OSError, KeyError, sqlite3.Error, zipfile.BadZipFile) as error:
        cli.exit(1, f'Candidate preparation failed: {error}\n')


if __name__ == '__main__':
    main()
