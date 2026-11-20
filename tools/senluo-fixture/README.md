# Optional SenLuo integration acceptance fixture

Disposable Paper 1.21.11 / Java 21 console fixture and Mineflayer 4.39 protocol suite. Never install the fixture on a production server. Build the root StarWorld JAR first, then run `mvn -f tools/senluo-fixture/pom.xml package`; the system-scoped root JAR is only a local fixture compilation input and is not bundled.

Prepare a fresh localhost runtime with `.starworld-smoke-runtime` containing `StarWorld isolated Paper smoke runtime v1`, separately accepted `eula.txt`, `server-ip=127.0.0.1`, offline mode, a free port, flat world and both view distances at 2. Install real StarCore, StarWorld, this fixture, and `tools/paper-fixture` (for public country/city setup). Install CraftEngine and the optional Cookery/Tavern/Deco/Music JARs plus their matching content packs. Keep third-party JARs and resource assets outside this repository.

Tune only the disposable configuration: StarWorld production period 10 seconds, random-event chance 0, life profession cooldown 1 period. Keep the default sixty-second concert, note timing, notes, pitches, real same-city audience and warehouse costs. Set StarCore test nation/claim creation costs to zero or fund the player. Do not copy another world or either plugin's database. For CE 26.9.1 the upstream tested initialization uses `serverside-blocks: 8000`, `delay-configuration-load: false`, `cache-resource-files: false`; this suite disables resource-pack sending because it does not verify visual/audio rendering.

```powershell
node tools/senluo-fixture/acceptance.cjs --java C:/tools/jdk21/bin/java.exe --paper C:/tools/paper-1.21.11-132.jar --runtime C:/tools/owned-runtime --client-deps C:/tools/mc-client
```

The script starts and cleanly stops only its own server child. It requires two real offline protocol clients. Fixture-seeded items are explicitly labeled; all tested warehouse credits require real `/sw deposit` transfers. Feast rewards require exact costs. Furniture is actually placed through the public CE API, selected by the player command, maintained while valid and removed through CE. Public StarCore territory cancellation pauses furniture upkeep and contribution; reclaiming resumes it. The concert uses a real CE instrument, same-city listener and timed StarWorld note/sound actions; it does not claim upstream `/kmusic` completion integration. Restart and replay checks verify no duplicate warehouse credits or culture rewards.

Every stage, exact plugin SHA-256, source baseline, tuning and complete server/client transcripts are preserved under `<runtime>/senluo-acceptance/<timestamp>/`. A partial or failed run stays failed. See [acceptance scope](../../docs/senluo-acceptance.md).

The current 0.3.0 suite requires `PREPARATION.json` in the disposable runtime with `upstreamAuditCommit` equal to `c3642ae22fc5252bf6da22ae745cd39be994b054`. It records the complete preparation and its SHA-256 alongside actual installed plugin hashes. Use the matching latest Cookery JAR and `kitchen-crops-2.zip` configuration/resources; retain previous assets outside CraftEngine's loaded resource directory. This source baseline adds crops but does not change StarWorld's delivered-food, furniture or instrument contract. Upstream crop gameplay acceptance remains separate.

The optional suite records its constrained JVM arguments (768 MiB heap, C1, SerialGC, one logical CPU, bounded stacks/metaspace/code cache). Disable Spark in the disposable Paper global config to avoid unrelated sampling overhead. This tuning is for the isolated integration scenario; use actual load measurements for production sizing.
