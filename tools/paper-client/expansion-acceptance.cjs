#!/usr/bin/env node
'use strict';

// Real clients, real Paper/StarCore services. The fixture seeds player items explicitly;
// every warehouse deposit, facility, route, escrow, feast and export uses the shipped behavior.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const { setTimeout: delay } = require('node:timers/promises');
const { Paper, connect, botMessage, properties, configuredNumber, hash, requireFile, waitUntil, argumentsOf } = require('./acceptance.cjs');

const HELP = `Usage: node expansion-acceptance.cjs --java <java.exe> --runtime <owned-test-directory>
  --paper <local-paper.jar> [--client-deps <directory-with-mineflayer>]

Requires the separately prepared disposable localhost runtime marker/EULA/offline settings
used by acceptance.cjs, the latest real StarCore + StarWorld + test fixture JARs, a fresh
StarWorldEA/StarWorldEB pair, period-seconds=10, random event-chance=0, and the default
expansion contracts/life rules. Set isolated StarCore playability.new-nation-protection-days=0
and diplomacy.war.declaration-cost=0 (or fund through a public treasury API).
The test creates two real countries and cities in test chunks 20..24 and 52..56, places
bounded platforms/plains anchor biomes, and explicitly seeds each player's inventory.
Seeded goods are recorded separately from real production. It verifies actual commands,
warehouse upkeep, knowledge, military supply, life contribution/production, actual national
development/specialization, two-sided escrow, terrain travel, culture, national resource
receipt replay, blockade and restart across nineteen stages. Ten fixture chunks stay loaded.
Evidence is written to runtime/expansion-acceptance/<timestamp>; no download or config edit.
`;

function amount(snapshot, item) { return snapshot.city.stock[item] || 0; }
function order(snapshot, id) { return snapshot.orders.find(value => value.id === id); }
// Core resource storage persists Instant using epoch milliseconds. Preserve exact payload checks
// while comparing the documented durable timestamp precision across a restart.
function durableReceipt(receipt) {
  assert(receipt && Number.isFinite(Date.parse(receipt.appliedAt)), 'Missing valid durable receipt timestamp');
  return { ...receipt, appliedAt: new Date(receipt.appliedAt).toISOString() };
}
function wheatOutput(snapshot) {
  const match = snapshot.city.lastReport.match(/minecraft:wheat=(\d+)/);
  assert(match, `No actual wheat production in settlement: ${snapshot.city.lastReport}`);
  return Number(match[1]);
}
async function fixture(server, action, bot, suffix = '') {
  const from = server.lines.length;
  server.command(`swfixture ${action} ${bot.username}${suffix ? ' ' + suffix : ''}`);
  return waitUntil(() => {
    const lines = server.lines.slice(from);
    const failed = lines.find(line => line.includes('SW_FIXTURE_FAILED'));
    if (failed) throw new Error(lines.slice(-16).join('\n'));
    for (const line of lines) {
      const index = line.indexOf('SW_FIXTURE_EXPANSION '); if (index < 0) continue;
      const record = JSON.parse(line.slice(index + 'SW_FIXTURE_EXPANSION '.length));
      if (record.action === action && record.player === bot.username) return record.observed;
    }
    return null;
  }, 20000, `actual fixture ${action} ${bot.username}`, () => { server.check(); if (bot.failure) throw bot.failure; });
}
async function snapshot(server, bot) {
  const value = await fixture(server, 'exp-snapshot', bot);
  assert(value.city && value.development && value.nationalDevelopment && value.life && Array.isArray(value.nodes) && Array.isArray(value.orders), 'Missing actual expansion snapshot');
  assert.match(value.city.id, /^[0-9a-f-]{36}$/i);
  assert.deepEqual(value.definitionCounts, { terrains: 6, buildings: 9, civilizations: 5, events: 11 }, 'Default expansion definitions did not load in the actual server');
  for (const [item, count] of Object.entries(value.city.stock)) assert(Number.isSafeInteger(count) && count >= 0, `Invalid actual warehouse stock ${item}`);
  return value;
}
async function poll(server, bot, predicate, timeout, description) {
  const deadline = Date.now() + timeout; let current;
  while (Date.now() < deadline) { current = await snapshot(server, bot); if (predicate(current)) return current; await delay(1200); }
  throw new Error(`Timeout ${description}; latest ${JSON.stringify(current)}`);
}
async function register(server, bot, slot, node) {
  const from = bot.messages.length;
  const dispatched = await fixture(server, node ? 'exp-node' : 'exp-register', bot, String(slot));
  await waitUntil(() => bot.messages.slice(from).filter(line => line.includes('已登记 ')).length >= (node ? 1 : 4), 20000, 'successful real raycast registration messages');
  return { dispatched, messages: bot.messages.slice(from), snapshot: await snapshot(server, bot) };
}
async function offer(server, a, b, routeId) {
  const message = await botMessage(a, `/sw trade offer ${routeId} minecraft:iron_ingot=4 minecraft:oak_log=6`, /已托管报价 [0-9a-f-]{36}/);
  const id = message.match(/已托管报价 ([0-9a-f-]{36})/)[1];
  const pending = await snapshot(server, a); assert.equal(order(pending, id).status, 'OFFERED');
  await botMessage(b, `/sw trade accept ${id}`, /货单 .* (ACCEPTED|IN_TRANSIT)/);
  return { id, pending, acceptedA: await snapshot(server, a), acceptedB: await snapshot(server, b) };
}

async function main() {
  const args = argumentsOf(process.argv.slice(2)); if (args.help) { process.stdout.write(HELP); return; }
  for (const name of ['java', 'runtime', 'paper']) assert(args[name], `Required --${name}\n${HELP}`);
  const options = { java: requireFile(args.java), paper: requireFile(args.paper), runtime: path.resolve(args.runtime) };
  assert.equal(fs.readFileSync(path.join(options.runtime, '.starworld-smoke-runtime'), 'utf8').trim(), 'StarWorld isolated Paper smoke runtime v1');
  assert.equal(properties(path.join(options.runtime, 'eula.txt')).eula, 'true');
  const propertiesFile = path.join(options.runtime, 'server.properties'), props = properties(propertiesFile);
  assert.equal(props['server-ip'], '127.0.0.1'); assert.equal(props['online-mode'], 'false');
  options.port = Number(props['server-port']); assert(Number.isInteger(options.port) && options.port >= 1024 && options.port <= 65535);
  const configDir = path.join(options.runtime, 'plugins', 'StarWorld');
  options.periodSeconds = configuredNumber(path.join(configDir, 'config.yml'), 'period-seconds');
  assert.equal(options.periodSeconds, 10); assert.equal(configuredNumber(path.join(configDir, 'config.yml'), 'event-chance'), 0);
  const coreConfig = fs.readFileSync(path.join(options.runtime, 'plugins', 'StarCore', 'config.yml'), 'utf8');
  assert.match(coreConfig, /^\s*new-nation-protection-days:\s*0\s*(?:#.*)?$/m, 'Disable new-country protection only in this disposable runtime');
  const localRequire = args['client-deps'] ? createRequire(path.resolve(args['client-deps'], 'package.json')) : require;
  const mineflayer = localRequire('mineflayer');
  const directory = path.join(options.runtime, 'expansion-acceptance', new Date().toISOString().replace(/[:.]/g, '-')); fs.mkdirSync(directory, { recursive: true });
  const evidence = { schema: 2, suite: 'real-paper-expansion', status: 'running', startedAt: new Date().toISOString(), node: process.version,
    minecraft: '1.21.11', mineflayer: localRequire('mineflayer/package.json').version, runtime: options.runtime,
    paperSha256: hash(options.paper), pluginJars: {}, configSha256: {}, steps: [], seeds: [],
    testTuning: { periodSeconds: 10, eventChance: 0, newNationProtectionDays: 0, anchorBiome: 'plains', slots: [20, 52],
      fixtureForceLoadedChunks: [20, 21, 22, 23, 24, 52, 53, 54, 55, 56], explicitPlayerInventorySeeds: true } };
  for (const file of fs.readdirSync(path.join(options.runtime, 'plugins'))) if (/\.jar$/i.test(file)) evidence.pluginJars[file] = hash(path.join(options.runtime, 'plugins', file));
  for (const file of ['config.yml', 'definitions.yml', 'evolution.yml', 'exports.yml', 'life.yml', 'logistics.yml']) if (fs.existsSync(path.join(configDir, file))) evidence.configSha256[file] = hash(path.join(configDir, file));
  evidence.configSha256['StarCore/config.yml'] = hash(path.join(options.runtime, 'plugins', 'StarCore', 'config.yml'));
  const save = () => fs.writeFileSync(path.join(directory, 'evidence.json'), JSON.stringify(evidence, null, 2) + '\n');
  const stage = async (name, work) => {
    const step = { name, status: 'running', startedAt: new Date().toISOString() }; evidence.steps.push(step); save();
    try { step.observed = await work(); step.status = 'passed'; step.finishedAt = new Date().toISOString(); save(); console.log(`[PASS] ${name}`); return step.observed; }
    catch (error) { step.status = 'failed'; step.error = error.stack; save(); throw error; }
  };
  let server = new Paper(options, directory, 1), a, b, routeId, exportId, preRestart;
  try {
    await stage('real Paper startup and two protocol clients', async () => {
      await server.start();
      for (const file of ['config.yml', 'definitions.yml', 'evolution.yml', 'exports.yml', 'life.yml', 'logistics.yml'])
        evidence.configSha256[file] = hash(path.join(configDir, file));
      a = await connect(mineflayer, { ...options, username: 'StarWorldEA' }, directory, '1-a');
      b = await connect(mineflayer, { ...options, username: 'StarWorldEB' }, directory, '1-b');
      server.command('deop StarWorldEA'); server.command('deop StarWorldEB');
      return { pid: server.child.pid, clients: [a.player.uuid, b.player.uuid] };
    });
    await stage('two real independent core countries and cities with five dual claims', async () => {
      const first = await fixture(server, 'exp-setup', a, '0'), second = await fixture(server, 'exp-setup', b, '1');
      assert.notEqual(first.cityId, second.cityId); assert.notEqual(first.nationId, second.nationId); return { first, second };
    });
    await stage('explicit fixture seed enters warehouses through real inventory deposits', async () => {
      const observations = [];
      for (const bot of [a, b]) {
        const seed = await fixture(server, 'exp-seed', bot); evidence.seeds.push({ player: bot.username, ...seed });
        for (const [item, count] of Object.entries(seed.items)) await botMessage(bot, `/sw deposit ${item} ${count}`, new RegExp(`已存入 ${count} ${item}`));
        const stored = await snapshot(server, bot);
        assert.equal(stored.city.buildings.length, 0, 'Expected fresh expansion players');
        for (const [item, count] of Object.entries(seed.items)) assert.equal(amount(stored, item), count, `Seed deposit lost ${item}`);
        const remaining = bot.inventory.items().filter(item => Object.hasOwn(seed.items, 'minecraft:' + item.name));
        assert.equal(remaining.length, 0, 'Seeded items remained in the actual protocol inventory after deposit');
        observations.push(stored);
      } return observations;
    });
    await stage('four actual registered facilities in each city pay upkeep and develop', async () => {
      await register(server, a, 0, false); await register(server, b, 1, false);
      const first = await poll(server, a, value => value.development.knowledge > 0 && value.development.supply > 0, 45000, 'real academy/barracks settlement');
      const second = await poll(server, b, value => value.development.knowledge > 0 && value.development.supply > 0, 45000, 'second city settlement');
      for (const value of [first, second]) {
        assert.equal(value.city.buildings.length, 4); assert.equal(value.capacity, configuredNumber(path.join(configDir, 'config.yml'), 'stock-limit') / 2);
        assert(amount(value, 'minecraft:paper') < 64); assert(amount(value, 'minecraft:arrow') < 256); assert(amount(value, 'minecraft:oak_planks') < 128);
      } return { first, second };
    });
    await stage('planning, distinct civilization and paid farm upgrades', async () => {
      await botMessage(a, '/sw plan culture', /城市规划：culture/);
      await botMessage(b, '/sw civilization martial', /国家文明已转型/);
      let first = await snapshot(server, a); const farm = first.city.buildings.find(value => value.type === 'farm'); assert(farm);
      await botMessage(a, `/sw upgrade ${farm.id}`, /设施已升级/); await botMessage(a, `/sw upgrade ${farm.id}`, /设施已升级/);
      first = await snapshot(server, a); const second = await snapshot(server, b);
      assert.equal(first.development.plan, 'culture'); assert.equal(first.city.buildings.find(value => value.id === farm.id).level, 3); assert.equal(second.city.civilization, 'martial'); return { first, second };
    });
    await stage('national agriculture direction and knowledge arise from real city production', async () => {
      const before = await snapshot(server, a);
      assert.equal(before.nationalDevelopment.specialty, '');
      assert.equal(before.nationalDevelopment.nation, before.city.nationId);
      // Public city and national futures are separate immutable reads, so wait for a stable
      // observation instead of treating a settlement between their captures as corruption.
      const eligible = await poll(server, a, value => value.nationalDevelopment.tendencies.agriculture >= 20 && value.nationalDevelopment.knowledge >= 5
        && value.nationalDevelopment.knowledge === value.development.knowledge
        && value.nationalDevelopment.tendencies.agriculture === value.development.tendencies.agriculture, 60000, 'actual national specialty prerequisites');
      assert.equal(eligible.nationalDevelopment.direction, 'agriculture');
      assert.equal(eligible.nationalDevelopment.knowledge, eligible.development.knowledge, 'Single-city nation aggregate must match its actual academy');
      assert.equal(eligible.nationalDevelopment.tendencies.agriculture, eligible.development.tendencies.agriculture);
      const command = await botMessage(a, '/sw nation-development', /国家发展：/); return { before, eligible, command };
    });
    await stage('chef profession and community feast spend bread and increase actual production', async () => {
      await botMessage(a, '/sw feast community', /需要生活职业/);
      await botMessage(a, '/sw profession chef', /生活职业：厨师/);
      let base = await snapshot(server, a);
      base = await poll(server, a, value => value.city.lastPeriod > base.city.lastPeriod, 20000, 'fresh pre-feast settlement');
      assert.equal(base.life.productionMultiplier, 1);
      await botMessage(a, '/sw feast community', /宴会完成：文化 \+12/);
      const immediate = await snapshot(server, a);
      assert.equal(immediate.city.lastPeriod, base.city.lastPeriod, 'A settlement crossed the exact feast debit observation');
      assert.equal(amount(immediate, 'minecraft:bread'), amount(base, 'minecraft:bread') - 8);
      assert.equal(immediate.profession.profession, 'chef'); assert.equal(immediate.life.productionMultiplier, 1.04); assert.equal(immediate.life.culture, base.life.culture + 12);
      const after = await poll(server, a, value => value.city.lastPeriod > immediate.city.lastPeriod, 20000, 'actual boosted production settlement');
      assert(wheatOutput(after) > wheatOutput(base), 'Level-three real farm did not reflect life contribution in output');
      return { base, immediate, after, baselineWheat: wheatOutput(base), boostedWheat: wheatOutput(after) };
    });
    await stage('founder national specialty increases real farm output with a persisted decision', async () => {
      let base = await snapshot(server, a);
      base = await poll(server, a, value => value.city.lastPeriod > base.city.lastPeriod, 20000, 'fresh pre-national-specialty settlement');
      assert.equal(base.nationalDevelopment.specialty, '');
      await botMessage(a, '/sw national-specialty agriculture', /国家专长：agriculture/);
      const selected = await snapshot(server, a);
      assert.equal(selected.nationalDevelopment.specialty, 'agriculture');
      assert.equal(selected.nationalMultipliers.farm, 1.15);
      assert(selected.nationalDevelopment.changedPeriod >= base.city.lastPeriod);
      const after = await poll(server, a, value => value.city.lastPeriod > selected.city.lastPeriod, 20000, 'national-specialty production settlement');
      assert(wheatOutput(after) > wheatOutput(base), 'National agricultural specialization did not affect actual farm output');
      assert(wheatOutput(after) <= 12 * 3 * 10, 'National specialization escaped the final production multiplier cap');
      return { base, selected, after, baselineWheat: wheatOutput(base), specializedWheat: wheatOutput(after) };
    });
    await stage('real persisted armies use the public city readiness combat extension', async () => {
      const funded = await fixture(server, 'exp-armies', a, `${b.username} 0 1`); evidence.seeds.push(funded);
      let result;
      await waitUntil(async () => { result = await fixture(server, 'exp-combat', a, b.username); return result.result.source === 'starworld:city_readiness'; }, 20000, 'live army_combat contributor');
      assert(result.contributors.includes('starworld:city_readiness')); assert(result.result.damage > 100 && result.result.damage <= 130);
      assert(result.result.appliedModifiers.some(value => value.startsWith('city_supply:')));
      const state = await snapshot(server, a); assert(state.readiness && state.readiness.provided > 0); return { funded, result, state };
    });
    await stage('two physical road nodes and a real cross-country route', async () => {
      await register(server, a, 0, true); await register(server, b, 1, true);
      const first = await snapshot(server, a), second = await snapshot(server, b);
      assert.equal(first.nodes.length, 1); assert.equal(second.nodes.length, 1);
      const reply = await botMessage(a, `/sw route create ${second.city.id} land ${first.nodes[0].id},${second.nodes[0].id}`, /商路 [0-9a-f-]{36} ·/);
      routeId = reply.match(/商路 ([0-9a-f-]{36})/)[1]; const result = await snapshot(server, a), route = result.routes.find(value => value.id === routeId);
      assert.equal(route.distanceBlocks, 512); assert(route.travelPeriods >= 3); return { first, second, route };
    });
    await stage('bilateral offer and accept debit actual resources into durable escrow', async () => {
      const beforeA = await snapshot(server, a), beforeB = await snapshot(server, b), trade = await offer(server, a, b, routeId);
      assert.equal(amount(trade.pending, 'minecraft:iron_ingot'), amount(beforeA, 'minecraft:iron_ingot') - 4);
      assert.equal(amount(trade.acceptedB, 'minecraft:oak_log'), amount(beforeB, 'minecraft:oak_log') - 6);
      evidence.firstOrder = trade.id; return { beforeA, beforeB, ...trade };
    });
    await stage('terrain-timed transport credits both warehouses once and diffuses foreign culture', async () => {
      const pendingA = await snapshot(server, a), pendingB = await snapshot(server, b), id = evidence.firstOrder;
      const doneA = await poll(server, a, value => order(value, id)?.status === 'COMPLETED', 120000, 'actual cross-country cargo arrival');
      const doneB = await snapshot(server, b);
      assert.equal(amount(doneA, 'minecraft:oak_log'), amount(pendingA, 'minecraft:oak_log') + 6);
      assert.equal(amount(doneB, 'minecraft:iron_ingot'), amount(pendingB, 'minecraft:iron_ingot') + 4);
      const cultureA = await poll(server, a, value => value.culture.some(entry => entry.tradition === 'martial'), 15000, 'foreign culture after committed trade');
      const cultureB = await snapshot(server, b); assert(cultureB.culture.some(entry => entry.tradition === 'agrarian'));
      const later = await poll(server, a, value => value.city.lastPeriod > cultureA.city.lastPeriod, 20000, 'post-delivery duplicate guard');
      assert.equal(amount(later, 'minecraft:oak_log'), amount(doneA, 'minecraft:oak_log')); return { pendingA, pendingB, doneA, doneB, cultureA, cultureB, later };
    });
    await stage('national export verifies a real durable core receipt and stable replay', async () => {
      const before = await snapshot(server, a);
      const reply = await botMessage(a, '/sw export grain 1', /国家资源供给已托管：starworld:/);
      exportId = reply.match(/已托管：([^；\s]+)/)[1];
      let receipt;
      await waitUntil(async () => { receipt = await fixture(server, 'exp-receipt', a, exportId); return receipt.present; }, 20000, 'real national export receipt');
      assert.equal(receipt.receipt.transactionId, exportId); assert.equal(receipt.receipt.operation, 'GRANT'); assert.equal(receipt.receipt.category, 'OTHER');
      assert.equal(receipt.receipt.nationId.value || receipt.receipt.nationId, before.city.nationId); assert.deepEqual(receipt.receipt.resources, { agricultural: 8 });
      assert.equal(receipt.stockAfter.agricultural, (before.nation.resourceStock.agricultural || 0) + 8, 'National resource total must receive the actual export once');
      const replay = await fixture(server, 'exp-replay', a, exportId); assert.equal(replay.delivery.status, 'CONFIRMED'); assert.deepEqual(replay.stockAfter, replay.stockBefore);
      await botMessage(a, '/sw outbox', new RegExp(`${exportId} ACKED`)); evidence.exportReceipt = receipt.receipt; return { before, receipt, replay, transactionId: exportId };
    });
    await stage('lost national node territory pauses escrow without delivery or refund', async () => {
      const trade = await offer(server, a, b, routeId); evidence.blockedOrder = trade.id;
      await fixture(server, 'exp-unclaim', b, '1');
      const paused = await poll(server, a, value => order(value, trade.id)?.status === 'PAUSED', 30000, 'territory loss blocks transport');
      const target = await snapshot(server, b); assert.match(order(paused, trade.id).reason, /领土|节点|失效/);
      assert.equal(amount(paused, 'minecraft:oak_log'), amount(trade.acceptedA, 'minecraft:oak_log'));
      assert.equal(amount(target, 'minecraft:iron_ingot'), amount(trade.acceptedB, 'minecraft:iron_ingot'));
      return { trade, paused, target };
    });
    await stage('real core war blocks restored route and keeps physical cargo in escrow', async () => {
      await fixture(server, 'exp-reclaim', b, '1'); const declaration = await fixture(server, 'exp-war', a, b.username);
      const paused = await poll(server, a, value => order(value, evidence.blockedOrder)?.status === 'PAUSED' && /战争|外交/.test(order(value, evidence.blockedOrder).reason), 30000, 'real war blockade');
      assert(paused.nation.warOpponents.includes((await snapshot(server, b)).city.nationId));
      const later = await poll(server, a, value => order(value, evidence.blockedOrder)?.lastPeriod > order(paused, evidence.blockedOrder).lastPeriod, 20000, 'second wartime period');
      assert.equal(order(later, evidence.blockedOrder).progressedPeriods, order(paused, evidence.blockedOrder).progressedPeriods);
      assert.equal(amount(later, 'minecraft:oak_log'), amount(paused, 'minecraft:oak_log')); return { declaration, paused, later };
    });
    await stage('public core peace resumes the same escrow and never duplicates its cargo', async () => {
      const peace = await fixture(server, 'exp-peace', a, b.username);
      const before = await snapshot(server, a), completed = await poll(server, a, value => order(value, evidence.blockedOrder)?.status === 'COMPLETED', 120000, 'resumed cargo completion');
      assert.equal(amount(completed, 'minecraft:oak_log'), amount(before, 'minecraft:oak_log') + 6);
      return { peace, before, completed };
    });
    await stage('completed trades, development, professions and receipt are durably flushed', async () => {
      preRestart = { a: await snapshot(server, a), b: await snapshot(server, b), receipt: await fixture(server, 'exp-receipt', a, exportId) };
      for (const bot of [a, b]) { bot.quit(); await bot.finishTranscript(); } a = b = null; await server.stop(); return preRestart;
    });
    server = new Paper(options, directory, 2);
    await stage('restart restores both cities and does not replay acknowledged national exports', async () => {
      await server.start(); a = await connect(mineflayer, { ...options, username: 'StarWorldEA' }, directory, '2-a'); b = await connect(mineflayer, { ...options, username: 'StarWorldEB' }, directory, '2-b');
      const after = { a: await snapshot(server, a), b: await snapshot(server, b), receipt: await fixture(server, 'exp-receipt', a, exportId) };
      for (const key of ['a', 'b']) {
        assert.equal(after[key].city.id, preRestart[key].city.id); assert.equal(after[key].city.nationId, preRestart[key].city.nationId);
        assert.equal(after[key].city.buildings.length, 4); assert.equal(after[key].nodes.length, 1);
        assert.equal(after[key].development.plan, preRestart[key].development.plan); assert(after[key].development.knowledge >= preRestart[key].development.knowledge);
        assert.equal(after[key].nationalDevelopment.specialty, preRestart[key].nationalDevelopment.specialty);
        assert.equal(after[key].nationalDevelopment.direction, preRestart[key].nationalDevelopment.direction);
        assert(after[key].nationalDevelopment.knowledge >= preRestart[key].nationalDevelopment.knowledge);
        for (const [direction, score] of Object.entries(preRestart[key].nationalDevelopment.tendencies)) assert(after[key].nationalDevelopment.tendencies[direction] >= score);
        assert.equal(after[key].capacity, preRestart[key].capacity); assert.equal(order(after[key], evidence.firstOrder).status, 'COMPLETED'); assert.equal(order(after[key], evidence.blockedOrder).status, 'COMPLETED');
        assert.equal(amount(after[key], 'minecraft:oak_log'), amount(preRestart[key], 'minecraft:oak_log')); assert.equal(amount(after[key], 'minecraft:iron_ingot'), amount(preRestart[key], 'minecraft:iron_ingot'));
        for (const prior of preRestart[key].culture) assert(after[key].culture.some(value => value.region === prior.region && value.tradition === prior.tradition && value.influence >= prior.influence));
      }
      assert.equal(after.a.profession.profession, 'chef'); assert.equal(after.a.life.culture, preRestart.a.life.culture);
      assert.deepEqual(durableReceipt(after.receipt.receipt), durableReceipt(preRestart.receipt.receipt)); const replay = await fixture(server, 'exp-replay', a, exportId); assert.deepEqual(replay.stockBefore, replay.stockAfter);
      await botMessage(a, '/sw outbox', new RegExp(`${exportId} ACKED`)); return { before: preRestart, after, replay };
    });
    await stage('second owned Paper process cleanly shuts down', async () => { for (const bot of [a, b]) { bot.quit(); await bot.finishTranscript(); } a = b = null; await server.stop(); return { cleanExit: true }; });
    evidence.status = 'passed'; evidence.finishedAt = new Date().toISOString(); save(); console.log(`PASS ${evidence.steps.length} expansion checks: ${path.join(directory, 'evidence.json')}`);
  } catch (error) {
    evidence.status = 'failed'; evidence.error = error.stack; evidence.finishedAt = new Date().toISOString(); save(); console.error(`FAIL ${error.stack}\n${server.lines.slice(-25).join('\n')}\nEvidence ${path.join(directory, 'evidence.json')}`); process.exitCode = 1;
  } finally {
    for (const bot of [a, b]) if (bot) { try { bot.quit(); await bot.finishTranscript(); } catch {} }
    if (server.child) try { await server.stop(); } catch (error) { evidence.status = 'failed'; evidence.cleanupError = error.stack; save(); process.exitCode = 1; }
    fs.writeFileSync(path.join(options.runtime, 'expansion-acceptance', 'latest.json'), JSON.stringify({ directory, status: evidence.status }, null, 2) + '\n');
  }
}
if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1; });
