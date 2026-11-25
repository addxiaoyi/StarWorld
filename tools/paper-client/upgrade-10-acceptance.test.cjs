'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { assertDurable, assertLifeEvolution, assertAddedTables, expectedAddedTables, stableTables, addedTables } = require('./upgrade-10-acceptance.cjs');
const { validateRunConfigurations } = require('../senluo-fixture/lifecycle-acceptance.cjs');

test('life migration permits only the exact new climate default and preserves every original configuration', () => {
  const initial={'config.yml':'a'.repeat(64),'life.yml':'b'.repeat(64)};
  const bundled={'climate.yml':'c'.repeat(64)};
  const actual={...initial,...bundled};
  assert.deepEqual(validateRunConfigurations('1.4.0',initial,actual,bundled),actual);
  assert.throws(()=>validateRunConfigurations('1.4.0',initial,initial,bundled),/Configuration input changed/);
  for(const tampered of [{...actual,'climate.yml':'d'.repeat(64)},
    {...actual,'life.yml':'d'.repeat(64)},{...actual,'unexpected.yml':'d'.repeat(64)}]) {
    assert.throws(()=>validateRunConfigurations('1.4.0',initial,tampered,bundled),/Configuration input changed/);
  }
  assert.throws(()=>validateRunConfigurations('1.4.0',initial,actual,{}),/must provide its climate default/);
  assert.throws(()=>validateRunConfigurations('1.4.0',actual,actual,bundled),/must not already contain/);
});

test('historical life source and pre-climate candidate accept no new configuration files', () => {
  const initial={'config.yml':'a'.repeat(64),'life.yml':'b'.repeat(64)};
  for(const version of ['1.0.0','1.1.0']) {
    assert.deepEqual(validateRunConfigurations(version,initial,{...initial},{}),initial);
    assert.throws(()=>validateRunConfigurations(version,initial,{...initial,'climate.yml':'c'.repeat(64)},{}),/Configuration input changed/);
  }
});

const BASE_ADDED = ['public_works_schema', 'public_works', 'public_works_history', 'civic_schema', 'civic_clock', 'civic_request', 'civic_request_cost', 'civic_action_claim', 'civic_history'].sort();
const V11_ADDED = ['life_evolution_budget', 'life_evolution_pending', 'starworld_dispatch_cursor'].sort();
const V13_ADDED = ['city_region'];
const AUXILIARY_TABLES = ['city_state', 'stock', 'settlement', 'region', 'reserve', 'disaster_response', 'disaster_response_history', 'disaster_recovery_ledger', 'life_schema', 'life_city'];

function snapshots() {
  const city = { id: 'city-a', nation: 'nation-a', name: 'A', civilization: 'agrarian', last_period: 9 };
  const actions = [
    { id: 'complete', city: city.id, nation: city.nation, period: 7, culture: 10, eligible: 1 },
    { id: 'partial', city: city.id, nation: city.nation, period: 8, culture: 12, eligible: 1 },
  ];
  const old = { world: { tables: ['life_schema', 'life_action', 'life_evolution_action', 'city_state'], rows: {
    city_state: [city], life_schema: [{ id: 1, version: 1 }], life_action: actions,
    life_evolution_action: [{ id: 'complete', consumed_period: 8 }],
  } } };
  const next = { world: { tables: [...old.world.tables, ...V11_ADDED], rows: {
    city_state: [city], life_schema: [{ id: 1, version: 2 }], life_action: actions,
    life_evolution_action: [
      { id: 'complete', consumed_period: 8, credited_culture: 10 },
      { id: 'partial', consumed_period: 9, credited_culture: 4 },
    ],
    life_evolution_pending: [{ id: 'partial', city: city.id, nation: city.nation, period: 8 }],
    life_evolution_budget: [{ city: city.id, nation: city.nation, period: 9, culture: 4 }],
    starworld_dispatch_cursor: [
      { queue: 'starworld:market-payment', next_lane: 'FRESH' },
      { queue: 'starworld:resource-export', next_lane: 'DUE' },
    ],
  } } };
  return { old, next };
}

function durableSnapshots(version) {
  const versionMinor = Number(version.split('.')[1]);
  const v11 = versionMinor >= 1;
  const v13 = versionMinor >= 3;
  const { old: life, next: evolved } = snapshots();
  const originalRows = Object.fromEntries(stableTables.map(table => [table, []]));
  Object.assign(originalRows, life.world.rows, {
    city_state: [{ id: 'city-a', nation: 'nation-a', name: 'A', civilization: 'agrarian', last_period: 9 }],
    building: [{ id: 'building-a', city: 'city-a', nation: 'nation-a', type: 'farm', world: 'world-a',
      world_name: 'world', x: -1, y: 64, z: -17, level: 1 }],
    stock: [], settlement: [], region: [{ id: 'world-a:-1:-2', terrain: 'plains', last_regen: -1 }], reserve: [],
    disaster_response: [], disaster_response_history: [], disaster_recovery_ledger: [],
  });
  if (!v11) originalRows.life_evolution_action = [{ id: 'complete', consumed_period: 8 }];
  const afterRows = structuredClone(originalRows);
  for (const table of addedTables) afterRows[table] = [];
  if (v13) afterRows.city_region = [{ city: 'city-a', nation: 'nation-a', region: 'world-a:-1:-2', world_name: 'world' }];
  afterRows.public_works_schema = [{ id: 1, version: 1 }];afterRows.civic_schema = [{ id: 1, version: 2 }];
  if (v11) Object.assign(afterRows, evolved.world.rows);
  const baseTables = [...new Set([...stableTables, ...AUXILIARY_TABLES])];
  const newTables = expectedAddedTables(version);
  const coreRows = { starcore_nation_state: [], starcore_treasury_state: [], starcore_resource_state: [] };
  const before = { pluginVersion: '0.5.0', world: { schema: 2, integrity: 'ok', foreignKeys: [], tables: baseTables, rows: originalRows }, core: { integrity: 'ok', rows: coreRows } };
  const after = { pluginVersion: version, world: { schema: v13 ? 3 : 2, integrity: 'ok', foreignKeys: [], tables: [...baseTables, ...newTables], rows: afterRows }, core: { integrity: 'ok', rows: structuredClone(coreRows) } };
  return { before, after };
}

test('historical 1.0 keeps legacy life schema and adds no 1.1 migration tables', () => {
  assert.deepEqual(expectedAddedTables('1.0.0'), BASE_ADDED);
  assert.doesNotThrow(() => assertAddedTables(['city_state'], ['city_state', ...BASE_ADDED], '1.0.0'));
  assert.throws(() => assertAddedTables(['city_state'], ['city_state', ...BASE_ADDED, ...V11_ADDED], '1.0.0'));
  const rows = { city_state: [], life_schema: [{ id: 1, version: 1 }], life_action: [], life_evolution_action: [] };
  assert.doesNotThrow(() => assertLifeEvolution({ world: { tables: ['life_schema'], rows } }, { world: { tables: ['life_schema'], rows: { ...rows } } }, '1.0.0'));
  assert.throws(() => assertLifeEvolution({ world: { tables: ['life_schema'], rows } }, { world: { tables: ['life_schema','life_evolution_pending'], rows: { ...rows, life_evolution_pending: [{ id: 'unexpected' }] } } }, '1.0.0'), /Historical 1\.0 must not/u);
  const durable = durableSnapshots('1.0.0');assert.doesNotThrow(() => assertDurable(durable.before,durable.after,'1.0.0'));
});

test('1.1 permits only the three new tables and validates migration state by city and queue namespace', () => {
  assert.deepEqual(expectedAddedTables('1.1.0'), [...BASE_ADDED, ...V11_ADDED].sort());
  assert.doesNotThrow(() => assertAddedTables(['city_state'], ['city_state', ...BASE_ADDED, ...V11_ADDED], '1.1.0'));
  assert.throws(() => assertAddedTables(['city_state'], ['city_state', ...BASE_ADDED, ...V11_ADDED, 'unexpected'], '1.1.0'));
  const { old, next } = snapshots();
  assert.doesNotThrow(() => assertLifeEvolution(old, next, '1.1.0'));
  const durable = durableSnapshots('1.1.0');assert.doesNotThrow(() => assertDurable(durable.before,durable.after,'1.1.0'));
});

test('1.2 keeps the 1.1 schema migration set without introducing another database schema',()=>{
  assert.deepEqual(expectedAddedTables('1.2.0'),[...BASE_ADDED,...V11_ADDED].sort());
  const durable=durableSnapshots('1.2.0');assert.doesNotThrow(()=>assertDurable(durable.before,durable.after,'1.2.0'));
});

test('1.3 and 1.4 add the nation-scoped atlas schema and backfill real historic facility regions', () => {
  for (const version of ['1.3.0', '1.4.0']) {
    const added = [...BASE_ADDED, ...V11_ADDED, ...V13_ADDED].sort();
    assert.deepEqual(expectedAddedTables(version), added);
    assert.doesNotThrow(() => assertAddedTables(['building'], ['building', ...added], version));
    assert.throws(() => assertAddedTables(['building'], ['building', ...added.filter(name => name !== 'city_region')], version));
    const durable = durableSnapshots(version);
    assert.deepEqual(durable.after.world.rows.city_region,
      [{ city: 'city-a', nation: 'nation-a', region: 'world-a:-1:-2', world_name: 'world' }]);
    assert.doesNotThrow(() => assertDurable(durable.before, durable.after, version));
    durable.after.world.rows.city_region[0].nation = 'another-nation';
    assert.throws(() => assertDurable(durable.before, durable.after, version), /original city and nation atlas/u);
  }
});

test('1.1 rejects deleted legacy progress, lost partial work, invalid budgets, and foreign queue namespaces', () => {
  const { old, next } = snapshots();
  assert.throws(() => assertLifeEvolution(old, { world: { ...next.world, rows: { ...next.world.rows, life_evolution_action: next.world.rows.life_evolution_action.slice(1) } } }, '1.1.0'), /Existing life evolution receipt was deleted/u);
  assert.throws(() => assertLifeEvolution(old, { world: { ...next.world, rows: { ...next.world.rows, life_evolution_pending: [] } } }, '1.1.0'), /Original pending action was lost/u);
  assert.throws(() => assertLifeEvolution(old, { world: { ...next.world, rows: { ...next.world.rows, life_evolution_budget: [{ city: 'city-b', nation: 'nation-b', period: 9, culture: 4 }] } } }, '1.1.0'), /Budget references a non-original city/u);
  assert.throws(() => assertLifeEvolution(old, { world: { ...next.world, rows: { ...next.world.rows, starworld_dispatch_cursor: [...next.world.rows.starworld_dispatch_cursor, { queue: 'foreign:queue', next_lane: 'DUE' }] } } }, '1.1.0'), /only the market and resource queue namespaces/u);
  const durable = durableSnapshots('1.1.0');durable.after.world.rows.life_action=[];
  assert.throws(() => assertDurable(durable.before,durable.after,'1.1.0'), /Existing durable life_action rows changed/u);
});
