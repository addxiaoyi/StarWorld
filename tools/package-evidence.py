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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).absolute().parent.parent)
    parser.add_argument("--expansion-dir", type=Path, required=True, help="Passed expansion run directory containing evidence.json and complete transcripts")
    parser.add_argument("--senluo-dir", type=Path, required=True, help="Passed optional integration run directory containing evidence.json and complete transcripts")
    parser.add_argument("--baseline-dir", type=Path, help="Required for 0.3: baseline run on the same final plugin JAR")
    parser.add_argument("--progression-dir", type=Path, help="Required 0.3 geography, reroute and treasury market runtime suite")
    parser.add_argument("--upgrade-dir", type=Path, help="Required 0.3 migration from a verified 0.2 server state")
    parser.add_argument("--border-dir", type=Path, help="Required 0.3 real neighboring-resource dispute regression")
    parser.add_argument("--operations-dir", type=Path, help="Required for 0.4: route planning, delivery statistics and online backup acceptance")
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
        recorded_hash = proof.get("artifact_sha256") or proof.get("pluginJars", {}).get("StarWorld.jar")
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
    run_inputs = [("expansion", args.expansion_dir, "tools/paper-client/expansion-acceptance.cjs"),
                  ("senluo", args.senluo_dir, "tools/senluo-fixture/acceptance.cjs")]
    if args.baseline_dir:
        run_inputs.append(("baseline", args.baseline_dir, "tools/paper-client/acceptance.cjs"))
    release_version = tuple(map(int, version.split("-")[0].split(".")))
    if release_version >= (0, 3, 0):
        require(args.baseline_dir is not None, "0.3 delivery requires the final basic gameplay regression")
        require(args.smoke_dir is not None, "0.3 delivery requires the final two-start standalone regression")
        require(args.progression_dir is not None, "0.3 delivery requires final progression runtime evidence")
        run_inputs.append(("progression", args.progression_dir, "tools/paper-client/progression-acceptance.cjs"))
        require(args.upgrade_dir is not None, "0.3 delivery requires real upgrade and restart evidence")
        upgrade_script = "tools/paper-client/upgrade-04-acceptance.cjs" if release_version >= (0, 4, 0) else "tools/paper-client/upgrade-acceptance.cjs"
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
    transcript_pattern = re.compile(r"(?:paper-run-[1-9][0-9]*|client-run-[A-Za-z0-9_-]+)\.log")
    expected_clients = {
        "expansion": {f"client-run-{run}-{player}.log" for run in (1, 2) for player in ("a", "b")},
        "senluo": {"client-run-1.log", "client-run-listener-1.log", "client-run-2.log"},
        "baseline": {"client-run-1.log", "client-run-2.log"},
        "progression": {f"client-run-{run}-{player}.log" for run in (1, 2) for player in ("a", "b")},
        "upgrade": {"client-run-1.log", "client-run-2.log"},
        "border": {f"client-run-{run}-{player}.log" for run in (1, 2) for player in ("a", "b")},
        "operations": {f"client-run-{run}-{player}.log" for run in (1, 2, 3) for player in ("a", "b")},
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
        preparation_name = "UPGRADE-PROVENANCE.json" if label == "upgrade" else "PREPARATION.json"
        preparation = directory.parent.parent / preparation_name
        if preparation.is_file():
            preparation_data = regular_file(preparation, directory.parent.parent)
            read_json(preparation_data, str(preparation))
            add(f"runtime/{label}/{preparation_name}", preparation_data)
        else:
            require(label not in {"upgrade", "senluo"}, f"{label} run lacks its source/preparation provenance")
        transcripts = sorted(file for file in directory.iterdir() if transcript_pattern.fullmatch(file.name))
        names = {file.name for file in transcripts}
        required_logs = {"paper-run-1.log", "paper-run-2.log"} | expected_clients[label]
        if label == "operations":
            required_logs.add("paper-run-3.log")
        require(required_logs.issubset(names), f"{label} run lacks complete two-start server/client transcripts: {sorted(required_logs - names)}")
        for file in transcripts:
            log = regular_file(file, directory)
            require(bool(log), f"Empty runtime transcript: {file}")
            add(f"runtime/{label}/{file.name}", log)
        runs.append({"suite": label, "source_directory": str(directory), "steps": len(steps),
                     "started_at": evidence["startedAt"], "finished_at": evidence["finishedAt"],
                     "plugin_sha256": plugin_hash, "fixture_script": script_name, "fixture_script_sha256": sha256(script)})

    if args.smoke_dir:
        directory = absolute_directory(args.smoke_dir)
        data = regular_file(directory / "smoke-evidence.json", directory)
        smoke = read_json(data, "smoke-evidence.json")
        require(smoke.get("status") == "passed" and smoke.get("artifact_sha256") == plugin_hash,
                "No-dependency smoke evidence is not passed on the final plugin")
        logs = smoke.get("logs", {})
        require(isinstance(logs, dict), "Smoke logs must map filenames to SHA256")
        add("runtime/smoke/smoke-evidence.json", data)
        for name in ("smoke-run-1.log", "smoke-run-2.log"):
            log = regular_file(directory / name, directory)
            require(bool(log) and logs.get(name) == sha256(log), f"Smoke transcript missing or hash mismatch: {name}")
            add(f"runtime/smoke/{name}", log)
        runs.append({"suite": "no-dependency-smoke", "source_directory": str(directory), "plugin_sha256": plugin_hash})

    manifest = {"schema": 1, "version": version, "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                "plugin_sha256": plugin_hash, "unit_tests": totals, "runtime_runs": runs,
                "scope": "Allowlisted evidence only; current machine and runtime inputs use the final plugin. Historical observations remain under docs/evidence/history in the source delivery. Manifest hashes cover all payload entries; manifest.json is excluded from its own self-reference.",
                "entries": [{"path": name, "bytes": len(data), "sha256": sha256(data)} for name, data in sorted(entries.items())]}
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
    except (EvidenceError, OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"Evidence packaging refused: {error}", file=sys.stderr)
        raise SystemExit(1)
