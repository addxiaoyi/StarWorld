# Public works (1.0)

This independent module adds one permanent public works slot per StarCore city and owning nation. It only writes StarWorld extension tables in the existing `WorldStore`; it never writes StarCore tables or scans chunks, blocks, or entities. The runtime supplies freshly verified facilities and current `CoreCity` authority.

## Default projects

| ID | Effective construction periods | Required facilities | Eligible terrain | City plans | National civilizations | Knowledge / culture | Effects while funded |
|---|---:|---|---|---|---|---:|---|
| `irrigation_network` | 3 | farm, well, warehouse | plains, river | balanced, agriculture | agrarian, mercantile, scholarly | 2 / 0 | farm ×1.15; workshop ×0.95 |
| `arsenal_district` | 5 | workshop, barracks, warehouse | mountains, forest, plains | industry, military | industrial, martial | 8 / 0 | workshop ×1.15; farm ×0.95 |
| `cultural_quarter` | 4 | academy, kitchen, market | plains, river, forest | culture, commerce | scholarly, mercantile, agrarian | 5 / 5 | kitchen ×1.15; farm ×1.05; workshop ×0.95 |

Every required facility type must have at least one currently verified, original-nation building on eligible terrain. Construction additionally requires those facilities to appear in the exact settlement period's actual `maintained` set. A plains farm does not validate a required well on an excluded mountain region. Effects are city wide for the captured, verified facilities of that project owner. Each definition explicitly includes the production penalty that accompanies its benefit.

`public-works.yml` contains separate, positive physical costs for allocation, each construction step, and each operating period. Costs accept vanilla or CraftEngine namespaced item IDs, including literal dots in namespaces or paths and slashes in resource paths, such as `server.food:grains/wheat.flour`. The YAML loader uses a separate internal path delimiter and reads cost map entries as literal keys. The frozen database codec preserves those same IDs. `itemIds()` exposes every required item to the runtime's existing item-dependency registration. No material credit or one-time reward is issued on completion.

## Java contract

```java
PublicWorksDefinitions.writeDefaults(file); // create only; never overwrite an administrator file
PublicWorksDefinitions configuration = PublicWorksDefinitions.load(file, definitions, evolutionOptions);
PublicWorksService works = new PublicWorksService(store, configuration);

works.configuration();
works.project(requestId);                    // Optional<Project>
works.project(cityId, nationId);             // Optional<Project>, permanent national slot
works.projects(cityId, limit, offset);        // immutable newest-first List<Project>
works.history(cityId, limit, offset);         // immutable newest-first List<History>
works.inspect(coreCity, cityView, period, verified); // immutable List<Feasibility>; NO writes
works.start(requestId, coreCity, definitionId, period, verified, actor);
```

`start` is a trusted internal write boundary. Its caller checks the player's management permission on Paper's main thread, captures current StarCore identity and real facility anchors, and rechecks permission after asynchronous reads. The repository independently rejects inactive or transferred city authority, obsolete periods, missing prerequisites, insufficient stock, and an already allocated city/nation slot. A repeated request UUID with the same city, nation, and definition ID returns its original immutable receipt after current authority validation. It does not pay again or require the old plan, event, period, or configuration to remain current. A conflicting payload is rejected. A newly conquering nation may allocate its own slot after establishing its own verified facilities; previous-owner projects remain frozen and queryable. Returning to a previous nation reuses that nation's already allocated slot.

Each `Project` includes its complete frozen `ProjectDefinition`: name, duration, all three cost maps, facilities, terrain, allowed plans and civilizations, knowledge and culture thresholds, and production multipliers. It also records the actual initial plan, civilization, knowledge and culture for audit. Reloading or removing a definition cannot rewrite a paid project's terms. Current plan, national civilization, education, culture, terrain, facility identity, and ownership are nevertheless checked against those frozen requirements whenever construction or operation is attempted.

## Exact settlement ordering

`cycle` consumes stock and is NOT a read API. Enclose it and the entire simulation in the trusted internal `atomicSettlement` wrapper, before `Simulation.settle` obtains its own starting stock. The wrapper joins or starts the existing `WorldStore` transaction and places an independent SQLite savepoint around the complete callback. Keep the runtime's existing duplicate-period guard before constructing a cycle. Do not expose this write wrapper through a public read API.

```java
return works.atomicSettlement(s -> {
    // Reconcile current CoreCity authority using the existing runtime boundary first.
    CityView before = s.view(core.cityId()).orElseThrow();
    // Existing runtime: return immediately for settled/backwards periods before any hooks.
    PublicWorksService.Cycle cycle = works.cycle(core, before, period, verified);
    // Simulation MUST read stock after cycle(), so its local inventory includes the upkeep debit.
    return simulation.settle(core, period, verified, new Simulation.Hooks() {
        @Override public double multiplier(Building building) throws SQLException {
            return existingHooks.multiplier(building) * cycle.multiplier(building);
        }
        @Override public void completed(CoreCity c, CityView previous, long p,
                Map<String,Long> produced, Set<UUID> maintained) throws SQLException {
            existingHooks.completed(c, previous, p, produced, maintained);
            cycle.completed(maintained); // after normal evolution, before final store.finish
        }
        // Preserve/delegate all other existing hooks, including capacity and event hooks.
    });
});
```

For an already completed, eligible project, `cycle` debits operating cost from the period's starting warehouse and records whether that period was funded. Its immutable `operating()` and `multiplier(Building)` then describe only that captured period. Lack of starting stock pauses the period even if that period's production later supplies the missing material. A second cycle for the same unsettled period reuses its paid/paused watermark and cannot charge twice. Older periods cannot operate again.

For construction, `cycle.completed(maintained)` intersects captured verified IDs with the actual maintained set, reads current extension profile and physical warehouse stock, and either pauses or debits one construction batch and advances one step. The request period itself cannot advance. Gaps of months still advance at most one step per actual settlement. The final step records completion with no item reward. The captured cycle remains inactive; operation is possible only from the next real period onward. A failure in any later hook or final settlement write rolls back the period's operation fee, production, evolution changes, construction fee, progress, watermarks, and history together.

Each local write (`start`, `cycle`, `completed`, schema initialization) also uses its own savepoint. If a surrounding caller handles a local failure and commits unrelated work, that failed call still leaves no partial fee, slot, progress, watermark, or history changes. The full `atomicSettlement` scope additionally protects a successfully returned cycle's earlier operating fee when a later simulation hook fails and a still wider caller handles that exception. Rollback failures are suppressed on the original failure; original exceptions and errors are rethrown. No global `WorldStore.tx` behavior is changed.

`Cycle.project()` returns the immutable captured project receipt; query the service after settlement for updated construction state. `Cycle.reason()` explains that capture's running or paused state. `Completion` reports inspection, one-step advancement, new completion, progress, and a reason. `inspect` provides bounded feasibility and missing-upfront-stock maps without allocating anything, initializing profile state, taking fees, or advancing watermarks.

## Persistence, bounds, and upgrade behavior

The independent `public_works_schema` version is 1; newer versions fail closed. Definitions have a separate `schema-version: 1`; unsupported future versions fail before use. Schema creation is additive and transactional. Existing administrator configuration is preserved. New code must migrate both schema and frozen snapshots explicitly if its storage format changes.

`public_works` has a UUID primary key and `UNIQUE(city,nation)`. Permanent single-row watermarks (`last_operation_period`, `last_operation_paid`, `last_construction_attempt_period`, `last_build_period`, progress, completion period) provide durable deduplication without creating an unbounded per-period receipt table. They are never removed during history cleanup. `public_works_history` retains the newest `limits.history-limit` records per city, default 200, allowed 10–2,000. Pruning happens in the same transaction as each new history entry; deleting display history cannot re-enable any business action. The number of archived project rows grows only when a distinct nation actually pays to allocate that city's slot. Ordinary yearly operation uses constant project-row space.

Queries permit `limit` 1 through `limits.max-page` (default 64, maximum 128) and offset 0 through 1,000,000. Captured facility evidence is capped at 64 by default, configurable up to 256; no world access is performed. Overlarge settlement evidence pauses this extension rather than requiring a world scan. Configuration allows at most 16 project definitions, 16 items in each cost map, 8 required facilities, 16 terrain/plan/civilization IDs, 3–5 effective construction periods, 1–1,000,000 units for each cost, and multipliers 0.5–1.25. Unknown references, duplicate or mixed-type lists, fractional integer values, zero/negative costs, and nonfinite multipliers are rejected. Public collections are immutable defensive copies.

## Tests and integration status

The source contains service tests for restart recovery, request conflicts, one national slot, immutable queries, same-period deduplication, offline gaps, maintained facilities, starting-stock operation costs, completion timing, material conservation, authority loss, conquest, terrain/plan/civilization/education/culture gates, configuration replacement, bounded history, future schema rejection, and cross-module rollback. Nested-failure tests verify that start, operation, construction, and a complete simulated period roll back even when an outer caller handles the failure and successfully commits unrelated audit entries. Configuration and restart tests cover literal dotted/slashed CraftEngine IDs in all three cost maps. Other configuration tests cover the three defaults, administrator-file preservation, strict bounds, unsupported references and types, and immutable collections.

The plugin registers this module through `PublicWorksController` and `ExpansionRuntime`. Players use `/sw works` or `/sw governance`; every start rechecks current StarCore ownership and city management permission. The runtime captures valid anchors on Paper's owning thread, then settles on the existing storage worker. Public API calls return immutable asynchronous views. The current build, actual-server evidence and any pending release checks are recorded in [test-results.md](test-results.md).
