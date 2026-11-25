"""Preserve the evidence for replaced builds of the same release version."""
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


_SPEC = importlib.util.spec_from_file_location('record_verification',
                                             Path(__file__).with_name('record-verification.py'))
recorder = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(recorder)


class ReplacedBuildEvidenceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.evidence = Path(temporary.name)
        self.previous = {'version': '1.4.0', 'artifact_sha256': 'a' * 64}
        self.history = self.evidence / 'history' / '1.4.0' / ('a' * 64)
        for name in ('unit-tests.json', 'scale.json'):
            (self.evidence / name).write_bytes(b'{"previous": true}\n')

    def archive(self, previous=None, version='1.4.0', artifact_hash='b' * 64):
        recorder.archive_replaced_build(self.evidence, previous or self.previous,
                                        version, artifact_hash)

    def test_archives_both_records_without_changing_the_originals(self):
        self.archive()
        for name in ('unit-tests.json', 'scale.json'):
            self.assertEqual((self.history / name).read_bytes(), b'{"previous": true}\n')
            self.assertEqual((self.evidence / name).read_bytes(), b'{"previous": true}\n')

    def test_an_identical_existing_archive_is_reusable(self):
        self.archive()
        self.archive()
        self.assertEqual(len(list(self.history.iterdir())), 2)

    def test_conflicting_archives_are_not_overwritten_or_partially_filled(self):
        self.history.mkdir(parents=True)
        (self.history / 'scale.json').write_bytes(b'conflict')
        with self.assertRaisesRegex(ValueError, 'Conflicting previous-build evidence'):
            self.archive()
        self.assertFalse((self.history / 'unit-tests.json').exists())
        self.assertEqual((self.history / 'scale.json').read_bytes(), b'conflict')

    def test_an_unchanged_artifact_and_other_versions_do_not_create_an_archive(self):
        self.archive(artifact_hash='a' * 64)
        self.archive(version='1.5.0')
        recorder.archive_replaced_build(self.evidence, None, '1.4.0', 'b' * 64)
        self.assertFalse((self.evidence / 'history').exists())

    def test_invalid_prior_hash_cannot_choose_an_archive_path(self):
        for value in ('../../outside', '', None, 'x' * 64):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'valid artifact SHA-256'):
                self.archive(previous={**self.previous, 'artifact_sha256': value})
        self.assertFalse((self.evidence / 'history').exists())


class RecordingCommandTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.evidence = self.root / 'docs/evidence'
        self.evidence.mkdir(parents=True)
        self.original = json.dumps({'version': '1.4.0', 'artifact_sha256': 'a' * 64}).encode()
        (self.evidence / 'unit-tests.json').write_bytes(self.original)
        (self.evidence / 'scale.json').write_text('{"previous": true}', encoding='utf-8')
        self.write('pom.xml', '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>1.4.0</version></project>')
        plugin = "version: '1.4.0'\n"
        self.write('src/main/resources/plugin.yml', plugin)
        (self.root / 'target').mkdir()
        for name in ('starworld-1.4.0.jar', 'starworld-1.4.0-sources.jar'):
            with zipfile.ZipFile(self.root / 'target' / name, 'w') as archive:
                archive.writestr('plugin.yml', plugin)
        self.write('target/surefire-reports/TEST-fixture.xml',
                   '<testsuite name="fixture" tests="1" failures="0" errors="0" skipped="0"/>')
        self.write('target/site/jacoco/jacoco.xml', '<report><counter type="LINE" covered="1" missed="0"/></report>')
        self.write('target/scale-results.json', '{"schema": 1, "failures": 0}')
        self.log = self.root / 'build.log'
        self.log.write_bytes(b'[INFO] ' + '测试'.encode('gbk') + b'\n[INFO] BUILD SUCCESS\n')

    def write(self, name, text):
        destination = self.root / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(text.encode('utf-8'))

    def record(self):
        arguments = ['record-verification.py', '--repository-root', str(self.root),
                     '--build-log', str(self.log), '--java', 'java', '--command', 'test; verify',
                     '--test-heap', '192m', '--maven-heap', '192m']
        with patch.object(sys, 'argv', arguments), contextlib.redirect_stdout(io.StringIO()):
            recorder.main()

    def test_records_windows_diagnostics_and_archives_the_replaced_build(self):
        self.record()
        current = json.loads((self.evidence / 'unit-tests.json').read_text('utf-8'))
        self.assertEqual(current['totals'], {'tests': 1, 'failures': 0, 'errors': 0, 'skipped': 0})
        self.assertEqual(current['build_log_sha256'], recorder.digest(self.log))
        history = self.evidence / 'history' / '1.4.0' / ('a' * 64)
        self.assertEqual((history / 'unit-tests.json').read_bytes(), self.original)
        self.assertEqual(json.loads((history / 'scale.json').read_text('utf-8')), {'previous': True})

    def test_failure_marker_prevents_recording_even_if_the_log_also_says_success(self):
        self.log.write_bytes(self.log.read_bytes() + b'BUILD FAILURE\n')
        with self.assertRaises(AssertionError):
            self.record()
        self.assertEqual((self.evidence / 'unit-tests.json').read_bytes(), self.original)
        self.assertFalse((self.evidence / 'history').exists())

    def test_missing_scale_output_does_not_replace_the_old_unit_evidence(self):
        (self.root / 'target/scale-results.json').unlink()
        with self.assertRaises(FileNotFoundError):
            self.record()
        self.assertEqual((self.evidence / 'unit-tests.json').read_bytes(), self.original)
        self.assertFalse((self.root / 'UNIT-EVIDENCE.json').exists())
        self.assertFalse((self.evidence / 'history').exists())


if __name__ == '__main__':
    unittest.main()
