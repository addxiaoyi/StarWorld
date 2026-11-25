#!/usr/bin/env python3
"""Prepare fresh Paper acceptance inputs from an owned runtime's static files.

Never copy worlds, players, databases, old transcripts or remapped plugins. This
tool does not start Java, accept the EULA, install dependencies or download files.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import zipfile


MARKER = 'StarWorld isolated Paper smoke runtime v1'
RUNNERS = {
    'baseline': 'paper-client/acceptance.cjs',
    'expansion': 'paper-client/expansion-acceptance.cjs',
    'progression': 'paper-client/progression-acceptance.cjs',
    'border': 'paper-client/border-acceptance.cjs',
    'operations': 'paper-client/operations-acceptance.cjs',
    'disaster': 'paper-client/disaster-acceptance.cjs',
    'works': 'paper-client/works-acceptance.cjs',
    'storage': 'paper-client/survey-acceptance.cjs',
    'corridor': 'paper-client/corridor-acceptance.cjs',
    'refund': 'paper-client/refund-acceptance.cjs',
    'tax': 'paper-client/tax-acceptance.cjs',
    'lifecycle-logistics': 'paper-client/lifecycle-logistics-acceptance.cjs',
    'civic': 'senluo-fixture/civic-acceptance.cjs',
    'smoke': 'smoke-paper.ps1',
}
CIVIC_JVM = ['-Xms128m', '-Xmx768m', '-Xss512k', '-XX:ActiveProcessorCount=1',
             '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1',
             '-XX:ReservedCodeCacheSize=64m', '-XX:MaxMetaspaceSize=384m',
             '-Dio.netty.eventLoopThreads=2']


def checked(condition, message):
    if not condition:
        raise ValueError(message)


def sha(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def unlinked(path):
    """Reject links before resolving them, including Windows junctions."""
    path = Path(os.path.abspath(path))
    for item in [*reversed(path.parents), path]:
        info = item.lstat()
        checked(not stat.S_ISLNK(info.st_mode) and not getattr(info, 'st_file_attributes', 0) & 0x400,
                f'Linked input is not allowed: {item}')
    return path


def ordinary(path):
    path = unlinked(path)
    checked(path.is_file(), f'Expected a regular file: {path}')
    return path


def inside(root, relative):
    checked(isinstance(relative, str) and relative and '\\' not in relative and ':' not in relative
            and not relative.startswith('/') and all(part not in ('', '.', '..') for part in relative.split('/')),
            f'Invalid static input path: {relative!r}')
    return ordinary(root / relative)


def read_json(file):
    return json.loads(ordinary(file).read_text(encoding='utf-8-sig'))


def properties(text):
    rows = re.findall(r'^([^#!\s=]+)=(.*?)\r?$', text, re.M)
    checked(len(rows) == len(dict(rows)), 'Duplicate server property')
    return dict(rows)


def replace_property(text, key, value):
    text, count = re.subn(r'^' + re.escape(key) + r'=.*?\r?$',
                          lambda _: key + '=' + str(value), text, flags=re.M)
    checked(count == 1, f'Missing or ambiguous server property: {key}')
    return text


def prepare(args):
    root = Path(os.path.abspath(args.repository_root))
    template = Path(os.path.abspath(args.template))
    runtime = Path(os.path.abspath(args.runtime))
    checked(not runtime.exists(), f'Preserve previous attempts; runtime already exists: {runtime}')
    parent = next(directory for directory in runtime.parents if directory.exists())
    unlinked(parent)
    checked(parent.is_dir(), f'Runtime parent is not a directory: {parent}')
    checked(template != runtime and template not in runtime.parents and runtime not in template.parents,
            'Use separate template and runtime trees')
    checked(1024 <= args.port <= 65535, 'Port must be between 1024 and 65535')
    checked(re.fullmatch(r'[A-Za-z0-9_-]+', runtime.name), 'Use a simple runtime directory name')
    checked(inside(template, '.starworld-smoke-runtime').read_text().strip() == MARKER,
            'Template is not an owned isolated Paper runtime')
    # This copies the existing choice, including eula=false, without accepting it.
    eula = inside(template, 'eula.txt').read_text(encoding='utf-8')
    checked(properties(eula).get('eula') in ('true', 'false'), 'Template lacks an explicit EULA choice')
    props = inside(template, 'server.properties').read_text(encoding='utf-8')
    values = properties(props)
    checked(values.get('server-ip') == '127.0.0.1' and values.get('online-mode') == 'false',
            'Template must explicitly use loopback and offline mode')
    props = replace_property(replace_property(props, 'server-port', args.port),
                             'level-name', runtime.name + '-world')

    jar = ordinary(args.jar)
    fixture = ordinary(args.fixture) if args.fixture is not None and args.suite != 'smoke' else None
    paper = ordinary(args.paper)
    java = ordinary(args.java)
    with zipfile.ZipFile(jar) as archive:
        checked(archive.testzip() is None, 'Product JAR failed CRC validation')
        descriptor = archive.read('plugin.yml').decode('utf-8')
        match = re.search(r'^version:\s*[\'"]?((?:0\.5|1\.\d+)\.\d+)[\'"]?\s*$', descriptor, re.M)
        checked(match is not None, 'Expected a released StarWorld 0.5 or 1.x product JAR')
        checked(re.search(r'^name:\s*StarWorld\s*$', descriptor, re.M), 'Expected the StarWorld plugin')
        version = match.group(1)
        checked(not version.startswith('0.') or args.suite == 'disaster',
                'StarWorld 0.5 is supported only as a disaster migration source')
        defaults = {name: archive.read(name) for name in archive.namelist()
                    if '/' not in name and name.endswith('.yml') and name != 'plugin.yml'}

    files = {}

    def include(relative, source=None):
        file = ordinary(source) if source else inside(template, relative)
        checked(relative not in files or files[relative] == file, f'Conflicting static input: {relative}')
        files[relative] = file

    # Only immutable bootstrap JARs may optionally share disk blocks. Every
    # configuration, plugin and asset remains an independent copy.
    bootstrap = set()
    for folder in ('cache', 'libraries', 'versions'):
        checked((template / folder).is_dir(), f'Missing Paper bootstrap directory: {folder}')
        for file in sorted((template / folder).rglob('*')):
            checked(not file.is_symlink() and not getattr(file.lstat(), 'st_file_attributes', 0) & 0x400,
                    f'Linked bootstrap input: {file}')
            if file.is_file():
                checked(file.suffix == '.jar', f'Unexpected mutable bootstrap input: {file}')
                relative = file.relative_to(template).as_posix()
                include(relative)
                bootstrap.add(relative)
    checked(bootstrap, 'Paper bootstrap cache is empty')
    for name in ('bukkit.yml', 'spigot.yml', 'commands.yml', 'help.yml', 'permissions.yml'):
        if (template / name).is_file():
            include(name)
    if (template / 'config').is_dir():
        for file in sorted((template / 'config').rglob('*.yml')):
            include(file.relative_to(template).as_posix())
    include('paper.jar', paper)
    bootstrap.add('paper.jar')
    include('plugins/StarWorld.jar', jar)
    if args.suite != 'smoke':
        checked(fixture is not None, 'Gameplay preparation needs --fixture')
        include('plugins/StarCore.jar', getattr(args, 'starcore', None))
        if getattr(args, 'starcore', None):
            checked(args.suite in ('refund', 'tax'), 'Explicit StarCore candidate is only supported by the refund and tax suites')
            checked(getattr(args, 'starcore_provenance', None), 'Explicit StarCore needs --starcore-provenance')
            provenance = read_json(args.starcore_provenance)
            checked(provenance['artifact_sha256'] == sha(files['plugins/StarCore.jar']), 'StarCore provenance hash differs')
            checked(re.fullmatch('[0-9a-f]{40}', provenance['base']) and re.fullmatch('[0-9a-f]{40}', provenance['commit']), 'StarCore provenance requires base and commit')
            checked(provenance['tests']['tests'] > 0 and provenance['tests']['failures'] == provenance['tests']['errors'] == 0, 'StarCore candidate verification failed')
            include('STARCORE-PROVENANCE.json', args.starcore_provenance)
        include('plugins/StarCore/config.yml')
        include('plugins/StarWorldFixture.jar', fixture)
    generated = {
        '.starworld-smoke-runtime': (MARKER + '\n').encode(),
        'eula.txt': eula.encode(),
        'server.properties': props.encode(),
    }
    configs = template / 'plugins/StarWorld'
    if args.suite != 'smoke':
        for file in sorted(configs.glob('*.yml')):
            include('plugins/StarWorld/' + file.name)
        for name, data in defaults.items():
            relative = 'plugins/StarWorld/' + name
            if relative not in files:
                generated[relative] = data

    civic = None
    if args.suite in ('corridor', 'refund', 'tax'):
        relative = 'plugins/StarWorld/logistics.yml'
        original = files.pop(relative).read_text(encoding='utf-8') if relative in files else generated[relative].decode()
        adjusted, count = re.subn(r'^blocks-per-period:.*$', 'blocks-per-period: 8', original, flags=re.M)
        checked(count == 1, 'Corridor fixture requires one blocks-per-period setting')
        generated[relative] = adjusted.encode()
    if args.suite == 'civic':
        civic = read_json(template / 'PREPARATION.json')
        checked(args.senluo_fixture is not None, 'Civic preparation needs --senluo-fixture')
        checked(len(civic['installedPlugins']) == 9, 'Expected nine audited civic plugins')
        checked(len({row['file'] for row in civic['installedPlugins']}) == 9, 'Duplicate civic plugin')
        for row in civic['installedPlugins']:
            name = row['file']
            checked(Path(name).name == name and '/' not in name and '\\' not in name, 'Invalid civic JAR name')
            if name not in ('StarWorld.jar', 'StarWorldFixture.jar', 'StarWorldSenLuoFixture.jar'):
                include('plugins/' + name)
                checked(sha(files['plugins/' + name]) == row['sha256'], f'Audited plugin changed: {name}')
        include('plugins/StarWorldSenLuoFixture.jar', args.senluo_fixture)
        include('UPSTREAM-PROVENANCE.json')
        for name in civic['dependencyFilesSha256']:
            # The existing preparation was independently validated by the civic
            # runner. It may only declare static assets and configuration files.
            checked(not re.search(r'\.(?:db|sqlite3?|dat|mca)(?:-|$)', name, re.I),
                    f'Authority/world file is not a static dependency: {name}')
            checked(name in ('server.properties', 'bukkit.yml', 'spigot.yml') or
                    name.startswith(('config/', 'plugins/StarCore/', 'plugins/CraftEngine/',
                                     'plugins/KaleidoscopeCookeryPlugin/', 'plugins/KaleidoscopeTavernPlugin/')),
                    f'Unexpected civic dependency: {name}')
            if name != 'server.properties':
                include(name)
                checked(sha(files[name]) == civic['dependencyFilesSha256'][name],
                        f'Audited dependency changed: {name}')
        asset_root = template / 'asset-inputs'
        for file in sorted(asset_root.rglob('*')):
            if file.is_file():
                include(file.relative_to(template).as_posix())
        manifest = read_json(template / 'asset-inputs/kitchen-crops-3/manifest.json')
        checked(len(manifest['files']) == 2399, 'Expected the audited 2,399-file kitchen pack')
        for name, fingerprint in manifest['files'].items():
            checked(name.startswith('cookery_kitchen/'), f'Unexpected kitchen asset: {name}')
            extracted = inside(template, 'asset-inputs/kitchen-crops-3/' + name)
            active = inside(template, 'plugins/CraftEngine/resources/' + name)
            checked(sha(extracted) == sha(active) == fingerprint, f'Audited kitchen asset changed: {name}')
            include('plugins/CraftEngine/resources/' + name)
        # CE's local Java dependency cache is static and avoids new dependency
        # downloads. Never copy CE's databases or generated resource-pack cache.
        for file in sorted((template / 'plugins/CraftEngine/libs').rglob('*.jar')):
            include(file.relative_to(template).as_posix())
        unit = ordinary(getattr(args, 'unit_evidence', None) or root / 'docs/evidence/unit-tests.json')
        evidence = read_json(unit)
        checked(evidence['version'] == version and evidence['artifact_sha256'] == sha(jar),
                'Unit evidence belongs to another product build')
        checked(evidence['totals']['tests'] > 0 and all(evidence['totals'][key] == 0
                for key in ('failures', 'errors', 'skipped')), 'Clean unit verification is required')
        include('UNIT-EVIDENCE.json', unit)

    runner = ordinary(root / 'tools' / RUNNERS[args.suite])
    helper = ordinary(root / 'tools/paper-client/acceptance.cjs')
    command = [str(args.node), '--max-old-space-size=' + ('128' if civic else '192'),
               '--max-semi-space-size=8', str(runner), '--java', str(java),
               '--paper', str(runtime / 'paper.jar'), '--runtime', str(runtime),
               '--client-deps', str(Path(args.client_deps).absolute())]
    if args.suite in ('operations', 'lifecycle-logistics'):
        command += ['--python', sys.executable]
    if args.suite == 'smoke':
        command = [str(args.powershell), '-NoProfile', '-File', str(runner),
                   '-JavaPath', str(java), '-ServerJar', str(runtime / 'paper.jar'),
                   '-PluginJar', str(jar), '-RuntimeDirectory', str(runtime), '-Port', str(args.port)]
    proof = dict(schema=1, status='prepared', suite=args.suite, runtime=str(runtime),
                 sourceStatic=str(template), createdAt=datetime.now(timezone.utc).isoformat(),
                 productVersion=version, targetPluginSha256=sha(jar), fixtureSha256=sha(fixture) if fixture else None,
                 paperSha256=sha(paper), starCoreSha256=sha(files['plugins/StarCore.jar']) if fixture else None,
                 runnerSha256=sha(runner), acceptanceHelperSha256=sha(helper),
                 serverPort=args.port, artifactsBound=True, fresh=True,
                 worldsCopied=0, databasesCopied=0, playersCopied=0, processStarted=False,
                 eulaAccepted=properties(eula)['eula'] == 'true', command=command,
                 bootstrapHardlinks=bool(args.link_bootstrap))
    if args.check_only:
        return {**proof, 'status': 'checked-not-created', 'inputFiles': len(files),
                'generatedFiles': len(generated), 'runtimeCreated': False}

    required = sum(source.stat().st_size for relative, source in files.items()
                   if not args.link_bootstrap or relative not in bootstrap)
    required += sum(len(data) for data in generated.values()) + 1024 * 1024 * 1024
    checked(shutil.disk_usage(parent).free >= required,
            f'Preparation needs {required // (1024 * 1024)} MiB free disk including a 1 GiB reserve')
    runtime.mkdir(parents=True, exist_ok=False)
    for relative, source in files.items():
        target = runtime / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if args.link_bootstrap and relative in bootstrap:
            os.link(source, target)
        else:
            shutil.copyfile(source, target)
    for relative, data in generated.items():
        target = runtime / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    proof['configurationSha256'] = {
        file.relative_to(runtime).as_posix(): sha(file)
        for file in sorted(runtime.rglob('*.yml'))}
    proof['configurationSha256']['server.properties'] = sha(runtime / 'server.properties')
    if civic:
        proof = {**civic, **proof}
        proof.update(status='prepared', runtime=str(runtime), sourceStatic=str(template),
                     createdAtUtc=datetime.now(timezone.utc).isoformat(),
                     starWorldVersion=version, starWorldSha256=sha(jar),
                     productVersion=version, targetPluginSha256=sha(jar),
                     fixtureSha256=sha(fixture), processStarted=False, worldsCopied=0,
                     authorityFilesCopied=0, paperSha256=sha(paper), javaSha256=sha(java),
                     senluoFixtureSha256=sha(runtime / 'plugins/StarWorldSenLuoFixture.jar'))
        proof['installedPlugins'] = [dict(file=file.name, sha256=sha(file), bytes=file.stat().st_size)
                                     for file in sorted((runtime / 'plugins').glob('*.jar'))]
        proof['unitEvidence'] = dict(file='UNIT-EVIDENCE.json', sha256=sha(runtime / 'UNIT-EVIDENCE.json'))
        proof['configSha256'] = {file.name: sha(file) for file in sorted((runtime / 'plugins/StarWorld').glob('*.yml'))}
        proof['dependencyFilesSha256'] = {name: sha(runtime / name) for name in civic['dependencyFilesSha256']}
        proof['newAssetPreparation'] = {**civic['newAssetPreparation'],
                                      'activeCookeryResources': str(runtime / 'plugins/CraftEngine/resources/cookery_kitchen')}
        proof['testTuning'] = {**civic['testTuning'], 'port': args.port, 'jvmArgs': CIVIC_JVM,
                               'nodeArgs': command[1:3]}
    (runtime / 'PREPARATION.json').write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    return proof


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--repository-root', type=Path, default=Path(__file__).absolute().parent.parent)
    cli.add_argument('--suite', choices=RUNNERS, required=True)
    for name in ('template', 'runtime', 'jar', 'paper', 'java', 'client-deps'):
        cli.add_argument('--' + name, type=Path, required=True)
    cli.add_argument('--fixture', type=Path)
    cli.add_argument('--starcore', type=Path, help='Explicit locally verified StarCore candidate for refund/tax acceptance')
    cli.add_argument('--starcore-provenance', type=Path, help='Candidate commit, JAR hash and build verification')
    cli.add_argument('--senluo-fixture', type=Path)
    cli.add_argument('--unit-evidence', type=Path,
                     help='Clean verification for the exact JAR (for a rebuilt historical civic source)')
    cli.add_argument('--node', type=Path, default=Path('node'))
    cli.add_argument('--powershell', type=Path, default=Path('pwsh'))
    cli.add_argument('--port', type=int, required=True)
    cli.add_argument('--link-bootstrap', action='store_true', help='Hardlink only immutable bootstrap JARs on the same volume')
    cli.add_argument('--check-only', action='store_true', help='Validate all inputs without creating a runtime')
    args = cli.parse_args()
    try:
        print(json.dumps(prepare(args), indent=2))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        cli.exit(1, f'Preparation failed: {error}\n')


if __name__ == '__main__':
    main()
