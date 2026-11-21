# 1.1.0 evidence status

The frozen 1.1.0 artifact is `f8b6988df9fe273448997384b72596636502889ad9af72d8825e751557b3806b`.

| Scope | Observed evidence | Status |
| --- | --- | --- |
| Automated verification | [unit-tests.json](unit-tests.json), artifact SHA-256 f8b6988df9fe273448997384b72596636502889ad9af72d8825e751557b3806b | 444 tests; 0 failures, 0 errors, 0 skipped |
| Scale run | [scale.json](scale.json), bound to the same artifact | 1,000 cities, 2,000 facilities, 3,000 settlements and 1,000 restart replays; SQLite/pure production-path scope |
| Life source and carry | Real Paper runs, 4 + 7 stages | Passed; legacy Cookery 25 carries as 20 then 5, with new Community 12 for a net 17 |
| Lifecycle logistics | Fresh isolated run `lifecycle11-reg09`, 14 stages and 4 clean starts | Passed; limits, escrow, maintenance watermarks, reroute and five unique receipts verified |
| 1.0 to 1.1 upgrade | Fresh 0.5 save `upgrade11-server05`, 7 stages and 2 clean starts | Passed; schema2 migration, additive ledgers/cursors, old receipt retry and restart recovery verified |

The lifecycle collector validates the three new runtime records using read-only stopped probes, current runner hashes, exact configuration bytes, timestamps, client/server logs and the frozen JAR. Historical 1.0 evidence remains in `docs/evidence/history/1.0.0`; it is not substituted for new 1.1 behavior. A formal release ZIP still needs all legacy 1.0 gameplay suites re-bound to the same 1.1 artifact before packaging.
