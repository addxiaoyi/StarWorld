# Real StarCore integration fixture

This is a test-only helper, never part of the shipped StarWorld plugin JAR. It requires an isolated Paper 1.21.11 world, the **real latest-built StarCore**, StarWorld, and a real connected player (client or protocol bot). It does not emulate StarCore services or access either plugin's private fields or database tables.

Build separately from the repository root using JDK 21: `./mvnw -B -f tools/paper-fixture/pom.xml package` (Windows: `.\mvnw.cmd -B -f tools/paper-fixture/pom.xml package`). Install only `tools/paper-fixture/target/starworld-paper-fixture-0.1.0-test.jar` into the disposable test server. The main project's build never includes this helper.

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

The independent expansion suite uses `exp-setup Player 0|1`, `exp-register Player 0|1`, `exp-node Player 0|1`, `exp-seed Player`, `exp-snapshot Player`, `exp-unclaim|exp-reclaim Player 0|1`, `exp-war|exp-peace Player Peer`, `exp-receipt|exp-replay Player TransactionId`, `exp-armies Player Peer 0 1`, and `exp-combat Player Peer`. These operate on bounded test chunks 20..24 and 52..56, set plains only at the five anchor locations, and preserve the original phase-one commands. `exp-seed` explicitly gives inventory supplies; real player deposit commands remain necessary. `exp-armies` explicitly seeds 10000 treasury units per nation via the public treasury service, then pays for a persistent ten-soldier infantry unit through ArmyService. `exp-combat` executes the real public combat extension registry with those actual army IDs. Expansion snapshots use only public runtime/services and their serialized asynchronous worker; they do not read private fields or database tables. See [evidence-schema.md](../../docs/evidence-schema.md) for exact evidence boundaries.
