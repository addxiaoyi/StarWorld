"""Candidate preparation keeps real stopped state and refuses stale evidence."""
import argparse
from contextlib import closing
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import zipfile


_SPEC = importlib.util.spec_from_file_location('prepare_life', Path(__file__).with_name('prepare-life-candidate.py'))
prepare = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(prepare)


class LifeCandidatePreparationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.root = self.base / 'repo'
        self.source = self.base / 'source'
        self.runtime = self.base / 'candidate'
        self.directory = self.source / 'lifecycle-acceptance/source-test'
        self.directory.mkdir(parents=True)
        self.evidence_file = self.directory / 'evidence.json'
        self.runner = self.write(self.root / 'tools/senluo-fixture/lifecycle-acceptance.cjs',
                                 "\n".join("await stage('source " + str(i) + "', run);" for i in range(4)))
        self.helper = self.write(self.root / 'tools/paper-client/acceptance.cjs', '// shared launcher')
        self.write(self.source / '.starworld-smoke-runtime', prepare.inputs.MARKER)
        self.write(self.source / 'eula.txt', 'eula=true\n')
        self.write(self.source / 'server.properties', 'server-ip=127.0.0.1\nonline-mode=false\nserver-port=25601\nlevel-name=life-world\n')
        self.write(self.source / 'life-world/level.dat', b'world data')
        self.write(self.source / 'life-world/playerdata/player.dat', b'player inventory')
        self.write(self.source / 'plugins/StarWorld/config.yml', 'period-seconds: 60\n')
        self.life = b'# custom input\r\n  player-culture-per-period: 240 # player\r\n  city-culture-per-period: 600\r\nother: 9\r\n'
        self.write(self.source / 'plugins/StarWorld/life.yml', self.life)
        self.jar = self.base / 'candidate.jar'
        for file, version in [(self.source / 'plugins/StarWorld.jar', '1.0.0'), (self.jar, '1.4.0')]:
            file.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(file, 'w') as archive:
                archive.writestr('plugin.yml', "name: StarWorld\nversion: '" + version + "'\n")
                archive.writestr('climate.yml', 'config-version: 1\n')
        for name in ('StarCore.jar', 'StarWorldFixture.jar', 'StarWorldSenLuoFixture.jar'):
            self.write(self.source / 'plugins' / name, name)
        self.write(self.source / 'cache/mojang.jar', b'immutable bootstrap')
        self.write(self.source / 'paper.jar', b'paper bootstrap')
        self.after = {}
        for label, folder, name in [('world', 'StarWorld', 'starworld.db'), ('core', 'StarCore', 'starcore.db')]:
            db = self.source / 'plugins' / folder / name
            db.parent.mkdir(parents=True, exist_ok=True)
            with closing(sqlite3.connect(db)) as connection:
                connection.execute('CREATE TABLE conserved (id TEXT PRIMARY KEY, amount INTEGER)')
                connection.execute('INSERT INTO conserved VALUES (?,?)', ('real-stock', 42))
                connection.commit()
            self.after[label] = {'sha256': prepare.sha(db)}
        action = dict(id='unconsumed25', eligible=1, culture=25, kind='feast', source='cookery')
        self.after['world'].update(columns={'life_evolution_action': ['id', 'consumed_period']}, rows={
            'life_schema': [{'id': 1, 'version': 1}], 'life_evolution_action': [{'id': 'completed'}],
            'life_action': [dict(id='completed', eligible=1), action]})
        self.proof = dict(schema=1, status='passed', suite='real-paper-life-carry', mode='source',
            version='1.0.0', runtime=str(self.source), after=self.after, oldActionId=action['id'], oldAction=action,
            steps=[dict(name='source ' + str(i), status='passed') for i in range(4)],
            preparation=dict(pluginJars={file.name: prepare.sha(file) for file in (self.source / 'plugins').glob('*.jar')},
                configSha256={file.name: prepare.sha(file) for file in (self.source / 'plugins/StarWorld').glob('*.yml')}))
        self.save()
        self.write(self.directory / 'client-run-1.log', 'actual client transcript')
        mib = 1024 * 1024
        preflight = dict(runnerSha256=prepare.sha(self.runner), acceptanceHelperSha256=prepare.sha(self.helper),
            minimumStartupFreeMemoryBytes=1536*mib, minimumStartupFreeCommitBytes=2048*mib,
            minimumStartupNonHeapReserveBytes=1024*mib, minimumRuntimeFreeMemoryBytes=768*mib,
            minimumRuntimeFreeCommitBytes=768*mib, startupFreeMemoryBytes=3072*mib,
            startupFreeCommitBytes=3072*mib, nodeHeapLimitBytes=152*mib, paperMaximumHeapBytes=768*mib,
            requiredStartupFreeMemoryBytes=2712*mib, requiredStartupFreeCommitBytes=2712*mib, jvmArgs=['-Xmx768m'])
        summary = dict(minimumObservedFreeMemoryBytes=1024*mib, minimumObservedFreeCommitBytes=1024*mib)
        self.write(self.directory / 'paper-run-1.log', 'StarWorld ready:\nStopping server\nAll dimensions are saved\n'
            + 'STARWORLD_RESOURCE_PREFLIGHT ' + json.dumps(preflight) + '\n'
            + 'STARWORLD_RESOURCE_SUMMARY ' + json.dumps(summary) + '\n')
        self.unit = self.root / 'docs/evidence/unit-tests.json'
        self.write(self.unit, json.dumps(dict(version='1.4.0', artifact_sha256=prepare.sha(self.jar),
            totals=dict(tests=475, errors=0, failures=0, skipped=0))))
        java = self.write(self.base / 'java.exe', b'local java executable')
        self.args = argparse.Namespace(repository_root=self.root, source_evidence=self.evidence_file,
            runtime=self.runtime, jar=self.jar, port=25853, java=java, paper=self.source / 'paper.jar',
            node=Path('node'), client_deps=self.base / 'client', link_bootstrap=False, check_only=False)

    def write(self, file, data):
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(data if isinstance(data, bytes) else data.encode('utf-8'))
        return file

    def save(self):
        self.write(self.evidence_file, json.dumps(self.proof))
        self.write(self.directory / 'stopped-source-final.json', json.dumps(self.after))

    def test_exact_databases_and_player_world_bytes_survive_candidate_copy(self):
        result = prepare.prepare(self.args)
        for relative in ['plugins/StarWorld/starworld.db', 'plugins/StarCore/starcore.db',
                         'life-world/level.dat', 'life-world/playerdata/player.dat']:
            self.assertEqual((self.runtime / relative).read_bytes(), (self.source / relative).read_bytes())
        with closing(sqlite3.connect((self.runtime / 'plugins/StarWorld/starworld.db').as_uri() + '?mode=ro&immutable=1', uri=True)) as connection:
            self.assertEqual(connection.execute('SELECT amount FROM conserved').fetchone()[0], 42)
        self.assertEqual(result['targetPluginSha256'], prepare.sha(self.jar))
        self.assertEqual((self.runtime / 'plugins/StarWorld/life.yml').read_bytes(),
                         self.life.replace(b'240', b'20').replace(b'600', b'20'))
        self.assertEqual((self.source / 'plugins/StarWorld/life.yml').read_bytes(), self.life)
        self.assertFalse((self.runtime / 'lifecycle-acceptance').exists())
        self.assertFalse((self.runtime / 'plugins/StarWorld/climate.yml').exists())
        self.assertFalse(result['processStarted'])

    def test_check_only_leaves_candidate_absent(self):
        self.args.check_only = True
        result = prepare.prepare(self.args)
        self.assertEqual(result['status'], 'checked-not-created')
        self.assertFalse(self.runtime.exists())

    def test_unfinished_or_failed_source_cannot_become_candidate(self):
        for status in ('running', 'failed'):
            self.proof['status'] = status
            self.save()
            with self.assertRaisesRegex(ValueError, 'passed real 1.0'):
                prepare.prepare(self.args)
            self.assertFalse(self.runtime.exists())

    def test_database_change_since_proof_is_rejected(self):
        with closing(sqlite3.connect(self.source / 'plugins/StarWorld/starworld.db')) as connection:
            connection.execute('UPDATE conserved SET amount=99')
            connection.commit()
        with self.assertRaisesRegex(ValueError, 'database changed'):
            prepare.prepare(self.args)
        self.assertFalse(self.runtime.exists())

    def test_nonempty_wal_is_rejected_without_opening_it_for_recovery(self):
        wal = self.write(self.source / 'plugins/StarWorld/starworld.db-wal', b'uncommitted work')
        with self.assertRaisesRegex(ValueError, 'uncheckpointed WAL'):
            prepare.prepare(self.args)
        self.assertEqual(wal.read_bytes(), b'uncommitted work')

    def test_outdated_launcher_binding_is_rejected(self):
        self.helper.write_text('// changed launcher')
        with self.assertRaisesRegex(ValueError, 'shared launcher fingerprint differs'):
            prepare.prepare(self.args)
        self.assertFalse(self.runtime.exists())

    def test_existing_failed_attempt_is_not_reused(self):
        sentinel = self.write(self.runtime / 'failure.json', 'old failure')
        with self.assertRaisesRegex(ValueError, 'already exists'):
            prepare.prepare(self.args)
        self.assertEqual(sentinel.read_text(), 'old failure')

    def test_hardlinks_do_not_share_databases_or_mutable_configuration(self):
        self.args.link_bootstrap = True
        prepare.prepare(self.args)
        self.assertTrue((self.runtime / 'cache/mojang.jar').samefile(self.source / 'cache/mojang.jar'))
        for name in ['plugins/StarWorld/starworld.db', 'plugins/StarWorld/life.yml', 'plugins/StarCore.jar']:
            self.assertFalse((self.runtime / name).samefile(self.source / name))

    def test_low_disk_and_wrong_candidate_unit_evidence_leave_no_output(self):
        with patch.object(prepare.shutil, 'disk_usage', return_value=SimpleNamespace(free=1)):
            with self.assertRaisesRegex(ValueError, '1 GiB'):
                prepare.prepare(self.args)
        self.assertFalse(self.runtime.exists())
        unit = json.loads(self.unit.read_text())
        unit['artifact_sha256'] = '0' * 64
        self.unit.write_text(json.dumps(unit))
        with self.assertRaisesRegex(ValueError, 'clean unit verification'):
            prepare.prepare(self.args)
        self.assertFalse(self.runtime.exists())

    def test_ambiguous_cap_configuration_cannot_be_rewritten(self):
        with self.assertRaisesRegex(ValueError, 'Exactly one numeric'):
            prepare.caps(self.life + b'player-culture-per-period: 30\n')

    def test_source_restarting_while_copying_cannot_emit_prepared_proof(self):
        copy = prepare.shutil.copyfile
        def copy_with_restart(source, target):
            result = copy(source, target)
            if Path(source).name == 'starworld.db':
                with closing(sqlite3.connect(self.source / 'plugins/StarWorld/starworld.db')) as connection:
                    connection.execute('UPDATE conserved SET amount=999')
                    connection.commit()
            return result
        with patch.object(prepare.shutil, 'copyfile', side_effect=copy_with_restart):
            with self.assertRaisesRegex(ValueError, 'Source changed while copying'):
                prepare.prepare(self.args)
        self.assertFalse((self.runtime / 'LIFECYCLE-PREPARATION.json').exists())


if __name__ == '__main__':
    unittest.main()
