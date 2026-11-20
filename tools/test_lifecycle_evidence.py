"""Standard-library tamper checks for lifecycle evidence collection; no server is started."""
from __future__ import annotations

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import lifecycle_evidence as lifecycle

_PATH = Path(__file__).with_name("package-evidence.py")
_SPEC = importlib.util.spec_from_file_location("starworld_parent_packager", _PATH)
parent = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(parent)


class LifecycleEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="starworld-lifecycle-evidence-")
        self.root = Path(self.temp.name)
        self.runtime = self.root / "runtime"
        self.directory = self.runtime / "lifecycle-acceptance/source-unit"
        self.directory.mkdir(parents=True)
        self.entries = {}
        self.product_hash = "a" * 64
        self.collector = lifecycle._Collector(
            self.root, "1.1.0", self.product_hash, self.entries.__setitem__,
            parent.regular_file, parent.read_json, parent.require, parent.stage_names)
        self.start = "2026-11-20T00:00:00.000Z"
        self.finish = "2026-11-20T00:01:00.000Z"
        self.run = {
            "directory": self.directory, "runtime": self.runtime, "label": "life-source",
            "start": self.collector.timestamp(self.start, "start"),
            "finish": self.collector.timestamp(self.finish, "finish"),
            "e": {"startPreflight": [{"run": 1, "at": "2026-11-20T00:00:01.000Z", "windows": True,
                                      "minimumFreeCommitBytes": 1610612736, "freeCommitBytes": 2147483648}]},
        }

    def tearDown(self):
        self.temp.cleanup()

    def write(self, file, value):
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(value if isinstance(value, bytes) else json.dumps(value).encode())

    def logs(self):
        self.write(self.directory / "paper-run-1.log",
                   b"StarWorld ready:\nStopping server\nAll dimensions are saved\n")
        self.write(self.directory / "client-run-1.log", b"2026-11-20T00:00:20.000Z joined\n")

    def failed(self, callback):
        with self.assertRaises(parent.EvidenceError):
            callback()

    def test_numeric_replacement_preserves_crlf_and_unrelated_values(self):
        original = b"# inputs\r\n  city-culture-per-period: 240 # configured\r\nother: 7\r\n"
        changed = self.collector.replace(original, "city-culture-per-period", 20)
        self.assertEqual(changed, b"# inputs\r\n  city-culture-per-period: 20 # configured\r\nother: 7\r\n")
        self.assertEqual(self.collector.numeric(changed, "city-culture-per-period"), 20)

    def test_duplicate_configuration_key_fails(self):
        self.failed(lambda: self.collector.numeric(b"key: 1\nkey: 2\n", "key"))
        self.failed(lambda: self.collector.replace(b"key: 1\nkey: 2\n", "key", 3))

    def test_archive_rejects_database_jar_traversal_and_binary(self):
        for name, data in (("state.db", b"data"), ("external.jar", b"data"), ("../secret.json", b"{}"),
                           ("log.log", b"SQLite\x00binary"), ("log.log", b"\xff")):
            self.failed(lambda name=name, data=data: self.collector.emit("life-source", name, data))
        self.assertEqual(self.entries, {})

    def test_safe_text_snapshot_is_added_without_a_database(self):
        self.collector.emit("life-source", "stopped-source-final.json", b'{"mode":"ro"}')
        self.assertEqual(set(self.entries), {"runtime/life-source/stopped-source-final.json"})

    def test_missing_or_extra_server_transcripts_fail(self):
        self.logs()
        (self.directory / "client-run-1.log").unlink()
        self.failed(lambda: self.collector.logs(self.run, 1))
        self.logs()
        self.write(self.directory / "paper-run-2.log", b"extra")
        self.failed(lambda: self.collector.logs(self.run, 1))

    def test_normal_server_and_actual_client_transcripts_pass(self):
        self.logs()
        self.assertEqual(len(self.collector.logs(self.run, 1)), 2)

    def test_business_failure_before_stop_fails(self):
        self.logs()
        self.write(self.directory / "paper-run-1.log",
                   b"StarWorld ready:\nSettlement failed (transaction rolled back): bad\nStopping server\nAll dimensions are saved\n")
        self.failed(lambda: self.collector.logs(self.run, 1))

    def test_teardown_cancel_after_stop_is_not_an_online_business_failure(self):
        self.logs()
        self.write(self.directory / "paper-run-1.log",
                   b"StarWorld ready:\nStopping server\nSettlement failed (transaction rolled back): stopping\nAll dimensions are saved\n")
        self.assertEqual(len(self.collector.logs(self.run, 1)), 2)

    def test_out_of_memory_even_after_stop_fails(self):
        self.logs()
        self.write(self.directory / "paper-run-1.log",
                   b"StarWorld ready:\nStopping server\nOutOfMemoryError\nAll dimensions are saved\n")
        self.failed(lambda: self.collector.logs(self.run, 1))

    def test_client_output_outside_run_timespan_fails(self):
        self.logs()
        self.write(self.directory / "client-run-1.log", b"2026-11-19T23:59:59.000Z joined\n")
        self.failed(lambda: self.collector.logs(self.run, 1))

    def test_memory_gate_requires_all_starts_and_sufficient_free_commit(self):
        self.collector.memory(self.run, 1)
        self.failed(lambda: self.collector.memory(self.run, 2))
        self.run["e"]["startPreflight"][0]["freeCommitBytes"] = 1610612735
        self.failed(lambda: self.collector.memory(self.run, 1))

    def test_memory_file_must_equal_embedded_record(self):
        record = self.run["e"]["startPreflight"][0]
        self.write(self.directory / "memory-before-run-1.json", record)
        self.collector.memory(self.run, 1, True)
        self.write(self.directory / "memory-before-run-1.json", {**record, "freeCommitBytes": 9})
        self.failed(lambda: self.collector.memory(self.run, 1, True))

    def test_independent_snapshot_must_equal_embedded_proof(self):
        proof = {"integrity": "ok", "rows": {"life_action": []}}
        self.write(self.directory / "stopped-source-final.json", proof)
        self.assertEqual(self.collector.snapshot(self.run, "stopped-source-final.json", proof), proof)
        self.failed(lambda: self.collector.snapshot(self.run, "stopped-source-final.json", {"rows": {}}))

    def test_credit_snapshot_rejects_replayed_or_truncated_amounts(self):
        identity = {"cityId": "city", "nationId": "nation", "actorId": "actor"}
        state = {"credits": {"action": {"actionId": "action", "cityId": "city", "nationId": "nation",
                                       "originalCulture": 25, "creditedCulture": 20, "lastCreditedPeriod": 11, "pending": True}}}
        lifecycle._credit(state, "action", 25, 20, 11, True, identity, self.collector.check)
        self.failed(lambda: lifecycle._credit(state, "action", 20, 20, 11, False, identity, self.collector.check))

    def test_read_retry_rejects_mutated_stock_or_crossed_period(self):
        before = {"period": 11, "city": {"lastPeriod": 11}, "credits": {}, "profile": {"culture": 20},
                  "stock": {"bread": 8}, "life": {"culture": 25}}
        lifecycle._same_read(before, copy.deepcopy(before), self.collector.check)
        after = copy.deepcopy(before)
        after["stock"]["bread"] = 9
        self.failed(lambda: lifecycle._same_read(before, after, self.collector.check))
        after = copy.deepcopy(before)
        after["period"] = 12
        self.failed(lambda: lifecycle._same_read(before, after, self.collector.check))

    def test_original_action_cannot_disappear_or_change(self):
        old = [{"id": "old", "culture": 25}]
        lifecycle._unchanged(old, [*old, {"id": "new", "culture": 12}], self.collector.check, "actions")
        self.failed(lambda: lifecycle._unchanged(old, [{"id": "old", "culture": 20}], self.collector.check, "actions"))
        self.failed(lambda: lifecycle._unchanged(old, [], self.collector.check, "actions"))

    def test_actual_installed_product_hash_version_and_plugin_set_are_checked(self):
        plugins = self.runtime / "plugins"
        plugins.mkdir()
        with zipfile.ZipFile(plugins / "StarWorld.jar", "w") as jar:
            jar.writestr("plugin.yml", "name: StarWorld\nversion: '1.1.0'\n")
        expected = {"StarWorld.jar": lifecycle._sha((plugins / "StarWorld.jar").read_bytes())}
        self.collector.plugin_hash = expected["StarWorld.jar"]
        self.collector.plugins(self.run, expected, "1.1.0")
        self.failed(lambda: self.collector.plugins(self.run, expected, "1.0.0", False))
        self.write(plugins / "Injected.jar", b"injected")
        self.failed(lambda: self.collector.plugins(self.run, expected, "1.1.0"))

    def test_exact_stage_names_order_and_complete_timespan_are_checked(self):
        script_name = "tools/senluo-fixture/lifecycle-acceptance.cjs"
        self.write(self.root / script_name, b"await stage('one',async()=>{});\n")
        proof = {"schema": 1, "suite": "real-paper-life-carry", "status": "passed", "mode": "source",
                 "runtime": str(self.runtime), "startedAt": self.start, "finishedAt": self.finish,
                 "expectedStages": 1, "steps": [{"name": "one", "status": "passed",
                                                "startedAt": "2026-11-20T00:00:02.000Z",
                                                "finishedAt": "2026-11-20T00:00:03.000Z"}]}
        self.write(self.directory / "evidence.json", proof)
        self.collector.run(self.directory, script_name, ["one"], "life-source", "source")
        proof["steps"][0]["name"] = "invented"
        self.write(self.directory / "evidence.json", proof)
        self.failed(lambda: self.collector.run(self.directory, script_name, ["one"], "life-source", "source"))
        proof["steps"][0]["name"] = "one"
        proof["steps"][0]["finishedAt"] = "2026-11-20T00:02:00.000Z"
        self.write(self.directory / "evidence.json", proof)
        self.failed(lambda: self.collector.run(self.directory, script_name, ["one"], "life-source", "source"))

    def test_all_three_real_run_inputs_are_mandatory(self):
        self.failed(lambda: lifecycle.collect_lifecycle(
            self.root, "1.1.0", "a" * 64, None, None, None, self.entries.__setitem__,
            parent.regular_file, parent.read_json, parent.require, parent.stage_names))


if __name__ == "__main__":
    unittest.main()

