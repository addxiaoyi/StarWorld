# Real StarCore integration fixture

This is a test-only helper, never part of the shipped StarWorld plugin JAR. It requires an isolated Paper 1.21.11 world, the **real latest-built StarCore**, StarWorld, and a real connected player (client or protocol bot). It does not emulate StarCore services or access either plugin's private fields or database tables.

Build separately from the repository root using JDK 21: `./mvnw -B -f tools/paper-fixture/pom.xml package` (Windows: `.\mvnw.cmd -B -f tools/paper-fixture/pom.xml package`). Install only `tools/paper-fixture/target/starworld-paper-fixture-0.1.0-test.jar` into the disposable test server. The main project's build never includes this helper.

The independent border acceptance adds console-only `exp-border-setup <player> <slot>`, `exp-border-register <player> <slot>`, `exp-border-state <player>` and `exp-border-claim|exp-border-unclaim <peer-player> <resource-city-player>`. It creates only two farm/sawmill anchors per city through actual raycasts and ordinary player chunk loading. Neighbor territory is changed through public `NationService`, with no neighbor chunk access/load or SQL. Read proofs call the same public `ExpansionRuntime.captureBorderEvidence` used by production and read history/atlas on StarWorld's storage worker; they never construct witnesses or trigger events. See the [client instructions](../paper-client/README.md) for the explicit isolated event tuning and eleven-stage two-restart procedure. Existing expansion/progression fixture behavior is preserved.

Before starting the disposable test server, copy StarCore's own config.yml and set `nation.economy.nation-create-cost` / `nation.economy.claim-cost` to zero, or fund the test player via the core's public economy commands. These key paths were verified against the audited config; never change production economics to run a test. Set StarWorld's cycle to 10 seconds and event-chance to zero in a **fresh disposable database** for deterministic baseline assertions, then trigger individual events explicitly. Give the test player only the permissions being tested.

Console commands:

```text
swfixture setup TestPlayer
swfixture register TestPlayer
swfixture verify TestPlayer
swfixture unclaim TestPlayer
swfixture verify TestPlayer
swfixture reclaim TestPlayer
swfixture seed TestPlayer
swfixture inventory TestPlayer
```

setup calls the actual public NationService.createNation / CityService.createCity / NationService.claimCurrentChunk / CityService.addClaim, places bounded test platforms near `(8,80,8)`, and force-loads three test chunks. register uses the connected player's real `performCommand` and raycast path to register farm, sawmill and workshop. It waits three ticks after each teleport and three ticks after each command before moving to the next target, logging positioned/dispatched steps; do not run a second series concurrently. verify reads StarWorld's registered Java API asynchronously and logs the persisted snapshot. unclaim removes the workshop's real national territory; production must stop until reclaim, independently of the still-existing city claim.

verify additionally emits a single `SW_FIXTURE_JSON` record with action/player/cityId/lastPeriod/stock/buildingCount/event/lastReport for machine assertions. seed gives exactly 16 ordinary wheat and 7 named wheat (no production supplies); use only after observing the unseeded production chain. inventory emits the real saved player's storage slot/type/amount/named flags as JSON. Named wheat must never be consumed by a vanilla warehouse deposit.

The fixture has no assertion that a command dispatch implies successful production. Acceptance must inspect successful registration messages, resource stock over multiple periods, active event behavior, lost territory, restart restoration, and player inventory/GUI interactions. Do not label this test passed until those observed outputs exist. Console-only access avoids creating a privileged player backdoor. Remove the fixture JAR and discard its test world after testing.

The finite-survey acceptance additionally uses `survey-seed Player`, `survey-position Player ChunkX`, and `survey-snapshot Player`. The seed gives ordinary paper to the real connected player, which must enter the city warehouse through `/sw deposit`. Position teleports only among the three already loaded fixture chunks. Snapshot reads `StarWorldApi.geography` and `regions` and emits immutable values for assertions; it does not inspect SQLite or load chunks.

The storage backpressure probe uses `swfixture storage-saturation Player Requests` with 257 to 1024 requests. It submits at most 384 requests at a time, waits for every request in the batch, and releases that batch before continuing. Each request and the final recovery query has a 30-second timeout. While the probe runs, the fixture samples used heap once per tick and records the sample count, peak heap use, JVM heap maximum, accepted/rejected counts, recovery, elapsed time, and queue/batch limits. A timeout is counted as a rejection, and the complete requested count is capped to bound helper memory. The Paper acceptance asserts the sampled heap stays within the server's 512 MiB maximum.

The independent expansion suite uses `exp-setup Player 0|1`, `exp-register Player 0|1`, `exp-node Player 0|1`, `exp-seed Player`, `exp-snapshot Player`, `exp-unclaim|exp-reclaim Player 0|1`, `exp-war|exp-peace Player Peer`, `exp-receipt|exp-replay Player TransactionId`, `exp-armies Player Peer 0 1`, and `exp-combat Player Peer`. These operate on ten explicitly force-loaded test chunks 20..24 and 52..56, set plains only at the five anchor locations, and preserve the original phase-one commands. This loading belongs to the test fixture; StarWorld does not load those chunks. `exp-seed` explicitly gives inventory supplies; real player deposit commands remain necessary. `exp-armies` explicitly seeds 10000 treasury units per nation via the public treasury service, then pays for a persistent ten-soldier infantry unit through ArmyService. `exp-combat` executes the real public combat extension registry with those actual army IDs. Expansion snapshots include the public `StarWorldApi.nationDevelopment` result and loaded definition counts, use only public runtime/services and their serialized asynchronous worker, and never read private fields or database tables. See [evidence-schema.md](../../docs/evidence-schema.md) for exact evidence boundaries.

After all restart assertions, `exp-delete-city Player` invokes the real public `CityService.deleteCity(UUID)` and checks removal of both city and membership. `exp-history Player CityUUID` then observes the public historical city and national development APIs without relying on current player-city membership. The client waits for the normal bounded settlement tick to exclude the deleted city from national development, verifies its national bonuses stop, and checks its local historical inventory/development remain accessible and stop producing. No fixture method directly invalidates StarWorld authority state.


## 0.3 progression acceptance

`../paper-client/progression-acceptance.cjs` is an independent twenty-stage real two-client suite. It uses the unchanged `exp-setup/register/node` paths and new console-only `exp-progress`, `exp-progress-seed`, `exp-alt-node`, `exp-fund`, `exp-policy`, `exp-reroute-proof`, `exp-market-receipt/replay` actions. The old fifteen/twenty-stage suites remain separately executable.

Use a fresh sentinel-owned localhost runtime, final StarWorld 0.3 JAR, audited StarCore, this rebuilt fixture, ten-second periods, random events disabled, culture decay interval **2** with the default **500** basis points, zero new-nation protection and affordable/zero test claims/war fees. The policy retains its actual 100-coin public activation cost and default 1.25 cultural factor. Market unit prices and treasury payments retain their real rules. Only this disposable runtime receives shortened cultural aging; the product default remains twelve periods.

The fixture explicitly seeds 256 bread, 512 planks, 128 iron, 64 logs, 128 paper, 256 arrows and 64 wheat into each real player's inventory; warehouse credits require actual player deposit commands. Public TreasuryService deposits of 1000 coins per nation are separate test seeds, not earned income. An academy is upgraded by the real command. No cultural/knowledge/market/transport score or ledger is injected.

The suite checks public region API/GUI protection, maintained-policy culture, actual trade-receipt foreign culture and natural aging, three real road nodes/two declared routes, bilateral reroute consent and full journey reset, congestion observations, a real priced listing, founder-authorized NationTransferService receipt, one-way paid transport, territory/war pause, exact cargo/money conservation, stable receipt replay and restart. Furniture/CE remains covered by its separate suite. The new snapshots use public immutable Futures and bounded repositories on the storage worker, while treasury/policy/world access stays on the Paper main thread. All logs and exact binaries/configuration hashes are retained under `runtime/progression-acceptance/<UTC>/`.

## 0.4 operations acceptance

`exp-operations Player TargetCityUUID` observes the public asynchronous `StarWorldApi.routePlans` and `marketDeliveryStats` projections. It returns actual planning costs, city/global completed-delivery aggregates and entry-point names; it never invokes settlement or reconciliation. `exp-permissions Player` only reads actual `starworld.admin/use` permissions. The new `../paper-client/operations-acceptance.cjs` retains all original twenty progression stages and adds nine operations stages, three owned Paper starts and six client transcripts. Online backup is invoked by the actual temporarily-opped client, then independently inspected read-only by Python; no fixture writes gameplay SQL or bypasses the command's permission check. Existing progression and expansion actions are unchanged.
