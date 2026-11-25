"""Strict, read-only collection of the independent StarWorld 1.x lifecycle suites.

Only allowlisted JSON, text logs, fixture sources and StarWorld YAML enter the ZIP.
Installed JARs are hashed/inspected, never added. Database files are never opened.
The public entry point uses the parent packager's file and failure primitives.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import io
import os
from pathlib import Path
import re
import zipfile

_SHA = re.compile(r"[a-f0-9]{64}")
_UUID = re.compile(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", re.I)
_MIN_COMMIT = 1610612736
_MIN_FREE_DISK = 1024 * 1024 * 1024
_LIFE_SCRIPT = "tools/senluo-fixture/lifecycle-acceptance.cjs"
_LOGISTICS_SCRIPT = "tools/paper-client/lifecycle-logistics-acceptance.cjs"
_MARKER = b"StarWorld isolated Paper smoke runtime v1"
_BAD_LOG = re.compile(rb"OutOfMemoryError|StarWorld startup failed|Could not flush StarWorld storage|Could not close StarWorld storage|SW_SENLUO_FAILED|SW_FIXTURE_FAILED")
_BUSINESS_LOG = re.compile(rb"Settlement failed \(transaction rolled back\)|StarWorld tick failed|Extension tick failed")


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _absolute(value):
    return Path(os.path.abspath(value))


def _minor_version(version):
    match = re.fullmatch(r"1\.(\d+)\.\d+(?:-[A-Za-z0-9.-]+)?", str(version))
    return int(match[1]) if match else None


class _Collector:
    def __init__(self, root, version, plugin_hash, add, regular_file, read_json, require, stage_names,
                 validate_resource_log=None, shared_helper_hash=None):
        self.root, self.version, self.plugin_hash = _absolute(root), version, plugin_hash
        self.add, self.regular_file, self.read_json = add, regular_file, read_json
        self.require, self.stage_names = require, stage_names
        self.validate_resource_log, self.shared_helper_hash = validate_resource_log, shared_helper_hash

    def check(self, condition, message):
        self.require(bool(condition), message)

    def read(self, path, boundary):
        return self.regular_file(_absolute(path), _absolute(boundary))

    def obj(self, path, boundary):
        return self.read_json(self.read(path, boundary), str(path))

    def emit(self, label, name, data):
        self.check(re.fullmatch(r"[A-Za-z0-9_./-]+\.(?:json|log|yml|cjs|java|py)", name), "Unsafe lifecycle archive member: " + name)
        self.check(not name.startswith("/") and ".." not in Path(name).parts and "\\" not in name,
                   "Lifecycle member escapes the archive: " + name)
        self.check(isinstance(data, bytes) and b"\x00" not in data, "Binary data cannot enter a lifecycle text member")
        try:
            data.decode("utf-8-sig")
        except UnicodeDecodeError:
            self.check(False, "Lifecycle text is not UTF-8: " + name)
        self.add("runtime/" + label + "/" + name, data)

    def timestamp(self, value, label):
        self.check(isinstance(value, str), "Missing timestamp: " + label)
        try:
            parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            self.check(False, "Invalid timestamp: " + label)
        self.check(parsed.tzinfo is not None, "Timezone is required: " + label)
        return parsed

    def run(self, directory, script_name, expected, label, mode=None):
        directory = _absolute(directory)
        runtime = directory.parent.parent
        data = self.read(directory / "evidence.json", runtime)
        evidence = self.read_json(data, str(directory / "evidence.json"))
        self.check(evidence.get("schema") == 1 and evidence.get("status") == "passed", label + " is not a passed schema1 run")
        self.check(not any(key in evidence for key in ("error", "cleanupError", "playerCleanupError", "configurationRestoreError")),
                   label + " retains an error or cleanup failure")
        if mode:
            self.check(evidence.get("suite") == "real-paper-life-carry" and evidence.get("mode") == mode, label + " has another suite/mode")
            self.check(directory.parent.name == "lifecycle-acceptance" and directory.name.startswith(mode + "-"), label + " is outside its runner directory")
            self.check(_absolute(evidence.get("runtime", "")) == runtime, label + " runtime path differs from its evidence directory")
        else:
            self.check(evidence.get("suite") == "real-paper-lifecycle-logistics" and directory.parent.name == "lifecycle-logistics-acceptance",
                       "Logistics evidence is outside its runner directory")
        steps = evidence.get("steps")
        self.check(isinstance(steps, list) and [step.get("name") for step in steps] == expected
                   and all(step.get("status") == "passed" for step in steps), label + " does not prove the exact current stage sequence")
        self.check(evidence.get("expectedStages", len(expected)) == len(expected), label + " declares another stage count")
        start = self.timestamp(evidence.get("startedAt"), label + ".startedAt")
        finish = self.timestamp(evidence.get("finishedAt"), label + ".finishedAt")
        self.check(start <= finish, label + " has a reversed timespan")
        previous = start
        for index, step in enumerate(steps):
            first = self.timestamp(step.get("startedAt"), label + ".step.startedAt")
            last = self.timestamp(step.get("finishedAt"), label + ".step.finishedAt")
            self.check(previous <= first <= last <= finish, label + " stage timestamps overlap or escape the run: " + str(index))
            previous = last
        script = self.read(self.root / script_name, self.root)
        if "runnerSha256" in evidence:
            self.check(evidence["runnerSha256"] == _sha(script), label + " runner changed after execution")
        self.emit(label, "evidence.json", data)
        self.emit(label, "fixture-script.cjs", script)
        return {"directory": directory, "runtime": runtime, "data": data, "e": evidence, "steps": steps,
                "label": label, "script": script_name, "script_sha256": _sha(script), "start": start, "finish": finish}

    def plugins(self, run, expected, version, final=True):
        runtime, label = run["runtime"], run["label"]
        self.check(isinstance(expected, dict) and bool(expected), label + " lacks installed JAR fingerprints")
        actual = {file.name for file in (runtime / "plugins").glob("*.jar")}
        self.check(actual == set(expected), label + " installed plugin set changed")
        for name, fingerprint in expected.items():
            self.check(re.fullmatch(r"[^/\\:]+\.jar", name, re.I) and _SHA.fullmatch(str(fingerprint)),
                       label + " has an invalid plugin filename/hash")
            binary = self.read(runtime / "plugins" / name, runtime)
            self.check(_sha(binary) == fingerprint, label + " installed JAR changed: " + name)
            if name == "StarWorld.jar":
                with zipfile.ZipFile(io.BytesIO(binary)) as jar:
                    descriptor = jar.read("plugin.yml").decode("utf-8")
                match = re.search(r"^version:\s*['\"]?([^'\"\s]+)", descriptor, re.M)
                self.check(match and match[1] == version, label + " installed plugin.yml version mismatch")
        self.check(expected.get("StarWorld.jar") is not None, label + " has no actual StarWorld JAR")
        if final:
            self.check(version == self.version and expected["StarWorld.jar"] == self.plugin_hash, label + " is not bound to the final product")

    def configs(self, run, expected):
        runtime, label = run["runtime"], run["label"]
        self.check(isinstance(expected, dict) and expected, label + " lacks configuration fingerprints")
        native = {name for name in expected if not name.startswith("StarCore/")}
        self.check(native == {file.name for file in (runtime / "plugins/StarWorld").glob("*.yml")}, label + " native configuration set changed")
        contents = {}
        for name, fingerprint in expected.items():
            self.check(re.fullmatch(r"(?:StarCore/)?[a-z0-9_-]+\.yml", name) and _SHA.fullmatch(str(fingerprint)),
                       label + " has an invalid configuration fingerprint")
            relative = name if name.startswith("StarCore/") else "StarWorld/" + name
            data = self.read(runtime / "plugins" / relative, runtime)
            self.check(_sha(data) == fingerprint, label + " final configuration differs: " + name)
            if not name.startswith("StarCore/"):
                self.emit(label, "starworld-config/" + name, data)
                contents[name] = data
        return contents

    def numeric(self, data, key):
        rows = re.findall(rb"^[ \t]*" + re.escape(key.encode()) + rb":[ \t]*([0-9]+)(?:[ \t]*(?:#.*)?)?\r?$", data, re.M)
        self.check(len(rows) == 1, "Need one numeric configuration value: " + key)
        return int(rows[0])

    def replace(self, data, key, value):
        expression = rb"^([ \t]*" + re.escape(key.encode()) + rb":[ \t]*)[0-9]+([ \t]*(?:#.*)?\r?)$"
        changed, count = re.subn(expression, lambda m: m[1] + str(value).encode() + m[2], data, flags=re.M)
        self.check(count == 1, "Need one explicit configuration replacement: " + key)
        return changed

    def memory(self, run, starts, separate_files=False):
        rows = run["e"].get("startPreflight")
        self.check(isinstance(rows, list) and [row.get("run") for row in rows] == list(range(1, starts + 1)),
                   run["label"] + " lacks every start's free-commit preflight")
        if run["label"] == "lifecycle-logistics":
            self.check(run["e"].get("testTuning", {}).get("minimumFreeDiskBytes") == _MIN_FREE_DISK,
                       "Logistics run lacks its 1 GiB free-disk policy")
        for row in rows:
            self.check(run["start"] <= self.timestamp(row.get("at"), "memory.at") <= run["finish"], "Memory preflight escapes the run")
            self.check(isinstance(row.get("windows"), bool) and row.get("minimumFreeCommitBytes") == _MIN_COMMIT,
                       "Missing platform/minimum commit proof")
            if row["windows"]:
                self.check(type(row.get("freeCommitBytes")) is int and row["freeCommitBytes"] >= _MIN_COMMIT,
                           "A server was started below the minimum free commit")
            if run["label"] == "lifecycle-logistics":
                self.check(row.get("minimumFreeDiskBytes") == _MIN_FREE_DISK and
                           type(row.get("freeDiskBytes")) is int and row["freeDiskBytes"] >= _MIN_FREE_DISK,
                           "A logistics server was started below the 1 GiB free-disk reserve")
            if separate_files:
                name = "memory-before-run-" + str(row["run"]) + ".json"
                data = self.read(run["directory"] / name, run["runtime"])
                self.check(self.read_json(data, name) == row, "Memory JSON differs from its embedded proof")
                self.emit(run["label"], name, data)

    def logs(self, run, starts, paired=False):
        directory, label = run["directory"], run["label"]
        paper = {"paper-run-" + str(number) + ".log" for number in range(1, starts + 1)}
        clients = ({"client-run-" + str(number) + "-" + slot + ".log" for number in range(1, starts + 1) for slot in ("a", "b")}
                   if paired else {"client-run-" + str(number) + ".log" for number in range(1, starts + 1)})
        actual = {file.name for file in directory.glob("*.log")}
        self.check(actual == paper | clients, label + " has missing or extra Paper/client transcripts")
        result = {}
        for name in sorted(actual):
            data = self.read(directory / name, run["runtime"])
            self.check(bool(data.strip()), label + " has an empty transcript: " + name)
            if name in paper:
                stopping = re.search(rb"Stopping (?:the )?server", data)
                running = data[:stopping.start()] if stopping else data
                self.check(b"StarWorld ready:" in data and b"All dimensions are saved" in data
                           and (b"Stopping server" in data or b"Stopping the server" in data)
                           and not _BAD_LOG.search(data) and not _BUSINESS_LOG.search(running), label + " has an incomplete or failed Paper log: " + name)
                if self.validate_resource_log:
                    self.validate_resource_log(data, label, run["script_sha256"], self.shared_helper_hash)
            else:
                self.check(not re.search(rb"(?:^|\n)ERROR |Client kicked", data), label + " has a failed actual client")
                stamps = re.findall(rb"(?m)^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z)", data)
                self.check(bool(stamps), label + " has no actual timestamped client output")
                for stamp in stamps:
                    when = self.timestamp(stamp.decode(), name)
                    self.check(run["start"] <= when <= run["finish"], label + " client output escapes the run")
            self.emit(label, name, data)
            result[name] = data
        return result

    def snapshot(self, run, name, expected):
        data = self.read(run["directory"] / name, run["runtime"])
        value = self.read_json(data, name)
        self.check(value == expected, run["label"] + " stopped snapshot differs from embedded evidence: " + name)
        self.emit(run["label"], name, data)
        return value

    def life_snapshot(self, value):
        self.check(isinstance(value, dict), "Missing life stopped snapshot")
        for label in ("world", "core"):
            part = value.get(label, {})
            self.check(part.get("integrity") == "ok" and part.get("foreignKeys") == []
                       and _SHA.fullmatch(str(part.get("sha256", ""))) and isinstance(part.get("rows"), dict),
                       "Invalid stopped life snapshot integrity/hash: " + label)
        return value["world"]["rows"]

    def record(self, run, plugin_hash, preparation_hash=None):
        e = run["e"]
        return {"suite": run["label"], "source_directory": str(run["directory"]), "steps": len(run["steps"]),
                "started_at": e["startedAt"], "finished_at": e["finishedAt"], "plugin_sha256": plugin_hash,
                "fixture_script": run["script"], "fixture_script_sha256": run["script_sha256"],
                "preparation_sha256": preparation_hash, "version": e.get("version", self.version)}


def _index(rows, column, check):
    check(isinstance(rows, list), "Missing immutable row list")
    result = {row[column]: row for row in rows}
    check(len(result) == len(rows), "Duplicate immutable row ID: " + column)
    return result


def _unchanged(old, new, check, label):
    before, after = _index(old, "id", check), _index(new, "id", check)
    check(all(after.get(key) == value for key, value in before.items()), "Original rows changed/disappeared: " + label)


def _budget(rows, identity, period, amount, check):
    matching = [row for row in rows if row["city"] == identity["cityId"] and row["nation"] == identity["nationId"]]
    check(len(matching) == 1 and matching[0].get("period") == period and matching[0].get("culture") == amount,
          "Life shared period budget does not prove the actual credit amount")


def _credit(state, action_id, original, credited, period, pending, identity, check):
    expected = {"actionId": action_id, "cityId": identity["cityId"], "nationId": identity["nationId"],
                "originalCulture": original, "creditedCulture": credited, "lastCreditedPeriod": period, "pending": pending}
    check(state.get("credits", {}).get(action_id) == expected, "Public exact-ID evolution credit mismatch: " + action_id)


def _validate_life_candidate_configs(c, source_configs, candidate_configs, candidate_runtime):
    source_names, candidate_names = set(source_configs), set(candidate_configs)
    c.check("climate.yml" not in source_names, "Life source version 1.0 unexpectedly contains climate.yml")
    expected_candidate_names = source_names
    minor = _minor_version(c.version)
    if minor is not None and minor >= 2:
        expected_candidate_names = source_names | {"climate.yml"}
    c.check(candidate_names == expected_candidate_names, "Life candidate configuration set changed")
    for name in source_names:
        expected = c.replace(c.replace(source_configs[name], "player-culture-per-period", 20),
                             "city-culture-per-period", 20) if name == "life.yml" else source_configs[name]
        c.check(candidate_configs[name] == expected,
                "Candidate has configuration changes beyond the two declared caps: " + name)
    if "climate.yml" not in candidate_names - source_names:
        return
    candidate_jar = c.read(candidate_runtime / "plugins" / "StarWorld.jar", candidate_runtime)
    try:
        with zipfile.ZipFile(io.BytesIO(candidate_jar)) as jar:
            climate_default = jar.read("climate.yml")
    except (KeyError, zipfile.BadZipFile) as error:
        c.check(False, "Candidate StarWorld JAR lacks a readable climate.yml default: " + str(error))
        return
    c.check(candidate_configs["climate.yml"] == climate_default,
            "Candidate climate.yml differs from the final StarWorld JAR default")


def _coherent(state, identity, check):
    check(all(state.get(key) == value for key, value in identity.items()), "Live city/nation/actor identity changed")
    check(state.get("gameplayWrites") is False and state.get("creditApiAvailable") is True, "Diagnostics are not the actual read-only API")
    p = state.get("period")
    check(type(p) is int and state.get("observedPeriod") == p and state.get("city", {}).get("lastPeriod") == p
          and state.get("profile", {}).get("lastPeriod") == p and state.get("life", {}).get("decorationPeriod") == p,
          "Life credit frame is not a coherent actual online city settlement")
    return p


def _same_read(before, after, check):
    check(before.get("period") == after.get("period") and before.get("city", {}).get("lastPeriod") == after.get("city", {}).get("lastPeriod"),
          "Read-only retry crossed its claimed actual period")
    for key in ("credits", "profile", "stock"):
        check(before.get(key) == after.get(key), "Read-only diagnostic mutated " + key)
    check(before.get("life", {}).get("culture") == after.get("life", {}).get("culture"), "Read-only diagnostic granted culture")


def _collect_life(c, source_dir, candidate_dir):
    script = c.read(c.root / _LIFE_SCRIPT, c.root)
    names = c.stage_names(script, _LIFE_SCRIPT)
    c.check(len(names) == 11, "Life runner must contain exactly four source and seven candidate stages")
    source = c.run(source_dir, _LIFE_SCRIPT, names[:4], "life-source", "source")
    candidate = c.run(candidate_dir, _LIFE_SCRIPT, names[4:], "life-carry", "candidate")
    se, ce = source["e"], candidate["e"]
    sp, cp = se.get("preparation", {}), ce.get("preparation", {})
    c.check(se.get("version") == "1.0.0" and ce.get("version") == c.version, "Life source/target version mismatch")
    c.check(source["finish"] <= candidate["start"], "Candidate ran before the genuine stopped source completed")
    c.plugins(source, sp.get("pluginJars"), "1.0.0", final=False)
    c.plugins(candidate, cp.get("pluginJars"), c.version)
    sc, cc = c.configs(source, sp.get("configSha256")), c.configs(candidate, cp.get("configSha256"))
    _validate_life_candidate_configs(c, sc, cc, candidate["runtime"])
    for config in (sc, cc):
        c.check(c.numeric(config["config.yml"], "period-seconds") == 60 and c.numeric(config["config.yml"], "event-chance") == 0,
                "Life run observation timing/events differ from its declared inputs")
        c.check(c.numeric(config["life.yml"], "profession-cooldown-periods") == 1, "Life profession cooldown changed")
    for key in ("player-culture-per-period", "city-culture-per-period"):
        c.check(c.numeric(sc["life.yml"], key) >= 25 and c.numeric(cc["life.yml"], key) == 20, "Life lowered-cap scenario was not actually configured")
    for key in ("javaSha256", "paperSha256"):
        c.check(_SHA.fullmatch(str(sp.get(key, ""))) and cp.get(key) == sp[key], "Life Java/Paper inputs changed between source and target")
    c.check(set(sp["pluginJars"]) == set(cp["pluginJars"]), "Source/target plugin sets differ")
    c.check(all(cp["pluginJars"][name] == value for name, value in sp["pluginJars"].items() if name != "StarWorld.jar"),
            "Life candidate changed an integration or fixture JAR")
    link = cp.get("source", {})
    c.check(_absolute(link.get("runtime", "")) == source["runtime"]
            and _absolute(link.get("evidenceFile", "")) == source["directory"] / "evidence.json"
            and link.get("evidenceSha256") == _sha(source["data"]), "Candidate is not bound to the exact immutable source evidence")
    original = sp.get("original", {})
    original_runtime = _absolute(original.get("runtime", ""))
    original_file = _absolute(original.get("evidenceFile", ""))
    old_data = c.read(original_file, original_runtime)
    old = c.read_json(old_data, str(original_file))
    c.check(_sha(old_data) == original.get("evidenceSha256") and old.get("status") == "passed" and old.get("version") == "1.0.0"
            and len(old.get("steps", [])) >= 30 and all(row.get("status") == "passed" for row in old["steps"]),
            "Original real1.0 acceptance evidence changed or is incomplete")
    historical_names = c.stage_names(c.read(c.root / "tools/senluo-fixture/civic-acceptance.cjs", c.root), "historical civic stages")
    c.check(len(historical_names) == 30 and [row.get("name") for row in old["steps"]] == historical_names,
            "Original1.0 evidence is missing the actual thirty civic/SenLuo stages")
    c.check(original_file.name == "evidence.json" and original_file.parent.parent.name == "civic-senluo-acceptance"
            and c.timestamp(old.get("finishedAt"), "original1.0.finishedAt") <= source["start"], "Original1.0 evidence path/time is not the passed historical input")
    c.check(old.get("pluginJars", {}).get("StarWorld.jar") == sp["pluginJars"]["StarWorld.jar"], "Life source is not the previously accepted actual1.0 product")
    c.emit("life-source", "original-1.0-evidence.json", old_data)
    original_configs = original.get("configurationSha256", {})
    c.check(set(original_configs) == set(sc), "Original configuration binding is incomplete")
    for name, fingerprint in original_configs.items():
        c.check(re.fullmatch(r"[a-z0-9_-]+\.yml", name) and _SHA.fullmatch(str(fingerprint)), "Unsafe original configuration binding")
        data = c.read(original_runtime / "plugins/StarWorld" / name, original_runtime)
        c.check(_sha(data) == fingerprint, "Original accepted configuration changed after source capture")
        expected = c.replace(data, "period-seconds", 60) if name == "config.yml" else data
        c.check(sc[name] == expected, "Source changed more than the declared isolated period window")
    c.memory(source, 1)
    c.memory(candidate, 3)
    source_logs, candidate_logs = c.logs(source, 1), c.logs(candidate, 3)
    sb, sa, cb = c.life_snapshot(se["before"]), c.life_snapshot(se["after"]), c.life_snapshot(ce["before"])
    c.check(se["before"].get("version") == se["after"].get("version") == "1.0.0" and ce["before"].get("version") == c.version,
            "Stopped source/target installed version mismatch")
    c.check(sb.get("life_schema") == sa.get("life_schema") == cb.get("life_schema") == [{"id": 1, "version": 1}], "Source was not genuine life schema1")
    c.check("credited_culture" not in se["after"]["world"].get("columns", {}).get("life_evolution_action", []), "Source was synthesized from life schema2")
    for label in ("world", "core"):
        c.check(se["before"][label]["sha256"] == original.get("databases", {}).get(label), "Source did not begin with the exact stopped original")
        c.check(ce["before"][label] == se["after"][label], "Candidate database probe differs from the proved stopped source")
    c.check(link.get("oldAction") == se.get("oldAction"), "Candidate source-action binding changed")
    identity = se.get("identity", {})
    c.check(set(identity) == {"cityId", "nationId", "actorId"} and all(_UUID.fullmatch(str(value)) for value in identity.values()), "Missing original identity")
    old_id, new_id = se.get("oldActionId"), ce.get("newActionId")
    c.check(_UUID.fullmatch(str(old_id)) and _UUID.fullmatch(str(new_id)) and old_id != new_id, "Missing actual distinct action receipt IDs")
    before_actions, source_actions = _index(sb["life_action"], "id", c.check), _index(sa["life_action"], "id", c.check)
    _unchanged(sb["life_action"], sa["life_action"], c.check, "source life_action")
    c.check(set(source_actions) - set(before_actions) == {old_id}, "Source must add exactly the one real Cookery action")
    action = source_actions[old_id]
    c.check(action == se.get("oldAction") and action.get("culture") == 25 and action.get("eligible") == 1
            and action.get("kind") == "feast" and action.get("source") == "cookery"
            and (action.get("city"), action.get("nation"), action.get("actor")) == (identity["cityId"], identity["nationId"], identity["actorId"]),
            "Stopped source action is not the real original Cookery25")
    consumed = _index(sa["life_evolution_action"], "id", c.check)
    c.check(bool(consumed) and old_id not in consumed and {row["id"] for row in sa["life_action"] if row.get("eligible") == 1 and row["id"] not in consumed} == {old_id},
            "Source Cookery25 was already consumed or other pending evidence interferes")
    c.snapshot(source, "stopped-source-final.json", se["after"])
    partial = c.snapshot(candidate, "stopped-candidate-partial.json", ce.get("partial"))
    complete = c.snapshot(candidate, "stopped-candidate-complete.json", ce.get("complete"))
    final = c.snapshot(candidate, "stopped-candidate-final.json", ce.get("after"))
    pr, cr, fr = [c.life_snapshot(value) for value in (partial, complete, final)]
    for snapshot, rows in ((partial, pr), (complete, cr), (final, fr)):
        c.check(snapshot.get("version") == c.version and rows.get("life_schema") == [{"id": 1, "version": 2}], "Candidate did not commit life schema2")
        _unchanged(sa["life_action"], rows["life_action"], c.check, "migrated original life_action")
        migrated = _index(rows["life_evolution_action"], "id", c.check)
        for key, old_credit in consumed.items():
            c.check(migrated.get(key) == {"id": key, "consumed_period": old_credit["consumed_period"], "credited_culture": source_actions[key]["culture"]},
                    "Already consumed original life credit changed/requeued")
    partial_api, partial_read = candidate["steps"][0]["result"], candidate["steps"][1]["result"]
    partial_period = _coherent(partial_api, identity, c.check)
    _credit(partial_api, old_id, 25, 20, partial_period, True, identity, c.check)
    _same_read(partial_api, partial_read, c.check)
    new_api = candidate["steps"][2]["result"]["after"]
    c.check(new_api.get("period") == partial_period, "New community action crossed the partial20 period")
    _credit(new_api, old_id, 25, 20, partial_period, True, identity, c.check)
    _credit(new_api, new_id, 12, 0, -1, True, identity, c.check)
    new_action = _index(pr["life_action"], "id", c.check).get(new_id, {})
    c.check(new_action.get("culture") == 12 and new_action.get("kind") == "feast" and new_action.get("source") == "community"
            and new_action.get("actor") == identity["actorId"] and new_action.get("city") == identity["cityId"] and new_action.get("nation") == identity["nationId"],
            "Candidate new12 is not a real community receipt")
    pcredits = _index(pr["life_evolution_action"], "id", c.check)
    c.check(pcredits.get(old_id) == {"id": old_id, "consumed_period": partial_period, "credited_culture": 20}
            and new_id not in pcredits and {row["id"] for row in pr["life_evolution_pending"]} == {old_id, new_id},
            "Stopped partial progress/queue is not20 plus pending12")
    _budget(pr["life_evolution_budget"], identity, partial_period, 20, c.check)
    full_api = candidate["steps"][3]["result"]
    full_period = _coherent(full_api, identity, c.check)
    c.check(full_period > partial_period, "Restart did not use a later actual online period")
    _credit(full_api, old_id, 25, 25, full_period, False, identity, c.check)
    _credit(full_api, new_id, 12, 12, full_period, False, identity, c.check)
    _same_read(full_api, candidate["steps"][4]["result"], c.check)
    _budget(cr["life_evolution_budget"], identity, full_period, 17, c.check)
    c.check(cr["life_evolution_pending"] == fr["life_evolution_pending"] == [], "Completed originals remain pending")
    c.check(cr["life_evolution_action"] == fr["life_evolution_action"] and cr["life_evolution_budget"] == fr["life_evolution_budget"]
            and cr["life_action"] == fr["life_action"], "Later restart repeated culture or changed original receipts")
    c.check(set(_index(fr["life_action"], "id", c.check)) == set(source_actions) | {new_id}, "Candidate invented another life action")
    third = candidate["steps"][6]["result"]
    c.check(third.get("netNewLifeCredit") == 37 and third.get("offlineCreditInvented") == 0
            and third.get("stopped") == final and third.get("next", {}).get("credits") == full_api.get("credits"), "Third restart does not conserve37")
    _coherent(third["next"], identity, c.check)
    c.check(old_id.encode() in source_logs["client-run-1.log"] and "文化 +25".encode() in source_logs["client-run-1.log"],
            "Real source client lacks its actual Cookery25 receipt")
    c.check(new_id.encode() in candidate_logs["client-run-1.log"] and "文化 +12".encode() in candidate_logs["client-run-1.log"],
            "Actual candidate client lacks the new community12 receipt")
    return [c.record(source, sp["pluginJars"]["StarWorld.jar"], original["evidenceSha256"]),
            c.record(candidate, c.plugin_hash, link["evidenceSha256"])]


def _collect_logistics(c, directory):
    script = c.read(c.root / _LOGISTICS_SCRIPT, c.root)
    names = c.stage_names(script, _LOGISTICS_SCRIPT)
    c.check(len(names) == 14, "Logistics runner must prove exactly fourteen stages")
    run = c.run(directory, _LOGISTICS_SCRIPT, names, "lifecycle-logistics")
    e, runtime = run["e"], run["runtime"]
    c.check(e.get("minecraft") == "1.21.11" and e.get("runnerSha256") == _sha(script), "Logistics runner/Minecraft binding changed")
    prep_data = c.read(runtime / "PREPARATION.json", runtime)
    prep = c.read_json(prep_data, "logistics PREPARATION.json")
    embedded = dict(e.get("preparation", {}))
    c.check(embedded.pop("sha256", None) == _sha(prep_data) and embedded == prep, "Logistics preparation changed after execution")
    c.check(prep.get("schema") == 1 and prep.get("artifactsBound") is True and prep.get("productVersion") == c.version
            and prep.get("targetPluginSha256") == c.plugin_hash and all(prep.get(key) == 0 for key in ("worldsCopied", "databasesCopied", "playersCopied")),
            "Logistics fresh-runtime final-product preparation is incomplete")
    c.emit(run["label"], "PREPARATION.json", prep_data)
    c.plugins(run, e.get("pluginJars"), c.version)
    c.check(prep.get("fixtureSha256") == e["pluginJars"].get("StarWorldFixture.jar"), "Logistics Paper fixture is unbound")
    configs = c.configs(run, e.get("configSha256"))
    c.check(c.numeric(configs["config.yml"], "period-seconds") == 10 and c.numeric(configs["config.yml"], "event-chance") == 0,
            "Logistics timing/events drifted")
    c.memory(run, 4, separate_files=True)
    c.logs(run, 4, paired=True)
    original = c.read(run["directory"] / "logistics-original.yml", runtime)
    c.check(original == configs["logistics.yml"] == c.read(c.root / "src/main/resources/logistics.yml", c.root)
            and e.get("originalConfigurationRestored") is True, "Logistics original configuration was not restored byte-for-byte")
    c.emit(run["label"], "logistics-original.yml", original)
    changes = e.get("configurationChanges")
    c.check(isinstance(changes, list) and [row.get("label") for row in changes] == ["travel-reduced", "waypoints-reduced", "restored"],
            "Logistics must isolate the three exact stopped configuration changes")
    c.check(type(changes[0].get("travelPeriods")) is int and 1 <= changes[0]["travelPeriods"] < 1000
            and changes[0].get("waypoints") == 16 and changes[1].get("travelPeriods") == 1000
            and changes[1].get("waypoints") == 2 and changes[2].get("travelPeriods") == 1000 and changes[2].get("waypoints") == 16,
            "Travel and waypoint limits were not independently restricted and restored")
    for row in changes:
        name = "logistics-" + row["label"] + ".yml"
        data = c.read(run["directory"] / name, runtime)
        c.check(row.get("whileServerStopped") is True and row.get("sha256") == _sha(data)
                and data == c.replace(c.replace(original, "travel-periods", row["travelPeriods"]), "waypoints", row["waypoints"]),
                "Logistics change touched other keys or has an unbound hash")
        c.emit(run["label"], name, data)
    snapshots = []
    for label, index in (("default", 3), ("travel-reduced", 7), ("waypoints-reduced", 10), ("restored", 13)):
        observed = run["steps"][index].get("observed", {})
        expected = observed if label == "restored" else observed.get("checkpoint")
        snapshot = c.snapshot(run, "stopped-" + label + ".json", expected)
        c.check(snapshot.get("mode") == "ro" and snapshot.get("integrity") == "ok" and _SHA.fullmatch(str(snapshot.get("sha256", "")))
                and isinstance(snapshot.get("rows"), dict), "Logistics stopped proof is not the actual read-only probe")
        c.check(_absolute(snapshot.get("path", "")) == runtime / "plugins/StarWorld/starworld.db", "Logistics probe observed another database")
        snapshots.append(snapshot["rows"])
    c.check([run["steps"][index]["observed"].get("configuration") for index in (3, 7, 10)] == changes,
            "Configuration changes are not bound to their actual clean-stop stages")
    ids = e.get("ids", {})
    expected_ids = {"shortRoute", "longRoute", "paidLong", "paidShort", "listingLong", "listingShort", "shipmentLong", "shipmentShort",
                    "keptBarter", "reroutedBarter", "offeredLong", "shortBarter", "rerouteId"}
    c.check(set(ids) == expected_ids and all(_UUID.fullmatch(str(value)) for value in ids.values()), "Logistics real receipt/order IDs are incomplete")
    baseline = run["steps"][0].get("observed", {}).get("roadBaseline", {})
    baseline_nodes = baseline.get("nodes", [])
    baseline_cities = baseline.get("cities", [])
    c.check(len(baseline_nodes) == 4 and {node.get("id") for node in baseline_nodes} == set(run["steps"][0].get("observed", {}).get("short", {}).get("nodes", [])) |
            set(run["steps"][0].get("observed", {}).get("long", {}).get("nodes", [])), "Road-maintenance baseline does not contain the four real registered nodes")
    c.check(len(baseline_cities) == 2 and all(type(city.get("planks")) is int and city["planks"] >= 32 for city in baseline_cities),
            "Road-maintenance baseline lacks the two actual post-construction warehouse balances")
    baseline_period = baseline.get("period")
    c.check(type(baseline_period) is int and all(node.get("lastMaintenance") == -1 for node in baseline_nodes),
            "Road-maintenance baseline was not captured before any route movement")
    node_city = {node["id"]: node["city"] for node in baseline_nodes}
    maintenance = e.get("maintenanceObservations")
    c.check(isinstance(maintenance, list) and bool(maintenance), "Missing observed public-API node maintenance periods")
    maintenance_keys = set()
    maintenance_rows = {}
    run_started = c.timestamp(e.get("startedAt"), "logistics.startedAt")
    run_finished = c.timestamp(e.get("finishedAt"), "logistics.finishedAt")
    for sample in maintenance:
        node, city, period = sample.get("node"), sample.get("city"), sample.get("period")
        c.check(node in node_city and node_city[node] == city and type(period) is int and period > baseline_period
                and _UUID.fullmatch(str(sample.get("route", ""))), "Maintenance observation is not a real registered node-period-route tuple")
        sampled_at = c.timestamp(sample.get("observedAt"), "maintenance observation")
        c.check(run_started <= sampled_at <= run_finished, "Maintenance observation escaped the real runner timespan")
        key = (node, period)
        c.check(key not in maintenance_keys, "Duplicate maintenance charge observation")
        maintenance_keys.add(key)
        maintenance_rows[key] = sample
    first, restricted, waypoints, final = snapshots
    first_watermarks = {row["id"]: row["last_maintenance"] for row in first.get("logistics_node", [])}
    restricted_watermarks = {row["id"]: row["last_maintenance"] for row in restricted.get("logistics_node", [])}
    for checkpoint, label, stage_index in zip(snapshots, ("default", "travel-reduced", "waypoints-reduced", "restored"), (3, 7, 10, 13)):
        cutoff = c.timestamp(run["steps"][stage_index]["finishedAt"], "logistics." + label + ".finishedAt")
        paid = {key for key, sample in maintenance_rows.items() if c.timestamp(sample["observedAt"], "maintenance observation") <= cutoff}
        for city in baseline_cities:
            expected = city["planks"] - sum(1 for node, period in paid if node_city[node] == city["id"])
            actual = sum(row["amount"] for row in checkpoint.get("stock", []) if row.get("city") == city["id"] and row.get("item") == "minecraft:oak_planks")
            c.check(actual == expected, "Stopped oak planks do not equal recorded real per-node paid maintenance: " + label + "/" + city["id"])
        for node in baseline_nodes:
            samples = [period for paid_node, period in paid if paid_node == node["id"]]
            saved = next((row for row in checkpoint.get("logistics_node", []) if row.get("id") == node["id"]), None)
            c.check(saved is not None and saved.get("last_maintenance") == (max(samples) if samples else -1),
                    "Persisted node watermark differs from observed paid maintenance: " + label + "/" + node["id"])
        c.check(all((row["node"], row["period"]) in paid for row in checkpoint.get("logistics_node_usage", [])),
                "Persisted cargo usage lacks its observed paid node-period: " + label)
    short_nodes = set(run["steps"][0]["observed"]["short"]["nodes"])
    long_only = set(run["steps"][0]["observed"]["long"]["nodes"]) - short_nodes
    c.check(len(long_only) == 2 and all(restricted_watermarks.get(node) == first_watermarks.get(node) for node in long_only),
            "Travel-restricted long-only nodes were maintained")
    orders = [_index(rows["logistics_order"], "id", c.check) for rows in snapshots]
    target_ids = [ids[name] for name in ("shipmentLong", "keptBarter", "reroutedBarter", "shortBarter", "shipmentShort")]
    c.check(len(set(target_ids)) == 5, "Logistics completion IDs are duplicated")
    receipts = final["logistics_receipt"]
    c.check(len(receipts) == 5 and {row["order_id"] for row in receipts} == set(target_ids)
            and all(orders[3].get(key, {}).get("state") == "COMPLETED" for key in target_ids),
            "Final logistics snapshot does not conserve five unique completions")
    c.check(len(final["logistics_paid_shipment"]) == len(final["market_purchase"]) == len(final["market_history"]) == 2
            and all(row.get("state") == "SHIPPED" for row in final["market_purchase"]), "Paid shipments were duplicated/refunded as barter")
    c.check(orders[1].get(ids["offeredLong"], {}).get("state") == "CANCELLED", "Historical unpaid offer could not actually cancel")
    frozen_fields = ("id", "route", "origin_city", "target_city", "origin_nation", "target_nation", "created_period", "expires_period", "progress", "travel", "completed_period")
    for index, frozen_ids in ((1, [ids["shipmentLong"], ids["keptBarter"], ids["reroutedBarter"]]), (2, [ids["shipmentLong"], ids["keptBarter"]])):
        for key in frozen_ids:
            c.check(all(orders[index].get(key, {}).get(field) == orders[0].get(key, {}).get(field) for field in frozen_fields),
                    "Restricted accepted journey changed or refunded")
            c.check(orders[index][key].get("state") == "PAUSED" and not any(row["order_id"] == key for row in snapshots[index]["logistics_receipt"]),
                    "Restricted journey progressed or gained a completion receipt")
    reroutes = [row for row in final["logistics_reroute"] if row["id"] == ids["rerouteId"]]
    c.check(len(reroutes) == 1 and reroutes[0].get("state") == "APPLIED", "Bilateral reroute did not apply exactly once")
    return c.record(run, c.plugin_hash, _sha(prep_data))


def collect_lifecycle(root, version, plugin_hash, source_dir, candidate_dir, logistics_dir,
                      add, regular_file, read_json, require, stage_names,
                      validate_resource_log=None, shared_helper_hash=None) -> list[dict]:
    """Validate the three independent lifecycle results and add only safe proof files.

    Directories are completed run directories (the directories containing evidence.json),
    not server roots. No JVM/process/network/SQLite API is called. Returns runtime_runs
    records for life-source, life-carry and lifecycle-logistics in that order.
    """
    c = _Collector(root, version, plugin_hash, add, regular_file, read_json, require, stage_names,
                   validate_resource_log, shared_helper_hash)
    c.check(_SHA.fullmatch(str(plugin_hash)) and re.fullmatch(r"1\.(?:1|[2-9]\d*)\.\d+(?:-[A-Za-z0-9.-]+)?", version),
            "Lifecycle collection requires a bound1.1-or-later product")
    c.check(all(value is not None for value in (source_dir, candidate_dir, logistics_dir)), "All three lifecycle evidence directories are required")
    records = _collect_life(c, source_dir, candidate_dir)
    records.append(_collect_logistics(c, logistics_dir))
    return records

