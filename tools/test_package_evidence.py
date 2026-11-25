import json
import importlib.util
from pathlib import Path
import unittest
import tempfile

_SPEC = importlib.util.spec_from_file_location("package_evidence", Path(__file__).with_name("package-evidence.py"))
package = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(package)


class PaperResourceLogTests(unittest.TestCase):
    runner_hash = "a" * 64
    helper_hash = "b" * 64

    def log(self, *, startup_free=None, observed_free=None, runner_hash=None):
        mib = 1024 * 1024
        preflight = {
            "runnerSha256": runner_hash or self.runner_hash,
            "acceptanceHelperSha256": self.helper_hash,
            "minimumStartupFreeMemoryBytes": 1536 * mib,
            "minimumStartupFreeCommitBytes": 2048 * mib,
            "minimumStartupNonHeapReserveBytes": 1024 * mib,
            "minimumRuntimeFreeMemoryBytes": 768 * mib,
            "minimumRuntimeFreeCommitBytes": 768 * mib,
            "startupFreeMemoryBytes": startup_free or 3072 * mib,
            "startupFreeCommitBytes": 3072 * mib,
            "nodeHeapLimitBytes": 452 * mib,
            "paperMaximumHeapBytes": 512 * mib,
            "requiredStartupFreeMemoryBytes": 2756 * mib,
            "requiredStartupFreeCommitBytes": 2756 * mib,
            "jvmArgs": ["-Xmx512m"]
        }
        summary = {"minimumObservedFreeMemoryBytes": observed_free or 1024 * mib,
                   "minimumObservedFreeCommitBytes": observed_free or 1024 * mib}
        return ("STARWORLD_RESOURCE_PREFLIGHT " + json.dumps(preflight) + "\n" +
                "STARWORLD_RESOURCE_SUMMARY " + json.dumps(summary) + "\n").encode()

    def test_accepts_a_runner_bound_log_that_stays_above_all_memory_floors(self):
        package.validate_paper_resource_log(self.log(), "baseline", self.runner_hash, self.helper_hash)

    def test_rejects_a_log_from_another_runner(self):
        with self.assertRaisesRegex(package.EvidenceError, "runner fingerprint differs"):
            package.validate_paper_resource_log(self.log(runner_hash="c" * 64), "baseline",
                                                self.runner_hash, self.helper_hash)

    def test_rejects_a_log_bound_to_an_outdated_shared_launcher(self):
        log = self.log().replace(self.helper_hash.encode(), ("c" * 64).encode())
        with self.assertRaisesRegex(package.EvidenceError, "shared launcher fingerprint differs"):
            package.validate_paper_resource_log(log, "baseline", self.runner_hash, self.helper_hash)

    def test_rejects_startup_below_the_physical_memory_floor(self):
        with self.assertRaisesRegex(package.EvidenceError, "started below its physical memory reserve"):
            package.validate_paper_resource_log(self.log(startup_free=1535 * 1024 * 1024), "baseline",
                                                self.runner_hash, self.helper_hash)

    def test_rejects_a_run_that_crossed_the_runtime_memory_floor(self):
        with self.assertRaisesRegex(package.EvidenceError, "crossed its runtime free-memory limit"):
            package.validate_paper_resource_log(self.log(observed_free=767 * 1024 * 1024), "baseline",
                                                self.runner_hash, self.helper_hash)

    def test_rejects_startup_without_heap_and_runtime_reserve(self):
        with self.assertRaisesRegex(package.EvidenceError, "started below its physical memory reserve"):
            package.validate_paper_resource_log(self.log(startup_free=2755 * 1024 * 1024), "baseline",
                                                self.runner_hash, self.helper_hash)

    def test_rejects_missing_resource_stop_proof(self):
        log = self.log().replace(b"STARWORLD_RESOURCE_SUMMARY ", b"STARWORLD_RESOURCE_NOTE ")
        with self.assertRaisesRegex(package.EvidenceError, "exactly one STARWORLD_RESOURCE_SUMMARY marker"):
            package.validate_paper_resource_log(log, "baseline", self.runner_hash, self.helper_hash)

    def test_rejects_a_startup_log_that_omits_non_heap_reserve(self):
        log = self.log().replace(b'"minimumStartupNonHeapReserveBytes": 1073741824,', b"")
        with self.assertRaisesRegex(package.EvidenceError, "omits its 1024 MiB JVM/native allowance"):
            package.validate_paper_resource_log(log, "baseline", self.runner_hash, self.helper_hash)

    def test_rejects_a_paper_heap_above_the_runner_limit(self):
        log = self.log().replace(b"-Xmx512m", b"-Xmx1g")
        with self.assertRaisesRegex(package.EvidenceError, "Paper maximum heap exceeded 768 MiB"):
            package.validate_paper_resource_log(log, "baseline", self.runner_hash, self.helper_hash)


class CandidatePatchTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.folder = self.root / "upstream/starcore"
        self.folder.mkdir(parents=True)
        self.settlement = {"base": "a" * 40, "commit": "b" * 40,
                           "patch_sha256": package.sha256(b"settlement"), "artifact_sha256": "c" * 64}
        self.transit = {"base": "a" * 40, "parent": "b" * 40, "commit": "d" * 40,
                        "patch_sha256": package.sha256(b"transit"), "artifact_sha256": "e" * 64}
        for name, value in (("verification.json", self.settlement), ("transit-verification.json", self.transit)):
            (self.folder / name).write_text(json.dumps(value))
        (self.folder / "0001-atomic-settlements-and-refunds.patch").write_bytes(b"settlement")
        (self.folder / "0002-versioned-transit-permissions.patch").write_bytes(b"transit")

    def test_each_financial_suite_accepts_the_complete_transit_chain(self):
        for label in ("refund", "tax", "transit"):
            self.assertEqual(len(package.candidate_patches(self.root, self.transit, label)), 2)
        self.assertEqual(len(package.candidate_patches(self.root, self.settlement, "refund")), 1)

    def test_transit_cannot_use_a_candidate_without_permission_authority(self):
        with self.assertRaisesRegex(package.EvidenceError, "requires the transit candidate"):
            package.candidate_patches(self.root, self.settlement, "transit")

    def test_rejects_runtime_artifact_and_parent_substitution(self):
        for key in ("artifact_sha256", "parent"):
            with self.subTest(key=key), self.assertRaises(package.EvidenceError):
                package.candidate_patches(self.root, {**self.transit, key: "f" * 64}, "tax")

    def test_transit_requires_the_unchanged_settlement_patch_too(self):
        (self.folder / "0001-atomic-settlements-and-refunds.patch").write_bytes(b"altered")
        with self.assertRaisesRegex(package.EvidenceError, "upstream patch changed"):
            package.candidate_patches(self.root, self.transit, "transit")


if __name__ == "__main__":
    unittest.main()
