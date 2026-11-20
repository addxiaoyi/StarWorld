#!/usr/bin/env node
'use strict';

// Read-only, stopped SQLite probes and two real gameplay sessions on a copy of the accepted 0.5 save.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const { createRequire } = require('node:module');
const { Paper, connect, botMessage, properties, configuredNumber, hash, requireFile, waitUntil, withTimeout } = require('./acceptance.cjs');

const HELP = `Usage: node upgrade-10-acceptance.cjs --java <Java21> --paper <Paper132.jar>
  --runtime <prepared-upgrade-copy> --python <Python3> [--client-deps <Mineflayer>] [--check-only]

Requires a stopped, already accepted 0.5 disaster save and a separately prepared copy
with UPGRADE-PROVENANCE.json. Verifies two normal 1.0 starts, immutable receipts,
actual production and original player inventory. The script uses read-only SQL,
does not seed inventory, and never starts or changes the source runtime.
`;
let VERSION = '1.0.0';
const PLAYER = 'StarWorldDA';
const ADDED = ['public_works_schema', 'public_works', 'public_works_history', 'civic_schema', 'civic_clock', 'civic_request', 'civic_request_cost', 'civic_action_claim', 'civic_history', 'life_evolution_pending', 'life_evolution_budget', 'starworld_dispatch_cursor'];
const V11_TABLES = ['life_evolution_pending', 'life_evolution_budget', 'starworld_dispatch_cursor'];
const DISPATCH_QUEUES = ['starworld:market-payment', 'starworld:resource-export'];
const CONFIGS = ['config.yml', 'definitions.yml', 'disaster-response.yml', 'evolution.yml', 'exports.yml', 'life.yml', 'logistics.yml', 'market.yml'];
const NEW_CONFIGS = ['civic-needs.yml', 'public-works.yml'];
const STABLE = ['building', 'transfer', 'nation_state', 'city_authority', 'simulation_clock', 'resource_outbox', 'logistics_order', 'logistics_receipt', 'life_action', 'life_profile', 'life_concert', 'life_note', 'life_audience', 'life_evolution_action', 'life_decoration', 'market_delivery_schema', 'market_delivery_clock', 'market_delivery_receipt', 'market_delivery_stat', 'disaster_response_schema', 'market_schema', 'market_listing', 'market_purchase', 'market_history', 'culture_dynamics_schema', 'logistics_reroute', 'logistics_paid_shipment'];

const PROBE = String.raw`
import hashlib,json,sqlite3,sys,zipfile,re
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
root=Path(sys.argv[1]);result={}
selected=sys.argv[2].split(',')
def normalize(value):
 if isinstance(value,bytes):return {'blob_sha256':hashlib.sha256(value).hexdigest(),'bytes':len(value)}
 return value
for label,relative in [('world','plugins/StarWorld/starworld.db'),('core','plugins/StarCore/starcore.db')]:
 db=root/relative
 wal=Path(str(db)+'-wal')
 if wal.exists() and wal.stat().st_size:raise RuntimeError('Nonempty WAL: '+str(wal))
 with sqlite3.connect(db.as_uri()+'?mode=ro&immutable=1',uri=True) as con:
  names=[r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
  probe={'schema':con.execute('PRAGMA user_version').fetchone()[0],'tables':names,'database_sha256':hashlib.sha256(db.read_bytes()).hexdigest(),'rows':{}}
  chosen=selected if label=='world' else ['starcore_nation_state','starcore_treasury_state','starcore_resource_state']
  for table in chosen:
   if table not in names:continue
   assert re.fullmatch('[A-Za-z0-9_]+',table)
   columns=[row[1] for row in con.execute('PRAGMA table_info('+table+')')]
   quoted=','.join('"'+c.replace('"','""')+'"' for c in columns)
   data=[dict(zip(columns,[normalize(v) for v in row])) for row in con.execute('SELECT * FROM '+table+' ORDER BY '+quoted)]
   probe['rows'][table]=data
  probe['integrity']=con.execute('PRAGMA quick_check').fetchone()[0]
  if label=='world':probe['foreignKeys']=list(con.execute('PRAGMA foreign_key_check'))
  result[label]=probe
with zipfile.ZipFile(root/'plugins/StarWorld.jar') as jar:
 descriptor=jar.read('plugin.yml').decode('utf-8');result['pluginVersion']=re.search(r"^version:\s*['\"]?([^'\"\s]+)",descriptor,re.M).group(1)
 result['bundledConfigs']={name:hashlib.sha256(jar.read(name)).hexdigest() for name in ['civic-needs.yml','public-works.yml'] if name in jar.namelist()}
print(json.dumps(result,ensure_ascii=False))
`;

function readJson(file) { return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')); }
function parseArgs(argv) {
  const result = {};
  for (let i = 0; i < argv.length; i++) {
    if (['--help','-h'].includes(argv[i])) { result.help = true; continue; }
    assert(['--java','--paper','--runtime','--python','--client-deps','--check-only'].includes(argv[i]), `Unknown option ${argv[i]}`);
    const key = argv[i].slice(2);
    assert(!result[key], `Duplicate option ${argv[i]}`);
    if (key === 'check-only') { result[key] = true; continue; }
    assert(argv[i+1] && !argv[i+1].startsWith('--'), `Missing value for ${argv[i]}`);
    result[key] = argv[++i];
  }
  return result;
}
function sqliteSnapshot(python, runtime) {
  const chosen = [...STABLE, ...ADDED, 'life_schema', 'life_city', 'city_state', 'stock', 'settlement', 'region', 'reserve', 'disaster_response', 'disaster_response_history', 'disaster_recovery_ledger'];
  const result = spawnSync(python, ['-c', PROBE, runtime, chosen.join(',')], { encoding: 'utf8', windowsHide: true, maxBuffer: 16 * 1024 * 1024 });
  assert.equal(result.status, 0, `Read-only stopped SQLite probe failed: ${result.error || result.stderr}`);
  return JSON.parse(result.stdout);
}
function configFingerprints(runtime, names) {
  return Object.fromEntries(names.map(name => [`plugins/StarWorld/${name}`, hash(path.join(runtime, 'plugins/StarWorld', name))]));
}
function checkFingerprints(runtime, fingerprints) {
  for (const [file, checksum] of Object.entries(fingerprints)) assert.equal(hash(path.join(runtime, file)), checksum, `Fingerprint drift: ${file}`);
}
function subsetRows(expected, actual, keys, table) {
  const index = row => JSON.stringify(keys.map(key => row[key]));
  const byId = new Map(actual.map(row => [index(row), row]));
  assert.equal(byId.size, actual.length, `Duplicate primary key in ${table}`);
  for (const row of expected) assert.deepEqual(byId.get(index(row)), row, `Old ${table} row changed or disappeared: ${index(row)}`);
}
function stockOf(rows, city) { return Object.fromEntries(rows.filter(row => row.city === city).map(row => [row.item, row.amount])); }
function retainedSettlements(previous, next) {
  return previous.settlement.filter(row => {
    const city = next.city_state.find(value => value.id === row.city);
    assert(city, `Missing city for previous settlement ${row.city}`);
    const oldPeriod = previous.city_state.find(value => value.id === row.city).last_period;
    return city.last_period === oldPeriod || row.period >= city.last_period - 1000;
  });
}
function assertInventory(actual, expected) {
  assert.equal(actual.player, PLAYER);
  assert.deepEqual(actual.items, expected, 'Named guide inventory or deposited goods changed across upgrade');
}

function is11(version) { return /^1\.1\./.test(version); }
function expectedAddedTables(version) { return ADDED.filter(table => is11(version) || !V11_TABLES.includes(table)).sort(); }
function assertAddedTables(before, after, version) {
  assert.deepEqual(after.filter(name => !before.includes(name)).sort(), expectedAddedTables(version));
}
function indexBy(rows, key, label) {
  const indexed = new Map(rows.map(row => [row[key], row]));
  assert.equal(indexed.size, rows.length, `Duplicate ${key} in ${label}`);
  return indexed;
}
function assertLifeEvolution(before, after, version) {
  const old = before.world.rows, next = after.world.rows;
  const oldSchema = old.life_schema || [], nextSchema = next.life_schema || [];
  if (!is11(version)) {
    for (const table of V11_TABLES) {
      assert(!after.world.tables.includes(table), `Historical 1.0 must not create ${table}`);
      assert.deepEqual(next[table] || [], [], `1.0 must not initialize 1.1 ${table} state`);
    }
    assert.deepEqual(nextSchema, oldSchema, 'Historical 1.0 life schema must remain unchanged');
    return;
  }

  for (const table of V11_TABLES) assert(after.world.tables.includes(table), `1.1 must create ${table}`);
  assert(before.world.tables.includes('life_schema') && after.world.tables.includes('life_schema'), 'life_schema is the existing migration version table, not an added table');
  assert.deepEqual(oldSchema, [{ id: 1, version: 1 }], 'Expected the accepted 0.5 life schema as migration input');
  assert.deepEqual(nextSchema, [{ id: 1, version: 2 }], '1.1 must advance the life schema without replacing it');

  const cities = indexBy(old.city_state, 'id', 'original cities');
  const actions = indexBy(old.life_action || [], 'id', 'original life actions');
  const oldProgress = indexBy(old.life_evolution_action || [], 'id', 'original life evolution progress');
  const progress = indexBy(next.life_evolution_action || [], 'id', '1.1 life evolution progress');
  const pending = indexBy(next.life_evolution_pending || [], 'id', '1.1 pending life actions');
  const budgets = next.life_evolution_budget || [];
  const cursorRows = next.starworld_dispatch_cursor || [];

  // Existing 0.5/1.0 authority and action receipts survive migration. Existing completed
  // progress is copied forward; newly credited rows may only refer to an old action.
  for (const [id, row] of oldProgress) {
    const migrated = progress.get(id);
    assert(migrated, `Existing life evolution receipt was deleted: ${id}`);
    for (const [key, value] of Object.entries(row)) assert.deepEqual(migrated[key], value, `Existing life evolution receipt changed: ${id}.${key}`);
    const action = actions.get(id);
    assert(action, `Legacy evolution receipt lost its original life action: ${id}`);
    assert.equal(migrated.credited_culture, action.culture, `Completed legacy credit must migrate in full: ${id}`);
  }
  for (const [id, row] of progress) {
    const action = actions.get(id);
    assert(action, `1.1 added progress for a non-original life action: ${id}`);
    assert(Number.isSafeInteger(row.credited_culture) && row.credited_culture > 0 && row.credited_culture <= action.culture, `Invalid credited culture: ${id}`);
    assert(Number.isSafeInteger(row.consumed_period) && row.consumed_period >= action.period, `Invalid consumed period: ${id}`);
  }
  for (const [id, row] of pending) {
    const action = actions.get(id), city = action && cities.get(action.city), credited = progress.get(id)?.credited_culture || 0;
    assert(action && city, `Pending culture is not backed by an original action and city: ${id}`);
    assert.equal(row.city, action.city, `Pending city changed: ${id}`);
    assert.equal(row.nation, action.nation, `Pending nation changed: ${id}`);
    assert.equal(row.period, action.period, `Pending source period changed: ${id}`);
    assert.equal(action.nation, city.nation, `Pending action belongs to a non-authoritative nation: ${id}`);
    assert.equal(action.eligible, 1, `Ineligible life action remains pending: ${id}`);
    assert(credited < action.culture, `Fully credited life action remains pending: ${id}`);
  }
  for (const [id, action] of actions) {
    const credited = progress.get(id)?.credited_culture || 0;
    const currentCity = cities.get(action.city);
    const stillAuthoritative = currentCity && currentCity.nation === action.nation;
    const shouldBePending = action.eligible === 1 && credited < action.culture && stillAuthoritative;
    assert.equal(pending.has(id), shouldBePending, `Original pending action was lost or resurrected: ${id}`);
  }

  const budgetKeys = new Set();
  for (const row of budgets) {
    const key = `${row.city}/${row.nation}`;
    assert(!budgetKeys.has(key), `Duplicate life evolution budget: ${key}`); budgetKeys.add(key);
    assert(cities.has(row.city), `Budget references a non-original city: ${row.city}`);
    const eligibleActions = [...actions.values()].filter(action => action.city === row.city && action.nation === row.nation);
    assert(eligibleActions.length > 0, `Budget has no original city/nation action namespace: ${key}`);
    assert(Number.isSafeInteger(row.period) && row.period >= 0 && row.period <= cities.get(row.city).last_period && Number.isSafeInteger(row.culture) && row.culture > 0 && row.culture <= 1_000_000_000, `Invalid saved budget: ${key}`);
    const periodProgress = [...progress.values()].some(value => {
      const action = actions.get(value.id);
      return action && action.city === row.city && action.nation === row.nation && value.consumed_period === row.period;
    });
    assert(periodProgress, `Budget is not backed by credited actions in its city/nation/period namespace: ${key}`);
    assert(row.culture <= eligibleActions.reduce((sum, action) => sum + action.culture, 0), `Budget exceeds original culture contributions: ${key}`);
  }

  const cursor = indexBy(cursorRows, 'queue', 'dispatch cursor namespaces');
  assert.deepEqual([...cursor.keys()].sort(), [...DISPATCH_QUEUES].sort(), '1.1 may initialize only the market and resource queue namespaces');
  for (const [queue, row] of cursor) assert(['FRESH', 'DUE'].includes(row.next_lane), `Invalid dispatch cursor lane: ${queue}`);
}

async function fixture(server, player, action, prefix) {
  const from = server.lines.length;
  server.command(`swfixture ${action} ${player}`);
  const raw = await server.wait(new RegExp(`${prefix} .*"action":"${action}"`), from);
  const value = JSON.parse(raw.slice(raw.indexOf(`${prefix} `) + prefix.length + 1));
  assert.equal(value.player, player);
  return value;
}
async function disasterState(server) {
  return waitUntil(async () => {
    const { observed: state } = await fixture(server, PLAYER, 'exp-disasters', 'SW_FIXTURE_EXPANSION');
    assert.equal(state.version, VERSION);
    assert.equal(state.gameplayWrites, false);
    if (state.atlas.cityLastPeriod !== state.city.lastPeriod || state.development.lastPeriod !== state.city.lastPeriod) return false;
    return state;
  }, 20000, 'coherent StarWorld API snapshot', () => server.check());
}
async function nextSettlement(server, period) {
  return waitUntil(async () => {
    const state = await disasterState(server);
    return state.city.lastPeriod > period && state.currentPeriod === state.city.lastPeriod ? state : false;
  }, 35000, 'actual next city settlement', () => server.check());
}
async function replayPaid(server, bot, paid, original) {
  const initial = await disasterState(server);
  // On an offline copy, the wall-clock period may be far ahead of the last
  // committed city period. Establish a real online settlement before retrying.
  const before = await nextSettlement(server, initial.city.lastPeriod);
  assert.equal(before.city.id, original.id);
  const found = before.responses.find(row => row.requestId === paid.request_id);
  assert(found && found.steps === paid.steps && found.remainingBudget === paid.remaining_budget);
  const reply = await botMessage(bot, `/sw disaster respond ${paid.strategy} ${paid.request_id}`, new RegExp(`救灾回执 ${paid.request_id}`));
  const after = await disasterState(server);
  assert(after.currentPeriod >= before.currentPeriod && after.currentPeriod <= before.currentPeriod + 1,
    'Historical retry observation crossed more than one real clock boundary');
  // The request can race the simulation's next durable settlement. The immutable
  // disaster response/history and its single requested row are the retry proof;
  // production/upkeep changes are checked separately by inspectProduction().
  assert.deepEqual(after.responses, before.responses);
  assert.deepEqual(after.history, before.history);
  assert.equal(after.history.filter(row => row.requestId === paid.request_id && row.kind === 'requested').length, 1);
  return { before, reply, after };
}
async function governanceWindow(bot) {
  let handler;
  const pending = new Promise(resolve => { handler = resolve; bot.on('windowOpen', handler); });
  try {
    bot.chat('/sw governance');
    const window = await withTimeout(pending, 15000, 'real governance GUI');
    const title = typeof window.title === 'string' ? window.title : JSON.stringify(window.title);
    assert.match(title, /城市治理总览/u);
    for (const [slot, item] of [[10, 'bricks'], [12, 'writable_book'], [14, 'jukebox'], [16, 'water_bucket']]) assert.equal(window.slots[slot]?.name, item);
    bot.closeWindow(window);
    return { title, slots: [10, 12, 14, 16] };
  } finally { bot.removeListener('windowOpen', handler); }
}

function production(report) {
  const prefix = report.match(/^产出 \{([^}]*)\}/u);
  assert(prefix, `Missing settlement production totals: ${report}`);
  return Object.fromEntries(prefix[1].split(',').filter(Boolean).map(entry => {
    const row = entry.trim().match(/^([a-z0-9_.-]+:[a-z0-9_./-]+)=(\d+)$/u);
    assert(row, `Invalid reported production entry: ${entry}`);
    return [row[1], Number(row[2])];
  }));
}
function inspectProduction(before, after, startedAt, finishedAt) {
  const clock = before.simulation_clock[0];
  const periodAt = time => clock.period_origin + Math.floor((Math.floor(time / 1000) - clock.epoch_origin) / clock.period_seconds);
  const earliest = periodAt(startedAt), latest = periodAt(finishedAt);
  assert(latest >= earliest, 'System clock moved backwards during acceptance');
  const observed = [];
  for (const city of before.city_state) {
    const final = after.city_state.find(row => row.id === city.id);
    assert(final && final.last_period >= city.last_period);
    assert(final.last_period <= latest, 'Retained settlement watermark is ahead of the actual online clock');
    const receipts = after.settlement.filter(row => row.city === city.id && row.period > city.last_period);
    assert(receipts.length <= latest - earliest + 1, 'Number of settlements exceeds real online periods');
    if (receipts.length) assert.equal(final.last_period, Math.max(...receipts.map(row => row.period)));
    const credits = {};
    for (const receipt of receipts) {
      assert(receipt.period >= earliest && receipt.period <= latest, `Offline settlement detected: ${receipt.period}`);
      for (const [item, amount] of Object.entries(production(receipt.report))) credits[item] = (credits[item] || 0) + amount;
    }
    const oldStock = stockOf(before.stock, city.id), newStock = stockOf(after.stock, city.id);
    // Each original level-one recipe and upkeep can consume at most once per actual receipt.
    // The farm may bake with 6 wheat; the kitchen may use 9 wheat and 1 log.
    // Kitchen and market each need 1 bread; well and warehouse each need 1 plank.
    const maxDebitPerPeriod = { 'minecraft:bread': 2, 'minecraft:wheat': 15, 'minecraft:oak_log': 1, 'minecraft:oak_planks': 2 };
    for (const item of new Set([...Object.keys(oldStock), ...Object.keys(newStock), ...Object.keys(credits)])) {
      const expected = (oldStock[item] || 0) + (credits[item] || 0), actual = newStock[item] || 0;
      assert(actual <= expected && actual >= Math.max(0, expected - (maxDebitPerPeriod[item] || 0) * receipts.length), `Stock changed outside actual online production/upkeep: ${city.id} ${item}`);
    }
    observed.push({ city: city.id, oldStock, newStock, credits, receipts });
  }
  return { earliest, latest, observed };
}
function assertDurable(before, after, version = VERSION) {
  assert.equal(after.pluginVersion, version);
  assert.equal(after.world.schema, 2);
  assert.equal(after.world.integrity, 'ok');
  assert.equal(after.core.integrity, 'ok');
  assert.deepEqual(after.world.foreignKeys, []);
  assertAddedTables(before.world.tables, after.world.tables, version);
  for (const table of before.world.tables) assert(after.world.tables.includes(table), `Dropped table ${table}`);
  const old = before.world.rows, next = after.world.rows;
  for (const table of STABLE) {
    if (table === 'life_evolution_action' && is11(version)) continue;
    const rows = table === 'life_evolution_action' ? next[table].map(row => Object.fromEntries(Object.keys(old[table][0] || row).filter(key => key !== 'credited_culture' || Object.hasOwn(old[table][0] || {}, key)).map(key => [key,row[key]]))) : next[table];
    assert.deepEqual(rows, old[table], `Existing durable ${table} rows changed`);
  }
  for (const table of ADDED) {
    if (V11_TABLES.includes(table)) continue;
    if (table.endsWith('_schema')) assert.deepEqual(next[table], [{ id: 1, version: table === 'civic_schema' ? 2 : 1 }]);
    else if (table === 'civic_clock') assert(next[table].every(row => old.city_state.some(city => row.city === city.id) && row.period >= 0));
    else assert.deepEqual(next[table], [], `Upgrade invented ${table}`);
  }
  assertLifeEvolution(before, after, version);
  const cityIdentity = cities => cities.map(({id,nation,name,civilization}) => ({id,nation,name,civilization}));
  assert.deepEqual(cityIdentity(next.city_state), cityIdentity(old.city_state));
  assert.equal(next.disaster_response.length, old.disaster_response.length);
  for (const previous of old.disaster_response) {
    const value = next.disaster_response.find(row => row.request_id === previous.request_id);
    assert(value, 'Missing old paid disaster receipt');
    const identity = ({steps, remaining_budget, last_applied_period, ...fields}) => fields;
    assert.deepEqual(identity(value), identity(previous), 'Existing paid terms changed');
    assert(value.steps >= previous.steps && value.steps <= previous.max_steps && value.remaining_budget >= 0 && value.remaining_budget <= previous.remaining_budget);
    assert.equal(next.disaster_response_history.filter(row => row.request_id === previous.request_id && row.kind === 'requested').length, 1);
    if (previous.steps === previous.max_steps) assert.deepEqual(value, previous, 'Spent recovery was replayed');
  }
  subsetRows(old.disaster_response_history, next.disaster_response_history, ['sequence'], 'disaster response history');
  subsetRows(old.disaster_recovery_ledger, next.disaster_recovery_ledger, ['request_id','region','resource','period'], 'recovery');
  const retained = retainedSettlements(old, next);
  subsetRows(retained, next.settlement, ['city','period'], 'retained settlement');
  const originalWatermarks = new Map(old.city_state.map(city => [city.id, city.last_period]));
  assert.equal(next.settlement.filter(row => row.period <= originalWatermarks.get(row.city)).length, retained.length,
    'Prior production receipts were replayed or changed outside the documented report retention window');
  const coreIdentity = rows => rows.filter(row => row.property_key !== '__starcore_revision__');
  for (const table of ['starcore_nation_state', 'starcore_treasury_state', 'starcore_resource_state']) assert.deepEqual(coreIdentity(after.core.rows[table]), coreIdentity(before.core.rows[table]), `Authoritative Core ${table} changed`);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) { console.log(HELP); return; }
  for (const key of ['java', 'paper', 'runtime', 'python']) assert(args[key], `Missing --${key}`);
  const options = { java: requireFile(args.java), paper: requireFile(args.paper), python: requireFile(args.python), runtime: fs.realpathSync(args.runtime), username: PLAYER,
    jvmArgs: ['-Xms128m','-Xmx512m','-Xss512k','-XX:MaxMetaspaceSize=192m','-XX:MaxDirectMemorySize=64m','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=64m','-Dio.netty.eventLoopThreads=2'] };
  const propFile = path.join(options.runtime, 'server.properties');
  const props = properties(propFile);
  options.port = Number(props['server-port']);
  assert.equal(props['server-ip'], '127.0.0.1');
  assert.equal(props['online-mode'], 'false');
  assert.equal(properties(path.join(options.runtime, 'eula.txt')).eula, 'true');
  assert.equal(fs.readFileSync(path.join(options.runtime, '.starworld-smoke-runtime'), 'utf8').trim(), 'StarWorld isolated Paper smoke runtime v1');
  const prep = readJson(path.join(options.runtime, 'UPGRADE-PROVENANCE.json'));
  assert.match(prep.targetVersion, /^1\.(?:0|1)\.\d+$/);
  VERSION = prep.targetVersion;
  assert.equal(prep.schema, 1);
  assert.equal(prep.preparationStatus, 'prepared-not-started');
  assert.equal(prep.processStarted, false);
  assert.equal(fs.realpathSync(prep.runtime), options.runtime);
  const source = fs.realpathSync(prep.sourceRuntime);
  assert.notEqual(source, options.runtime);
  const isNested = (directory, child) => {
    const relative = path.relative(directory, child);
    return relative !== '' && relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
  };
  assert(!isNested(source, options.runtime) && !isNested(options.runtime, source), 'Original and upgrade runtime must be separate trees');
  assert.equal(prep.sourceVersion, '0.5.0');
  assert.equal(prep.targetVersion, VERSION);
  assert.equal(prep.sourceEvidence.status, 'passed');
  assert.equal(prep.sourceEvidence.steps, 22);
  assert.equal(prep.sourceEvidence.pluginSha256, prep.sourcePluginSha256);
  const sourceEvidence = path.join(prep.sourceEvidence.directory, 'evidence.json');
  assert.equal(path.dirname(fs.realpathSync(prep.sourceEvidence.directory)), path.join(source, 'disaster-acceptance'));
  assert.equal(hash(sourceEvidence), prep.sourceEvidence.jsonSha256);
  const accepted = readJson(sourceEvidence);
  assert.equal(accepted.suite, 'real-paper-disaster-governance');
  assert.equal(accepted.status, 'passed');
  assert.equal(accepted.pluginJars['StarWorld.jar'], prep.sourcePluginSha256);
  assert.equal(accepted.steps.length, prep.sourceEvidence.steps);
  assert(accepted.steps.every(step => step.status === 'passed'));
  checkFingerprints(prep.sourceEvidence.directory, prep.sourceEvidence.serverLogSha256);
  assert.equal(hash(path.join(source,'plugins/StarWorld.jar')), prep.sourcePluginSha256);
  assert.equal(hash(propFile), prep.serverPropertiesSha256);
  assert.equal(options.port, prep.serverPort);
  assert.equal(configuredNumber(path.join(options.runtime, 'plugins/StarWorld/config.yml'), 'period-seconds'), 10);
  assert.equal(configuredNumber(path.join(options.runtime, 'plugins/StarWorld/config.yml'), 'event-chance'), 0);
  const sourceDb = sqliteSnapshot(options.python, source), before = sqliteSnapshot(options.python, options.runtime);
  assert.equal(sourceDb.pluginVersion, prep.sourceVersion);
  assert.equal(before.pluginVersion, VERSION);
  assert.equal(before.world.schema, 2);
  assert.equal(before.world.integrity, 'ok');
  assert.equal(before.core.integrity, 'ok');
  assert.deepEqual(before.world.foreignKeys, []);
  for (const label of ['world','core']) {
    assert.deepEqual(before[label], sourceDb[label], `Copy drift: ${label} database differs from original`);
    assert.equal(before[label].database_sha256, prep.sourceDatabaseSha256[label]);
  }
  for (const table of expectedAddedTables(VERSION)) assert(!before.world.tables.includes(table), `Source already has ${table}`);
  checkFingerprints(source, prep.sourceCriticalFiles);
  checkFingerprints(options.runtime, prep.sourceCriticalFiles);
  assert.deepEqual(configFingerprints(options.runtime, CONFIGS), prep.oldConfigurationSha256);
  assert.deepEqual(configFingerprints(source, CONFIGS), prep.oldConfigurationSha256);
  for (const name of NEW_CONFIGS) assert(!fs.existsSync(path.join(options.runtime, 'plugins/StarWorld', name)), `${name} must be created by startup`);
  const localRequire = args['client-deps'] ? createRequire(path.resolve(args['client-deps'], 'package.json')) : require;
  const mineflayer = localRequire('mineflayer');
  const plugins = path.join(options.runtime, 'plugins');
  const pluginJars = Object.fromEntries(fs.readdirSync(plugins).filter(file => file.endsWith('.jar')).map(file => [file, hash(path.join(plugins, file))]));
  assert.equal(pluginJars['StarWorld.jar'], prep.targetPluginSha256);
  assert.equal(pluginJars['StarCore.jar'], prep.sourceCriticalFiles['plugins/StarCore.jar']);
  assert.equal(pluginJars['StarWorldFixture.jar'], prep.fixtureSha256);
  const playerFile = path.join(options.runtime, props['level-name'], 'playerdata', `${prep.testPlayer.uuid}.dat`);
  const nbt = createRequire(createRequire(localRequire.resolve('mineflayer')).resolve('minecraft-protocol'))('prismarine-nbt');
  const savedInventory = async () => nbt.simplify((await nbt.parse(fs.readFileSync(playerFile))).parsed).Inventory;
  const originalInventory = await savedInventory();
  const expectedInventory = originalInventory.filter(item => item.Slot >= 0 && item.Slot < 36).map(item => ({slot:item.Slot,type:item.id,amount:item.count,named:!!item.components?.['minecraft:custom_name']})).sort((a,b) => a.slot-b.slot);
  assert(expectedInventory.some(item => item.type === 'minecraft:written_book' && item.named), 'No preexisting named inventory item to preserve');
  assert(!expectedInventory.some(item => ['minecraft:wheat','minecraft:bread','minecraft:oak_log','minecraft:oak_planks'].includes(item.type)), 'Original deposits must no longer be in player inventory');
  const rows = before.world.rows, paid = rows.disaster_response.find(row => row.strategy === 'drought_irrigation');
  assert.equal(rows.city_state.length, 2);
  assert.equal(rows.building.length, 10);
  assert.equal(rows.transfer.length, 8);
  assert.equal(rows.disaster_response.length, 3);
  assert(paid && paid.steps === paid.max_steps && paid.costs.length > 0);
  const original = rows.city_state.find(city => city.id === paid.city);
  assert.equal(original.id, prep.testPlayer.cityId);
  assert.equal(original.nation, prep.testPlayer.nationId);
  assert.equal(prep.testPlayer.username, PLAYER);
  for (const city of rows.city_state) assert.deepEqual(rows.building.filter(row => row.city === city.id).map(row => `${row.type}:${row.level}`).sort(), ['farm:1','kitchen:1','market:1','warehouse:1','well:1']);
  if (args['check-only']) { console.log(`PASS stopped source fingerprints, ${rows.transfer.length} transfers, ${rows.disaster_response.length} paid responses and named player inventory`); return; }

  const suiteRoot = path.join(options.runtime, 'upgrade-10-acceptance');
  assert(!fs.existsSync(suiteRoot), 'Preserve every attempt; prepare a fresh copy to rerun');
  const directory = path.join(suiteRoot, new Date().toISOString().replace(/[:.]/g, '-'));
  fs.mkdirSync(directory, { recursive: true });
  const evidence = { schema: 1, suite: 'real-paper-upgrade-05-to-10', status: 'running', startedAt: new Date().toISOString(), sourceVersion: prep.sourceVersion, targetVersion: VERSION, sourcePluginSha256: prep.sourcePluginSha256, paperSha256: hash(options.paper), pluginJars,
    beforeProvenance: prep, scope: 'Upgrade one verified 0.5 disaster save copy. Stopped SQLite is read-only. No seed, city creation, withdrawal, deposit, new disaster payment or config replacement. Two normal Paper and real client sessions.',
    testTuning: { jvmArgs: options.jvmArgs, nodeVersion: process.version, nodeExecArgv: process.execArgv, periodSeconds: 10, eventChance: 0 }, steps: [] };
  const save = () => fs.writeFileSync(path.join(directory, 'evidence.json'), JSON.stringify(evidence, null, 2) + '\n');
  const stage = async (name, work) => {
    const step = { name, status: 'running', startedAt: new Date().toISOString() };
    evidence.steps.push(step); save(); console.log(`RUN ${name}`);
    try { step.observed = await work(); step.status = 'passed'; console.log(`PASS ${name}`); return step.observed; }
    catch (error) { step.status = 'failed'; step.error = error.stack; throw error; }
    finally { step.finishedAt = new Date().toISOString(); save(); }
  };
  let server = new Paper(options, directory, 1), bot, firstStop, startedAt;
  async function closeClient() { if (bot) { const client = bot; bot = null; client.quit(); await client.finishTranscript(); } }
  async function start() {
    startedAt = Date.now();
    await server.start();
    bot = await connect(mineflayer, options, directory, server.run);
    assert.equal(bot.player.uuid, prep.testPlayer.uuid);
    const state = await disasterState(server);
    assert.equal(state.city.id, original.id);
    assert.equal(state.city.nationId, original.nation);
    assert.deepEqual(state.city.buildings.map(row => row.id).sort(), rows.building.filter(row => row.city === original.id).map(row => row.id).sort());
    const nation = await botMessage(bot, '/sw nation', new RegExp(`nationId=${original.nation}`));
    const inventory = await fixture(server, PLAYER, 'inventory', 'SW_FIXTURE_JSON');
    assertInventory(inventory, expectedInventory);
    return { state, nation, inventory, uuid: bot.player.uuid, pid: server.child.pid };
  }
  async function stop(previous) {
    await closeClient(); await server.stop();
    assert(server.lines.some(line => /All dimensions are saved/.test(line)), 'Missing normal world-save proof');
    assert(!server.lines.some(line => /OutOfMemoryError|Settlement failed|StarWorld tick failed|Could not flush StarWorld storage/.test(line)), 'Server had an invalid settlement or shutdown');
    const after = sqliteSnapshot(options.python, options.runtime);
    assertDurable(before, after);
    const onlineProduction = inspectProduction(previous.world.rows, after.world.rows, startedAt, Date.now());
    assert.deepEqual(await savedInventory(), originalInventory, 'Named item data changed on disk');
    assert.deepEqual(configFingerprints(options.runtime, [...CONFIGS, ...NEW_CONFIGS]), evidence.startupConfigurationSha256);
    return { after, onlineProduction, originalInventory, cleanExit: true };
  }
  try {
    await stage('stopped accepted 0.5 disaster save and copied fingerprints match provenance', async () => ({ sourceVersion: sourceDb.pluginVersion, snapshot: before, expectedInventory }));
    await stage('first startup restores original city authority facilities and real player inventory', start);
    await stage('additive configurations and governance GUI open while old market remains usable', async () => {
      assert.deepEqual(configFingerprints(options.runtime, CONFIGS), prep.oldConfigurationSha256);
      for (const name of NEW_CONFIGS) assert.equal(hash(path.join(options.runtime, 'plugins/StarWorld', name)), before.bundledConfigs[name], `Startup did not install exact bundled ${name}`);
      evidence.startupConfigurationSha256 = configFingerprints(options.runtime, [...CONFIGS, ...NEW_CONFIGS]);
      return { governance: await governanceWindow(bot), market: await botMessage(bot, '/sw market list', /实体商品挂牌/u), configurationSha256: evidence.startupConfigurationSha256 };
    });
    await stage('old paid disaster UUID retries without debit before resumed production', async () => {
      const replay = await replayPaid(server, bot, paid, original);
      const next = await nextSettlement(server, replay.after.city.lastPeriod);
      assert(Object.values(production(next.city.lastReport)).some(amount => amount > 0), 'The saved active city produced no real goods');
      return { replay, next };
    });
    await stage('first normal shutdown preserves old receipts and commits additive empty governance schemas', async () => {
      const result = await stop(before); firstStop = result.after; return result;
    });
    server = new Paper(options, directory, 2);
    await stage('second startup restores the same city inventory and paid disaster UUID without rededuction', async () => ({ restored: await start(), retry: await replayPaid(server, bot, paid, original) }));
    await stage('second normal shutdown retains world player payment and transfer state without replay', async () => {
      const result = await stop(firstStop);
      assert.deepEqual(result.after.world.rows.transfer, firstStop.world.rows.transfer);
      subsetRows(retainedSettlements(firstStop.world.rows, result.after.world.rows), result.after.world.rows.settlement, ['city','period'], 'first startup settlement');
      checkFingerprints(source, { ...prep.sourceCriticalFiles, ...prep.oldConfigurationSha256, 'plugins/StarWorld.jar': prep.sourcePluginSha256 });
      return result;
    });
    evidence.status = 'passed';
    console.log(`PASS ${evidence.steps.length} real upgrade stages: ${directory}`);
  } catch (error) { evidence.status = 'failed'; evidence.error = error.stack; console.error(error.stack); process.exitCode = 1; }
  finally {
    try { await closeClient(); if (server.child) await server.stop(); }
    catch (error) { evidence.status = 'failed'; evidence.cleanupError = error.stack; process.exitCode = 1; }
    evidence.finishedAt = new Date().toISOString(); save();
    fs.writeFileSync(path.join(suiteRoot,'latest.json'), JSON.stringify({ directory, status: evidence.status }, null, 2) + '\n');
  }
}

if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1; });
module.exports = { sqliteSnapshot, assertDurable, assertLifeEvolution, assertAddedTables, expectedAddedTables, stableTables: STABLE, addedTables: ADDED };
