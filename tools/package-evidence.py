#!/usr/bin/env python3
"""Package verified StarWorld evidence using only the Python standard library.

No server, database, dependency, network or Java process is touched.  Runtime
directories are read only; the sole output is an atomically replaced ZIP in
the repository's target directory.  Use --check-only to validate without writes.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile


class EvidenceError(ValueError):
    """An input cannot prove the stated verification result."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def regular_file(path: Path, boundary: Path) -> bytes:
    """Read an allowlisted regular file, rejecting symlinks/junction traversal."""
    require(path.is_relative_to(boundary), f"File escapes input directory: {path}")
    # The boundary itself is checked so a runtime junction is not silently followed.
    for candidate in [boundary, *(boundary / parent for parent in reversed(path.relative_to(boundary).parents))]:
        info = candidate.lstat()
        require(not stat.S_ISLNK(info.st_mode) and not (getattr(info, "st_file_attributes", 0) & 0x400),
                f"Linked input is not allowed: {candidate}")
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and not (getattr(info, "st_file_attributes", 0) & 0x400),
            f"Expected an ordinary file: {path}")
    return path.read_bytes()


def read_json(data: bytes, label: str) -> dict:
    value = json.loads(data.decode("utf-8-sig"))
    require(isinstance(value, dict), f"Expected JSON object: {label}")
    return value


def absolute_directory(value: str | Path) -> Path:
    # abspath preserves links for regular_file to reject; resolve() would hide them.
    path = Path(os.path.abspath(value))
    require(path.is_dir(), f"Input directory does not exist: {path}")
    return path


def stage_names(script: bytes, label: str) -> list[str]:
    names = re.findall(r"await\s+stage\(\s*(['\"])(.*?)\1", script.decode("utf-8"))
    result = [name for _, name in names]
    require(bool(result) and len(result) == len(set(result)), f"Missing or duplicate stage names in {label}")
    return result


def hash_string(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value) is not None


def recorded_artifact_hash(proof: dict) -> str | None:
    return (proof.get("artifact_sha256") or
            proof.get("pluginJars", {}).get("StarWorld.jar") or
            proof.get("artifacts", {}).get("jar", {}).get("sha256"))


def validate_storage_evidence(evidence: dict, script: bytes, plugin_hash: str) -> dict:
    require(evidence.get("suite") == "real-paper-survey-and-storage-backpressure" and
            evidence.get("runnerSha256") == sha256(script) and
            evidence.get("pluginJars", {}).get("StarWorld.jar") == plugin_hash and
            hash_string(evidence.get("paperSha256")),
            "Storage acceptance runner, suite or artifact fingerprint differs")
    tuning = evidence.get("testTuning", {})
    node_limit = evidence.get("nodeHeapLimitBytes", 0)
    jvm_args = tuning.get("jvmArgs", [])
    minimum_free = tuning.get("minimumFreeSystemMemoryBytes", 0)
    startup_free = tuning.get("startupFreeSystemMemoryBytes", 0)
    require(isinstance(node_limit, int) and 0 < node_limit <= 256 * 1024 * 1024 and
            any(isinstance(arg, str) and re.fullmatch(r"-Xmx(?:[1-4][0-9]{2}|512)[mM]", arg) for arg in jvm_args) and
            minimum_free >= 1024 * 1024 * 1024 and startup_free >= minimum_free and
            tuning.get("storageQueueCapacity") == 256 and tuning.get("paperHeapSafetyLimitPercent") == 95,
            "Storage acceptance exceeds its Node/Paper heap, queue or system-memory bounds")
    matching_steps = [step for step in evidence.get("steps", [])
                      if step.get("name") == "real Paper sustains bounded queue saturation and recovers for 60 seconds"]
    require(len(matching_steps) == 1 and matching_steps[0].get("status") == "passed",
            "Storage acceptance lacks one passed 60-second pressure stage")
    pressure = matching_steps[0].get("observed", {})
    require(isinstance(pressure, dict) and pressure.get("elapsedMillis", 0) >= 60_000 and
            pressure.get("rounds", 0) >= 10 and pressure.get("requestsPerRound") == 384 and
            pressure.get("requests") == pressure.get("accepted", -1) + pressure.get("rejected", -1) and
            pressure.get("accepted", 0) > 0 and pressure.get("rejected", 0) > 0 and
            pressure.get("heapSamples", 0) > 0 and pressure.get("safetyLimitPercent") == 95 and
            pressure.get("maxHeapBytes", 0) <= 512 * 1024 * 1024 and
            pressure.get("peakHeapBytes", 0) <= pressure.get("maxHeapBytes", 0) * 0.95 and
            pressure.get("peakPercentOfMaximum", 100) <= 95,
            "Storage acceptance did not prove bounded 60-second pressure, recovery and the heap stop line")
    return pressure


def validate_paper_resource_log(log: bytes, label: str, runner_hash: str, helper_hash: str) -> None:
    def marker(prefix: bytes) -> dict:
        matches = [line[len(prefix):] for line in log.splitlines() if line.startswith(prefix)]
        require(len(matches) == 1, f"{label} Paper log must contain exactly one {prefix.decode().strip()} marker")
        try:
            value = json.loads(matches[0].decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise EvidenceError(f"{label} Paper resource marker is invalid JSON") from error
        require(isinstance(value, dict), f"{label} Paper resource marker must be an object")
        return value

    preflight = marker(b"STARWORLD_RESOURCE_PREFLIGHT ")
    summary = marker(b"STARWORLD_RESOURCE_SUMMARY ")
    mib = 1024 * 1024
    startup_physical_floor = 1536 * mib
    startup_commit_floor = 2 * 1024 * mib
    recorded_runner = preflight.get("runnerSha256")
    if recorded_runner != runner_hash:
        raise EvidenceError(
            f"{label} Paper log runner fingerprint differs: recorded {recorded_runner!r}, current {runner_hash}")
    recorded_helper = preflight.get("acceptanceHelperSha256")
    if recorded_helper != helper_hash:
        raise EvidenceError(
            f"{label} Paper log shared launcher fingerprint differs: recorded {recorded_helper!r}, current {helper_hash}")
    for actual, floor in (("minimumStartupFreeMemoryBytes", startup_physical_floor),
                          ("minimumStartupFreeCommitBytes", startup_commit_floor)):
        require(type(preflight.get(actual)) is int and preflight[actual] >= floor,
                f"{label} Paper log lacks its {floor // mib} MiB {actual} guard")
    runtime_floor = 768 * mib
    non_heap_reserve = preflight.get("minimumStartupNonHeapReserveBytes")
    require(type(non_heap_reserve) is int and non_heap_reserve >= 1024 * mib,
            f"{label} Paper startup reserve omits its 1024 MiB JVM/native allowance")
    runtime_limit = preflight.get("minimumRuntimeFreeMemoryBytes")
    observed_free = summary.get("minimumObservedFreeMemoryBytes")
    require(type(runtime_limit) is int and runtime_limit >= runtime_floor and
            type(observed_free) is int and observed_free >= runtime_limit,
            f"{label} Paper process crossed its runtime free-memory limit")
    commit_limit = preflight.get("minimumRuntimeFreeCommitBytes")
    observed_commit = summary.get("minimumObservedFreeCommitBytes")
    require(type(commit_limit) is int and commit_limit >= runtime_floor and
            type(observed_commit) is int and observed_commit >= commit_limit,
            f"{label} Paper process crossed its runtime free-commit limit")
    node_heap = preflight.get("nodeHeapLimitBytes")
    require(type(node_heap) is int and 0 < node_heap <= 512 * mib,
            f"{label} Node heap exceeded 512 MiB")

    heap_bytes = []
    for argument in preflight.get("jvmArgs", []):
        if not isinstance(argument, str):
            continue
        match = re.fullmatch(r"-Xmx([1-9][0-9]*)([mMgG])", argument)
        if match:
            heap_bytes.append(int(match.group(1)) * (1024 ** 2 if match.group(2).lower() == "m" else 1024 ** 3))
    require(len(heap_bytes) == 1 and 0 < heap_bytes[0] <= 768 * mib,
            f"{label} Paper maximum heap exceeded 768 MiB or was not recorded exactly once")
    require(preflight.get("paperMaximumHeapBytes") == heap_bytes[0],
            f"{label} Paper heap fingerprint differs from its recorded -Xmx")
    required_reserve = heap_bytes[0] + node_heap + non_heap_reserve + runtime_floor
    required_memory = max(startup_physical_floor, required_reserve)
    required_commit = max(startup_commit_floor, required_reserve)
    require(preflight.get("requiredStartupFreeMemoryBytes") == required_memory and
            preflight.get("requiredStartupFreeCommitBytes") == required_commit,
            f"{label} Paper startup reserve does not match its JVM/Node heap and runtime limits")
    require(type(preflight.get("startupFreeMemoryBytes")) is int and
            preflight["startupFreeMemoryBytes"] >= required_memory,
            f"{label} Paper process started below its physical memory reserve")
    require(type(preflight.get("startupFreeCommitBytes")) is int and
            preflight["startupFreeCommitBytes"] >= required_commit,
            f"{label} Paper process started below its commit memory reserve")
    require(not any(line.startswith(b"STARWORLD_RESOURCE_STOP ") for line in log.splitlines()),
            f"{label} Paper log contains a memory safety stop")


def clean_shutdown(log: bytes) -> bool:
    return (b"StarWorld ready:" in log and (b"Stopping server" in log or b"Stopping the server" in log)
            and b"All dimensions are saved" in log and b"Could not flush StarWorld storage" not in log)


def final_source_manifest(root: Path, version: str) -> dict:
    """Bind the product sources JAR and all native YAML to the same source tree."""
    target = root / "target"
    source_jar = regular_file(target / f"starworld-{version}-sources.jar", target)
    with zipfile.ZipFile(io.BytesIO(source_jar)) as archive:
        require(archive.testzip() is None, "Sources JAR failed CRC validation")
        for folder in ("java", "resources"):
            boundary = root / "src/main" / folder
            for file in sorted(boundary.rglob("*")):
                if file.is_file():
                    name = file.relative_to(boundary).as_posix()
                    require(archive.read(name) == regular_file(file, boundary), f"Sources JAR differs from current source: {name}")
    files = [*sorted((root / "src/main/resources").glob("*.yml")), *sorted((root / "examples").glob("*.yml"))]
    return {"sources_jar_sha256": sha256(source_jar), "configuration_sha256": {
        file.relative_to(root).as_posix(): sha256(regular_file(file, root)) for file in files}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).absolute().parent.parent)
    parser.add_argument("--expansion-dir", type=Path, required=True, help="Passed expansion run directory containing evidence.json and complete transcripts")
    parser.add_argument("--senluo-dir", type=Path, help="Legacy standalone real SenLuo suite (required before 1.0; 1.0 civic suite repeats it)")
    parser.add_argument("--baseline-dir", type=Path, help="Required for 0.3: baseline run on the same final plugin JAR")
    parser.add_argument("--progression-dir", type=Path, help="Required 0.3 geography, reroute and treasury market runtime suite")
    parser.add_argument("--market-upgrade-dir", type=Path, help="Required from 1.5: real 1.4 market migration and receipt replay")
    parser.add_argument("--upgrade-dir", type=Path, help="Required 0.3 migration from a verified 0.2 server state")
    parser.add_argument("--border-dir", type=Path, help="Required 0.3 real neighboring-resource dispute regression")
    parser.add_argument("--operations-dir", type=Path, help="Required for 0.4: route planning, delivery statistics and online backup acceptance")
    parser.add_argument("--disaster-dir", type=Path, help="Required from 0.5: disaster costs, response restart, and finite recovery")
    parser.add_argument("--storage-dir", type=Path, help="Required from 1.4: bounded storage pressure and heap recovery acceptance")
    parser.add_argument("--corridor-dir", type=Path, help="Required from 1.7: physical road/water blockage and restart acceptance")
    parser.add_argument("--refund-dir", type=Path, help="Required from 1.8: bilateral refunds, insolvency and replay over three Paper starts")
    parser.add_argument("--works-dir", type=Path, help="Required for 1.0: public works under three consecutive starts")
    parser.add_argument("--civic-dir", type=Path, help="Required for 1.0: real SenLuo integrations and civic needs, including the original fifteen stages")
    parser.add_argument("--lifecycle-source-dir", type=Path, help="Required from 1.1 onward: real 1.0 source action before migration")
    parser.add_argument("--lifecycle-dir", type=Path, help="Required from 1.1 onward: three-start partial culture migration on the final JAR")
    parser.add_argument("--lifecycle-logistics-dir", type=Path, help="Required from 1.1 onward: four-start transport configuration recovery on the final JAR")
    parser.add_argument("--smoke-dir", type=Path, help="Required for 0.3: no-dependency runtime containing smoke-evidence.json and both smoke-run logs")
    parser.add_argument("--check-only", action="store_true", help="Validate all inputs and print the planned archive without writing")
    args = parser.parse_args()
    root = absolute_directory(args.repository_root)
    pom = ET.fromstring(regular_file(root / "pom.xml", root))
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    require(pom.findtext("m:groupId", namespaces=ns) == "dev.starworld" and
            pom.findtext("m:artifactId", namespaces=ns) == "starworld", "Repository is not StarWorld")
    version = pom.findtext("m:version", namespaces=ns)
    require(bool(version and re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", version)), "Invalid project version")
    release_version = tuple(map(int, version.split("-")[0].split(".")))
    target = absolute_directory(root / "target")
    plugin_hash = sha256(regular_file(target / f"starworld-{version}.jar", target))

    entries: dict[str, bytes] = {}

    def add(name: str, data: bytes) -> None:
        require(not name.startswith("/") and "\\" not in name and ".." not in Path(name).parts,
                f"Invalid archive name: {name}")
        require(name not in entries, f"Duplicate archive entry: {name}")
        entries[name] = data

    machine_dir = absolute_directory(root / "docs/evidence")
    machine_files = sorted(machine_dir.glob("*.json"))
    require(bool(machine_files), "Missing machine evidence")
    unit_data = regular_file(machine_dir / "unit-tests.json", machine_dir)
    unit = read_json(unit_data, "unit-tests.json")
    require(unit.get("version") == version and unit.get("artifact_sha256") == plugin_hash,
            "Unit evidence version/hash differs from the actual final plugin")
    totals = unit.get("totals", {})
    require(isinstance(totals.get("tests"), int) and totals["tests"] > 0 and
            all(totals.get(key) == 0 for key in ("failures", "errors", "skipped")),
            "Unit evidence contains failures/errors/skips or no tests")
    for file in machine_files:
        data = regular_file(file, machine_dir)
        proof = read_json(data, str(file))
        recorded_hash = recorded_artifact_hash(proof)
        require(recorded_hash == plugin_hash, f"Current machine evidence describes another artifact: {file.name}; move historical evidence under history/")
        if "version" in proof:
            require(proof["version"] == version, f"Current machine evidence describes another version: {file.name}")
        if "status" in proof:
            require(proof["status"] == "passed", f"Current machine evidence is incomplete or failed: {file.name}")
        add(f"machine/{file.name}", data)

    reports = absolute_directory(target / "surefire-reports")
    xml_files = sorted(reports.glob("TEST-*.xml"))
    require(bool(xml_files), "Missing actual Surefire XML reports")
    actual_suites = []
    for file in xml_files:
        data = regular_file(file, reports)
        suite = ET.fromstring(data)
        actual_suites.append({"suite": suite.attrib["name"], **{key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}})
        add(f"surefire/{file.name}", data)
        summary = reports / f"{suite.attrib['name']}.txt"
        add(f"surefire/{summary.name}", regular_file(summary, reports))
    recorded_suites = unit.get("suites", [])
    require(sorted(actual_suites, key=lambda x: x["suite"]) == sorted(recorded_suites, key=lambda x: x["suite"]),
            "Surefire suites differ from the recorded final build")
    require({key: sum(s[key] for s in actual_suites) for key in ("tests", "failures", "errors", "skipped")} == totals,
            "Surefire totals differ from the recorded final build")

    coverage = absolute_directory(target / "site/jacoco")
    require((coverage / "index.html").is_file() and (coverage / "jacoco.xml").is_file(), "Missing JaCoCo report")
    coverage_suffixes = {".html", ".css", ".js", ".gif", ".png", ".svg", ".xml", ".csv", ".dtd"}
    for file in sorted(coverage.rglob("*")):
        if file.is_file():
            require(file.suffix.lower() in coverage_suffixes, f"Unexpected JaCoCo file: {file}")
            add(f"jacoco/{file.relative_to(coverage).as_posix()}", regular_file(file, coverage))
    coverage_xml = ET.fromstring(entries["jacoco/jacoco.xml"])
    counters = {c.attrib["type"]: {"covered": int(c.attrib["covered"]), "missed": int(c.attrib["missed"])} for c in coverage_xml.findall("counter")}
    require(counters == unit.get("coverage"), "JaCoCo counters differ from the recorded final build")

    runs = []
    run_inputs = [("expansion", args.expansion_dir, "tools/paper-client/expansion-acceptance.cjs")]
    require(args.senluo_dir is not None or release_version >= (1, 0, 0), "Pre-1.0 delivery requires the standalone SenLuo runtime suite")
    if args.senluo_dir:
        run_inputs.append(("senluo", args.senluo_dir, "tools/senluo-fixture/acceptance.cjs"))
    if args.baseline_dir:
        run_inputs.append(("baseline", args.baseline_dir, "tools/paper-client/acceptance.cjs"))
    if release_version >= (0, 3, 0):
        require(args.baseline_dir is not None, "0.3 delivery requires the final basic gameplay regression")
        require(args.smoke_dir is not None, "0.3 delivery requires the final two-start standalone regression")
        require(args.progression_dir is not None, "0.3 delivery requires final progression runtime evidence")
        run_inputs.append(("progression", args.progression_dir, "tools/paper-client/progression-acceptance.cjs"))
        require(args.upgrade_dir is not None, "0.3 delivery requires real upgrade and restart evidence")
        upgrade_script = ("tools/paper-client/upgrade-10-acceptance.cjs" if release_version >= (1, 0, 0) else
                          "tools/paper-client/upgrade-05-acceptance.cjs" if release_version >= (0, 5, 0) else
                          "tools/paper-client/upgrade-04-acceptance.cjs" if release_version >= (0, 4, 0) else
                          "tools/paper-client/upgrade-acceptance.cjs")
        run_inputs.append(("upgrade", args.upgrade_dir, upgrade_script))
        require(args.border_dir is not None, "0.3 delivery requires real border-resource dispute evidence")
        run_inputs.append(("border", args.border_dir, "tools/paper-client/border-acceptance.cjs"))
        scale_data = regular_file(target / "scale-results.json", target)
        scale = read_json(scale_data, "scale-results.json")
        require(scale.get("cities", 0) >= 1000 and scale.get("settlements", 0) >= 3000 and scale.get("replayedAfterRestart", 0) >= 1000 and scale.get("failures") == 0,
                "Missing successful thousand-city storage baseline")
        require(any("WorldScaleTest" in suite["suite"] and suite["tests"] > 0 for suite in unit["suites"]), "Scale report has no matching verified test suite")
        add("scale/scale-results.json", scale_data)
    if release_version >= (0, 4, 0):
        require(args.operations_dir is not None, "0.4 delivery requires completed operations runtime evidence")
        run_inputs.append(("operations", args.operations_dir, "tools/paper-client/operations-acceptance.cjs"))
    if release_version >= (0, 5, 0):
        require(args.disaster_dir is not None, "0.5 or later delivery requires --disaster-dir")
        run_inputs.append(("disaster", args.disaster_dir, "tools/paper-client/disaster-acceptance.cjs"))
    if release_version >= (1, 4, 0):
        require(args.storage_dir is not None, "1.4 delivery requires --storage-dir")
        run_inputs.append(("storage", args.storage_dir, "tools/paper-client/survey-acceptance.cjs"))
    if release_version >= (1, 5, 0):
        require(args.market_upgrade_dir is not None, "1.5 delivery requires --market-upgrade-dir")
        run_inputs.append(("market-upgrade", args.market_upgrade_dir, "tools/paper-client/market-upgrade-acceptance.cjs"))
    if release_version >= (1, 7, 0):
        require(args.corridor_dir is not None, "1.7 delivery requires --corridor-dir")
        run_inputs.append(("corridor", args.corridor_dir, "tools/paper-client/corridor-acceptance.cjs"))
    if release_version >= (1, 8, 0):
        require(args.refund_dir is not None, "1.8 delivery requires --refund-dir")
        run_inputs.append(("refund", args.refund_dir, "tools/paper-client/refund-acceptance.cjs"))
    if release_version >= (1, 0, 0):
        for label, directory, script in (
                ("works", args.works_dir, "tools/paper-client/works-acceptance.cjs"),
                ("civic", args.civic_dir, "tools/senluo-fixture/civic-acceptance.cjs")):
            require(directory is not None, f"1.0 delivery requires --{label}-dir")
            run_inputs.append((label, directory, script))
        # The combined civic suite must actually retain every standalone SenLuo assertion.
        original_senluo = "tools/senluo-fixture/acceptance.cjs"
        legacy_names = stage_names(regular_file(root / original_senluo, root), original_senluo)
        combined = "tools/senluo-fixture/civic-acceptance.cjs"
        civic_names = stage_names(regular_file(root / combined, root), combined)
        require([name for name in civic_names if name in legacy_names] == legacy_names,
                "The combined civic suite must retain every original SenLuo stage in order")
    transcript_pattern = re.compile(r"(?:paper-run-[1-9][0-9]*|client-run-[A-Za-z0-9_-]+)\.log")
    shared_launcher_hash = sha256(regular_file(root / "tools/paper-client/acceptance.cjs", root))
    expected_clients = {
        "expansion": {f"client-run-{run}-{player}.log" for run in (1, 2) for player in ("a", "b")},
        "senluo": {"client-run-1.log", "client-run-listener-1.log", "client-run-2.log"},
        "baseline": {"client-run-1.log", "client-run-2.log"},
        "progression": {f"client-run-{run}-{player}.log" for run in ((1, 2, 3) if release_version >= (1, 5, 0) else (1, 2)) for player in ("a", "b")},
        "market-upgrade": {f"client-run-{run}-{player}.log" for run in (1, 2) for player in ("a", "b")},
        "upgrade": {"client-run-1.log", "client-run-2.log"},
        "border": {f"client-run-{run}-{player}.log" for run in (1, 2) for player in ("a", "b")},
        "operations": {f"client-run-{run}-{player}.log" for run in (1, 2, 3) for player in ("a", "b")},
        "disaster": {f"client-run-{run}-{player}.log" for run in (1, 2) for player in ("a", "b")},
        "storage": {f"client-run-{run}.log" for run in ((1, 2) if release_version >= (1, 6, 0) else (1,))},
        "corridor": {f"client-run-{run}-{player}.log" for run in (1, 2) for player in ("a", "b")},
        "refund": {f"client-run-{run}-{player}.log" for run in (1, 2, 3) for player in ("a", "b")},
        "works": {f"client-run-{run}-{slot}.log" for run in (1, 2, 3) for slot in range(3)},
        "civic": {"client-run-1.log", "client-run-listener-1.log", "client-run-2.log"},
    }
    for label, raw_directory, script_name in run_inputs:
        directory = absolute_directory(raw_directory)
        data = regular_file(directory / "evidence.json", directory)
        evidence = read_json(data, str(directory / "evidence.json"))
        require(evidence.get("status") == "passed", f"{label} run is not passed")
        require(evidence.get("pluginJars", {}).get("StarWorld.jar") == plugin_hash,
                f"{label} run used another StarWorld artifact")
        steps = evidence.get("steps", [])
        script = regular_file(root / script_name, root)
        expected = stage_names(script, script_name)
        require(isinstance(steps, list) and [s.get("name") for s in steps] == expected and
                all(s.get("status") == "passed" for s in steps), f"{label} run does not prove every current fixture stage")
        require(evidence.get("startedAt") and evidence.get("finishedAt"), f"{label} run has no completed timespan")
        add(f"runtime/{label}/evidence.json", data)
        preparation_name = "UPGRADE-PROVENANCE.json" if label in {"upgrade", "market-upgrade"} else "PREPARATION.json"
        runtime = directory.parent.parent
        preparation = runtime / preparation_name
        preparation_proof = None
        preparation_hash = None
        if preparation.is_file():
            preparation_data = regular_file(preparation, runtime)
            preparation_proof = read_json(preparation_data, str(preparation))
            preparation_hash = sha256(preparation_data)
            add(f"runtime/{label}/{preparation_name}", preparation_data)
        else:
            require(label not in {"upgrade", "market-upgrade", "senluo", "disaster", "civic", "works", "corridor", "refund"}, f"{label} run lacks its source/preparation provenance")
        if release_version >= (1, 0, 0):
            require(evidence.get("version", version) == version and evidence.get("targetVersion", version) == version,
                    f"{label} run declares another product version")
            require(hash_string(evidence.get("paperSha256")), f"{label} lacks its Paper fingerprint")
            plugin_jars = evidence["pluginJars"]
            for name, fingerprint in sorted(plugin_jars.items()):
                require(re.fullmatch(r"[^/\\:]+\.jar", name, re.IGNORECASE) is not None and hash_string(fingerprint),
                        f"Invalid {label} plugin fingerprint/name: {name}")
                require(sha256(regular_file(runtime / "plugins" / name, runtime)) == fingerprint,
                        f"{label} actual installed artifact changed: {name}")
            if label == "storage":
                validate_storage_evidence(evidence, script, plugin_hash)
                require(sha256(regular_file(runtime / "paper.jar", runtime)) == evidence["paperSha256"],
                        "Storage acceptance Paper artifact changed")
                tuning = evidence["testTuning"]
                eula = regular_file(runtime / "eula.txt", runtime).decode("utf-8")
                server_properties = regular_file(runtime / "server.properties", runtime).decode("utf-8")
                ownership = regular_file(runtime / ".starworld-smoke-runtime", runtime).decode("utf-8")
                require(re.search(r"(?m)^\s*eula\s*=\s*true\s*$", eula) is not None and
                        re.search(r"(?m)^\s*server-ip\s*=\s*127\.0\.0\.1\s*$", server_properties) is not None and
                        re.search(r"(?m)^\s*online-mode\s*=\s*false\s*$", server_properties) is not None and
                        ownership.strip() == "StarWorld isolated Paper smoke runtime v1",
                        "Storage acceptance runtime is not an owned localhost test server with accepted EULA")
            if label in {"disaster", "operations", "works", "upgrade"}:
                require(preparation_proof is not None and preparation_proof.get("productVersion", preparation_proof.get("targetVersion")) == version and
                        preparation_proof.get("targetPluginSha256") == plugin_hash, f"{label} preparation is not bound to the final JAR/version")
                if label in {"disaster", "operations"}:
                    require(evidence.get("preparation", {}).get("fileSha256") == preparation_hash and
                            preparation_proof.get("fixtureSha256") == plugin_jars.get("StarWorldFixture.jar"), f"{label} preparation or fixture fingerprint mismatch")
                elif label == "works":
                    require(preparation_proof.get("fixtureSha256") == plugin_jars.get("StarWorldFixture.jar"), "Works preparation fixture mismatch")
                else:
                    require(evidence.get("beforeProvenance") == preparation_proof and hash_string(preparation_proof.get("sourcePluginSha256")) and
                            evidence.get("sourcePluginSha256") == preparation_proof["sourcePluginSha256"] != plugin_hash and
                            preparation_proof.get("fixtureSha256") == plugin_jars.get("StarWorldFixture.jar"), "Upgrade source/target provenance mismatch")
            if label == "market-upgrade":
                require(evidence.get("sourceVersion") == "1.4.0" and evidence.get("runnerSha256") == sha256(script) and
                        evidence.get("probeSha256") == sha256(regular_file(root / "tools/paper-client/upgrade-10-acceptance.cjs", root)) and
                        evidence.get("acceptanceHelperSha256") == shared_launcher_hash and
                        evidence.get("provenanceSha256") == preparation_hash and preparation_proof.get("targetVersion") == version and
                        preparation_proof.get("targetPluginSha256") == plugin_hash,
                        "Market upgrade source/target, runner or provenance mismatch")
                source_evidence = regular_file(Path(preparation_proof["sourceEvidence"]), Path(preparation_proof["source"]))
                source_run = read_json(source_evidence, "market source")
                require(sha256(source_evidence) == preparation_proof.get("sourceEvidenceSha256") and source_run.get("status") == "passed" and
                        source_run.get("suite") == "real-paper-progression" and len(source_run.get("steps", [])) == 20 and
                        all(step.get("status") == "passed" for step in source_run["steps"]) and
                        not any(key in source_run for key in ("error", "cleanupError", "playerCleanupError")),
                        "Market migration lacks its passed original 1.4 gameplay evidence")
                add("runtime/market-upgrade/source-evidence.json", source_evidence)
            if label == "corridor":
                require(evidence.get("suite") == "real-paper-physical-corridors" and
                        evidence.get("runnerSha256") == sha256(script) and
                        evidence.get("acceptanceHelperSha256") == shared_launcher_hash and
                        preparation_proof.get("productVersion") == version and
                        preparation_proof.get("targetPluginSha256") == plugin_hash and
                        preparation_proof.get("fixtureSha256") == plugin_jars.get("StarWorldFixture.jar"),
                        "Corridor preparation, runner or artifact fingerprint differs")
            if label == "refund":
                require(evidence.get("suite") == "real-paper-market-refund" and
                        evidence.get("runnerSha256") == sha256(script) and
                        evidence.get("acceptanceHelperSha256") == shared_launcher_hash and
                        evidence.get("preparationSha256") == preparation_hash and
                        preparation_proof.get("productVersion") == version and
                        preparation_proof.get("targetPluginSha256") == plugin_hash and
                        preparation_proof.get("fixtureSha256") == plugin_jars.get("StarWorldFixture.jar"),
                        "Refund preparation, runner or artifact fingerprint differs")
                upstream_data = regular_file(runtime / "STARCORE-PROVENANCE.json", runtime)
                upstream = read_json(upstream_data, "refund StarCore candidate")
                require(upstream == evidence.get("upstream") and upstream.get("artifact_sha256") == plugin_jars.get("StarCore.jar") and
                        re.fullmatch(r"[0-9a-f]{40}", upstream.get("commit", "")) is not None,
                        "Refund acceptance lacks its exact upstream candidate")
                patch = regular_file(root / "upstream/starcore/0001-atomic-settlements-and-refunds.patch", root)
                require(sha256(patch) == upstream.get("patch_sha256"), "Refund upstream patch changed")
                add("runtime/refund/STARCORE-PROVENANCE.json", upstream_data)
                add("upstream/starcore/0001-atomic-settlements-and-refunds.patch", patch)
            if label in {"civic", "senluo"}:
                asset_proof = dict(evidence.get("assetPreparation", {}))
                require(preparation_proof is not None and preparation_proof.get("starWorldVersion") == version and preparation_proof.get("starWorldSha256") == plugin_hash and
                        asset_proof.pop("fileSha256", None) == preparation_hash and asset_proof == preparation_proof and
                        preparation_proof.get("senluoFixtureSha256") == plugin_jars.get("StarWorldSenLuoFixture.jar"), f"{label} preparation or fixture fingerprint mismatch")
            if label == "civic":
                require(evidence.get("unitEvidence") == unit and preparation_proof.get("unitEvidence", {}).get("sha256") == sha256(unit_data), "Civic run is not bound to the final unit verification")
                require(evidence.get("expectedStages") == len(expected), "Civic expected stage count does not match the shipped script")
            config_hashes = dict(evidence.get("configSha256", {}))
            if label in {"civic", "senluo"}:
                config_hashes.update(preparation_proof["configSha256"])
            if label == "baseline":
                tuning = evidence.get("testTuning", {})
                config_hashes.update({"config.yml": tuning.get("configSha256"), "definitions.yml": tuning.get("definitionsSha256")})
            if label == "works":
                required_works_configs = ("config.yml", "definitions.yml", "evolution.yml", "public-works.yml", "civic-needs.yml", "disaster-response.yml")
                if release_version >= (1, 2, 0):
                    required_works_configs += ("climate.yml",)
                require(all(name in config_hashes for name in required_works_configs),
                        "Works run is missing final configuration fingerprints")
            if label == "upgrade":
                preserved = preparation_proof.get("oldConfigurationSha256", {})
                after_start = evidence.get("startupConfigurationSha256", {})
                added_configs = {"plugins/StarWorld/civic-needs.yml", "plugins/StarWorld/public-works.yml"}
                if release_version >= (1, 2, 0):
                    added_configs.add("plugins/StarWorld/climate.yml")
                require(bool(preserved) and set(after_start) == set(preserved) | added_configs and
                        all(after_start.get(name) == fingerprint for name, fingerprint in preserved.items()), "Upgrade configurations were not preserved and supplemented")
                for name, fingerprint in after_start.items():
                    match = re.fullmatch(r"plugins/StarWorld/([a-z0-9_-]+\.yml)", name)
                    require(match is not None and hash_string(fingerprint), f"Invalid preserved upgrade configuration fingerprint: {name}")
                    config_hashes[match[1]] = fingerprint
            require(bool(config_hashes), f"{label} run lacks runtime configuration fingerprints")
            for name, fingerprint in sorted(config_hashes.items()):
                require(re.fullmatch(r"(?:StarCore/)?[a-z0-9_-]+\.yml", name) is not None and hash_string(fingerprint),
                        f"Invalid {label} configuration fingerprint/name: {name}")
                relative = name if name.startswith("StarCore/") else f"StarWorld/{name}"
                config_data = regular_file(runtime / "plugins" / relative, runtime)
                require(sha256(config_data) == fingerprint, f"{label} runtime configuration changed: {name}")
                # Keep reviewed StarWorld test inputs, never export external StarCore configuration.
                if not name.startswith("StarCore/"):
                    add(f"runtime/{label}/starworld-config/{name}", config_data)
        transcripts = sorted(file for file in directory.iterdir() if transcript_pattern.fullmatch(file.name))
        names = {file.name for file in transcripts}
        required_logs = {"paper-run-1.log", "paper-run-2.log"} | expected_clients[label]
        if label == "storage" and release_version < (1, 6, 0):
            required_logs = {"paper-run-1.log"} | expected_clients[label]
        if label in {"operations", "works", "refund"} or (label == "progression" and release_version >= (1, 5, 0)):
            required_logs.add("paper-run-3.log")
        require(required_logs.issubset(names), f"{label} run lacks required server/client transcripts: {sorted(required_logs - names)}")
        for file in transcripts:
            log = regular_file(file, directory)
            require(bool(log), f"Empty runtime transcript: {file}")
            if release_version >= (1, 0, 0) and file.name.startswith("paper-run-"):
                require(clean_shutdown(log),
                        f"{label} transcript does not show startup and normal shutdown: {file.name}")
            if release_version >= (1, 4, 0) and file.name.startswith("paper-run-"):
                validate_paper_resource_log(log, label, sha256(script), shared_launcher_hash)
            if label == "storage":
                require(b"OutOfMemoryError" not in log and b"Could not flush StarWorld storage" not in log,
                        "Storage acceptance log contains OOM or storage flush failure")
            add(f"runtime/{label}/{file.name}", log)
        runs.append({"suite": label, "source_directory": str(directory), "steps": len(steps),
                     "started_at": evidence["startedAt"], "finished_at": evidence["finishedAt"],
                     "plugin_sha256": plugin_hash, "fixture_script": script_name, "fixture_script_sha256": sha256(script),
                     "preparation_sha256": preparation_hash})

    if release_version >= (1, 1, 0):
        from lifecycle_evidence import collect_lifecycle

        require(all((args.lifecycle_source_dir, args.lifecycle_dir, args.lifecycle_logistics_dir)),
                "Delivery from 1.1 onward requires the source, candidate and logistics lifecycle suites")
        runs.extend(collect_lifecycle(root, version, plugin_hash,
                                      args.lifecycle_source_dir, args.lifecycle_dir, args.lifecycle_logistics_dir,
                                      add, regular_file, read_json, require, stage_names,
                                      validate_paper_resource_log, shared_launcher_hash))

    if args.smoke_dir:
        directory = absolute_directory(args.smoke_dir)
        data = regular_file(directory / "smoke-evidence.json", directory)
        smoke = read_json(data, "smoke-evidence.json")
        require(smoke.get("status") == "passed" and smoke.get("artifact_sha256") == plugin_hash,
                "No-dependency smoke evidence is not passed on the final plugin")
        if release_version >= (1, 0, 0):
            require(smoke.get("version") == version and smoke.get("startedAt") and smoke.get("finishedAt"),
                    "Standalone evidence must record final version and a completed timespan")
            require(sha256(regular_file(directory / "plugins/StarWorld.jar", directory)) == plugin_hash,
                    "Standalone installed JAR differs from the final plugin")
            require({path.name for path in (directory / "plugins").glob("*.jar")} == {"StarWorld.jar"},
                    "Standalone runtime contains other plugin JARs")
        logs = smoke.get("logs", {})
        require(isinstance(logs, dict), "Smoke logs must map filenames to SHA256")
        add("runtime/smoke/smoke-evidence.json", data)
        for name in ("smoke-run-1.log", "smoke-run-2.log"):
            log = regular_file(directory / name, directory)
            require(bool(log) and logs.get(name) == sha256(log), f"Smoke transcript missing or hash mismatch: {name}")
            if release_version >= (1, 0, 0):
                require(clean_shutdown(log),
                        f"Standalone transcript does not show startup and normal shutdown: {name}")
            add(f"runtime/smoke/{name}", log)
        runs.append({"suite": "no-dependency-smoke", "source_directory": str(directory), "plugin_sha256": plugin_hash})

    manifest = {"schema": 1, "version": version, "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                "plugin_sha256": plugin_hash, "unit_tests": totals, "runtime_runs": runs,
                "scope": "Allowlisted evidence only; current machine and runtime inputs use the final plugin. Historical observations remain under docs/evidence/history in the source delivery. Manifest hashes cover all payload entries; manifest.json is excluded from its own self-reference.",
                "entries": [{"path": name, "bytes": len(data), "sha256": sha256(data)} for name, data in sorted(entries.items())]}
    if release_version >= (1, 0, 0):
        manifest["source"] = final_source_manifest(root, version)
    add("manifest.json", (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    output = target / f"StarWorld-{version}-evidence.zip"
    summary = {"path": str(output), "plugin_sha256": plugin_hash, "entries": len(entries), "runtime_runs": runs, "check_only": args.check_only}
    if not args.check_only:
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(prefix="evidence-", suffix=".zip", dir=target, delete=False) as handle:
                temporary = Path(handle.name)
            with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
                for name, data in sorted(entries.items()):
                    archive.writestr(name, data)
            with zipfile.ZipFile(temporary) as archive:
                require(archive.testzip() is None, "Evidence archive failed CRC validation")
                require(len(archive.namelist()) == len(entries), "Evidence archive entry count mismatch")
                for name, data in entries.items():
                    require(sha256(archive.read(name)) == sha256(data), f"Evidence archive changed payload: {name}")
            os.replace(temporary, output)
            temporary = None
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
        summary.update(bytes=output.stat().st_size, sha256=sha256(output.read_bytes()))
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (EvidenceError, OSError, ValueError, KeyError, TypeError, AttributeError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"Evidence packaging refused: {error}", file=sys.stderr)
        raise SystemExit(1)
