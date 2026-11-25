#!/usr/bin/env node
'use strict';

// Test helper only. No downloads, plugin configuration edits, EULA acceptance,
// database writes, or management of an existing/unrelated Java process.
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const crypto = require('node:crypto');
const os = require('node:os');
const v8 = require('node:v8');
const { execFile, execFileSync, spawn } = require('node:child_process');
const { createRequire } = require('node:module');
const assert = require('node:assert/strict');
const { setTimeout: delay } = require('node:timers/promises');

const RESOURCE_LIMITS = Object.freeze({
  minimumStartupFreeMemoryBytes: 1536 * 1024 * 1024,
  minimumStartupFreeCommitBytes: 2 * 1024 * 1024 * 1024,
  minimumStartupNonHeapReserveBytes: 1024 * 1024 * 1024,
  minimumRuntimeFreeMemoryBytes: 768 * 1024 * 1024,
  minimumRuntimeFreeCommitBytes: 768 * 1024 * 1024,
  maximumPaperHeapBytes: 768 * 1024 * 1024,
  maximumNodeHeapBytes: 512 * 1024 * 1024
});
const WINDOWS_COMMIT_QUERY_TIMEOUT_MS = 10000;
const WINDOWS_COMMIT_MEMORY_COMMAND = [
  "Add-Type -TypeDefinition @'",
  'using System;',
  'using System.Runtime.InteropServices;',
  'public static class StarWorldMemoryStatus {',
  '  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]',
  '  public class MEMORYSTATUSEX {',
  '    public uint dwLength;',
  '    public uint dwMemoryLoad;',
  '    public ulong ullTotalPhys;',
  '    public ulong ullAvailPhys;',
  '    public ulong ullTotalPageFile;',
  '    public ulong ullAvailPageFile;',
  '    public ulong ullTotalVirtual;',
  '    public ulong ullAvailVirtual;',
  '    public ulong ullAvailExtendedVirtual;',
  '    public MEMORYSTATUSEX() { dwLength = (uint)Marshal.SizeOf(typeof(MEMORYSTATUSEX)); }',
  '  }',
  '  [DllImport("kernel32.dll", SetLastError = true)]',
  '  public static extern bool GlobalMemoryStatusEx([In, Out] MEMORYSTATUSEX buffer);',
  '}',
  "'@",
  '$memory = [StarWorldMemoryStatus+MEMORYSTATUSEX]::new()',
  'if (![StarWorldMemoryStatus]::GlobalMemoryStatusEx($memory)) { throw "GlobalMemoryStatusEx failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }',
  '[math]::Floor($memory.ullAvailPageFile / 1024)'
].join('\n');

function freeCommitMemoryBytes() {
  if (process.platform !== 'win32') return Number.MAX_SAFE_INTEGER;
  const output = execFileSync('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command',
    WINDOWS_COMMIT_MEMORY_COMMAND],
  { encoding: 'utf8', timeout: WINDOWS_COMMIT_QUERY_TIMEOUT_MS, windowsHide: true }).trim();
  assert(/^\d+$/.test(output), 'Could not read available Windows commit memory');
  return Number(output) * 1024;
}

async function freeCommitMemoryBytesAsync(execute = execFile, platform = process.platform) {
  if (platform !== 'win32') return Promise.resolve(Number.MAX_SAFE_INTEGER);
  let lastError;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const stdout = await new Promise((resolve, reject) => {
        execute('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command',
          WINDOWS_COMMIT_MEMORY_COMMAND],
        { encoding: 'utf8', timeout: WINDOWS_COMMIT_QUERY_TIMEOUT_MS, windowsHide: true, maxBuffer: 1024 },
        (error, output) => error ? reject(error) : resolve(output));
      });
      const output = String(stdout).trim();
      if (!/^\d+$/.test(output)) throw new Error('Could not read available Windows commit memory');
      return Number(output) * 1024;
    } catch (error) {
      lastError = error;
      if (attempt === 0) await delay(250);
    }
  }
  throw new Error(`Could not read available Windows commit memory after two attempts: ${lastError.message}`, { cause: lastError });
}

const HELP = `Usage: node acceptance.cjs --java <java.exe> --runtime <owned-test-directory>
  --paper <local-paper.jar> [--client-deps <directory-with-mineflayer>]

Requires a disposable localhost Paper 1.21.11 runtime prepared separately:
  * .starworld-smoke-runtime contains "StarWorld isolated Paper smoke runtime v1"
  * eula=true already exists; server-ip=127.0.0.1; online-mode=false
  * real StarCore, StarWorld and tools/paper-fixture JARs are installed
  * StarWorld period-seconds=10, event-chance=0, fresh extension database
  * isolated drought duration=12 and aftermath=2 periods for active restart evidence
  * isolated StarCore nation/claim creation costs are zero (or player is funded)
The test creates a real offline player, country, city, claims and three facilities;
it changes only this disposable world/player through public commands and fixture APIs.
It starts/stops two child servers, verifies production, item transfer, GUI protection,
permissions, loss/recovery of national territory, events and persisted city recovery.
Evidence and complete process/client logs are written under runtime/client-acceptance.
Mineflayer is loaded locally or via --client-deps; nothing is installed automatically.
`;

function argumentsOf(argv) {
  const result = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--help' || argv[i] === '-h') { result.help = true; continue; }
    if (!['--java', '--runtime', '--paper', '--client-deps'].includes(argv[i])) throw new Error(`Unknown option: ${argv[i]}`);
    const key = argv[i].slice(2);
    if (!argv[i + 1] || argv[i + 1].startsWith('--')) throw new Error(`Missing value: ${argv[i]}`);
    if (result[key]) throw new Error(`Duplicate option: ${argv[i]}`);
    result[key] = argv[++i];
  }
  return result;
}

function properties(file) {
  const values = {};
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (/^\s*[#!]/.test(line)) continue;
    const match = line.match(/^\s*([^=:]+?)\s*[=:]\s*(.*?)\s*$/);
    if (match) values[match[1]] = match[2];
  }
  return values;
}

function plain(text) { return String(text).replace(/\x1b\[[0-9;]*m/g, '').replace(/§[0-9a-fk-or]/gi, ''); }
function hash(file) { return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'); }
function count(bot, name) { return bot.inventory.items().filter(item => item.name === name).reduce((n, item) => n + item.count, 0); }
function requireFile(file) { const full = path.resolve(file); assert(fs.statSync(full).isFile(), `Not a file: ${full}`); return full; }
function configuredNumber(file, key) {
  const text = fs.readFileSync(file, 'utf8');
  const matches = [...text.matchAll(new RegExp(`^\\s*${key}:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:#.*)?$`, 'gm'))];
  assert.equal(matches.length, 1, `Expected exactly one numeric ${key} in ${file}`);
  return Number(matches[0][1]);
}
function eventState(value) {
  assert.equal(typeof value, 'string', 'Expected a persisted event state');
  const match = value.match(/^EventState\[type=([a-z0-9_-]+), startPeriod=(\d+), endPeriod=(\d+), aftermathEndPeriod=(\d+)\]$/);
  assert(match, `Unrecognized event state: ${value}`);
  return { type: match[1], startPeriod: Number(match[2]), endPeriod: Number(match[3]), aftermathEndPeriod: Number(match[4]) };
}
function inventoryCount(snapshot, type, named) {
  return snapshot.items.filter(item => item.type === type && (named === undefined || item.named === named)).reduce((sum, item) => sum + item.amount, 0);
}

async function portIsFree(port) {
  const server = net.createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  await new Promise(resolve => server.close(resolve));
}

async function waitUntil(condition, timeout, description, check) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (check) check();
    const value = await condition();
    if (value) return value;
    await delay(100);
  }
  throw new Error(`Timeout: ${description}`);
}

async function withTimeout(promise, timeout, description) {
  let timer;
  try {
    return await Promise.race([promise, new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new Error(`Timeout: ${description}`)), timeout);
    })]);
  } finally { clearTimeout(timer); }
}

function assertResourceHeadroom(freeMemoryBytes = os.freemem(), nodeHeapLimitBytes = v8.getHeapStatistics().heap_size_limit,
                               freeCommitBytes = freeCommitMemoryBytes()) {
  assert(Number.isSafeInteger(freeMemoryBytes) && freeMemoryBytes >= 0, 'Invalid free system memory measurement');
  assert(Number.isSafeInteger(nodeHeapLimitBytes) && nodeHeapLimitBytes > 0, 'Invalid Node heap limit');
  assert(Number.isSafeInteger(freeCommitBytes) && freeCommitBytes >= 0, 'Invalid free commit memory measurement');
  assert(freeMemoryBytes >= RESOURCE_LIMITS.minimumStartupFreeMemoryBytes,
    `Paper acceptance requires at least 1536 MiB free physical memory before startup; got ${Math.floor(freeMemoryBytes / 1024 / 1024)} MiB`);
  assert(freeCommitBytes >= RESOURCE_LIMITS.minimumStartupFreeCommitBytes,
    `Paper acceptance requires at least 2048 MiB free commit memory before startup; got ${Math.floor(freeCommitBytes / 1024 / 1024)} MiB`);
  assert(nodeHeapLimitBytes <= RESOURCE_LIMITS.maximumNodeHeapBytes,
    `Node heap limit must be at most 512 MiB; got ${nodeHeapLimitBytes} bytes. Start with --max-old-space-size=384`);
  return {
    minimumStartupFreeMemoryBytes: RESOURCE_LIMITS.minimumStartupFreeMemoryBytes,
    minimumStartupFreeCommitBytes: RESOURCE_LIMITS.minimumStartupFreeCommitBytes,
    minimumRuntimeFreeMemoryBytes: RESOURCE_LIMITS.minimumRuntimeFreeMemoryBytes,
    startupFreeMemoryBytes: freeMemoryBytes,
    startupFreeCommitBytes: freeCommitBytes,
    nodeHeapLimitBytes
  };
}

function paperHeapLimitBytes(jvmArgs) {
  assert(Array.isArray(jvmArgs), 'Paper JVM arguments must be an array');
  const matches = jvmArgs.filter(argument => typeof argument === 'string')
    .map(argument => argument.match(/^-Xmx([1-9][0-9]*)([mMgG])$/))
    .filter(Boolean);
  assert.equal(matches.length, 1, 'Paper JVM arguments must set exactly one explicit -Xmx limit');
  const [, amount, unit] = matches[0];
  const limit = Number(amount) * (unit.toLowerCase() === 'm' ? 1024 * 1024 : 1024 * 1024 * 1024);
  assert(Number.isSafeInteger(limit) && limit <= RESOURCE_LIMITS.maximumPaperHeapBytes,
    'Paper maximum heap must not exceed 768 MiB');
  return limit;
}

function assertPaperLaunchLimits(jvmArgs, javaEnvironment = process.env, freeMemoryBytes = os.freemem(),
                                 nodeHeapLimitBytes = v8.getHeapStatistics().heap_size_limit,
                                 freeCommitBytes = freeCommitMemoryBytes()) {
  for (const name of ['_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'])
    assert(!javaEnvironment[name], `Unexpected JVM option injection: ${name}`);
  const paperHeapBytes = paperHeapLimitBytes(jvmArgs);
  const headroom = assertResourceHeadroom(freeMemoryBytes, nodeHeapLimitBytes, freeCommitBytes);
  const reservedBytes = paperHeapBytes + nodeHeapLimitBytes + RESOURCE_LIMITS.minimumStartupNonHeapReserveBytes
    + RESOURCE_LIMITS.minimumRuntimeFreeMemoryBytes;
  const requiredStartupFreeMemoryBytes = Math.max(headroom.minimumStartupFreeMemoryBytes, reservedBytes);
  const requiredStartupFreeCommitBytes = Math.max(headroom.minimumStartupFreeCommitBytes, reservedBytes);
  assert(headroom.startupFreeMemoryBytes >= requiredStartupFreeMemoryBytes,
    `Paper acceptance requires ${Math.ceil(requiredStartupFreeMemoryBytes / 1024 / 1024)} MiB free physical memory for its JVM/Node heaps, non-heap reserve and runtime reserve; got ${Math.floor(headroom.startupFreeMemoryBytes / 1024 / 1024)} MiB`);
  assert(headroom.startupFreeCommitBytes >= requiredStartupFreeCommitBytes,
    `Paper acceptance requires ${Math.ceil(requiredStartupFreeCommitBytes / 1024 / 1024)} MiB free commit memory for its JVM/Node heaps, non-heap reserve and runtime reserve; got ${Math.floor(headroom.startupFreeCommitBytes / 1024 / 1024)} MiB`);
  return { ...headroom, paperMaximumHeapBytes: paperHeapBytes,
    minimumStartupNonHeapReserveBytes: RESOURCE_LIMITS.minimumStartupNonHeapReserveBytes,
    requiredStartupFreeMemoryBytes, requiredStartupFreeCommitBytes };
}

class Paper {
  constructor(options, directory, run) {
    this.options = options; this.directory = directory; this.run = run;
    this.lines = []; this.buffers = { stdout: '', stderr: '' }; this.stopping = false;
    this.child = null; this.exit = null; this.error = null; this.out = null;
    this.stopCommandSent = false; this.memoryMonitor = null; this.resourceStopTimer = null; this.resourcePreflight = null;
    this.resourceStopForced = false;
    this.lastCommitCheck = Date.now(); this.commitCheckPromise = null;
  }
  async start() {
    const jvmArgs = this.options.jvmArgs || ['-Xms128m', '-Xmx512m', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC'];
    const runner = requireFile(process.argv[1]);
    this.resourcePreflight = {
      ...assertPaperLaunchLimits(jvmArgs),
      minimumRuntimeFreeCommitBytes: RESOURCE_LIMITS.minimumRuntimeFreeCommitBytes,
      runnerSha256: hash(runner),
      acceptanceHelperSha256: hash(__filename),
      jvmArgs,
      nodeExecArgv: process.execArgv
    };
    this.minimumObservedFreeMemoryBytes = this.resourcePreflight.startupFreeMemoryBytes;
    this.minimumObservedFreeCommitBytes = this.resourcePreflight.startupFreeCommitBytes;
    await portIsFree(this.options.port);
    this.out = fs.createWriteStream(path.join(this.directory, `paper-run-${this.run}.log`), { encoding: 'utf8' });
    this.out.write(`STARWORLD_RESOURCE_PREFLIGHT ${JSON.stringify(this.resourcePreflight)}\n`);
    this.child = spawn(this.options.java, [...jvmArgs,
      '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Dterminal.jline=false',
      '-Dterminal.ansi=false', '-jar', this.options.paper, '--nogui'], {
      cwd: this.options.runtime, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true
    });
    this.exited = new Promise(resolve => {
      this.child.once('error', error => { this.error = error; resolve(); });
      this.child.once('exit', (code, signal) => { this.exit = { code, signal }; });
      this.child.once('close', resolve);
    });
    for (const stream of ['stdout', 'stderr']) {
      this.child[stream].setEncoding('utf8');
      this.child[stream].on('data', data => {
        this.out.write(data);
        this.buffers[stream] += data;
        const rows = this.buffers[stream].split(/\r?\n/); this.buffers[stream] = rows.pop();
        for (const row of rows) this.lines.push(plain(row));
      });
    }
    this.lastCommitCheck = Date.now();
    this.memoryMonitor = setInterval(() => {
      const freeMemoryBytes = os.freemem();
      this.minimumObservedFreeMemoryBytes = Math.min(this.minimumObservedFreeMemoryBytes, freeMemoryBytes);
      if (this.stopping) return;
      if (freeMemoryBytes < RESOURCE_LIMITS.minimumRuntimeFreeMemoryBytes) {
        this.stopForResourceFailure(new Error(`Paper acceptance stopped because free physical memory fell below 768 MiB; got ${Math.floor(freeMemoryBytes / 1024 / 1024)} MiB`));
        return;
      }
      if (Date.now() - this.lastCommitCheck < 5000 || this.commitCheckPromise) return;
      this.lastCommitCheck = Date.now();
      this.commitCheckPromise = freeCommitMemoryBytesAsync()
        .then(freeCommitBytes => {
          this.minimumObservedFreeCommitBytes = Math.min(this.minimumObservedFreeCommitBytes, freeCommitBytes);
          if (!this.stopping && freeCommitBytes < RESOURCE_LIMITS.minimumRuntimeFreeCommitBytes)
            this.stopForResourceFailure(new Error(`Paper acceptance stopped because free commit memory fell below 768 MiB; got ${Math.floor(freeCommitBytes / 1024 / 1024)} MiB`));
        })
        .catch(error => {
          if (!this.stopping)
            this.stopForResourceFailure(new Error(`Paper acceptance could not recheck Windows commit memory: ${error.message}`));
        })
        .finally(() => { this.commitCheckPromise = null; });
    }, 1000);
    this.memoryMonitor.unref();
    await this.wait(/Done \(/, 0, 240000);
    assert(this.lines.some(line => /StarWorld ready:/.test(line)), 'StarWorld did not enable before Paper became ready');
    assert(this.lines.some(line => /StarWorldFixture|StarWorldPaperFixture/.test(line)), 'Real integration fixture was not loaded');
    this.command('starworld status');
    await this.wait(/StarCore.*公共 API 已连接/, 0, 15000);
  }
  stopForResourceFailure(error) {
    if (this.stopping) return;
    this.error = error;
    this.stopping = true;
    this.out.write(`STARWORLD_RESOURCE_STOP ${JSON.stringify({ error: error.message, at: new Date().toISOString() })}\n`);
    if (!this.child || this.exit) return;
    if (!this.lines.some(line => /Done \(/.test(line)) || !this.child.stdin.writable) {
      this.resourceStopForced = true;
      this.child.kill('SIGKILL');
      return;
    }
    try {
      this.child.stdin.write('stop\n');
      this.stopCommandSent = true;
      this.resourceStopTimer = setTimeout(() => {
        if (!this.exit) { this.resourceStopForced = true; this.child.kill('SIGKILL'); }
      }, 5000);
      this.resourceStopTimer.unref();
    } catch { this.resourceStopForced = true; this.child.kill('SIGKILL'); }
  }
  check() {
    if (this.error) throw this.error;
    if (this.exit && !this.stopping) throw new Error(`Paper exited unexpectedly: ${JSON.stringify(this.exit)}\n${this.lines.slice(-15).join('\n')}`);
  }
  command(command) {
    this.check(); assert(this.child && this.child.stdin.writable, 'Paper console is unavailable');
    this.child.stdin.write(command + '\n');
  }
  async wait(pattern, from = this.lines.length, timeout = 15000) {
    return waitUntil(() => {
      const recent = this.lines.slice(from);
      const failure = recent.find(line => /SW_FIXTURE_FAILED|StarWorld startup failed|Error occurred while enabling StarWorld/.test(line));
      if (failure) throw new Error(failure + '\n' + recent.slice(-12).join('\n'));
      return recent.find(line => pattern.test(line));
    }, timeout, `server log ${pattern}`, () => this.check());
  }
  async fixture(action) {
    const from = this.lines.length; this.command(`swfixture ${action} StarWorldQA`);
    return this.wait(new RegExp(`SW_FIXTURE_${action.toUpperCase()}(?: |$)`), from);
  }
  async snapshot() {
    const from = this.lines.length; this.command('swfixture verify StarWorldQA');
    const raw = await this.wait(/SW_FIXTURE_JSON .*"action":"verify"/, from);
    const value = JSON.parse(raw.slice(raw.indexOf('SW_FIXTURE_JSON ') + 'SW_FIXTURE_JSON '.length));
    assert.match(value.cityId, /^[0-9a-f-]{36}$/i); assert(Number.isSafeInteger(value.lastPeriod), 'Invalid last period');
    assert(Number.isInteger(value.buildingCount) && value.stock && typeof value.stock === 'object', 'Invalid fixture state');
    for (const [item, amount] of Object.entries(value.stock)) assert(Number.isSafeInteger(amount) && amount >= 0, `Invalid stock: ${item}`);
    return { cityId: value.cityId, lastPeriod: value.lastPeriod, stock: value.stock, buildings: value.buildingCount,
      event: value.event, lastReport: value.lastReport, raw };
  }
  async inventory(action = 'inventory') {
    const from = this.lines.length; this.command(`swfixture ${action} StarWorldQA`);
    const raw = await this.wait(new RegExp(`SW_FIXTURE_JSON .*"action":"${action}"`), from);
    return JSON.parse(raw.slice(raw.indexOf('SW_FIXTURE_JSON ') + 'SW_FIXTURE_JSON '.length));
  }
  async finishResourceLog() {
    if (this.commitCheckPromise) await this.commitCheckPromise;
    if (!this.out) return;
    this.out.write(`STARWORLD_RESOURCE_SUMMARY ${JSON.stringify({ minimumObservedFreeMemoryBytes: this.minimumObservedFreeMemoryBytes,
      minimumObservedFreeCommitBytes: this.minimumObservedFreeCommitBytes })}\n`);
    await new Promise(resolve => this.out.end(resolve));
    this.out = null;
  }
  async stop() {
    if (!this.child) return;
    if (this.memoryMonitor) { clearInterval(this.memoryMonitor); this.memoryMonitor = null; }
    this.stopping = true;
    if (!this.error && !this.exit && !this.stopCommandSent && this.child.stdin.writable) {
      this.child.stdin.write('stop\n');
      this.stopCommandSent = true;
    }
    try { await withTimeout(this.exited, this.options.stopTimeoutMs ?? 45000, 'owned Paper clean shutdown'); }
    catch (timeout) {
      // This is only the process spawned above; never inspect/kill another server.
      this.resourceStopForced = true;
      const child = this.child;
      child.kill('SIGKILL'); await this.exited;
      this.child = null;
      await this.finishResourceLog();
      throw new Error(`Owned Paper child did not stop cleanly: PID ${child.pid}`);
    } finally {
      if (this.resourceStopTimer) { clearTimeout(this.resourceStopTimer); this.resourceStopTimer = null; }
    }
    await this.finishResourceLog();
    const child = this.child;
    this.child = null;
    if (this.resourceStopForced) {
      const error = new Error(`Owned Paper child was force-terminated: PID ${child.pid}`);
      if (this.error) error.cause = this.error;
      throw error;
    }
    if (this.error) throw this.error;
    assert(this.exit && this.exit.code === 0, `Paper did not exit successfully: ${JSON.stringify(this.exit)}`);
    assert(!this.lines.some(line => /Could not flush StarWorld storage/.test(line)), 'StarWorld did not flush storage');
  }
}

async function connect(mineflayer, options, directory, run) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: options.port, username: options.username || 'StarWorldQA', auth: 'offline', version: '1.21.11',
    checkTimeoutInterval: 60000, hideErrors: true });
  bot.messages = []; bot.failure = null;
  const transcript = fs.createWriteStream(path.join(directory, `client-run-${run}.log`), { encoding: 'utf8' });
  const writeTranscript = text => { if (!transcript.writableEnded && !transcript.destroyed) transcript.write(text); };
  bot.on('messagestr', text => { const line = plain(text); bot.messages.push(line); writeTranscript(`${new Date().toISOString()} ${line}\n`); });
  bot.on('error', error => { bot.failure = error; writeTranscript(`ERROR ${error.stack}\n`); });
  bot.on('kicked', reason => { bot.failure = new Error(`Client kicked: ${JSON.stringify(reason)}`); writeTranscript(`${bot.failure.message}\n`); });
  bot.finishTranscript = () => new Promise(resolve => transcript.end(resolve));
  try { await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Protocol client did not spawn in 45 seconds')), 45000);
    bot.once('spawn', () => { clearTimeout(timer); bot.physicsEnabled = false; resolve(); });
    bot.once('error', error => { clearTimeout(timer); reject(error); });
    bot.once('kicked', reason => { clearTimeout(timer); reject(new Error(`Client kicked before spawn: ${JSON.stringify(reason)}`)); });
  }); } catch (error) { bot.end('client connection failed'); await bot.finishTranscript(); throw error; }
  return bot;
}

async function botMessage(bot, command, pattern) {
  const from = bot.messages.length; bot.chat(command);
  return waitUntil(() => bot.messages.slice(from).find(line => pattern.test(line)), 15000, `player reply ${pattern}`, () => { if (bot.failure) throw bot.failure; });
}

async function pollSnapshot(server, predicate, timeout, description) {
  const deadline = Date.now() + timeout; let latest;
  while (Date.now() < deadline) {
    latest = await server.snapshot(); if (predicate(latest)) return latest; await delay(2500);
  }
  throw new Error(`Timeout: ${description}; latest ${JSON.stringify(latest)}`);
}

async function main() {
  const args = argumentsOf(process.argv.slice(2));
  if (args.help) { process.stdout.write(HELP); return; }
  for (const name of ['java', 'runtime', 'paper']) assert(args[name], `Required: --${name}\n${HELP}`);
  const options = { java: requireFile(args.java), paper: requireFile(args.paper), runtime: path.resolve(args.runtime),
    jvmArgs: ['-Xms128m', '-Xmx512m', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC'] };
  assert.equal(fs.readFileSync(path.join(options.runtime, '.starworld-smoke-runtime'), 'utf8').trim(), 'StarWorld isolated Paper smoke runtime v1', 'Refusing an unowned runtime');
  assert.equal(properties(path.join(options.runtime, 'eula.txt')).eula, 'true', 'Accept the EULA separately; this script will not alter it');
  const config = properties(path.join(options.runtime, 'server.properties'));
  assert.equal(config['server-ip'], '127.0.0.1', 'Test server must explicitly bind localhost');
  assert.equal(config['online-mode'], 'false', 'Disposable offline protocol test requires online-mode=false');
  options.port = Number(config['server-port']); assert(Number.isInteger(options.port) && options.port >= 1024 && options.port <= 65535, 'Invalid server port');
  const worldConfig = path.join(options.runtime, 'plugins', 'StarWorld', 'config.yml');
  const worldDefinitions = path.join(options.runtime, 'plugins', 'StarWorld', 'definitions.yml');
  options.periodSeconds = configuredNumber(worldConfig, 'period-seconds');
  const eventChance = configuredNumber(worldConfig, 'event-chance');
  assert.equal(options.periodSeconds, 10, 'Acceptance timing requires an isolated 10-second production period');
  assert.equal(eventChance, 0, 'Acceptance requires random events disabled to isolate explicit event/territory observations');
  const localRequire = args['client-deps'] ? createRequire(path.resolve(args['client-deps'], 'package.json')) : require;
  const mineflayer = localRequire('mineflayer');
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const directory = path.join(options.runtime, 'client-acceptance', stamp); fs.mkdirSync(directory, { recursive: true });
  const evidence = { schema: 1, startedAt: new Date().toISOString(), status: 'running', node: process.version,
    mineflayer: localRequire('mineflayer/package.json').version, minecraft: '1.21.11', runtime: options.runtime,
    paperSha256: hash(options.paper), pluginJars: {},
    testTuning: { periodSeconds: options.periodSeconds, eventChance, paperJvmArgs: options.jvmArgs,
      configSha256: hash(worldConfig), definitionsSha256: hash(worldDefinitions),
      nodeVersion: process.version, nodeExecArgv: process.execArgv, javaOptionsEnvironment: process.env._JAVA_OPTIONS || '' },
    steps: [], snapshots: [] };
  for (const file of fs.readdirSync(path.join(options.runtime, 'plugins'))) if (/\.jar$/i.test(file)) evidence.pluginJars[file] = hash(path.join(options.runtime, 'plugins', file));
  const save = () => fs.writeFileSync(path.join(directory, 'evidence.json'), JSON.stringify(evidence, null, 2) + '\n');
  const stage = async (name, work) => {
    const step = { name, startedAt: new Date().toISOString(), status: 'running' }; evidence.steps.push(step); save();
    try { step.observed = await work(); step.status = 'passed'; step.finishedAt = new Date().toISOString(); save(); console.log(`[PASS] ${name}`); return step.observed; }
    catch (error) { step.status = 'failed'; step.error = error.stack; save(); throw error; }
  };
  let server = new Paper(options, directory, 1), bot = null, preRestart = null;
  try {
    await stage('real Paper, StarCore and StarWorld startup', async () => { await server.start(); return { loaded: true, serverPid: server.child.pid }; });
    await stage('real offline player login', async () => { bot = await connect(mineflayer, options, directory, 1); return { uuid: bot.player.uuid, username: bot.username }; });
    server.command('deop StarWorldQA');
    await stage('public StarCore country, city and dual claims', () => server.fixture('setup'));
    await stage('three real raycast facility registrations', async () => {
      const from = bot.messages.length, lines = server.lines.length; server.command('swfixture register StarWorldQA');
      await server.wait(/SW_FIXTURE_REGISTER_DISPATCHED/, lines);
      await waitUntil(() => bot.messages.slice(from).filter(line => line.includes('已登记 ')).length === 3, 20000, 'three successful registration messages', () => { server.check(); if (bot.failure) throw bot.failure; });
      const snapshot = await server.snapshot(); assert.equal(snapshot.buildings, 3, 'Expected exactly three persisted facilities'); evidence.snapshots.push(snapshot); return snapshot;
    });
    await stage('food, timber and military supply chains actually produce', async () => {
      const snapshot = await pollSnapshot(server, s => (s.stock['minecraft:arrow'] || 0) >= 4 && (s.stock['minecraft:wheat'] || 0) > 0 && (s.stock['minecraft:oak_planks'] || 0) > 0,
        90000, 'real chained production'); evidence.snapshots.push(snapshot); return snapshot;
    });
    await stage('real arrow withdrawal and deposit conserve player items', async () => {
      const initial = count(bot, 'arrow'); assert.equal(initial, 0, 'Test player must start without arrows');
      await botMessage(bot, '/sw withdraw minecraft:arrow 4', /已取出 4 minecraft:arrow/);
      await waitUntil(() => count(bot, 'arrow') === 4, 5000, 'four arrows delivered to actual client inventory');
      await botMessage(bot, '/sw deposit minecraft:arrow 4', /已存入 4 minecraft:arrow/);
      await waitUntil(() => count(bot, 'arrow') === 0, 5000, 'deposited arrows removed from client inventory');
      return { before: initial, delivered: 4, afterDeposit: count(bot, 'arrow') };
    });
    await stage('GUI shift click cannot extract display items', async () => {
      const windowPromise = new Promise((resolve, reject) => { const timer = setTimeout(() => reject(new Error('City GUI did not open')), 15000); bot.once('windowOpen', window => { clearTimeout(timer); resolve(window); }); });
      bot.chat('/sw'); const window = await windowPromise;
      assert.equal(window.slots[4]?.name, 'beacon', 'City info display item must occupy slot 4'); const before = count(bot, 'beacon');
      await withTimeout(bot.clickWindow(4, 0, 1), 10000, 'shift-click transaction');
      await delay(1000); assert.equal(count(bot, 'beacon'), before, 'Shift click leaked a display beacon');
      assert.equal(bot.currentWindow?.slots[4]?.name, 'beacon', 'Server did not restore cancelled display slot');
      const title = window.title; bot.closeWindow(window); return { title, beaconCountBefore: before, beaconCountAfter: count(bot, 'beacon') };
    });
    await stage('ordinary founder cannot trigger administrator events', () => botMessage(bot, '/sw event drought', /需要管理员权限/));
    await stage('special named items survive real warehouse deposits', async () => {
      const seeded = await server.inventory('seed');
      const wheat = (value, named) => value.items.filter(item => item.type === 'minecraft:wheat' && item.named === named).reduce((sum, item) => sum + item.amount, 0);
      assert.equal(wheat(seeded, false), 16, 'Fixture must give exactly sixteen ordinary wheat');
      assert.equal(wheat(seeded, true), 7, 'Fixture must give exactly seven protected named wheat');
      await botMessage(bot, '/sw deposit minecraft:wheat 17', /背包中的普通物品不足/);
      const rejected = await server.inventory(); assert.equal(wheat(rejected, false), 16); assert.equal(wheat(rejected, true), 7);
      await botMessage(bot, '/sw deposit minecraft:wheat 16', /已存入 16 minecraft:wheat/);
      const deposited = await server.inventory(); assert.equal(wheat(deposited, false), 0); assert.equal(wheat(deposited, true), 7);
      return { seeded, rejected, deposited };
    });
    await stage('lost national territory halts military production', async () => {
      await server.fixture('unclaim');
      const first = await pollSnapshot(server, s => s.lastReport && /领土\/建筑失效/.test(s.lastReport), 35000, 'lost territory stop report');
      const after = await pollSnapshot(server, s => s.lastPeriod >= first.lastPeriod + 2, 40000, 'two actual settlement periods after lost territory');
      assert.equal(after.stock['minecraft:arrow'] || 0, first.stock['minecraft:arrow'] || 0, 'Workshop continued producing arrows without national territory');
      evidence.snapshots.push(first, after); return { first, after };
    });
    await stage('reclaimed territory resumes military production', async () => {
      const before = await server.snapshot(); await server.fixture('reclaim');
      const after = await pollSnapshot(server, s => (s.stock['minecraft:arrow'] || 0) > (before.stock['minecraft:arrow'] || 0), 65000, 'military production after reclaim');
      evidence.snapshots.push(after); return { before, after };
    });
    await stage('administrator world event is actually persisted', async () => {
      server.command('op StarWorldQA'); await delay(250);
      await botMessage(bot, '/sw event drought', /事件已触发：drought/);
      const snapshot = await server.snapshot(); assert(/type=drought/.test(snapshot.event || ''), 'Drought is absent from persisted API snapshot');
      const event = eventState(snapshot.event);
      assert.equal(event.endPeriod - event.startPeriod, 12, 'Isolated drought must be configured for twelve periods to survive the restart observation');
      assert.equal(event.aftermathEndPeriod - event.endPeriod, 2, 'Isolated drought aftermath must remain two periods');
      evidence.testTuning.droughtDurationPeriods = event.endPeriod - event.startPeriod;
      evidence.testTuning.droughtAftermathPeriods = event.aftermathEndPeriod - event.endPeriod;
      preRestart = snapshot; evidence.snapshots.push(snapshot); return snapshot;
    });
    await stage('first server shuts down and flushes extension state', async () => {
      bot.end('acceptance stage complete'); await bot.finishTranscript(); bot = null; await server.stop(); return { cleanExit: true };
    });
    server = new Paper(options, directory, 2);
    await stage('same world and real core city recover after restart', async () => {
      await server.start(); bot = await connect(mineflayer, options, directory, 2); const snapshot = await server.snapshot();
      assert.equal(snapshot.cityId, preRestart.cityId, 'Restart changed or lost the real core city');
      assert.equal(snapshot.buildings, 3, 'Restart lost persisted facilities');
      for (const item of Object.keys(preRestart.stock)) assert((snapshot.stock[item] || 0) >= 0, `Invalid restored stock: ${item}`);
      assert((snapshot.stock['minecraft:arrow'] || 0) >= (preRestart.stock['minecraft:arrow'] || 0), 'Restart lost produced military inventory');
      assert(snapshot.lastPeriod >= preRestart.lastPeriod, 'Restart lost the settlement receipt');
      const beforeEvent = eventState(preRestart.event), afterEvent = eventState(snapshot.event);
      assert.deepEqual(afterEvent, beforeEvent, 'Restart changed the persisted event identity or timeline');
      const currentPeriod = Math.floor(Date.now() / 1000 / options.periodSeconds);
      assert(currentPeriod < afterEvent.endPeriod, 'Restart observation occurred after the event expired; active-event restoration is unproven');
      const inventory = await server.inventory();
      assert.equal(inventoryCount(inventory, 'minecraft:arrow'), 0, 'Restart duplicated deposited arrows in the actual player inventory');
      assert.equal(inventoryCount(inventory, 'minecraft:wheat', true), 7, 'Restart lost protected named wheat');
      assert.equal(inventoryCount(inventory, 'minecraft:wheat', false), 0, 'Restart restored already deposited ordinary wheat');
      assert.equal(count(bot, 'arrow'), 0, 'Protocol client sees duplicated deposited arrows after restart');
      evidence.snapshots.push(snapshot); return { before: preRestart, after: snapshot, currentPeriod,
        clientArrowCount: count(bot, 'arrow'), inventory };
    });
    await stage('second server cleanly stops', async () => { bot.end('acceptance stage complete'); await bot.finishTranscript(); bot = null; await server.stop(); return { cleanExit: true }; });
    evidence.status = 'passed'; evidence.finishedAt = new Date().toISOString(); save();
    console.log(`PASS: ${evidence.steps.length} real protocol/client checks. Evidence: ${path.join(directory, 'evidence.json')}`);
  } catch (error) {
    evidence.status = 'failed'; evidence.error = error.stack; evidence.finishedAt = new Date().toISOString(); save();
    console.error(`FAIL: ${error.stack}\nRecent server output:\n${server.lines.slice(-20).join('\n')}\nEvidence: ${path.join(directory, 'evidence.json')}`);
    process.exitCode = 1;
  } finally {
    if (bot) { try { bot.end('acceptance cleanup'); await bot.finishTranscript(); } catch {} }
    if (server.child) {
      try { await server.stop(); }
      catch (error) {
        evidence.status = 'failed'; process.exitCode = 1;
        if (error !== server.error) {
          console.error(`Cleanup: ${error.message}`); evidence.cleanupError = error.stack; save();
        }
      }
    }
    fs.writeFileSync(path.join(options.runtime, 'client-acceptance', 'latest.json'), JSON.stringify({ directory, status: evidence.status }, null, 2) + '\n');
  }
}

module.exports = { Paper, connect, botMessage, properties, configuredNumber, hash, plain, requireFile, waitUntil, withTimeout, argumentsOf,
  assertResourceHeadroom, assertPaperLaunchLimits, paperHeapLimitBytes, freeCommitMemoryBytesAsync, RESOURCE_LIMITS };
if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1; });
