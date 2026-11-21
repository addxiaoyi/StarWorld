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
    parser.add_argument("--upgrade-dir", type=Path, help="Required 0.3 migration from a verified 0.2 server state")
    parser.add_argument("--border-dir", type=Path, help="Required 0.3 real neighboring-resource dispute regression")
    parser.add_argument("--operations-dir", type=Path, help="Required for 0.4: route planning, delivery statistics and online backup acceptance")
    parser.add_argument("--disaster-dir", type=Path, help="Required from 0.5: disaster costs, response restart, and finite recovery")
    parser.add_argument("--works-dir", type=Path, help="Required for 1.0: public works under three consecutive starts")
    parser.add_argument("--civic-dir", type=Path, help="Required for 1.0: real SenLuo integrations and civic needs, including the original fifteen stages")
    parser.add_argument("--lifecycle-source-dir", type=Path, help="Required from 1.1: real 1.0 source action before migration")
    parser.add_argument("--lifecycle-dir", type=Path, help="Required from 1.1: three-start 1.0-to-1.1 partial culture migration")
    parser.add_argument("--lifecycle-logistics-dir", type=Path, help="Required from 1.1: four-start transport configuration recovery")
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
    expected_clients = {
        "expansion": {f"client-run-{run}-{player}.log" for run in (1, 2) for player in ("a", "b")},
        "senluo": {"client-run-1.log", "client-run-listener-1.log", "client-run-2.log"},
        "baseline": {"client-run-1.log", "client-run-2.log"},
        "progression": {f"client-run-{run}-{player}.log" for run in (1, 2) for player in ("a", "b")},
        "upgrade": {"client-run-1.log", "client-run-2.log"},
        "border": {f"client-run-{run}-{player}.log" for run in (1, 2) for player in ("a", "b")},
        "operations": {f"client-run-{run}-{player}.log" for run in (1, 2, 3) for player in ("a", "b")},
        "disaster": {f"client-run-{run}-{player}.log" for run in (1, 2) for player in ("a", "b")},
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
        preparation_name = "UPGRADE-PROVENANCE.json" if label == "upgrade" else "PREPARATION.json"
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
            require(label not in {"upgrade", "senluo", "disaster", "civic", "works"}, f"{label} run lacks its source/preparation provenance")
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
        if label in {"operations", "works"}:
            required_logs.add("paper-run-3.log")
        require(required_logs.issubset(names), f"{label} run lacks required server/client transcripts: {sorted(required_logs - names)}")
        for file in transcripts:
            log = regular_file(file, directory)
            require(bool(log), f"Empty runtime transcript: {file}")
            if release_version >= (1, 0, 0) and file.name.startswith("paper-run-"):
                require(clean_shutdown(log),
                        f"{label} transcript does not show startup and normal shutdown: {file.name}")
            add(f"runtime/{label}/{file.name}", log)
        runs.append({"suite": label, "source_directory": str(directory), "steps": len(steps),
                     "started_at": evidence["startedAt"], "finished_at": evidence["finishedAt"],
                     "plugin_sha256": plugin_hash, "fixture_script": script_name, "fixture_script_sha256": sha256(script),
                     "preparation_sha256": preparation_hash})

    if release_version >= (1, 1, 0):
        from lifecycle_evidence import collect_lifecycle

        require(all((args.lifecycle_source_dir, args.lifecycle_dir, args.lifecycle_logistics_dir)),
                "1.1 delivery requires the source, candidate and logistics lifecycle suites")
        runs.extend(collect_lifecycle(root, version, plugin_hash,
                                      args.lifecycle_source_dir, args.lifecycle_dir, args.lifecycle_logistics_dir,
                                      add, regular_file, read_json, require, stage_names))

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
