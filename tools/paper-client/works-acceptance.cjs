#!/usr/bin/env node
'use strict';

// Static delivery: no downloads/config rewrites/profile injection/SQL. Three sequential owned Paper children.
// Every project write uses a real player command; fixture snapshots only read public extension APIs.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const { setTimeout: delay } = require('node:timers/promises');
const { Paper, connect, botMessage, properties, configuredNumber, hash, requireFile, waitUntil, argumentsOf } = require('./acceptance.cjs');

const HELP = `Usage: node works-acceptance.cjs --java <java.exe> --runtime <owned-test-directory>
  --paper <local-paper.jar> [--client-deps <directory-with-mineflayer>]
  node works-acceptance.cjs --self-test

Requires an independently prepared disposable localhost runtime with the normal marker/EULA,
Paper 1.21.11, Java 21, real StarCore + final StarWorld + this test fixture, fresh SWWorksA/B/C
players/cities, period-seconds=10, event-chance=0, default definitions/evolution/public-works,
and zero/funded isolated national/city/claim creation costs. No setting is changed by this suite.
The fixture explicitly seeds player inventories (recorded as seeds, never production), makes
three real nations/cities with both city and nation territory, places nine bounded real anchors
per city in six forced chunks [180,181,212,213,244,245], and dispatches real raycast registrations.
Knowledge/culture come only from maintained academies. Founders choose civilization/plans and
pay projects using ordinary commands. Actual warehouse deposits/withdrawals remain player actions.
The suite exercises GUI read protection, 3/5/4-step projects, territory pause, restart,
UUID/slot deduplication, completed-project starting-stock maintenance pause and physical recovery.
It then continues IN THE SAME WORLD through real road escrow, public core war blockade,
a third-process restart while cargo is blocked, public peace, exactly-once physical delivery,
and the real famine-import response backed by that exact completed logistics receipt.
Isolated StarCore new-nation-protection-days and war declaration-cost must be zero.
Failures preserve the disposable world and evidence; only this suite's child processes are stopped.
`;
const SPEC = [
  { name: 'SWWorksA', slot: 0, id: 'irrigation_network', civilization: 'agrarian', plan: 'balanced', steps: 3, knowledge: 2, culture: 0 },
  { name: 'SWWorksB', slot: 1, id: 'arsenal_district', civilization: 'industrial', plan: 'industry', steps: 5, knowledge: 8, culture: 0 },
  { name: 'SWWorksC', slot: 2, id: 'cultural_quarter', civilization: 'scholarly', plan: 'culture', steps: 4, knowledge: 5, culture: 5 }
];
const DEFAULT_FACTORS = {
  irrigation_network: { farm: 1.15, workshop: 0.95 },
  arsenal_district: { workshop: 1.15, farm: 0.95 },
  cultural_quarter: { kitchen: 1.15, farm: 1.05, workshop: 0.95 }
};
function produced(s) { const match = s.city.lastReport.match(/产出 \{([^}]*)\}/); assert(match, `Missing real settlement production: ${s.city.lastReport}`); const result = {}; for (const part of match[1].split(/,\s*/)) if (part) { const item = part.match(/^([^=]+)=(\d+)$/); assert(item, `Unknown production entry: ${part}`); result[item[1]] = Number(item[2]); } return result; }
function stock(s, item) { return s.city.stock[item] || 0; }
function project(s) { return s.projects.find(p => p.nationId === s.city.nationId); }
function records(s, kind) { const p = project(s); return s.history.filter(h => h.requestId === p.requestId && h.kind === kind); }
function coherent(s) { const p = project(s); return s.development.lastPeriod === s.city.lastPeriod && s.atlas.cityLastPeriod === s.city.lastPeriod && (!p || Math.max(p.lastBuildPeriod, p.lastOperationPeriod, p.lastConstructionAttemptPeriod) <= s.city.lastPeriod); }
function frozen(p) {
  const definition = { ...p.definition };
  // These four public definition fields are Java Sets, whose JVM iteration order is unspecified.
  for (const field of ['facilities', 'terrains', 'plans', 'civilizations']) definition[field] = [...definition[field]].sort();
  return { requestId: p.requestId, cityId: p.cityId, nationId: p.nationId, definition, requestedPeriod: p.requestedPeriod, effectivePeriod: p.effectivePeriod, startPlan: p.startPlan, startCivilization: p.startCivilization, startKnowledge: p.startKnowledge, startCulture: p.startCulture };
}
function count(bot, name) { return bot.inventory.items().filter(i => i.name === name).reduce((sum, item) => sum + item.count, 0); }
function order(s, id) { return s.orders.find(o => o.id === id); }
function tradeReceipts(s, id) { return s.tradeReceipts.filter(r => r.orderId === id); }
function title(window) { return typeof window.title === 'string' ? window.title : JSON.stringify(window.title); }
function afterExactDebit(before, after, costs) { assert.equal(after.city.lastPeriod, before.city.lastPeriod, 'A settlement crossed an immediate payment assertion'); const keys = new Set([...Object.keys(before.city.stock), ...Object.keys(after.city.stock), ...Object.keys(costs)]); for (const item of keys) assert.equal(stock(after, item), stock(before, item) - (costs[item] || 0), `Exact warehouse debit mismatch: ${item}`); }
function arithmeticChecks() {
  // Separate deterministic arithmetic unit model. Real-server output is recorded separately below.
  const output = (base, core, civil, extension, terrain, level = 1) => Math.floor(base * level * Math.min(core * civil * extension * terrain, 10));
  const baseline = output(12, 1, 1.25, 1, 1.3), irrigated = output(12, 1, 1.25, 1.15, 1.3);
  assert.equal(baseline, 19); assert.equal(irrigated, 22); assert.equal(output(3, 1, 1.3, 1.2 * 1.15, 1), 5); assert.equal(output(3, 1, 1.3, 1.2, 1), 4);
  assert.equal(output(5, 1, 1.15, 1.1 * 1.15, 1), 7); assert.equal(output(5, 1, 1.15, 1.1, 1), 6);
  assert.equal(output(12, 1, 1, 0.95, 1), 11); assert.equal(output(12, 1, 1, 1, 1), 12); assert.equal(output(12, 10, 10, 10, 10), 120);
  return { scope: 'deterministic arithmetic unit model; does not hold every live city modifier constant', rounding: 'floor(base * level * min(core * civilization * extension * terrain, 10))', baselineAgrarianFarm: baseline, fundedIrrigationFarm: irrigated, defaultFactors: DEFAULT_FACTORS };
}
async function fixture(server, action, bot, suffix = '') {
  const from = server.lines.length; server.command(`swfixture ${action} ${bot.username}${suffix ? ' ' + suffix : ''}`);
  const prefix = action.startsWith('exp-') ? 'SW_FIXTURE_EXPANSION ' : 'SW_FIXTURE_WORKS ';
  return waitUntil(() => { const recent = server.lines.slice(from); if (recent.some(line => line.includes('SW_FIXTURE_FAILED'))) throw new Error(recent.slice(-18).join('\n')); for (const line of recent) { const at = line.indexOf(prefix); if (at < 0) continue; const record = JSON.parse(line.slice(at + prefix.length)); if (record.action === action && record.player === bot.username) return record.observed; } return null; }, 25000, `real works fixture ${action}/${bot.username}`, () => { server.check(); if (bot.failure) throw bot.failure; });
}
async function permissions(server, bot, expected) {
  return waitUntil(async () => {
    const observed = await fixture(server, 'exp-permissions', bot);
    return observed.admin === expected ? observed : false;
  }, 10000, 'actual isolated player admin permission', () => server.check());
}
async function famineCommand(server, bot) {
  const permissionBefore = await permissions(server, bot, false);
  let message, permissionDuring;
  server.command(`op ${bot.username}`);
  try {
    permissionDuring = await permissions(server, bot, true);
    message = await botMessage(bot, '/sw event famine', /事件已触发：famine/);
  } finally {
    server.command(`deop ${bot.username}`);
    await permissions(server, bot, false);
  }
  return { entryPoint: 'real player /sw event famine', permissionBefore, permissionDuring,
    permissionAfter: await permissions(server, bot, false), message, directSimulationMutation: false };
}
async function snapshot(server, bot) {
  const s = await fixture(server, 'works-snapshot', bot); assert(s.city && s.development && s.atlas && Array.isArray(s.projects) && Array.isArray(s.history) && Array.isArray(s.feasibility), 'Missing public works/profile/atlas evidence');
  assert.equal(s.atlas.authorityActive, true); for (const [item, amount] of Object.entries(s.city.stock)) assert(Number.isSafeInteger(amount) && amount >= 0, `Invalid real stock ${item}`); return s;
}
async function poll(server, bot, predicate, timeout = 45000, description = 'works state') { const deadline = Date.now() + timeout; let latest; while (Date.now() < deadline) { latest = await snapshot(server, bot); if (coherent(latest) && predicate(latest)) return latest; await delay(450); } throw new Error(`Timeout ${description}; latest=${JSON.stringify(latest)}`); }
async function fresh(server, bot) {
  // SimulationClock is anchored to server initialization, not UTC multiples of ten seconds.
  const observed = await snapshot(server, bot);
  return poll(server, bot, s => s.currentPeriod > observed.currentPeriod && s.city.lastPeriod === s.currentPeriod,
    30000, 'a newly observed committed logical period before an immediate assertion');
}
async function freshAll(server, bots) {
  const observed = await Promise.all(bots.map(bot => snapshot(server, bot)));
  const afterPeriod = Math.max(...observed.map(s => s.currentPeriod));
  return waitUntil(async () => {
    const readings = await Promise.all(bots.map(bot => snapshot(server, bot)));
    return readings[0].currentPeriod > afterPeriod && readings.every(s => coherent(s) && s.city.lastPeriod === s.currentPeriod && s.currentPeriod === readings[0].currentPeriod) ? readings : false;
  }, 35000, 'all cities observed in the same newly committed logical period', () => server.check());
}
async function next(server, bot, before, predicate = () => true, timeout = 30000) { return poll(server, bot, s => s.city.lastPeriod > before.city.lastPeriod && predicate(s), timeout, 'next actual committed settlement'); }
async function windowAfter(bot, action, expected) { const promise = new Promise((resolve, reject) => { const timer = setTimeout(() => reject(new Error(`GUI did not open: ${expected}`)), 15000); bot.once('windowOpen', window => { clearTimeout(timer); resolve(window); }); }); await action(); const window = await promise; assert.match(title(window), expected); return window; }
async function inspectGui(server, bot) {
  const before = await fresh(server, bot), inventoryBefore = count(bot, 'bricks');
  await windowAfter(bot, async () => bot.chat('/sw governance'), /治理总览/);
  await windowAfter(bot, () => bot.clickWindow(10, 0, 0), /公共工程选项/);
  const preview = await windowAfter(bot, () => bot.clickWindow(11, 0, 0), /明确确认/);
  assert(preview.slots[13] && preview.slots[21] && preview.slots[31], 'Real preview lacks terms/costs/explicit confirmation'); assert.equal(preview.slots[31].name, 'lime_dye', 'Eligible actual irrigation project did not expose confirmation');
  await bot.clickWindow(13, 0, 1); await delay(180); assert.equal(count(bot, 'bricks'), inventoryBefore, 'GUI shift click extracted a project display'); const observedTitle = title(preview); bot.closeWindow(bot.currentWindow);
  const after = await snapshot(server, bot); afterExactDebit(before, after, {}); assert.deepEqual(after.projects, before.projects); assert.deepEqual(after.history, before.history); return { before, after, observedTitle, slots: { terms: 13, costs: 21, confirmation: 31 }, extracted: false };
}
function checkConstruction(s, spec) {
  const p = project(s), build = s.history.filter(h => h.requestId === p.requestId && ['construction', 'completed'].includes(h.kind)); assert.equal(p.progress, spec.steps); assert.equal(build.length, spec.steps, 'Not exactly the configured number of actual paid construction steps'); assert.equal(new Set(build.map(h => h.period)).size, spec.steps, 'One actual period advanced construction twice'); assert.equal(records(s, 'completed').length, 1);
  for (const h of build) { assert(h.period >= p.effectivePeriod); assert(h.detail.includes('construction='), 'No real construction debit detail'); for (const [item, amount] of Object.entries(p.definition.constructionCosts)) assert(h.detail.includes(`${item}=${amount}`), `Missing exact paid construction material ${item}`); }
  assert.equal(p.lastBuildPeriod, p.completedPeriod); assert.equal(records(s, 'started').length, 1); return { project: p, construction: build, warehouse: s.city.stock, actualProduction: produced(s) };
}
function constructionConservation(before, after, spec) {
  const p = project(after), item = spec.slot === 0 ? 'minecraft:oak_planks' : spec.slot === 1 ? 'minecraft:iron_ingot' : 'minecraft:paper';
  assert.equal(after.city.lastPeriod, before.city.lastPeriod + 1, 'Missed an actual construction inventory observation period');
  assert.equal(p.progress, project(before).progress + 1, 'This real maintained period did not advance exactly one construction step');
  assert.equal(before.verified.length, 9); assert.equal(after.verified.length, 9); const made = produced(after);
  const upkeep = after.city.buildings.reduce((sum, building) => sum + (after.definitions.buildings[building.type].upkeep[item] || 0) * building.level, 0);
  let recipeInputs = 0;
  for (const recipe of after.definitions.recipes) if (recipe.inputs[item]) {
    // For the three selected materials, every consuming recipe has its own unique output:
    // plank/iron -> arrows or shields; paper has no recipe consumers. Meals share no such input.
    const outputs = Object.keys(recipe.outputs); assert(outputs.every(output => after.definitions.recipes.filter(other => other.outputs[output]).length === 1), `Ambiguous live recipe output for material conservation: ${recipe.id}`);
    if (outputs.some(output => (made[output] || 0) > 0)) recipeInputs += recipe.inputs[item];
  }
  const projectCost = p.definition.constructionCosts[item] || 0;
  const expected = stock(before, item) + (made[item] || 0) - upkeep - recipeInputs - projectCost;
  assert.equal(stock(after, item), expected, `Actual per-period construction material was not conserved: ${spec.id}/${item}`);
  return { player: spec.name, period: after.city.lastPeriod, item, before: stock(before, item), produced: made[item] || 0, actualFacilityUpkeep: upkeep, successfulRecipeInputs: recipeInputs, actualProjectConstruction: projectCost, expected, after: stock(after, item), project: p, actualProduction: made };
}
function deliveryLedger(before, item) { return { item, start: stock(before, item), period: before.city.lastPeriod, regularNet: 0, observations: [] }; }
function deliveryCredit(ledger, s) {
  if (s.city.lastPeriod > ledger.period) {
    assert.equal(s.city.lastPeriod, ledger.period + 1, 'Missed a real city settlement while proving cargo quantity conservation');
    assert.equal(s.verified.length, 9, 'Continuous delivery ledger requires the nine real maintained facilities');
    const p = project(s); assert.equal(p.lastOperationPeriod, s.city.lastPeriod, 'Missing contemporaneous public-works maintenance watermark');
    const made = produced(s)[ledger.item] || 0;
    const facilityUpkeep = s.city.buildings.reduce((sum, building) => sum + (s.definitions.buildings[building.type].upkeep[ledger.item] || 0) * building.level, 0);
    assert(!s.definitions.recipes.some(recipe => (recipe.inputs[ledger.item] || 0) > 0), 'Selected delivery accounting material has unexpected recipe consumers');
    const worksUpkeep = p.lastOperationPaid ? p.definition.upkeepCosts[ledger.item] || 0 : 0;
    ledger.regularNet += made - facilityUpkeep - worksUpkeep; ledger.period = s.city.lastPeriod;
    ledger.observations.push({ period: ledger.period, item: ledger.item, made, facilityUpkeep, worksUpkeep, actualStock: stock(s, ledger.item), actualProduction: produced(s), projectPaid: p.lastOperationPaid });
  }
  return stock(s, ledger.item) - ledger.start - ledger.regularNet;
}

async function main() {
  if (process.argv.slice(2).includes('--self-test')) { assert.deepEqual(process.argv.slice(2), ['--self-test']); console.log(JSON.stringify(arithmeticChecks(), null, 2)); return; }
  const args = argumentsOf(process.argv.slice(2)); if (args.help) { process.stdout.write(HELP); return; } for (const key of ['java', 'runtime', 'paper']) assert(args[key], `Required --${key}\n${HELP}`);
  const options = { java: requireFile(args.java), paper: requireFile(args.paper), runtime: path.resolve(args.runtime), jvmArgs: ['-Xms128m', '-Xmx512m', '-Xss512k', '-XX:MaxMetaspaceSize=192m', '-XX:MaxDirectMemorySize=64m', '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=64m', '-Dio.netty.eventLoopThreads=2'] };
  assert.equal(fs.readFileSync(path.join(options.runtime, '.starworld-smoke-runtime'), 'utf8').trim(), 'StarWorld isolated Paper smoke runtime v1'); assert.equal(properties(path.join(options.runtime, 'eula.txt')).eula, 'true');
  const props = properties(path.join(options.runtime, 'server.properties')); assert.equal(props['server-ip'], '127.0.0.1'); assert.equal(props['online-mode'], 'false'); options.port = Number(props['server-port']); assert(Number.isInteger(options.port) && options.port >= 1024 && options.port <= 65535);
  const config = path.join(options.runtime, 'plugins', 'StarWorld'); assert.equal(configuredNumber(path.join(config, 'config.yml'), 'period-seconds'), 10); assert.equal(configuredNumber(path.join(config, 'config.yml'), 'event-chance'), 0);
  const requireClient = args['client-deps'] ? createRequire(path.resolve(args['client-deps'], 'package.json')) : require, mineflayer = requireClient('mineflayer');
  const directory = path.join(options.runtime, 'works-acceptance', new Date().toISOString().replace(/[:.]/g, '-')); fs.mkdirSync(directory, { recursive: true });
  const corePath = path.join(options.runtime, 'plugins', 'StarCore', 'config.yml');
  const coreConfig = fs.readFileSync(corePath, 'utf8');
  assert.match(coreConfig, /^[ \t]*new-nation-protection-days:[ \t]*0[ \t]*(?:#.*)?\r?$/m, 'Isolated continuous-world war fixture requires disabled new-nation protection');
  assert.match(coreConfig, /^[ \t]*declaration-cost:[ \t]*(['"]?)0(?:\.0+)?\1[ \t]*(?:#.*)?\r?$/m, 'Prepare isolated war declaration cost=0; this script never edits it');
  const evidence = { schema: 1, suite: 'real-paper-continuous-world-works', status: 'running', startedAt: new Date().toISOString(), minecraft: '1.21.11', node: process.version, mineflayer: requireClient('mineflayer/package.json').version, paperSha256: hash(options.paper), pluginJars: {}, configSha256: {}, steps: [], seeds: [], arithmeticUnits: arithmeticChecks(), actualSettlements: [], testTuning: { periodSeconds: 10, eventChance: 0, players: SPEC.map(s => s.name), forcedChunks: [180, 181, 212, 213, 244, 245], anchorBiome: 'plains', maxSequentialOwnedPaperStarts: 3, jvmArgs: options.jvmArgs, nodeExecArgv: process.execArgv, seededItemsAreProduction: false, injectedProfileOrFakeEvidence: false, coreWarMutations: 'public WarService via console-only fixture; declaration cost/protection preconfigured zero' } };
  for (const file of fs.readdirSync(path.join(options.runtime, 'plugins'))) if (file.endsWith('.jar')) evidence.pluginJars[file] = hash(path.join(options.runtime, 'plugins', file));
  for (const file of ['config.yml', 'definitions.yml', 'evolution.yml', 'exports.yml', 'logistics.yml', 'life.yml', 'market.yml', 'disaster-response.yml', 'public-works.yml', 'civic-needs.yml']) evidence.configSha256[file] = hash(path.join(config, file));
  if (fs.existsSync(path.join(config, 'climate.yml'))) evidence.configSha256['climate.yml'] = hash(path.join(config, 'climate.yml'));
  evidence.configSha256['StarCore/config.yml'] = hash(corePath);
  const save = () => fs.writeFileSync(path.join(directory, 'evidence.json'), JSON.stringify(evidence, null, 2) + '\n');
  const stage = async (name, work) => { const step = { name, status: 'running', startedAt: new Date().toISOString() }; evidence.steps.push(step); save(); try { step.observed = await work(); step.status = 'passed'; console.log(`[PASS] ${name}`); return step.observed; } catch (error) { step.status = 'failed'; step.error = error.stack; throw error; } finally { step.finishedAt = new Date().toISOString(); save(); } };
  let server = new Paper(options, directory, 1), bots = [], requests = {}, beforeRestart, maintenancePaused, routeId, orderId, disasterId, warSaved, deliveryProof;
  const closeClients = async () => { for (const bot of bots) { try { bot.quit(); await bot.finishTranscript(); } catch {} } bots = []; };
  const connectAll = async run => { for (const spec of SPEC) { const bot = await connect(mineflayer, { ...options, username: spec.name }, directory, `${run}-${spec.slot}`); bots.push(bot); server.command(`deop ${spec.name}`); } };
  const capture = (spec, s) => { evidence.actualSettlements.push({ player: spec.name, cityId: s.city.id, period: s.city.lastPeriod, actualProduction: produced(s), stock: s.city.stock, project: project(s), profile: s.development, core: s.core }); return s; };
  const resupply = async (bot, needs, purpose) => { const result = []; for (const [item, minimum, amount] of needs) { let before = await snapshot(server, bot); if (stock(before, item) >= minimum) { result.push({ item, needed: false, warehouse: stock(before, item) }); continue; } before = await fresh(server, bot); const seed = await fixture(server, 'works-resupply', bot, `${item} ${amount}`); evidence.seeds.push({ player: bot.username, purpose, ...seed }); await botMessage(bot, `/sw deposit ${item} ${amount}`, new RegExp(`已存入 ${amount} ${item}`)); const after = await snapshot(server, bot); assert.equal(after.city.lastPeriod, before.city.lastPeriod); assert.equal(stock(after, item), stock(before, item) + amount); result.push({ before, seed, after }); } return result; };
  try {
    await stage('one owned Paper and three non-operator real clients', async () => { await server.start(); await connectAll(1); return { clients: bots.map(b => ({ name: b.username, uuid: b.player.uuid })), serverRun: 1 }; });
    await stage('three real countries cities and independently verified national and city claims', async () => { const setup = []; for (const spec of SPEC) setup.push(await fixture(server, 'works-setup', bots[spec.slot], String(spec.slot))); assert.equal(new Set(setup.map(s => s.cityId)).size, 3); assert.equal(new Set(setup.map(s => s.nationId)).size, 3); assert(setup.every(s => s.dualTerritoryVerified && s.chunks.length === 2)); return setup; });
    await stage('explicit inventory seeds enter warehouses only through ordinary player deposit commands', async () => { const result = []; for (const spec of SPEC) { const bot = bots[spec.slot], baseline = await fixture(server, 'works-inventory', bot), seed = await fixture(server, 'works-seed', bot); evidence.seeds.push({ player: spec.name, ...seed }); for (const [item, amount] of Object.entries(seed.items)) await botMessage(bot, `/sw deposit ${item} ${amount}`, new RegExp(`已存入 ${amount} ${item}`)); const inventory = await fixture(server, 'works-inventory', bot); assert.deepEqual(inventory.items, baseline.items, 'Deposits must remove the seed while preserving unrelated inventory'); const s = await snapshot(server, bot); assert.equal(s.city.buildings.length, 0); for (const [item, amount] of Object.entries(seed.items)) assert.equal(stock(s, item), amount); assert.equal(s.projects.length, 0, 'Requires a fresh unused national project slot'); result.push({ seed, baseline, inventory, warehouse: s.city.stock }); } return result; });
    await stage('ordinary founder commands choose distinct civilizations and appropriate real plans', async () => { const result = []; for (const spec of SPEC) { const bot = bots[spec.slot]; await botMessage(bot, `/sw civilization ${spec.civilization}`, /国家文明已转型/); await botMessage(bot, `/sw plan ${spec.plan}`, /城市规划/); result.push(await snapshot(server, bot)); } return result; });
    await stage('twenty seven real raycast registrations preserve two-region city atlases', async () => { const result = []; for (const spec of SPEC) { const bot = bots[spec.slot], from = bot.messages.length; const dispatched = await fixture(server, 'works-register', bot, String(spec.slot)); await waitUntil(() => bot.messages.slice(from).filter(m => m.includes('已登记 ')).length === 9, 20000, 'nine successful real registrations'); const s = await poll(server, bot, s => s.city.buildings.length === 9 && s.atlas.regions.length === 2); assert(s.atlas.regions.every(r => r.terrain === 'plains')); result.push({ dispatched, snapshot: s }); } return result; });
    await stage('actual maintained academies earn every project knowledge and culture prerequisite', async () => { const observations = []; for (const spec of SPEC) { const s = await poll(server, bots[spec.slot], s => s.development.knowledge >= spec.knowledge && s.development.culture >= spec.culture && s.feasibility.find(f => f.definitionId === spec.id)?.eligible, 160000, `${spec.name} actual academy progression`); assert.equal(s.city.civilization, spec.civilization); assert.equal(s.development.plan, spec.plan); assert(s.development.knowledge > 0 && s.development.culture > 0); assert.deepEqual(s.worksDefinitions.projects[spec.id].multipliers, DEFAULT_FACTORS[spec.id]); observations.push(s); } return { profileWasSeeded: false, observations }; });
    await stage('real governance GUI terms and costs remain read-only and resist shift extraction', () => inspectGui(server, bots[0]));
    await stage('three player commands pay exact actual upfront costs and preserve stable request UUIDs', async () => { const baseline = await freshAll(server, bots); return Promise.all(SPEC.map(async spec => { const bot = bots[spec.slot], before = baseline[spec.slot], id = crypto.randomUUID(); requests[spec.slot] = id; await botMessage(bot, `/sw works start ${spec.id} ${id}`, /公共工程回执/); const after = await snapshot(server, bot); assert.equal(project(after).requestId, id); assert.equal(project(after).progress, 0); afterExactDebit(before, after, project(after).definition.upfrontCosts); assert.equal(records(after, 'started').length, 1); await botMessage(bot, `/sw works start ${spec.id} ${id}`, /公共工程回执/); const retryAfter = await snapshot(server, bot); afterExactDebit(after, retryAfter, {}); assert.deepEqual(frozen(project(retryAfter)), frozen(project(after))); return { before, after, retryAfter }; })); });
    await stage('a real maintained construction period conserves each projects warehouse materials exactly', async () => { const before = await Promise.all(bots.map(bot => snapshot(server, bot))); return Promise.all(SPEC.map(async spec => { const previous = before[spec.slot]; assert(project(previous).progress < spec.steps, 'Lost the observation window before project completion'); const after = await next(server, bots[spec.slot], previous); capture(spec, after); return constructionConservation(previous, after, spec); })); });
    await stage('a second new UUID cannot allocate or charge the same national city slot', async () => { const before = await fresh(server, bots[0]); await botMessage(bots[0], `/sw works start irrigation_network ${crypto.randomUUID()}`, /already allocated|national public works slot|工程槽/); const after = await snapshot(server, bots[0]); afterExactDebit(before, after, {}); assert.equal(after.projects.length, 1); assert.equal(records(after, 'started').length, 1); return { before, after }; });
    await stage('real national territory loss pauses an unfinished arsenal without paying construction again', async () => {
      const before = await fresh(server, bots[1]); assert(project(before).progress >= 1 && project(before).progress < 5, 'Lost unfinished arsenal observation window');
      const claim = await fixture(server, 'works-unclaim', bots[1], '1');
      // A prior authorized capture may already be queued. Observe a new post-claim settlement,
      // then prove successive genuinely paused periods never advance or charge construction.
      const paused = await fresh(server, bots[1]);
      assert(paused.verified.length < 9, 'National territory loss did not remove real facility authorization');
      assert(project(paused).progress < 5, 'Arsenal completed before a post-claim pause could be observed');
      assert(records(paused, 'construction_paused').some(h => h.period === paused.city.lastPeriod), 'No actual post-claim construction pause receipt');
      const later = await next(server, bots[1], paused);
      assert.equal(project(later).progress, project(paused).progress);
      assert.equal(project(later).lastBuildPeriod, project(paused).lastBuildPeriod);
      assert.equal(records(later, 'construction').length, records(paused, 'construction').length);
      assert(records(later, 'construction_paused').some(h => h.period === later.city.lastPeriod));
      assert.equal(claim.cityClaimPreserved, true); assert.equal(claim.nationalClaimPresent, false); return { before, claim, paused, later };
    });
    await stage('irrigation and culture complete exactly three and four separately paid maintained periods', async () => { const result = []; for (const spec of [SPEC[0], SPEC[2]]) { const s = await poll(server, bots[spec.slot], s => project(s).progress === spec.steps, 70000, 'completed real project'); result.push(checkConstruction(s, spec)); capture(spec, s); } return result; });
    await stage('first owned process cleanly flushes permanent project identities and paused construction', async () => { beforeRestart = []; for (const spec of SPEC) beforeRestart.push(await snapshot(server, bots[spec.slot])); await closeClients(); await server.stop(); return { cleanExit: true, beforeRestart }; });
    server = new Paper(options, directory, 2);
    await stage('second process restores all frozen terms and cannot catch up a lost-territory project', async () => { await server.start(); await connectAll(2); const restored = []; for (const spec of SPEC) { const s = await poll(server, bots[spec.slot], () => true); assert.deepEqual(frozen(project(s)), frozen(project(beforeRestart[spec.slot]))); assert.equal(records(s, 'started').length, 1); restored.push(s); } assert.equal(project(restored[1]).progress, project(beforeRestart[1]).progress); const later = await next(server, bots[1], restored[1]); assert.equal(project(later).progress, project(restored[1]).progress); return { restored, later }; });
    await stage('same original UUIDs replay after restart with no warehouse or project allocation charge', async () => { const result = []; for (const spec of SPEC) { const bot = bots[spec.slot], before = await fresh(server, bot); await botMessage(bot, `/sw works start ${spec.id} ${requests[spec.slot]}`, /公共工程回执/); const after = await snapshot(server, bot); afterExactDebit(before, after, {}); assert.equal(records(after, 'started').length, 1); result.push({ before, after }); } return result; });
    await stage('public national reclaim resumes the same arsenal and finishes its fifth paid step once', async () => { const restoredClaim = await fixture(server, 'works-reclaim', bots[1], '1'); const s = await poll(server, bots[1], s => project(s).progress === 5, 100000, 'reclaimed arsenal completes'); capture(SPEC[1], s); return { restoredClaim, ...checkConstruction(s, SPEC[1]) }; });
    await stage('all three funded completed projects pay only from following periods and record real production', async () => { const result = []; for (const spec of SPEC) { const s = await poll(server, bots[spec.slot], s => project(s).lastOperationPaid && project(s).lastOperationPeriod === s.city.lastPeriod && project(s).lastOperationPeriod > project(s).completedPeriod, 35000, 'actual funded next-period project operation'); const p = project(s); assert(records(s, 'operating').every(h => h.period > p.completedPeriod)); assert(Object.values(produced(s)).some(value => value > 0), 'Funded city produced no actual goods'); capture(spec, s); result.push({ spec, paidPeriod: p.lastOperationPeriod, completedPeriod: p.completedPeriod, realProduction: produced(s), stock: s.city.stock, exactProjectFactors: p.definition.multipliers, profile: s.development, core: s.core }); } return result; });
    await stage('real warehouse withdrawal proves an operating project cannot borrow that periods future iron output', async () => { const bot = bots[1], before = await fresh(server, bot), iron = stock(before, 'minecraft:iron_ingot'); assert(iron >= 1 && iron <= 2304, 'Expected withdrawable earned/seeded iron in actual arsenal warehouse'); await botMessage(bot, `/sw withdraw minecraft:iron_ingot ${iron}`, new RegExp(`已取出 ${iron} minecraft:iron_ingot`)); const emptied = await snapshot(server, bot); assert.equal(emptied.city.lastPeriod, before.city.lastPeriod); assert.equal(stock(emptied, 'minecraft:iron_ingot'), 0); const paused = await next(server, bot, emptied, s => project(s).lastOperationPeriod === s.city.lastPeriod, 25000); assert.equal(project(paused).lastOperationPaid, false, 'Operating project borrowed iron produced after its starting-stock gate'); assert(records(paused, 'operation_paused').some(h => h.period === paused.city.lastPeriod && /Insufficient operating stock/.test(h.detail)), 'Pause was not caused by real maintenance material shortage'); assert(paused.feasibility.find(f => f.definitionId === 'arsenal_district'), 'Missing read projection'); maintenancePaused = paused; capture(SPEC[1], paused); return { before, ironPhysicallyWithdrawn: iron, emptied, paused, ironProducedLaterInSamePeriod: produced(paused)['minecraft:iron_ingot'] || 0 }; });
    await stage('physical redeposit precedes the next funded operation without replaying allocation or construction', async () => { const bot = bots[1], before = await snapshot(server, bot); assert.equal(before.city.lastPeriod, maintenancePaused.city.lastPeriod, 'Missed the material-restoration observation window'); assert.equal(project(before).lastOperationPaid, false, 'Project already auto-recovered before physical restoration evidence'); const inventory = await fixture(server, 'works-inventory', bot), amount = Math.min(16, inventory.items['minecraft:iron_ingot'] || 0); assert(amount >= 1, 'Withdrawal did not deliver actual player iron'); await botMessage(bot, `/sw deposit minecraft:iron_ingot ${amount}`, new RegExp(`已存入 ${amount} minecraft:iron_ingot`)); const deposited = await snapshot(server, bot); assert.equal(deposited.city.lastPeriod, before.city.lastPeriod); assert.equal(stock(deposited, 'minecraft:iron_ingot'), stock(before, 'minecraft:iron_ingot') + amount); const resumed = await next(server, bot, deposited, s => project(s).lastOperationPaid && project(s).lastOperationPeriod === s.city.lastPeriod); assert.equal(project(resumed).progress, 5); assert.equal(records(resumed, 'completed').length, 1); assert.equal(records(resumed, 'started').length, 1); capture(SPEC[1], resumed); return { inventory, amount, before, deposited, resumed }; });
    await stage('final source-bound project histories preserve one allocation one completion and unique paid periods', async () => { const result = []; for (const spec of SPEC) { const s = await snapshot(server, bots[spec.slot]); checkConstruction(s, spec); const running = records(s, 'operating'); assert.equal(new Set(running.map(h => h.period)).size, running.length); assert.equal(s.projects.length, 1); result.push(capture(spec, s)); } return result; });
    await stage('the same developed countries provision continued real road transport without disguised production seeds', async () => { const result = []; for (const spec of [SPEC[0], SPEC[1]]) { const bot = bots[spec.slot]; result.push({ player: spec.name, funding: await resupply(bot, [['minecraft:bread',128,256],['minecraft:paper',64,128],['minecraft:oak_planks',64,128],['minecraft:iron_ingot',64,128],['minecraft:oak_log',64,128]], 'explicit resources for continuous-world transport and existing works operation') }); const from = bot.messages.length, node = await fixture(server, 'works-node', bot, String(spec.slot)); await waitUntil(() => bot.messages.slice(from).some(m => /已登记 ROAD/.test(m)), 15000, 'actual road-node command receipt'); const s = await poll(server, bot, s => s.nodes.length === 1); assert.equal(project(s).progress, spec.steps); result.push({ node, snapshot: s }); } return result; });
    await stage('ordinary route and bilateral escrow commands debit sixteen bread and eight paper exactly', async () => { const a = await fresh(server, bots[0]), b = await fresh(server, bots[1]); const routeText = await botMessage(bots[0], `/sw route create ${b.city.id} land ${a.nodes[0].id},${b.nodes[0].id}`, /商路 [0-9a-f-]{36} ·/); routeId = routeText.match(/商路 ([0-9a-f-]{36})/)[1]; const declared = await snapshot(server, bots[0]), route = declared.routes.find(r => r.id === routeId); assert(route && route.travelPeriods >= 3, 'Real default road journey must leave time to observe war blockade'); const beforeA = await fresh(server, bots[0]), offerText = await botMessage(bots[0], `/sw trade offer ${routeId} minecraft:bread=16 minecraft:paper=8`, /已托管报价 [0-9a-f-]{36}/); orderId = offerText.match(/已托管报价 ([0-9a-f-]{36})/)[1]; const escrowA = await snapshot(server, bots[0]); afterExactDebit(beforeA, escrowA, {'minecraft:bread':16}); assert.equal(order(escrowA, orderId).status, 'OFFERED'); const beforeB = await fresh(server, bots[1]); await botMessage(bots[1], `/sw trade accept ${orderId}`, /货单 .* IN_TRANSIT/); const escrowB = await snapshot(server, bots[1]); afterExactDebit(beforeB, escrowB, {'minecraft:paper':8}); assert.deepEqual(order(escrowB, orderId).offered, {'minecraft:bread':16}); assert.deepEqual(order(escrowB, orderId).requested, {'minecraft:paper':8}); assert.equal(tradeReceipts(escrowB, orderId).length, 0); return { source: 'warehouses funded by recorded player deposits plus separately recorded native production', route, beforeA, escrowA, beforeB, escrowB }; });
    await stage('real declared war blocks that moving physical cargo while existing public works keep their identities', async () => { const moving = await poll(server, bots[1], s => order(s,orderId)?.status === 'IN_TRANSIT' && order(s,orderId).progressedPeriods > 0 && order(s,orderId).progressedPeriods < order(s,orderId).travelPeriods, 35000, 'actual moving bilateral cargo'); const war = await fixture(server,'works-war',bots[0],bots[1].username); const paused = await poll(server,bots[1],s => order(s,orderId)?.status === 'PAUSED' && /战争|外交|war/i.test(order(s,orderId).reason),30000,'public-core war blockade'); const later = await poll(server,bots[1],s => order(s,orderId)?.status === 'PAUSED' && order(s,orderId).lastPeriod > order(paused,orderId).lastPeriod,25000,'another actual blocked transport period'); assert.equal(order(later,orderId).progressedPeriods,order(paused,orderId).progressedPeriods); assert.equal(tradeReceipts(later,orderId).length,0); assert.equal(project(later).progress,5); warSaved = { a:await snapshot(server,bots[0]), b:later }; return { moving, war, paused, later }; });
    await stage('the second owned process flushes the same world with a paid completed arsenal and war-blocked escrow', async () => { await closeClients(); await server.stop(); return { cleanExit:true, warSaved }; });
    server = new Paper(options,directory,3);
    await stage('third startup preserves war blockade exact cargo and all existing project allocation receipts', async () => { await server.start(); await connectAll(3); const restored = []; for (const spec of SPEC) { const s = await poll(server,bots[spec.slot],()=>true); assert.deepEqual(frozen(project(s)),frozen(project(beforeRestart[spec.slot]))); assert.equal(project(s).progress,spec.steps); assert.equal(records(s,'started').length,1); restored.push(s); } const b = restored[1]; assert.equal(order(b,orderId).status,'PAUSED'); assert.equal(order(b,orderId).progressedPeriods,order(warSaved.b,orderId).progressedPeriods); assert.deepEqual(order(b,orderId).offered,{'minecraft:bread':16}); assert.deepEqual(order(b,orderId).requested,{'minecraft:paper':8}); assert.equal(tradeReceipts(b,orderId).length,0); const later = await poll(server,bots[1],s => order(s,orderId)?.status === 'PAUSED' && order(s,orderId).lastPeriod > order(b,orderId).lastPeriod,25000,'blocked cargo remains paused after real restart'); assert.equal(order(later,orderId).progressedPeriods,order(b,orderId).progressedPeriods); return { restored, later }; });
    await stage('public peace delivers the same escrow once with exact physical cargo conservation over live city production', async () => { const beforeA = await fresh(server,bots[0]),beforeB = await fresh(server,bots[1]),paperLedger = deliveryLedger(beforeA,'minecraft:paper'),breadLedger = deliveryLedger(beforeB,'minecraft:bread'); const peace = await fixture(server,'works-peace',bots[0],bots[1].username); const deadline = Date.now()+100000; let a,b,paper,bread,receipt;
      while(Date.now()<deadline){a=await snapshot(server,bots[0]);b=await snapshot(server,bots[1]);if(!coherent(a)||!coherent(b)){await delay(150);continue;}paper=deliveryCredit(paperLedger,a);bread=deliveryCredit(breadLedger,b);assert([0,8].includes(paper),`Unexpected physical paper delivery/duplication: ${paper}`);assert([0,16].includes(bread),`Unexpected physical bread delivery/duplication: ${bread}`);const receipts=tradeReceipts(b,orderId);assert(receipts.length<=1,'Duplicate durable logistics receipts');if(order(b,orderId)?.status==='COMPLETED'&&paper===8&&bread===16&&receipts.length===1){receipt=receipts[0];break;}await delay(350);}
      assert(receipt,'Exact completed bilateral delivery was not observed');assert.equal(receipt.orderId,orderId);assert.equal(receipt.period,order(b,orderId).completedPeriod);assert.equal(receipt.originCity,beforeA.city.id);assert.equal(receipt.targetCity,beforeB.city.id);assert.equal(receipt.targetNation,beforeB.city.nationId);assert.deepEqual(receipt.offered,{'minecraft:bread':16});assert.deepEqual(receipt.requested,{'minecraft:paper':8});assert.equal(paper+bread,24);deliveryProof={beforeA,beforeB,peace,a,b,paperLedger,breadLedger,paperReceived:paper,breadReceived:bread,receipt};capture(SPEC[0],a);capture(SPEC[1],b);return deliveryProof; });
    await stage('another normal settlement cannot redeliver that completed cargo or recreate its receipt', async () => { const a0=await snapshot(server,bots[0]),b0=await snapshot(server,bots[1]),aLedger=deliveryLedger(a0,'minecraft:paper'),bLedger=deliveryLedger(b0,'minecraft:bread'),a=await next(server,bots[0],a0),b=await next(server,bots[1],b0);assert.equal(deliveryCredit(aLedger,a),0);assert.equal(deliveryCredit(bLedger,b),0);assert.equal(tradeReceipts(a,orderId).length,1);assert.equal(tradeReceipts(b,orderId).length,1);assert.equal(order(b,orderId).status,'COMPLETED');return{a0,b0,a,b,aLedger,bLedger}; });
    await stage('a real famine admin command precedes an ordinary import response paid with warehouse goods and the exact receipt', async () => { const bot=bots[1],funding=await resupply(bot,[['minecraft:bread',64,128],['minecraft:oak_planks',32,128],['minecraft:iron_ingot',32,64],['minecraft:paper',32,64]],'explicit continued existing-works upkeep and famine response ingredients');await fresh(server,bot);const trigger=await famineCommand(server,bot);assert.equal(trigger.directSimulationMutation,false);assert.equal(trigger.permissionBefore.admin,false);assert.equal(trigger.permissionDuring.admin,true);assert.equal(trigger.permissionAfter.admin,false);const before=await poll(server,bot,s=>s.city.event?.type==='famine',12000,'real player-command famine event');assert(before.currentPeriod<before.city.event.endPeriod,'Lost active famine response observation window');disasterId=crypto.randomUUID();await botMessage(bot,`/sw disaster respond famine_import ${disasterId}`,/救灾回执/);const after=await snapshot(server,bot),response=after.responses.find(r=>r.requestId===disasterId);assert(response,'No real persisted famine-import response');assert.equal(response.importReceipt,orderId);assert.equal(response.strategyId,'famine_import');assert.equal(response.steps,0);assert.equal(response.eventType,'famine');assert.equal(response.eventStart,after.city.event.startPeriod);assert.deepEqual(response.costs,{'minecraft:bread':8,'minecraft:oak_planks':4});afterExactDebit(before,after,response.costs);assert.equal(project(after).requestId,requests[1]);return{funding,trigger,deliveryReceipt:deliveryProof.receipt,before,after,response}; });
    await stage('one real settlement runs the existing public works and imported-food disaster recovery hooks together', async () => { const before=await snapshot(server,bots[1]),initial=before.responses.find(r=>r.requestId===disasterId),s=await poll(server,bots[1],s=>{const r=s.responses.find(r=>r.requestId===disasterId);return r&&r.steps>0&&r.lastAppliedPeriod===s.city.lastPeriod&&project(s).lastOperationPaid&&project(s).lastOperationPeriod===s.city.lastPeriod;},35000,'actual dual public-works and disaster hook settlement');const r=s.responses.find(r=>r.requestId===disasterId);assert(r.remainingBudget<initial.remainingBudget,'No bounded real recovery budget was consumed');assert.equal(r.importReceipt,orderId);assert.equal(s.disasterHistory.filter(h=>h.requestId===disasterId&&h.kind==='requested').length,1);assert(s.disasterHistory.some(h=>h.requestId===disasterId&&h.kind==='recovery'&&h.period===s.city.lastPeriod));assert.equal(records(s,'started').length,1);assert.equal(records(s,'completed').length,1);capture(SPEC[1],s);return{before,after:s,publicWorksPaidPeriod:project(s).lastOperationPeriod,disasterRecoveryPeriod:r.lastAppliedPeriod,actualProduction:produced(s),exactImportReceipt:deliveryProof.receipt}; });
    await stage('same-world final snapshots retain three original completed projects one trade receipt and one import response', async () => { const final=[];for(const spec of SPEC){const s=await snapshot(server,bots[spec.slot]);assert.equal(project(s).requestId,requests[spec.slot]);assert.equal(project(s).progress,spec.steps);assert.equal(records(s,'started').length,1);assert.equal(records(s,'completed').length,1);assert.equal(s.atlas.regions[0].worldId,beforeRestart[spec.slot].atlas.regions[0].worldId);final.push(capture(spec,s));}assert.equal(tradeReceipts(final[1],orderId).length,1);assert.equal(final[1].responses.filter(r=>r.requestId===disasterId).length,1);return final; });
    await stage('third and final owned Paper process stops cleanly without removing the continuous test world', async () => { await closeClients(); await server.stop(); return { cleanExit: true, preservedWorld: true }; });
    evidence.status = 'passed'; evidence.finishedAt = new Date().toISOString(); evidence.requestIds = requests; evidence.continuousWorldIds={routeId,orderId,disasterId};save(); console.log(`PASS ${evidence.steps.length} continuous world/works stages: ${directory}`);
  } catch (error) { evidence.status = 'failed'; evidence.error = error.stack; evidence.finishedAt = new Date().toISOString(); save(); console.error(`${error.stack}\nRecent owned server output:\n${server.lines.slice(-20).join('\n')}`); process.exitCode = 1; }
  finally { await closeClients(); if (server.child) try { await server.stop(); } catch (error) { evidence.status = 'failed'; evidence.cleanupError = error.stack; save(); process.exitCode = 1; } fs.writeFileSync(path.join(options.runtime, 'works-acceptance', 'latest.json'), JSON.stringify({ directory, status: evidence.status }, null, 2) + '\n'); }
}
module.exports = { arithmeticChecks };
if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1; });
