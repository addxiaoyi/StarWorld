"""Fresh acceptance inputs must not inherit gameplay state or change a template."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


_SPEC = importlib.util.spec_from_file_location('prepare_paper',
                                             Path(__file__).with_name('prepare-paper-acceptance.py'))
prepare = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(prepare)


class FreshPaperInputTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.template = self.base / 'accepted-template'
        self.root = self.base / 'repo'
        self.runtime = self.base / 'fresh-runtime'
        self.write('.starworld-smoke-runtime', prepare.MARKER + '\n')
        self.write('eula.txt', 'eula=true\n')
        self.write('server.properties', 'server-ip=127.0.0.1\nonline-mode=false\nserver-port=25601\nlevel-name=old-world\n')
        self.write('cache/mojang.jar', b'bootstrap')
        (self.template / 'libraries').mkdir()
        (self.template / 'versions').mkdir()
        self.write('plugins/StarCore.jar', b'core')
        self.write('plugins/StarCore/config.yml', 'claim-cost: 0\n')
        self.write('plugins/StarWorld/config.yml', 'period-seconds: 10\n')
        for script in [*prepare.RUNNERS.values(), 'paper-client/acceptance.cjs']:
            target = self.root / 'tools' / script
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('test runner', encoding='utf-8')
        self.jar = self.base / 'StarWorld.jar'
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('plugin.yml', "name: StarWorld\nversion: '1.4.0'\n")
            archive.writestr('config.yml', 'period-seconds: 300\n')
            archive.writestr('climate.yml', 'config-version: 1\n')
        for name in ('fixture.jar', 'paper.jar', 'java.exe'):
            (self.base / name).write_bytes(name.encode())
        self.args = argparse.Namespace(repository_root=self.root, template=self.template,
            runtime=self.runtime, suite='baseline', port=25699, jar=self.jar,
            fixture=self.base / 'fixture.jar', paper=self.base / 'paper.jar',
            java=self.base / 'java.exe', node=Path('node'), client_deps=self.base / 'client',
            senluo_fixture=None, link_bootstrap=False, check_only=False)

    def write(self, relative, data):
        file = self.template / relative
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(data.encode() if isinstance(data, str) else data)
        return file

    def test_prepares_new_build_without_copying_worlds_databases_or_old_evidence(self):
        for name in ('old-world/level.dat', 'old-world/playerdata/player.dat',
                     'plugins/StarWorld/starworld.db', 'plugins/StarCore/starcore.db-wal',
                     'client-acceptance/latest.json', 'plugins/.paper-remapped/Old.jar', 'ops.json'):
            self.write(name, 'must stay in template')
        proof = prepare.prepare(self.args)
        self.assertEqual(proof['targetPluginSha256'], prepare.sha(self.jar))
        self.assertEqual(proof['productVersion'], '1.4.0')
        self.assertEqual(proof['serverPort'], 25699)
        self.assertFalse(proof['processStarted'])
        self.assertEqual([file.name for file in sorted((self.runtime / 'plugins').glob('*.jar'))],
                         ['StarCore.jar', 'StarWorld.jar', 'StarWorldFixture.jar'])
        self.assertFalse(any(file.suffix in ('.db', '.dat', '.mca') for file in self.runtime.rglob('*')))
        self.assertFalse((self.runtime / 'client-acceptance').exists())
        self.assertEqual((self.template / 'old-world/level.dat').read_text(), 'must stay in template')
        props = prepare.properties((self.runtime / 'server.properties').read_text())
        self.assertEqual(props['server-port'], '25699')
        self.assertEqual(props['level-name'], 'fresh-runtime-world')
        self.assertEqual(prepare.read_json(self.runtime / 'PREPARATION.json'), proof)

    def test_preserves_custom_definitions_and_adds_missing_defaults_from_candidate(self):
        prepare.prepare(self.args)
        self.assertEqual((self.runtime / 'plugins/StarWorld/config.yml').read_text(), 'period-seconds: 10\n')
        self.assertEqual((self.runtime / 'plugins/StarWorld/climate.yml').read_text(), 'config-version: 1\n')
        self.assertFalse((self.template / 'plugins/StarWorld/climate.yml').exists())

    def test_check_only_validates_but_creates_nothing(self):
        self.args.check_only = True
        proof = prepare.prepare(self.args)
        self.assertEqual(proof['status'], 'checked-not-created')
        self.assertFalse(self.runtime.exists())
        self.assertIn('--max-old-space-size=192', proof['command'])

    def test_existing_attempt_is_preserved(self):
        self.runtime.mkdir()
        previous = self.runtime / 'previous.json'
        previous.write_text('failed attempt')
        with self.assertRaisesRegex(ValueError, 'already exists'):
            prepare.prepare(self.args)
        self.assertEqual(previous.read_text(), 'failed attempt')

    def test_declined_eula_stays_declined(self):
        self.write('eula.txt', '# operator has not accepted\neula=false\n')
        proof = prepare.prepare(self.args)
        self.assertFalse(proof['eulaAccepted'])
        self.assertEqual((self.runtime / 'eula.txt').read_bytes(), (self.template / 'eula.txt').read_bytes())

    def test_remote_or_ambiguous_server_properties_are_rejected_before_writing(self):
        for extra in ('server-ip=0.0.0.0\n', 'server-port=1234\n'):
            with self.subTest(extra=extra):
                file = self.template / 'server.properties'
                original = file.read_text()
                file.write_text(original + extra)
                with self.assertRaisesRegex(ValueError, 'Duplicate'):
                    prepare.prepare(self.args)
                self.assertFalse(self.runtime.exists())
                file.write_text(original)

    def test_mutable_file_in_bootstrap_is_rejected(self):
        self.write('cache/old.db', b'not a bootstrap jar')
        with self.assertRaisesRegex(ValueError, 'mutable bootstrap'):
            prepare.prepare(self.args)
        self.assertFalse(self.runtime.exists())

    def test_optional_hardlinks_only_share_bootstrap_not_configs_or_plugins(self):
        self.args.link_bootstrap = True
        prepare.prepare(self.args)
        self.assertTrue(os.path.samefile(self.template / 'cache/mojang.jar', self.runtime / 'cache/mojang.jar'))
        self.assertTrue(os.path.samefile(self.base / 'paper.jar', self.runtime / 'paper.jar'))
        self.assertFalse(os.path.samefile(self.jar, self.runtime / 'plugins/StarWorld.jar'))
        self.assertFalse(os.path.samefile(self.template / 'plugins/StarWorld/config.yml',
                                         self.runtime / 'plugins/StarWorld/config.yml'))

    def test_linked_input_is_rejected_without_creating_runtime(self):
        file = self.template / 'eula.txt'
        file.unlink()
        original = self.base / 'external-eula.txt'
        original.write_text('eula=true\n')
        try:
            file.symlink_to(original)
        except OSError:
            self.skipTest('Host does not allow symlink creation')
        with self.assertRaisesRegex(ValueError, 'Linked input'):
            prepare.prepare(self.args)
        self.assertFalse(self.runtime.exists())

    def test_civic_dependencies_cannot_replace_a_bound_plugin_or_escape_template(self):
        self.args.suite = 'civic'
        self.args.senluo_fixture = self.base / 'fixture.jar'
        rows = [{'file': f'plugin-{number}.jar', 'sha256': 'unused'} for number in range(9)]
        for row in rows:
            file = self.write('plugins/' + row['file'], 'audited binary')
            row['sha256'] = prepare.sha(file)
        for path in ('plugins/StarWorld.jar', '../external.yml'):
            with self.subTest(path=path):
                self.write('PREPARATION.json', json.dumps({'installedPlugins': rows,
                           'dependencyFilesSha256': {path: 'unused'}}))
                self.write('UPSTREAM-PROVENANCE.json', '{}')
                with self.assertRaisesRegex(ValueError, 'Unexpected civic dependency'):
                    prepare.prepare(self.args)
                self.assertFalse(self.runtime.exists())

    def test_nested_output_and_invalid_ports_do_not_modify_template(self):
        self.args.runtime = self.template / 'nested'
        with self.assertRaisesRegex(ValueError, 'separate'):
            prepare.prepare(self.args)
        self.args.runtime = self.runtime
        self.args.port = 80
        with self.assertRaisesRegex(ValueError, 'Port'):
            prepare.prepare(self.args)
        self.assertFalse(self.runtime.exists())

    def test_smoke_only_installs_product_and_leaves_default_generation_to_plugin(self):
        self.args.suite = 'smoke'
        self.args.powershell = Path('pwsh')
        self.args.fixture = None
        proof = prepare.prepare(self.args)
        self.assertEqual([file.name for file in (self.runtime / 'plugins').iterdir()], ['StarWorld.jar'])
        self.assertFalse((self.runtime / 'plugins/StarWorld').exists())
        self.assertEqual(proof['command'][0], 'pwsh')
        self.assertNotIn('-AgreeEula', proof['command'])
        self.assertIsNone(proof['starCoreSha256'])

    def test_operations_command_uses_real_python_and_shared_resource_launcher(self):
        self.args.suite = 'operations'
        proof = prepare.prepare(self.args)
        self.assertIn('--python', proof['command'])
        self.assertEqual(proof['acceptanceHelperSha256'], prepare.sha(self.root / 'tools/paper-client/acceptance.cjs'))
        self.assertEqual(proof['runnerSha256'], prepare.sha(self.root / 'tools/paper-client/operations-acceptance.cjs'))

    def test_low_disk_is_rejected_before_creating_an_attempt(self):
        from types import SimpleNamespace
        with patch.object(prepare.shutil, 'disk_usage', return_value=SimpleNamespace(free=1)):
            with self.assertRaisesRegex(ValueError, '1 GiB reserve'):
                prepare.prepare(self.args)
        self.assertFalse(self.runtime.exists())

    def test_civic_copies_both_audited_asset_trees_and_rebinds_current_build(self):
        self.args.suite = 'civic'
        self.args.senluo_fixture = self.base / 'fixture.jar'
        jars = ['StarCore.jar', 'StarWorld.jar', 'StarWorldFixture.jar', 'StarWorldSenLuoFixture.jar',
                'craftengine.jar', 'Cookery.jar', 'Tavern.jar', 'Deco.jar', 'Music.jar']
        rows = []
        for name in jars:
            file = self.write('plugins/' + name, 'old-' + name)
            rows.append(dict(file=name, sha256=prepare.sha(file), bytes=file.stat().st_size))
        manifests = {}
        for index in range(2399):
            name = f'cookery_kitchen/items/{index}.json'
            extracted = self.write('asset-inputs/kitchen-crops-3/' + name, '{}')
            self.write('plugins/CraftEngine/resources/' + name, '{}')
            manifests[name] = prepare.sha(extracted)
        self.write('asset-inputs/kitchen-crops-3/manifest.json', json.dumps({'files': manifests}))
        self.write('asset-inputs/kitchen-crops-3.zip', 'retained archive')
        dependency = self.write('plugins/CraftEngine/resources/internal/static.yml', 'static: true\n')
        self.write('plugins/CraftEngine/libs/test/dependency.jar', 'cached dependency')
        self.write('plugins/CraftEngine/cache/generated.zip', 'must not copy mutable generated packs')
        self.write('UPSTREAM-PROVENANCE.json', '{}')
        self.write('PREPARATION.json', json.dumps(dict(installedPlugins=rows,
            dependencyFilesSha256={'plugins/CraftEngine/resources/internal/static.yml': prepare.sha(dependency)},
            newAssetPreparation={'activeCookeryResources': 'old path'}, testTuning={'port': 1},
            runtime='old runtime', serverPort=1, command=['old command'], runnerSha256='old hash',
            configurationSha256={'old.yml': 'old hash'})))
        unit = self.root / 'docs/evidence/unit-tests.json'
        unit.parent.mkdir(parents=True)
        unit.write_text(json.dumps(dict(version='1.4.0', artifact_sha256=prepare.sha(self.jar),
                        totals=dict(tests=475, failures=0, errors=0, skipped=0))))
        proof = prepare.prepare(self.args)
        self.assertEqual(len(list((self.runtime / 'plugins/CraftEngine/resources/cookery_kitchen').rglob('*.json'))), 2399)
        self.assertEqual((self.runtime / 'plugins/CraftEngine/libs/test/dependency.jar').read_text(), 'cached dependency')
        self.assertFalse((self.runtime / 'plugins/CraftEngine/cache/generated.zip').exists())
        self.assertEqual(proof['starWorldSha256'], prepare.sha(self.jar))
        self.assertEqual(proof['testTuning']['port'], 25699)
        self.assertEqual(proof['testTuning']['nodeArgs'], proof['command'][1:3])
        self.assertEqual(proof['newAssetPreparation']['activeCookeryResources'],
                         str(self.runtime / 'plugins/CraftEngine/resources/cookery_kitchen'))
        self.assertEqual(proof['unitEvidence']['sha256'], prepare.sha(unit))
        self.assertEqual(len(proof['installedPlugins']), 9)
        self.assertEqual(proof['serverPort'], 25699)
        self.assertIn(str(self.runtime), proof['command'])
        self.assertEqual(proof['runnerSha256'], prepare.sha(self.root / 'tools/senluo-fixture/civic-acceptance.cjs'))
        self.assertNotIn('old.yml', proof['configurationSha256'])

    def test_linked_output_parent_cannot_write_into_template(self):
        alias = self.base / 'alias'
        try:
            alias.symlink_to(self.template, target_is_directory=True)
        except OSError:
            self.skipTest('Host does not allow symlink creation')
        self.args.runtime = alias / 'nested'
        with self.assertRaisesRegex(ValueError, 'Linked input'):
            prepare.prepare(self.args)
        self.assertFalse((self.template / 'nested').exists())


if __name__ == '__main__':
    unittest.main()
