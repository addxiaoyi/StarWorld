"""Tamper checks for 1.4 storage-pressure evidence; no server is started."""
from __future__ import annotations

import copy
import hashlib
import importlib.util
from pathlib import Path
import unittest

_PATH = Path(__file__).with_name("package-evidence.py")
_SPEC = importlib.util.spec_from_file_location("starworld_evidence_packager", _PATH)
packager = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(packager)


class StorageEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.script = b"fixed storage acceptance runner"
        self.plugin_hash = "a" * 64
        self.evidence = {
            "suite": "real-paper-survey-and-storage-backpressure",
            "runnerSha256": hashlib.sha256(self.script).hexdigest(),
            "paperSha256": "b" * 64,
            "pluginJars": {"StarWorld.jar": self.plugin_hash},
            "nodeHeapLimitBytes": 226_492_416,
            "testTuning": {
                "jvmArgs": ["-Xms128m", "-Xmx512m"],
                "minimumFreeSystemMemoryBytes": 1024 * 1024 * 1024,
                "startupFreeSystemMemoryBytes": 1536 * 1024 * 1024,
                "storageQueueCapacity": 256,
                "paperHeapSafetyLimitPercent": 95,
            },
            "steps": [{
                "name": "real Paper sustains bounded queue saturation and recovers for 60 seconds",
                "status": "passed",
                "observed": {
                    "elapsedMillis": 60_031,
                    "rounds": 136,
                    "requestsPerRound": 384,
                    "requests": 52_224,
                    "accepted": 37_650,
                    "rejected": 14_574,
                    "heapSamples": 681,
                    "maxHeapBytes": 518_979_584,
                    "peakHeapBytes": 448_158_464,
                    "safetyLimitPercent": 95,
                    "peakPercentOfMaximum": 86.35,
                },
            }],
        }

    def reject(self, evidence):
        with self.assertRaises(packager.EvidenceError):
            packager.validate_storage_evidence(evidence, self.script, self.plugin_hash)

    def test_reads_jar_fingerprint_from_each_supported_evidence_shape(self):
        self.assertEqual(packager.recorded_artifact_hash({"artifact_sha256": self.plugin_hash}), self.plugin_hash)
        self.assertEqual(packager.recorded_artifact_hash({"pluginJars": {"StarWorld.jar": self.plugin_hash}}), self.plugin_hash)
        self.assertEqual(packager.recorded_artifact_hash({"artifacts": {"jar": {"sha256": self.plugin_hash}}}), self.plugin_hash)

    def test_accepts_bounded_paper_soak_bound_to_runner_and_plugin(self):
        result = packager.validate_storage_evidence(self.evidence, self.script, self.plugin_hash)
        self.assertEqual(result["rounds"], 136)

    def test_rejects_changed_runner_or_plugin(self):
        changed = copy.deepcopy(self.evidence)
        changed["runnerSha256"] = "c" * 64
        self.reject(changed)
        self.reject(self.evidence | {"pluginJars": {"StarWorld.jar": "d" * 64}})

    def test_rejects_unbounded_heap_or_insufficient_startup_memory(self):
        changed = copy.deepcopy(self.evidence)
        changed["testTuning"]["jvmArgs"] = ["-Xmx768m"]
        self.reject(changed)
        changed = copy.deepcopy(self.evidence)
        changed["testTuning"]["startupFreeSystemMemoryBytes"] = 512 * 1024 * 1024
        self.reject(changed)

    def test_rejects_short_soak_or_failed_recovery_stage(self):
        changed = copy.deepcopy(self.evidence)
        changed["steps"][0]["observed"]["elapsedMillis"] = 59_999
        self.reject(changed)
        changed = copy.deepcopy(self.evidence)
        changed["steps"][0]["status"] = "failed"
        self.reject(changed)

    def test_rejects_heap_peak_above_the_hard_stop(self):
        changed = copy.deepcopy(self.evidence)
        changed["steps"][0]["observed"]["peakHeapBytes"] = 500_000_000
        self.reject(changed)


if __name__ == "__main__":
    unittest.main()
