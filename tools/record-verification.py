#!/usr/bin/env python3
"""Bind successful Maven XML/coverage and exact source bytes to the current artifact.

No Java, network or database activity. Previous-version evidence is copied to
history before any current evidence is replaced; failed builds are rejected.
"""
from pathlib import Path
import argparse
import datetime as dt
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
import zipfile


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository-root', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--build-log', type=Path, required=True)
    parser.add_argument('--java', required=True)
    args = parser.parse_args()
    root = args.repository_root.resolve()
    pom = ET.parse(root / 'pom.xml').getroot()
    ns = {'p': 'http://maven.apache.org/POM/4.0.0'}
    version = pom.findtext('p:version', namespaces=ns)
    assert version and 'SNAPSHOT' not in version, 'Freeze the release version before recording'
    log = args.build_log.read_text(encoding='utf-8-sig')
    assert 'BUILD SUCCESS' in log and 'BUILD FAILURE' not in log
    product = root / f'target/starworld-{version}.jar'
    sources = root / f'target/starworld-{version}-sources.jar'
    with zipfile.ZipFile(product) as archive:
        assert archive.testzip() is None
        assert f"version: '{version}'" in archive.read('plugin.yml').decode('utf-8')
    with zipfile.ZipFile(sources) as archive:
        assert archive.testzip() is None
        for group in ('java', 'resources'):
            for file in (root / 'src/main' / group).rglob('*'):
                if file.is_file():
                    assert archive.read(file.relative_to(root / 'src/main' / group).as_posix()) == file.read_bytes(), file
    suites = []
    for report in sorted((root / 'target/surefire-reports').glob('TEST-*.xml')):
        suite = ET.parse(report).getroot()
        suites.append({'suite': suite.attrib['name'], **{
            key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}})
    totals = {key: sum(row[key] for row in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    assert totals['tests'] and not any(totals[key] for key in ('failures', 'errors', 'skipped')), totals
    coverage = {counter.attrib['type']: {key: int(counter.attrib[key]) for key in ('covered', 'missed')}
                for counter in ET.parse(root / 'target/site/jacoco/jacoco.xml').getroot().findall('counter')}
    evidence = root / 'docs/evidence'
    previous_file = evidence / 'unit-tests.json'
    previous = json.loads(previous_file.read_text('utf-8'))
    if previous['version'] != version:
        history = evidence / 'history' / previous['version']
        history.mkdir(exist_ok=True)
        for file in evidence.glob('*.json'):
            destination = history / file.name
            assert not destination.exists() or destination.read_bytes() == file.read_bytes(), destination
            if not destination.exists():
                shutil.copy2(file, destination)
        for name in ('test-results', 'completion-audit', 'scale', 'senluo-acceptance'):
            file = root / f'docs/{name}.md'
            destination = root / f'docs/history/{name}-{previous["version"]}.md'
            assert not destination.exists() or destination.read_bytes() == file.read_bytes(), destination
            if file.is_file() and not destination.exists():
                shutil.copy2(file, destination)
    result = {'schema': 2, 'version': version, 'recorded_at': dt.datetime.now(dt.timezone.utc).isoformat(),
              'command': './mvnw.cmd -B --no-transfer-progress -Dstarworld.testHeap=256m clean verify',
              'java': args.java, 'artifact_sha256': digest(product), 'totals': totals, 'suites': suites,
              'coverage': coverage, 'build_log_sha256': digest(args.build_log), 'sources_sha256': digest(sources)}
    data = json.dumps(result, indent=2) + '\n'
    previous_file.write_text(data, encoding='utf-8')
    (root / 'UNIT-EVIDENCE.json').write_text(data, encoding='utf-8')
    scale = json.loads((root / 'target/scale-results.json').read_text('utf-8'))
    scale.update(version=version, artifact_sha256=result['artifact_sha256'], java=args.java,
                 test_heap='256m', maven_heap='192m', command=result['command'])
    (evidence / 'scale.json').write_text(json.dumps(scale, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'version': version, 'totals': totals, 'suites': len(suites),
                      'plugin': result['artifact_sha256'], 'sources': result['sources_sha256']}))


if __name__ == '__main__':
    main()
